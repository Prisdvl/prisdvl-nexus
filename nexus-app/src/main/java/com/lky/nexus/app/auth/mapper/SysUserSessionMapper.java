package com.lky.nexus.app.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.auth.model.entity.SysUserSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 用户登录会话 Mapper。
 *
 * <p>并发 refresh 的竞争控制写在注解 SQL：{@code UPDATE ... WHERE revoked = 0}
 * 只有一行更新成功的请求才能签发新对，其余一律按无效 refresh 处理。
 */
@Mapper
public interface SysUserSessionMapper extends BaseMapper<SysUserSessionPO> {

    /**
     * 条件撤销：仅当仍未撤销时成功。返回 1 = 抢到撤销权；0 = 已撤销或行不存在。
     */
    @Update("""
            UPDATE sys_user_session
               SET revoked = 1,
                   revoked_at = #{revokedAt}
             WHERE id = #{id}
               AND revoked = 0
            """)
    int revokeIfActive(@Param("id") Long id, @Param("revokedAt") LocalDateTime revokedAt);

    /**
     * 撤销某用户全部未撤销会话（改密 / 重置密码 / 全设备退出）。
     */
    @Update("""
            UPDATE sys_user_session
               SET revoked = 1,
                   revoked_at = #{revokedAt}
             WHERE user_id = #{userId}
               AND revoked = 0
            """)
    int revokeAllActiveForUser(@Param("userId") Long userId, @Param("revokedAt") LocalDateTime revokedAt);
}
