package com.lky.nexus.app.infra.mybatis;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p><b>为什么必须有这个类</b>：MyBatis-Plus 的分页是"插件式"的。
 * 如果只调用 {@code mapper.selectPage(...)} 而没注册 {@link PaginationInnerInterceptor}，
 * 它<b>不会报错</b>，而是安静地把 SQL 变成不带 LIMIT 的全表查询——
 * 接口看起来正常，实际上是把整表读进内存再截断（既慢又可能 OOM）。
 * 这是 MyBatis-Plus 最容易踩的坑之一，因此这里显式注册，并写测试断言分页真的生效。
 *
 * <p><b>依赖注意</b>：自 MyBatis-Plus 3.5.9 起，{@code PaginationInnerInterceptor} 已从
 * {@code mybatis-plus-extension} 移到 {@code mybatis-plus-jsqlparser} 模块
 * （因为 JSqlParser 被拆成了可选依赖）。只引入 starter 会编译失败或功能缺失。
 *
 * <p>不写死 {@code DbType}：让插件根据 JDBC URL 自动识别方言，
 * 这样生产用 MySQL、测试用 H2（MySQL 兼容模式）都能生成正确的 LIMIT 语法。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
