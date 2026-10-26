package com.lky.nexus.app.plugin;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SKILL.md 正文落库（{@code user_skill}）。
 *
 * <p>钉住三件事：
 * <ul>
 *   <li><b>正文真的存住了</b>：导入后拿得回来，换设备也不丢（这正是它离开 localStorage 的理由）；</li>
 *   <li><b>200 KB 闸门在写入侧</b>：超限当场 400，而不是等提问时变成一次 4006；</li>
 *   <li><b>每用户隔离</b>：别人的技能与不存在的一样，都是 404。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillApiTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String SKILL_MD = """
            ---
            name: demo-skill
            version: 1.0.0
            description: Demo skill
            ---
            # demo-skill

            1. 先读上下文
            2. 再写结论
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("导入后正文拿得回来；列表只给元信息不给正文")
    void saveThenRead() throws Exception {
        String token = registerAndLogin("skill-roundtrip");

        mockMvc.perform(put("/api/skills/skill-demo")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("demo-skill", "1.0.0", "Demo skill", SKILL_MD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("skill-demo"))
                .andExpect(jsonPath("$.data.name").value("demo-skill"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(get("/api/skills/skill-demo")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentMd").value(SKILL_MD))
                .andExpect(jsonPath("$.data.sizeBytes").value(SKILL_MD.getBytes(StandardCharsets.UTF_8).length));

        MvcResult list = mockMvc.perform(get("/api/skills")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("skill-demo"))
                .andReturn();
        // 列表不带正文：200 KB 一份的正文乘上几十份会把列表撑到几 MB
        assertThat((Object) JsonPath.read(list.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data[0].contentMd")).isNull();
        // 但"多大"要给：这个数字来自写入时算好的 size_bytes，不靠把正文读出来
        assertThat((Integer) JsonPath.read(list.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data[0].sizeBytes")).isEqualTo(SKILL_MD.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("删除后再查是 9302，列表里也没有了；重复删同样 9302")
    void deleteRemovesSkill() throws Exception {
        String token = registerAndLogin("skill-delete");

        mockMvc.perform(put("/api/skills/skill-gone")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("gone", "1.0.0", "", SKILL_MD)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/skills/skill-keep")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("keep", "1.0.0", "", SKILL_MD)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/skills/skill-gone")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        MvcResult gone = mockMvc.perform(get("/api/skills/skill-gone")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat((Integer) JsonPath.read(gone.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.code")).isEqualTo(9302);

        // 只删掉这一条：另一条还在
        mockMvc.perform(get("/api/skills")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("skill-keep"));

        // 重复删与"从没存在过"一样是 9302：不给出"曾经存在过"这条信息
        mockMvc.perform(delete("/api/skills/skill-gone")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("删掉再用同一个 code 导入：不撞唯一键（这是软删最容易埋的坑）")
    void deleteThenReimportSameCode() throws Exception {
        String token = registerAndLogin("skill-reimport");

        mockMvc.perform(put("/api/skills/skill-again")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("first", "1.0.0", "", SKILL_MD)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/skills/skill-again")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 软删会把已删的行留在 UNIQUE(user_id, code) 里，这里必须还能写进新的一份
        mockMvc.perform(put("/api/skills/skill-again")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("second", "2.0.0", "", SKILL_MD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("second"))
                .andExpect(jsonPath("$.data.version").value("2.0.0"));

        mockMvc.perform(get("/api/skills")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("skill-again"))
                .andExpect(jsonPath("$.data[0].name").value("second"));
    }

    @Test
    @DisplayName("同名再导入是覆盖，不是多一份")
    void saveIsUpsert() throws Exception {
        String token = registerAndLogin("skill-upsert");
        String head = "skill-upsert-";

        mockMvc.perform(put("/api/skills/" + head + "demo")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("v1", "0.1.0", "first", SKILL_MD)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/skills/" + head + "demo")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("v2", "0.2.0", "second", SKILL_MD + "\n改了一行\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("v2"))
                .andExpect(jsonPath("$.data.version").value("0.2.0"));

        mockMvc.perform(get("/api/skills")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("v2"));
    }

    @Test
    @DisplayName("正文超过 200 KB → 400（1001），错误里说清超了多少")
    void oversizeBodyIsRejected() throws Exception {
        String token = registerAndLogin("skill-too-big");
        String huge = "x".repeat(SkillService.LIMIT_BYTES + 1);

        MvcResult result = mockMvc.perform(put("/api/skills/skill-huge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("huge", "1.0.0", "", huge)))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((Integer) JsonPath.read(body, "$.code")).isEqualTo(1001);
        assertThat((String) JsonPath.read(body, "$.message")).contains("200 KB");

        // 边界内（正好 200 KB）必须还能存：闸门不能把合法正文也挡了
        String limit = "y".repeat(SkillService.LIMIT_BYTES);
        mockMvc.perform(put("/api/skills/skill-limit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("limit", "1.0.0", "", limit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sizeBytes").value(SkillService.LIMIT_BYTES));
    }

    @Test
    @DisplayName("别人的技能与不存在的技能一样：404（9302）")
    void skillsArePerUser() throws Exception {
        String alice = registerAndLogin("skill-alice");
        String bob = registerAndLogin("skill-bob");

        mockMvc.perform(put("/api/skills/skill-private")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("private", "1.0.0", "alice only", SKILL_MD)))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/skills/skill-private")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat((Integer) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.code")).isEqualTo(9302);

        mockMvc.perform(get("/api/skills")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("空名字 / 空正文 → 400（1001）")
    void blankFieldsAreRejected() throws Exception {
        String token = registerAndLogin("skill-blank");

        mockMvc.perform(put("/api/skills/skill-blank")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("  ", "1.0.0", "", SKILL_MD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(put("/api/skills/skill-blank")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("blank", "1.0.0", "", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    // ---------- helpers ----------

    private static String payload(String name, String version, String description, String content) {
        try {
            return new tools.jackson.databind.json.JsonMapper()
                    .writeValueAsString(java.util.Map.of(
                            "name", name,
                            "version", version,
                            "description", description,
                            "contentMd", content));
        } catch (RuntimeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String registerAndLogin(String prefix) throws Exception {
        String username = prefix + "-" + SEQ.incrementAndGet() + "-" + (System.nanoTime() % 100_000);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.accessToken");
    }
}
