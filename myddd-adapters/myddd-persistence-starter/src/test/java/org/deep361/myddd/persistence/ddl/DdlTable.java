package org.deep361.myddd.persistence.ddl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 测试夹具：把一份 {@code create table} 文本解析成可断言的结构。
 *
 * <p>解析前提（由 {@code DdlReferenceTest.每个列与索引定义独占一行} 钉住）：一个定义项一行、
 * 行注释以 {@code --} 开头、列注释一律以 {@code comment '...'} 收尾。空白先按单空格归一化，
 * 因此列定义里用于对齐的空格不影响解析。遇到夹具不支持的形态直接抛异常，绝不静默跳过。
 */
record DdlTable(String tableName, List<DdlColumn> columns, List<DdlIndex> indexes, List<String> checkConstraints,
                List<String> primaryKeyColumns, String tableOptions) {

    /** 一列：名字、类型串（如 {@code datetime(3)}、{@code varchar(64)}）、是否可空。 */
    record DdlColumn(String name, String type, boolean nullable) {
    }

    /** 一个二级索引或唯一索引。 */
    record DdlIndex(String name, List<String> columns, boolean unique) {
    }

    /** 按列名取列；取不到就把实际列集合写进失败信息，避免"断言在解析不到的列上静默通过"。 */
    DdlColumn column(String name) {
        return columns.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("DDL 里没有列 " + name + "，实际列：" + columns));
    }

    static DdlTable parse(String ddl) {
        String tableName = null;
        String tableOptions = null;
        List<String> primaryKeyColumns = List.of();
        List<DdlColumn> columns = new ArrayList<>();
        List<DdlIndex> indexes = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        boolean insideBody = false;
        for (String raw : ddl.split("\\R")) {
            String line = raw.replaceAll("\\s+", " ").trim();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            if (tableName == null) {
                if (!line.startsWith("create table ")) {
                    continue;
                }
                tableName = line.substring("create table ".length(), line.indexOf('(')).trim();
                insideBody = line.endsWith("(");
                continue;
            }
            if (insideBody && line.startsWith(")")) {
                tableOptions = line.substring(1).replace(";", "").trim();
                insideBody = false;
                continue;
            }
            if (!insideBody) {
                continue;
            }
            String item = line.endsWith(",") ? line.substring(0, line.length() - 1).trim() : line;
            if (item.startsWith("primary key (")) {
                primaryKeyColumns = splitNames(item.substring("primary key (".length(), item.length() - 1));
            } else if (item.startsWith("unique key ") || item.startsWith("key ") || item.startsWith("index ")) {
                indexes.add(parseIndex(item));
            } else if (item.startsWith("constraint ")) {
                int checkAt = item.indexOf(" check (");
                if (checkAt < 0) {
                    throw new IllegalStateException("夹具不支持的约束形态：" + item);
                }
                checks.add(item.substring(checkAt + " check (".length(), item.length() - 1));
            } else {
                columns.add(parseColumn(item));
            }
        }
        return new DdlTable(tableName, List.copyOf(columns), List.copyOf(indexes), List.copyOf(checks),
                primaryKeyColumns, tableOptions);
    }

    private static DdlColumn parseColumn(String item) {
        int commentAt = item.indexOf(" comment '");
        String body = commentAt < 0 ? item : item.substring(0, commentAt).trim();
        String[] tokens = body.split(" ");
        if (tokens.length < 2) {
            throw new IllegalStateException("夹具解析不出列类型：" + item);
        }
        return new DdlColumn(tokens[0], tokens[1], !body.contains(" not null"));
    }

    private static DdlIndex parseIndex(String item) {
        boolean unique = item.startsWith("unique key ");
        String rest = unique ? item.substring("unique key ".length()) : item.substring(item.indexOf(' ') + 1);
        int open = rest.indexOf('(');
        return new DdlIndex(rest.substring(0, open).trim(), splitNames(rest.substring(open + 1, rest.length() - 1)),
                unique);
    }

    private static List<String> splitNames(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",")).map(String::trim).toList();
    }
}
