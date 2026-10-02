package org.deep361.myddd.testing.reset;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表名集合处理：排除项归一化 + 白名单校验。
 *
 * <p>表名来自 {@code information_schema}，但 DDL 无法用占位符绑定，因此一律按不可信输入处理：
 * 只允许 MySQL 裸标识符的安全子集，其余直接拒绝——宁可让集成测试红，也不拼一条没审过的 DDL。
 */
record TableNameList(Set<String> excluded) {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_$]+$");

    static TableNameList of(Collection<String> excluded) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : excluded) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new TableNameList(Set.copyOf(normalized));
    }

    /** 是否跳过：Flyway 版本表永不碰，其次查排除项（都按小写比较）。 */
    boolean shouldSkip(String table) {
        String key = table.toLowerCase(Locale.ROOT);
        return DataResetExtension.FLYWAY_HISTORY_TABLE.equals(key) || excluded.contains(key);
    }

    /** 白名单校验：表名来自 information_schema，但 DDL 无法绑定占位符，因此按不可信输入处理。 */
    static void requireSafeIdentifier(String table) {
        if (!SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("非法表名 " + table + "：只允许字母、数字、下划线与 $，拒绝拼入 DDL");
        }
    }

    /** 反引号包裹的表名；白名单外抛 {@link IllegalArgumentException}。 */
    static String quoted(String table) {
        requireSafeIdentifier(table);
        return "`" + table + "`";
    }
}
