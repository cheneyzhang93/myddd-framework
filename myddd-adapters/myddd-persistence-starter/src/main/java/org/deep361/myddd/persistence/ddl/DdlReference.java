package org.deep361.myddd.persistence.ddl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.kernel.common.error.FrameworkException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 框架表（Outbox、幂等占位）的 schema 约定：资源位置常量 + {@code OutboxRecord} 到列名的唯一映射点。
 *
 * <p><b>为什么是 classpath 资源而不是仓库里的文档</b>：这两份 DDL 要能被运行中的应用读到，才好在一句话里
 * 指名"去复制哪个文件"（Task 19 的建表缺失错误、Task 27 的 DB 幂等同源）。同时它们刻意放在
 * {@value #RESOURCE_DIRECTORY} 而不是 Flyway 的缺省扫描位置，所以框架交付的是样板，不是自动注入的迁移。
 *
 * <p><b>列名的唯一真源是 {@link OutboxRecord} 的 record 组件</b>，命名映射直接用 MyBatis-Plus 的
 * {@code StringUtils.camelToUnderline}（与 {@code *PO} 走同一套下划线约定，不给这张表开特例）。
 * 表结构本身由本模块的 {@code DdlReferenceTest} 逐列核对：加字段忘改 DDL、DDL 精度写错、
 * 可空性与内核不变式分叉，都以测试失败收场。
 */
public final class DdlReference {

    /** 参考 DDL 所在目录（相对 classpath）。刻意区别于 {@code db/migration}。 */
    public static final String RESOURCE_DIRECTORY = "db/ddl-reference";

    /** Outbox 表的缺省名，同时是 {@code myddd.persistence.outbox.table} 的默认值（Task 19 钉住二者一致）。 */
    public static final String OUTBOX_TABLE = "myddd_outbox";

    /** 幂等占位表的缺省名，同时是 {@code myddd.consistency.idempotent.db.table} 的默认值（Task 27 钉）。 */
    public static final String IDEMPOTENT_RECORD_TABLE = "myddd_idempotent_record";

    public static final String OUTBOX_DDL = RESOURCE_DIRECTORY + "/V1__myddd_outbox.sql";
    public static final String IDEMPOTENT_RECORD_DDL = RESOURCE_DIRECTORY + "/V2__myddd_idempotent_record.sql";
    public static final String README = RESOURCE_DIRECTORY + "/README.md";

    /** 投递租约到期时间列：{@code OutboxStore} 的存储侧概念，刻意不落在 {@link OutboxRecord} 上。 */
    public static final String LEASE_COLUMN = "dispatch_lease_until";

    private static final List<String> RECORD_COLUMNS = Arrays.stream(OutboxRecord.class.getRecordComponents())
            .map(component -> StringUtils.camelToUnderline(component.getName()))
            .toList();

    private static final List<String> OUTBOX_COLUMNS =
            Stream.concat(RECORD_COLUMNS.stream(), Stream.of(LEASE_COLUMN)).toList();

    private DdlReference() {
    }

    /**
     * {@link OutboxRecord} 的 13 个组件对应的列，顺序即 record 组件声明顺序。
     *
     * <p>Task 19 的 {@code INSERT} 与 {@code SELECT} 列表用这个而不是 {@link #outboxColumns()}：
     * {@link #LEASE_COLUMN} 是存储侧的认领状态，写进 {@code OutboxRecord} 的读写列表等于让内核契约
     * 背上一个它没有的字段。
     */
    public static List<String> outboxRecordColumns() {
        return RECORD_COLUMNS;
    }

    /**
     * {@code myddd_outbox} 的全部物理列，顺序为 {@link #outboxRecordColumns()} 加末位
     * {@link #LEASE_COLUMN}。给 schema 核对测试用（列全集 ↔ 交付 DDL 逐列对上）；SQL 的读写列表用
     * {@link #outboxRecordColumns()}，这样"给 record 加字段"与"改 SQL"之间不存在第二种顺序说法。
     */
    public static List<String> outboxColumns() {
        return OUTBOX_COLUMNS;
    }

    /**
     * 读取一份交付物文本（DDL 或说明）。
     *
     * @param resource 相对 classpath 的资源路径，取值就是本类的 {@code *_DDL} / {@link #README} 常量
     * @throws FrameworkException 资源不在 classpath 上（说明 jar 被裁剪过或路径写错）
     */
    public static String read(String resource) {
        try (InputStream stream = DdlReference.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new FrameworkException("找不到参考 DDL 资源 " + resource
                        + "：它应由 myddd-persistence-starter 交付，缺失说明该 jar 被裁剪过或路径写错了");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException readFailure) {
            throw new FrameworkException("读取参考 DDL 资源 " + resource + " 失败：" + readFailure.getMessage(),
                    readFailure);
        }
    }
}
