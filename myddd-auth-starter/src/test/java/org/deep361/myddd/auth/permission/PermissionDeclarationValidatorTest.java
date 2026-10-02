package org.deep361.myddd.auth.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.deep361.myddd.auth.config.MydddAuthProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.RequiresPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code RS} 组：声明解析（{@link PermissionDeclaration}）与启动期校验（{@link PermissionDeclarationValidator}）
 * 的判据面。夹具类直接传给 {@code resolve}/{@code check}，不需要代理容器（那是 {@code AT} 组的事）；
 * 只有 {@code RS10} 摸一次真容器的钩子面。
 */
class PermissionDeclarationValidatorTest {

    public static class MethodLevel {

        @RequiresPermission(value = { "read", "write" }, message = "  逐字文案含首尾空格  ")
        public String data() {
            return "data";
        }
    }

    /** 组合注解（业务写法只出现元注解）。 */
    @RequiresPermission(value = "report:read", message = "需要报表只读角色")
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.METHOD, ElementType.TYPE })
    public @interface ReadReport {
    }

    public static class ComposedLevel {

        @ReadReport
        public String report() {
            return "report";
        }
    }

    @ReadReport
    public static class ComposedOnType {

        public String report() {
            return "report";
        }
    }

    @RequiresPermission("a")
    public static class LevelsMixed {

        @RequiresPermission("b")
        public String bothWays() {
            return "mixed";
        }
    }

    @RequiresPermission("parent:class")
    public static class ParentClassMarked {

        public String marked() {
            return "parent";
        }
    }

    public static class ChildOfMarkedParent extends ParentClassMarked {

        public String mine() {
            return "mine";
        }
    }

    public static class OverrideOfAnnotatedChild extends ParentClassMarked {

        @Override
        public String marked() {
            return "child";
        }
    }

    public static class ParentMethodMarked {

        @RequiresPermission("parent:method")
        public String marked() {
            return "parent";
        }
    }

    public static class OverrideChild extends ParentMethodMarked {

        @Override
        public String marked() {
            return "child";
        }
    }
    public interface IfaceContract {

        @RequiresPermission("iface:method")
        String fromIface();
    }

    public static class ImplOfIfaceContract implements IfaceContract {

        @Override
        public String fromIface() {
            return "ran";
        }
    }

    @RequiresPermission("iface:type")
    public interface IfaceTyped {

        String run();
    }

    public static class ImplOfIfaceTyped implements IfaceTyped {

        @Override
        public String run() {
            return "ran";
        }
    }

    /** 类型级坏声明且一个方法都没有：{@code check()} 里那次独立的 {@code onType} 调用的唯一消费者。 */
    @RequiresPermission({})
    public static class ClassMarkedEmpty {

        private ClassMarkedEmpty() {
        }
    }

    public static class BlankForm {

        @RequiresPermission({ " " })
        public String ask() {
            return "ask";
        }
    }

    public static class BlankSecond {

        @RequiresPermission({ "a", "" })
        public String ask() {
            return "ask";
        }
    }

    public static class Duplicated {

        @RequiresPermission({ "a", " a " })
        public String ask() {
            return "ask";
        }
    }

    public static class TrimmedLegal {

        @RequiresPermission({ " admin " })
        public String ask() {
            return "ask";
        }
    }

    public static class BadBean {

        @RequiresPermission({})
        public String boom() {
            return "boom";
        }
    }

    @RequiresPermission("ok")
    public static class GoodService {

        public String fine() {
            return "fine";
        }
    }

    /** 非泛型 {@link FactoryBean}：{@code allowEagerInit=false} 下 {@code getType} 给 {@code null} ⇒ 扫描跳过。 */
    @SuppressWarnings("rawtypes")
    public static class UninspectableFactory implements FactoryBean {

        @Override
        public Object getObject() {
            return new Object();
        }

        @Override
        public Class<?> getObjectType() {
            return null;
        }

        @Override
        public boolean isSingleton() {
            return true;
        }

        @RequiresPermission({})
        public String bad() {
            return "bad";
        }
    }

    private static Method method(Class<?> owner, String name) {
        try {
            return owner.getDeclaredMethod(name);
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    /** {@code RS1}：方法级声明被读出——权限逐项、组合缺省 {@code ALL_OF}、文案逐字（不 trim）、{@code where} 指方法。 */
    @Test
    void should_read_a_method_level_declaration_field_by_field() {
        PermissionDeclaration declaration = PermissionDeclaration.resolve(method(MethodLevel.class, "data"),
                MethodLevel.class);
        assertThat(declaration).isNotNull();
        assertThat(declaration.permissions()).containsExactly("read", "write");
        assertThat(declaration.logical()).isSameAs(RequiresPermission.Logical.ALL_OF);
        assertThat(declaration.message()).isEqualTo("  逐字文案含首尾空格  ");
        assertThat(declaration.where()).isEqualTo("MethodLevel.data");
    }

    /** {@code RS2}：组合注解在方法级与类型级都读得到，{@code where} 指向被标注的业务元素而不是元注解。 */
    @Test
    void should_read_composed_annotations_at_both_levels_locating_the_business_element() {
        PermissionDeclaration onMethod = PermissionDeclaration.resolve(method(ComposedLevel.class, "report"),
                ComposedLevel.class);
        PermissionDeclaration onType = PermissionDeclaration.onType(ComposedOnType.class);
        assertThat(onMethod.permissions()).containsExactly("report:read");
        assertThat(onMethod.where()).isEqualTo("ComposedLevel.report");
        assertThat(onMethod.message()).isEqualTo("需要报表只读角色");
        assertThat(onType).isNotNull();
        assertThat(onType.permissions()).containsExactly("report:read");
        assertThat(onType.where()).isEqualTo("类 ComposedOnType");
    }

    /**
     * {@code RS11}：声明<b>只</b>来自组合注解（元标注）——解析面读得到（{@code RS2}）而 AspectJ 表达式的
     * 切点门不开（{@code AT9} 的实测：方法级与类级两格都不触发），两格各报一次"永远不会生效"并给出出路；
     * 直接标注的那一格（{@code LevelsMixed}）必须照常通过，否则这条报就成了误伤。
     *
     * <p>判据是结构而不是类型：{@code MergedAnnotations} 的 {@code DIRECT} 认元注解、
     * {@code isAnnotationPresent} 不认，两者之差就是"这一格靠元注解"（见
     * {@link PermissionDeclaration#onlyThroughMetaAnnotation}）。
     */
    @Test
    void should_report_a_declaration_that_only_a_meta_annotation_carries() {
        assertThatThrownBy(() -> PermissionDeclarationValidator.check(ComposedLevel.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("ComposedLevel.report")
                .hasMessageContaining("组合注解")
                .hasMessageContaining("myddd.auth.validate-declarations=false");
        assertThatThrownBy(() -> PermissionDeclarationValidator.check(ComposedOnType.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("类 ComposedOnType")
                .hasMessageContaining("组合注解");
        assertThat(PermissionDeclaration.onlyThroughMetaAnnotation(ComposedOnType.class)).isTrue();
        assertThat(PermissionDeclaration.onlyThroughMetaAnnotation(
                method(LevelsMixed.class, "bothWays"))).isFalse();
        assertThatCode(() -> PermissionDeclarationValidator.check(LevelsMixed.class))
                .doesNotThrowAnyException();
    }

    /** {@code RS3}：方法级赢类级——生效声明只剩方法上那一条，{@code where} 落在方法。 */
    @Test
    void should_let_the_method_level_declaration_beat_the_class_level_one() {
        PermissionDeclaration declaration = PermissionDeclaration.resolve(method(LevelsMixed.class, "bothWays"),
                LevelsMixed.class);
        assertThat(declaration.permissions()).containsExactly("b");
        assertThat(declaration.where()).endsWith(".bothWays");
    }

    /**
     * {@code RS4}：注解在父类<b>类型</b>上、方法没被子类覆盖——第三级（声明类）读到，{@code where}
     * 是父类那一条类型级定位串。
     */
    @Test
    void should_resolve_an_inherited_method_through_the_annotated_declaring_class() {
        PermissionDeclaration inherited = PermissionDeclaration.resolve(method(ParentClassMarked.class, "marked"),
                ChildOfMarkedParent.class);
        assertThat(inherited).isNotNull();
        assertThat(inherited.permissions()).containsExactly("parent:class");
        assertThat(inherited.where()).isEqualTo("类 ParentClassMarked");
    }

    /** {@code RS5}：子类自己的方法三级全空 ⇒ {@code null}——这一格没有"沿层次向上"的第二套真相。 */
    @Test
    void should_return_null_for_the_childs_own_method_when_nothing_is_declared_anywhere_on_its_levels() {
        assertThat(PermissionDeclaration.resolve(method(ChildOfMarkedParent.class, "mine"),
                ChildOfMarkedParent.class)).isNull();
    }
    /**
     * {@code RS6}：子类覆盖了父类被声明的那一格——解析面给 {@code null}（第三级在覆盖后短路），
     * 而 {@code check()} 报"永远不会生效"，两种覆盖形态（父方法级声明、父类型级声明）各钉一遍。
     */
    @Test
    void should_report_declarations_dropped_by_override_as_ineffective() {
        assertThat(PermissionDeclaration.resolve(method(OverrideChild.class, "marked"), OverrideChild.class))
                .isNull();
        assertThatThrownBy(() -> PermissionDeclarationValidator.check(OverrideChild.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("ParentMethodMarked.marked")
                .hasMessageContaining("覆盖")
                .hasMessageContaining("myddd.auth.validate-declarations=false");
        assertThat(PermissionDeclaration.resolve(method(OverrideOfAnnotatedChild.class, "marked"),
                OverrideOfAnnotatedChild.class)).isNull();
        assertThatThrownBy(() -> PermissionDeclarationValidator.check(OverrideOfAnnotatedChild.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("类 ParentClassMarked")
                .hasMessageContaining("OverrideOfAnnotatedChild.marked 覆盖");
    }

    /**
     * {@code RS7}：声明写在接口那一侧（方法级与类型级两格）——解析面给 {@code null}，
     * {@code check()} 沿 {@code TYPE_HIERARCHY} 读到并指名"接口侧"报无效（探针 U3/U4）。
     */
    @Test
    void should_report_declarations_written_on_the_interface_side_as_ineffective() {
        assertThat(PermissionDeclaration.resolve(method(ImplOfIfaceContract.class, "fromIface"),
                ImplOfIfaceContract.class)).isNull();
        assertThatThrownBy(() -> PermissionDeclarationValidator.check(ImplOfIfaceContract.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("IfaceContract.fromIface")
                .hasMessageContaining("的接口侧");
        assertThat(PermissionDeclaration.resolve(method(ImplOfIfaceTyped.class, "run"),
                ImplOfIfaceTyped.class)).isNull();
        assertThatThrownBy(() -> PermissionDeclarationValidator.check(ImplOfIfaceTyped.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("类 IfaceTyped")
                .hasMessageContaining("声明写在接口类型上");
    }

    /**
     * {@code RS8}：类型级坏声明、类里一个方法都没有——{@code check()} 里那次独立的 {@code onType}
     * 调用的存在理由：合法性判定不许依赖"这个类至少有一个可扫方法"。
     */
    @Test
    void should_reject_a_type_level_empty_declaration_on_a_class_without_any_methods() {
        assertThatThrownBy(() -> PermissionDeclarationValidator.check(ClassMarkedEmpty.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("@RequiresPermission")
                .hasMessageContaining("类 ClassMarkedEmpty")
                .hasMessageContaining("不要权限")
                .hasMessageContaining("拒绝一切");
    }
    /**
     * {@code RS9}：构造即校验——空白元素（首项与次项两格）与归一后重复各报一条；合法写法
     * {@code " admin "} 归一为 {@code "admin"} 且 {@code satisfiedBy} 只问归一后的那一个。
     */
    @Test
    void should_reject_blank_and_duplicated_elements_but_trim_legal_declarations() {
        assertThatThrownBy(() -> PermissionDeclaration.resolve(method(BlankForm.class, "ask"), BlankForm.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("空白权限标识");
        assertThatThrownBy(() -> PermissionDeclaration.resolve(method(BlankSecond.class, "ask"), BlankSecond.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("空白权限标识");
        assertThatThrownBy(() -> PermissionDeclaration.resolve(method(Duplicated.class, "ask"), Duplicated.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("声明了两次");
        PermissionDeclaration trimmed = PermissionDeclaration.resolve(method(TrimmedLegal.class, "ask"),
                TrimmedLegal.class);
        assertThat(trimmed.permissions()).containsExactly("admin");
        List<String> asked = new ArrayList<>();
        assertThat(trimmed.satisfiedBy(permission -> {
            asked.add(permission);
            return "admin".equals(permission);
        })).isTrue();
        assertThat(asked).containsExactly("admin");
    }

    /**
     * {@code RS10}：钩子面对真容器的四种形态——① {@code validate-declarations=false} 时坏声明 bean
     * 在场也不扫；② 工厂 bean 只以 {@code &name} 出现在 {@code getBeanNamesForType(Object,true,false)} 的
     * 结果里（一手实测：{@code registerSingleton} 与 {@code registerBeanDefinition} 两种写法都只出
     * {@code &rawFactory}/{@code &defFactory}，且 {@code getType("rawFactory")} 给 {@code null}），
     * 扫它等于给一个不可能生效的形态报"永远不会生效"⇒ 按前缀跳过；③ 全部合法的容器过钩子不炸；
     * ④ 没注册的坏类不在扫描面里。
     */
    @Test
    void should_drive_the_container_hook_across_its_four_shapes() {
        MydddAuthProperties off = new MydddAuthProperties();
        off.setValidateDeclarations(false);
        AnnotationConfigApplicationContext withBadButOff = new AnnotationConfigApplicationContext();
        withBadButOff.register(BadBean.class);
        withBadButOff.refresh();
        assertThatCode(() -> new PermissionDeclarationValidator(withBadButOff.getBeanFactory(), off)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
        assertThatCode(() -> {
            AnnotationConfigApplicationContext factoryOnly = new AnnotationConfigApplicationContext();
            factoryOnly.getBeanFactory().registerSingleton("rawFactory", new UninspectableFactory());
            new PermissionDeclarationValidator(factoryOnly.getBeanFactory(), new MydddAuthProperties())
                    .afterSingletonsInstantiated();
        }).doesNotThrowAnyException();
        AnnotationConfigApplicationContext allGood = new AnnotationConfigApplicationContext();
        allGood.register(GoodService.class);
        allGood.refresh();
        assertThatCode(() -> new PermissionDeclarationValidator(allGood.getBeanFactory(), new MydddAuthProperties())
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
        AnnotationConfigApplicationContext empty = new AnnotationConfigApplicationContext();
        assertThatCode(() -> new PermissionDeclarationValidator(empty.getBeanFactory(), new MydddAuthProperties())
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
        withBadButOff.close();
        allGood.close();
        empty.close();
    }
}
