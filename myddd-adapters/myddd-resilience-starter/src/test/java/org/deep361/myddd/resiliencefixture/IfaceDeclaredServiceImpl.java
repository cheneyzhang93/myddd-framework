package org.deep361.myddd.resiliencefixture;

import org.deep361.myddd.kernel.port.resilience.RateLimit;

/** 接口实现：本类<b>不</b>写 {@link RateLimit}，注解只在 {@link IfaceDeclaredService} 上。 */
public class IfaceDeclaredServiceImpl implements IfaceDeclaredService {

    @Override
    public String declaredInIface(String argument) {
        return argument;
    }

    @Override
    public String anotherInIface(String argument) {
        return argument;
    }

    /**
     * 注解写在实现方法上（即便接口也有一个同名注解）：这一条必须命中，用于对照前两条。
     *
     * <p>{@code key} 整串按 SpEL 解析（{@code ResourceKeyResolver} 的既定形状，与锁切面同一条），所以
     * 带连字符的字面量必须写成 SpEL 字符串字面量；不写引号就是 {@code impl - side} 两个属性相减。
     */
    @RateLimit(key = "'impl-side'")
    @Override
    public String bothSides(String argument) {
        return argument;
    }
}
