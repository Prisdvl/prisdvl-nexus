package com.lky.nexus.knowledge.quota;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 存储配额的默认实现：账号总量 + 单库上限两条硬线。
 *
 * <p>判定口径是<b>原始文件字节</b>（{@code kb_document.file_size}），不是解析后的正文、
 * 也不是向量体积：用户能理解"这个 PDF 有 12 MB"，理解不了"你占了 37 MB 的 chunk 行"。
 *
 * <p>上限配成 {@code <= 0} 表示不限（运维想临时放开时不必改代码）。
 */
@Service
public class StorageQuotaServiceImpl implements StorageQuotaService {

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final long totalLimitBytes;
    private final long perKnowledgeBaseLimitBytes;

    public StorageQuotaServiceImpl(DocumentMapper documentMapper,
                                   KnowledgeBaseMapper knowledgeBaseMapper,
                                   @Value("${nexus.storage.quota.total-bytes:5368709120}") long totalLimitBytes,
                                   @Value("${nexus.storage.quota.per-knowledge-base-bytes:1073741824}")
                                   long perKnowledgeBaseLimitBytes) {
        this.documentMapper = documentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.totalLimitBytes = totalLimitBytes;
        this.perKnowledgeBaseLimitBytes = perKnowledgeBaseLimitBytes;
    }

    @Override
    public StorageUsage usage(Long userId) {
        return new StorageUsage(
                documentMapper.sumFileSizeByUser(userId),
                documentMapper.countByUser(userId),
                knowledgeBaseMapper.countByUser(userId),
                totalLimitBytes,
                perKnowledgeBaseLimitBytes);
    }

    @Override
    public void ensureRoom(Long userId, Long knowledgeBaseId, long incomingBytes, String sourceName) {
        if (incomingBytes <= 0) {
            return;
        }
        String name = sourceName == null || sourceName.isBlank() ? "当前文件" : sourceName;

        if (perKnowledgeBaseLimitBytes > 0) {
            long usedInKb = documentMapper.sumFileSizeByKb(knowledgeBaseId);
            if (usedInKb + incomingBytes > perKnowledgeBaseLimitBytes) {
                throw new BusinessException(ErrorCode.QUOTA_EXHAUSTED,
                        "单库存储已用 %s / %s，%s 放不下（超出 %s）".formatted(
                                human(usedInKb), human(perKnowledgeBaseLimitBytes), name,
                                human(usedInKb + incomingBytes - perKnowledgeBaseLimitBytes)));
            }
        }

        if (totalLimitBytes > 0) {
            long used = documentMapper.sumFileSizeByUser(userId);
            if (used + incomingBytes > totalLimitBytes) {
                throw new BusinessException(ErrorCode.QUOTA_EXHAUSTED,
                        "存储空间已用 %s / %s，%s 放不下（超出 %s）".formatted(
                                human(used), human(totalLimitBytes), name,
                                human(used + incomingBytes - totalLimitBytes)));
            }
        }
    }

    /**
     * 字节转成便于阅读的大小，只用于错误文案。
     *
     * <p>必须做分档：配额上限可能是 1 GB（"0.0 MB" 这种说法根本看不出差多少），
     * 也可能做小规模校验时被配成 1 KB —— 一律按 MB 保留一位小数会把差额抹成 0。
     */
    private static String human(long bytes) {
        if (bytes < 1024L * 1024L) {
            return "%.1f KB".formatted(bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return "%.1f MB".formatted(bytes / 1024.0 / 1024.0);
        }
        return "%.2f GB".formatted(bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
