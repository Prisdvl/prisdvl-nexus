package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档标签 Mapper。
 */
@Mapper
public interface DocumentTagMapper extends BaseMapper<DocumentTagPO> {

    /**
     * 按标签查文档 id（any-of：给了多个标签就是"命中任一"）。
     *
     * <p><b>为什么 join 一次 kb_document</b>：{@code kb_document_tag} 里没有逻辑删除列，
     * 文档被删时标签行是按 documentId 主动删的；万一漏删（或将来有别的删除路径），
     * 单查标签表就会让已删除的笔记"复活"进检索结果。加一个 {@code d.deleted = 0}
     * 的成本是一次主键 join，换来的是"检索结果永远只含现存文档"这条不变量。
     *
     * <p>走 {@code idx_tag_kb}（kb_id, tag）索引。
     */
    @Select("""
            <script>
            SELECT DISTINCT t.document_id
            FROM kb_document_tag t
            JOIN kb_document d ON d.id = t.document_id AND d.deleted = 0
            WHERE t.kb_id = #{kbId}
              AND t.tag IN
              <foreach collection="tags" item="tag" open="(" separator="," close=")">#{tag}</foreach>
            </script>
            """)
    List<Long> selectDocumentIdsByTags(@Param("kbId") Long kbId, @Param("tags") List<String> tags);
}
