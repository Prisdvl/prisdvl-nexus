package com.lky.nexus.app.notify;

import com.lky.nexus.app.auth.email.RecordingEmailSender;
import com.lky.nexus.app.notify.impl.NotifyServiceImpl;
import com.lky.nexus.app.notify.mapper.UserNotifyChannelMapper;
import com.lky.nexus.app.notify.model.dto.NotifyChannelRequest;
import com.lky.nexus.app.notify.model.dto.NotifyChannelResponse;
import com.lky.nexus.app.notify.model.entity.UserNotifyChannelPO;
import com.lky.nexus.app.security.CredentialCipher;
import com.lky.nexus.app.security.NexusSecurityProperties;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NotifyServiceImpl")
class NotifyServiceImplTest {

    /** 与 application-test.yml 同一把测试 KEK（base64 32 字节）。 */
    private static final String TEST_KEK = "FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0=";

    private UserNotifyChannelMapper mapper;
    private CredentialCipher cipher;
    private RecordingEmailSender emailSender;
    private WebhookSender webhookSender;
    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserNotifyChannelMapper.class);
        NexusSecurityProperties props = new NexusSecurityProperties();
        props.getCrypto().setKek(TEST_KEK);
        cipher = new CredentialCipher(props);
        emailSender = new RecordingEmailSender();
        // 指向一个必然连不上的端口：投递失败路径用它，成功路径走 EMAIL
        webhookSender = new WebhookSender(new com.fasterxml.jackson.databind.ObjectMapper(), true,
                Duration.ofMillis(300));
        service = new NotifyServiceImpl(mapper, cipher, emailSender, webhookSender, 10);
    }

    private static NotifyChannelRequest emailRequest(String events, String secret) {
        return new NotifyChannelRequest("我的邮箱", "EMAIL", "me@example.com", secret, List.of(events), true);
    }

    @Test
    @DisplayName("创建：邮箱格式必须对，事件只保留已知码")
    void createValidatesAndNormalizes() {
        when(mapper.selectList(any())).thenReturn(List.of());

        NotifyChannelResponse created = service.create(7L, emailRequest("import.done", null));

        assertThat(created.events()).containsExactly("import.done");
        assertThat(created.hasSecret()).isFalse();
        ArgumentCaptor<UserNotifyChannelPO> captor = ArgumentCaptor.forClass(UserNotifyChannelPO.class);
        verify(mapper).insert((UserNotifyChannelPO) captor.capture());
        assertThat(captor.getValue().getEvents()).isEqualTo("import.done");
        assertThat(captor.getValue().getEnabled()).isEqualTo(1);
    }

    @Test
    @DisplayName("创建：邮箱格式不合法直接拒绝")
    void createRejectsBadEmail() {
        when(mapper.selectList(any())).thenReturn(List.of());
        NotifyChannelRequest bad = new NotifyChannelRequest("x", "EMAIL", "not-an-email", null,
                List.of("import.done"), true);

        assertThatThrownBy(() -> service.create(7L, bad))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        verify(mapper, never()).insert(any(UserNotifyChannelPO.class));
    }

    @Test
    @DisplayName("密钥：写入即加密，更新时 null 保留、空串清除")
    void secretHandling() {
        when(mapper.selectList(any())).thenReturn(List.of());
        service.create(7L, emailRequest("import.done", "s3cret"));

        ArgumentCaptor<UserNotifyChannelPO> insert = ArgumentCaptor.forClass(UserNotifyChannelPO.class);
        verify(mapper).insert(insert.capture());
        String stored = insert.getValue().getSecret();
        assertThat(stored).startsWith(CredentialCipher.VERSION_PREFIX);
        assertThat(stored).doesNotContain("s3cret");
        assertThat(cipher.decrypt(stored)).isEqualTo("s3cret");

        UserNotifyChannelPO existing = insert.getValue();
        existing.setId(9L);
        when(mapper.selectOne(any())).thenReturn(existing);

        service.update(7L, 9L, emailRequest("import.done", null));
        assertThat(existing.getSecret()).isEqualTo(stored);

        service.update(7L, 9L, emailRequest("import.done", ""));
        assertThat(existing.getSecret()).isNull();
    }

    @Test
    @DisplayName("越权与不存在同样回 3010（不泄露 id 是否存在）")
    void otherUsersChannelIsNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.update(7L, 42L, emailRequest("import.done", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.NOTIFY_CHANNEL_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("分发：只投给启用的、且订阅了该事件的通道")
    void dispatchFiltersByEnabledAndEvent() {
        UserNotifyChannelPO subscribed = channel(1L, 1, "import.done", "me@example.com");
        UserNotifyChannelPO disabled = channel(2L, 0, "import.done", "off@example.com");
        UserNotifyChannelPO otherEvent = channel(3L, 1, "import.failed", "fail@example.com");
        when(mapper.selectList(any())).thenReturn(List.of(subscribed, disabled, otherEvent));

        service.dispatch(7L, NotifyEvent.IMPORT_DONE, "标题", "正文");

        assertThat(emailSender.notices).hasSize(1);
        assertThat(emailSender.notices.get(0).email()).isEqualTo("me@example.com");
        assertThat(emailSender.notices.get(0).title()).isEqualTo("标题");
    }

    @Test
    @DisplayName("分发：Webhook 挂了只写 last_error，不抛给调用方")
    void dispatchSwallowsWebhookFailure() {
        UserNotifyChannelPO broken = channel(4L, 1, "import.done", "http://127.0.0.1:1/hook");
        broken.setType("WEBHOOK");
        when(mapper.selectList(any())).thenReturn(List.of(broken));

        service.dispatch(7L, NotifyEvent.IMPORT_DONE, "标题", "正文");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> update =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(eq(null), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("last_status").contains("last_error");
    }

    private static UserNotifyChannelPO channel(Long id, int enabled, String events, String target) {
        UserNotifyChannelPO po = new UserNotifyChannelPO();
        po.setId(id);
        po.setUserId(7L);
        po.setName("通道 " + id);
        po.setType("EMAIL");
        po.setTarget(target);
        po.setEvents(events);
        po.setEnabled(enabled);
        return po;
    }
}
