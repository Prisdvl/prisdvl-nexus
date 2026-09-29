package com.lky.akh.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Knowledge Hub 应用入口。
 *
 * <p>位于 com.lky.akh 之下，使 @SpringBootApplication 的组件扫描覆盖
 * akh-app / akh-knowledge（akh-common 无 Spring 组件）中的全部 Bean。
 */
@SpringBootApplication(scanBasePackages = "com.lky.akh")
public class AkhApplication {

    public static void main(String[] args) {
        SpringApplication.run(AkhApplication.class, args);
    }
}
