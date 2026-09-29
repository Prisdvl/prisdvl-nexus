package com.lky.nexus.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 哈希工具。
 *
 * <p>本项目用它做两件重要的事：
 * <ul>
 *   <li><b>增量同步</b>（Phase 2.5）：{@code file_hash} 判断文件是否变化、
 *       {@code content_hash} 判断是否"只改了时间戳"；</li>
 *   <li><b>Embedding 缓存键</b>：相同内容不重复调用嵌入、不重复付费。</li>
 * </ul>
 * 选 SHA-256 而不是 MD5/CRC：我们要的是"碰撞概率可忽略"，
 * 而不是"算得快"——一次碰撞就意味着用户的一篇笔记永远不会被更新。
 */
public final class HashUtils {

    private HashUtils() {
    }

    /** 对字节数组求 SHA-256 十六进制小写串（64 字符）。 */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境异常
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    /** 对字符串（UTF-8）求 SHA-256 十六进制串。 */
    public static String sha256Hex(String text) {
        return sha256Hex(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }
}
