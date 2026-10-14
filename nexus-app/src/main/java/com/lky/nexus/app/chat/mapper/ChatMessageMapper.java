package com.lky.nexus.app.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息表（chat_message）的 MyBatis-Plus Mapper。
 *
 * <p>本接口不声明任何方法：增删改查全部继承自 {@code BaseMapper}；
 * 表名、字段映射、JSON 列的 {@code typeHandler} 等均来自
 * {@link com.lky.nexus.app.chat.model.entity.ChatMessagePO} 上的注解。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessagePO> {
}
