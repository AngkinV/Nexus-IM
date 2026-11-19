package com.nexus.chat.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelUrlsTest {

    @Test
    void bareHostGetsV1() {
        assertEquals("http://localhost:11434/v1", ModelUrls.openAiBase("http://localhost:11434"));
        assertEquals("https://api.deepseek.com/v1", ModelUrls.openAiBase("https://api.deepseek.com"));
    }

    @Test
    void trailingSlashesAndWhitespaceTrimmed() {
        assertEquals("http://localhost:11434/v1", ModelUrls.openAiBase("  http://localhost:11434///  "));
        assertEquals("https://api.moonshot.cn/v1", ModelUrls.openAiBase("https://api.moonshot.cn/v1/"));
    }

    @Test
    void versionedRootKeptAsIs() {
        assertEquals("https://api.openai.com/v1", ModelUrls.openAiBase("https://api.openai.com/v1"));
        assertEquals("https://open.bigmodel.cn/api/paas/v4", ModelUrls.openAiBase("https://open.bigmodel.cn/api/paas/v4"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                ModelUrls.openAiBase("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals("https://api.groq.com/openai/v1", ModelUrls.openAiBase("https://api.groq.com/openai/v1"));
    }

    @Test
    void nonVersionedPathGetsV1Appended() {
        assertEquals("https://proxy.example.com/openai/v1", ModelUrls.openAiBase("https://proxy.example.com/openai"));
    }

    @Test
    void fullEndpointUrlsReducedToRoot() {
        assertEquals("https://api.x.com/v1", ModelUrls.openAiBase("https://api.x.com/v1/chat/completions"));
        assertEquals("https://api.x.com/v1", ModelUrls.openAiBase("https://api.x.com/v1/chat/completions/"));
        assertEquals("https://api.x.com/v1", ModelUrls.openAiBase("https://api.x.com/v1/embeddings"));
        assertEquals("https://api.x.com/v1", ModelUrls.openAiBase("https://api.x.com/v1/models"));
        assertEquals("https://api.x.com/v1", ModelUrls.openAiBase("https://api.x.com/chat/completions"));
    }

    @Test
    void chatCompletionsAppendsPath() {
        assertEquals("http://localhost:11434/v1/chat/completions",
                ModelUrls.chatCompletions("http://localhost:11434"));
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                ModelUrls.chatCompletions("https://open.bigmodel.cn/api/paas/v4"));
        assertEquals("https://api.x.com/v1/chat/completions",
                ModelUrls.chatCompletions("https://api.x.com/v1/chat/completions"));
    }

    @Test
    void anthropicRootStripsSdkSuffixes() {
        assertEquals("https://api.anthropic.com", ModelUrls.anthropicBase("https://api.anthropic.com"));
        assertEquals("https://api.anthropic.com", ModelUrls.anthropicBase("https://api.anthropic.com/v1"));
        assertEquals("https://api.anthropic.com", ModelUrls.anthropicBase("https://api.anthropic.com/v1/messages"));
        assertEquals("https://proxy.example.com", ModelUrls.anthropicBase("https://proxy.example.com/v1/messages/"));
    }

    @Test
    void geminiRootStripsVersionSuffixes() {
        assertEquals("https://generativelanguage.googleapis.com",
                ModelUrls.geminiBase("https://generativelanguage.googleapis.com"));
        assertEquals("https://generativelanguage.googleapis.com",
                ModelUrls.geminiBase("https://generativelanguage.googleapis.com/v1beta"));
        assertEquals("https://generativelanguage.googleapis.com",
                ModelUrls.geminiBase("https://generativelanguage.googleapis.com/v1"));
    }

    @Test
    void nullAndEmptyYieldNull() {
        assertNull(ModelUrls.openAiBase(null));
        assertNull(ModelUrls.openAiBase(""));
        assertNull(ModelUrls.openAiBase("   "));
        assertNull(ModelUrls.openAiBase("///"));
        assertNull(ModelUrls.chatCompletions(null));
        assertNull(ModelUrls.chatCompletions("  "));
        assertNull(ModelUrls.anthropicBase(null));
        assertNull(ModelUrls.geminiBase(""));
    }
}