package org.deep361.myddd.limiter.sentinel.rule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.deep361.myddd.kernel.common.error.FrameworkException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 规则文档的读取器：只管<b>形态</b>（这份字节是不是一张我们能认识的表），语义（这张表能不能
 * 真把流量拦住）一律交给 Step 5 的校验器。两张网独立存在是本任务的核心判据（Step 0 第 4 条），
 * 所以这里一条都不查"值域对不对"。
 *
 * <p>三件事在这里判，每件都只给一个原因：根必须是对象、段必须是数组、字段必须在白名单里且
 * 类型逐字对。未知字段<b>拒收</b>而不是忽略——{@code group}、{@code limitApp} 这些都是 Sentinel
 * 自己规则对象上的真字段名，忽略它们等于把"配错一个字母"变成"这条规则静默少一个条件"。
 *
 * <p>只走 {@link JsonNode} 树，不做 databind 的类型映射（不 {@code readValue(…, FlowRule.class)}
 * 之类）：把厂商的 {@code FlowRule} 当反序列化目标会让字段面由厂商决定（Step 0 第 1 条），
 * 而 {@code JsonNode} 树读法需要的能力只有 {@code tools.jackson.core:jackson-databind} 提供，
 * 与 {@code myddd-domain-starter/pom.xml:59-62} 的 Jackson 3 坐标同一条。
 */
public final class SentinelRuleDocumentReader {

    private static final Set<String> SECTIONS = Set.of("flow", "degrade");

    private static final Set<String> FLOW_FIELDS = Set.of("resource", "count");

    private static final Set<String> DEGRADE_FIELDS =
            Set.of("resource", "threshold", "ratio", "maxRtMs", "minCalls", "windowMs", "openSeconds");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /**
     * 从文件读。
     *
     * @param path 绝对或相对路径都由调用方负责存在性（{@code IQ5}/{@code IQ6} 已在启动期判过）
     * @throws FrameworkException 不可解析、形态不对、字段不认识或类型不对；消息一律带这份文件的路径
     */
    public MydddSentinelRuleDocument read(Path path) {
        String where = "规则文件 " + path;
        return toDocument(parseQuietly(where, () -> this.mapper.readTree(path)), where);
    }

    /**
     * 从字符串读。测试与 {@code memory} 来源之外的第二入口：夹具不必为了测一份文档而先落一个
     * 临时文件，而 {@code RS1} 要断"同一个内容，两种来源给逐字相同的文档"。
     */
    public MydddSentinelRuleDocument readJson(String json) {
        String where = "规则字符串";
        return toDocument(parseQuietly(where, () -> this.mapper.readTree(json)), where);
    }

    private JsonNode parseQuietly(String where, Supplier<JsonNode> parse) {
        try {
            return parse.get();
        } catch (JacksonException malformed) {
            throw new FrameworkException(where + " 不是可解析的 JSON：" + firstLine(malformed.getMessage())
                    + "——读不进来不能退化成“没有规则”：那一张空规则表的效果是所有资源全放行，"
                    + "而运维以为限流与熔断都在生效（与 Task 44 的 IQ 组同一条裁定）", malformed);
        }
    }

    private MydddSentinelRuleDocument toDocument(JsonNode root, String where) {
        if (!root.isObject()) {
            throw new FrameworkException(where + " 的根节点必须是 JSON 对象，实际是 "
                    + root.getNodeType().name() + "；期望形态：{\"flow\": […], \"degrade\": […]}，两段都可省略");
        }
        for (Map.Entry<String, JsonNode> top : root.properties()) {
            if (!SECTIONS.contains(top.getKey())) {
                throw new FrameworkException(unknownFieldMessage(where, top.getKey(), SECTIONS)
                        + "；能写的段只有 " + SECTIONS + "（段名写错会得到一条规则都不下发的空表，"
                        + "而空表等于全部放行）");
            }
        }
        return new MydddSentinelRuleDocument(readFlow(root.get("flow"), where), readDegrade(root.get("degrade"), where));
    }

    private List<MydddSentinelRuleDocument.FlowRuleSpec> readFlow(JsonNode section, String where) {
        List<MydddSentinelRuleDocument.FlowRuleSpec> specs = new ArrayList<>();
        forEachEntry(section, "flow", where, (index, entry) -> {
            String path = "flow[" + index + "]";
            rejectUnknownFields(entry, where, path, FLOW_FIELDS);
            specs.add(new MydddSentinelRuleDocument.FlowRuleSpec(
                    text(entry, where, path, "resource"),
                    number(entry, where, path, "count")));
        });
        return specs;
    }

    private List<MydddSentinelRuleDocument.DegradeRuleSpec> readDegrade(JsonNode section, String where) {
        List<MydddSentinelRuleDocument.DegradeRuleSpec> specs = new ArrayList<>();
        forEachEntry(section, "degrade", where, (index, entry) -> {
            String path = "degrade[" + index + "]";
            rejectUnknownFields(entry, where, path, DEGRADE_FIELDS);
            specs.add(new MydddSentinelRuleDocument.DegradeRuleSpec(
                    text(entry, where, path, "resource"),
                    text(entry, where, path, "threshold"),
                    number(entry, where, path, "ratio"),
                    number(entry, where, path, "maxRtMs"),
                    integer(entry, where, path, "minCalls"),
                    integer(entry, where, path, "windowMs"),
                    integer(entry, where, path, "openSeconds")));
        });
        return specs;
    }

    /** 段可以整个省略（读成空列表），但写了就必须是对象数组：写成对象是手改 JSON 最常见的错位。 */
    private void forEachEntry(JsonNode section, String name, String where, BiConsumer<Integer, JsonNode> onEntry) {
        if (section == null || section.isNull()) {
            return;
        }
        if (!section.isArray()) {
            throw new FrameworkException(where + " 的 " + name + " 段必须是数组，实际是 "
                    + section.getNodeType().name() + "；期望形态：\"" + name + "\": [ { … } ]");
        }
        int index = 0;
        for (JsonNode entry : section) {
            if (!entry.isObject()) {
                throw new FrameworkException(where + " 的 " + name + "[" + index + "] 必须是对象，实际是 "
                        + entry.getNodeType().name() + "；一条规则就是一个对象，不是字符串也不是数字");
            }
            onEntry.accept(index, entry);
            index++;
        }
    }

    private void rejectUnknownFields(JsonNode entry, String where, String path, Set<String> allowed) {
        for (Map.Entry<String, JsonNode> field : entry.properties()) {
            if (!allowed.contains(field.getKey())) {
                throw new FrameworkException(unknownFieldMessage(where, path, field.getKey(), allowed));
            }
        }
    }

    private String unknownFieldMessage(String where, String path, String field, Set<String> allowed) {
        return where + " 的 " + path + " 含不认识的字段 " + field + "；这一条能写的字段只有 " + allowed;
    }

    private String unknownFieldMessage(String where, String field, Set<String> allowed) {
        return where + " 含不认识的顶层键 " + field + "；" + allowed + " 之外的键没有任何消费者";
    }

    private String text(JsonNode entry, String where, String path, String field) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw typeMismatch(where, path, field, "字符串", node);
        }
        return node.stringValue();
    }

    private Double number(JsonNode entry, String where, String path, String field) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw typeMismatch(where, path, field, "数字", node);
        }
        return node.doubleValue();
    }

    /**
     * 整数位单独一档：{@code minCalls} / {@code windowMs} / {@code openSeconds} 落到厂商对象上是
     * {@code int}，写成 {@code 1.5} 时如果按 {@code doubleValue()} 截断，规则数量与运维写的数字
     * 就对不上，而回读核验（Step 5）会比不出来。
     */
    private Integer integer(JsonNode entry, String where, String path, String field) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber() || !node.isIntegralNumber()) {
            throw typeMismatch(where, path, field, "整数", node);
        }
        return node.intValue();
    }

    private FrameworkException typeMismatch(String where, String path, String field, String expected, JsonNode node) {
        return new FrameworkException(where + " 的 " + path + "." + field + " 必须是" + expected + "，实际是 "
                + node.getNodeType().name() + "（值：" + node + "）。本读取器不做类型强转：\"10\" 与 10 的来历不同"
                + "（带引号通常是手写 JSON 或从别处粘贴时的错位），把它们当成同一个值就是把配错推到运行期");
    }

    /** Jackson 的失败消息带行列与整段引用，直接拼进消息会淹没路径；取首行，根因仍作为 cause 保留。 */
    private static String firstLine(String message) {
        if (message == null) {
            return "无消息";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message.trim() : message.substring(0, newline).trim();
    }
}
