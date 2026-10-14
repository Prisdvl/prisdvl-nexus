package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 */
@Service
public class UserLlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(UserLlmConfigService.class);

    private final UserLlmConfigMapper userLlmConfigMapper;

    public UserLlmConfigService(UserLlmConfigMapper userLlmConfigMapper) {
        this.userLlmConfigMapper = userLlmConfigMapper;
    }

    /** 读当前用户的配置（apiKey 掩码回显）。 */
    public UserLlmConfigResponse get(Long userId) {
        UserLlmConfigPO po = findByUser(userId);
        if (po == null) {
            return new UserLlmConfigResponse(null, null, false, false, null);
        }
        return new UserLlmConfigResponse(
                po.getBaseUrl(), po.getModelName(),
                Integer.valueOf(1).equals(po.getEnabled()),
                StringUtils.hasText(po.getApiKey()),
                mask(po.getApiKey()));
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

        // apiKey 留空 = 保留已有；填了才覆盖
        if (StringUtils.hasText(request.apiKey())) {
            po.setApiKey(request.apiKey().trim());
        }

        if (enabled) {
            validateReady(baseUrl, modelName, po.getApiKey());
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
