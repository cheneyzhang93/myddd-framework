package org.deep361.myddd.auth.permission;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.RequiresPermission;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;

/**
 * 一次权限声明的归一化中间值：要哪些权限、怎么组合、拒绝时说什么、声明读自哪里。
 *
 * <p><b>三条存在的理由</b>：① <b>校验只有一份</b。内核 {@link RequiresPermission} 的 javadoc 明写
 * "空数组与空白元素由切面在启动期校验拒绝"，而启动期扫描会跳过它拿不到类型的 bean
 * （{@code getType} 在 {@code allowEagerInit=false} 下可能给 {@code null}）——校验只写在扫描器里就留下
 * "被跳过的 bean 带着坏声明安静运行"的洞，只写在切面里就违背内核那句"启动期"。两侧共用这一个构造，
 * 一条消息只在这里拼一次。② <b>解析规则只有一份</b>：三级 {@code DIRECT} 链（见 {@link #resolve}）。
 * ③ <b>{@code where} 只算一次</b>：拒绝消息、启动期报错、管理端展示读同一个定位串。
 *
 * <p><b>{@code permissions} 存 trim 之后的值</b>：内核 {@code AuthUser#hasPermission} 是精确匹配、无通配，
 * provider 侧 {@code " admin:read "} 与 {@code "admin:read"} 是两个权限名。归一化放在这一层，两家 provider
 * 才不必各自再 trim 一次；归一后重复的（{@code {"a", " a "}}）按重复拒绝——那多半是复制粘贴改漏，
 * 而不是有意的写法。
 *
 * <p><b>{@code message} 存原文</b>（不 trim、不改写）：R12 的裁定是"注解自带文案只换展示文案、不换身份"，
 * 用例要逐字断言"异常 {@code getMessage()} == 注解里那串"，这里 trim 一下那条就变成在测框架自己的改写。
 * 空白 {@code message} 的含义（走统一错误码文案）由调用方判，不在这里折叠。
 *
 * <p><b>类型是包内的</b>：它是"切面 + 启动期校验器"两处的内部中间值，不是给业务或 provider 用的 API。
 */
record PermissionDeclaration(List<String> permissions, RequiresPermission.Logical logical, String message,
        String where) {

    /**
     * 构造即校验：非法声明不可能成为一个实例。判据顺序是"空数组 → 空白元素 → 归一后重复"，
     * 与内核 javadoc 把空数组写在第一位一致。
     *
     * <p>⚠️ 紧凑构造器里读的是<b>参数</b>（{@code where}）而不是 {@code this.where}：记录组件在紧凑
     * 构造器结束前尚未赋值。
     */
    PermissionDeclaration {
        if (permissions.isEmpty()) {
            throw new FrameworkException("@RequiresPermission 在 " + where + " 上没写任何权限："
                    + "value 至少要一项（内核 javadoc 原文）。空数组在这里既不能读成“不要权限”，"
                    + "也不能读成“拒绝一切”——两种读法都会让业务以为自己的接口正在被保护");
        }
        List<String> normalized = new ArrayList<>(permissions.size());
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                throw new FrameworkException("@RequiresPermission 在 " + where + " 上有一个空白权限标识："
                        + permissions + "。空白串会被 provider 当成一个真实的权限名"
                        + "（内核 AuthPort#hasPermission 的实现契约要求 permission 不可为空白），"
                        + "于是这条声明永远不会被满足——它不是宽松，是永不可达");
            }
            String trimmed = permission.trim();
            if (normalized.contains(trimmed)) {
                throw new FrameworkException("@RequiresPermission 在 " + where + " 上把 " + trimmed
                        + " 声明了两次：" + permissions + "。ALL_OF 下它是无意义的重复，"
                        + "ANY_OF 下它会掩盖“本想写两项却写成同一项”");
            }
            normalized.add(trimmed);
        }
        permissions = List.copyOf(normalized);
    }

    /**
     * 解析这次调用生效的声明：方法级 → 目标类 → 执行方法的声明类，<b>三级全部 {@code DIRECT}</b>。
     *
     * <p>这个形状是被两条一手实测（Plan 4 探针 48e/48g，切点面 {@code @annotation(..) || @within(..)}）
     * 夹出来的：
     * ① "注解只在父类上、方法<b>没被子类覆盖</b>"这种形态<b>门是开的</b>（执行的那个方法就是父类声明的那一个），
     * 所以第三级必须存在，否则会出现"门开了、解析读不到"的悬空形态；
     * ② "子类覆盖了父类被声明的那一格"与"声明写在接口上"两种形态<b>门根本不开</b>。解析面若改用
     * {@code TYPE_HIERARCHY}（或 {@code AnnotatedElementUtils} 的 {@code find} 系），就会读到一条切面
     * 永远不会触发的声明，得到"解析认为该拦、切点认为不该切"的第二套真相——框架对外说"这条声明生效"，
     * 运行时一次都不拦。这两格不在解析面兜，改由 {@link PermissionDeclarationValidator} 在启动期报出来。
     *
     * <p>组合注解照样读得到：{@code DIRECT} 只挡"沿类型层次向上找"，不挡元注解（探针 F1/F5/F7/F9，
     * 且 {@code MergedAnnotation#getSource()} 给的是<b>被标注的那个元素</b>，所以 {@code where}
     * 指向业务的类而不是元注解）。
     *
     * <p><b>与限流侧的刻意不同</b>：那里的声明解析用 {@code findMergedAnnotation}（沿类型层次），
     * 其用例恰恰钉住"父类上的注解对子类生效"。两条内核承诺本来相反——{@link RequiresPermission} 的 javadoc
     * 明写"本注解未标 {@code @Inherited}，标注在父类上的注解不会被子类继承"。<b>不许把两处"统一"成一份实现</b>：
     * 那是拿一处安全语义换另一处的便利。
     *
     * @param mostSpecificMethod 已折算到目标类视角的方法（{@code ClassUtils#getMostSpecificMethod} 的结果）
     * @param targetClass        代理背后的目标类
     * @return 生效的声明；三级都没有时 {@code null}（= 这次调用没有权限要求）
     */
    static PermissionDeclaration resolve(Method mostSpecificMethod, Class<?> targetClass) {
        MergedAnnotation<RequiresPermission> onMethod = direct(mostSpecificMethod);
        if (onMethod != null) {
            return from(onMethod);
        }
        PermissionDeclaration onTargetClass = onType(targetClass);
        if (onTargetClass != null) {
            return onTargetClass;
        }
        Class<?> declaringClass = mostSpecificMethod.getDeclaringClass();
        if (declaringClass == targetClass || declaringClass.isInterface()) {
            return null;
        }
        MergedAnnotation<RequiresPermission> onDeclaringClass = direct(declaringClass);
        return onDeclaringClass == null ? null : from(onDeclaringClass);
    }

    /**
     * 只看这个类自己那一条<b>类型级</b>声明；校验器用它单独判"类上那条合不合法"，
     * 从而不依赖"这个类至少有一个可扫方法"那一格。
     */
    static PermissionDeclaration onType(Class<?> type) {
        MergedAnnotation<RequiresPermission> found = direct(type);
        return found == null ? null : from(found);
    }

    /** 组合方式在这里落地一次：{@code ALL_OF} 要全中，{@code ANY_OF} 中一个就够（两侧都短路）。 */
    boolean satisfiedBy(Predicate<String> checker) {
        return this.logical == RequiresPermission.Logical.ALL_OF ? this.permissions.stream().allMatch(checker)
                : this.permissions.stream().anyMatch(checker);
    }

    /**
     * 这条声明是不是<b>只</b>从元注解（组合注解）那一条路读到的。
     *
     * <p><b>为什么单独问这一格</b>：{@code @Around("@annotation(X) || @within(X)}") 的 AspectJ 表达式
     * <b>不认元注解</b>（一手实测，真代理四格：方法级组合注解不触发、类级组合注解不触发、直接标注触发、
     * 无标注不触发），而 {@code MergedAnnotations} 的 {@code DIRECT} 认——于是"解析读得到、切点门不开"
     * 这一格是静默的鉴权洞。它与接口侧、被覆盖的父类侧同族，所以同样交回
     * {@link PermissionDeclarationValidator} 在启动期报，而不是把切点换成 {@code AnnotationMatchingPointcut}：
     * 后者 {@code checkInherited=true} 会连接口侧一起开门，把"接口声明永不生效"那两条钉死的语义推翻。
     */
    static boolean onlyThroughMetaAnnotation(AnnotatedElement element) {
        return MergedAnnotations.from(element, SearchStrategy.DIRECT).isPresent(RequiresPermission.class)
                && !element.isAnnotationPresent(RequiresPermission.class);
    }

    /**
     * 沿类层次的读法，<b>只服务于"报无效声明"</b>（校验器），解析面一处都不用：
     * {@code TYPE_HIERARCHY} 会读到父类与接口两侧的声明，而两侧的门都不开。
     *
     * @return 每一条命中的声明<b>写在哪个元素上</b>（方法或类型），顺序不保证
     */
    static List<AnnotatedElement> hierarchySources(AnnotatedElement element) {
        List<AnnotatedElement> sources = new ArrayList<>();
        MergedAnnotations.from(element, SearchStrategy.TYPE_HIERARCHY).stream(RequiresPermission.class)
                .forEach(annotation -> sources.add((AnnotatedElement) annotation.getSource()));
        return sources;
    }

    /** 来源是不是接口那一侧（方法声明在接口上，或注解写在接口类型上）。 */
    static boolean interfaceSide(AnnotatedElement source) {
        if (source instanceof Method method) {
            return method.getDeclaringClass().isInterface();
        }
        return source instanceof Class<?> type && type.isInterface();
    }

    /** 定位串：方法是 {@code 简单类名.方法名}，类是 {@code 类 简单名}。 */
    static String describe(Object source) {
        if (source instanceof Method method) {
            return method.getDeclaringClass().getSimpleName() + "." + method.getName();
        }
        if (source instanceof Class<?> type) {
            return "类 " + type.getSimpleName();
        }
        return String.valueOf(source);
    }

    private static MergedAnnotation<RequiresPermission> direct(AnnotatedElement element) {
        MergedAnnotations annotations = MergedAnnotations.from(element, SearchStrategy.DIRECT);
        return annotations.isPresent(RequiresPermission.class) ? annotations.get(RequiresPermission.class) : null;
    }

    private static PermissionDeclaration from(MergedAnnotation<RequiresPermission> annotation) {
        RequiresPermission spec = annotation.synthesize();
        return new PermissionDeclaration(Arrays.asList(spec.value()), spec.logical(), spec.message(),
                describe(annotation.getSource()));
    }
}
