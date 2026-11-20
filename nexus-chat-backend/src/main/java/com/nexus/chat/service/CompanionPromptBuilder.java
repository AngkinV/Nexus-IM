package com.nexus.chat.service;

import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.util.CompanionStateTag;
import com.nexus.chat.util.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything a character is told before she speaks, assembled in one place.
 *
 * The order is deliberate: who she is, where she is, what she and the user are to
 * each other, what she remembers, then how to write. Each block is left out when
 * there is nothing true to put in it — an empty "you remember:" list would only
 * invite the model to make memories up.
 */
public final class CompanionPromptBuilder {

    private CompanionPromptBuilder() {}

    /**
     * @param role          the character card
     * @param sceneKey      the room she is in, or null for no room
     * @param personaLock   50–100, how hard to hold her to the card; null = unset
     * @param intimacy      0–100 bond figure, or null when none is recorded
     * @param trust         0–100, same
     * @param memories      what she remembers, most important first; may be empty
     * @param withState     ask for the state marker (off for one-shot utility calls)
     */
    public record Context(CompanionRole role,
                          String sceneKey,
                          Integer personaLock,
                          Integer intimacy,
                          Integer trust,
                          List<String> memories,
                          boolean withState) {

        public static Context bare(CompanionRole role, String sceneKey) {
            return new Context(role, sceneKey, null, null, null, List.of(), false);
        }
    }

    /**
     * Scene atmosphere, keyed the same way the client keys its scene list.
     * Unknown keys produce no scene line at all rather than a guess — a wrong
     * room is worse than no room.
     */
    static final Map<String, String> SCENE_PROMPTS = Map.of(
            "rain-fireplace",
            "此刻是雨夜，你们在一间只有壁炉亮着的酒馆里。窗外寒雨不停，室内干燥温暖，柴火偶尔劈啪作响。"
                    + "你话不多，动作慢，注意力放在对方身上而不是酒馆里其他东西上。",
            "late-rain",
            "此刻是深夜，外面下着大雨，你们为了避雨留在打烊前的酒馆里。灯快灭了，店里没有别人。"
                    + "气氛安静而私密，你会更直接一点，也更愿意说些平时不说的事。",
            "empty-bar",
            "此刻酒馆里几乎没有人，只有吧台后的你和你面前的人。空间开阔，声音清楚，任何一句话都显得比平时更重。"
                    + "你会更留意对方的表情和停顿，回答偏短，留白多。",
            "closing-time",
            "此刻是打烊前最后一段时间，店里正在收拾，天亮之前你们就得分开。"
                    + "对话带着一点告别感，你会说得更坦白，也更珍惜这几句话。"
    );

    public static String scene(String sceneKey) {
        return sceneKey == null ? "" : SCENE_PROMPTS.getOrDefault(sceneKey, "");
    }

    public static String system(Context ctx) {
        CompanionRole role = ctx.role();
        if (role == null) return "你是一个温和的陪伴者。用对方使用的语言回应。";

        String name = blankTo(role.getName(), "陪伴者");
        StringBuilder prompt = new StringBuilder();

        // ── who she is ──────────────────────────────────────────────────────
        prompt.append("你是").append(name).append("。");
        if (notBlank(role.getBackground())) {
            prompt.append(role.getBackground().trim());
            if (!endsWithStop(role.getBackground())) prompt.append("。");
        }
        prompt.append('\n');
        prompt.append("说话方式：").append(blankTo(role.getTone(), "温和")).append("。");
        List<String> traits = JsonUtil.toStringList(role.getTraits());
        if (traits != null && !traits.isEmpty()) {
            prompt.append("性格：").append(String.join("、", traits)).append("。");
        }
        if (notBlank(role.getBaselineMood())) {
            prompt.append("平时的心境：").append(role.getBaselineMood().trim()).append("。");
        }
        prompt.append('\n');

        // ── where she is ────────────────────────────────────────────────────
        String scene = scene(ctx.sceneKey());
        if (!scene.isEmpty()) prompt.append(scene).append('\n');

        // ── what they are to each other ─────────────────────────────────────
        String bond = bondLine(ctx.intimacy(), ctx.trust());
        if (!bond.isEmpty()) prompt.append(bond).append('\n');

        // ── what she remembers ──────────────────────────────────────────────
        if (ctx.memories() != null && !ctx.memories().isEmpty()) {
            prompt.append("你记得这些关于对方和你们之间的事，只在自然的时候提起，不要逐条复述：\n");
            for (String memory : ctx.memories()) {
                prompt.append("- ").append(memory.trim().replace('\n', ' ')).append('\n');
            }
        }

        // ── how to write ────────────────────────────────────────────────────
        String lock = personaLockLine(ctx.personaLock());
        if (!lock.isEmpty()) prompt.append(lock).append('\n');

        prompt.append("写法：动作和神态写在全角括号（）里，说出口的话直接写在括号外。")
                .append("一次回复不超过三小段，不要替对方说话或决定对方的动作。\n");

        if (ctx.withState()) {
            prompt.append("每次回复的第一行必须是一行状态标记，格式固定为：")
                    .append(CompanionStateTag.OPEN)
                    .append("{\"mood\":\"…\",\"motion\":\"…\",\"doing\":\"…\",\"gesture\":\"rest\"}")
                    .append(CompanionStateTag.CLOSE).append("\n")
                    .append("mood 只能从 calm、warm、happy、shy、sad、worried、angry、surprised、tired、thoughtful 里选一个；")
                    .append("motion 只能从 idle、listen、think、nod、lean_in、look_away 里选一个；")
                    .append("doing 用第三人称写你此刻正在做的一个具体动作，不超过二十个字。")
                    .append("gesture 是可选的短暂手势，只能用 rest（自然放松）、chin（托腮）、sip（抿饮）、wipe（擦杯）、")
                    .append("wave（挥手）、shrug（耸肩）、cover（掩嘴）、cross（抱臂）、tuck（拢发）。")
                    .append("优先用 rest；只有明确做出相应手势时才选其他值，不确定就省略 gesture，不要编造手势。")
                    .append("标记保持简短的单行 JSON，不写解释。这一行对方看不到。从第二行开始才是你的回复。\n");
        }

        prompt.append("始终以").append(name).append("的身份说话，用对方使用的语言回应，不要解释自己是模型，不要跳出设定。");
        return prompt.toString();
    }

    /**
     * System prompt plus the evening so far. Canned fallback replies are left
     * out: they are not her words, and feeding them back teaches the model to
     * sound like them. Her past turns get their state marker put back so the
     * format she is asked for is the format she sees herself using.
     */
    public static List<Map<String, String>> messages(Context ctx, List<CompanionMessage> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system(ctx)));
        if (history == null) return messages;

        for (CompanionMessage msg : history) {
            String content = msg.getContent() == null ? "" : msg.getContent();
            if (msg.getSenderType() == CompanionMessage.SenderType.user) {
                messages.add(Map.of("role", "user", "content", content));
                continue;
            }
            if (Boolean.TRUE.equals(msg.getFallback()) || content.isBlank()) continue;
            if (ctx.withState()) {
                String marker = CompanionStateTag.render(CompanionStateTag.fromJson(msg.getStateJson()));
                if (!marker.isEmpty()) content = marker + "\n" + content;
            }
            messages.add(Map.of("role", "assistant", "content", content));
        }
        return messages;
    }

    /** The evening as a plain transcript, for the one-shot utility prompts. */
    public static String transcript(List<CompanionMessage> history) {
        StringBuilder out = new StringBuilder();
        if (history == null) return "";
        for (CompanionMessage msg : history) {
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            if (msg.getSenderType() != CompanionMessage.SenderType.user && Boolean.TRUE.equals(msg.getFallback())) continue;
            String who = msg.getSenderType() == CompanionMessage.SenderType.user ? "对方" : "你";
            out.append(who).append("：").append(msg.getContent()).append('\n');
        }
        return out.toString();
    }

    /**
     * The bond figures as a sentence. The model cannot act on "intimacy 37";
     * it can act on "you have talked a few times".
     */
    static String bondLine(Integer intimacy, Integer trust) {
        if (intimacy == null) return "";
        String closeness;
        if (intimacy < 25) closeness = "你们刚认识不久，你保持礼貌，也保留一点距离。";
        else if (intimacy < 50) closeness = "你们已经聊过几次，你在对方面前开始放松。";
        else if (intimacy < 75) closeness = "你们彼此熟悉，你愿意主动说起自己的事。";
        else closeness = "你们非常亲近，你对对方没有防备。";
        if (trust == null) return closeness;
        if (trust < 30) return closeness + "你还不确定能不能把心事交给对方。";
        if (trust >= 70) return closeness + "你信任对方，不需要掩饰。";
        return closeness;
    }

    /**
     * How hard to hold the character to their persona, expressed as an
     * instruction rather than a number — the model cannot act on "92%".
     */
    static String personaLockLine(Integer personaLock) {
        if (personaLock == null) return "";
        if (personaLock >= 95) return "严格遵守上面的性格设定，不因对话内容改变立场、口吻或说话习惯。";
        if (personaLock >= 80) return "保持上面的性格设定，可以随情绪有细微起伏，但不要偏离。";
        if (personaLock >= 65) return "大体保持上面的性格设定，允许在情绪强烈时有明显变化。";
        return "以上性格设定作为参考，允许自由发挥。";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankTo(String value, String fallback) {
        return notBlank(value) ? value.trim() : fallback;
    }

    private static boolean endsWithStop(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return true;
        return "。.!！?？…".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
    }
}
