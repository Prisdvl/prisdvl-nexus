package com.lky.nexus.app.infra.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 数据源哨兵：在 local / prod profile 下**强制**确认连的是 MySQL。
 *
 * <p><b>为什么需要它</b>（真实踩坑）：H2 依赖是 {@code runtime} 作用域（为了让 standalone 开发
 * profile 能启动）。这带来一个危险行为——<b>当某个 profile 没有配置 spring.datasource.url 时，
 * Spring Boot 的 DataSourceAutoConfiguration 会静默退回"嵌入式数据库"自动配置</b>，
 * 于是应用照常启动、接口照常工作，但数据其实写进了进程内存里的临时库，
 * 重启即消失，而且与真实数据库的表结构毫无关系。这种"看起来一切正常"的错误最难发现。
 *
 * <p>因此这里在启动完成时做一次显式检查：连上之后看 JDBC URL，
 * 不是 MySQL 就直接让启动失败，并给出可操作的提示。
 * 只在 local / prod 生效；test 与 standalone 本来就该用 H2。
 */
@Component
@Profile({"local", "prod"})
public class DatasourceGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasourceGuard.class);

    private final DataSource dataSource;

    public DatasourceGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url == null || !url.startsWith("jdbc:mysql")) {
                throw new IllegalStateException("""
                        local / prod profile 期望连接 MySQL，实际连的是：%s
                        常见原因：application-local.yml 没有被加载（例如 jar 是旧的、忘了重新打包），
                        而 H2 在 runtime 作用域会让 Spring Boot 静默退回嵌入式内存库。
                        请重新执行 `mvn -B clean package -DskipTests` 后，再以 --spring.profiles.active=local 启动。"""
                        .formatted(url));
            }
            log.info("数据源检查通过：{}", url.substring(0, url.indexOf('?') > 0 ? url.indexOf('?') : url.length()));
        }
    }
}
