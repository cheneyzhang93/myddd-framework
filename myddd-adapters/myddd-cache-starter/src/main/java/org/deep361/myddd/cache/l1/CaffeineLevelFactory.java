package org.deep361.myddd.cache.l1;

import java.util.List;

import org.deep361.myddd.cache.autoconfigure.MydddCacheProperties;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * L1（进程内 Caffeine）那一层的构造：把 {@code myddd.cache.caffeine.*} 与白名单落成一台
 * {@link CaffeineCacheManager}。
 *
 * <p><b>这一层不需要初始化回调</b>：实测（javap 核对 spring-context-support 7.0.9）
 * {@code CaffeineCacheManager} 现在是直接 {@code implements CacheManager}，不再继承
 * {@code AbstractCacheManager}、也不再实现 {@code InitializingBean}，白名单由
 * {@code setCacheNames} 自己消化。所以这里没有"手工 new 出来就没人调 afterPropertiesSet"这一问题——
 * L2 那一层<em>有</em>（{@code RedisCacheManager} 仍继承 {@code AbstractCacheManager}），
 * 两层的差别见 {@link org.deep361.myddd.cache.l2.RedisLevelFactory}。
 */
public final class CaffeineLevelFactory {

    private CaffeineLevelFactory() {
    }

    /**
     * @param properties 已校验过的缓存配置（{@code validate()} 已排除过非法值，这里不再判空）
     */
    public static CaffeineCacheManager create(MydddCacheProperties properties) {
        MydddCacheProperties.Caffeine caffeine = properties.getCaffeine();
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(caffeine.getMaxSize())
                .expireAfterWrite(caffeine.getExpireAfterWrite()));
        manager.setAllowNullValues(properties.isCacheNullValues());
        List<String> names = properties.getCacheNames();
        if (!names.isEmpty()) {
            manager.setCacheNames(names);
        }
        return manager;
    }
}
