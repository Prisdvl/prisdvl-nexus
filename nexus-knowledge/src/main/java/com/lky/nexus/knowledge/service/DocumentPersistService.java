package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.chunk.TextChunk;
import com.lky.nexus.knowledge.imports.VaultFile;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.parser.ParsedDocument;

import java.util.List;

/**
 * 文档落库服务：把"解析 + 切分"的结果写进 kb_document / kb_document_tag / kb_chunk。
 *
 * <p>它被导入流水线复用，也被未来的"重新解析"入口复用；
 * 覆盖已有文档时先清理旧切片与标签，避免出现新旧混杂的脏数据。
 */
public interface DocumentPersistService {

    /**
     * 保存（新建或覆盖）一篇文档。
     *
     * @param userId        归属用户
     * @param knowledgeBase 所属知识库（取切分与嵌入配置）
     * @param file          原始文件（用于计算 file_hash 与大小）
     * @param parsed        解析结果
     * @param chunks        切分结果
     * @param storedPath    原始文件的存储相对路径
     * @return 落库结果（含"是否新建"，供任务统计）
     */
    DocumentPersistResult save(Long userId,
                               KnowledgeBasePO knowledgeBase,
                               VaultFile file,
                               ParsedDocument parsed,
                               List<TextChunk> chunks,
                               String storedPath);

    /** 删除文档：文档软删，切片与标签物理删除。 */
    void delete(Long userId, DocumentPO document);

    /** 重算知识库的 docCount / chunkCount / totalTokens（导入结束后调用一次，避免每篇文档都做聚合）。 */
    void refreshKnowledgeBaseStats(Long knowledgeBaseId);
}
