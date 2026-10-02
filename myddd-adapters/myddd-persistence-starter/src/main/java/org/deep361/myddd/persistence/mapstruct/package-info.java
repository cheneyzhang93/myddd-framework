/**
 * 对象转换器（PO ↔ 领域 ↔ 传输对象）的框架约定：一份 {@link
 * org.deep361.myddd.persistence.mapstruct.MydddMapperConfig} 承载全部映射策略。
 *
 * <p>本包刻意<b>不含</b>任何具体转换器：转换是业务语义（哪个字段配哪个字段），框架只负责让
 * "漏了一个字段"这件事在编译期暴露。业务转换器按分层各归其位——PO ↔ 领域在
 * {@code <p>-infrastructure}，领域 ↔ DTO 在 {@code <p>-application}，DTO ↔ VO 在 {@code <p>-api}，
 * 三者都只写 {@code @Mapper(config = MydddMapperConfig.class)} 一行配置。
 */
package org.deep361.myddd.persistence.mapstruct;
