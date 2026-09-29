package com.lky.nexus.app.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即可获得单表 CRUD；复杂查询写在对应的 XML 或注解 SQL 中。
 * 标注 {@code @Mapper} 后由 MyBatis(-Plus) 的自动扫描注册为 Bean，无需在启动类上写 @MapperScan。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUserPO> {
}
