package org.deep361.myddd.auth.satoken.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpInterfaceDefaultImpl;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.List;

/**
 * R15 的启动自检：容器里必须<b>恰好有一个</b> {@link StpInterface}，而且 {@link SaManager} 用的<b>就是它</b>。
 *
 * <p><b>本模块一个 {@code StpInterface} 都不产</b>（{@code 49a} Step 0 第 1 条，登记 D-182）。三条一手事实
 * 只指向这一个自洽落点：① {@code SaBeanRegister} 的 public 面只有 {@code getSaTokenConfig()} 与
 * {@code getApplicationContextPathLoading()} 两个 {@code @Bean}——Sa-Token 自己从不产权限源；
 * ② {@code SaBeanInject.setStpInterface(StpInterface)} 是<b>单参</b> {@code @Autowired} setter，
 * {@code @Autowired} 只容忍零候选、不容忍多候选 ⇒ 我们再产一个就与业务那一个撞成
 * {@code NoUniqueBeanDefinitionException}，整份装配在启动期就废掉；③ 没人接权限源时
 * {@code SaManager.getStpInterface()} 双检锁懒装 {@link StpInterfaceDefaultImpl}，它两个方法都返回空列表
 * （探针 49a 运行时读数：{@code permList=[]}、{@code roleList=[]}）——"没人管权限"在 Sa-Token 侧的形态是
 * <b>静默全 deny</b>，不是失败。所以权限数据源归业务，我们只负责"没接上就把话说清"。
 *
 * <p><b>第二档（同一性）存在的全部理由</b>：把 bean 递给 {@code SaManager} 的那句
 * {@code invokestatic SaManager.setStpInterface} 只存在于 {@code sa-token-spring-boot4-starter} 的
 * {@code SaBeanInject} 里（§0.1 那行一手清单），而本模块的 POM <b>不引</b>它（{@code 49b} 的依赖面事实）。
 * 于是有一种很容易踩到的形态：业务声明了 {@code @Bean StpInterface}、classpath 上没有 starter ⇒
 * 容器里 bean 在、{@code SaManager} 里却还是缺省实现 ⇒ 全部权限判定为 false、启动期一句废话都没有。
 * 判"同一性"而不是判"等值"，是因为这里要比的是"厂商实际调用的那个对象是不是容器里那一个"。
 *
 * <p><b>没有 ServiceLoader 兜底</b>（一手字节码：{@code samgr.txt:169-191}——{@code getStpInterface()}
 * 只有"读静态字段 → 双检锁 → {@code new StpInterfaceDefaultImpl()}"这一条路径，写入口只有
 * {@code setStpInterface}，登记 D-188）。这条事实的另一半是：本模块的测试夹具必须自己调那个 setter
 * （见 {@code SaTokenRequestScope}），因为测试类路径上同样没有 starter。
 *
 * <p><b>数候选用 {@code stream()} 而不是 {@code getIfAvailable()}</b>：后者在多候选且无 {@code @Primary} 时
 * 抛（一手：{@code spring-beans-7.0.9} 的 {@code ObjectProvider.java:85-95}＋{@code :120-130}），
 * 而"两个权限源"必须变成我们那句话而不是 Spring 的那一句（同 Task 48 的 D-174，两个守卫共用一条结论）。
 */
public class StpInterfacePresenceGuard implements SmartInitializingSingleton {

    private final ObjectProvider<StpInterface> sources;

    public StpInterfacePresenceGuard(ObjectProvider<StpInterface> sources) {
        this.sources = sources;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<StpInterface> declared = sources.stream().toList();
        if (declared.isEmpty()) {
            throw new FrameworkException("容器里没有任何 " + StpInterface.class.getName()
                    + "：Sa-Token 会懒装 " + StpInterfaceDefaultImpl.class.getName()
                    + "，它对每个用户都返回空的权限与角色列表，于是所有鉴权判定静默为 false、启动期零提示。"
                    + "出路：声明一个 @Bean StpInterface（权限数据从你的库或配置里取），"
                    + "并把 sa-token-spring-boot4-starter 放进依赖——只有它会把这个 bean 递给 SaManager。");
        }
        if (declared.size() > 1) {
            throw new FrameworkException("容器里有 " + declared.size() + " 个 " + StpInterface.class.getName()
                    + "（" + declared.stream().map(bean -> bean.getClass().getName()).toList()
                    + "）：权限数据源只能有一个真源。两个 bean 本身还会让 SaBeanInject 的单参 setter 注入失败，"
                    + "于是这里拒绝启动而不是猜一个——猜中的那份决定了每个用户能看见什么。");
        }
        StpInterface single = declared.get(0);
        if (SaManager.getStpInterface() != single) {
            throw new FrameworkException("容器里声明了 " + single.getClass().getName()
                    + "，但 SaManager 正在用的是 " + SaManager.getStpInterface().getClass().getName()
                    + "：把 bean 递给 SaManager 的那一步在 sa-token-spring-boot4-starter 的 SaBeanInject 里，"
                    + "本模块刻意不引它（库模块不该把 Web 过滤器链拉进依赖树）。"
                    + "现在的形态是 bean 在场、厂商却不认它——权限判定会静默全部为 false。"
                    + "出路：把 sa-token-spring-boot4-starter 加进应用依赖。");
        }
    }
}
