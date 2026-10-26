package com.lky.nexus.app.plugin.model.dto;

import java.time.LocalDateTime;

/**
 * 一份 SKILL.md（{@code GET /api/skills} / {@code GET /api/skills/{code}}）。
 *
 * <p>列表接口不带正文（{@code contentMd} 为 null）：200 KB 一份的正文乘上几十份，
 * 只为画一个列表就把响应撑到几 MB，是没必要的。要看正文就按 code 单独取。
 *
 * @param sizeBytes 正文的 UTF-8 字节数，前端据此显示"多大"（比字符数更接近用户理解的大小）
 */
public record SkillResponse(String code,
                            String name,
                            String version,
                            String description,
                            boolean enabled,
                            String contentMd,
                            int sizeBytes,
                            LocalDateTime updatedAt) {
}
