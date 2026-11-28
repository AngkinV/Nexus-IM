package com.nexus.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative extraction from an actual user message, never from model prose. */
public final class CompanionMemorySafety {
    private CompanionMemorySafety() {}
    private static final Pattern SECRET = Pattern.compile("(?i)(?:sk-[a-z0-9_-]{8,}|bearer\\s+\\S+|(?:api[ _-]?key|password|secret|token|验证码|密码|密钥|口令)|-----BEGIN|\\b\\d{6}\\b)");
    private static final Pattern PRIVATE = Pattern.compile("(?i)(健康|生病|疾病|诊断|药|住址|地址|身份证|银行卡|财务|工资|单位|公司|姓名|真名|家庭|家人|恋爱|感情|婚|性取向|宗教|政治|第三方|他喜欢|她喜欢|\\b(?:health|medical|address|salary|family|wife|husband|diagnosis|religion|politic|real name)\\b|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|https?://|\\d{7,})");
    private static final Pattern PREFERENCE = Pattern.compile("(?is)^(?:我(?:更)?喜欢|我不喜欢|我偏好|我通常|以后请用|我们约定|我们一起|今天我们|I (?:prefer|like|dislike)|Please (?:use|keep)).{1,180}$");
    // Closed low-sensitivity vocabulary: an arbitrary proper name or medical/financial clause is not a safe preference.
    private static final Pattern LOW_VALUE = Pattern.compile("(?i)^(?:(?:我(?:更)?喜欢|我不喜欢|我偏好|我通常)(?:喝茶|喝咖啡|茶|咖啡|读书|阅读|看电影|听音乐|音乐|散步|跑步|烘焙|打羽毛球|简短回复|简短的回复|详细回复|详细的回复|英文回复|中文回复|温柔的语气|清晰的解释|多举例|少用表情|安静聊天|睡前闲聊)|以后请用(?:简短回复|详细回复|中文|英文|温柔的语气|清晰的解释)|(?:我们约定|我们一起|今天我们)(?:一起)?(?:聊天|聊书|聊音乐|散步|看电影|听音乐|喝茶)|I (?:prefer|like|dislike) (?:short replies|short answers|brief replies|detailed replies|detailed answers|English replies|Chinese replies|tea|coffee|reading|books|music|walking|running|baking)|Please (?:use|keep) (?:short replies|brief replies|detailed replies|English|Chinese))[。.!！]?$" );
    public static boolean containsCredential(String value) {
        return value != null && SECRET.matcher(value).find();
    }
    public static String candidate(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.length() > 200 || text.contains("\n") || containsCredential(text) || PRIVATE.matcher(text).find()
                || !PREFERENCE.matcher(text).matches() || !LOW_VALUE.matcher(text).matches()) return null;
        return (text.regionMatches(true,0,"I ",0,2) || text.regionMatches(true,0,"Please ",0,7) ? "User stated: " : "用户表示：") + text;
    }
    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
