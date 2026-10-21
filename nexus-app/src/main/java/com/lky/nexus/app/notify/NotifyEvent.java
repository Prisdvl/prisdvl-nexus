package com.lky.nexus.app.notify;

/**
 * 可订阅的通知事件。
 *
 * <p>用固定字符串码而不是枚举名落库：事件码是<b>契约</b>（前端勾选框、通道的 {@code events}
 * 列、webhook 请求体都用它），枚举改名不应该让存量通道静默失效。
 *
 * <p>目前只有导入相关两条 —— 先做真正有触发源的，聊天完成、配额告警这些还没有服务端触发点，
 * 提前放上来只会变成永远不亮的开关。
 */
public enum NotifyEvent {

    IMPORT_DONE("import.done", "导入完成"),
    IMPORT_FAILED("import.failed", "导入失败");

    private final String code;
    private final String label;

    NotifyEvent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    /** 服务端兜底文案；前端会用本地化文案覆盖。 */
    public String label() {
        return label;
    }

    /** 未知码返回 null（存量数据里可能有已下线的事件，读的时候要跳过而不是报错）。 */
    public static NotifyEvent ofCode(String code) {
        for (NotifyEvent event : values()) {
            if (event.code.equals(code)) {
                return event;
            }
        }
        return null;
    }
}
