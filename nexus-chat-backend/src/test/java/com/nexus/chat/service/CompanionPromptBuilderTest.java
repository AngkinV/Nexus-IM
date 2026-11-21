package com.nexus.chat.service;

import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.util.CompanionStateTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompanionPromptBuilderTest {

    private static CompanionRole role() {
        CompanionRole role = new CompanionRole();
        role.setName("艾瑞斯");
        role.setTone("柔和、慢速");
        role.setTraits("[\"温柔\",\"慢节奏\"]");
        role.setBaselineMood("安稳");
        return role;
    }

    private static CompanionMessage msg(CompanionMessage.SenderType who, String content, boolean fallback, String state) {
        CompanionMessage m = new CompanionMessage();
        m.setSenderType(who);
        m.setContent(content);
        m.setFallback(fallback);
        m.setStateJson(state);
        return m;
    }

    @Test
    void theCutShortMarkerNeverReachesThePrompt() {
        List<Map<String, String>> messages = CompanionPromptBuilder.messages(
                new CompanionPromptBuilder.Context(role(), null, null, null, null, List.of(), true),
                List.of(msg(CompanionMessage.SenderType.role, "半句", false,
                                CompanionStateTag.toJson(new CompanionStateTag.State("shy", null, null, null), true)),
                        msg(CompanionMessage.SenderType.role, "又半句", false, CompanionStateTag.toJson(null, true))));
        assertEquals("<state>{\"mood\":\"shy\"}</state>\n半句", messages.get(1).get("content"));
        assertEquals("又半句", messages.get(2).get("content"));
    }

    @Test
    void traitsAreWrittenAsWordsNotAsRawJson() {
        String prompt = CompanionPromptBuilder.system(CompanionPromptBuilder.Context.bare(role(), null));
        assertTrue(prompt.contains("性格：温柔、慢节奏。"), prompt);
        assertFalse(prompt.contains("[\""), "raw JSON leaked into the prompt");
    }

    @Test
    void blocksWithNothingTrueToSayAreLeftOut() {
        String prompt = CompanionPromptBuilder.system(CompanionPromptBuilder.Context.bare(role(), "no-such-room"));
        assertFalse(prompt.contains("你记得"));
        assertFalse(prompt.contains("你们"), "no bond figures were given, so no bond line");
        assertFalse(prompt.contains("<state>"));
        assertFalse(prompt.contains("酒馆"), "an unknown scene key must not produce a room");
    }

    @Test
    void fullContextCarriesCardSceneBondAndMemories() {
        CompanionRole role = role();
        role.setBackground("她在北方的雪原上守过七年的灯");
        String prompt = CompanionPromptBuilder.system(new CompanionPromptBuilder.Context(
                role, "rain-fireplace", 96, 62, 80, List.of("对方怕冷", "约好下次带琴来"), true));

        assertTrue(prompt.contains("她在北方的雪原上守过七年的灯。"));
        assertTrue(prompt.contains("壁炉"));
        assertTrue(prompt.contains("你们彼此熟悉"));
        assertTrue(prompt.contains("你信任对方"));
        assertTrue(prompt.contains("- 对方怕冷\n- 约好下次带琴来\n"));
        assertTrue(prompt.contains("严格遵守上面的性格设定"));
        assertTrue(prompt.contains("<state>"));
        assertTrue(prompt.contains("用对方使用的语言回应"));
        assertFalse(prompt.contains("62"), "the bond is a sentence, not a number");
    }

    @Test
    void gestureInstructionsAreOptionalShortAndUseTheSupportedVocabulary() {
        String prompt = CompanionPromptBuilder.system(new CompanionPromptBuilder.Context(
                role(), null, null, null, null, List.of(), true));

        for (String label : List.of("rest（自然放松）", "chin（托腮）", "sip（抿饮）", "wipe（擦杯）",
                "wave（挥手）", "shrug（耸肩）", "cover（掩嘴）", "cross（抱臂）", "tuck（拢发）")) {
            assertTrue(prompt.contains(label), label);
        }
        assertTrue(prompt.contains("gesture 是可选的短暂手势"));
        assertTrue(prompt.contains("优先用 rest"));
        assertTrue(prompt.contains("不确定就省略 gesture，不要编造手势"));
        assertTrue(prompt.contains("简短的单行 JSON"));
    }

    @Test
    void bondLineFollowsTheFigures() {
        assertEquals("", CompanionPromptBuilder.bondLine(null, 50));
        assertTrue(CompanionPromptBuilder.bondLine(10, 10).contains("刚认识"));
        assertTrue(CompanionPromptBuilder.bondLine(10, 10).contains("不确定"));
        assertTrue(CompanionPromptBuilder.bondLine(90, 50).startsWith("你们非常亲近"));
    }

    @Test
    void cannedRepliesNeverReachTheModelAndStateIsReplayed() {
        List<CompanionMessage> history = List.of(
                msg(CompanionMessage.SenderType.user, "今晚有点累", false, null),
                msg(CompanionMessage.SenderType.role, "我在的。你可以继续说，我会认真听。", true, null),
                msg(CompanionMessage.SenderType.user, "你在听吗", false, null),
                msg(CompanionMessage.SenderType.role, "（她点了点头。）在听。", false,
                        "{\"mood\":\"warm\",\"motion\":\"nod\",\"gesture\":\"chin\"}"));

        List<Map<String, String>> withState = CompanionPromptBuilder.messages(
                new CompanionPromptBuilder.Context(role(), null, null, null, null, List.of(), true), history);

        assertEquals(List.of("system", "user", "user", "assistant"),
                withState.stream().map(m -> m.get("role")).toList());
        String assistant = withState.get(3).get("content");
        assertTrue(assistant.startsWith("<state>{\"mood\":\"warm\",\"motion\":\"nod\",\"gesture\":\"chin\"}</state>\n"), assistant);
        assertTrue(assistant.endsWith("在听。"));

        List<Map<String, String>> plain = CompanionPromptBuilder.messages(
                CompanionPromptBuilder.Context.bare(role(), null), history);
        assertEquals("（她点了点头。）在听。", plain.get(3).get("content"));

        String transcript = CompanionPromptBuilder.transcript(history);
        assertFalse(transcript.contains("我会认真听"));
        assertTrue(transcript.contains("对方：今晚有点累\n"));
    }

    @Test
    void oldHistoryWithoutGestureIsReplayedWithoutInventingOne() {
        List<Map<String, String>> messages = CompanionPromptBuilder.messages(
                new CompanionPromptBuilder.Context(role(), null, null, null, null, List.of(), true),
                List.of(msg(CompanionMessage.SenderType.role, "在听。", false, "{\"mood\":\"warm\",\"motion\":\"nod\"}")));
        assertEquals("<state>{\"mood\":\"warm\",\"motion\":\"nod\"}</state>\n在听。", messages.get(1).get("content"));
    }

    @Test
    void guestCardIsReadByLabelNotByPosition() {
        CompanionModelService.GeneratedRole card = CompanionModelService.parseGeneratedRole(
                "好的，以下是角色：\n"
                        + "1. 名字：柯林\n"
                        + "来历：旧城修表铺的学徒，躲雨进来的。\n"
                        + "- 性格: 寡言、细致、固执\n"
                        + "语气：慢，字斟句酌\n"
                        + "此刻：低头擦着一只怀表\n"
                        + "开场：（他抬了抬眼）外面雨不小。");
        assertNotNull(card);
        assertEquals("柯林", card.name());
        assertEquals("寡言、细致、固执", card.traits());
        assertEquals("慢，字斟句酌", card.tone());
        assertEquals("低头擦着一只怀表", card.presence());
        assertEquals("旧城修表铺的学徒，躲雨进来的。", card.background());
        assertEquals("（他抬了抬眼）外面雨不小。", card.greeting());
    }

    @Test
    void guestCardWithoutTheRequiredLinesIsRejected() {
        assertNull(CompanionModelService.parseGeneratedRole("柯林\n寡言\n慢\n擦怀表"));
        assertNull(CompanionModelService.parseGeneratedRole("名字：柯林\n来历：学徒"));
        assertNotNull(CompanionModelService.parseGeneratedRole("名字：柯林\n性格：寡言\n语气：慢"));
    }
}
