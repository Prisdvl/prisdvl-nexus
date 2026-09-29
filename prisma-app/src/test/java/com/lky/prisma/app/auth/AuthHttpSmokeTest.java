package com.lky.prisma.app.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 冒烟测试：启动**真实 Tomcat**（随机端口）并用 JDK 原生 HttpClient 发请求。
 *
 * <p>为什么在 MockMvc 之外还要这一层：MockMvc 走的是模拟 Servlet 环境，
 * 它<b>不能</b>证明"应用真的能启动并对外提供服务"。这一步额外覆盖：
 * <ol>
 *   <li>Spring 上下文在真实 Web 容器中完整启动；</li>
 *   <li>Spring Security 过滤器链在真实容器里的装配顺序正确；</li>
 *   <li>请求/响应经过真实的 HTTP 编解码（中文提示信息的编码是否正确）；</li>
 *   <li>{@code X-Request-Id} 响应头在真实链路中可见。</li>
 * </ol>
 *
 * <p>刻意使用 JDK 自带的 {@link HttpClient} 而不是 RestTemplate/WebTestClient：
 * 零额外依赖、零版本适配风险（Boot 4 搬迁过多个测试工具类的包名）。
 *
 * <p>端口通过 {@code local.server.port} 属性注入，同样是为了避免依赖
 * {@code @LocalServerPort} 注解的包路径（Boot 4 有调整）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthHttpSmokeTest {

    private static final String PASSWORD = "Passw0rd!23";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("真实 HTTP：注册 → 登录 → 带 Token 访问；无 Token 时 401 且响应体仍是统一格式")
    void realHttpAuthFlowWorks() throws Exception {
        // 1) 注册
        HttpResponse<String> registerResponse = post("/api/auth/register", """
                {"username":"httpuser","password":"%s","nickname":"HTTP 用户"}
                """.formatted(PASSWORD));
        assertThat(registerResponse.statusCode()).isEqualTo(200);
        assertThat((int) JsonPath.read(registerResponse.body(), "$.code")).isZero();

        // 2) 登录
        HttpResponse<String> loginResponse = post("/api/auth/login", """
                {"username":"httpuser","password":"%s"}
                """.formatted(PASSWORD));
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        String accessToken = JsonPath.read(loginResponse.body(), "$.data.accessToken");
        assertThat(accessToken).isNotBlank();

        // 3) 带 Token 访问受保护接口
        HttpResponse<String> meResponse = get("/api/users/me", "Bearer " + accessToken);
        assertThat(meResponse.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(meResponse.body(), "$.data.username")).isEqualTo("httpuser");

        // 4) 不带 Token：401 + 统一返回体 + 可追踪的 requestId + 中文编码正确
        HttpResponse<String> unauthorized = get("/api/users/me", null);
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat((int) JsonPath.read(unauthorized.body(), "$.code")).isEqualTo(2001);
        assertThat((String) JsonPath.read(unauthorized.body(), "$.message")).isEqualTo("未认证或凭证缺失");
        assertThat(unauthorized.headers().firstValue("X-Request-Id")).isPresent();
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
