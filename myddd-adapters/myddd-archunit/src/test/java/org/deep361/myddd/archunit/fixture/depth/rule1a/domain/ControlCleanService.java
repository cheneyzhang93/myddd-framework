package org.deep361.myddd.archunit.fixture.depth.rule1a.domain;

/**
 * 深度夹具的对照组：位于 {@code <base>..domain..} 的浅层，任何形态的包模式都选得中。
 *
 * <p>它的存在保证选择集非空，因此深层反例漏选时规则会"由红转绿"（可判别），
 * 而不是退化成"空选择集被 allowEmptyShould(true) 掩盖"。
 */
public class ControlCleanService {

    public String describe() {
        return "浅层干净领域服务";
    }
}
