package com.lky.prisma.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Prisma 应用入口。
 *
 * <p>位于 com.lky.prisma 之下，使 @SpringBootApplication 的组件扫描覆盖
 * prisma-app / prisma-knowledge（prisma-common 无 Spring 组件）中的全部 Bean。
 */
@SpringBootApplication(scanBasePackages = "com.lky.prisma")
public class PrismaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismaApplication.class, args);
    }
}
