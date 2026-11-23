package com.nexus.chat.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompanionStateTagTest {

    private static final String TAG =
            "<state>{\"mood\":\"warm\",\"motion\":\"lean_in\",\"doing\":\"把杯子往前推了推\",\"gesture\":\"rest\"}</state>";

    @Test
    void leadingMarkerIsCutOutAndParsed() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse(TAG + "\n（她抬起头。）你来了。");
        assertEquals("（她抬起头。）你来了。", parsed.text());
        assertEquals("warm", parsed.state().mood());
        assertEquals("lean_in", parsed.state().motion());
        assertEquals("把杯子往前推了推", parsed.state().doing());
        assertEquals("rest", parsed.state().gesture());
    }

    @Test
    void replyWithoutMarkerHasNoState() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse("你来了。坐吧。");
        assertEquals("你来了。坐吧。", parsed.text());
        assertNull(parsed.state());
    }

    @Test
    void trailingMarkerIsAlsoRemoved() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse("坐吧。\n" + TAG);
        assertEquals("坐吧。", parsed.text());
        assertEquals("warm", parsed.state().mood());
    }

    @Test
    void valuesOutsideTheVocabularyAreDropped() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse(
                "<state>{\"mood\":\"ecstatic\",\"motion\":\"backflip\",\"doing\":\"擦杯子\"}</state>好。");
        assertEquals("好。", parsed.text());
        assertNull(parsed.state().mood());
        assertNull(parsed.state().motion());
        assertEquals("擦杯子", parsed.state().doing());
    }

    @Test
    void markerWithNothingUsableMeansNoState() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse("<state>{\"mood\":\"??\"}</state>好。");
        assertEquals("好。", parsed.text());
        assertNull(parsed.state());
    }

    @Test
    void looseKeyValueBodyIsAccepted() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse(
                "<state>mood=sad, motion=look_away, doing=望着窗外, gesture=chin</state>\n嗯。");
        assertEquals("嗯。", parsed.text());
        assertEquals("sad", parsed.state().mood());
        assertEquals("look_away", parsed.state().motion());
        assertEquals("望着窗外", parsed.state().doing());
        assertEquals("chin", parsed.state().gesture());
    }

    @Test
    void missingClosingTagEndsAtTheLineBreak() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse(
                "<state>{\"mood\":\"calm\",\"motion\":\"nod\"}\n她点了点头。");
        assertEquals("她点了点头。", parsed.text());
        assertEquals("nod", parsed.state().motion());
    }

    @Test
    void closingTagOnItsOwnLineDoesNotLeak() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse(
                "<state>{\"mood\":\"calm\"}\n</state>\n她点了点头。");
        assertEquals("她点了点头。", parsed.text());
        assertEquals("calm", parsed.state().mood());
    }

    @Test
    void overlongUnclosedMarkerGivesTheTextBack() {
        String body = "x".repeat(500);
        List<String> out = new ArrayList<>();
        CompanionStateTag.Filter filter = new CompanionStateTag.Filter(out::add);
        filter.accept("<state>" + body);
        filter.accept("后面的话。");
        filter.finish();
        assertTrue(filter.visibleText().endsWith("后面的话。"));
        assertNull(filter.state());
    }

    /** The marker must survive being cut at every possible chunk boundary. */
    @Test
    void streamingMatchesOneShotAtEveryChunkSize() {
        String raw = TAG + "\n（她抬起头，看了你一眼。）\n今晚 <b>不</b> 赶时间，坐 < 一会儿吧。";
        CompanionStateTag.Parsed whole = CompanionStateTag.parse(raw);
        assertEquals("（她抬起头，看了你一眼。）\n今晚 <b>不</b> 赶时间，坐 < 一会儿吧。", whole.text());

        for (int size = 1; size <= raw.length(); size++) {
            StringBuilder streamed = new StringBuilder();
            CompanionStateTag.Filter filter = new CompanionStateTag.Filter(streamed::append);
            for (int i = 0; i < raw.length(); i += size) {
                filter.accept(raw.substring(i, Math.min(raw.length(), i + size)));
                assertFalse(streamed.toString().contains("<state"), "marker leaked at chunk size " + size);
                assertFalse(streamed.toString().contains("mood"), "marker body leaked at chunk size " + size);
                assertFalse(streamed.toString().contains("gesture"), "gesture leaked at chunk size " + size);
            }
            filter.finish();
            assertEquals(whole.text(), filter.visibleText(), "chunk size " + size);
            assertEquals(whole.text(), streamed.toString().stripTrailing(), "emitted text, chunk size " + size);
            assertEquals(whole.state(), filter.state(), "chunk size " + size);
        }
    }

    @Test
    void textTheReaderNeverReceivedIsNotRecordedAsSaid() {
        int[] calls = {0};
        CompanionStateTag.Filter filter = new CompanionStateTag.Filter(piece -> {
            if (++calls[0] >= 2) throw new IllegalStateException("gone");
        });
        filter.accept("第一句。");
        assertThrows(IllegalStateException.class, () -> filter.accept("第二句。"));
        assertThrows(IllegalStateException.class, () -> {
            filter.accept("第三句。");
            filter.finish();
        });
        assertEquals("第一句。", filter.visibleText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rest", "chin", "sip", "wipe", "wave", "shrug", "cover", "cross", "tuck"})
    void gestureOnlyStateIsNormalizedAndRoundTrips(String gesture) {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse(
                "<state>{\"gesture\":\" " + gesture.toUpperCase(Locale.ROOT) + " \"}</state>嗯。");
        CompanionStateTag.State state = parsed.state();
        assertEquals("嗯。", parsed.text());
        assertEquals(new CompanionStateTag.State(null, null, null, gesture), state);
        assertFalse(state.isEmpty());
        assertEquals(Map.of("gesture", gesture), state.toMap());
        assertEquals(state, CompanionStateTag.fromJson(CompanionStateTag.toJson(state)));
        assertEquals(state, CompanionStateTag.parse(CompanionStateTag.render(state)).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"dance\"", "\"\"", "\"   \"", "null", "42", "true", "{}", "[\"wave\"]"})
    void unusableGestureIsDroppedWithoutLosingOtherFields(String value) {
        CompanionStateTag.State state = CompanionStateTag.fromJson(
                "{\"mood\":\"calm\",\"gesture\":" + value + "}");
        assertEquals(Map.of("mood", "calm"), state.toMap());
        assertNull(state.gesture());
        CompanionStateTag.Parsed gestureOnly = CompanionStateTag.parse(
                "<state>{\"gesture\":" + value + "}</state>好。");
        assertEquals("好。", gestureOnly.text());
        assertNull(gestureOnly.state());
    }

    @Test
    void historicalStateWithoutGestureStaysAbsent() {
        String json = "{\"mood\":\"calm\",\"motion\":\"listen\",\"doing\":\"听你说话\"}";
        CompanionStateTag.State state = CompanionStateTag.fromJson(json);
        assertNull(state.gesture());
        assertFalse(state.toMap().containsKey("gesture"));
        assertEquals(json, CompanionStateTag.toJson(state));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gesture= WAVE", "{'gesture':'wave'}", "gesture：wave", "{gesture:wave,}"})
    void looseGestureOnlyBodyIsAccepted(String body) {
        assertEquals(new CompanionStateTag.State(null, null, null, "wave"), CompanionStateTag.fromJson(body));
    }

    @Test
    void unknownLooseGestureDoesNotBecomeDoing() {
        assertNull(CompanionStateTag.fromJson("gesture=dance"));
        assertEquals(Map.of("mood", "calm"), CompanionStateTag.fromJson("mood=calm, gesture=dance").toMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"gesture\":\"wave\"}", "gesture=chin", "{\"mood\":\"calm\",\"gesture\":\"dance\"}"})
    void gestureMarkerSurvivesEveryTwoChunkSplit(String body) {
        String raw = "<state>" + body + "</state>\n你好。";
        CompanionStateTag.Parsed whole = CompanionStateTag.parse(raw);
        for (int split = 0; split <= raw.length(); split++) {
            StringBuilder streamed = new StringBuilder();
            CompanionStateTag.Filter filter = new CompanionStateTag.Filter(streamed::append);
            filter.accept(raw.substring(0, split));
            filter.accept(raw.substring(split));
            filter.finish();
            assertEquals("你好。", streamed.toString(), "split at " + split);
            assertEquals(whole.state(), filter.state(), "split at " + split);
        }
    }

    @Test
    void cutShortMarkerIsStoredBesideTheStateButIsNeverPartOfIt() {
        CompanionStateTag.State state = new CompanionStateTag.State("shy", null, null, null);
        String stored = CompanionStateTag.toJson(state, true);
        assertTrue(CompanionStateTag.isPartial(stored));
        assertEquals(state, CompanionStateTag.fromJson(stored));
        assertEquals("<state>{\"mood\":\"shy\"}</state>", CompanionStateTag.render(CompanionStateTag.fromJson(stored)));

        String markerOnly = CompanionStateTag.toJson(null, true);
        assertTrue(CompanionStateTag.isPartial(markerOnly), "a fragment without any state is still marked");
        assertNull(CompanionStateTag.fromJson(markerOnly));

        assertFalse(CompanionStateTag.isPartial(CompanionStateTag.toJson(state, false)));
        assertFalse(CompanionStateTag.isPartial(CompanionStateTag.toJson(state)));
        assertFalse(CompanionStateTag.isPartial(null));
        assertFalse(CompanionStateTag.isPartial("mood=calm"));
        assertNull(CompanionStateTag.toJson(null, false));
    }

    @Test
    void stateIsDeliveredBeforeFollowingTextAtEveryChunkSize() {
        String raw = TAG + "\n你好。";
        for (int size = 1; size <= raw.length(); size++) {
            List<String> events = new ArrayList<>();
            CompanionStateTag.Filter filter = new CompanionStateTag.Filter(
                    piece -> events.add("delta:" + piece), cue -> events.add("state:" + cue.mood()));
            for (int offset = 0; offset < raw.length(); offset += size) {
                filter.accept(raw.substring(offset, Math.min(raw.length(), offset + size)));
            }
            filter.finish();
            assertEquals("state:warm", events.get(0), "chunk size " + size);
            assertEquals(1, events.stream().filter(event -> event.startsWith("state:")).count());
            assertEquals("你好。", filter.visibleText());
        }
    }

    @Test
    void duplicateMarkersDoNotChangeTheAlreadyDeliveredState() {
        List<CompanionStateTag.State> cues = new ArrayList<>();
        CompanionStateTag.Filter filter = new CompanionStateTag.Filter(piece -> {}, cues::add);
        filter.accept(TAG + "好。<state>{\"mood\":\"angry\"}</state>再见。");
        filter.finish();
        assertEquals(1, cues.size());
        assertEquals(cues.get(0), filter.state());
        assertEquals("warm", filter.state().mood());
        assertEquals("好。再见。", filter.visibleText());
    }

    @Test
    void renderRoundTrips() {
        CompanionStateTag.State state = new CompanionStateTag.State("shy", "look_away", "低头擦杯子", "wipe");
        assertEquals(state, CompanionStateTag.parse(CompanionStateTag.render(state) + "嗯。").state());
        assertEquals(state, CompanionStateTag.fromJson(CompanionStateTag.toJson(state)));
        assertEquals("", CompanionStateTag.render(null));
        CompanionStateTag.State empty = new CompanionStateTag.State(null, null, null, null);
        assertTrue(empty.isEmpty());
        assertTrue(empty.toMap().isEmpty());
        assertEquals("", CompanionStateTag.render(empty));
        assertNull(CompanionStateTag.toJson(empty));
    }
}
