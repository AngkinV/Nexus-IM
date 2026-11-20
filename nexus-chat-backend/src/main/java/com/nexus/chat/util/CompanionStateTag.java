package com.nexus.chat.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one-line state marker a character writes ahead of her reply:
 *
 * <pre>&lt;state&gt;{"mood":"calm","motion":"listen","doing":"把杯子往前推了推"}&lt;/state&gt;</pre>
 *
 * It is how the stage knows what she is doing without anyone guessing: the model
 * says it, the server cuts it out of the text before the user sees it, and the
 * client receives it as its own event. A reply without a marker simply carries
 * no state — nothing is inferred from the prose.
 */
public final class CompanionStateTag {

    public static final String OPEN = "<state>";
    public static final String CLOSE = "</state>";

    /** Closed vocabularies: the stage can only act on values it has a pose for. */
    public static final Set<String> MOODS = Set.of(
            "calm", "warm", "happy", "shy", "sad", "worried", "angry", "surprised", "tired", "thoughtful");
    public static final Set<String> MOTIONS = Set.of(
            "idle", "listen", "think", "nod", "lean_in", "look_away");
    public static final Set<String> GESTURES = Set.of(
            "rest", "chin", "sip", "wipe", "wave", "shrug", "cover", "cross", "tuck");

    private static final int MAX_DOING = 60;
    /** A marker longer than this is not a marker; stop holding text back for it. */
    private static final int MAX_TAG = 400;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LOOSE_PAIR =
            Pattern.compile("(mood|motion|doing|gesture)\\s*[\"']?\\s*[:=：]\\s*[\"'“]?([^\"'”,，|}\\n]+)");
    private static final Pattern BRACE_THEN_NEWLINE = Pattern.compile("\\}\\s*\\n");

    private CompanionStateTag() {}

    /** What she reported. Any field may be null; all null means "no state". */
    public record State(String mood, String motion, String doing, String gesture) {
        public boolean isEmpty() {
            return mood == null && motion == null && doing == null && gesture == null;
        }

        public Map<String, String> toMap() {
            Map<String, String> out = new LinkedHashMap<>();
            if (mood != null) out.put("mood", mood);
            if (motion != null) out.put("motion", motion);
            if (doing != null) out.put("doing", doing);
            if (gesture != null) out.put("gesture", gesture);
            return out;
        }
    }

    /** The reply with the marker removed, plus the state it carried (or null). */
    public record Parsed(String text, State state) {}

    /** Parse a complete reply in one go. */
    public static Parsed parse(String raw) {
        Filter filter = new Filter(piece -> {});
        if (raw != null) filter.accept(raw);
        filter.finish();
        return new Parsed(filter.visibleText(), filter.state());
    }

    /** Serialize a state back into marker form, for replaying history to the model. */
    public static String render(State state) {
        if (state == null || state.isEmpty()) return "";
        try {
            return OPEN + MAPPER.writeValueAsString(state.toMap()) + CLOSE;
        } catch (Exception e) {
            return "";
        }
    }

    public static String toJson(State state) {
        if (state == null || state.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(state.toMap());
        } catch (Exception e) {
            return null;
        }
    }

    public static State fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        return parseBody(json);
    }

    /** Read a marker body: strict JSON first, then a forgiving key/value scan. */
    static State parseBody(String body) {
        if (body == null) return null;
        String mood = null;
        String motion = null;
        String doing = null;
        String gesture = null;
        String trimmed = body.trim();
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (node != null && node.isObject()) {
                mood = text(node, "mood");
                motion = text(node, "motion");
                doing = text(node, "doing");
                gesture = text(node, "gesture");
            }
        } catch (Exception ignored) {
            Matcher matcher = LOOSE_PAIR.matcher(trimmed);
            while (matcher.find()) {
                String value = matcher.group(2).trim();
                switch (matcher.group(1)) {
                    case "mood" -> mood = value;
                    case "motion" -> motion = value;
                    case "gesture" -> gesture = value;
                    default -> doing = value;
                }
            }
        }
        mood = normalize(mood, MOODS);
        motion = normalize(motion, MOTIONS);
        gesture = normalize(gesture, GESTURES);
        if (doing != null) {
            doing = doing.trim();
            if (doing.isEmpty()) doing = null;
            else if (doing.length() > MAX_DOING) doing = doing.substring(0, MAX_DOING);
        }
        State state = new State(mood, motion, doing, gesture);
        return state.isEmpty() ? null : state;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String normalize(String value, Set<String> allowed) {
        if (value == null) return null;
        String key = value.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        return allowed.contains(key) ? key : null;
    }

    /**
     * Streaming filter. Text goes in as the provider sends it; only what the user
     * should read comes out. A marker may be split across any number of chunks, so
     * a trailing fragment that could still turn into {@code <state>} is held back
     * until the next chunk settles it.
     */
    public static final class Filter {
        private final Consumer<String> out;
        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder visible = new StringBuilder();
        private boolean inTag;
        /** Drop whitespace at the very start and right after a marker. */
        private boolean trimLeading = true;
        private State state;

        public Filter(Consumer<String> out) {
            this.out = out;
        }

        public void accept(String piece) {
            if (piece == null || piece.isEmpty()) return;
            buffer.append(piece);
            drain(false);
        }

        /** Call once the provider is done: releases anything still held back. */
        public void finish() {
            drain(true);
        }

        public String visibleText() {
            return visible.toString().stripTrailing();
        }

        public State state() {
            return state;
        }

        private void drain(boolean last) {
            while (true) {
                if (inTag) {
                    int close = buffer.indexOf(CLOSE);
                    int end = close;
                    int skip = CLOSE.length();
                    // A model that forgets the closing tag still ends the line.
                    Matcher brace = BRACE_THEN_NEWLINE.matcher(buffer);
                    if (brace.find() && (close < 0 || brace.start() < close)) {
                        end = brace.start() + 1;
                        skip = 0;
                    }
                    if (end < 0) {
                        if (last) {
                            absorb(buffer.toString());
                            buffer.setLength(0);
                        } else if (buffer.length() > MAX_TAG) {
                            // Not a marker after all. Give the text back rather
                            // than swallow a whole reply.
                            inTag = false;
                            emit(take(buffer.length()));
                        }
                        return;
                    }
                    absorb(buffer.substring(0, end));
                    buffer.delete(0, end + skip);
                    inTag = false;
                    trimLeading = true;
                    continue;
                }

                int open = buffer.indexOf(OPEN);
                int stray = buffer.indexOf(CLOSE);
                // A closing tag with no marker in front of it: the marker ended
                // on its line break and this is what was left over. Never text.
                if (stray >= 0 && (open < 0 || stray < open)) {
                    String before = take(stray);
                    buffer.delete(0, CLOSE.length());
                    emit(before);
                    trimLeading = true;
                    continue;
                }
                if (open >= 0) {
                    String before = take(open);
                    buffer.delete(0, OPEN.length());
                    inTag = true;
                    emit(before);
                    continue;
                }

                int keep = last ? 0 : partialOpenLength();
                int flush = buffer.length() - keep;
                if (flush > 0) emit(take(flush));
                return;
            }
        }

        /** Length of the buffer's tail that could still become either tag. */
        private int partialOpenLength() {
            int max = Math.min(buffer.length(), CLOSE.length() - 1);
            for (int len = max; len > 0; len--) {
                String tail = buffer.substring(buffer.length() - len);
                if (OPEN.startsWith(tail) || CLOSE.startsWith(tail)) return len;
            }
            return 0;
        }

        private void absorb(String body) {
            State parsed = parseBody(body);
            if (parsed != null) state = parsed;
        }

        /** Remove and return the first {@code length} chars of the buffer. */
        private String take(int length) {
            String head = buffer.substring(0, length);
            buffer.delete(0, length);
            return head;
        }

        /**
         * Hand text to the reader. The buffer is always advanced before this is
         * called and the text only counts as visible once delivery returned, so
         * if the consumer throws (the reader left) nothing is delivered twice and
         * nothing the reader never received is recorded as said.
         */
        private void emit(String text) {
            if (text.isEmpty()) return;
            if (trimLeading) {
                text = text.stripLeading();
                if (text.isEmpty()) return;
                trimLeading = false;
            }
            out.accept(text);
            visible.append(text);
        }
    }
}
