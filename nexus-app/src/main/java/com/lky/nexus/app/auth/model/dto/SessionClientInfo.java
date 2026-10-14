package com.lky.nexus.app.auth.model.dto;

/**
 * 登录 / 刷新时从 HTTP 请求提取的客户端信息（写入会话行，不进 JWT）。
 */
public record SessionClientInfo(
        String ipAddress,
        String userAgent,
        String deviceName) {

    public static SessionClientInfo of(String ipAddress, String userAgent) {
        String ua = truncate(userAgent, 512);
        return new SessionClientInfo(truncate(ipAddress, 64), ua, deriveDeviceName(ua));
    }

    private static String deriveDeviceName(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }
        String ua = userAgent;
        // 粗解析：只为会话列表展示，不做指纹
        if (containsIgnoreCase(ua, "iPhone")) {
            return "iPhone";
        }
        if (containsIgnoreCase(ua, "iPad")) {
            return "iPad";
        }
        if (containsIgnoreCase(ua, "Android")) {
            return "Android";
        }
        if (containsIgnoreCase(ua, "Windows")) {
            return "Windows";
        }
        if (containsIgnoreCase(ua, "Macintosh") || containsIgnoreCase(ua, "Mac OS")) {
            return "macOS";
        }
        if (containsIgnoreCase(ua, "Linux")) {
            return "Linux";
        }
        return truncate(ua, 64);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
