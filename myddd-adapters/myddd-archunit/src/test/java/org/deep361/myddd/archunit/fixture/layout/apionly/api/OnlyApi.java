package org.deep361.myddd.archunit.fixture.layout.apionly.api;

/**
 * 空选择集夹具：根包下只有 {@code api}，因此 rule2（domain/application 选择集）与
 * rule3（application 选择集）在该根上都选不到任何类。
 *
 * <p>用于钉住两条规则同样开启了 {@code allowEmptyShould(true)}——分层可选的工程不该因
 * "没有可检查的类"而构建失败。
 */
public class OnlyApi {

    public String ping() {
        return "pong";
    }
}
