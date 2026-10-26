package com.lky.nexus.app.plugin;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.plugin.model.dto.SaveSkillRequest;
import com.lky.nexus.app.plugin.model.dto.SkillResponse;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户级 SKILL.md（{@code user_skill}）。
 *
 * <p>没有 {@code POST}：技能以 code 唯一，第一次 {@code PUT} 就是导入，之后都是覆盖。
 * 这与 {@code /api/plugins/{code}} 的取舍是同一个：客户端不需要先知道"要不要新建"。
 *
 * <p>{@code GET /api/skills} 不回正文（列表要轻），正文按 code 单独取。
 *
 * <p>{@code DELETE} 是<b>物理删除</b>：行没了，正文也就没了。这一点与 {@code user_plugin} 的
 * "只改标记"不同，原因是 {@code user_skill} 上有 {@code UNIQUE (user_id, code)}——
 * 软删会把已删的行留在唯一键里，"删掉再用同名导入"就会撞键变成 500。
 * 技能正文是用户自己的内容、没有审计价值，直接删掉更诚实。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public Result<List<SkillResponse>> list() {
        return Result.ok(skillService.list(CurrentUser.requireId()));
    }

    @GetMapping("/{code}")
    public Result<SkillResponse> get(@PathVariable String code) {
        return Result.ok(skillService.get(CurrentUser.requireId(), code));
    }

    @PutMapping("/{code}")
    public Result<SkillResponse> save(@PathVariable String code,
                                      @Valid @RequestBody SaveSkillRequest request) {
        return Result.ok(skillService.save(CurrentUser.requireId(), code, request));
    }

    @DeleteMapping("/{code}")
    public Result<Void> delete(@PathVariable String code) {
        skillService.delete(CurrentUser.requireId(), code);
        return Result.ok();
    }
}
