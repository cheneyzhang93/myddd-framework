/**
 * 一致性适配器：分布式锁（{@code LockPort} 的 Redisson 实现）与幂等占位（{@code IdempotentPort} 的
 * Redis 实现），以及把两个注解拦下来的那两条切面。
 *
 * <p>子包按<b>能力</b>而不是按角色切：{@code lock}＝{@code @DistributedLock} 注解 + 切面 +
 * {@code DefaultLockPort} + Redisson 客户端构造入口，{@code idempotent}＝{@code @Idempotent} 切面 +
 * 缺省键摘要 + {@code RedisIdempotentStore}（{@code IdempotentPort} 的缺省实现），
 * {@code support}＝两条链<b>共用</b>的机制（现在是注解里那串 SpEL 键表达式的求值与报话），
 * {@code autoconfigure}＝装配与配置键。
 *
 * <p><b>为什么按能力切而不是切出一个 {@code aspect} 包</b>：一个能力的注解、切面、端口实现长在同一个
 * 变化原因上——改锁的语义时不该跨三个包凑改动。切出去的第二个好处是"共用机制"必须显形：
 * 锁与幂等的键表达式曾经各写一份，抽进 {@code support} 之后两侧的差异只剩下消息里的名字。
 *
 * <p>锁的<b>键</b>由调用方给出，本模块不解释它的含义，也<b>不做</b>任何形式的规范化（不转小写、
 * 不替换分隔符）：同一个键字符串在两个进程里必须拼出同一个 Redis 键名，否则锁形同不存在。
 * 幂等的缺省键是个例外，它的形状由 §0.5 的裁定 R2 钉死（{@code 简单类名.方法名:参数摘要}），
 * 而 Redis 与 DB 两种实现必须对同一次调用算出<b>同一个</b>键（Task 27 的等价用例负责取证）。
 * 框架只提供 {@code myddd.consistency.lock.key-prefix} 与
 * {@code myddd.consistency.idempotent.key-prefix} 两个前缀段，用来在共用 Redis 实例时圈定本应用的键范围。
 *
 * <p>本包不出现领域模型；业务工程注入 {@code org.deep361.myddd.kernel.port.consistency} 下的端口类型，
 * 使用这里装配的切面与端口。
 */
package org.deep361.myddd.consistency;
