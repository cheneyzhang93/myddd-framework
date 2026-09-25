package org.deep361.myddd.kernel.rule;

import java.math.BigDecimal;

/** 规则夹具：以"会员等级折扣"为例（VIP 8 折、普通 95 折、其余不打折）。 */
final class RuleFixtures {

    private RuleFixtures() {
    }

    enum MemberLevel {
        NORMAL, VIP
    }

    static final String LEVEL = "level";
    static final String AMOUNT = "amount";

    static RuleMatch vipMatch() {
        return RuleMatch.of("discount").with(LEVEL, MemberLevel.VIP).with(AMOUNT, new BigDecimal("100.00"));
    }

    static RuleMatch normalMatch() {
        return RuleMatch.of("discount").with(LEVEL, MemberLevel.NORMAL).with(AMOUNT, new BigDecimal("100.00"));
    }

    /** VIP 8 折。 */
    static Rule<BigDecimal> vipDiscountRule() {
        return new Rule<>() {
            @Override
            public String name() {
                return "vip-discount";
            }

            @Override
            public boolean matches(RuleMatch match) {
                return match.attribute(LEVEL).filter(MemberLevel.VIP::equals).isPresent();
            }

            @Override
            public BigDecimal apply(RuleMatch match) {
                return match.requireAttribute(AMOUNT, BigDecimal.class).multiply(new BigDecimal("0.80"));
            }
        };
    }

    /** 普通会员 95 折（兜底规则：永远匹配）。 */
    static Rule<BigDecimal> normalDiscountRule() {
        return new Rule<>() {
            @Override
            public String name() {
                return "normal-discount";
            }

            @Override
            public boolean matches(RuleMatch match) {
                return true;
            }

            @Override
            public BigDecimal apply(RuleMatch match) {
                return match.requireAttribute(AMOUNT, BigDecimal.class).multiply(new BigDecimal("0.95"));
            }
        };
    }

    /** 一旦被调用即失败的规则：用于断言"未匹配的规则绝不会被 apply"。 */
    static Rule<BigDecimal> explosiveRule(String name) {
        return new Rule<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean matches(RuleMatch match) {
                return false;
            }

            @Override
            public BigDecimal apply(RuleMatch match) {
                throw new AssertionError("未匹配的规则不应被执行：" + name);
            }
        };
    }
}
