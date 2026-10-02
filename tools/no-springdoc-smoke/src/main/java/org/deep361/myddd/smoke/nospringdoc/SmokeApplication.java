package org.deep361.myddd.smoke.nospringdoc;

import org.deep361.myddd.kernel.ddd.context.Domain;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 只引 {@code myddd-web-starter} 而不引 springdoc 的样例应用（本夹具的唯一用途就是被测试真启动一次）。
 *
 * <p>{@code @Domain} 是必需的而不是装饰：web-starter 依赖 domain-starter 复用内核的 fluent 命名策略，
 * 而领域装配的语义是"扫不到 {@code @Domain} 领域模块即启动失败"（README「跨模块依赖」一节明写的那条已知代价）。
 * 本夹具顺手把这条代价也钉住——真起一个只引 web-starter 的应用，看它到底起不起得来。
 */
@Domain(value = "smoke")
@SpringBootApplication
public class SmokeApplication {
}
