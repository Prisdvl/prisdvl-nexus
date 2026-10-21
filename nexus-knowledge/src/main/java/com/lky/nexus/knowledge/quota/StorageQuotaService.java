package com.lky.nexus.knowledge.quota;

/**
 * 知识库存储配额。
 *
 * <p>为什么放在知识域而不是 app 层：配额要拦的是"文档入库"这件事本身，
 * 而入库有两条入口（单文件上传、Vault ZIP 批量导入）。拦在 Controller 里
 * 换个入口（MCP 工具、批量导入、后台重试）就绕过去了。
 */
public interface StorageQuotaService {

    /** 当前用量与上限。 */
    StorageUsage usage(Long userId);

    /**
     * 入库前校验：加上 {@code incomingBytes} 之后是否仍然在配额内。
     *
     * @throws com.lky.nexus.common.exception.BusinessException 超出（{@code QUOTA_EXHAUSTED}）
     */
    void ensureRoom(Long userId, Long knowledgeBaseId, long incomingBytes, String sourceName);
}
