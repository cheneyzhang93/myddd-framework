package org.deep361.myddd.kernel.extension;

import java.util.Objects;

/**
 * 扩展注册元数据：供诊断、健康检查与文档生成读取，不参与运行时匹配。
 *
 * @param point          扩展点接口
 * @param pointName      扩展点名称：{@code @ExtensionPoint} 的值，留空时为接口简单类名
 * @param scenario       场景标识，空字符串表示默认实现
 * @param priority       优先级，数值越小越优先
 * @param implementation 扩展实现类
 */
public record ExtensionMeta(Class<?> point, String pointName, String scenario, int priority,
                            Class<?> implementation) {

    public ExtensionMeta {
        Objects.requireNonNull(point, "point 不能为 null");
        Objects.requireNonNull(pointName, "pointName 不能为 null");
        Objects.requireNonNull(scenario, "scenario 不能为 null");
        Objects.requireNonNull(implementation, "implementation 不能为 null");
    }
}
