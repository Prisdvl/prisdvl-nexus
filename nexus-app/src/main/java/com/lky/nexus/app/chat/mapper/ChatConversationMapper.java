package com.lky.nexus.app.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话表 Mapper。
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversationPO> {
}
