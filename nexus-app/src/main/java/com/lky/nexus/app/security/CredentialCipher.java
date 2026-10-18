package com.lky.nexus.app.security;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 落库凭据的字段级加密（AES-GCM，P1 凭证安全）。
 *
 * <p>背景：{@code user_llm_config.api_key} 在此之前是<b>明文列</b>——接口只做掩码回显，
 * 而<b>掩码不等于加密</b>：拿到库文件/备份的人仍然直接读到可用 Key。本类负责在写入前加密、
 * 在真正要调用模型那一刻解密，落库形态与使用形态分离。
 *
 * <p>密文格式：{@code v1:} + base64(12 字节随机 IV ‖ 密文 ‖ 16 字节 GCM tag)。
 * 版本前缀不是装饰：换算法或换 KEK 时按前缀分流，而不是靠"猜这段到底是什么"。
 *
 * <p><b>KEK</b> 来自 {@code nexus.security.crypto.kek}（base64 的 16/24/32 字节，
 * 通常由环境变量 {@code NEXUS_SECURITY_CRYPTO_KEK} 注入）。两条刻意的取舍：
 * <ul>
 *   <li><b>缺失时 fail-closed</b>：不配置不是"退化成不加密"，而是写入直接拒绝
 *       （{@link ErrorCode#INTERNAL_ERROR}）。静默存明文才是真正的危险。</li>
 *   <li><b>配置错（不是合法 base64 / 长度不对）时启动即失败</b>：宁可起不来，
 *       也不要先写进一批"以后换对 KEK 也解不开"的密文。</li>
 * </ul>
 *
 * <p>历史明文行按<b>明文读</b>（首次出现时打一条 WARN），升级由
 * {@code UserLlmConfigService} 在保存 / 读取配置时就地完成——本类不做任何 IO。
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);

    /** 密文版本前缀。 */
    public static final String VERSION_PREFIX = "v1:";

    /**
     * 明文长度上限。
     *
     * <p>由列宽倒推：{@code user_llm_config.api_key} 是 {@code VARCHAR(500)}，
     * 密文长度 = 前缀 3 + base64(IV 12 + 明文 L + tag 16)。L = 320 时密文 467 字符，
     * 留出余量；再长就有被 MySQL 截断的风险，所以在这里拦住而不是靠数据库报错。
     */
    public static final int MAX_PLAINTEXT_LENGTH = 320;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    /** null = 未配置 KEK（写入与解密均 fail-closed）。 */
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    /** 历史明文只提醒一次：避免每次读配置都刷日志。 */
    private final AtomicBoolean legacyWarned = new AtomicBoolean(false);

    public CredentialCipher(NexusSecurityProperties properties) {
        this.key = parseKek(properties.getCrypto().getKek());
    }

    /** 加密；{@code plain} 为空返回 null（列可空，且"没填"与"填了空串"要能区分）。 */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        if (plain.length() > MAX_PLAINTEXT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "凭据长度不能超过 " + MAX_PLAINTEXT_LENGTH + " 个字符");
        }
        requireConfigured();
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] cipherText = doFinal(Cipher.ENCRYPT_MODE, iv, plainBytes, 0, plainBytes.length);
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            // 消息里不放任何 Key 片段：加密失败的排查靠 KEK 配置，不靠密文内容
            log.error("凭据加密失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "凭据加密失败");
        }
    }

    /** 落库值是否为本类产出的密文；{@code v1:} 之外的一律视为历史明文。 */
    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(VERSION_PREFIX);
    }

    /**
     * 解密；历史明文原样返回（并在首次出现时 WARN 一次）。
     *
     * <p>不做长度/格式猜测：只有 {@link #VERSION_PREFIX} 开头的才当密文，
     * 否则就是升级前写下的明文——猜错方向的代价是"把明文当密文解密"或反之，
     * 两者都会表现为难以定位的 401。
     */
    public String decrypt(String stored) {
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        if (!isEncrypted(stored)) {
            if (legacyWarned.compareAndSet(false, true)) {
                log.warn("检测到未加密的历史凭据（无 {} 前缀），按明文读取；下次保存该项时会被加密", VERSION_PREFIX);
            }
            return stored;
        }
        requireConfigured();
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("payload too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            byte[] plain = doFinal(Cipher.DECRYPT_MODE, iv, payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // AEAD 校验失败只说明"解不开"，不说明内容；换过 KEK 是首要嫌疑
            log.warn("凭据解密失败（密文损坏，或 KEK 与加密时不是同一把）");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "凭据解密失败：请重新保存「模型 Key」（若刚更换过服务端加密密钥）");
        }
    }

    private byte[] doFinal(int mode, byte[] iv, byte[] input, int offset, int length) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(input, offset, length);
    }

    private void requireConfigured() {
        if (key == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "未配置 nexus.security.crypto.kek（环境变量 NEXUS_SECURITY_CRYPTO_KEK），"
                            + "拒绝以明文保存凭据");
        }
    }

    private static SecretKeySpec parseKek(String base64) {
        if (!StringUtils.hasText(base64)) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "nexus.security.crypto.kek 必须是 base64（16/24/32 字节），当前值无法解码", e);
        }
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException(
                    "nexus.security.crypto.kek 解码后必须是 16/24/32 字节（AES），当前 " + raw.length + " 字节");
        }
        return new SecretKeySpec(raw, KEY_ALGORITHM);
    }
}
