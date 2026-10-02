package org.deep361.myddd.web.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.deep361.myddd.web.autoconfigure.MydddWebProperties;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.util.StringUtils;

/**
 * 把 {@code myddd.web.openapi.*} 三个键写进 springdoc 生成文档的元信息。
 *
 * <p>只写 {@code info} 的 title 与 version，以及"配了才写"的 description，其它字段一概不碰：文档里非
 * {@code info} 的东西由别的定制器或 springdoc 负责，两个框架定制器的改动面交集为空，因此谁先被回放都一样。
 *
 * <p>入口选 {@link GlobalOpenApiCustomizer} 而不是声明一个 {@code OpenAPI} bean：后者等于把 springdoc 生成的
 * 基底换成我们自己 new 的对象，业务与框架两处都能声明，冲突只能等到运行时以"文档少半截"的形式暴露。
 */
public class OpenApiInfoCustomizer implements GlobalOpenApiCustomizer {

    private final MydddWebProperties.Openapi openapi;

    public OpenApiInfoCustomizer(MydddWebProperties properties) {
        this.openapi = properties.getOpenapi();
    }

    @Override
    public void customise(OpenAPI openApi) {
        Info info = openApi.getInfo();
        if (info == null) {
            info = new Info();
            openApi.setInfo(info);
        }
        info.setTitle(openapi.getTitle());
        info.setVersion(openapi.getVersion());
        // description 的默认值就是空串，而"只配标题"是最常见的一次调用：无条件 set 等于把业务先写的描述擦掉
        if (StringUtils.hasText(openapi.getDescription())) {
            info.setDescription(openapi.getDescription());
        }
    }
}
