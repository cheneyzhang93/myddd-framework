package org.deep361.myddd.domain.context;

import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.Objects;

/**
 * 一个已被发现的领域模块：模块名 + 需要装配层扫描的包 + 声明来源类。
 *
 * <p>不可变值对象：{@code scanPackages} 在构造时做防御性拷贝，之后不可修改。
 *
 * @param name         模块名（应用内唯一），不可为空白
 * @param scanPackages 需要装配层扫描的包（含子包），不可为空、不可含空白项
 * @param source       声明来源类（被 {@code @Domain} 标注的那个类），不可为 null
 */
public record DomainModule(String name, List<String> scanPackages, Class<?> source) {

    public DomainModule {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("领域模块名不能为空白");
        }
        Objects.requireNonNull(scanPackages, "扫描包不能为 null");
        if (scanPackages.isEmpty()) {
            throw new IllegalArgumentException("领域模块 " + name + " 的扫描包不能为空");
        }
        for (String scanPackage : scanPackages) {
            if (scanPackage == null || scanPackage.isBlank()) {
                throw new IllegalArgumentException("领域模块 " + name + " 的扫描包不能包含空白项");
            }
        }
        Objects.requireNonNull(source, "模块来源类不能为 null");
        scanPackages = List.copyOf(scanPackages);
    }

    /** 给定包名是否属于本模块（等于某个扫描包，或位于其子包内；按包边界比较，不做字符串前缀匹配）。 */
    public boolean covers(String candidatePackage) {
        Objects.requireNonNull(candidatePackage, "包名不能为 null");
        for (String scanPackage : scanPackages) {
            if (candidatePackage.equals(scanPackage) || candidatePackage.startsWith(scanPackage + ".")) {
                return true;
            }
        }
        return false;
    }

    /** 给定类型是否属于本模块（按其所在包判断）。 */
    public boolean covers(Class<?> type) {
        Objects.requireNonNull(type, "类型不能为 null");
        return covers(ClassUtils.getPackageName(type));
    }
}
