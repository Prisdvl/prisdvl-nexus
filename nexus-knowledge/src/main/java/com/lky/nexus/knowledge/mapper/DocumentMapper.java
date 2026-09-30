package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档 Mapper。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentPO> {

    /**
     * 按目录查文档 id（含子目录）。
     *
     * <p>两个必须显式写出来的东西：
     * <ol>
     *   <li><b>{@code deleted = 0}</b>：MyBatis-Plus 的逻辑删除只作用于它自己生成的 SQL，
     *       手写 SQL 没有这个待遇。漏了它，被删掉的笔记会从过滤结果里"复活"——
     *       而且只在检索时可见，列表页一切正常（列表走的是 BaseMapper），极难定位。</li>
     *   <li><b>{@code ESCAPE '!'}</b>：目录名里出现 {@code %} 或 {@code _} 时（
     *       {@code _} 在笔记命名里很常见，如 {@code day_01}），不转义就会被 LIKE 当通配符，
     *       把 {@code day101} 也匹配进来。转义字符特意选 {@code !} 而不是反斜杠——
     *       反斜杠在 MySQL 的字符串字面量里本身还要转义一次，两个数据库下写法不一致。</li>
     * </ol>
     *
     * <p>走 {@code idx_doc_kb_folder}（kb_id, folder, deleted）索引。
     */
    @Select("""
            SELECT id FROM kb_document
            WHERE kb_id = #{kbId}
              AND deleted = 0
              AND (folder = #{folder} OR folder LIKE #{prefix} ESCAPE '!')
            """)
    List<Long> selectIdsByFolder(@Param("kbId") Long kbId,
                                 @Param("folder") String folder,
                                 @Param("prefix") String prefix);
}
