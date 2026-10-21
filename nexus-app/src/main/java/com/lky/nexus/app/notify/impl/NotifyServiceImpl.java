package com.lky.nexus.app.notify.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.auth.email.EmailSender;
import com.lky.nexus.app.infra.net.OutboundUrlGuard;
import com.lky.nexus.app.notify.NotifyChannelType;
import com.lky.nexus.app.notify.NotifyEvent;
import com.lky.nexus.app.notify.NotifyService;
import com.lky.nexus.app.notify.WebhookSender;
import com.lky.nexus.app.notify.mapper.UserNotifyChannelMapper;
import com.lky.nexus.app.notify.model.dto.NotifyChannelRequest;
import com.lky.nexus.app.notify.model.dto.NotifyChannelResponse;
import com.lky.nexus.app.notify.model.entity.UserNotifyChannelPO;
import com.lky.nexus.app.security.CredentialCipher;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 通知通道的读写与投递实现。
 *
 * <p>三条贯穿全类的规则：
 * <ol>
 *   <li><b>通道只能由属主操作</b>：所有按 id 取通道的查询都同时带 {@code user_id} 条件，
 *       越权与不存在同样回 3010（不泄露"这个 id 存在"）；</li>
 *   <li><b>密钥只进不出</b>：写的时候加密，读的时候只回 {@code hasSecret}；</li>
 *   <li><b>投递失败不影响业务</b>：{@link #dispatch} 里 catch 掉一切异常并写进 last_*，
 *       导入任务不会因为用户填了个挂掉的 Webhook 而变成失败。</li>
 * </ol>
 */
@Service
public class NotifyServiceImpl implements NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyServiceImpl.class);

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_NAME = 100;
    private static final int MAX_ERROR = 500;

    private final UserNotifyChannelMapper channelMapper;
    private final CredentialCipher credentialCipher;
    private final EmailSender emailSender;
    private final WebhookSender webhookSender;
    private final int maxChannels;

    public NotifyServiceImpl(UserNotifyChannelMapper channelMapper,
                             CredentialCipher credentialCipher,
                             EmailSender emailSender,
                             WebhookSender webhookSender,
                             @Value("${nexus.notify.max-channels:10}") int maxChannels) {
        this.channelMapper = channelMapper;
        this.credentialCipher = credentialCipher;
        this.emailSender = emailSender;
        this.webhookSender = webhookSender;
        this.maxChannels = maxChannels;
    }

    @Override
    public List<NotifyChannelResponse> list(Long userId) {
        return ownedChannels(userId).stream().map(NotifyServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional
    public NotifyChannelResponse create(Long userId, NotifyChannelRequest request) {
        if (ownedChannels(userId).size() >= maxChannels) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "最多只能创建 " + maxChannels + " 个通知通道");
        }
        UserNotifyChannelPO po = new UserNotifyChannelPO();
        po.setUserId(userId);
        apply(po, request, true);
        po.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        po.setCreatedAt(LocalDateTime.now());
        channelMapper.insert(po);
        return toResponse(po);
    }

    @Override
    @Transactional
    public NotifyChannelResponse update(Long userId, Long channelId, NotifyChannelRequest request) {
        UserNotifyChannelPO po = requireOwned(userId, channelId);
        apply(po, request, false);
        if (request.enabled() != null) {
            po.setEnabled(request.enabled() ? 1 : 0);
        }
        po.setUpdatedAt(LocalDateTime.now());
        channelMapper.updateById(po);
        return toResponse(po);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long channelId) {
        requireOwned(userId, channelId);
        channelMapper.delete(Wrappers.<UserNotifyChannelPO>lambdaQuery()
                .eq(UserNotifyChannelPO::getId, channelId)
                .eq(UserNotifyChannelPO::getUserId, userId));
    }

    @Override
    public NotifyChannelResponse test(Long userId, Long channelId) {
        UserNotifyChannelPO po = requireOwned(userId, channelId);
        // 测试消息用真实事件码 + 明说是测试：用户拿它调通群机器人即可，不需要再造一个"test"事件
        deliver(po, NotifyEvent.IMPORT_DONE,
                "[测试] Prisdvl Nexus 通知",
                "这是一条测试消息：收到它说明通道配置可用。");
        return toResponse(channelMapper.selectById(po.getId()));
    }

    @Override
    public void dispatch(Long userId, NotifyEvent event, String title, String body) {
        List<UserNotifyChannelPO> channels = ownedChannels(userId);
        List<UserNotifyChannelPO> targets = new ArrayList<>();
        for (UserNotifyChannelPO channel : channels) {
            if (channel.getEnabled() == null || channel.getEnabled() != 1) {
                continue;
            }
            if (parseEvents(channel.getEvents()).contains(event.code())) {
                targets.add(channel);
            }
        }
        for (UserNotifyChannelPO channel : targets) {
            try {
                deliver(channel, event, title, body);
            } catch (RuntimeException ex) {
                // deliver 内部已经写过 last_*；这里只为"一个通道炸了不影响其它通道"
                log.warn("通知投递异常 channelId={} event={}", channel.getId(), event.code(), ex);
            }
        }
    }

    // ------------------------------------------------------------------

    /** 真正投递一条：成功写 OK，失败写 FAILED + 可读原因。 */
    private void deliver(UserNotifyChannelPO channel, NotifyEvent event, String title, String body) {
        String status;
        String error = null;
        try {
            NotifyChannelType type = NotifyChannelType.of(channel.getType());
            switch (type) {
                case WEBHOOK -> webhookSender.send(channel.getTarget(), revealSecret(channel), event, title, body);
                case EMAIL -> emailSender.sendNotice(channel.getTarget(), title, body);
            }
            status = "OK";
        } catch (RuntimeException ex) {
            status = "FAILED";
            error = truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn("通知投递失败 channelId={} event={} reason={}", channel.getId(), event.code(), error);
        }
        channelMapper.update(null, Wrappers.<UserNotifyChannelPO>lambdaUpdate()
                .set(UserNotifyChannelPO::getLastStatus, status)
                .set(UserNotifyChannelPO::getLastError, error)
                .set(UserNotifyChannelPO::getLastAt, LocalDateTime.now())
                .eq(UserNotifyChannelPO::getId, channel.getId()));
    }

    /** 校验并写入 type / target / events / secret（secret 的三种语义见 {@link NotifyChannelRequest}）。 */
    private void apply(UserNotifyChannelPO po, NotifyChannelRequest request, boolean creating) {
        NotifyChannelType type;
        try {
            type = NotifyChannelType.of(request.type());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, ex.getMessage());
        }
        String target = request.target() == null ? "" : request.target().trim();
        if (target.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "目标地址不能为空");
        }
        if (type == NotifyChannelType.EMAIL) {
            if (!EMAIL.matcher(target).matches()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "邮箱地址格式不正确");
            }
        } else {
            // 保存时就验一遍地址形态与（生产下的）内网限制，别让用户等到第一次通知才发现填错
            OutboundUrlGuard.requireHttpTarget(target, "Webhook 地址",
                    webhookAllowsPrivate());
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "通道名称不能为空");
        }
        po.setName(name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name);
        po.setType(type.name());
        po.setTarget(target);
        // 一个事件都没选中的通道永远不会触发 —— 与其让它安静地躺在列表里，不如当场拒绝
        Set<String> events = normalizeEvents(request.events());
        if (events.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "至少要选择一个触发事件");
        }
        po.setEvents(String.join(",", events));

        String secret = request.secret();
        if (creating) {
            po.setSecret(secret == null || secret.isBlank() ? null : credentialCipher.encrypt(secret.trim()));
        } else if (secret != null) {
            // 空串 = 清掉；非空 = 换掉；null = 不动
            po.setSecret(secret.isBlank() ? null : credentialCipher.encrypt(secret.trim()));
        }
    }

    /** 只保留已知事件码、去重、保持提交顺序；未知码静默丢弃（前端版本错位时不该 500）。 */
    private static Set<String> normalizeEvents(List<String> raw) {
        Set<String> codes = new LinkedHashSet<>();
        if (raw == null) {
            return codes;
        }
        for (String item : raw) {
            NotifyEvent event = NotifyEvent.ofCode(item == null ? "" : item.trim());
            if (event != null) {
                codes.add(event.code());
            }
        }
        return codes;
    }

    private static Set<String> parseEvents(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private String revealSecret(UserNotifyChannelPO po) {
        String stored = po.getSecret();
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return credentialCipher.decrypt(stored);
    }

    private boolean webhookAllowsPrivate() {
        return webhookSender != null && webhookSender.allowsPrivateEndpoints();
    }

    private List<UserNotifyChannelPO> ownedChannels(Long userId) {
        return channelMapper.selectList(Wrappers.<UserNotifyChannelPO>lambdaQuery()
                .eq(UserNotifyChannelPO::getUserId, userId)
                .orderByDesc(UserNotifyChannelPO::getCreatedAt));
    }

    private UserNotifyChannelPO requireOwned(Long userId, Long channelId) {
        UserNotifyChannelPO po = channelMapper.selectOne(Wrappers.<UserNotifyChannelPO>lambdaQuery()
                .eq(UserNotifyChannelPO::getId, channelId)
                .eq(UserNotifyChannelPO::getUserId, userId));
        if (po == null) {
            throw new BusinessException(ErrorCode.NOTIFY_CHANNEL_NOT_FOUND);
        }
        return po;
    }

    private static NotifyChannelResponse toResponse(UserNotifyChannelPO po) {
        return new NotifyChannelResponse(
                po.getId(),
                po.getName(),
                po.getType(),
                po.getTarget(),
                po.getSecret() != null && !po.getSecret().isBlank(),
                List.copyOf(parseEvents(po.getEvents())),
                po.getEnabled() != null && po.getEnabled() == 1,
                po.getLastStatus(),
                po.getLastError(),
                po.getLastAt());
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR ? text : text.substring(0, MAX_ERROR) + "…";
    }
}
