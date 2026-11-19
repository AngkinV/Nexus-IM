package com.nexus.chat.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompanionStateTagTest {

    private static final String TAG =
            "<state>{\"mood\":\"warm\",\"motion\":\"lean_in\",\"doing\":\"把杯子往前推了推\"}</state>";

    @Test
    void leadingMarkerIsCutOutAndParsed() {
        CompanionStateTag.Parsed parsed = CompanionStateTag.parse(TAG + "\n（她抬起头。）你来了。");
        assertEquals("（她抬起头。）你来了。", parsed.text());
        assertEquals("warm", parsed.state().mood());
        assertEquals("lean_in", parsed.state().motion());
        assertEquals("把杯子往前推了推", parsed.state().doing());
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
                "<state>mood=sad, motion=look_away, doing=望着窗外</state>\n嗯。");
        assertEquals("嗯。", parsed.text());
        assertEquals("sad", parsed.state().mood());
        assertEquals("look_away", parsed.state().motion());
        assertEquals("望着窗外", parsed.state().doing());
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

    @Test
    void renderRoundTrips() {
        CompanionStateTag.State state = new CompanionStateTag.State("shy", "look_away", "低头擦杯子");
        assertEquals(state, CompanionStateTag.parse(CompanionStateTag.render(state) + "嗯。").state());
        assertEquals(state, CompanionStateTag.fromJson(CompanionStateTag.toJson(state)));
        assertEquals("", CompanionStateTag.render(null));
        assertNull(CompanionStateTag.toJson(new CompanionStateTag.State(null, null, null)));
    }
}
