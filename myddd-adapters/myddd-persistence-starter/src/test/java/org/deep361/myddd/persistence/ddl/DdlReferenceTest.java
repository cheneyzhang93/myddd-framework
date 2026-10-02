package org.deep361.myddd.persistence.ddl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DdlReferenceTest {

    private static final String FLYWAY_DEFAULT_LOCATION = "db/migration";

    @Test
    void should_expose_outbox_columns_in_the_same_order_as_the_record_components() {
        DdlTable table = outboxTable();

        assertThat(table.tableName()).isEqualTo(DdlReference.OUTBOX_TABLE);
        assertThat(table.columns()).extracting(DdlTable.DdlColumn::name)
                .containsExactlyElementsOf(DdlReference.outboxColumns());
    }

    @Test
    void should_derive_column_names_from_mybatis_plus_underscore_naming() {
        List<String> derived = Arrays.stream(OutboxRecord.class.getRecordComponents())
                .map(component -> StringUtils.camelToUnderline(component.getName()))
                .toList();

        // 前 13 项必须逐个等于 record 组件经 MP 命名法派生的列名（顺序即声明顺序）；第 14 项是
        // 存储侧独有的租约列，由下面两条断言单独钉住——它不在 record 上，见设计要点 2。
        assertThat(DdlReference.outboxColumns()).startsWith(derived.toArray(String[]::new));
        assertThat(DdlReference.outboxColumns()).hasSize(14);
        assertThat(DdlReference.outboxColumns()).last().isEqualTo(DdlReference.LEASE_COLUMN);
    }

    @Test
    void should_keep_the_lease_column_out_of_the_record_write_list() {
        // 认领的 update 会把租约清空、写侧的 insert/select 只走 record 的 13 列；这两条列表的关系就是
        // JdbcOutboxStore 的三条 SQL 不碰租约的唯一凭据，所以钉在这里而不是钉在 store 的测试里。
        assertThat(DdlReference.outboxRecordColumns()).hasSize(13)
                .doesNotContain(DdlReference.LEASE_COLUMN);
        assertThat(Stream.concat(DdlReference.outboxRecordColumns().stream(), Stream.of(DdlReference.LEASE_COLUMN))
                .toList()).containsExactlyElementsOf(DdlReference.outboxColumns());
    }

    @Test
    void should_use_datetime_3_for_every_instant_column() {
        DdlTable table = outboxTable();
        List<String> instantColumns = Arrays.stream(OutboxRecord.class.getRecordComponents())
                .filter(component -> component.getType() == Instant.class)
                .map(component -> StringUtils.camelToUnderline(component.getName()))
                .toList();

        assertThat(instantColumns).containsExactly("occurred_at", "next_retry_at", "created_at");
        Stream.concat(instantColumns.stream(), Stream.of(DdlReference.LEASE_COLUMN))
                .forEach(column -> assertThat(table.column(column).type())
                        .as("列 %s 承载 Instant，精度必须到毫秒", column)
                        .isEqualTo("datetime(3)"));
    }

    @Test
    void should_match_record_null_invariants_for_every_nullable_column() throws Exception {
        RecordComponent[] components = OutboxRecord.class.getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Constructor<OutboxRecord> canonicalConstructor = OutboxRecord.class.getConstructor(types);
        Set<String> nullableFromRecord = new LinkedHashSet<>();

        for (int index = 0; index < components.length; index++) {
            if (components[index].getType().isPrimitive()) {
                continue;
            }
            Object[] arguments = validRecordArguments();
            arguments[index] = null;
            try {
                canonicalConstructor.newInstance(arguments);
                nullableFromRecord.add(StringUtils.camelToUnderline(components[index].getName()));
            } catch (InvocationTargetException expected) {
                // 构造校验拒绝 null，DDL 里这一列必须 NOT NULL
            }
        }
        nullableFromRecord.add(DdlReference.LEASE_COLUMN);

        assertThat(outboxTable().columns()).filteredOn(DdlTable.DdlColumn::nullable)
                .extracting(DdlTable.DdlColumn::name)
                .containsExactlyInAnyOrderElementsOf(nullableFromRecord);
    }

    @Test
    void should_keep_status_as_varchar_wide_enough_for_all_enum_names() {
        DdlTable table = outboxTable();
        DdlTable.DdlColumn status = table.column("status");
        int declaredLength = Integer
                .parseInt(status.type().substring("varchar(".length(), status.type().length() - 1));
        int longestEnumName = Arrays.stream(OutboxStatus.values()).mapToInt(name -> name.name().length()).max()
                .orElseThrow();

        assertThat(status.type()).startsWith("varchar(");
        assertThat(longestEnumName).isEqualTo(11);
        assertThat(declaredLength).isGreaterThanOrEqualTo(longestEnumName * 2);
    }

    @Test
    void should_store_payload_as_mediumtext_without_json_normalization() {
        assertThat(outboxTable().column("payload").type()).isEqualTo("mediumtext");
    }

    @Test
    void should_make_event_id_the_primary_key() {
        DdlTable table = outboxTable();

        assertThat(table.primaryKeyColumns()).containsExactly("event_id");
        assertThat(table.indexes()).extracting(DdlTable.DdlIndex::columns).doesNotContain(List.of("event_id"));
    }

    @Test
    void should_index_both_claim_predicates_as_non_unique() {
        DdlTable table = outboxTable();

        assertThat(table.indexes()).hasSize(2);
        assertThat(table.indexes()).extracting(DdlTable.DdlIndex::columns)
                .containsExactlyInAnyOrder(List.of("status", "next_retry_at"),
                        List.of("status", DdlReference.LEASE_COLUMN));
        assertThat(table.indexes()).allSatisfy(index -> assertThat(index.unique()).isFalse());
    }

    @Test
    void should_check_retry_count_is_not_negative_in_the_schema() {
        assertThat(outboxTable().checkConstraints()).containsExactly("retry_count >= 0");
    }

    @Test
    void should_declare_engine_and_charset_explicitly_for_both_tables() {
        for (DdlTable table : List.of(outboxTable(), idempotentTable())) {
            assertThat(table.tableOptions())
                    .as("表 %s 的选项", table.tableName())
                    .contains("engine = innodb")
                    .contains("default charset = utf8mb4")
                    .contains("collate = utf8mb4_0900_ai_ci");
        }
    }

    @Test
    void should_match_idempotent_port_semantics_for_the_idempotent_table() {
        DdlTable table = idempotentTable();

        assertThat(table.tableName()).isEqualTo(DdlReference.IDEMPOTENT_RECORD_TABLE);
        assertThat(table.columns()).extracting(DdlTable.DdlColumn::name)
                .containsExactly("idem_key", "token", "created_at", "expires_at");
        assertThat(table.columns()).allSatisfy(
                column -> assertThat(column.nullable()).as("列 %s", column.name()).isFalse());
        assertThat(table.column("created_at").type()).isEqualTo("datetime(3)");
        assertThat(table.column("expires_at").type()).isEqualTo("datetime(3)");
        assertThat(table.primaryKeyColumns()).containsExactly("idem_key");
        assertThat(table.indexes()).singleElement().satisfies(index -> {
            assertThat(index.name()).isEqualTo("idx_myddd_idempotent_expires_at");
            assertThat(index.columns()).containsExactly("expires_at");
            assertThat(index.unique()).isFalse();
        });
        assertThat(table.checkConstraints()).containsExactly("expires_at > created_at");
    }

    @Test
    void should_keep_one_definition_per_line_as_the_parser_assumes() {
        for (String ddl : List.of(read(DdlReference.OUTBOX_DDL), read(DdlReference.IDEMPOTENT_RECORD_DDL))) {
            List<String> bodyLines = ddl.lines()
                    .map(line -> line.replaceAll("\\s+", " ").trim())
                    .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                    .filter(line -> !line.startsWith("create table ") && !line.startsWith(")"))
                    .toList();
            DdlTable table = DdlTable.parse(ddl);

            assertThat(bodyLines).allSatisfy(line -> assertThat(line).matches(
                    "(primary key \\(|(unique )?(key|index) |constraint |[a-z][a-z0-9_]* "
                            + "(varchar\\(\\d+\\)|datetime\\(3\\)|mediumtext|int) ).*"));
            assertThat(table.columns())
                    .hasSize(bodyLines.size() - table.indexes().size() - table.checkConstraints().size() - 1);
        }
    }

    @Test
    void should_name_reference_migrations_with_unique_ascending_versions() throws Exception {
        URL directory = classLoader().getResource(DdlReference.RESOURCE_DIRECTORY);
        assertThat(directory).isNotNull();
        List<String> fileNames;
        try (Stream<Path> entries = Files.list(Path.of(directory.toURI()))) {
            fileNames = entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
        List<String> migrationNames = fileNames.stream().filter(name -> name.endsWith(".sql")).toList();

        assertThat(fileNames).containsExactly("README.md", "V1__myddd_outbox.sql", "V2__myddd_idempotent_record.sql");
        assertThat(migrationNames)
                .allSatisfy(name -> assertThat(name).matches("V\\d+__myddd_[a-z0-9_]+\\.sql"));
        assertThat(migrationNames.stream()
                .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__")))).toList())
                .containsExactly(1, 2);
    }

    @Test
    void should_ship_a_readme_that_explains_the_copy_and_rename_step() {
        String readme = read(DdlReference.README);

        assertThat(readme).contains("classpath:db/migration")
                .contains("spring.flyway.locations")
                .contains("V7__myddd_outbox.sql")
                .contains(DdlReference.OUTBOX_TABLE)
                .contains(DdlReference.IDEMPOTENT_RECORD_TABLE);
    }

    @Test
    void should_not_place_reference_ddl_on_flyway_default_location() {
        assertThat(classLoader().getResource(FLYWAY_DEFAULT_LOCATION)).isNull();
        assertThat(classLoader().getResource(FLYWAY_DEFAULT_LOCATION + "/V1__myddd_outbox.sql")).isNull();
        assertThat(classLoader().getResource(DdlReference.OUTBOX_DDL)).isNotNull();
    }

    @Test
    void should_fail_fast_when_reference_ddl_resource_is_missing() {
        assertThatThrownBy(() -> DdlReference.read("db/ddl-reference/V9__myddd_missing.sql"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("db/ddl-reference/V9__myddd_missing.sql");
    }

    private static DdlTable outboxTable() {
        return DdlTable.parse(read(DdlReference.OUTBOX_DDL));
    }

    private static DdlTable idempotentTable() {
        return DdlTable.parse(read(DdlReference.IDEMPOTENT_RECORD_DDL));
    }

    private static String read(String resource) {
        return DdlReference.read(resource);
    }

    private static ClassLoader classLoader() {
        return DdlReference.class.getClassLoader();
    }

    /** 用真实工厂方法取一份合法记录，再逐位替换成 null 试探构造校验。 */
    private static Object[] validRecordArguments() throws Exception {
        OutboxRecord sample = OutboxRecord.pending("event-1", "org.example.OrderPaidEvent",
                "org.example.order.Order", "1000", Instant.parse("2026-09-26T00:00:00Z"), "trace-1",
                "order-events", "{\"orderId\":\"1000\"}", Instant.parse("2026-09-26T00:00:01Z"));
        List<Object> arguments = new ArrayList<>();
        for (RecordComponent component : OutboxRecord.class.getRecordComponents()) {
            Method accessor = OutboxRecord.class.getMethod(component.getName());
            arguments.add(accessor.invoke(sample));
        }
        return arguments.toArray();
    }
}
