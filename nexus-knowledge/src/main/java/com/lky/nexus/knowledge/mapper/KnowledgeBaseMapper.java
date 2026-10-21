package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识库 Mapper。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBasePO> {

    /**
     * 某用户的知识库个数（用量面板展示）。
     *
     * <p>走 {@code idx_kb_user (user_id, deleted, created_at)}；{@code deleted = 0} 必须显式写，
     * 手写 SQL 不经 {@code @TableLogic}。
     */
    @Select("SELECT COUNT(*) FROM kb_knowledge_base WHERE user_id = #{userId} AND deleted = 0")
    long countByUser(@Param("userId") Long userId);
}
