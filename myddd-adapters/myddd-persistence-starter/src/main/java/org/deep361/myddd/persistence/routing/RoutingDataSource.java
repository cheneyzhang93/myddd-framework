package org.deep361.myddd.persistence.routing;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按线程绑定的键把取连接的动作派发到命名数据源。
 *
 * <p><b>与父类的三处不同</b>（都是刻意的，见 Task 17 设计要点 6 与 8）：
 * <ol>
 *   <li>{@link #determineCurrentLookupKey()} 把"未绑定"显式映射成默认键，而不是依赖父类的回落；</li>
 *   <li>{@code setLenientFallback(false)} 关掉父类"找不到就用默认"的静默行为；</li>
 *   <li>{@link #determineTargetDataSource()} 覆写查找，把父类的
 *       {@code IllegalStateException: Cannot determine target DataSource for lookup key [x]}
 *       换成指名道姓的 {@link FrameworkException}（被拒的键 + 全部已知键 + 该改哪个配置键）。</li>
 * </ol>
 *
 * <p><b>生命周期</b>：目标数据源由框架创建，因此也由框架关闭（{@link #destroy()}）。
 *
 * <p><b>{@code getConnection(username, password)}</b>：沿用父类语义——忽略传入的凭据，返回当前键对应数据源
 * 的连接。带凭据取连接在本脚手架里没有合法用途（凭据来自配置）。
 */
public class RoutingDataSource extends AbstractRoutingDataSource implements DisposableBean {

    private final Map<String, DataSource> targets;
    private final String defaultKey;

    /**
     * @param targets    键到数据源的映射，不可为空，且必须包含 {@code defaultKey}
     * @param defaultKey 未绑定键时使用的键
     * @throws FrameworkException {@code targets} 为空，或 {@code defaultKey} 不在其中
     */
    public RoutingDataSource(Map<String, DataSource> targets, String defaultKey) {
        super();
        if (targets.isEmpty()) {
            throw new FrameworkException("路由数据源至少要有一个目标：myddd.persistence.datasources.<name>.url 不能全空");
        }
        if (!targets.containsKey(defaultKey)) {
            throw new FrameworkException("默认数据源键 [" + defaultKey + "] 不在已定义的数据源里，已定义的键："
                    + String.join(", ", targets.keySet()) + "（对应配置键 myddd.persistence.default-datasource）");
        }
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        this.defaultKey = defaultKey;
        setTargetDataSources(new LinkedHashMap<>(targets));
        setDefaultTargetDataSource(targets.get(defaultKey));
        setLenientFallback(false);
    }
    /** 默认键（{@code myddd.persistence.default-datasource}）。 */
    public String getDefaultKey() {
        return defaultKey;
    }

    /** 已定义的键，顺序与配置声明顺序一致。 */
    public Set<String> getKeys() {
        return targets.keySet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String key = DataSourceKeyHolder.current();
        return key == null ? defaultKey : key;
    }

    @Override
    protected DataSource determineTargetDataSource() {
        Object key = determineCurrentLookupKey();
        DataSource target = getResolvedDataSources().get(key);
        if (target == null) {
            throw new FrameworkException("数据源键 [" + key + "] 没有在 myddd.persistence.datasources 里定义，"
                    + "已定义的键：" + String.join(", ", targets.keySet())
                    + "；请改正 @UseDataSource 的取值或补齐配置（键名区分大小写）");
        }
        return target;
    }

    @Override
    public void destroy() {
        Map<String, Exception> failures = new LinkedHashMap<>();
        targets.forEach((name, target) -> {
            if (!(target instanceof AutoCloseable closeable)) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception e) {
                failures.put(name, e);
            }
        });
        if (failures.isEmpty()) {
            return;
        }
        String detail = failures.entrySet().stream()
                .map(e -> e.getKey() + " -> " + e.getValue().getClass().getName() + ": " + e.getValue().getMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        throw new FrameworkException("关闭数据源连接池失败：" + detail + "；这些池由框架按 myddd.persistence.datasources"
                + " 建出，关不掉会拖住优雅停机。请检查该键对应的池上是否有未归还的连接（长事务、泄漏的 Statement），"
                + "必要时用 myddd.persistence.default-datasource 之外的键先把流量切走");
    }
}
