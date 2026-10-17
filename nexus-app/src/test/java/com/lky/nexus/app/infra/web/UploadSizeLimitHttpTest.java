package com.lky.nexus.app.infra.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传超限的**真实链路**回归测试（Phase 5.2 / P1-5）。
 *
 * <p>为什么不能像 {@code ErrorContractTest} 那样只做 MockMvc 探针：
 * 体积上限<b>不在业务代码里判</b>，而是 Servlet 容器在解析 multipart 时判的。
 * MockMvc 用的是模拟请求，压根不经过容器解析，所以"超限请求到底会变成什么响应"
 * 在 MockMvc 里永远测不出来——那正是这次要修的缺陷所在：
 * 修之前超限会落到兜底 {@code Exception}，用户拿到 500「系统内部错误」。
 * 探针测试只能证明"接到这个异常会返回 413"，证明不了"容器真的会抛这个异常"。
 *
 * <p><b>为什么上限调到 8KB/16KB 而载荷只有 64KB</b>：容器在拒绝请求后会尝试
 * "吞掉"剩余请求体好把响应写回去，Tomcat 的 {@code maxSwallowSize} 默认 2MB。
 * 如果按线上真实体积（200MB+）去测，连接会在响应写出前被中断，
 * 客户端拿到的是连接重置而不是我们的 413 JSON——
 * 那样测的就不是"我们的异常处理器"，而是"Tomcat 怎么处理半截请求"了。
 * 只用 64KB，既能真实触发超限，又能让响应完整回来。
 *
 * <p>断言里要具体数字（{@code 8KB}/{@code 16KB}）：这两个值是本测试<b>自己配的</b>，
 * 出现在文案里就证明处理器读的是配置而不是写死的「200MB」——
 * 写死的数字在改配置后就是一句谎话，而这类谎话没人会去测。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.servlet.multipart.max-file-size=8KB",
                "spring.servlet.multipart.max-request-size=16KB"
        })
@ActiveProfiles("test")
@DisplayName("上传超限（真实 Tomcat）：413 + 3006，文案带上限的实际配置值")
class UploadSizeLimitHttpTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String BOUNDARY = "----NexusUploadLimitBoundary";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("真的往 /vault 传一个超限 ZIP：拿到 413 + 3006，而不是 500「系统内部错误」")
    void oversizedUploadIsRejectedWith413() throws Exception {
        String token = registerAndLogin("upload-limit-user");
        long knowledgeBaseId = createKnowledgeBase(token, "上传超限测试库");

        // 64KB > 16KB（单次请求上限），也 > 8KB（单文件上限）——两层上限都会被撞到
        byte[] oversizedZip = new byte[64 * 1024];
        oversizedZip[0] = 0x50;
        oversizedZip[1] = 0x4B;
        oversizedZip[2] = 0x03;
        oversizedZip[3] = 0x04;
        Arrays.fill(oversizedZip, 4, oversizedZip.length, (byte) 0x20);

        HttpRequest request = HttpRequest.newBuilder(
                        uri("/api/knowledge-bases/" + knowledgeBaseId + "/vault"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipartBody("file", "Oversized.zip", "application/zip", oversizedZip)))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode())
                .as("超限必须是 413（客户端错误），不是 500——500 会把用户的错记成服务端 BUG，"
                        + "响应体=%s", response.body())
                .isEqualTo(413);
        assertThat((int) JsonPath.read(response.body(), "$.code")).isEqualTo(3006);
        String message = JsonPath.read(response.body(), "$.message");
        assertThat(message)
                .as("文案要说出上限是多少，且必须是本次配置的值")
                .contains("8KB")
                .contains("16KB");
    }

    // ------------------------------------------------------------------

    /** 手搓 multipart：JDK HttpClient 没有现成的 multipart 构造器，这样也不必引入额外依赖。 */
    private byte[] multipartBody(String fieldName, String fileName, String contentType, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String registerAndLogin(String username) throws Exception {
        // 注册可能因为用户名已存在而返回 409（同一进程内多个测试类共用上下文时），登录才是真正的门
        postJson("/api/auth/register", """
                {"username":"%s","password":"%s","email":"%s@example.com"}
                """.formatted(username, PASSWORD, username));

        HttpResponse<String> login = postJson("/api/auth/login", """
                {"username":"%s","password":"%s"}
                """.formatted(username, PASSWORD));
        assertThat(login.statusCode()).as("登录失败，响应体=%s", login.body()).isEqualTo(200);
        return JsonPath.read(login.body(), "$.data.accessToken");
    }

    private long createKnowledgeBase(String token, String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/knowledge-bases"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"%s"}
                        """.formatted(name), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("建库失败，响应体=%s", response.body()).isEqualTo(200);
        return Long.parseLong(JsonPath.read(response.body(), "$.data.id").toString());
    }

    private HttpResponse<String> postJson(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
