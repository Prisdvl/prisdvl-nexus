package com.lky.nexus.app.notify;

import com.lky.nexus.app.notify.model.dto.NotifyChannelRequest;
import com.lky.nexus.app.notify.model.dto.NotifyChannelResponse;

import java.util.List;

/**
 * 通知通道与投递。
 *
 * <p>与 {@code nexus.auth.email.EmailSender} 的关系：邮件通道复用同一个发信端口
 * （{@code sendNotice}），Webhook 走 {@link WebhookSender}；本服务只负责「谁订阅了什么、
 * 发完把结果记在哪」。
 */
public interface NotifyService {

    /** 当前用户的所有通道（含停用的；停用也要能看见并重新打开）。 */
    List<NotifyChannelResponse> list(Long userId);

    NotifyChannelResponse create(Long userId, NotifyChannelRequest request);

    NotifyChannelResponse update(Long userId, Long channelId, NotifyChannelRequest request);

    void delete(Long userId, Long channelId);

    /** 给单个通道发一条测试消息，并把结果写进 last_*。 */
    NotifyChannelResponse test(Long userId, Long channelId);

    /**
     * 把事件投递给该用户所有订阅了它、且启用的通道。
     *
     * <p>失败不抛给调用方（导入任务不该因为用户填的 Webhook 挂了而失败），只记日志与 last_*。
     */
    void dispatch(Long userId, NotifyEvent event, String title, String body);
}
