package com.lky.nexus.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档实体（kb_document）。
 *
 * <p><b>文档身份 = (kbId, sourcePath)</b>，不是文件名也不是 id。这是增量同步的地基：
 * 反复导入同一个 Vault 时，相对路径是唯一稳定标识；只靠文件名会在
 * "同名不同目录"（Obsidian 允许）时互相覆盖。
 */
@Data
@TableName("kb_document")
public class DocumentPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    private Long userId;

    private String fileName;

    private String fileExt;

    /** UPLOAD / OBSIDIAN / ZIP */
    private String sourceType;

    /** 相对 Vault 根的路径，如 Spring/SpringBoot.md —— 增量同步的身份键。 */
    private String sourcePath;

    private String folder;

    private String title;

    private Long fileSize;

    /** SHA-256(原始字节)：判断文件是否变化。 */
    private String fileHash;

    /** SHA-256(规范化正文)：判断是否"只改了时间戳、内容没变"。 */
    private String contentHash;

    /** 完整 frontmatter（JSON 字符串）。 */
    private String frontmatter;

    /** 标签（JSON 数组字符串，冗余自 kb_document_tag）。 */
    private String tags;

    /** 标题目录树（JSON 字符串）。 */
    private String headingTree;

    private LocalDateTime docCreatedAt;

    private LocalDateTime docUpdatedAt;

    /** 见 {@code DocumentStatus}。 */
    private String status;

    private String errorMsg;

    private Integer chunkCount;

    private Integer tokenCount;

    /** 每次内容变更 +1。 */
    private Integer version;

    private LocalDateTime indexedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
