/**
 * {@code myddd.auth.*} 四个自有键、唯一一份自动装配，以及两个守卫的装配点。
 *
 * <p>键表与语义的唯一出处是计划正文 §1.2.1；本包不做任何三方键的翻译（{@code sa-token.*}、
 * Spring Security 的 {@code spring.security.*} 原样交给各家），在这里重复一遍就会有两个真源。
 *
 * <p><b>provider 二选一的判定函数不在本包</b>：{@code myddd.auth.provider} 由 provider 侧的
 * {@code @Conditional} 直接从 {@code Environment} 读，判定逻辑两家各镜像一份。本包只在诊断文本里
 * 以字符串提到那个键名与两个合法值——字符串不是依赖。
 *
 * <p><b>{@link AuthPortGuard} 是本包唯一回答"端口在场吗"的地方</b>。一手实测（探针 {@code 48c}）
 * 给出的四档形态（零/一/二/二加 {@code @Primary}）都在这里判；{@code ObjectProvider#getIfAvailable()}
 * 在多候选且无 {@code @Primary} 时<b>抛</b>而不是返回 {@code null}，所以分类真源是 {@code stream()}。
 * {@code CredentialVerifierGuard} 只做在场性判断，因此同一支探针结论在它那里表现为"只用
 * {@code stream().findFirst()}，永不抛"——两个守卫的差别是有意的，不是抄漏。
 */
package org.deep361.myddd.auth.config;
