package org.deep361.myddd.web.json;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 领域风格的夹具：无 {@code get}/{@code is} 前缀的访问器 + 一个只读计算属性。
 */
record FluentOrder(String orderId, BigDecimal amount, Instant occurredAt, String currencyCode) {

    /** fluent 计算属性：不是 record 组件，Jackson 默认不认，只有 fluent 命名策略才会把它写出来。 */
    public String display() {
        return orderId + " " + currencyCode;
    }
}
