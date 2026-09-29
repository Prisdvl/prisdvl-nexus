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
 *
 * <p><b>三种写入路径的取舍</b>（增量同步的核心）：
 * <table border="1">
 *   <tr><th>方法</th><th>触发条件</th><th>切片</th><th>version</th></tr>
 *   <tr><td>{@link #save}</td><td>新增 / 正文变化</td><td>重建</td><td>+1</td></tr>
 *   <tr><td>{@link #updateMetadataOnly}</td><td>只有 frontmatter/标签变了</td><td>原样保留</td><td>不变</td></tr>
 *   <tr><td>{@link #relocate}</td><td>改名 / 移动</td><td>原样保留</td><td>不变</td></tr>
 * </table>
 * 保留切片的意义在于<b>保住了向量</b>——重新嵌入既慢又花钱，而这类变化并没有改变可检索的正文。
 */
public interface DocumentPersistService {

    /**
     * 保存（新建或覆盖）一篇文档：重建切片与标签。
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

    /**
     * 只更新元数据（标题 / frontmatter / 标签 / 时间），<b>保留切片与向量</b>。
     *
     * <p>适用：`file_hash` 变了但 `content_hash` 没变（例如只加了一个 tag）。
     */
    void updateMetadataOnly(Long userId,
                            KnowledgeBasePO knowledgeBase,
                            DocumentPO existing,
                            VaultFile file,
                            ParsedDocument parsed);

    /**
     * 改名 / 移动：更新路径与元数据，<b>保留切片与向量</b>。
     *
     * <p>调用方须保证新路径在该知识库中尚不存在（否则会撞 (kb_id, source_path) 唯一键）。
     */
    void relocate(Long userId,
                  KnowledgeBasePO knowledgeBase,
                  DocumentPO existing,
                  VaultFile file,
                  ParsedDocument parsed);

    /** 删除文档：文档软删，切片与标签物理删除，并清理磁盘上的原始文件。 */
    void delete(Long userId, DocumentPO document);

    /** 重算知识库的 docCount / chunkCount / totalTokens（导入结束后调用一次，避免每篇文档都做聚合）。 */
    void refreshKnowledgeBaseStats(Long knowledgeBaseId);
}
