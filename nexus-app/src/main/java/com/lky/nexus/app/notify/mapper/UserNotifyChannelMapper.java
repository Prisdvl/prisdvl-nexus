package com.lky.nexus.app.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.notify.model.entity.UserNotifyChannelPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知通道 Mapper。
 *
 * <p>查询按 {@code user_id} 过滤全部写在 Service 的 {@code LambdaQueryWrapper} 里 ——
 * 这里不提供「只按 id 查」的方法，避免有人用 id 直接取到别人的通道。
 */
@Mapper
public interface UserNotifyChannelMapper extends BaseMapper<UserNotifyChannelPO> {
}
