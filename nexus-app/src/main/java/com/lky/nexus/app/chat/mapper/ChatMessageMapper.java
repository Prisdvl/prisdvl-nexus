package com.lky.nexus.app.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息表 Mapper。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessagePO> {
}
