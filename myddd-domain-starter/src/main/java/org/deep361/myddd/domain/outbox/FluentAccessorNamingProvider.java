package org.deep361.myddd.domain.outbox;

import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.AccessorNamingStrategy;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.AnnotatedField;
import tools.jackson.databind.introspect.AnnotatedMethod;
import tools.jackson.databind.introspect.DefaultAccessorNamingStrategy;

import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * 支持 fluent 访问器（{@code orderId()}）的 Jackson 访问器命名策略。
 *
 * <p><b>为什么需要</b>：框架的值对象与领域事件按业务语言命名方法（{@code amount()} / {@code settled()}），
 * 不写 {@code get}/{@code is} 前缀；Jackson 3 默认把这类对象序列化成 {@code {}}，Outbox 落库的 payload
 * 会变成空对象。本 Provider 在标准命名之外补一条兜底规则：<b>无参、非 void、非 static、非 {@code Object}
 * 方法名、且不是标准 Bean 形态的方法名即属性名</b>。
 *
 * <p><b>规则边界</b>（每条都有测试）：
 * <ul>
 *   <li>{@code getXxx()} / {@code isXxx()} 仍走 Jackson 标准命名：{@code getURL()} → {@code URL}、
 *       {@code isDone()} → {@code done}；</li>
 *   <li>{@code setXxx(...)} 仍走标准 mutator 命名（反序列化入口）；</li>
 *   <li>{@code toString()} / {@code hashCode()} 不会被误认成属性；</li>
 *   <li>标准命名的拒绝形态（{@code get2()}）与非布尔形态的 {@code isActive()} 不被接管；以 {@code is}
 *       开头但不构成驼峰的 {@code issue()} 按 fluent 访问器处理；</li>
 *   <li>{@code get} + 小写字母按 Jackson 2 legacy 形态去前缀命名（{@code getaway()} → {@code away}）
 *       ——Jackson 3 的标准命名已不再做 legacy 变换，只能由此兜底规则承担；</li>
 *   <li>接口 {@code default} 方法不是属性（{@code DomainEvent#eventType()} 不出现在 payload）；</li>
 *   <li>record 与普通类语义一致：组件照常，额外的无参 fluent 方法（计算属性）同样成为属性
 *       ——Jackson 默认只认 record 组件。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：无状态，可作为单例复用（自动装配层只创建一个实例）。
 */
public final class FluentAccessorNamingProvider extends DefaultAccessorNamingStrategy.Provider {

    private static final long serialVersionUID = 1L;

    /** {@code Object} 的无参有返回值方法名：不排除会被当成属性（{@code hashCode()} 尤其隐蔽）。 */
    private static final Set<String> JDK_OBJECT_NOISE_NAMES = Set.of("toString", "hashCode");

    /** 标准 getter 前缀：{@code getXxx()} 由标准命名接管；{@code get} + 小写字母按 legacy 形态命名。 */
    private static final String GETTER_PREFIX = "get";

    /** 标准 is-getter 前缀：{@code isXxx} 驼峰由标准命名接管。 */
    private static final String IS_GETTER_PREFIX = "is";

    @Override
    public AccessorNamingStrategy forPOJO(MapperConfig<?> config, AnnotatedClass valueClass) {
        return new FluentNaming(super.forPOJO(config, valueClass));
    }

    @Override
    public AccessorNamingStrategy forRecord(MapperConfig<?> config, AnnotatedClass valueClass) {
        return new FluentNaming(super.forRecord(config, valueClass));
    }

    /** 在标准命名方案外套一层 fluent 兜底：标准命名给得出名字就用标准的，否则再问 fluent 规则。 */
    private static final class FluentNaming extends AccessorNamingStrategy {

        private final AccessorNamingStrategy standard;

        FluentNaming(AccessorNamingStrategy standard) {
            this.standard = standard;
        }

        @Override
        public String findNameForIsGetter(AnnotatedMethod method, String name) {
            String standardName = standard.findNameForIsGetter(method, name);
            return standardName != null ? standardName : fluentName(method, name);
        }

        @Override
        public String findNameForRegularGetter(AnnotatedMethod method, String name) {
            String standardName = standard.findNameForRegularGetter(method, name);
            return standardName != null ? standardName : fluentName(method, name);
        }

        @Override
        public String findNameForMutator(AnnotatedMethod method, String name) {
            return standard.findNameForMutator(method, name);
        }

        @Override
        public String modifyFieldName(AnnotatedField field, String name) {
            return standard.modifyFieldName(field, name);
        }

        private static String fluentName(AnnotatedMethod method, String name) {
            if (method.getParameterCount() != 0) {
                return null;
            }
            if (method.getRawReturnType() == void.class) {
                return null;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                return null;
            }
            if (method.getMember().isDefault()) {
                return null;
            }
            if (JDK_OBJECT_NOISE_NAMES.contains(name)) {
                return null;
            }
            if (name.startsWith(GETTER_PREFIX)) {
                return isLegacyGetterName(name) ? name.substring(GETTER_PREFIX.length()) : null;
            }
            if (isStandardIsGetterName(name)) {
                return null;
            }
            return name;
        }

        /** Jackson 2 legacy 形态：{@code get} + 小写字母（{@code getaway()} → {@code away}）；{@code get2()} / {@code get_value()} 不属于此类。 */
        private static boolean isLegacyGetterName(String name) {
            return name.length() > GETTER_PREFIX.length()
                    && Character.isLowerCase(name.charAt(GETTER_PREFIX.length()));
        }

        /** 标准 is-getter 形态：{@code isXxx} 驼峰；{@code isActive()}（非布尔返回）与 {@code issue()} 均不属于此类。 */
        private static boolean isStandardIsGetterName(String name) {
            return name.length() > IS_GETTER_PREFIX.length() && name.startsWith(IS_GETTER_PREFIX)
                    && Character.isUpperCase(name.charAt(IS_GETTER_PREFIX.length()));
        }
    }
}
