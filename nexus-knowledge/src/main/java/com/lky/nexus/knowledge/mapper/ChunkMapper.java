package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
