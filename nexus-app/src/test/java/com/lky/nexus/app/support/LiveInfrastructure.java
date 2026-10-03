package com.lky.nexus.app.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 联调测试（{@code Live*}）自带的基础设施（Phase 5.9 七）。
 *
 * <p><b>为什么要有这个类</b>：{@code LiveVectorStoreTest} / {@code LiveChunkIndexTest} 此前连的是
 * 开发者电脑上正在运行的那套业务容器（{@code deploy/docker-compose.yml} 起的那 8 个）。
 * 那是一类很隐蔽的问题：测试通过与否取决于"你此刻那台机器上跑着什么、里面有什么数据"。
 * 最坏的一次是真实的——某次测试把<strong>共用的那份 ES 索引整个清空</strong>，
 * 于是开发栈里 BM25 那条腿静默失效，而所有测试仍然是绿的。
 *
 * <p>现在改为每个用例组自己拉起容器，测完连容器一起销毁：
 * <ul>
 *   <li>测的是"适配器对不对"，而不是"我本机那套数据对不对"；</li>
 *   <li>再没有"清空共享索引/表"这种可能——容器本来就是一次性的；</li>
 *   <li>顺带让 {@code db/migration} 与 {@code deploy/postgres/init/01-extensions.sql}
 *       这两个真实部署产物也被跑一遍（{@code mvn test} 用的 H2 跑不出这个效果）。</li>
 * </ul>
 *
 * <p><b>镜像与端口策略</b>：镜像固定成 {@code deploy/docker-compose.yml} 里那几个**同一个 tag**，
 * 否则"测试绿了、真机红了"又会变成可能。容器端口一律用随机映射（{@code getMappedPort}），
 * 因此跑测试时开发者那套 3307/5433/9200 不需要停，两者不抢端口。
 *
 * <p><b>代价是诚实的</b>：这三个容器加起来约 2~3GB 内存与 30~60 秒启动时间，
 * 所以它<b>不是 {@code mvn test} 的默认行为</b>——仍然由环境变量
 * （{@code AI_VECTOR_LIVE} / {@code AI_SEARCH_LIVE}）决定是否运行。
 * 不带这些变量时 JUnit 根本不会创建 Spring 上下文，
 * 本类的静态方法也就不会被调用，一个容器都不会起。
 */
public final class LiveInfrastructure {

    /** 与 deploy/docker-compose.yml 保持同一个 tag：测试环境必须就是部署环境。 */
    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String POSTGRES_IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";

    /**
     * ES 用**本仓库自己构建**的那个镜像（含 analysis-ik 插件）。
     *
     * <p>不能用官方镜像：IK 不装，中文会被切成单字，而"检索看起来还是能用"——
     * {@code LiveChunkIndexTest} 第一条用例专门断言分词结果，正是为了挡住这种情况。
     * 镜像名带后缀版本号，因此 Testcontainers 不会去 Docker Hub 拉取（那里也没有它），
     * 拉取策略被显式关掉，缺镜像时报的是"镜像不存在 + 怎么构建"，而不是一句 pull 失败。
     */
    private static final String ES_IMAGE =
            System.getenv().getOrDefault("NEXUS_ES_IMAGE", "nexus-elasticsearch:9.4.5");

    /** 业务库参数与 compose 一致（库名/账号/口令），让迁移脚本拿到与真机相同的环境。 */
    private static final String MYSQL_DATABASE = "nexus";
    private static final String MYSQL_USERNAME = "nexus";
    private static final String MYSQL_PASSWORD = "nexus_pwd";

    private static final String VECTOR_DATABASE = "nexus_vector";
    private static final String VECTOR_USERNAME = "nexus";
    private static final String VECTOR_PASSWORD = "nexus_pwd";

    /** 容器在同一个 JVM 内共享：两个测试类各建一次 Spring 上下文，但只起一次容器。 */
    private static MySQLContainer mysql;
    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> elasticsearch;

    private LiveInfrastructure() {
    }

    // ------------------------------------------------------------------
    // 三个"按需启动"的注册入口
    // ------------------------------------------------------------------

    /** 业务库（MySQL + Flyway）：两个用例组都需要——{@code @SpringBootTest} 的上下文离不开它。 */
    public static synchronized void registerMySql(DynamicPropertyRegistry registry) {
        if (mysql == null) {
            mysql = new MySQLContainer(MYSQL_IMAGE)
                    .withDatabaseName(MYSQL_DATABASE)
                    .withUsername(MYSQL_USERNAME)
                    .withPassword(MYSQL_PASSWORD);
            mysql.start();
        }
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // local profile 的 URL 一大串参数（字符集/时区/批量重写）在这里没有意义：
        // Testcontainers 生成的 URL 已经带 allowPublicKeyRetrieval，且容器与宿主机同时区。
    }

    /** 向量库（PostgreSQL + pgvector）。 */
    public static synchronized void registerPgVector(DynamicPropertyRegistry registry) {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName(VECTOR_DATABASE)
                    .withUsername(VECTOR_USERNAME)
                    .withPassword(VECTOR_PASSWORD)
                    // 扩展由**部署脚本**创建（迁移里刻意不写 CREATE EXTENSION，见 V1 的注释：
                    // 扩展属于实例级配置，应用账号不该需要超级用户权限）。
                    // 这里直接复用部署用的那一份脚本，而不是在测试里再抄一遍 SQL——
                    // 抄一遍就会在将来"有人加了个扩展"时悄悄落后。
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(repoFile("deploy/postgres/init/01-extensions.sql")),
                            "/docker-entrypoint-initdb.d/01-extensions.sql");
            postgres.start();
        }
        registry.add("nexus.vector.datasource.url", postgres::getJdbcUrl);
        registry.add("nexus.vector.datasource.username", postgres::getUsername);
        registry.add("nexus.vector.datasource.password", postgres::getPassword);
        registry.add("nexus.vector.enabled", () -> "true");
    }

    /** 检索索引（Elasticsearch + IK）。 */
    public static synchronized void registerElasticsearch(DynamicPropertyRegistry registry) {
        if (elasticsearch == null) {
            elasticsearch = new GenericContainer<>(ES_IMAGE)
                    // 与 compose 同样的启动参数：单节点 + 关安全模块（测试容器只在 127.0.0.1 随机端口）
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.security.enrollment.enabled", "false")
                    // 堆压到 512m：与 compose 同一个理由（宿主 Docker VM 内存有限），
                    // 也避免测试把开发机的内存吃满
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withExposedPorts(9200)
                    // 本地构建的镜像不在任何仓库里，显式不拉取：
                    // 缺镜像时得到的是"docker 找不到该镜像"，比一句 pull access denied 好定位
                    .withImagePullPolicy(image -> false)
                    .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200))
                    .withStartupTimeout(Duration.ofMinutes(3));
            elasticsearch.start();
        }
        registry.add("nexus.search.uris",
                () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200));
        registry.add("nexus.search.enabled", () -> "true");
    }

    /**
     * 外部依赖的"关掉"开关：Redis 与 RabbitMQ 不在本期范围内。
     *
     * <p>不是省事，是避免把无关组件拖进来：这两个用例验的是 pgvector / ES 适配器，
     * 而 Redis（限流、令牌版本）与 RabbitMQ（异步导入）都不在这条链路上。
     * 关掉之后 SecurityStore 走进程内存实现、MQ 监听器不启动，
     * 测试不再需要它们，也不再被它们的启动状态影响。
     */
    public static void registerUnusedExternalDependencies(DynamicPropertyRegistry registry) {
        registry.add("nexus.security.redis-enabled", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    /**
     * 关掉向量库（供只验 ES 的用例使用）。
     *
     * <p>它不能跟 {@link #registerPgVector} 共存：{@code vectorFlywayInitializer} 是启动期的
     * 必连依赖，向量库一旦启用就必须有个真的 PostgreSQL，否则上下文直接起不来
     * （报的是 "Connection to localhost:5433 refused"，与本次要验的 ES 毫无关系，
     * 排查方向会被带偏）。
     *
     * <p>关掉之后注入的是 {@code DisabledVectorStore}——它不是"什么都不做"，
     * 而是任何写入/检索都明确抛 4003。所以万一这个用例碰了向量，
     * 得到的是一句如实的报错，而不是静默通过。
     */
    public static void registerVectorServerDisabled(DynamicPropertyRegistry registry) {
        registry.add("nexus.vector.enabled", () -> "false");
    }

    // ------------------------------------------------------------------

    /**
     * 从工作目录向上找仓库里的一个文件。
     *
     * <p>Maven 跑测试时工作目录是模块目录（{@code nexus-app}），IDEA 里可能是仓库根目录，
     * 写死相对路径必然有一边不通。向上找到就返回，两边都对；找不到时把查找起点写进报错里，
     * 免得变成一句"文件不存在"而不知道它到底在哪儿找。
     */
    private static Path repoFile(String relativePath) {
        Path start = Path.of("").toAbsolutePath();
        for (Path cursor = start; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "找不到仓库文件 %s（从 %s 向上逐级查找）。请确认在仓库内运行本测试。"
                        .formatted(relativePath, start));
    }
}
