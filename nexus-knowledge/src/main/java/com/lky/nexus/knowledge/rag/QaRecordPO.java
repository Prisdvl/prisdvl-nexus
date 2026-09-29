package com.lky.nexus.knowledge.rag;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 问答记录（kb_qa_record）：把"问了什么、答了什么、依据哪些资料"留档。 */
@Data
@TableName("kb_qa_record")
public class QaRecordPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    private Long userId;

    private String question;

    private String answer;

    /** 引用列表 JSON（历史是整体读取，不按引用单独查询）。 */
    private String citations;

    private Integer contextTokens;

    private Integer truncated;

    private Integer reranked;

    private Long costMs;

    private LocalDateTime createdAt;
}
