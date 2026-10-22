package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 切片 Mapper。
 *
 * <p>额外提供批量插入：一篇笔记可能有几十个切片，一个 Vault 上千篇就是几万行。
 * 逐条 insert 会产生几万次数据库往返（本地可能还好，跨网络就是灾难），
 * 批量插入把它压到几十次——这是"批量导入"必须做的优化，而不是过早优化。
 */
@Mapper
public interface ChunkMapper extends BaseMapper<ChunkPO> {

    /**
     * 批量插入切片。调用方需自行填充 {@code id}（雪花）。
     *
     * <p>注意：绕过 MyBatis-Plus 的自动填充，因此 {@code created_at/updated_at} 也由 SQL 写入。
     */
    @Insert("""
            <script>
            INSERT INTO kb_chunk (id, kb_id, document_id, user_id, chunk_index, chunk_type,
                                  content, raw_content, content_hash, heading, heading_path, heading_level,
                                  start_offset, end_offset, token_count, char_count,
                                  vector_status, es_status, created_at, updated_at)
            VALUES
            <foreach collection="chunks" item="c" separator=",">
                (#{c.id}, #{c.kbId}, #{c.documentId}, #{c.userId}, #{c.chunkIndex}, #{c.chunkType},
                 #{c.content}, #{c.rawContent}, #{c.contentHash}, #{c.heading}, #{c.headingPath}, #{c.headingLevel},
                 #{c.startOffset}, #{c.endOffset}, #{c.tokenCount}, #{c.charCount},
                 #{c.vectorStatus}, #{c.esStatus}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            </foreach>
            </script>
            """)
    int insertBatch(@Param("chunks") List<ChunkPO> chunks);

    /**
     * 某知识库的切片 token 总数（在数据库里聚合，不把行读进内存）。
     *
     * <p>为什么值得单独写一条 SQL：重算知识库统计在<b>每次文档删除</b>之后都会调用
     * （见 {@code DocumentPersistServiceImpl}），而一个真实知识库的切片是几万到几十万行。
     * 原先的做法是 {@code selectList} 把整库切片的 {@code token_count} 拉回 Java 再求和——
     * 删一篇文档的代价随全库规模线性增长，而且传的是几十万行数据。
     *
     * <p>{@code COALESCE} 不是防御性编程：空库（或切片全被删掉）时 {@code SUM} 返回
     * {@code NULL}，直接映射到 {@code long} 会抛 NPE——这是"删掉最后一篇文档"必然走到的路径。
     */
    @Select("SELECT COALESCE(SUM(token_count), 0) FROM kb_chunk WHERE kb_id = #{kbId}")
    long sumTokenCount(@Param("kbId") Long kbId);

    /**
     * 该知识库的切片总数。
     *
     * <p>与 {@link #countIndexedByKb} 成对使用才表达得清「导入 ≠ 建索引」：
     * 前者大于 0 而后者等于 0，就是这个库还没建过索引——检索永远不可能命中，
     * 而界面上只显示「0 条结果」，用户会以为检索坏了。
     */
    @Select("SELECT COUNT(*) FROM kb_chunk WHERE kb_id = #{kbId}")
    long countByKb(@Param("kbId") Long kbId);

    /** 已写入检索索引（{@code es_status = 'DONE'}）的切片数。 */
    @Select("SELECT COUNT(*) FROM kb_chunk WHERE kb_id = #{kbId} AND es_status = 'DONE'")
    long countIndexedByKb(@Param("kbId") Long kbId);

    /**
     * 最近一次把切片写进索引的时间；该库从未建过索引时返回 {@code null}。
     *
     * <p>用专用的 {@code indexed_at}（V29）而不是 {@code updated_at}：后者会被任何字段更新带跑
     * （重新分块、改标签、甚至清空 PENDING），拿它当「建索引时间」会把「刚改过但没重建索引」
     * 的库显示成已索引。这里仍带 {@code es_status = 'DONE'} 过滤，PENDING 期间喂不进假时间。
     */
    @Select("SELECT MAX(indexed_at) FROM kb_chunk WHERE kb_id = #{kbId} AND es_status = 'DONE'")
    java.time.LocalDateTime lastIndexedAt(@Param("kbId") Long kbId);
}
