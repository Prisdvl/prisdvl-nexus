package com.lky.nexus.app.plugin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.plugin.mapper.UserSkillMapper;
import com.lky.nexus.app.plugin.model.dto.SaveSkillRequest;
import com.lky.nexus.app.plugin.model.dto.SkillResponse;
import com.lky.nexus.app.plugin.model.entity.UserSkillPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户级 SKILL.md 正文（{@code user_skill}）。
 *
 * <p><b>为什么正文要离开 localStorage</b>：它此前是 localStorage 里的字符串。换设备、换浏览器、
 * 清缓存就没了——而 SKILL.md 是用户自己写的东西，属于资产，不属于界面偏好。
 *
 * <p><b>为什么限制 200 KB</b>：SKILL.md 最终要被塞进模型上下文。不限制的话，
 * 用户第一次导入一个 5 MB 的正文会"成功"，然后在提问时收到一个 4006 ——
 * 错误发生在离原因很远的地方。把闸门放在写入侧，用户当场就知道为什么不行。
 *
 * <p><b>同名即覆盖</b>：每用户每 code 至多一行。再导入一次是更新，不是又一份；
 * 否则技能列表会堆出一串同名条目，而用户分不清该删哪个。
 *
 * <p><b>列表不读正文</b>（V35）：大小在写入时算好存进 {@code size_bytes}，
 * 列表只查元信息列。之前为了显示"多大"把 200 KB 的正文整行读出来，
 * 技能一多，打开页面就成了 O(技能数 × 正文大小)。
 */
@Service
public class SkillService {

    /** 与 PluginService 同一个 code 形状。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    /** 正文上限：200 KB（按 UTF-8 字节算，这才是"文件多大"的真实含义）。 */
    public static final int LIMIT_BYTES = 200 * 1024;

    private final UserSkillMapper skillMapper;

    public SkillService(UserSkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    /** 列表：只回元信息 + 大小，**不读正文**（见 {@link SkillResponse}）。 */
    public List<SkillResponse> list(Long userId) {
        return skillMapper.selectList(Wrappers.<UserSkillPO>lambdaQuery()
                        // 显式列清单：不选 content_md，列表才不会随技能体积线性变慢
                        .select(UserSkillPO::getId,
                                UserSkillPO::getCode,
                                UserSkillPO::getName,
                                UserSkillPO::getVersion,
                                UserSkillPO::getDescription,
                                UserSkillPO::getEnabled,
                                UserSkillPO::getSizeBytes,
                                UserSkillPO::getCreatedAt,
                                UserSkillPO::getUpdatedAt)
                        .eq(UserSkillPO::getUserId, userId)
                        .orderByDesc(UserSkillPO::getUpdatedAt)
                        .orderByDesc(UserSkillPO::getId))
                .stream()
                .map(row -> toResponse(row, false))
                .toList();
    }

    public SkillResponse get(Long userId, String code) {
        String normalized = requireCode(code);
        UserSkillPO row = find(userId, normalized);
        if (row == null) {
            throw new BusinessException(ErrorCode.SKILL_NOT_FOUND, "没有导入过这个技能：%s".formatted(normalized));
        }
        repairSizeIfMissing(row);
        return toResponse(row, true);
    }

    /**
     * 导入 / 覆盖一份 SKILL.md。
     *
     * <p>{@code name} 必填：它是列表里唯一可辨识的东西。正文也必填——
     * 一个没有正文的技能只会是一个点开就报错的条目。
     */
    @Transactional
    public SkillResponse save(Long userId, String code, SaveSkillRequest request) {
        String normalized = requireCode(code);
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "技能名不能为空：它来自 SKILL.md 的 name 或一级标题");
        }
        String content = request.contentMd();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "SKILL.md 正文不能为空");
        }
        int size = sizeOf(content);
        if (size > LIMIT_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "SKILL.md 正文不能超过 200 KB（当前 %d KB）。正文会被塞进模型上下文，超限的正文只会换来一次调用失败"
                            .formatted((size + 1023) / 1024));
        }
        LocalDateTime now = LocalDateTime.now();
        UserSkillPO row = find(userId, normalized);
        boolean created = row == null;
        if (created) {
            row = new UserSkillPO();
            row.setUserId(userId);
            row.setCode(normalized);
            row.setCreatedAt(now);
            row.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        } else if (request.enabled() != null) {
            row.setEnabled(request.enabled() ? 1 : 0);
        }
        row.setName(name);
        row.setVersion(clip(defaultIfBlank(request.version(), "0.1.0"), 32));
        row.setDescription(clip(request.description(), 512));
        row.setContentMd(content);
        row.setSizeBytes(size);
        row.setUpdatedAt(now);
        if (created) {
            skillMapper.insert(row);
        } else {
            skillMapper.updateById(row);
        }
        return toResponse(row, true);
    }

    /**
     * 删除（物理删除）：重复删/不存在一律 9302（与"不是你的"不做区分）。
     *
     * <p>不用软删：{@code user_skill} 上有 {@code UNIQUE (user_id, code)}，
     * 软删的行仍占着唯一键，"删掉再用同名导入"会变成一次 500。
     */
    @Transactional
    public void delete(Long userId, String code) {
        String normalized = requireCode(code);
        UserSkillPO row = find(userId, normalized);
        if (row == null) {
            throw new BusinessException(ErrorCode.SKILL_NOT_FOUND, "没有导入过这个技能：%s".formatted(normalized));
        }
        // 走硬删（见 UserSkillMapper.hardDeleteById 的注释）：软删会占住 UNIQUE(user_id, code)
        skillMapper.hardDeleteById(row.getId());
    }

    private UserSkillPO find(Long userId, String code) {
        return skillMapper.selectOne(Wrappers.<UserSkillPO>lambdaQuery()
                .eq(UserSkillPO::getUserId, userId)
                .eq(UserSkillPO::getCode, code)
                .last("LIMIT 1"));
    }

    /**
     * 老数据（V35 之前写入的）没有 {@code size_bytes}：读正文时顺手补上。
     *
     * <p>只在按 code 取正文这条路径上做——列表手上没有正文，不能为了补一个数字去读整篇。
     */
    private void repairSizeIfMissing(UserSkillPO row) {
        Integer stored = row.getSizeBytes();
        if (stored != null && stored > 0) {
            return;
        }
        int computed = sizeOf(row.getContentMd());
        if (computed <= 0) {
            return;
        }
        row.setSizeBytes(computed);
        skillMapper.updateById(row);
    }

    private String requireCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "技能 code 只能是小写字母/数字/点/横线/下划线，且以字母或数字开头：%s".formatted(code));
        }
        return normalized;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 列宽是硬约束：截断比让用户看到 500 更接近"这条数据能存"的真相。 */
    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static int sizeOf(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 正文大小：优先用写入时算好的列；老数据（0）在拿到正文时现算。
     * 列表的行没有正文，此时就是 0——宁可显示 0，也不为了补数字去读整篇。
     */
    private int resolvedSize(UserSkillPO row) {
        Integer stored = row.getSizeBytes();
        return stored != null && stored > 0 ? stored : sizeOf(row.getContentMd());
    }

    private SkillResponse toResponse(UserSkillPO row, boolean withContent) {
        return new SkillResponse(row.getCode(),
                row.getName(),
                row.getVersion(),
                row.getDescription(),
                row.getEnabled() == null || row.getEnabled() != 0,
                withContent ? row.getContentMd() : null,
                resolvedSize(row),
                row.getUpdatedAt());
    }
}
