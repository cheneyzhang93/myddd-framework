package org.deep361.myddd.resiliencefixture;

import org.deep361.myddd.kernel.port.resilience.RateLimit;

/** 注解只写在类上：内核 javadoc 的"作用于全部方法"。 */
@RateLimit(timeout = 8)
public class ClassLevelGuardedService {

    public String first() {
        return "first";
    }

    public String second(String argument) {
        return "second";
    }

    /** 方法级注解在场时类型级<b>整份让位</b>（不做字段合并）：这条与 `RL16` 的第二半同读。 */
    @RateLimit(permits = 3)
    public String methodWins() {
        return "done";
    }
}
