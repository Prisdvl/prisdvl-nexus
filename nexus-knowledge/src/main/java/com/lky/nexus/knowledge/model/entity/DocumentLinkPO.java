package com.lky.nexus.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档关联边（kb_document_link）。
 *
 * <p>只存<b>原始目标</b>，不做写入期解析：WikiLink 指向的笔记可能还没导入
 * （先写笔记、后补被引用那篇很常见），写入期解析会把"暂未解析"永久固化下来。
 * 因此解析放到查询时做（见 DocumentLinkService）。
 */
@Data
@TableName("kb_document_link")
public class DocumentLinkPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    /** 来源文档。 */
    private Long fromDocumentId;

    /** WIKILINK / MARKDOWN_LINK / EMBED */
    private String linkType;

    /** 原始目标（笔记名 / 相对路径 / URL）。 */
    private String target;

    private String display;

    private Integer startOffset;

    /** http(s) 外链：不参与内部关联图。 */
    private Integer isExternal;

    private LocalDateTime createdAt;
}
