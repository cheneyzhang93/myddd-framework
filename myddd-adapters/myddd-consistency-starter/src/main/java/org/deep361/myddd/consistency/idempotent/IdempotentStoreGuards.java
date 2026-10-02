package org.deep361.myddd.consistency.idempotent;

import java.time.Duration;

import org.deep361.myddd.kernel.common.error.FrameworkException;

/**
 * 两种占位存储（{@link RedisIdempotentStore} 与 {@link JdbcIdempotentStore}）共用的入参守卫与键名拼法。
 *
 * <p><b>为什么单独一个类</b>：端口契约（{@code IdempotentPort}）把"键不能空白、token 不能空白、ttl 必须为正"
 * 定成了 {@link IllegalArgumentException}，而这条契约的验收是<b>两种存储必须给出同一个答案</b>
 * （计划文档 §0.4 的裁定 6：同一能力的双实现要过同一组等价用例）。同一个判断在两个类里各写一遍，
 * 漂移的方式不是编译不过，而是"换一种存储以后空白键不再报错"——那正是等价性被击穿的方式。
 * 键名前缀的拼法（{@link #prefixedKey}）同理：Redis 里那条键与 DB 表里 {@code idem_key} 的值
 * 必须是同一个串，否则换存储等于换键空间，重启或迁移期间的重复请求会双双放行。
 *
 * <p>包私有：这不是扩展点。业务要换存储请实现 {@code IdempotentPort}，而不是绕开这些守卫。
 */
final class IdempotentStoreGuards {

    /**
     * 框架表表名的合法形状。与 {@code myddd.persistence.outbox.table} 同一条约定（持久化侧的
     * {@code OutboxSql} 用的是同一个表达式）：表名是唯一会原样拼进 SQL 文本的用户输入，
     * 所以宁可构造失败也不接受引号、空白或分号形态。
     *
     * <p>两侧各留一份而不是共享常量，是模块边界的实情：{@code myddd-consistency-starter} 不在编译期依赖
     * {@code myddd-persistence-starter}（依赖方向反过来也一样），而为了让一个正则串去污染测试类路径
     * （见模块 POM 里那段 H2 注释）比留两份更糟。改这一行的时候请同时改
     * {@code org.deep361.myddd.persistence.outbox.OutboxSql#TABLE_NAME_RULE}。
     */
    static final String TABLE_NAME_RULE = "[a-z0-9_]{1,64}";

    private IdempotentStoreGuards() {
    }

    static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("幂等键不能为空白：空白键会让所有“空白名字”共用一条占位记录，"
                    + "幂等范围从一个接口扩大到整个应用");
        }
        return key;
    }

    static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("幂等 token 不能为空白：token 是占位的属主凭据，"
                    + "空白值会让“按 token 比对后删除”永远匹配不上，释放变成静默失效——"
                    + "业务失败以后在 TTL 内重试会被自己的占位拒绝");
        }
        return token;
    }

    static Duration requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("占位时长 ttl 必须为正（收到 " + ttl + "）：零或负数会让占位从不存在"
                    + "（Redis 侧的 SET PX 要么当场报错、要么立刻过期；DB 侧的 expires_at 与 created_at 相等，"
                    + "交付 DDL 的 ck_myddd_idempotent_ttl 直接拒掉这一行），于是幂等退化成“每个请求都算首次”。"
                    + "要短一些请写 @Idempotent(ttl = 1)，要长一些请写更大的秒数");
        }
        return ttl;
    }

    /** 存储侧的物理键名：{@code keyPrefix + ":" + key}，两种存储共用，不做任何其它加工。 */
    static String prefixedKey(String keyPrefix, String key) {
        return keyPrefix + ":" + key;
    }

    /**
     * 表名守卫（构造期调用）：失败话术指名本能力的配置键与交付的参考建表脚本。
     *
     * @param configKey 这个表名来自哪个配置键，写进话术让用户知道该改哪一行
     */
    static String requireTableName(String table, String configKey) {
        if (table == null || table.isBlank()) {
            throw new FrameworkException(configKey + " 不能是空白：它是幂等占位表的表名，空白值拼出来的 SQL "
                    + "语法错且看不出错在哪。不想要框架交付这份存储请写 myddd.consistency.idempotent.enabled=false");
        }
        if (!table.matches(TABLE_NAME_RULE)) {
            throw new FrameworkException("幂等占位表名 \"" + table + "\" 非法：只允许 " + TABLE_NAME_RULE
                    + "。这个取值会原样拼进 SQL 文本，框架不接受引号、空白或分号形态；请检查 " + configKey
                    + "（默认 myddd_idempotent_record），建表脚本见 classpath:db/ddl-reference/"
                    + "V2__myddd_idempotent_record.sql（由 myddd-persistence-starter 交付，框架不会自动注入它）");
        }
        return table;
    }
}
