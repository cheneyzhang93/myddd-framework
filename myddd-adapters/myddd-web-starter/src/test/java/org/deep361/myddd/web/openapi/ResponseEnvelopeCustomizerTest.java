package org.deep361.myddd.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import org.deep361.myddd.kernel.common.response.Response;
import org.deep361.myddd.web.json.JsonConventions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应信封在文档里的形状：springdoc 推不出的派生字段由框架补齐，补出来的必须与真 mapper 写出的字节同集合。
 *
 * <p>夹具刻意把组件名写成 {@code Component0}…{@code Component3}：判定如果依赖名字，这批用例当场就红（设计要点 4）。
 */
class ResponseEnvelopeCustomizerTest {

    private final ResponseEnvelopeCustomizer customizer = new ResponseEnvelopeCustomizer();

    private static Schema<?> objectSchema(String... propertyNames) {
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setTypes(Set.of("object"));
        for (String name : propertyNames) {
            schema.addProperties(name, new StringSchema());
        }
        return schema;
    }

    private static OpenAPI openApiWith(Schema<?>... schemas) {
        Components components = new Components();
        for (int index = 0; index < schemas.length; index++) {
            components.addSchemas("Component" + index, schemas[index]);
        }
        OpenAPI openApi = new OpenAPI();
        openApi.setComponents(components);
        return openApi;
    }

    /** 与内核信封同形的组件，{@code data} 用传入的实例，便于断言"补齐不会把它换掉"。 */
    private static Schema<?> envelopeWith(Schema<?> dataSchema) {
        Schema<?> envelope = objectSchema("code", "message", "traceId");
        envelope.addProperties("data", dataSchema);
        return envelope;
    }

    @Test
    void should_add_successful_with_type_in_both_30_and_31_shapes() throws Exception {
        OpenAPI openApi = openApiWith(envelopeWith(new StringSchema()));
        Schema<?> envelope = openApi.getComponents().getSchemas().get("Component0");

        this.customizer.customise(openApi);

        Schema<?> successful = envelope.getProperties().get("successful");
        assertThat(successful).isNotNull();
        // 两个字段都要有值。实测（swagger-models-jakarta 2.2.55 + 直接跑 main 取证）：带类型的子类
        // （BooleanSchema/StringSchema…）的构造器把 type 与 types 同时填满，而裸 Schema#setType 只写 type——
        // 所以"只填一个"的事故形状发生在裸 Schema 上（见 should_leave_bare_schema_untyped_in_3_1_until_types_is_filled）。
        // 这里显式补 setTypes 不是"构造器没填"，而是"不依赖子类构造器填没填"：换实现（改成裸 Schema 或
        // MapSchema）时这一行就是唯一的防线，而它一旦缺失，3.1 出口就静默没有类型。
        assertThat(successful.getType()).isEqualTo("boolean");
        assertThat(successful.getTypes()).containsExactly("boolean");
        assertThat(successful.getReadOnly()).isEqualTo(Boolean.TRUE);
        assertThat(successful.getDescription()).containsIgnoringCase("code");

        // 两个出口各写一遍字节：3.0 读 type 字段、3.1 读 types 字段，两边都必须写出 boolean
        String doc31 = Json31.mapper().writeValueAsString(openApi);
        assertThat(Json31.mapper().readTree(doc31).path("components").path("schemas").path("Component0")
                .path("properties").path("successful").path("type").asText()).isEqualTo("boolean");
        String doc30 = Json.mapper().writeValueAsString(openApi);
        assertThat(Json.mapper().readTree(doc30).path("components").path("schemas").path("Component0")
                .path("properties").path("successful").path("type").asText()).isEqualTo("boolean");
    }

    /**
     * 设计要点 3 的判据本身：同一个 {@code type} 值在 3.0 与 3.1 出口来自两个互不派生的字段。
     * 用裸 {@link Schema} 演一遍——只填 {@code type} 时 3.1 文档里该字段根本没有类型（前端 codegen 得到
     * {@code Object}），补上 {@code types} 才两边都有；而 3.0 出口反过来只看 {@code type}。
     */
    @Test
    void should_leave_bare_schema_untyped_in_3_1_until_types_is_filled() throws Exception {
        Schema<Object> onlyType = new Schema<>();
        onlyType.setType("boolean");
        Schema<Object> bothFields = new Schema<>();
        bothFields.setType("boolean");
        bothFields.setTypes(Set.of("boolean"));

        assertThat(Json31.mapper().readTree(Json31.mapper().writeValueAsString(onlyType)).has("type")).isFalse();
        assertThat(Json31.mapper().readTree(Json31.mapper().writeValueAsString(bothFields))
                .path("type").asText()).isEqualTo("boolean");
        assertThat(Json.mapper().readTree(Json.mapper().writeValueAsString(onlyType))
                .path("type").asText()).isEqualTo("boolean");
    }

    @Test
    void should_union_required_without_duplicates_or_removal() {
        Schema<?> envelope = envelopeWith(new StringSchema());
        // 实测（swagger-models-jakarta 2.2.55 的 Schema#setRequired 字节码）：写入时会剔除 properties 里没有的
        // 名字并去重，所以夹具只能填真实存在的属性名——写 "id" 会被三方当场吃掉，测不到"保留别人推出来的必填项"。
        envelope.setRequired(new ArrayList<>(List.of("data", "message")));
        OpenAPI openApi = openApiWith(envelope);

        this.customizer.customise(openApi);

        // 原有的两项原位保留（框架不越权删别人推出来的必填项）、已有的 message 不重复追加、code 按固定顺序补在尾部。
        // 实测（swagger-models-jakarta 2.2.55 的 Schema#setRequired 字节码 Collections.sort）：模型会把必填项
        // 按字典序归一，所以"追加顺序"不是对外可观测的契约——本条钉的是并集、去重、不删除这三件事。
        assertThat(envelope.getRequired()).containsExactly("code", "data", "message");
    }

    @Test
    void should_keep_existing_data_schema_instance_untouched() {
        Schema<?> businessData = objectSchema("id", "status");
        Schema<?> envelope = envelopeWith(businessData);
        OpenAPI openApi = openApiWith(envelope);

        this.customizer.customise(openApi);

        assertThat(envelope.getProperties().get("data")).isSameAs(businessData);
        assertThat(envelope.getProperties().get("data").getProperties()).hasSize(2);
    }

    @Test
    void should_leave_near_miss_and_business_dtos_unpatched() {
        Schema<?> nearMiss = objectSchema("code", "message", "data");
        Schema<?> businessDto = objectSchema("id", "status");
        OpenAPI openApi = openApiWith(nearMiss, businessDto);

        this.customizer.customise(openApi);

        assertThat(nearMiss.getProperties().keySet()).containsExactly("code", "message", "data");
        assertThat(nearMiss.getRequired()).isNull();
        assertThat(businessDto.getProperties().keySet()).containsExactly("id", "status");
        assertThat(openApi.getComponents().getSchemas()).hasSize(2);
    }

    @Test
    void should_return_silently_for_empty_or_componentless_documents() {
        OpenAPI bare = new OpenAPI();
        this.customizer.customise(bare);
        assertThat(bare.getComponents()).isNull();

        OpenAPI emptyComponents = new OpenAPI();
        emptyComponents.setComponents(new Components());
        this.customizer.customise(emptyComponents);
        assertThat(emptyComponents.getComponents().getSchemas()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_match_documented_property_names_with_mapper_field_names() throws Exception {
        Schema<?> envelope = envelopeWith(new StringSchema());
        OpenAPI openApi = openApiWith(envelope);

        this.customizer.customise(openApi);

        // 真 mapper 写出的真实响应字节 —— 这才是"线上形状"，不是任何人脑子里的形状
        JsonMapper wireMapper = JsonConventions.frameworkMapper();
        Map<String, Object> wireFields = wireMapper.readValue(
                wireMapper.writeValueAsString(Response.ok(Map.of("id", "A1"))), Map.class);
        assertThat(wireFields).hasSize(5);
        assertThat(envelope.getProperties().keySet()).containsExactlyInAnyOrderElementsOf(wireFields.keySet());
        assertThat(envelope.getRequired()).containsExactly("code", "message");

        // 再用 springdoc 默认 spec 版本（openapi-3-1）的序列化器写一遍文档字节，证明补出来的字段真的带类型
        String doc = Json31.mapper().writeValueAsString(openApi);
        assertThat(Json31.mapper().readTree(doc).path("components").path("schemas").path("Component0")
                .path("properties").path("successful").path("type").asText()).isEqualTo("boolean");
    }

    @Test
    void should_ignore_schema_without_properties() {
        // 文档里出现"连 properties 都没有"的组件是常态（$ref、数组、裸 object），判定时必须先容 null
        Schema<?> bare = new Schema<>();
        OpenAPI openApi = openApiWith(bare, envelopeWith(new StringSchema()));

        this.customizer.customise(openApi);

        assertThat(bare.getProperties()).isNull();
        assertThat(openApi.getComponents().getSchemas().get("Component1").getProperties())
                .containsKey("successful");
    }

    @Test
    void should_keep_business_defined_successful_property_untouched() {
        // 故意给一个类型完全不同的定义：证明框架只看"有没有"，不看"对不对"，更不替换
        Schema<?> business = new StringSchema();
        Schema<?> envelope = envelopeWith(new StringSchema());
        envelope.addProperties(ResponseEnvelopeCustomizer.SUCCESSFUL_PROPERTY, business);
        OpenAPI openApi = openApiWith(envelope);

        this.customizer.customise(openApi);

        assertThat(envelope.getProperties().get(ResponseEnvelopeCustomizer.SUCCESSFUL_PROPERTY))
                .isSameAs(business);
    }
}
