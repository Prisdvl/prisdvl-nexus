package com.lky.nexus.app.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.plugin.model.entity.UserSkillPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserSkillMapper extends BaseMapper<UserSkillPO> {

    /**
     * 物理删除一行（按主键）。
     *
     * <p><b>为什么不能用 {@code deleteById}</b>：测试 profile 里有一条全局配置
     * {@code mybatis-plus.global-config.db-config.logic-delete-field=deleted}，
     * 它会把 {@code deleteById} 改写成 {@code UPDATE ... SET deleted=1}——即使实体上没有
     * {@code @TableLogic}。于是 {@code UNIQUE (user_id, code)} 会被已删的行占住，
     * "删掉再用同名导入"就撞键变成 500，而且这个行为在测试与生产之间还不一致。
     * 裸 SQL 不经过任何改写，两边都是真的删。
     */
    @Delete("DELETE FROM user_skill WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);
}
