package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lky.nexus.app.security.CredentialCipher;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户自定义对话模型凭据服务。
 *
 * <p>负责读 / 写当前用户那一行（{@code uk_user_llm_user} 1:1）。
 * 模型调用时真正消费这份凭据的是 {@link DbUserChatCredentialsProvider}，
 * 它实现 nexus-ai 的 {@code UserChatCredentialsProvider} 端口。
 *
 * <p>落库的 {@code api_key} 是 {@link CredentialCipher} 产出的密文：
 * 本类是唯一负责"明文 ⇄ 密文"两侧转换的地方（写入前加密、回显前解密再掩码），
 * 顺带把加密之前写下的历史明文行就地升级。
 */
@Service
public class UserLlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(UserLlmConfigService.class);

    private final UserLlmConfigMapper userLlmConfigMapper;
    private final CredentialCipher credentialCipher;

    public UserLlmConfigService(UserLlmConfigMapper userLlmConfigMapper, CredentialCipher credentialCipher) {
        this.userLlmConfigMapper = userLlmConfigMapper;
        this.credentialCipher = credentialCipher;
    }

    /** 读当前用户的配置（apiKey 掩码回显）。 */
    public UserLlmConfigResponse get(Long userId) {
        UserLlmConfigPO po = findByUser(userId);
        if (po == null) {
            return new UserLlmConfigResponse(null, null, false, false, null);
        }
        // 掩码必须打在**明文**上：打在密文上既没有意义，又会把密文片段当成 Key 回显出去
        String plainKey = credentialCipher.decrypt(po.getApiKey());
        upgradeLegacyRow(po, plainKey);
        return new UserLlmConfigResponse(
                po.getBaseUrl(), po.getModelName(),
                Integer.valueOf(1).equals(po.getEnabled()),
                StringUtils.hasText(plainKey),
                mask(plainKey));
    }

    /**
     * 保存（upsert）当前用户配置。
     *
     * <p>apiKey 留空 = 保留旧值；启用时三项（base-url / model / key）必须齐全，
     * 否则保存了一启用就 4001/4007 的坏配置——不如在保存时拦住。
     */
    @Transactional
    public UserLlmConfigResponse upsert(Long userId, UserLlmConfigRequest request) {
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String baseUrl = trimmedToNull(request.baseUrl());
        String modelName = trimmedToNull(request.modelName());

        UserLlmConfigPO po = findByUser(userId);
        boolean isNew = po == null;
        if (isNew) {
            po = new UserLlmConfigPO();
            po.setUserId(userId);
            po.setEnabled(0);
        }

        po.setBaseUrl(baseUrl);
        po.setModelName(modelName);

        // apiKey 留空 = 保留已有（若那行是加密前的历史明文，这里顺手把它升级成密文）；
        // 填了才覆盖。校验与落库用的是同一个明文值，避免"校验明文、存了密文"这类错位。
        //
        // 刻意**不**无脑重加密：GCM 用的是随机 IV，同样明文每次加密结果都不同，
        // 无脑重加密会让"用户只改了模型名"也把 api_key 密文换掉——无谓的行变更，
        // 也让"留空即保留"这条语义在库层面无法核对。
        String plainKey = credentialCipher.decrypt(po.getApiKey());
        if (StringUtils.hasText(request.apiKey())) {
            plainKey = request.apiKey().trim();
            po.setApiKey(credentialCipher.encrypt(plainKey));
        } else if (StringUtils.hasText(plainKey) && !credentialCipher.isEncrypted(po.getApiKey())) {
            // 历史明文行：留空也顺手升级为密文（幂等，见 upgradeLegacyRow 的说明）
            po.setApiKey(credentialCipher.encrypt(plainKey));
        }

        if (enabled) {
            validateReady(baseUrl, modelName, plainKey);
        }
        po.setEnabled(enabled ? 1 : 0);

        if (isNew) {
            userLlmConfigMapper.insert(po);
        } else {
            userLlmConfigMapper.updateById(po);
        }
        log.info("用户 {} 的自定义对话模型配置已保存：enabled={}, baseUrl={}, model={}",
                userId, enabled, baseUrl, modelName);
        return get(userId);
    }

    private void validateReady(String baseUrl, String modelName, String apiKey) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(modelName) || !StringUtils.hasText(apiKey)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "启用自定义模型需要填完整：Base URL、模型名与 API Key（若 Key 输入框为空，请重新填写）");
        }
    }

    /**
     * 把「加密之前写下」的历史明文行就地升级为密文。
     *
     * <p>时机是刻意的：只在这一行真的被读出来（GET 配置 / upsert）时做，幂等——
     * 升级后 {@link CredentialCipher#isEncrypted} 为真，之后不再写库。
     * <b>不</b>放在模型调用的热路径（{@code DbUserChatCredentialsProvider}）上：
     * 那条路径只读不写，不该在一次模型调用里混进一次业务写入。
     *
     * <p>KEK 未配置时这里会抛 9999（拒绝以明文保存）——这正是"未配置"应该有的表现：
     * 在用户实际操作的位置报错，而不是安静地把明文继续留在库里。
     */
    private void upgradeLegacyRow(UserLlmConfigPO po, String plainKey) {
        if (!StringUtils.hasText(plainKey) || credentialCipher.isEncrypted(po.getApiKey())) {
            return;
        }
        UserLlmConfigPO patch = new UserLlmConfigPO();
        patch.setId(po.getId());
        patch.setApiKey(credentialCipher.encrypt(plainKey));
        userLlmConfigMapper.updateById(patch);
        log.info("用户 {} 的历史明文凭据已升级为密文", po.getUserId());
    }

    private UserLlmConfigPO findByUser(Long userId) {
        return userLlmConfigMapper.selectOne(new LambdaQueryWrapper<UserLlmConfigPO>()
                .eq(UserLlmConfigPO::getUserId, userId));
    }

    private static String trimmedToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 掩码：保留前 3 与后 2，中间用省略号；太短就只留首尾。 */
    static String mask(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        int len = apiKey.length();
        if (len <= 6) {
            return apiKey.charAt(0) + "***";
        }
        return apiKey.substring(0, 3) + "…" + apiKey.substring(len - 2);
    }
}
