package com.lky.nexus.app.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.auth.model.entity.UserRecoveryCodePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 两步验证恢复码 Mapper。
 *
 * <p>消费用条件更新而不是"先查再改"：两枚并发请求拿同一枚码时，
 * 只有一行能更新成功（返回 1），另一行按"已使用"处理。
 */
@Mapper
public interface UserRecoveryCodeMapper extends BaseMapper<UserRecoveryCodePO> {

    /** 未使用的恢复码数量（设置页「还剩 N 枚」）。 */
    @Select("""
            SELECT COUNT(*)
              FROM user_recovery_code
             WHERE user_id = #{userId}
               AND used_at IS NULL
            """)
    long countUnused(@Param("userId") Long userId);

    /**
     * 条件消费：仅当该码存在、属于该用户且未使用时成功。
     * 返回 1 = 本次请求消费成功；0 = 不存在 / 已用过 / 不属于该用户。
     */
    @Update("""
            UPDATE user_recovery_code
               SET used_at = #{usedAt}
             WHERE user_id = #{userId}
               AND code_hash = #{codeHash}
               AND used_at IS NULL
            """)
    int consumeIfUnused(@Param("userId") Long userId,
                        @Param("codeHash") String codeHash,
                        @Param("usedAt") LocalDateTime usedAt);
}
