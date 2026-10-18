package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 用户自定义嵌入凭据 Mapper。 */
@Mapper
public interface UserEmbeddingConfigMapper extends BaseMapper<UserEmbeddingConfigPO> {
}
