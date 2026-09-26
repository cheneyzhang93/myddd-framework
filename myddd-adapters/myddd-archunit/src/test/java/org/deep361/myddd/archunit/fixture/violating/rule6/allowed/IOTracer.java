package org.deep361.myddd.archunit.fixture.violating.rule6.allowed;

/**
 * 以 IO 开头的接口不是 I 前缀匈牙利命名，规则 6a 必须放行。
 */
public interface IOTracer {

    String trace();
}
