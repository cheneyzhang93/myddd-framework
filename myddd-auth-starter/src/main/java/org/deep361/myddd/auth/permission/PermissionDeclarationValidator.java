package org.deep361.myddd.auth.permission;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.deep361.myddd.auth.config.MydddAuthProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.RequiresPermission;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * 启动期声明校验：内核 javadoc 那句"空数组与空白元素由切面在启动期校验拒绝"在这里发生。
 *
 * <p><b>为什么 auth 壳做启动期扫描而限流壳不做</b>：Task 44 明确裁定"不为替应用复查自己刚做的决定而遍历
 * 每个 bean 的每个方法"，并把接口侧那个盲区写成用例（{@code RL15}/{@code RL16}）+ 包说明里的出路。
 * auth 这边不是推翻那条裁定，而是内核只对 {@link RequiresPermission} 多要了一件事（"由切面在启动期校验拒绝"
 * ——{@code RateLimit} 的 javadoc 没有这句）。既然扫描的代价已经必须付，同一次扫描里另外三件"注解标着、
 * 一次都不拦"的形态就顺带说清了：它们全是探针实测出来的门不开形态，不是猜的。
 *
 * <p><b>五件事，各自的判据</b>：① 坏声明（空数组、空白、重复）——内核明写；② 声明写在<b>接口</b>上
 * （方法或类型），{@code TYPE_HIERARCHY} 读得到而切点门不开（探针 U3/U4）；③ 子类<b>覆盖</b>了父类被声明的
 * 方法，父类那一级声明随覆盖消失（探针 48e 两行都没有 FIRED）；④ 类型级声明合法性的单独一次判定
 * （不依赖"该类至少有一个方法"）；⑤ 声明<b>只</b>来自组合注解（元标注）——{@code MergedAnnotations} 的
 * {@code DIRECT} 认元注解而 AspectJ 表达式的切点不认（一手实测真代理四格：方法级组合注解不触发、
 * 类级组合注解不触发、直接标注触发、无标注不触发），判据与不许改切点的理由在
 * {@link PermissionDeclaration#onlyThroughMetaAnnotation}。
 *
 * <p><b>报的是 {@link FrameworkException}（启动失败）而不是日志</b>：本模块零 {@code slf4j}，
 * 而敞开的鉴权洞不是可以"看一眼就过"的信息。出路是 {@code myddd.auth.validate-declarations=false}
 * ——它存在的意义就是让一个已有历史形态的应用能先起来再改（与 {@code permission-check.enabled} 的合法组合
 * 由 {@code IQ2}/{@code IQ3} 钉：要关校验就得同时关拦截，否则等于"声明不查、拦截照旧"）。
 *
 * <p><b>{@link #afterSingletonsInstantiated()} 而不是 {@code @PostConstruct}</b>：属性类的自检在自己的
 * {@code @PostConstruct} 里就够了（{@code IQ} 组），而这里要看<b>别人的</b> bean，必须等全部单例就绪；
 * 早一步会有"扫到还没被代理包装的半成品类"这种假形态。
 *
 * <p><b>扫不到就是扫不到，且要说出来</b>：{@code getBeanNamesForType(Object.class, true, false)} 用
 * {@code allowEagerInit=false}，工厂 bean 只以 {@code &name} 出现在结果里（一手实测：{@code registerSingleton}
 * 与 {@code registerBeanDefinition} 两种写法都只出 {@code &rawFactory}/{@code &defFactory}），而
 * {@code &name} 那一格是<b>工厂自己</b>——它的方法从来不是业务调用点，扫它只会给一个不可能生效的形态报错，
 * 所以按前缀跳过；产品类型在同一组参数下可能给 {@code null}（同一探针：{@code getType("rawFactory")=null}），
 * 提前实例化去换一次复查代价比收益大，也跳过。这一格由解析面兜住：
 * 同一个构造器（{@link PermissionDeclaration}）在第一次调用时还会走一遍，坏声明只是从启动期推迟到
 * 第一次调用，不会变成静默放行——这是"校验只有一份"那条理由的实际收益，也是 {@code RS} 组不假装
 * 扫描覆盖全部 bean 的原因。
 */
public final class PermissionDeclarationValidator implements SmartInitializingSingleton {

    private final ListableBeanFactory beans;

    private final MydddAuthProperties properties;

    /**
     * @param beans      容器句柄，用来列 bean（不实例化任何 bean）
     * @param properties 属性；{@code validate-declarations} 决定本钩子整段是否执行
     *
     * <p><b>刻意不做 null 检查</b>：两个参数只由 {@code MydddAuthAutoConfiguration} 那一个调用点注入，
     * 拿到 null 时 NPE 出现在装配自己的方法里、出现在启动期，比我们自己编的一句 {@link FrameworkException}
     * 更早也更准。与 {@code CredentialVerifierGuard} 的裁定同一条；{@code AuthPortGuard} 之所以有那一句，
     * 是因为 {@code PG} 组的夹具真的直接 {@code new} 它，那一格由 {@code AC7} 钉着。
     */
    public PermissionDeclarationValidator(ListableBeanFactory beans, MydddAuthProperties properties) {
        this.beans = beans;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!this.properties.isValidateDeclarations()) {
            return;
        }
        for (String beanName : this.beans.getBeanNamesForType(Object.class, true, false)) {
            // {@code &name} 那一格是工厂自己（实测两种写法都只出 {@code &name}）：工厂的方法从来不是
            // 业务调用点，扫它等于给一个不可能生效的形态报"永远不会生效"。产品类型在 allowEagerInit=false
            // 下拿不到，这一格交回解析面兜（见类 javadoc 最后一段）。
            if (beanName.startsWith(BeanFactory.FACTORY_BEAN_PREFIX)) {
                continue;
            }
            Class<?> type = this.beans.getType(beanName);
            // 工厂 bean 在 allowEagerInit=false 下可能给 null：提前实例化去换一次复查，代价比收益大。
            if (type == null || type.isInterface() || ClassUtils.isCglibProxyClass(type)) {
                continue;
            }
            check(type);
        }
    }

    /**
     * 单个类的校验。容器钩子与 {@code RS} 组用例共用这一个入口（用例直接传夹具类，不需要真容器）。
     *
     * <p>⚠️ 方法枚举用 {@code ReflectionUtils.getAllDeclaredMethods}：<b>Spring 7.0.9 的
     * {@code ClassUtils} 已经没有 {@code getAllDeclaredMethods}</b>（一手 {@code javap}），而前者实测
     * 会把接口的 {@code default} 方法与 {@code Object} 方法一起给出来（探针 48f），所以两道过滤都要：
     * {@code isUserLevelMethod} 挡不住 {@code default}（实测 {@code true}），必须自己按
     * {@code getDeclaringClass().isInterface()} 排除——接口那一侧交给 ② 那一段去报。
     */
    static void check(Class<?> beanClass) {
        // 只用两个工厂方法的校验副作用：非法声明在 PermissionDeclaration 的构造里就抛，返回值在这里不需要。
        // 类型级单独走一次，是为了不依赖"这个类至少有一个方法"那一格（RS10 的合法夹具里有一个空 bean 类）。
        PermissionDeclaration.onType(beanClass);
        if (PermissionDeclaration.onlyThroughMetaAnnotation(beanClass)) {
            throw ineffective(PermissionDeclaration.describe(beanClass), beanClass,
                    "它是组合注解（元标注出来的），而本切面的切点是 AspectJ 表达式");
        }
        for (Method method : ReflectionUtils.getAllDeclaredMethods(beanClass)) {
            if (isScannable(method)) {
                PermissionDeclaration.resolve(method, beanClass);
                if (PermissionDeclaration.onlyThroughMetaAnnotation(method)) {
                    throw ineffective(PermissionDeclaration.describe(method), beanClass,
                            "它是组合注解（元标注出来的），而本切面的切点是 AspectJ 表达式");
                }
            }
        }
        checkInterfaceSide(beanClass);
        checkDroppedByOverride(beanClass);
    }

    private static boolean isScannable(Method method) {
        if (Modifier.isStatic(method.getModifiers()) || !ClassUtils.isUserLevelMethod(method)) {
            return false;
        }
        Class<?> owner = method.getDeclaringClass();
        return owner != Object.class && !owner.isInterface();
    }

    /** ②：声明写在接口那一侧——读得到、永远不生效，所以要给一句成话的说法。 */
    private static void checkInterfaceSide(Class<?> beanClass) {
        for (AnnotatedElement source : PermissionDeclaration.hierarchySources(beanClass)) {
            if (PermissionDeclaration.interfaceSide(source)) {
                throw ineffective(PermissionDeclaration.describe(source), beanClass, "声明写在接口类型上");
            }
        }
        for (Method method : ReflectionUtils.getAllDeclaredMethods(beanClass)) {
            if (Modifier.isStatic(method.getModifiers()) || method.getDeclaringClass().isInterface()) {
                continue;
            }
            for (AnnotatedElement source : PermissionDeclaration.hierarchySources(method)) {
                if (PermissionDeclaration.interfaceSide(source)) {
                    throw ineffective(PermissionDeclaration.describe(source), beanClass,
                            PermissionDeclaration.describe(method) + " 的接口侧");
                }
            }
        }
    }

    /** ③：子类覆盖了父类被声明的那一格——门随覆盖关闭（探针 48e）。 */
    private static void checkDroppedByOverride(Class<?> beanClass) {
        if (MergedAnnotations.from(beanClass, SearchStrategy.DIRECT).isPresent(RequiresPermission.class)) {
            return;
        }
        for (Class<?> ancestor = beanClass.getSuperclass(); ancestor != null && ancestor != Object.class;
                ancestor = ancestor.getSuperclass()) {
            boolean ancestorAnnotated = MergedAnnotations.from(ancestor, SearchStrategy.DIRECT)
                    .isPresent(RequiresPermission.class);
            for (Method declared : ReflectionUtils.getDeclaredMethods(ancestor)) {
                if (Modifier.isStatic(declared.getModifiers()) || !ClassUtils.isUserLevelMethod(declared)) {
                    continue;
                }
                boolean declaredAnnotated = MergedAnnotations.from(declared, SearchStrategy.DIRECT)
                        .isPresent(RequiresPermission.class);
                if (!declaredAnnotated && !ancestorAnnotated) {
                    continue;
                }
                Method overrider = ClassUtils.getMostSpecificMethod(declared, beanClass);
                if (overrider.getDeclaringClass() == ancestor
                        || MergedAnnotations.from(overrider, SearchStrategy.DIRECT)
                                .isPresent(RequiresPermission.class)) {
                    continue;
                }
                throw ineffective(PermissionDeclaration.describe(declaredAnnotated ? declared : ancestor),
                        beanClass, PermissionDeclaration.describe(overrider) + " 覆盖");
            }
        }
    }

    private static FrameworkException ineffective(String declaration, Class<?> beanClass, String how) {
        return new FrameworkException("@RequiresPermission 写在 " + declaration + " 上，而它永远不会生效（"
                + how + "，被 " + beanClass.getSimpleName() + " 调用时切点门不开）："
                + "Spring 的 @annotation 与 @within 按目标类自己声明的那一格匹配（Plan 4 探针 48e"
                + " 与 Task 44 探针 U3/U4 实测：接口侧、被覆盖的父类侧都是零命中），"
                + "注解没写错、代码也没跑错，只是这一格现在不拦。"
                + "请把 @RequiresPermission 移到 " + beanClass.getSimpleName()
                + " 上对应的方法或类上（或删掉那条无效声明）。"
                + "确实需要保留现状时写 myddd.auth.validate-declarations=false 关掉这道复查"
                + "——那时其余校验与拦截面照常工作，只是这一格真的没有保护，代价自己承担");
    }
}
