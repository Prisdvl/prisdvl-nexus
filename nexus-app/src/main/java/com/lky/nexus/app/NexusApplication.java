package com.lky.nexus.app;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Prisdvl-Nexus 应用入口。
 *
 * <p>组件扫描覆盖 {@code com.lky.nexus} 全树，使 {@code nexus-app} 与 {@code nexus-knowledge}
 * 中的 Bean 都被装配（{@code nexus-common} 无 Spring 组件）。
 *
 * <p><b>必须显式声明 {@code @MapperScan}</b>（真实踩坑）：MyBatis 的 Mapper 自动扫描
 * 默认只覆盖"启动类所在包及其子包"，也就是只有 {@code com.lky.nexus.app.**} 会被扫到。
 * 而按分层设计，{@code kb_*} 的 Mapper 位于 {@code com.lky.nexus.knowledge.mapper}，
 * 结果是：应用能启动（没有报"找不到 Mapper"这种直观错误），一注入就抛
 * {@code No qualifying bean of type ...Mapper}。
 *
 * <p>这里用"扫全树 + 只认 @Mapper 注解"的写法，而不是写死一串包名：
 * 后续新增模块（例如 Phase 7 的 {@code nexus-mcp-server}）时不需要再改这里。
 */
@SpringBootApplication(scanBasePackages = "com.lky.nexus")
@MapperScan(basePackages = "com.lky.nexus", annotationClass = Mapper.class)
public class NexusApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusApplication.class, args);
    }
}
