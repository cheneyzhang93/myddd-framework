package org.deep361.myddd.resiliencefixture;

import org.deep361.myddd.kernel.port.resilience.RateLimit;

/** 注解只在接口方法/接口类型上——探针 U3/U4 实测那种形态切点门不开，{@code RL15} 钉它。 */
public interface IfaceDeclaredService {

    @RateLimit(key = "iface-only")
    String declaredInIface(String argument);

    @RateLimit(key = "iface-method-and-class")
    String anotherInIface(String argument);

    /** 接口与实现各写一份：`RL15` 用它证明"开门的是实现那一份"。 */
    @RateLimit(key = "iface-wins-if-merged")
    String bothSides(String argument);
}
