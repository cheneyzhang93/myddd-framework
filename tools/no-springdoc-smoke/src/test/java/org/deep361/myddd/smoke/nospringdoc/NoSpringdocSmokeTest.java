package org.deep361.myddd.smoke.nospringdoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.deep361.myddd.web.error.GlobalExceptionHandler;
import org.deep361.myddd.web.i18n.ErrorMessageRenderer;
import org.deep361.myddd.web.trace.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 证明 web-starter 的 openapi 那支靠 {@code @ConditionalOnClass(name = "…")} 在类缺席时<b>整体静默退场</b>：
 * 上下文照常起、其余四支照常装配、容器里一个 springdoc 类型的 bean 都没有。
 *
 * <p>这一支在单元层测不到（见同目录 {@code pom.xml} 的说明），所以由本夹具在打包期真启动一次。
 */
@SpringBootTest(classes = SmokeApplication.class)
class NoSpringdocSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void should_have_springdoc_genuinely_absent_from_the_classpath() {
        // 这条不是废话：本类的全部结论都建立在"类真的不在"之上，夹具的 exclusions 写错就等于什么也没测
        assertThatThrownBy(() -> Class.forName("org.springdoc.core.properties.SpringDocConfigProperties"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void should_start_the_context_and_keep_the_other_four_web_capabilities() {
        assertThat(context.getBean(TraceIdFilter.class)).isNotNull();
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class)).hasSize(1);
        assertThat(context.getBeansOfType(ErrorMessageRenderer.class)).hasSize(1);
        // Jackson 约定那支与 openapi 无关，springdoc 缺席时它必须仍在（框架的 customizer 已作用到 JsonMapper 上）
        assertThat(context.getBeansOfType(JsonMapper.class)).isNotEmpty();
    }

    @Test
    void should_back_off_the_entire_openapi_branch_without_a_single_springdoc_bean() {
        // 按类名前缀筛而不是按类型筛：连 openapi 的装配类都不该被解析，"退场"必须是整支退而非"装配了但 bean 建不出来"
        String[] springdocBeans = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && type.getName().startsWith("org.springdoc.");
                })
                .toArray(String[]::new);
        assertThat(springdocBeans).isEmpty();
    }
}
