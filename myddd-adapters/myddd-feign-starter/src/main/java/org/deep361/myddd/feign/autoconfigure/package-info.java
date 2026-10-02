/**
 * 出站调用（OpenFeign）装配面：{@code myddd.feign.*} 三个开关与两个 bean 的条件装配。
 *
 * <p>本包只做"条件与装配"，能力本身在 {@code org.deep361.myddd.feign.trace} 与
 * {@code org.deep361.myddd.feign.error} 里，因而两侧都能脱离 Spring 单测。
 */
package org.deep361.myddd.feign.autoconfigure;
