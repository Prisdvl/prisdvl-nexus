package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import org.apache.ibatis.annotations.Delete;
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
     * 物理删除同一 {@code (kb_id, source_path)} 上的历史墓碑（{@code deleted = 1} 的行）。
     *
     * <p><b>为什么必须有这一步</b>：唯一键是 {@code uk_doc_kb_path (kb_id, source_path, deleted)}
     * （见 V4），而 {@code deleted} 是 MyBatis-Plus 的逻辑删除列。逻辑删除一行会写入
     * {@code deleted = 1}，于是这个键对"已删除的同一路径"只能用<b>一次</b>——
     * 第二次删除同路径的文档（重复导入之后再删、或删完再传再删）会撞唯一键，
     * 报成一句与现场毫无关系的 {@code Duplicate entry}。
     *
     * <p>把"已删除的同路径行"当成墓碑清掉，是这套键唯一自洽的用法：
     * 墓碑没有承载任何信息（逻辑删除的审计价值在 {@code kb_document} 这一行本身，
     * 而这里删掉的是更早的那一行）。
     *
     * <p>原生 SQL 会绕过 {@code @TableLogic}，这里正是要它绕过——目标行本来就是要物理删除的。
     * 也因为它绕过了逻辑删除，{@code deleted = 1} 必须显式写在条件里（同 {@link #selectIdsByFolder}）。
     */
    @Delete("""
            DELETE FROM kb_document
            WHERE kb_id = #{kbId} AND source_path = #{sourcePath} AND deleted = 1
            """)
    int purgePathTombstones(@Param("kbId") Long kbId, @Param("sourcePath") String sourcePath);

    /**
     * 物理删除整个知识库下的墓碑。
     *
     * <p>删除知识库时，库里所有存活文档会被一次性逻辑删除；若其中某条路径上
     * 已经躺着一个早先删除留下的墓碑，同样会撞 {@code uk_doc_kb_path}。
     * 与 {@link #purgePathTombstones} 是同一件事的两个粒度。
     */
    @Delete("DELETE FROM kb_document WHERE kb_id = #{kbId} AND deleted = 1")
    int purgeKnowledgeBaseTombstones(@Param("kbId") Long kbId);

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
