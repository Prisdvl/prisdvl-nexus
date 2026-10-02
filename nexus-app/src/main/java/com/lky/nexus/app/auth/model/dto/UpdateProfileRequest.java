package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 修改个人资料请求（5.6）。
 *
 * <p>只放开 {@code nickname} 与 {@code email} 两个字段——用户名不可改
 * （它是登录凭证的一部分，改了会让用户困惑于"我到底该用哪个登录"）。
 *
 * <p><b>字段语义（半更新）</b>，两条规则要一起看：
 * <ul>
 *   <li>{@code null}（请求里不带这个字段）= <b>不修改</b>；</li>
 *   <li>空串 / 空白 = <b>清空</b>（邮箱清空后落 NULL；昵称清空后界面回退显示用户名，
 *       前端本来就是 {@code nickname || username}）。</li>
 * </ul>
 * 两条合起来意味着"不能只传一个字段就把另一个抹掉"，这是接口安全性的一部分：
 * 若把"缺失"当成"清空"，前端少渲染一个输入框就会静默删掉用户数据。
 *
 * <p>为什么要区分 {@code null} 与空串：{@code updateById} 默认跳过 null 字段
 * （MyBatis-Plus 的 NOT_NULL 策略），所以"清空"必须走显式 {@code set(col, null)}。
 * 这两种意图在 SQL 上是不同写法，在 DTO 里就必须是不同取值。
 */
public record UpdateProfileRequest(

        @Size(max = 64, message = "昵称长度不能超过 64")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱长度不能超过 128")
        String email) {
}
