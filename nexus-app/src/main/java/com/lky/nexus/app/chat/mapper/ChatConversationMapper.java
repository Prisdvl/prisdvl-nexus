package com.lky.nexus.app.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话表（chat_conversation）的 MyBatis-Plus Mapper。
 *
 * <p>本接口不声明任何方法：增删改查全部继承自 {@code BaseMapper}；
 * 表名、字段映射、逻辑删除、JSON 列的 {@code typeHandler} 等均来自
 * {@link com.lky.nexus.app.chat.model.entity.ChatConversationPO} 上的注解。
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversationPO> {
}
