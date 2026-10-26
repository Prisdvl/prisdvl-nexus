package com.lky.nexus.app.plugin.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户级 SKILL.md 正文（{@code user_skill}）。
 *
 * <p>每用户每 code 至多一行：同名技能再导入一次就是覆盖。正文是用户的资产，
 * 换设备、换浏览器都必须还在——这正是它从 localStorage 搬到这里的原因。
 *
 * <p><b>刻意没有 {@code @TableLogic}</b>：删除是<b>物理删除</b>。
 * {@code user_skill} 上有 {@code UNIQUE (user_id, code)}，软删会把"已删的行"留在唯一键里，
 * 于是"删掉一个技能，再用同名重新导入"会撞唯一键、变成一次 500——而这是用户最自然的动作。
 * 技能正文没有审计价值（它本来就是用户自己写的），行没了就没了，重新导入不该被阻碍。
 * 表里的 {@code deleted} 列保留（迁移历史），恒为 0，不再参与任何查询。
 */
@Data
@TableName("user_skill")
public class UserSkillPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String code;
    private String name;
    private String version;
    private String description;
    /** SKILL.md 正文原样保存（上限 200 KB 由 {@code SkillService} 校验）。 */
    private String contentMd;
    /**
     * 正文的 UTF-8 字节数（V35）。
     *
     * <p>写的时候就算好：列表要显示"多大"，但列表不读正文——为了一个数字把 200 KB 的正文
     * 整行读进内存，会让"打开技能页"随技能数量线性变慢。{@code 0} 表示老数据还没算过。
     */
    private Integer sizeBytes;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
