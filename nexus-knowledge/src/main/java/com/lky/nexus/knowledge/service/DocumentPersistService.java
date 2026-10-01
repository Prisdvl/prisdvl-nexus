package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.chunk.TextChunk;
import com.lky.nexus.knowledge.imports.VaultFile;
import com.lky.nexus.knowledge.model.KnowledgeSourceType;
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
 *
 * <p><b>sourceType 为什么是参数而不是在这里推断</b>：这一层拿到的只有 {@code VaultFile}
 * （路径 + 字节 + 时间），从这些信息里推断不出"这篇是整包同步来的，还是用户单传的"——
 * 两者都可以长得完全一样。而它是有实际后果的：{@code KnowledgeSourceType} 的注释写明，
 * 区分来源就是为了"增量同步时判断这次的清单是否覆盖了库里的全部来源"。
 * 一旦上传的文档被标成 OBSIDIAN，那个判断就会把用户手工传的文件当成同步范围内的东西。
 * （5.3 之前这里硬编码 OBSIDIAN，于是 {@code UPLOAD} 这个枚举值根本不可达。）
 */
public interface DocumentPersistService {

    /**
     * 保存（新建或覆盖）一篇文档：重建切片与标签。
     *
     * <p><b>覆盖已有文档时，会先清掉它在向量库与检索索引里的全部痕迹</b>：
     * 切片重切后拿到的是新 id，而向量按 chunk_id 存，不清就是一批指向已消失切片的孤儿。
     * 与 {@link #delete} 同一取舍——外部清理在前，失败即整体失败。
     *
     * @param userId        归属用户
     * @param knowledgeBase 所属知识库（取切分与嵌入配置）
     * @param file          原始文件（用于计算 file_hash 与大小）
     * @param parsed        解析结果
     * @param chunks        切分结果
     * @param storedPath    原始文件的存储相对路径
     * @param sourceType    文档来源（整包同步传 OBSIDIAN，单文件上传传 UPLOAD）；
     *                      覆盖已有文档时<b>以最后一次写入为准</b>——它描述的是"当前这份正文从哪来"
     * @return 落库结果（含"是否新建"，供任务统计）
     */
    DocumentPersistResult save(Long userId,
                               KnowledgeBasePO knowledgeBase,
                               VaultFile file,
                               ParsedDocument parsed,
                               List<TextChunk> chunks,
                               String storedPath,
                               KnowledgeSourceType sourceType);

    /**
     * 只更新元数据（标题 / frontmatter / 标签 / 时间），<b>保留切片与向量</b>。
     *
     * <p>适用：`file_hash` 变了但 `content_hash` 没变（例如只加了一个 tag）。
     */
    void updateMetadataOnly(Long userId,
                            KnowledgeBasePO knowledgeBase,
                            DocumentPO existing,
                            VaultFile file,
                            ParsedDocument parsed,
                            KnowledgeSourceType sourceType);

    /**
     * 改名 / 移动：更新路径与元数据，<b>保留切片与向量</b>。
     *
     * <p>调用方须保证新路径在该知识库中尚不存在（否则会撞 (kb_id, source_path) 唯一键）。
     */
    void relocate(Long userId,
                  KnowledgeBasePO knowledgeBase,
                  DocumentPO existing,
                  VaultFile file,
                  ParsedDocument parsed,
                  KnowledgeSourceType sourceType);

    /**
     * 删除文档：文档软删，切片与标签物理删除，并清理磁盘上的原始文件。
     *
     * <p><b>会先清外部存储（向量库 / 检索索引），且清不掉就整个失败。</b>
     * 这两处不在本事务里，无法回滚，所以顺序是"先外部、后 MySQL"：
     * 外部失败 → 事务回滚，文档原封不动、可重试；反过来则会在向量库里留下
     * 查不到内容的孤儿向量（检索命中却取不出正文，且没有任何入口能修）。
     *
     * <p>删除本文档的<b>出链</b>，保留指向它的<b>入链</b>——入链会自然退化成
     * "尚未创建的引用"，这正是 Obsidian 的语义（详见实现里的说明）。
     *
     * <p>注意本方法<b>不</b>重算知识库统计：导入流水线一批会删/改很多篇，
     * 每篇都重算一次是重复劳动，它自己在收尾时统一调用
     * {@link #refreshKnowledgeBaseStats}。单个删除的入口见 {@link #deleteDocument}。
     */
    void delete(Long userId, DocumentPO document);

    /**
     * 按 id 删除单篇文档（面向"删除按钮"的入口）。
     *
     * <p>与 {@link #delete} 的区别只有两点，但都需要：先做归属校验（库属于谁），
     * 再重算知识库统计——单篇删除是一次独立的用户动作，没有别人替它收尾。
     *
     * @throws com.lky.nexus.common.exception.BusinessException 3001（库不属于该用户）
     *                                                         / 3003（文档不在该库里）
     */
    void deleteDocument(Long userId, Long knowledgeBaseId, Long documentId);

    /** 重算知识库的 docCount / chunkCount / totalTokens（导入结束后调用一次，避免每篇文档都做聚合）。 */
    void refreshKnowledgeBaseStats(Long knowledgeBaseId);
}
