package org.deep361.myddd.web.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.deep361.myddd.web.i18n.ErrorMessageRenderer;
import org.deep361.myddd.web.json.JsonConventions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 参数校验异常的归一：三种来源进同一个出口，字段路径按同一套键名规则写进 {@code data}。
 *
 * <p>夹具尽量手搭（{@code BeanPropertyBindingResult}／{@code MethodValidationResult.create}），因为本任务守的是
 * "接住之后怎么建键、取哪条文案"，与异常怎么被抛出无关；唯一的端到端用例走真实抛出路径，证明 {@code BindException}
 * 那条注册真的能被 Spring 命中。
 */
class GlobalExceptionHandlerValidationTest {

    private static final Locale ZH_CN = Locale.forLanguageTag("zh-CN");

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new ErrorMessageRenderer(null, true, ZH_CN));

    @BeforeEach
    void useChineseRequest() {
        LocaleContextHolder.setLocale(ZH_CN);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void should_key_bind_errors_by_field_and_object_name_in_sorted_order() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(order(), "orderForm");
        binding.rejectValue("orderId", "NotBlank", "订单号不能为空");
        binding.rejectValue("level", "NotBlank", "级别不能为空");
        binding.reject("OrderValid", "整单校验不通过");

        var entity = this.handler.handleBindException(new BindException(binding));

        assertThat(entity.getStatusCode().value()).isEqualTo(400);
        assertThat(entity.getBody().code()).isEqualTo("MYDDD_40001");
        assertThat(entity.getBody().message()).isEqualTo("参数校验失败");
        assertThat(entity.getBody().data()).containsEntry("orderId", "订单号不能为空")
                .containsEntry("level", "级别不能为空");
        // 全局错误的键是 objectName；三条的整体顺序必须是字典序（level, orderForm, orderId），不是放进去的先后
        assertThat(entity.getBody().data().keySet()).containsExactly("level", "orderForm", "orderId");
        assertThat(entity.getBody().data().get("orderForm")).isEqualTo("整单校验不通过");
    }

    @Test
    void should_keep_only_first_message_for_repeated_key() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(order(), "orderForm");
        binding.rejectValue("orderId", "NotBlank", "第一条：不能为空");
        binding.rejectValue("orderId", "Size", "第二条：长度超限");

        var entity = this.handler.handleBindException(new BindException(binding));

        assertThat(entity.getBody().data()).hasSize(1).containsEntry("orderId", "第一条：不能为空");
    }

    @Test
    void should_dispatch_body_and_form_validation_through_same_handler() throws Exception {
        Method method = ProbeEndpoint.class.getDeclaredMethod("validBody", ProbeEndpoint.ValidOrder.class);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(order(), "orderForm");
        binding.rejectValue("level", "NotBlank", "级别不能为空");

        // 编译期就把继承关系钉死：MethodArgumentNotValidException 能作为 BindException 传进同一个 handler
        var entity = this.handler.handleBindException(
                new MethodArgumentNotValidException(new MethodParameter(method, 0), binding));

        assertThat(entity.getStatusCode().value()).isEqualTo(400);
        assertThat(entity.getBody().data()).containsOnlyKeys("level");
    }
    @Test
    void should_key_parameter_errors_by_name_with_container_position() throws Exception {
        Method method = ProbeEndpoint.class.getDeclaredMethod("validBody", ProbeEndpoint.ValidOrder.class);
        MethodParameter named = new MethodParameter(method, 0);
        MethodParameter nameless = new MethodParameter(method, 0);
        // 模拟业务构建没开 -parameters（Spring 拿不到参数名、返回 null）：此时键必须是 arg+下标而不是字符串 "null"
        nameless.initParameterNameDiscovery(noParameterNames());

        HandlerMethodValidationException ex = new HandlerMethodValidationException(MethodValidationResult.create(
                new ProbeEndpoint(), method,
                List.of(parameterResult(named, "order", null, null),
                        parameterResult(named, List.of("a", ""), 1, null),
                        parameterResult(named, Map.of("k", ""), null, "cardNo"),
                        parameterResult(nameless, "x", null, null),
                        nestedFieldErrorResult(named))));

        var entity = this.handler.handleHandlerMethodValidation(ex);

        assertThat(entity.getBody().code()).isEqualTo("MYDDD_40001");
        assertThat(entity.getBody().data().keySet())
                .containsExactly("arg0", "nickname", "order", "order[1]", "order{cardNo}");
        // 走 ObjectError 分支时键取字段名，文案仍由渲染器按 codes 解析（与 BindException 那一路同一套规则）
        assertThat(entity.getBody().data().get("nickname")).isEqualTo("昵称不能为空");
    }

    @Test
    void should_use_fixed_cross_parameter_key() throws Exception {
        Method method = ProbeEndpoint.class.getDeclaredMethod("validBody", ProbeEndpoint.ValidOrder.class);

        HandlerMethodValidationException ex = new HandlerMethodValidationException(MethodValidationResult.create(
                new ProbeEndpoint(), method,
                List.of(parameterResult(new MethodParameter(method, 0), "order", null, null)),
                List.of(new DefaultMessageSourceResolvable(
                        new String[] {"Consistent.order", "Consistent"}, null, "两个参数必须一致"))));

        var entity = this.handler.handleHandlerMethodValidation(ex);

        assertThat(entity.getBody().data()).containsOnlyKeys("crossParameter", "order");
        assertThat(entity.getBody().data().get("crossParameter")).isEqualTo("两个参数必须一致");
    }

    @Test
    void should_key_violations_by_property_path_regardless_of_order() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<ProbeEndpoint.ValidOrder>> violations =
                    validator.validate(new ProbeEndpoint.ValidOrder(null, null, "abcdef"));
            assertThat(violations).hasSize(3);

            var entity = this.handler.handleConstraintViolation(new ConstraintViolationException(violations));

            assertThat(entity.getBody().code()).isEqualTo("MYDDD_40001");
            // 声明序是 orderId, level, tag；输出必须是字典序 level, orderId, tag（jakarta 不承诺 Set 顺序）
            assertThat(entity.getBody().data().keySet()).containsExactly("level", "orderId", "tag");
            assertThat(entity.getBody().data().values()).allSatisfy(text -> assertThat(text).isNotBlank());
        }
    }

    @Test
    void should_return_400_with_per_field_data_without_leaking_internals() throws Exception {
        MvcResult result = mockMvc().perform(post("/probe/valid-body")
                        .header("Accept-Language", "zh-CN")
                        .contentType("application/json")
                        .content("{\"orderId\":null,\"level\":\"\",\"tag\":\"abcdef\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":\"MYDDD_40001\"").contains("参数校验失败");
        assertThat(body).doesNotContain("MethodArgumentNotValidException").doesNotContain("org.springframework")
                .doesNotContain("ProbeEndpoint");

        Map<String, String> data = dataOf(json(result));
        assertThat(data).hasSize(3);
        assertThat(data.keySet()).containsExactly("level", "orderId", "tag");
    }

    /** 校验通过的实例：{@code rejectValue} 会读属性值装进 FieldError，所以目标对象必须有这三个属性。 */
    private static ProbeEndpoint.ValidOrder order() {
        return new ProbeEndpoint.ValidOrder("A1", "gold", "x");
    }

    /** 参数级校验结果夹具：按 Spring 的 7 参构造器给齐（实测没有更简的构造器），解包适配器给恒等函数。 */
    private static ParameterValidationResult parameterResult(MethodParameter parameter, Object argument,
                                                            Integer containerIndex, Object containerKey) {
        return new ParameterValidationResult(parameter, argument,
                List.of(new DefaultMessageSourceResolvable(
                        new String[] {"NotBlank.order"}, null, "不能为空")),
                null, containerIndex, containerKey, (resolvable, type) -> resolvable);
    }

    /**
     * 嵌套 {@code @Valid} 请求体的形状：Spring 放进 {@code ParameterValidationResult} 的是 {@link FieldError}
     * （它是 {@code ObjectError} 的子类），此时键必须走 {@code objectErrorKey} 而不是"参数名 + 容器位置"——
     * 参数名在这里是请求体对象的名字，不是出错字段的名字。
     */
    private static ParameterValidationResult nestedFieldErrorResult(MethodParameter parameter) {
        return new ParameterValidationResult(parameter, new ProbeEndpoint.ValidOrder("A1", "gold", "x"),
                List.of(new FieldError("validOrder", "nickname", null, false,
                        new String[] {"NotBlank.validOrder.nickname"}, null, "昵称不能为空")),
                null, null, null, (resolvable, type) -> resolvable);
    }

    /** 拿不到参数名的发现器：{@code ParameterNameDiscoverer} 有两个抽象方法（实测 javap），所以只能匿名类。 */
    private static ParameterNameDiscoverer noParameterNames() {
        return new ParameterNameDiscoverer() {
            @Override
            public String[] getParameterNames(Method method) {
                return null;
            }

            @Override
            public String[] getParameterNames(Constructor<?> constructor) {
                return null;
            }
        };
    }

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        // standalone 不走 BeanPostProcessor，必须手工初始化，否则它内部的 jakarta Validator 仍是 null
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new ProbeEndpoint())
                .setValidator(validator)
                .setControllerAdvice(this.handler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonConventions.frameworkMapper()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(MvcResult result) throws Exception {
        return JsonConventions.frameworkMapper().readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> dataOf(Map<String, Object> body) {
        return (Map<String, String>) body.get("data");
    }
}
