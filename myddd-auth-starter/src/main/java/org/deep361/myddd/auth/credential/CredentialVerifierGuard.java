package org.deep361.myddd.auth.credential;

import java.util.Optional;

import org.deep361.myddd.auth.config.MydddAuthProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * "有 {@link AuthPort} 却没人校验凭据"的启动期守卫（R14 的落点，键 {@code
 * myddd.auth.credential.verifier-required}，缺省 {@code true}）。
 *
 * <p><b>为什么这件事要在启动期做</b>：Sa-Token 的 {@code StpUtil.login(loginId)} 不校验凭据
 * （{@code CredentialVerifier} 的 javadoc 里有那张重载表），所以业务忘了提供 verifier 时，
 * satoken 侧的 {@code login} 不会报错——它会<b>发一个真的、可用的 token</b>。运行期第一条登录
 * 成功返回 200，没人会去查它背后有没有校验；等到出事已经是一次线上事故。因此判据不能是
 * "调用时拿不到就抛"，只能是启动期。
 *
 * <p><b>为什么钩子是 {@link SmartInitializingSingleton} 而不是 {@code @PostConstruct}</b>：
 * 本类要回答的是"容器里有没有人提供 verifier"，而业务自己的 {@code @Configuration} 完全可能在
 * 自动配置之后才被实例化；只有在所有单例都实例化完成后，"没有"才等于"确实没人提供"。
 * 与 {@code PermissionDeclarationValidator} 用同一个钩子，也同一条理由。
 *
 * <p><b>判据用 {@code stream()} 而不是 {@code getIfAvailable()}</b>：一手实测（探针
 * {@code 48c}，{@code D:\tmp\p4probe\auth\p48cout.txt}）表明确实有两个候选且无 {@code @Primary} 时
 * {@code getIfAvailable()} <b>抛</b> {@code NoUniqueBeanDefinitionException}。本类只关心"在场与否"，
 * 所以取 {@code stream().findFirst()}——它在四种容器形态下都不抛。
 *
 * <p><b>本类不判"多个"</b>，这与 {@code AuthPortGuard} 是<b>有意的不对称</b>：那里必须判多候选，
 * 因为壳里的切面在每次调用时自己要从容器取一个端口，{@code @Primary} 之外没人消歧就得报错；
 * 这里壳一次都不消费 verifier（消费它的是 provider），多个实现时失败点在 Spring 注入 provider
 * 那个 bean 方法的瞬间，仍然是启动期、仍然会阻止应用带病上线，只是消息是 Spring 的话术。
 * 把 {@code AuthPortGuard} 那套三形态分类再抄一份进本类，只会让两个守卫从此各说各话。
 *
 * <p><b>没有 {@code require()} 方法</b>：壳里没有消费者，加一个"给 provider 用"的 public 方法
 * 等于给壳造一个依赖它的外部调用方，而 auth 这条线上 provider 不依赖壳（判据见 Step 6 开头那张表）。
 * 本类的全部对外面就是那一个钩子。
 *
 * <p><b>关掉之后是什么形态</b>：{@code verifier-required=false} 是路线图键表
 * （{@code 121-13.md:34}）为"只做鉴权不做登录"的部署留的唯一出路。关掉之后仍然<b>没人校验凭据</b>，
 * 只是框架不再拦你——所以那条出路只适用于"本部署根本不会调 {@code AuthPort.login}"这一事实成立时。
 * 写 {@code myddd.auth.enabled=false} 不是这一格的替身：那关掉的是整个能力（切面、声明校验、
 * 本守卫全都不装），消息里会明确把它指开。
 */
public final class CredentialVerifierGuard implements SmartInitializingSingleton {

    /** 为 R14 这条接缝登记的键；缺省 {@code true}。 */
    static final String VERIFIER_REQUIRED_KEY = "myddd.auth.credential.verifier-required";

    private final ObjectProvider<CredentialVerifier> verifiers;

    private final ObjectProvider<AuthPort> ports;

    private final MydddAuthProperties properties;

    /**
     * @param verifiers  verifier 的在场性接缝；收 {@link ObjectProvider} 而不是实例本身，
     *                   否则"业务没提供"会变成一次注入失败而不是本类那条可读诊断
     * @param ports      端口在场性接缝（零个端口时本守卫闭嘴，那条诊断归 {@code AuthPortGuard}，
     *                   否则同一次启动会出现两条互相抢话的消息）
     * @param properties 提供 {@code verifier-required} 的当前值
     *
     * <p><b>刻意不做 null 检查</b>：三个参数只由 {@code MydddAuthAutoConfiguration} 那一个调用点注入，
     * 拿到 null 时 NPE 出现在装配自己的方法里、出现在启动期，比框架自己编的一句
     * {@link FrameworkException} 更早也更准。唯一保留 null 分支的是 {@code AuthPortGuard}，
     * 因为那里的构造器真的被用例直接 {@code new} 过。
     */
    public CredentialVerifierGuard(ObjectProvider<CredentialVerifier> verifiers, ObjectProvider<AuthPort> ports,
            MydddAuthProperties properties) {
        this.verifiers = verifiers;
        this.ports = ports;
        this.properties = properties;
    }

    /**
     * 三段判断，顺序即判据：先问属性、再问端口、最后才问 verifier。
     *
     * <p>顺序不许换：{@code verifier-required=false} 的部署是"根本不用登录"那一类，
     * 框架在该关掉时连容器都不该去问一次（{@code CV} 组用访问计数器钉这一格）。
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!this.properties.getCredential().isVerifierRequired()) {
            return;
        }
        if (!isPresent(this.ports)) {
            return;
        }
        if (!isPresent(this.verifiers)) {
            throw missingVerifier();
        }
    }

    private static boolean isPresent(ObjectProvider<?> provider) {
        Optional<?> first = provider.stream().findFirst();
        return first.isPresent();
    }

    private static FrameworkException missingVerifier() {
        return new FrameworkException("鉴权能力已装配（容器里有 AuthPort），但一个 CredentialVerifier bean 都没有。"
                + "AuthPort.login(AuthCredentials) 的语义是“先校验凭据、再发会话”，而框架不掌握凭据的真相源："
                + "Sa-Token 侧的 StpUtil.login(...) 五个重载的第一个参数永远是 loginId，收了就发 token、"
                + "一个凭据都不校验（R14），Spring Security 侧的 AuthenticationManager 也无从知道你的用户表在哪。"
                + "所以这个 bean 只能由业务提供，框架代产等于代答“密码对不对”。"
                + "两条出路：① 提供一个 CredentialVerifier bean——校验不通过抛 "
                + "BizException(CommonErrorCode.UNAUTHORIZED)，不得返回 null；"
                + "② 本次部署确实不使用登录（只做鉴权），把 " + VERIFIER_REQUIRED_KEY
                + "=false 写进配置——关掉之后仍然没人校验凭据，代价请见本类 javadoc。"
                + "注意 myddd.auth.enabled=false 不是这一格的替身：那关掉的是整个鉴权能力，"
                + "@RequiresPermission 一次都不会被拦");
    }
}
