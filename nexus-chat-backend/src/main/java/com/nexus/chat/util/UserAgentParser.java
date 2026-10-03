package com.nexus.chat.util;

import com.nexus.chat.model.UserSession.DeviceType;

/**
 * Lightweight User-Agent parser. Derives a human-readable device name, a coarse
 * device type, and a browser name without pulling in an external dependency.
 * It is heuristic by design — enough to label sessions and login history, not a
 * full UA database.
 */
public final class UserAgentParser {

    private UserAgentParser() {
    }

    public record Result(String deviceName, DeviceType deviceType, String browser) {
    }

    public static Result parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new Result("Unknown device", DeviceType.unknown, "Unknown");
        }
        String ua = userAgent;
        String lower = ua.toLowerCase();

        DeviceType type = detectType(lower);
        String os = detectOs(ua, lower);
        String browser = detectBrowser(lower);
        // Device name favors the OS/hardware label; fall back to the type.
        String deviceName = os != null ? os : capitalize(type.name());

        return new Result(deviceName, type, browser);
    }

    private static DeviceType detectType(String lower) {
        if (lower.contains("ipad") || (lower.contains("tablet") && !lower.contains("mobile"))) {
            return DeviceType.tablet;
        }
        if (lower.contains("mobile") || lower.contains("iphone") || lower.contains("android")
                || lower.contains("windows phone")) {
            // Android tablets omit "mobile"; treat the rest as phones.
            if (lower.contains("android") && !lower.contains("mobile")) {
                return DeviceType.tablet;
            }
            return DeviceType.mobile;
        }
        if (lower.contains("windows") || lower.contains("macintosh") || lower.contains("mac os")
                || lower.contains("linux") || lower.contains("x11") || lower.contains("electron")) {
            return DeviceType.desktop;
        }
        return DeviceType.unknown;
    }

    private static String detectOs(String ua, String lower) {
        if (lower.contains("iphone")) return "iPhone";
        if (lower.contains("ipad")) return "iPad";
        if (lower.contains("windows nt 10")) return "Windows 10/11";
        if (lower.contains("windows")) return "Windows";
        if (lower.contains("android")) {
            String ver = between(ua, "Android ", ";");
            return ver != null ? "Android " + ver.trim() : "Android";
        }
        if (lower.contains("mac os x") || lower.contains("macintosh")) return "macOS";
        if (lower.contains("linux")) return "Linux";
        return null;
    }

    private static String detectBrowser(String lower) {
        // Order matters: more specific engines first.
        if (lower.contains("edg/") || lower.contains("edga") || lower.contains("edgios")) return "Edge";
        if (lower.contains("opr/") || lower.contains("opera")) return "Opera";
        if (lower.contains("firefox")) return "Firefox";
        if (lower.contains("electron")) return "Nexus App";
        if (lower.contains("chrome") || lower.contains("crios")) return "Chrome";
        if (lower.contains("safari")) return "Safari";
        return "Unknown";
    }

    private static String between(String s, String start, String end) {
        int i = s.indexOf(start);
        if (i < 0) return null;
        i += start.length();
        int j = s.indexOf(end, i);
        return j < 0 ? s.substring(i) : s.substring(i, j);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
