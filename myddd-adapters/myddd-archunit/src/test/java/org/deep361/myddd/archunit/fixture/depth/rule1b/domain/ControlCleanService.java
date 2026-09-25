package org.deep361.myddd.archunit.fixture.depth.rule1b.domain;

/**
 * 深度夹具的对照组：位于 {@code <base>..domain..} 的浅层，任何形态的包模式都选得中。
 *
 * <p>与 {@code depth.rule1b.inner.domain} 的深层反例配对，用于判别
 * "base 与 layer 之间插入中间包"（{@code <base>..X..<layer>..}）是否仍被匹配。
 */
public class ControlCleanService {

    public String describe() {
        return "浅层干净领域服务";
    }
}
