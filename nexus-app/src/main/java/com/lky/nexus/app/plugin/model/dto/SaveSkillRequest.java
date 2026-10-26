package com.lky.nexus.app.plugin.model.dto;

/**
 * 写入一份 SKILL.md（{@code PUT /api/skills/{code}}）。
 *
 * <p>正文上限 200 KB，由 {@code SkillService} 校验：这不是为了刁难用户，
 * 而是 SKILL.md 要被塞进模型上下文——一份 5 MB 的正文只会换来一次 4006。
 * 拒绝在写入侧发生，用户当场就能看到原因。
 *
 * @param contentMd SKILL.md 原文（含 frontmatter），原样保存
 */
public record SaveSkillRequest(String name,
                               String version,
                               String description,
                               String contentMd,
                               Boolean enabled) {
}
