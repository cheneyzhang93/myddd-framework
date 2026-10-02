/**
 * R14 凭据校验接缝的<b>守卫</b>包——接缝本体不在这里。
 *
 * <p><b>接缝在哪</b>：{@code org.deep361.myddd.kernel.port.auth.CredentialVerifier}
 * （{@code myddd-kernel-port}）。R14 原文把落点写在本模块，落点按一手拓扑证据修正到端口层并登记为
 * D-176，判据表在计划正文 Task 48 Step 6：{@code login} 的消费者是 satoken provider，而 auth 这条线上
 * 壳与 provider 之间没有依赖边（mq 侧那条边的理由在这里不成立）；内核里同方向的 {@code JobHandler}
 * 与 {@code MessageListener} 都在端口层。
 * {@code AC8} 用一次正向 {@code Class.forName} 钉住这个 FQN，接口被搬回来那条就红。
 *
 * <p><b>本包只有一件事</b>：容器里有 {@code AuthPort} 却没有 {@code CredentialVerifier} 时，
 * 在启动期说清楚（{@code CV1}）。为什么不能留到运行期：Sa-Token 的 {@code StpUtil.login(loginId)}
 * 五个重载一个凭据都不校验，业务漏提供校验器时 {@code login} 会<b>照常发一个可用 token</b>，
 * 200 响应背后没有任何校验记录。
 *
 * <p><b>关掉之后是什么形态</b>：{@code myddd.auth.credential.verifier-required=false} 是路线图为
 * "只做鉴权不做登录"的部署留的唯一出路；关掉之后仍然没人校验凭据，只是框架不再拦——这条出路只适用于
 * "本部署根本不会调 {@code AuthPort.login}" 这一事实成立时。写 {@code myddd.auth.enabled=false}
 * 不是它的替身，那关掉的是整个能力。
 *
 * <p><b>"两个实现怎么消歧"与"实现返回 null 怎么归一"都不在本包</b>：消费点在 provider，
 * 而壳递不出包装好的 verifier（auth 这条线上 provider 不依赖壳），所以这两格分别是 Task 49/50 的一条用例。
 * 本包的守卫<b>有意不判"多个"</b>，与 {@code AuthPortGuard} 的这条不对称写在它的类注释里。
 */
package org.deep361.myddd.auth.credential;
