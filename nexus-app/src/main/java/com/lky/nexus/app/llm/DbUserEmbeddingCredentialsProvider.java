package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lky.nexus.ai.model.UserEmbeddingCredentials;
import com.lky.nexus.ai.model.UserEmbeddingCredentialsProvider;
import com.lky.nexus.app.security.CredentialCipher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * {@link UserEmbeddingCredentialsProvider} 的数据库实现：把用户在「设置 → 嵌入模型」
 * 里保存的凭据桥接给 nexus-knowledge 的 {@code KnowledgeEmbeddingResolver}。
 *
 * <p>放在 nexus-app 是依赖方向的要求（app → knowledge → ai）；nexus-ai 只定义端口不碰数据库。
 * 只有当行存在、{@code enabled=1}、且 provider / 模型名 / Base URL / Key / 维度齐全时才返回凭据，
 * 否则空 Option——解析方据此回落服务端全局 {@code embedding.default}。
 *
 * <p>解密失败是 <b>fail-closed</b>（{@code CredentialCipher} 抛 9999），与对话侧凭据同一行为：
 * 宁可报错让用户重存一次，也不静默回落到全局模型——那等于用户以为在用自己的额度，
 * 实际花的是服务端的钱。
 *
 * <p>刻意<b>不</b>在这里做白名单校验：那是 {@code EmbeddingByokPolicy} 的职责，
 * 保存侧（{@code UserEmbeddingConfigService}）与使用侧（{@code KnowledgeEmbeddingResolver}、
 * {@code EmbeddingClientFactory}）各自按自己的错误码执行，规则仍然只有一份。
 */
@Component
public class DbUserEmbeddingCredentialsProvider implements UserEmbeddingCredentialsProvider {

    private final UserEmbeddingConfigMapper userEmbeddingConfigMapper;
    private final CredentialCipher credentialCipher;

    public DbUserEmbeddingCredentialsProvider(UserEmbeddingConfigMapper userEmbeddingConfigMapper,
                                              CredentialCipher credentialCipher) {
        this.userEmbeddingConfigMapper = userEmbeddingConfigMapper;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public Optional<UserEmbeddingCredentials> find(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        UserEmbeddingConfigPO po = userEmbeddingConfigMapper.selectOne(
                new LambdaQueryWrapper<UserEmbeddingConfigPO>().eq(UserEmbeddingConfigPO::getUserId, userId));
        if (po == null || !Integer.valueOf(1).equals(po.getEnabled())) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(po.getProvider())
                || !StringUtils.hasText(po.getModelName())
                || !StringUtils.hasText(po.getBaseUrl())
                || !StringUtils.hasText(po.getApiKey())
                || po.getDimensions() == null) {
            return Optional.empty();
        }
        // 库里是密文（或加密前的历史明文，见 CredentialCipher）；明文只在构建客户端那一刻存在
        String plainKey = credentialCipher.decrypt(po.getApiKey());
        if (!StringUtils.hasText(plainKey)) {
            return Optional.empty();
        }
        return Optional.of(new UserEmbeddingCredentials(
                po.getProvider().trim(), po.getModelName().trim(), po.getBaseUrl().trim(),
                plainKey.trim(), po.getDimensions()));
    }
}
