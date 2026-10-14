package com.lky.nexus.app.auth.email;

/**
 * {@link EmailCodeStore#verifyAndConsume} 的原子结果。
 * 服务层据此映射到 {@link com.lky.nexus.common.api.ErrorCode}，不向外抛字符串。
 */
public enum EmailCodeVerifyResult {

    /** 校验通过且已消费（键已删除）。 */
    SUCCESS,

    /** 无记录或已过期 / 已消费。 */
    MISSING,

    /** 码不匹配，attempts 已 +1（未达上限）。 */
    MISMATCH,

    /** 错误次数达到上限，验证码已作废。 */
    EXHAUSTED
}
