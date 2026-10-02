package org.deep361.myddd.auth.credential;

/**
 * 旧落点残留：接缝本体已按偏差 D-176 迁移到
 * {@code org.deep361.myddd.kernel.port.auth.CredentialVerifier}（{@code myddd-kernel-port}），
 * 那里是唯一真源——消费者是 satoken provider，而 auth 这条线上壳与 provider 之间没有 compile 边
 * （判据表见计划正文 Task 48 Step 6 开头；内核先例 {@code JobHandler}/{@code MessageListener} 同在端口层）。
 *
 * <p>本类型只是待删除的兼容别名（本壳的 {@link CredentialVerification} 与既有测试源仍按旧 FQN 引用它，
 * 实现轮没有删除文件的权限），不再是任何装配判据的消费对象：
 * 守卫、自动装配与 {@code AC8} 的 {@code Class.forName} 认的都是端口层那一个 FQN。
 * 新代码一律 import {@code org.deep361.myddd.kernel.port.auth.CredentialVerifier}。
 *
 * @deprecated 落点修正（D-176）：请改用 {@code org.deep361.myddd.kernel.port.auth.CredentialVerifier}
 */
@Deprecated(since = "1.0.0", forRemoval = true)
public interface CredentialVerifier extends org.deep361.myddd.kernel.port.auth.CredentialVerifier {
}
