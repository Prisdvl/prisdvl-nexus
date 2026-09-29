package com.lky.nexus.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 文档标签（kb_document_tag）。
 *
 * <p>把标签拆成独立表而不是只塞进 JSON 字段：检索时要按标签过滤，
 * 而 JSON 字段做不了高效索引。JSON 字段只作为展示用的冗余。
 */
@Data
@TableName("kb_document_tag")
public class DocumentTagPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    private Long documentId;

    private String tag;

    /** FRONTMATTER / INLINE / FOLDER */
    private String tagSource;
}
