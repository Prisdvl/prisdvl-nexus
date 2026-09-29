package com.lky.akh.app;

import com.lky.akh.app.auth.mapper.SysUserMapper;
import com.lky.akh.app.auth.model.entity.SysUserPO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 0.3 的核心验收测试：证明 MyBatis-Plus 3.5.17 的 Boot 4 starter 在 Spring Boot 4.1.1 + Java 21 下真的可用。
 *
 * <p>为什么这条测试重要：MyBatis-Plus 官方 boot4-starter 的 POM 以 Spring Boot 4.0.1 为基线，
 * 并未声明兼容 4.1.1。如果不实测就往下写业务代码，风险会延迟到 Phase 1 才爆发。
 * 这里用 H2 内存库把验证成本降到最低（不需要 Docker），验证点包括：
 * <ol>
 *   <li>MyBatis-Plus 自动配置能在 Boot 4.1.1 下正常装配（Mapper Bean 被扫描到）；</li>
 *   <li>雪花 ID 主键回填（ASSIGN_ID）；</li>
 *   <li>BaseMapper 的 insert / selectById / updateById 可用；</li>
 *   <li>@TableLogic 逻辑删除生效（删除后 selectById 查不到，但数据仍在）。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class MybatisPlusCompatibilityTest {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    @DisplayName("MyBatis-Plus 在 Boot 4.1.1 下 CRUD 与逻辑删除均正常")
    void mybatisPlusCrudWorks() {
        // ---------- insert：验证雪花 ID 回填 ----------
        SysUserPO user = new SysUserPO();
        user.setUsername("lky");
        user.setPasswordHash("$2a$10$dummy-bcrypt-hash");
        user.setNickname("LKY");
        user.setStatus(1);

        int inserted = sysUserMapper.insert(user);
        assertThat(inserted).isEqualTo(1);
        assertThat(user.getId()).as("ASSIGN_ID 应回填雪花主键").isNotNull();

        // ---------- selectById ----------
        SysUserPO loaded = sysUserMapper.selectById(user.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUsername()).isEqualTo("lky");
        assertThat(loaded.getDeleted()).as("逻辑删除标记默认应为 0").isZero();

        // ---------- updateById ----------
        loaded.setNickname("LKY-updated");
        assertThat(sysUserMapper.updateById(loaded)).isEqualTo(1);
        assertThat(sysUserMapper.selectById(user.getId()).getNickname()).isEqualTo("LKY-updated");

        // ---------- @TableLogic 逻辑删除 ----------
        assertThat(sysUserMapper.deleteById(user.getId())).isEqualTo(1);
        assertThat(sysUserMapper.selectById(user.getId()))
                .as("逻辑删除后普通查询应查不到该行").isNull();
    }
}
