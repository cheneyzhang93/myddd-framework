package org.deep361.myddd.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.deep361.myddd.kernel.common.response.Response;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把统一响应信封在文档里补齐到与线上字节一致。
 *
 * <p>内核 {@link Response} 是 {@code record Response<T>(code, message, data, traceId)}，那四个组件 springdoc
 * 推得出来；推不出来的是 {@code Response.successful()}——它既不是 {@code getX()} 也不是 {@code isX()}，
 * 文档侧那套 Jackson 2 内省不认识它，而 wire 侧我们的 mapper 装了 {@code FluentAccessorNamingProvider}（Task 9），
 * 会把它写成第 5 个字段。同一份契约、两套内省规则，分叉方向固定：<b>文档比线上少一个字段</b>。
 *
 * <p>为什么修在文档侧而不是给内核加 {@code @Schema}：那会撞 {@code myddd-kernel-common} 的"零三方运行时依赖"
 * 红线（§0.3-3），而 §1.2 也已锁定错误响应不新增包装类型。
 *
 * <p>作用面只有 {@code components.schemas}：springdoc 把方法返回类型提成组件、在操作里只留 {@code $ref}，
 * 而 {@code $ref} 与组件指向同一个 {@link Schema} 实例，补组件即覆盖全部端点；遍历 {@code paths} 只会引入
 * {@code allOf}／{@code oneOf} 的递归与环。若将来出现内联信封，{@link #isEnvelope} 的签名判定可原样复用，
 * 只是入口要多一处遍历。
 */
public class ResponseEnvelopeCustomizer implements GlobalOpenApiCustomizer {

    /** 派生字段名，与内核 {@code Response.successful()} 逐字一致。 */
    static final String SUCCESSFUL_PROPERTY = "successful";

    /** 描述里刻意带上 {@code code}：用例据此确认"这条说明来自框架"，同时也提醒前端别拿派生字段做机器判别。 */
    private static final String SUCCESSFUL_DESCRIPTION = "业务是否成功（code 等于 OK）。派生字段，请读 code 做机器判别。";

    /**
     * 框架追加的必填项，顺序即写出顺序（同一份输入必须给出同一份字节）。依据是内核契约：
     * {@code code} 非 null（构造器 {@code requireNonNull}）、{@code message} 为 null 时归一化为空串。
     */
    private static final List<String> FRAMEWORK_REQUIRED = List.of("code", "message");

    /** 信封字段名集合，反射自内核 record 组件——内核将来重命名时这里自动跟随（设计要点 4）。 */
    private static final Set<String> ENVELOPE_FIELDS = envelopeFields();
    private static Set<String> envelopeFields() {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : Response.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return Set.copyOf(names);
    }

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            // 空文档没有组件可补。这里绝不能抛：文档构建期抛出异常等于整个 /v3/api-docs 变 500。
            return;
        }
        for (Schema<?> schema : components.getSchemas().values()) {
            if (isEnvelope(schema)) {
                patch(schema);
            }
        }
    }

    /** 按属性签名判定，不按组件名：springdoc 的泛型组件命名不在任何契约里（设计要点 4）。 */
    private static boolean isEnvelope(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        return properties != null && properties.keySet().containsAll(ENVELOPE_FIELDS);
    }

    private static void patch(Schema<?> schema) {
        schema.getProperties().putIfAbsent(SUCCESSFUL_PROPERTY, successfulSchema());

        // 并集而不是替换：springdoc 可能已按业务注解推出别的必填项，删它是越权
        List<String> required = schema.getRequired() == null
                ? new ArrayList<>() : new ArrayList<>(schema.getRequired());
        for (String mandatory : FRAMEWORK_REQUIRED) {
            if (!required.contains(mandatory)) {
                required.add(mandatory);
            }
        }
        schema.setRequired(required);
    }

    private static BooleanSchema successfulSchema() {
        BooleanSchema schema = new BooleanSchema();
        // type 与 types 是两个互不派生的字段，两个出口各读一个：3.0 出口忽略 types（SchemaMixin#getTypes 带
        // @JsonIgnore），3.1 出口忽略 type（Schema31Mixin#getType 带 @JsonIgnore，getTypes 被 @JsonProperty("type")
        // 改名后由 TypeSerializer 写出），而 springdoc 3.1.1 的 springdoc.api-docs.version 默认就是 openapi-3-1。
        // 实测（swagger-models-jakarta 2.2.55）：带类型的子类构造器会把两个字段同时填满，裸 Schema#setType 只填一个——
        // 所以这一行不是补构造器的缺，是不把正确性押在子类构造器上：换成裸 Schema/MapSchema 时它是唯一的防线，
        // 缺了就在 3.1 文档里静默丢类型。判据本身由 ResponseEnvelopeCustomizerTest 的裸 Schema 用例钉住。
        schema.setType("boolean");
        schema.setTypes(Set.of("boolean"));
        schema.setReadOnly(Boolean.TRUE);
        schema.setDescription(SUCCESSFUL_DESCRIPTION);
        return schema;
    }
}
