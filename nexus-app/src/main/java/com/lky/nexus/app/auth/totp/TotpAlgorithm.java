package com.lky.nexus.app.auth.totp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP（RFC 6238）与 Base32（RFC 4648）的最小实现。
 *
 * <p><b>为什么自己写而不是引第三方库</b>：算法本体只有「HMAC-SHA1 + 动态截断 + 十进制取模」
 * 三步，全部由 JDK 提供（{@code javax.crypto}）；引一个第三方 TOTP 库要为它引入一条新的
 * 供应链、一次新的依赖升级义务，而它带来的代码量比这里还少。真正容易写错的地方
 * （动态截断的符号位、窗口对齐、Base32 填充）都由 {@code TotpAlgorithmTest} 对着
 * RFC 6238 附录 B 的官方测试向量钉住。
 *
 * <p>刻意<b>不</b>提供「把密钥写进日志」的入口：本类任何方法都不记录密钥或验证码。
 */
public final class TotpAlgorithm {

    /** RFC 4648 的 Base32 字母表（TOTP 密钥的事实标准编码，验证器 App 都按它读）。 */
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    /** 默认 20 字节 = 160 bit，与 RFC 4226 的建议一致，Base32 后正好 32 个字符。 */
    public static final int DEFAULT_SECRET_BYTES = 20;

    private TotpAlgorithm() {
    }

    /**
     * 生成一枚新的随机密钥（Base32，无填充）。
     *
     * <p>用 {@link SecureRandom} 而不是 {@code Math.random()} / {@code UUID}：
     * 后者不是密码学安全随机源，密钥可预测就等于没有第二因子。
     */
    public static String randomSecret() {
        byte[] bytes = new byte[DEFAULT_SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** 当前时间步：UTC 秒数除以周期并向下取整。 */
    public static long timeStep(Instant now, int periodSeconds) {
        return Math.floorDiv(now.getEpochSecond(), periodSeconds);
    }

    /**
     * 计算某个时间步的 TOTP 值（左补零到 digits 位）。
     *
     * <p>算法即 RFC 4226 的 HOTP：{@code HMAC-SHA1(secret, counter)} 后取最后 4 bit 作为偏移，
     * 从偏移处取 4 字节按大端拼成 31 位整数（与 0x7fffffff 相与清掉符号位），再对 10^digits 取模。
     * 不截断符号位会让 Java 的 int 变成负数，取模结果就成了负值——这是最容易踩的一处。
     */
    public static String code(String base32Secret, long timeStep, int digits) {
        byte[] key = base32Decode(base32Secret);
        byte[] counter = new byte[8];
        long value = timeStep;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(counter);
        } catch (GeneralSecurityException e) {
            // HmacSHA1 是 JDK 必备算法，走到这里说明运行环境被裁剪过——属于部署错误，不是输入问题
            throw new IllegalStateException("当前 JVM 不支持 HmacSHA1，无法计算 TOTP", e);
        }

        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int modulo = switch (digits) {
            case 6 -> 1_000_000;
            case 8 -> 100_000_000;
            default -> throw new IllegalArgumentException("只支持 6 或 8 位验证码，收到 " + digits);
        };
        return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulo);
    }

    /**
     * 校验验证码，允许前后各 {@code window} 个时间步的时钟漂移。
     *
     * <p><b>为什么默认 window=1</b>：验证器 App 与服务端的时钟总有秒级偏差，
     * 手机时间也可能被用户手动调过；不放开窗口会让一整类用户"怎么输都不对"。
     * 但窗口不能大：每多一个时间步，猜中一枚 6 位码的概率就多 1/10^6，
     * 而 3 个时间步已经是可用性与安全性的常规折中（RFC 6238 §5.2 建议不宜超过 1 个步长）。
     *
     * <p>比较用定长时间实现：验证码只有 6 位，但提前 return 会让攻击者靠响应时间
     * 逐位试探——这类侧信道在第二因子上尤其不该留。
     */
    public static boolean verify(String base32Secret, String code, Instant now, int periodSeconds,
                                 int digits, int window) {
        if (base32Secret == null || base32Secret.isBlank() || code == null) {
            return false;
        }
        String normalized = code.trim().replace(" ", "");
        if (normalized.length() != digits || !normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }
        long current = timeStep(now, periodSeconds);
        boolean matched = false;
        for (long step = current - window; step <= current + window; step++) {
            // 不能写成 `matched = matched || constantTimeEquals(...)` 之外的短路形式，
            // 否则命中后剩余步长都不再比较，反而让"命中得早"变得可测
            matched |= constantTimeEquals(code(base32Secret, step, digits), normalized);
        }
        return matched;
    }

    /** 生成 {@code otpauth://} 绑定 URI（验证器 App 扫码/手输都用它）。 */
    public static String otpauthUri(String issuer, String account, String secret, int digits, int periodSeconds) {
        // 标签里的 issuer 前缀是 Google Authenticator 的扩展约定；冒号必须编码成 %3A，
        // 否则部分 App 会把标签截断成 issuer 为空。
        String label = urlEncode(issuer) + "%3A" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + digits
                + "&period=" + periodSeconds;
    }

    /** Base32 编码（无 '=' 填充，小写输入不需要）。 */
    public static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >>> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    /** Base32 解码：容忍小写、空格、以及用户手输时补上的 '='。 */
    public static byte[] base32Decode(String encoded) {
        String cleaned = encoded.trim().replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8 + 1];
        int index = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            int value = indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("非法的 Base32 字符：" + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        byte[] trimmed = new byte[index];
        System.arraycopy(out, 0, trimmed, 0, index);
        return trimmed;
    }

    /** 定长比较：不因第一个不同的字符提前返回。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static int indexOf(char c) {
        for (int i = 0; i < BASE32.length; i++) {
            if (BASE32[i] == c) {
                return i;
            }
        }
        return -1;
    }

    private static String urlEncode(String raw) {
        return java.net.URLEncoder.encode(raw == null ? "" : raw, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
