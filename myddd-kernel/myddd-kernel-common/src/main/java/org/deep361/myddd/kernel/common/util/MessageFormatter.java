package org.deep361.myddd.kernel.common.util;

import java.util.Objects;

/**
 * SLF4J 风格占位符（{@code {}}）消息格式化工具。
 *
 * <p>本工具用于渲染客户可见的错误文案，全部用户可见规则如下：
 * <ul>
 *     <li>{@code null} 模板返回 {@code null}；</li>
 *     <li>无参数（或参数数组为 {@code null}）时原样返回模板；</li>
 *     <li>{@code {}} 占位符按参数出现顺序从左到右替换，多余参数被忽略；</li>
 *     <li>参数不足时未匹配的占位符原样保留（与 SLF4J 行为一致）；</li>
 *     <li>参数为 {@code null} 时渲染为字面量 {@code null}；</li>
 *     <li><b>不支持</b> {@code \{\}} 转义（与 SLF4J 的差异点，需输出字面量花括号时请自行处理）。</li>
 * </ul>
 */
public final class MessageFormatter {

    private MessageFormatter() {
    }

    public static String format(String template, Object... args) {
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
