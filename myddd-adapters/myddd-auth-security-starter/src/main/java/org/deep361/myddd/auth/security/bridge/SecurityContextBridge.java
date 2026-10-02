package org.deep361.myddd.auth.security.bridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * R9 的唯一归一点：Spring Security 侧"这一线程有没有身份""身份里哪些算业务权限"两件事，
 * 全部收在这一个类里，模块其余代码不直接读 {@link SecurityContextHolder}。
 *
 * <p><b>本类没有 try/catch，与 satoken 侧的形状差是结构性的</b>：{@code SecurityContextHolder.getContext()}
 * 从不抛（一手探针读数：一条从未 {@code setContext} 的线程读到的是 {@code SecurityContextImpl} 且
 * {@code authentication=null}），所以"没有身份"是一个 null 值而不是一个异常，没有需要归一的异常。
 * 写一个包不住的 try 只会让人以为这里有一条被处理掉的失败路径。
 *
 * <p><b>三支判据，缺一不可，顺序不可换</b>（{@code RS1}–{@code RS3} 一条钉一支）：
 * <ol>
 *   <li>{@code null} —— holder 干净；</li>
 *   <li>{@code !isAuthenticated()} —— 预认证流程会在 holder 里放一个未认证的 token；</li>
 *   <li>{@code isAnonymous(...)} —— <b>这一支是三支里唯一不能靠自己推理得到的</b>：厂商的
 *       {@code AnonymousAuthenticationToken} 的 {@code isAuthenticated()} 是 {@code true}，
 *       {@code getName()} 是 {@code anonymousUser}。只写前两支的后果不是抛异常而是
 *       <b>给每个未登录请求返回一个叫 {@code anonymousUser} 的用户、带一条 {@code ROLE_ANONYMOUS}
 *       角色</b>——那是一条会一路绿到生产的身份泄漏。</li>
 * </ol>
 * 第三支必须用厂商的 {@link AuthenticationTrustResolver} 而不是自己写 {@code instanceof
 * AnonymousAuthenticationToken}：那个类可以被部署方换掉（{@code AuthenticationTrustResolverImpl}
 * 有 {@code setAnonymousClass}），而本模块判"是不是匿名"的口径必须与厂商过滤器链自己的口径一致，
 * 否则同一个 token 会在"厂商认为匿名、我们认为有身份"之间裂开。也不用
 * {@code isFullyAuthenticated()}：它把 remember-me 判成 false，而记住我的身份<b>是</b>已登录身份。
 *
 * <p><b>static 解析器是私有的、不可配置的</b>：本类不复用容器里的 {@code AuthenticationTrustResolver}
 * （本模块不产这个 bean，见 {@code config} 包说明），也不暴露改 {@code anonymousClass} 的口子——
 * 允许部署方重定义"匿名"，等于允许他把上面第三支判据关掉。
 *
 * <p><b>不做第二层校验，一条都不做</b>（{@code RS6} 钉这两格）：不校验 {@code getName()} 的空白、
 * 不过滤空白权限串、不过滤切分后可能出现的空角色名。全部理由是一条一手分工事实：内核
 * {@code AuthUser} 的紧凑构造器已经把 {@code userId}/{@code username} 的空白、以及 {@code roles}/
 * {@code permissions} <b>元素</b>的 null 与空白挡下了，厂商的 {@code SimpleGrantedAuthority} 在
 * <b>构造时</b>就拒绝空白串（{@code IllegalArgumentException: A granted authority textual
 * representation is required}）。在这里再挡一遍就是第三处真源。
 */
public final class SecurityContextBridge {

    /**
     * 角色前缀。厂商默认值，两处一手读数：{@code User.withUsername(...).roles("admin")} 给的是
     * {@code ROLE_admin}，以及同一份夹具上厂商 {@code hasRole("admin")=true} 而
     * {@code hasAuthority("admin")=false}。
     *
     * <p>它<b>不是不可动的</b>：{@code User.withUsername(...).withRolePrefix("")} 之后
     * {@code roles("admin")} 给的是字面量 {@code admin}。也就是说"角色"与"权限"在厂商数据面上本来
     * 就没有区分，是<b>本框架用这一串前缀把它们分开</b>的。这一格写死、不做成配置键
     * （{@code config} 包说明里"只有一个布尔"就是这个决定的落点），并要求业务的授权数据一律带前缀——
     * 不带前缀的角色只出现在 {@code permissions} 里，{@code AuthUser.roles()} 读不到它，
     * 这是一条要人来发现的配置错误，不该由框架替他猜。
     */
    private static final String ROLE_PREFIX = "ROLE_";

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private SecurityContextBridge() {
    }

    /**
     * 本线程当前的身份，三支判据见类注释。无身份 ⇒ {@link Optional#empty()}，永不抛。
     */
    public static Optional<Authentication> currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || TRUST_RESOLVER.isAnonymous(authentication)) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }

    /**
     * token 上的<b>业务</b>权限串，原样顺序、原样字符串，唯一被剔掉的是厂商自己追加的
     * {@link FactorGrantedAuthority}。
     *
     * <p>为什么必须剔：一次真实认证成功之后，token 的权限集是<b>主体权限集加上一条
     * {@code FACTOR_PASSWORD}</b>（同一个 {@code User} 主体自己身上只有前两条——那条是认证链事后加的，
     * 不是业务给的）。不剔的后果是 {@code AuthUser.permissions} 里凭空多出一条谁也没申请过的权限，
     * {@code hasPermission("FACTOR_PASSWORD")} 返回 true，并且它会被审计切面写进"这个人有哪些权限"。
     *
     * <p>为什么按<b>类型</b>剔而不是按字符串剔：{@code FactorGrantedAuthority} 的权限串有八种
     * （{@code FACTOR_PASSWORD}、{@code FACTOR_BEARER}、{@code FACTOR_X509}……），按字符串过滤要维护
     * 那张表并且会漏掉新增的一种。该类<b>没有公开构造器</b>（唯一的 {@code (String, Instant)} 构造器是
     * private），但<b>有三个公开静态工厂</b>（{@code fromFactor}、{@code fromAuthority}、
     * {@code withFactor/withAuthority} 的 builder），实测 {@code fromFactor("PASSWORD").getAuthority()}
     * 就是 {@code FACTOR_PASSWORD} ⇒ 测试夹具造它不需要打一次真认证，
     * {@code RS4} 的钉子因此只依赖被滤的<em>类型</em>。
     */
    public static List<String> authorityNames(Authentication authentication) {
        List<String> names = new ArrayList<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (!(authority instanceof FactorGrantedAuthority)) {
                names.add(authority.getAuthority());
            }
        }
        return names;
    }

    /**
     * 身份映射：{@code userId} 与 {@code username} <b>同源于</b> {@link Authentication#getName()}，
     * 权限集按 {@value #ROLE_PREFIX} 前缀<b>额外</b>给出一份去壳的角色视图。
     *
     * <p><b>{@code permissions} 收全部权限串，含带前缀那一条；{@code roles} 是叠加视图不是互斥分区</b>。
     * 一手依据是厂商在同一份数据上 {@code hasAuthority("ROLE_admin")=true} 而
     * {@code hasAuthority("admin")=false}——{@code ROLE_admin} 本身就是一个真实存在、可被精确命中的
     * 权限串。把它从 {@code permissions} 里拿走（互斥切分）等于端口在厂商语义之外<b>少</b>认一条权限，
     * 而 {@code AuthUser.hasPermission} 的 javadoc 写的是精确匹配，端口不该比厂商更少。
     *
     * <p>用 {@link LinkedHashSet} 累积只为诊断文本顺序稳定；内核紧接着再做一次 {@code Set.copyOf}
     * （那一份是不可变集合，<b>不承诺迭代顺序</b>），所以本类的用例断言也不许依赖顺序。
     */
    public static AuthUser toAuthUser(Authentication authentication) {
        List<String> names = authorityNames(authentication);
        Set<String> roles = new LinkedHashSet<>();
        for (String name : names) {
            if (name.startsWith(ROLE_PREFIX)) {
                roles.add(name.substring(ROLE_PREFIX.length()));
            }
        }
        String name = authentication.getName();
        return new AuthUser(name, name, roles, new LinkedHashSet<>(names));
    }

    /**
     * 把一次<b>认证成功换来的</b> token 装进本线程 holder——模块内唯一的 holder <b>写</b>入口
     * （{@code verify/AuthenticationManagerLogin} 是它唯一的调用方）。
     *
     * <p>为什么是 {@code createEmptyContext() + setContext(...)} 而不是 {@code getContext()
     * .setAuthentication(...)}：后者能用的前提是"holder 里那份 context 是同一枚可变对象"，那是
     * {@code SecurityContextHolder} 的实现细节而不是它的契约。本模块的 {@link #logout()} 走的是
     * 整份替换语义的 {@code clearContext()}，两种写法混在同一类里会让"到底哪一份对象是权威"读不出来。
     */
    public static void install(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /**
     * 清掉<b>本线程</b>的 holder。
     *
     * <p>语义边界写死在这里：本方法只做到这一步。会话失效、删 {@code JSESSIONID}、发
     * {@code LogoutSuccessEvent} 都归厂商的 {@code LogoutHandler} 链（那需要
     * {@code HttpServletRequest}，而本模块零 Servlet）。这是两家一处<b>真实的行为不对称</b>：
     * satoken 侧的 {@code logout()} 撤销的是进程级会话存储里的会话，这边撤销的是一条线程本地引用；
     * 两家在"logout 之后再读 {@code currentUser()}"上给同一个答案（都是空），在"手里那个凭据还能不能用"
     * 上给不同答案。这一格的判据在跨家等价性轮（两家同时在场），不在本模块自证。
     */
    public static void logout() {
        SecurityContextHolder.clearContext();
    }
}
