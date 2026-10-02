package org.deep361.myddd.limiter.resilience4j.rule;

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
 * 规则文档的读取器：只管<b>形态</b>（这份字节是不是一张我们能认识的表），语义一律交给
 * {@link Resilience4jRuleValidator}。与 Task 45 的 {@code SentinelRuleDocumentReader} 同一条实现路线，
 * 只差数值字段的包装类型（{@code Integer}/{@code Float}/{@code Long}）。
 *
 * <p>三件事在这里判，每件都只给一个原因：根必须是对象、段必须是数组、字段必须在白名单里且类型逐字对。
 * 未知字段<b>拒收</b>而不是忽略——{@code timeoutDuration}、{@code limitRefreshPeriod} 这些都是 r4j 自己
 * 配置对象上的真字段名（{@code RS3} 的夹具就是抄 r4j 官方文档里的一条），忽略它们等于把"配错一个字母"
 * 变成"这条规则静默少一个条件"，而本模块的语义是"字段面由我们决定，换算由 publisher 决定"。
 *
 * <p>只走 {@link JsonNode} 树，不做 databind 的类型映射（不 {@code readValue(…, RateLimiterConfig.class)}
 * 之类）：把厂商的配置对象当反序列化目标会让字段面由厂商决定，而 {@code JsonNode} 树读法需要的能力只有
 * {@code tools.jackson.core:jackson-databind} 提供。一手 Jackson 3 事实（沿用 Sentinel 侧取证）：
 * {@code JsonMapper} 在 {@code tools.jackson.databind}、遍历用 {@code properties()}（不是 Jackson 2 的
 * {@code fields()}）、{@code readTree(Path)} 抛的是非受检的 {@code tools.jackson.core.JacksonException}。
 */
public final class Resilience4jRuleDocumentReader {

    private static final Set<String> SECTIONS = Set.of("flow", "degrade");

    private static final Set<String> FLOW_FIELDS = Set.of("resource", "count");

    private static final Set<String> DEGRADE_FIELDS =
            Set.of("resource", "threshold", "ratio", "maxRtMs", "minCalls", "windowMs", "openSeconds");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** {@code myddd.limiter.resilience4j.rule-location} 解析后的绝对路径；{@code memory} 来源为 {@code null}。 */
    private final Path location;

    public Resilience4jRuleDocumentReader(Path location) {
        this.location = location;
    }

    /**
     * 按启动期校验过的那条路径读整张表。{@code memory} 来源（路径为 {@code null}）⇒ 空文档，
     * 一切规则由 {@code Resilience4jRulePublisher.publish(…)} 在运行期下发。
     */
    public MydddResilience4jRuleDocument read() {
        return this.location == null ? new MydddResilience4jRuleDocument() : read(this.location);
    }

    /**
     * 从文件读。
     *
     * @param path 绝对或相对路径都由调用方负责存在性（{@code IQ5}/{@code IQ6} 已在启动期判过）
     * @throws FrameworkException 不可解析、形态不对、字段不认识或类型不对；消息一律带这份文件的路径
     */
    public MydddResilience4jRuleDocument read(Path path) {
        String where = "规则文件 " + path;
        return toDocument(parseQuietly(where, () -> this.mapper.readTree(path)), where);
    }

    /**
     * 从字符串读。测试与 {@code memory} 来源之外的第二入口：夹具不必为了测一份文档而先落一个
     * 临时文件，而 {@code RS1} 要断"同一个内容，两种来源给逐字相同的文档"。
     */
    public MydddResilience4jRuleDocument readJson(String json) {
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

    private MydddResilience4jRuleDocument toDocument(JsonNode root, String where) {
        if (!root.isObject()) {
            throw new FrameworkException(where + " 的根节点必须是 JSON 对象，实际是 "
                    + root.getNodeType().name() + "；期望形态：{\"flow\": […], \"degrade\": […]}，两段都可省略");
        }
        for (Map.Entry<String, JsonNode> top : root.properties()) {
            if (!SECTIONS.contains(top.getKey())) {
                throw new FrameworkException(unknownFieldMessage(where, top.getKey(), SECTIONS)
                        + "；能写的段只有 " + SECTIONS + "（段名写错会得到一张都不下发的空表，"
                        + "而空表等于全部放行）");
            }
        }
        MydddResilience4jRuleDocument document = new MydddResilience4jRuleDocument();
        readFlow(root.get("flow"), where, document);
        readDegrade(root.get("degrade"), where, document);
        return document;
    }

    private void readFlow(JsonNode section, String where, MydddResilience4jRuleDocument document) {
        forEachEntry(section, "flow", where, (index, entry) -> {
            String path = "flow[" + index + "]";
            String resource = text(entry, where, path, "resource");
            rejectUnknownFields(entry, where, path, resource, FLOW_FIELDS);
            MydddResilience4jRuleDocument.FlowEntry flow = new MydddResilience4jRuleDocument.FlowEntry();
            flow.setResource(resource);
            flow.setCount(integer(entry, where, path, "count"));
            document.getFlow().add(flow);
        });
    }

    private void readDegrade(JsonNode section, String where, MydddResilience4jRuleDocument document) {
        forEachEntry(section, "degrade", where, (index, entry) -> {
            String path = "degrade[" + index + "]";
            String resource = text(entry, where, path, "resource");
            rejectUnknownFields(entry, where, path, resource, DEGRADE_FIELDS);
            MydddResilience4jRuleDocument.DegradeEntry degrade = new MydddResilience4jRuleDocument.DegradeEntry();
            degrade.setResource(resource);
            degrade.setThreshold(text(entry, where, path, "threshold"));
            degrade.setRatio(ratio(entry, where, path, "ratio"));
            degrade.setMaxRtMs(longValue(entry, where, path, "maxRtMs"));
            degrade.setMinCalls(integer(entry, where, path, "minCalls"));
            degrade.setWindowMs(longValue(entry, where, path, "windowMs"));
            degrade.setOpenSeconds(longValue(entry, where, path, "openSeconds"));
            document.getDegrade().add(degrade);
        });
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

    private void rejectUnknownFields(JsonNode entry, String where, String path, String resource,
            Set<String> allowed) {
        for (Map.Entry<String, JsonNode> field : entry.properties()) {
            if (!allowed.contains(field.getKey())) {
                throw new FrameworkException(unknownFieldMessage(where, path, resource, field.getKey(), allowed));
            }
        }
    }

    /** 诊断四段（{@code RS3} 的判据）：段名 + 下标 + 资源名 + 字段名，外加这一条能写的字段全集。 */
    private String unknownFieldMessage(String where, String path, String resource, String field,
            Set<String> allowed) {
        return where + " 的 " + path + "（资源 " + resource + "）含不认识的字段 " + field
                + "；这一条能写的字段只有 " + allowed
                + "（从 r4j 官方文档抄来的键名在我们这里没有消费者——字段面由框架定，换算由 publisher 定）";
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

    /**
     * {@code Long} 一档：{@code maxRtMs} / {@code windowMs} / {@code openSeconds} 落到厂商配置上是
     * {@code Duration} 的毫秒/秒数，写成 {@code 1.5} 时按截断处理会让规则与运维写的数字对不上，
     * 而 publisher 的回读核验（名字集 + 配置值）会比不出来。
     */
    private Long longValue(JsonNode entry, String where, String path, String field) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber() || !node.isIntegralNumber()) {
            throw typeMismatch(where, path, field, "整数", node);
        }
        return node.longValue();
    }

    /** {@code ratio} 是唯一允许小数的字段（百分比阈值），其余整数位一律不接纳亚整数形态。 */
    private Float ratio(JsonNode entry, String where, String path, String field) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw typeMismatch(where, path, field, "数字", node);
        }
        return node.floatValue();
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
