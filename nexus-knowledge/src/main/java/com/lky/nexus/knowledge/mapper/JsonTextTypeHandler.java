package com.lky.nexus.knowledge.mapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code JSON} 列的读写处理器，与应用层的同名类<b>行为完全一致</b>：
 * 只在<b>读</b>侧解开 H2 的"字符串节点"形态，写入侧原样透传。
 *
 * <p><b>为什么要有一个副本</b>（而不是复用 {@code nexus-app} 那份）：
 * 模块依赖方向是 {@code nexus-app → nexus-knowledge}，
 * 处理器放在 {@code nexus-app} 时 {@code nexus-knowledge} 无法反向引用它。
 * 抽到 {@code nexus-common} 需要给它加 MyBatis 依赖 ——
 * 而 {@code nexus-common} 本身与持久化无关，加持久化依赖会污染它的定位。
 * 两份各自独立、行为一致，是这个约束下最省事也最不容易出错的做法。
 *
 * <p><b>不修会怎样（实测）</b>：H2 的 {@code json} 列把 JSON 文本存成字符串节点
 * （写 {@code {"a":1}} 读回来是 {@code "{\"a\":1}"}），
 * 而 {@code updateById} 走「整行读出 → 改字段 → 全列写回」，
 * 未改动的 {@code frontmatter} / {@code headingTree} 也会被把已转义的形态再写回一次：
 * <pre>
 *   初始 len=61 → 第1次 85 → 第2次 133 → 第3次 229
 * </pre>
 * 每update 一次套一层，<b>指数增长</b>；解析侧只做单层解包，
 * 套两层以上就解不出来 → frontmatter / 标题树静默失效。
 *
 * <p>生产 MySQL 上不受影响（实测 {@code JSON_TYPE} 始终是 {@code OBJECT}/{@code ARRAY}），
 * 但 H2 上的测试因此无法暴露真实问题—— 测试环境与生产行为不一致本身就是隐患。
 *
 * @see com.lky.nexus.knowledge.model.entity.DocumentPO
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
        return unwrap(rs.getString(columnIndex), rs.getMetaData().getColumnLabel(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return unwrap(cs.getString(columnIndex), cs.getMetaData().getColumnLabel(columnIndex));
    }

    /**
     * 解开 H2 的"JSON 字符串节点"形态；已经是数组/对象的<b>原样返回</b>。
     *
     * <p>判定：首尾是双引号时反解一次，若内层是 {@code [} 或 {@code {} 开头则解包成功；
     * 否则原样返回（那已经是规范 JSON，或本就是普通字符串）。
     *
     * <p>⚠️ 只解<b>一层</b>。两层以上是历史脏数据，原样返回并告警 ——
     * 由上层按自己的规则处理，本处理器不替它做判断，也<b>不把失败静默当成空</b>。
     */
    private String unwrap(String raw, String column) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String trimmed = raw.strip();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            return raw;
        }
        String inner = unquoteJsonString(trimmed);
        if (inner == null) {
            return raw;
        }
        String t = inner.strip();
        if (t.startsWith("[") || t.startsWith("{")) {
            return t;
        }
        log.warn("JSON 列 {} 的外层是字符串节点但内层不是数组/对象，按原样返回：{}", column, abbreviate(raw));
        return raw;
    }

    /** 把一个 JSON 字符串字面量反解成它承载的文本（手写转义还原，不引JSON 库）。 */
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
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…(" + s.length() + " 字符)";
    }
}