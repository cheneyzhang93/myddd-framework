package org.deep361.myddd.kernel.common.util;

import java.util.Objects;

/**
 * SLF4J 风格占位符（{@code {}}）消息格式化工具。
 *
 * <p>占位符少于参数时多余参数被忽略；占位符多于参数时未匹配的占位符原样保留。
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
