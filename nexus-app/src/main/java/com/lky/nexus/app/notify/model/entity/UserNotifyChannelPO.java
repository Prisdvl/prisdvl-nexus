package com.lky.nexus.app.notify.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户自己维护的通知通道（{@code user_notify_channel}）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code target}：Webhook 是 URL、邮件是地址，按 {@code type} 解释；</li>
 *   <li>{@code secret}：Webhook 的 HMAC 密钥，{@code CredentialCipher} 加密后存（{@code v1:} 前缀），
 *       对外只回「有没有」；</li>
 *   <li>{@code events}：逗号分隔的事件码（{@link com.lky.nexus.app.notify.NotifyEvent#code()}）；</li>
 *   <li>{@code last*}：最近一次投递结果，不做历史表 —— 用户只需要看见「上次成没成、报了什么」。</li>
 * </ul>
 */
@TableName("user_notify_channel")
public class UserNotifyChannelPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String name;

    private String type;

    private String target;

    private String secret;

    /** 逗号分隔的事件码，空串表示「一个都不订阅」。 */
    private String events;

    private Integer enabled;

    private String lastStatus;

    private String lastError;

    private LocalDateTime lastAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getLastAt() {
        return lastAt;
    }

    public void setLastAt(LocalDateTime lastAt) {
        this.lastAt = lastAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
