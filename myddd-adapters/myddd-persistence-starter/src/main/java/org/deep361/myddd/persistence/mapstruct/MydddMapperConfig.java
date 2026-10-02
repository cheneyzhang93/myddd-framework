package org.deep361.myddd.persistence.mapstruct;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.MapperConfig;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 业务转换器（{@code @Mapper}）的统一策略基座：写
 * {@code @Mapper(config = MydddMapperConfig.class)} 即继承框架全部映射约定。
 *
 * <p>四条策略各对应一类会被静默放过的故障：
 * <ul>
 *   <li>{@code unmappedTargetPolicy = ERROR}——目标属性没有来源时<b>编译失败</b>。MapStruct 默认只
 *       告警，于是"DTO 加了字段、转换器忘了改"会变成线上永久为 {@code null} 的那一列。
 *       确实不映射的目标用 {@code @Mapping(target = "x", ignore = true)} 显式写下决定。</li>
 *   <li>{@code componentModel = "spring"} + {@code injectionStrategy = CONSTRUCTOR}——生成物是容器
 *       里的协作者，依赖走 {@code final} 字段的单构造器，容器外也能直接 {@code new} 出来做单测。</li>
 *   <li>{@code nullValuePropertyMappingStrategy = IGNORE}——部分更新（{@code @MappingTarget}）时
 *       源端为 {@code null} 的属性保持目标原值，不会把已有数据写空。</li>
 *   <li>{@code suppressTimestampInGenerated = true}——生成物不带构建时间戳，同一份源码每次构建出
 *       同样的字节码。</li>
 * </ul>
 *
 * <p><b>命名纪律</b>：本模块与业务工程都不要手写以 {@code MapperImpl} 结尾的类名——该后缀属于
 * MapStruct 生成物，并被 JaCoCo 的生成物排除名单覆盖（见本模块 {@code pom.xml}）。
 *
 * <p>注解处理器不随依赖传递，因此使用方必须在自己的 {@code maven-compiler-plugin} 里声明
 * {@code org.mapstruct:mapstruct-processor}；漏声明的症状是"接口没有生成 {@code Impl}"。
 * 可直接照抄的接线见 {@code myddd-persistence-starter/pom.xml}。
 */
@MapperConfig(
        componentModel = "spring",
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        suppressTimestampInGenerated = true)
public interface MydddMapperConfig {
}
