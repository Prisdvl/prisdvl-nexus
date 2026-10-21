package com.lky.nexus.app.auth.email;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试用发信器：记录每次 {@link #sendCode}，供断言捕获明文验证码（不依赖 HTTP 响应）。
 */
public final class RecordingEmailSender implements EmailSender {

    public final List<Event> events = new CopyOnWriteArrayList<>();
    /** 通知（非验证码）也记下来，供通知通道的用例断言"到底发了什么"。 */
    public final List<Notice> notices = new CopyOnWriteArrayList<>();

    @Override
    public void sendCode(String email, String code, EmailPurpose purpose, Duration expire) {
        events.add(new Event(email, code, purpose, expire));
    }

    @Override
    public void sendNotice(String email, String title, String body) {
        notices.add(new Notice(email, title, body));
    }

    public Notice lastNotice() {
        assertThat(notices).isNotEmpty();
        return notices.get(notices.size() - 1);
    }

    public String lastCode() {
        assertThat(events).isNotEmpty();
        return events.get(events.size() - 1).code();
    }

    public void clear() {
        events.clear();
    }

    public record Event(String email, String code, EmailPurpose purpose, Duration expire) {
    }

    public record Notice(String email, String title, String body) {
    }
}
