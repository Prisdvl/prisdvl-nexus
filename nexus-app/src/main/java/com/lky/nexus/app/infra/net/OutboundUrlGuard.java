package com.lky.nexus.app.infra.net;

import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.api.ErrorCode;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * 用户可填的「对外地址」守卫（SSRF 第一道闸）。
 *
 * <p>目前有两处会替用户出网：自定义嵌入端点的保存探针（{@code CustomEmbeddingProbe}）与
 * 通知通道的 Webhook 投递（{@code WebhookSender}）。两处规则必须一致，否则会出现
 * 「探针拦住、投递放行」这种只在一边成立的假防护，所以规则只写在这里。
 *
 * <p>它拦的是<b>地址形态</b>：协议必须是 http/https、主机必须能解析、解析出的每个地址都不能是
 * 环回 / 私网 / 链路本地 / 组播。它<b>不</b>拦 DNS rebinding —— 解析发生在调用这一刻，
 * 运行期真正建连时还会再解析一次；这一点写在 docs/security.md 的已知边界里，不在这里假装解决。
 */
public final class OutboundUrlGuard {

    private OutboundUrlGuard() {
    }

    /** 解析并校验一个用户填写的 Base URL / 回调地址；返回可用的 {@link URI}。 */
    public static URI parseHttpUrl(String raw, String fieldLabel) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, fieldLabel + "不能为空");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, fieldLabel + "不是合法的 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, fieldLabel + "只支持 http / https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, fieldLabel + "缺少主机名");
        }
        return uri;
    }

    /**
     * 拒绝指向内网的主机（含 localhost 与私有 / 链路本地 IP）。
     *
     * <p>用解析后的地址判断而不是字符串匹配：{@code http://127.0.0.1}、{@code http://[::1]}、
     * {@code http://internal-host} 都得拦住。
     */
    public static void requirePublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "无法解析主机名：" + host);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "地址不能指向内网或本机（" + host + "）");
            }
        }
    }

    /**
     * 解析 + 内网判定一步到位：用户填的对外地址要么能用，要么带着可读理由被拒。
     *
     * <p>{@code allowPrivate} 只给本地开发/测试放行（{@code nexus.notify.webhook.allow-private-endpoints}
     * 默认 false，standalone/test 打开），因为本地验证投递必须能打 {@code http://127.0.0.1:9099}。
     * 生产保持 false —— 这跟「让用户填任意 URL」是两件事，后者等于把内网端口扫描交给用户。
     */
    public static URI requireHttpTarget(String raw, String fieldLabel, boolean allowPrivate) {
        URI uri = parseHttpUrl(raw, fieldLabel);
        if (!allowPrivate) {
            requirePublicHost(uri.getHost());
        }
        return uri;
    }
}
