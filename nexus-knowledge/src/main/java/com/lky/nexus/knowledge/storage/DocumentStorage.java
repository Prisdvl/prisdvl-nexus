package com.lky.nexus.knowledge.storage;

/**
 * 原始文件存储。
 *
 * <p>为什么要存原始文件（而不是只存解析结果）：增量同步时需要"重新解析已导入的文件"，
 * 而用户不会为了改一处笔误就重新上传整个 Vault。因此原始字节必须留一份。
 *
 * <p>第一版落到本地磁盘，接口与实现分离使 Phase 11 换成对象存储时业务代码零改动。
 */
public interface DocumentStorage {

    /**
     * 保存原始文件。
     *
     * @param knowledgeBaseId 知识库 id（用于分目录）
     * @param sourcePath      源文件相对路径（作为身份参与命名，保证幂等覆盖）
     * @param content         文件字节
     * @return 存储相对路径（落库到 kb_document，后续据此读取）
     */
    String store(Long knowledgeBaseId, String sourcePath, byte[] content);

    /** 读取原始文件；不存在返回 null。 */
    byte[] load(String storedPath);

    /** 删除原始文件；不存在则忽略。 */
    void delete(String storedPath);
}
