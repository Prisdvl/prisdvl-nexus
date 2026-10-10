package com.lky.nexus.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.lky.nexus.knowledge.mapper.JsonTextTypeHandler;
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
/**
 * ⚠️ {@code autoResultMap = true} 不能省：没有它，MyBatis-Plus
 * 不会为带typeHandler 的字段启用 resultMap，处理器等于没挂。
 */
@TableName(value = "kb_document", autoResultMap = true)
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

    /** 原始文件在存储中的相对路径（重新解析与删除清理用）。 */
    private String storedPath;

    private String folder;

    private String title;

    private Long fileSize;

    /** SHA-256(原始字节)：判断文件是否变化。 */
    private String fileHash;

    /** SHA-256(规范化正文)：判断是否"只改了时间戳、内容没变"。 */
    private String contentHash;

    /**
     * 完整 frontmatter（JSON 字符串）。
     *
     * <p>挂 {@link com.lky.nexus.knowledge.mapper.JsonTextTypeHandler} 的原因：
     * H2 的 {@code json} 列会把 JSON 文本存成字符串节点，而 {@code updateById}
     * 是「整行读出 → 改字段 → 全列写回」，未改动的本列也会被把已转义的形态再写回，
     * 实测每次 update 套一层（len 61 → 85 → 133 → 229，<b>指数增长</b>），
     * 而解析侧只做单层解包 → 套两层以上 frontmatter 就静默失效。
     */
    @TableField(typeHandler = JsonTextTypeHandler.class)
    private String frontmatter;

    /** 标签（JSON 数组字符串，冗余自 kb_document_tag）。 */
    @TableField(typeHandler = JsonTextTypeHandler.class)
    private String tags;

    /** 标题目录树（JSON 字符串）。挂处理器的原因同 {@link #frontmatter}。 */
    @TableField(typeHandler = JsonTextTypeHandler.class)
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
