package org.deep361.myddd.consistency.idempotent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code store=db} 那一支的离线测试库：H2 以 {@code MODE=MySQL} 在内存里跑<b>真 SQL</b>，
 * 表结构是 {@code myddd-persistence-starter} 交付的那份参考 DDL 的原文副本。
 *
 * <p><b>为什么是副本而不是直接读交付物</b>：读 {@code DdlReference} 要把 {@code myddd-persistence-starter}
 * 引成本模块的测试依赖，而它的传递依赖（MyBatis-Plus、spring-boot-jdbc）一进测试类路径，
 * 本模块带着 {@code @EnableAutoConfiguration} 的 IT 上下文就会因为"配不出 DataSource"整个失败——
 * 没有 Docker 时那些 IT 是跳过的，坏掉的路径当场看不出来（模块 POM 里那段 H2 注释记的是同一条）。
 * 代价是这份文本有两处，靠两条用例分别钉住：本模块的
 * {@code JdbcIdempotentStoreTest#should_have_been_copied_from_the_delivered_reference_ddl}（副本与
 * {@link JdbcIdempotentStore} 所用列名/列宽/主键对得上）与那边的
 * {@code DdlReferenceTest#should_match_idempotent_port_semantics_for_the_idempotent_table}
 * （交付物与同一组列名/列宽/主键对得上）。两端的清单是同一份四列顺序，改列名要两处都改才会绿。
 *
 * <p><b>这里不证明的</b>：真 MySQL 的到期行并发认领（行锁与当前读）、驱动 1062 → {@code DuplicateKeyException}
 * 的具体子类型、{@code datetime(3)} 的四舍五入行为——这些归 Docker 就位后的补跑清单（计划文档 Task 27
 * 的验收边界一节）。本类钉的是<b>我们的代码</b>的形状：四条语句、两个谓词、以及各条守卫。
 */
final class IdempotentH2 {

    /** 交付 DDL 的表名，同时也是 {@link JdbcIdempotentStore#DEFAULT_TABLE}。 */
    static final String REFERENCE_TABLE = "myddd_idempotent_record";

    /**
     * {@code db/ddl-reference/V2__myddd_idempotent_record.sql} 的原文副本（含注释与表选项）。
     *
     * <p>刻意不加工：连 {@code engine}/{@code charset}/{@code comment} 这些 H2 只是"容忍"的部分一起留着，
     * 这样这份副本建得起来本身就是"交付脚本在 MySQL 模式下parse得通"的一条证据。
     */
    static final String REFERENCE_DDL = """
            create table myddd_idempotent_record (
                idem_key varchar(255) not null comment '幂等键：@Idempotent 解析出的完整键（含 key-prefix 段）',
                token varchar(64) not null comment '本次请求的占位凭据，释放时按它比对',
                created_at datetime(3) not null comment '占位写入时间',
                expires_at datetime(3) not null comment '占位过期时间，过点视为不存在（与 Redis TTL 同义）',
                primary key (idem_key),
                key idx_myddd_idempotent_expires_at (expires_at),
                constraint ck_myddd_idempotent_ttl check (expires_at > created_at)
            ) engine = innodb default charset = utf8mb4 collate = utf8mb4_0900_ai_ci comment = 'myddd 幂等占位表（参考 DDL，复制改名后由业务自己的迁移执行）';
            """;

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String table;

    private IdempotentH2(DataSource dataSource, JdbcTemplate jdbc, String table) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.table = table;
    }

    /** 用缺省表名开一个库。 */
    static IdempotentH2 open() {
        return open(REFERENCE_TABLE);
    }

    /**
     * 开一个独立的内存库并按交付 DDL 建表。
     *
     * @param table 表名；非缺省名时会把 DDL 里的表名换掉（列、约束、索引名一律不动）
     */
    static IdempotentH2 open(String table) {
        String url = "jdbc:h2:mem:idempotent-" + DATABASES.incrementAndGet() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource backing = new DriverManagerDataSource(url);
        backing.setDriverClassName("org.h2.Driver");
        JdbcTemplate template = new JdbcTemplate(backing);
        template.execute(REFERENCE_DDL.replace(REFERENCE_TABLE, table));
        return new IdempotentH2(backing, template, table);
    }

    DataSource dataSource() {
        return this.dataSource;
    }

    JdbcTemplate jdbc() {
        return this.jdbc;
    }

    String table() {
        return this.table;
    }

    // 下面是"存储侧到底那一行长什么样"的读取口：断言要落在行上，而不是只落在 store 的返回值上。

    boolean hasRow(String physicalKey) {
        return count(physicalKey) == 1;
    }

    int rowCount() {
        Integer total = this.jdbc.queryForObject("select count(*) from " + this.table, Integer.class);
        return total == null ? 0 : total;
    }

    private int count(String physicalKey) {
        Integer hit = this.jdbc.queryForObject("select count(*) from " + this.table + " where idem_key = ?",
                Integer.class, physicalKey);
        return hit == null ? 0 : hit;
    }

    String tokenOf(String physicalKey) {
        return this.jdbc.queryForObject("select token from " + this.table + " where idem_key = ?", String.class,
                physicalKey);
    }

    Instant expiresAtOf(String physicalKey) {
        return instant(this.jdbc.queryForObject("select expires_at from " + this.table + " where idem_key = ?",
                Timestamp.class, physicalKey));
    }

    Instant createdAtOf(String physicalKey) {
        return instant(this.jdbc.queryForObject("select created_at from " + this.table + " where idem_key = ?",
                Timestamp.class, physicalKey));
    }

    /** 直接造一行占位（用于"已存在的、已过期的占位"这类无法靠时序稳定演出来的前置状态）。 */
    void insertRow(String physicalKey, String token, Instant createdAt, Instant expiresAt) {
        this.jdbc.update("insert into " + this.table + " (idem_key, token, created_at, expires_at)"
                + " values (?, ?, ?, ?)", physicalKey, token, Timestamp.from(createdAt),
                Timestamp.from(expiresAt));
    }

    void deleteAll() {
        this.jdbc.update("delete from " + this.table);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
