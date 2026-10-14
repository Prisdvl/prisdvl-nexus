package com.lky.nexus.app.auth.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户登录会话（表 {@code sys_user_session}，迁移 V21）。
 *
 * <p>一行对应一台设备上的一支 refresh token。{@code token_hash} 为 SHA-256 摘要，
 * 绝不保存明文。撤销后行保留（审计 / 列表），不再参与 refresh。
 */
@Data
@TableName("sys_user_session")
public class SysUserSessionPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** refresh token 的 SHA-256 十六进制（64 字符）。 */
    private String tokenHash;

    private String deviceName;

    private String userAgent;

    private String ipAddress;

    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime expiresAt;

    /** 1 = 已撤销；0 = 有效。 */
    private Integer revoked;

    private LocalDateTime revokedAt;
}
