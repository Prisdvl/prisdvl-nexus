package com.lky.nexus.app.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 会话绑定的知识库 id（{@code chat_conversation.kb_ids}，JSON 数组字符串）。
 *
 * <p><b>为什么要单独一个类</b>：写进去、读出来的地方不在一处
 * （会话服务负责绑定，会话流式负责按绑定去检索），而"JSON 列在 MySQL 与 H2 下的行为差异"
 * 是个只有踩过才知道的坑，不能靠每处各写一遍 try-catch 来复现同一个正确行为。
 *
 * <p><b>那个坑</b>（与 {@code chat_message.metadata} 同源，见 ChatMessageServiceImpl）：
 * <ul>
 *   <li>MySQL：写入 Java 字符串时服务端把它<b>当作 JSON 文本解析</b>，读回来是数组；</li>
 *   <li>H2（测试 profile）：字符串被当成<b>字符串值</b>序列化，读回来是
 *       {@code "[1,2]"}——一个内容为 JSON 的字符串节点。</li>
 * </ul>
 * 两种形态都要接受，否则会出现"生产能用、测试读不出绑定"这种最误导人的差异。
 *
 * <p>解析失败<b>不做兜底猜测</b>：返回空列表并告警。调用方（会话流式）在拿到空列表时会
 * 明确报"该会话未绑定知识库"，用户看到的是可操作的信息，而不是"检索悄悄没生效"。
 */
public final class ConversationKbIds {

    private static final Logger log = LoggerFactory.getLogger(ConversationKbIds.class);

    private ConversationKbIds() {
    }

    /**
     * 归一化：去掉 null、去重、保持首次出现的顺序。
     *
     * <p>重复的 id 没有任何意义，但会让"我绑了 1 个库"这句话变成谎话
     * （计数、提示、日志都会按 2 说）——所以在入口处就收敛。
     */
    public static List<Long> normalize(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id != null) {
                unique.add(id);
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 序列化为写库的字符串。空列表写 {@code "[]"}，<b>不写 null</b>。
     *
     * <p>这不是风格问题，而是一次实测抓到的静默失败：MyBatis-Plus 的 {@code updateById}
     * 默认策略是 {@code NOT_NULL}——<b>null 字段不进 UPDATE 语句</b>。
     * 于是"解绑知识库"（把这一列置空）会被整条跳过：接口返回 200、
     * 返回体里的 {@code kbIds} 甚至是空的（它来自内存对象），
     * 但数据库里那一列纹丝不动，刷新后绑定又回来了。
     *
     * <p>写 {@code "[]"} 则是一个普通值，会正常参与 UPDATE，
     * 且"空数组 = 没绑定"在 MySQL JSON 与 H2 VARCHAR 下都合法，
     * 让这一列只有一种表示法（不必再区分"null"与"[]"两种空）。
     */
    public static String write(JsonMapper mapper, List<Long> kbIds) {
        return mapper.writeValueAsString(normalize(kbIds));
    }

    /** 反序列化；空值或格式非法都返回空列表（非法时告警，不抛异常打断读会话）。 */
    public static List<Long> parse(JsonMapper mapper, String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root.isString()) {
                // H2 的"字符串节点"形态：里层才是真正的数组
                root = mapper.readTree(root.asString());
            }
            if (!root.isArray()) {
                log.warn("kb_ids 不是数组，按未绑定处理：{}", abbreviate(json));
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            for (JsonNode node : root) {
                if (node.isNumber()) {
                    ids.add(node.asLong());
                } else if (node.isString() && !node.asString().isBlank()) {
                    ids.add(Long.parseLong(node.asString().trim()));
                }
            }
            return normalize(ids);
        } catch (Exception ex) {
            log.warn("kb_ids 解析失败，按未绑定处理：{}（{}）", abbreviate(json), ex.getMessage());
            return List.of();
        }
    }

    private static String abbreviate(String json) {
        return json.length() <= 200 ? json : json.substring(0, 200) + "…";
    }
}
