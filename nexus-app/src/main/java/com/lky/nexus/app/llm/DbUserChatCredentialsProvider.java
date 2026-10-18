package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lky.nexus.ai.model.UserChatCredentials;
import com.lky.nexus.ai.model.UserChatCredentialsProvider;
import com.lky.nexus.app.security.CredentialCipher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * {@link UserChatCredentialsProvider} 的数据库实现：把当前用户在「设置 → 模型 Key」
 * 里保存的凭据桥接给 nexus-ai 的 {@code ChatClientFactory}。
 *
 * <p>放在 nexus-app 是依赖方向的要求（app → ai）；nexus-ai 只定义端口不碰数据库。
 * 只有当行存在、{@code enabled=1}、且三项字段齐全时才返回凭据，否则空 Option——
 * 工厂据此回落服务端全局模型。
 */
@Component
public class DbUserChatCredentialsProvider implements UserChatCredentialsProvider {

    private final UserLlmConfigMapper userLlmConfigMapper;
    private final CredentialCipher credentialCipher;

    public DbUserChatCredentialsProvider(UserLlmConfigMapper userLlmConfigMapper,
                                         CredentialCipher credentialCipher) {
        this.userLlmConfigMapper = userLlmConfigMapper;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public Optional<UserChatCredentials> find(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        UserLlmConfigPO po = userLlmConfigMapper.selectOne(
                new LambdaQueryWrapper<UserLlmConfigPO>().eq(UserLlmConfigPO::getUserId, userId));
        if (po == null || !Integer.valueOf(1).equals(po.getEnabled())) {
            return Optional.empty();
        }
        // 库里是密文（或加密前的历史明文，见 CredentialCipher）；明文只在构造客户端这一刻存在，
        // 且不会再被写回、回显或打进日志。解密失败是 fail-closed：宁可报错，不静默回落全局模型。
        String plainKey = credentialCipher.decrypt(po.getApiKey());
        if (!StringUtils.hasText(po.getBaseUrl())
                || !StringUtils.hasText(plainKey)
                || !StringUtils.hasText(po.getModelName())) {
            return Optional.empty();
        }
        return Optional.of(new UserChatCredentials(
                po.getBaseUrl().trim(), plainKey.trim(), po.getModelName().trim()));
    }
}
