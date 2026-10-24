package com.lky.nexus.app.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.observability.model.entity.ObservabilityCallPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 调用明细读写。
 *
 * <p>聚合查询没有写成 SQL：按小时分桶、按 request_id 聚合这两个需求，
 * MySQL 与 H2（测试库）的日期函数不通用（{@code DATE_FORMAT} / {@code TIMESTAMPADD} /
 * {@code DATEDIFF} 各自成立、互不兼容），一旦写进 {@code @Select}，
 * 就会出现"生产能跑、测试红"或反过来——而这个项目的测试库
 * （{@code MODE=MySQL} 的 H2）跑的正是同一套语句。宁可把聚合放在 Service 里，
 * 用纯 Java 做分组，代价是一次窗口扫描，收益是同一份代码在两个库上语义一致。
 */
@Mapper
public interface ObservabilityCallMapper extends BaseMapper<ObservabilityCallPO> {
}
