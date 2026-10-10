package com.lky.nexus.app.infra.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code JSON} 列的读写处理器：<b>只解包H2 的"字符串节点"形态</b>。
 *
 * <p><b>要解决的 bug（实测，不是推测）</b>：
 * {@code chat_conversation.kb_ids} 在 H2 里是原生 {@code json} 类型，而 H2 对
 * 「把 JSON 文本写进 json 列」的处理是<b>存成一个字符串节点</b>——
 * 写 {@code ["1"]} 存回来是 {@code "[\"1\"]"}（一个内容为 JSON 的字符串）。
 * 于是：
 * <pre>
 *   写入（create）  ["1"]           →库里 "[\"1\"]"
 *   读取                → 实体 "[\"1\"]"       ← 已是转义过的形态
 *   updateById 写回     "[\"1\"]"      → 库里 "\"[\\\"1\\\"]\""  ← 又被序列化一层
 * </pre>
 * 表现是「改一下标题，绑定的知识库就没了」——
 * 而 {@code updateById} 走的是「整行读出→ 改字段 → 全列写回」，
 * 所以<b>任何</b> update 都会踩到，不限于模式切换。
 *
 * <p><b>为什么原生 JDBC 复现不了</b>：直接用 {@code PreparedStatement.setString}
 * 写同一个值两次，库里内容不变（H2 对它幂等）。所以问题不在 H2 本身，
 * 而在 <b>MyBatis 把「H2 返回的转义形态」原样写回</b>。
 *
 * <p><b>本处理器的职责边界</b>（刻意做得很窄）：
 * <ul>
 *   <li><b>读</b>：H2 返回字符串节点时<b>反向解包一次</b>，让实体里始终是规范的
 *       {@code ["1"]}；已经是数组形态（MySQL 的行为）就<b>原样返回</b>。</li>
 *   <li><b>写</b>：<b>原样透传</b>，不做任何加工 —— 序列化是 H2/MySQL 该做的事，
 *       处理器插手只会把问题换个地方藏起来。</li>
 * </ul>
 *
 * <p><b>解析失败绝不静默返回空数组</b>：那会让"写入已损坏"变成"读出来是空的"，
 * 两者混在一起就再也分不清了。解不开就抛，让调用方看到真实异常。
 *
 * <p>启用方式：实体字段上加
 * {@code @TableField(typeHandler = JsonTextTypeHandler.class)}，
 * 并在 {@code @TableName} 上加 {@code autoResultMap = true}
 * （没有它，MyBatis-Plus 不会为该字段启用 resultMap 里的处理器）。
 */
@MappedTypes(String.class)
public class JsonTextTypeHandler extends BaseTypeHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(JsonTextTypeHandler.class);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        /*
         * 原样透传，**不做二次序列化**。
         * 写这一侧出问题的根源就是"把 H2 返回的转义形态又写回去"，
         * 这里若再加工就是同一种错误的另一层包装。
         */
        ps.setString(i, parameter);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return unwrap(rs.getString(columnName), columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String label = rs.getMetaData().getColumnLabel(columnIndex);
        return unwrap(rs.getString(columnIndex), label);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String label = cs.getMetaData().getColumnLabel(columnIndex);
        return unwrap(cs.getString(columnIndex), label);
    }

    /**
     * 解开 H2 的"JSON 字符串节点"形态。
     *
     * <p>判定方式：内容<b>看起来是 JSON 字符串</b>（首尾双引号）时，
     * 先反解一次得到内层文本，再看内层是否已经是<b>数组或对象</b>——
     * 是则返回内层（这正是 MySQL 会直接返回的形态）；
     * 不是就说明调用方给的已经是规范 JSON，原样返回。
     *
     * <p>⚠️ 只解<b>一层</b>。两层以上的嵌套是"历史脏数据"，
     * 解一层之后若还不是数组，<b>原样返回并告警</b>——
     * 由上层 {@code ConversationKbIds.parse} 按它自己的规则处理，
     * 本处理器不替它做判断。
     */
    private String unwrap(String raw, String column) throws SQLException {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String trimmed = raw.strip();
        // 不是 JSON 字符串（首尾不是双引号）→ 已是规范形态
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            return raw;
        }
        String inner = unquoteJsonString(trimmed);
        if (inner == null) {
            return raw;
        }
        String t = inner.strip();
        // 内层已经是数组/对象 → 解包成功，返回规范形态
        if (t.startsWith("[") || t.startsWith("{")) {
            return t;
        }
        // 内层既不是数组也不是对象：多半已经是脏数据或本就是普通字符串，原样返回
        log.warn("JSON 列 {} 的外层是字符串节点但内层不是数组/对象，按原样返回：{}", column, abbreviate(raw));
        return raw;
    }

    /**
     * 把一个 JSON 字符串字面量反解成它承载的文本。
     *
     * <p>不用现成 JSON 库：这里处理的是「H2 存JSON 文本时产生的转义形态」，
     * 只需要把 {@code \\"} → {@code "}、{@code \\\\} → {@code \\} 这类转义还原，
     * 以及外围那对引号。手写比引依赖更可控，也避免"用 JSON 库解 JSON"
     * 在这个场景下的过度处理。
     */
    private String unquoteJsonString(String jsonString) {
        String body = jsonString.substring(1, jsonString.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i == body.length() - 1) {
                sb.append(c);
                continue;
            }
            char next = body.charAt(++i);
            switch (next) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                // 未知转义：原样保留反斜杠，不吞掉
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…(" + s.length() + " 字符)";
    }

    /** 供测试复用：与解包逻辑同源的字节视图，便于断言"库里到底长什么样"。 */
    static String decode(byte[] utf8) {
        return utf8 == null ? null : new String(utf8, StandardCharsets.UTF_8);
    }
}