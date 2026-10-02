package org.deep361.myddd.persistence.mybatis;

import java.util.Optional;

/**
 * 当前操作人提供者：审计字段里 {@code creator/updater} 的取值来源。
 *
 * <p><b>为什么不直接依赖鉴权组件</b>：持久化能力与鉴权能力是两条独立交付线（Plan 3 与 Plan 4），编译期依赖会把
 * 它们焊死——只引持久化 starter 的批处理应用会因为"没有 sa-token"而起不来。这里只留一个接缝：默认实现
 * {@link #none()} 表示"取不到人"，届时审计列写 {@code myddd.persistence.audit.unknown-principal}；
 * 鉴权线交付自己的实现（或业务直接声明一个 bean）即整体生效。
 *
 * <p><b>实现契约</b>：不得抛异常——本方法在每次插入/更新上都会被调用一次，取不到人请返回
 * {@link Optional#empty()}，而不是让业务写入失败；须线程安全。返回值若是空白串，按"取不到人"处理。
 */
@FunctionalInterface
public interface PrincipalProvider {

    /** 当前操作人标识；无上下文（后台任务、消息投递线程、未登录接口）时返回空。 */
    Optional<String> currentPrincipal();

    /** 缺省实现：永远取不到人。 */
    static PrincipalProvider none() {
        return Optional::empty;
    }
}
