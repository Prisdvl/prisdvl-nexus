package com.lky.prisma.app.infra.jackson;

import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * 把 {@code Long} 序列化为 JSON 字符串（Boot 4 的 Jackson 3 写法）。
 *
 * <p><b>为什么必须这么做</b>：本项目主键是雪花 ID（例如 2104750903666438145），
 * 而 JavaScript 的 {@code Number} 只能安全表示到 2^53-1（9007199254740991）。
 * 若把 Long 当数字返回，前端解析后会变成 2104750903666438144 —— 表现为
 * "前端拿到的 id 查不到数据"，且不报错，是最难排查的一类 bug。
 *
 * <p><b>实现方式</b>：Boot 4 默认使用 Jackson 3（{@code tools.jackson}），
 * 其自动配置提供的是 {@code JsonMapper} bean；官方推荐用 {@link JacksonComponent}
 * 注解把序列化器注册为 Spring Bean（等价于 Jackson 的 module 注册，但不需要手写 module）。
 * 泛型参数 {@code ValueSerializer<Long>} 告诉框架"我负责 Long 类型"。
 *
 * <p><b>配套约定（务必遵守）</b>：既然 {@code Long} 会变成字符串，
 * 那么 DTO 里的<b>计数字段请用 {@code int}/{@code long} 基本类型</b>（保持数字形态），
 * <b>只有 ID 类字段才用包装类型 {@code Long}</b>。该约定由测试
 * {@code LongToStringSerializationTest} 断言，避免以后有人不小心把计数写成 Long。
 */
@JacksonComponent
public class LongToStringJacksonComponent {

    public static class LongToStringSerializer extends ValueSerializer<Long> {

        @Override
        public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt) {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.toString());
        }
    }
}
