package org.deep361.myddd.domain.fixture.component;

import org.springframework.stereotype.Component;

/** 只有 {@code @Component} 没有 {@code @Domain}：默认过滤器必须关闭，否则它会被当成模块候选并导致扫描失败。 */
@Component
public class ComponentOnlyService {
}
