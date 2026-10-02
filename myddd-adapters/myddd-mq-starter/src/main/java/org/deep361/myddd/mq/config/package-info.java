/**
 * 装配面：{@code myddd.mq.*} 配置键与唯一自动配置入口。
 *
 * <p>配置值绑 {@code String} 再经 {@link org.deep361.myddd.mq.MqProvider#parse(String)} 解析，
 * 而不是直接绑枚举：诊断消息必须同时含"哪个键、配了什么、允许什么"，枚举转换异常给不出这三段。
 * 校验全部挂在 {@link org.deep361.myddd.mq.config.MydddMqProperties#validate()}（{@code @PostConstruct}）
 * 与 bean 工厂方法里，后果是"配错起不来"，不是"第一次发消息才发现"。
 */
package org.deep361.myddd.mq.config;
