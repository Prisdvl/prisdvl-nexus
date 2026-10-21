package com.lky.nexus.app.auth.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 两步验证的一次性恢复码（表 {@code user_recovery_code}，迁移 V26）。
 *
 * <p>只存 SHA-256 十六进制摘要：恢复码本身就是"第二把钥匙"，
 * 明文落库等于把这些钥匙摊在备份里。{@code usedAt} 非空即视为已消费，
 * 消费走条件更新（{@code WHERE used_at IS NULL}），避免并发下同一枚码用两次。
 */
@Data
@TableName("user_recovery_code")
public class UserRecoveryCodePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 恢复码的 SHA-256 十六进制（64 字符）。 */
    private String codeHash;

    /** 非空 = 已使用。 */
    private LocalDateTime usedAt;

    private LocalDateTime createdAt;
}
