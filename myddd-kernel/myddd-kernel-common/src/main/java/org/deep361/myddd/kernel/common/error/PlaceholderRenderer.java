package org.deep361.myddd.kernel.common.error;

import java.util.Objects;

/**
 * 占位符渲染原语：把 {@code {}} 占位符按参数出现顺序从左到右替换（细则见
 * {@link org.deep361.myddd.kernel.common.util.MessageFormatter}）。
 *
 * <p>原语与 {@link ErrorCode} 同包：{@code common.util} 已依赖 {@code common.error}
 * （{@link org.deep361.myddd.kernel.common.util.MydddAssert} 抛 {@link BizException}），
 * 原语若留在 {@code common.util} 会让两个包互为依赖。公开外观
 * {@link org.deep361.myddd.kernel.common.util.MessageFormatter} 委托到本类，全仓只有一份实现。
 */
public final class PlaceholderRenderer {

    private PlaceholderRenderer() {
    }

    /**
     * 渲染模板：{@code null} 模板返回 {@code null}；无参数（或参数数组为 {@code null}）时原样返回模板；
     * 参数不足时未匹配的占位符原样保留；参数为 {@code null} 时渲染为字面量 {@code null}。
     */
    public static String render(String template, Object... args) {
        if (template == null) {
            return null;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        StringBuilder result = new StringBuilder(template.length() + 32);
        int argIndex = 0;
        int from = 0;
        int placeholderIndex;
        while ((placeholderIndex = template.indexOf("{}", from)) >= 0 && argIndex < args.length) {
            result.append(template, from, placeholderIndex).append(Objects.toString(args[argIndex++], "null"));
            from = placeholderIndex + 2;
        }
        result.append(template, from, template.length());
        return result.toString();
    }
}
