/**
 * OpenAPI 文档定制：把 {@code myddd.web.openapi.*} 写进元信息，并把统一响应信封补齐到与线上字节一致。
 *
 * <p>入口是 springdoc 的 {@code GlobalOpenApiCustomizer}（它按 bean 类型被 springdoc 收集，分组文档也回放），
 * 框架不声明 {@code OpenAPI} bean。{@code myddd.web.openapi.enabled=false} 只关闭本包的两处贡献，
 * {@code /v3/api-docs} 与 swagger-ui 仍由 springdoc 提供（三方键 {@code springdoc.api-docs.enabled} 的地盘）。
 */
package org.deep361.myddd.web.openapi;
