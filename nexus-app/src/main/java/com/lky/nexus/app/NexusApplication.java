package com.lky.nexus.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Prisdvl-Nexus 应用入口。
 *
 * <p>位于 com.lky.nexus 之下，使 @SpringBootApplication 的组件扫描覆盖
 * nexus-app / nexus-knowledge（nexus-common 无 Spring 组件）中的全部 Bean。
 */
@SpringBootApplication(scanBasePackages = "com.lky.nexus")
public class NexusApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusApplication.class, args);
    }
}
