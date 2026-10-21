package com.lky.nexus.app.auth.service;

import com.lky.nexus.app.auth.model.dto.TotpEnableResponse;
import com.lky.nexus.app.auth.model.dto.TotpSetupResponse;
import com.lky.nexus.app.auth.model.dto.TotpStatusResponse;

/**
 * 两步验证（TOTP，RFC 6238）。
 *
 * <p>三个动作刻意拆开：{@code beginSetup} 只写候选密钥、{@code enable} 才把账号标成"需要第二因子"
 * 并下发恢复码。中间这一步是防呆：用户扫完码必须回填一枚当前验证码，
 * 证明密钥真的进了他手机上的验证器——否则一次误点就把账号锁死在自己手里。
 */
public interface TwoFactorService {

    /** 当前状态（是否开启、恢复码还剩几枚、绑定时间）。 */
    TotpStatusResponse status(Long userId);

    /**
     * 生成（或重新生成）候选密钥。
     *
     * <p>已开启时拒绝：换密钥要走"先关闭再开启"，否则一次请求就能把用户的验证器作废。
     * 返回的明文密钥只出现这一次，服务端存密文。
     */
    TotpSetupResponse beginSetup(Long userId);

    /**
     * 校验一枚验证码并正式启用，返回<b>一次性</b>的明文恢复码。
     *
     * <p>每次启用都重新生成整套恢复码：旧的一套在换绑后已经不对应任何真实设备，
     * 留着只会让人以为还有退路。
     */
    TotpEnableResponse enable(Long userId, String code);

    /** 关闭两步验证（需验密码），同时清空密钥与恢复码。 */
    void disable(Long userId, String password);

    /**
     * 登录第二因子校验：接受 TOTP 码，或一枚未使用的恢复码（登录路径用）。
     *
     * @throws com.lky.nexus.common.exception.BusinessException 码无效时抛
     *         {@code TOTP_CODE_INVALID}；调用方负责在缺码时先抛 {@code TOTP_REQUIRED}
     */
    void verifyLogin(Long userId, String code);
}
