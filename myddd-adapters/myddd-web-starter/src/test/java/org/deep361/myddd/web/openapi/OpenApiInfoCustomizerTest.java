package org.deep361.myddd.web.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.deep361.myddd.web.autoconfigure.MydddWebProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档元信息装配：{@code myddd.web.openapi.*} 三个键 → OpenAPI 的 {@code info} 三字段。
 *
 * <p>只测"写进了哪三个字段、没配的那个字段是否保住了业务的值"，不测 springdoc 本身怎么生成文档
 * （那是三方的地盘，§0.3-6）。
 */
class OpenApiInfoCustomizerTest {

    private static MydddWebProperties properties(String title, String description, String version) {
        MydddWebProperties properties = new MydddWebProperties();
        properties.getOpenapi().setTitle(title);
        properties.getOpenapi().setDescription(description);
        properties.getOpenapi().setVersion(version);
        return properties;
    }

    @Test
    void should_build_info_from_properties_when_document_has_none() {
        OpenAPI openApi = new OpenAPI();

        new OpenApiInfoCustomizer(properties("订单中心", "订单域对外 API", "2.3.1")).customise(openApi);

        assertThat(openApi.getInfo()).isNotNull();
        assertThat(openApi.getInfo().getTitle()).isEqualTo("订单中心");
        assertThat(openApi.getInfo().getDescription()).isEqualTo("订单域对外 API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("2.3.1");
    }

    @Test
    void should_override_title_and_version_but_keep_existing_when_description_blank() {
        OpenAPI openApi = new OpenAPI();
        Info existing = new Info();
        existing.setTitle("业务先设的标题");
        existing.setDescription("业务先设的描述");
        existing.setVersion("0.0.1");
        openApi.setInfo(existing);

        // description 的默认值就是空串（MydddWebProperties.Openapi），所以"只配标题"是最常见的一次调用；
        // 无条件覆盖等于把业务先写的描述擦掉，而标题不覆盖就等于配置键失效——两者必须区别对待。
        new OpenApiInfoCustomizer(properties("框架标题", "", "9.9.9")).customise(openApi);

        assertThat(openApi.getInfo()).isSameAs(existing);
        assertThat(openApi.getInfo().getDescription()).isEqualTo("业务先设的描述");
        assertThat(openApi.getInfo().getTitle()).isEqualTo("框架标题");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("9.9.9");
    }
}
