package org.deep361.myddd.persistence.autoconfigure;

import com.baomidou.mybatisplus.annotation.DbType;
import java.sql.Driver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.deep361.myddd.persistence.routing.DataSourceKeyHolder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import jakarta.annotation.PostConstruct;

/**
 * 持久化能力配置（前缀 {@code myddd.persistence}）。
 *
 * <p><b>只管框架自己的事</b>：这里三个拦截器开关决定"框架往 MyBatis 插件链里放什么"；MyBatis-Plus 的全部
 * 105 个键（{@code mybatis-plus.*}）由 MP 自己解析，本类不代理、不改名、不设默认值（§0.3-6）。
 *
 * <p>已交付：拦截器与总开关（Task 13）、{@code audit.*}（Task 14）、{@code datasources.<name>.*} 与
 * {@code default-datasource}（Task 17）、{@code outbox.*}（Task 19，Task 21 再补 {@code dead-letter.*}）。
 */
@ConfigurationProperties("myddd.persistence")
public class MydddPersistenceProperties {

    /** 整个持久化适配层的总开关。关闭时本模块不装配任何 bean，配置校验同样跳过。 */
    private boolean enabled = true;

    private final Interceptor interceptor = new Interceptor();
    private final Audit audit = new Audit();
    private final Outbox outbox = new Outbox();

    /**
     * 命名数据源：{@code myddd.persistence.datasources.<name>.url} 等。
     *
     * <p>非空即进入路由模式（装配见 {@code MydddRoutingDataSourceAutoConfiguration}），
     * 空则表示沿用 Boot 的单数据源配法——本模块不声明任何 {@code DataSource}。
     */
    private final Map<String, Datasource> datasources = new LinkedHashMap<>();

    /** 路由模式下的默认键：未绑定任何键的调用走它。 */
    private String defaultDatasource = "primary";

    public Map<String, Datasource> getDatasources() {
        return datasources;
    }

    public String getDefaultDatasource() {
        return defaultDatasource;
    }

    public void setDefaultDatasource(String defaultDatasource) {
        this.defaultDatasource = defaultDatasource;
    }

    public Audit getAudit() {
        return audit;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Interceptor getInterceptor() {
        return interceptor;
    }

    /**
     * 启动期校验：分页上限、审计占位身份、以及路由模式下每个命名数据源的可用性。
     *
     * <p>路由校验只查"形状与可加载"，<b>不连库</b>（理由见 Task 17 设计要点 9）。
     *
     * @throws FrameworkException 任一项配置会让应用跑不起来
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        Pagination pagination = interceptor.getPagination();
        if (pagination.isEnabled() && pagination.getMaxLimit() < 0) {
            throw new FrameworkException("myddd.persistence.interceptor.pagination.max-limit=" + pagination.getMaxLimit()
                    + " 非法：0 表示不限、正数表示单页上限，负数会让分页返回空列表；请改正取值或关闭该拦截器");
        }
        if (audit.isEnabled() && audit.getUnknownPrincipal().isBlank()) {
            throw new FrameworkException("myddd.persistence.audit.unknown-principal 不能为空白：无登录上下文时"
                    + "creator/updater 必须有值，否则审计列写不进去；请给出一个占位身份（默认 system），"
                    + "或关闭 myddd.persistence.audit.enabled");
        }
        validateDatasources();
    }

    private void validateDatasources() {
        if (datasources.isEmpty()) {
            return;
        }
        datasources.forEach(this::validateDatasource);
        if (!datasources.containsKey(defaultDatasource)) {
            throw new FrameworkException("myddd.persistence.default-datasource=" + defaultDatasource
                    + " 在 myddd.persistence.datasources 里没有定义，已定义的键：" + String.join(", ", datasources.keySet())
                    + "；路由模式的默认键必须是一个已定义的数据源，否则每个未绑定的请求都会在取连接时失败");
        }
    }

    private void validateDatasource(String name, Datasource datasource) {
        String property = "myddd.persistence.datasources." + name;
        if (!DataSourceKeyHolder.isValidKey(name)) {
            throw new FrameworkException(property + " 的键名非法：必须以英文小写字母开头、只含 [a-z0-9_]、长度不超过 64；"
                    + "这个键名同时用作 @UseDataSource 的取值，两边必须一字不差地对上");
        }
        String url = datasource.getUrl();
        if (!StringUtils.hasText(url)) {
            throw new FrameworkException(property + ".url 不能为空：路由模式下每个命名数据源都必须给出自己的连接串");
        }
        if (!url.startsWith("jdbc:")) {
            throw new FrameworkException(property + ".url=" + url + " 缺少 \"jdbc:\" 前缀：连接池要到第一次取连接"
                    + "才报错，那时栈里已经没有配置信息了");
        }
        String driverClassName = datasource.getDriverClassName();
        if (StringUtils.hasText(driverClassName)) {
            validateDriverClass(property + ".driver-class-name", driverClassName);
        }
    }

    private void validateDriverClass(String property, String driverClassName) {
        Class<?> loaded;
        try {
            // 用本类的类加载器：属性 bean 与驱动同源于应用类加载器；用 TCCL 会在容器部署时选错加载器
            loaded = ClassUtils.forName(driverClassName, getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError cause) {
            throw new FrameworkException(property + "=" + driverClassName + " 无法加载：" + cause
                    + "；要么 classpath 上确实没有这个驱动，要么删掉这个键——JDBC 4 起 DriverManager 会按 url 自动找驱动",
                    cause);
        }
        if (!Driver.class.isAssignableFrom(loaded)) {
            throw new FrameworkException(property + "=" + driverClassName + " 不是 java.sql.Driver 的实现类，"
                    + "连接池拿它当驱动必然失败");
        }
    }

    /** MyBatis-Plus 插件链（框架侧可装配的三个拦截器）。 */
    public static class Interceptor {

        private final Pagination pagination = new Pagination();
        private final OptimisticLock optimisticLock = new OptimisticLock();
        private final BlockAttack blockAttack = new BlockAttack();

        public Pagination getPagination() {
            return pagination;
        }

        public OptimisticLock getOptimisticLock() {
            return optimisticLock;
        }

        public BlockAttack getBlockAttack() {
            return blockAttack;
        }
    }

    /** 分页拦截器。 */
    public static class Pagination {

        private boolean enabled = true;
        /**
         * 分页方言。<b>缺省 {@code null} 表示"按连接的 JDBC URL 自动识别"</b>（MyBatis-Plus 的
         * {@code PaginationInnerInterceptor} 无参构造就是这个行为）。默认钉成 {@code MYSQL} 等于把整套
         * 持久化层绑在一个数据库上，与"同一套代码换库能跑"的设计目标相反；只有在自动识别拿不准时
         * （连接池代理改写过 URL 之类）才显式给一个值。
         */
        private DbType dbType;
        private long maxLimit = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public DbType getDbType() {
            return dbType;
        }

        public void setDbType(DbType dbType) {
            this.dbType = dbType;
        }

        /** {@code 0} 表示不限（见设计要点 5：不调 {@code setMaxLimit} 而不是传 0）。 */
        public long getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(long maxLimit) {
            this.maxLimit = maxLimit;
        }
    }

    /** 乐观锁拦截器：实体上标了 {@code @Version} 才会生效，所以开着对未用版本号的实体无副作用。 */
    public static class OptimisticLock {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 防全表更新/删除拦截器：没有 where 的 update/delete 直接抛异常。 */
    public static class BlockAttack {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 审计字段自动填充（{@code AbstractPO} 的四个字段）。 */
    public static class Audit {

        private boolean enabled = true;
        private String unknownPrincipal = "system";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** 无鉴权上下文时写进 {@code creator/updater} 的占位身份。 */
        public String getUnknownPrincipal() {
            return unknownPrincipal;
        }

        public void setUnknownPrincipal(String unknownPrincipal) {
            this.unknownPrincipal = unknownPrincipal;
        }
    }

    /**
     * Outbox 的 JDBC 存储（{@link org.deep361.myddd.persistence.outbox.JdbcOutboxStore}）。
     *
     * <p><b>{@code enabled} 默认 false 是刻意的</b>：框架交付的是<em>参考</em>建表脚本，不自动注入迁移
     * （见 {@code DdlReference}），所以默认就假定库里没有 {@code myddd_outbox}。把开关默认打开会让每个只引
     * starter 未建表的应用起不来，而"领域事件发不出去"这种事又不该靠框架猜表存在来解决——业务建完表再显式打开。
     *
     * <p>{@code table} 的形状校验落在 {@code JdbcOutboxStore} 的构造器（表名要拼进 SQL 文本，那里是唯一的
     * 注入面，也是唯一需要把关的地方）。开关关闭时不会构造该 bean，因此非法表名也不会报错——没被使用的键
     * 不值得让应用起不来。
     */
    public static class Outbox {

        private boolean enabled;
        private String table = DdlReference.OUTBOX_TABLE;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** Outbox 表名，缺省 {@code myddd_outbox}；取值必须匹配 {@code [a-z0-9_]{1,64}}。 */
        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        /** 死信读侧（查询与重放）的旋钮。 */
        private final DeadLetter deadLetter = new DeadLetter();

        public DeadLetter getDeadLetter() {
            return deadLetter;
        }
    }

    /**
     * 死信读侧（{@link org.deep361.myddd.persistence.outbox.OutboxDeadLetterStore}）。
     *
     * <p>{@code queryLimit} 同时管两件事：死信列表的页大小上限，与一次重放请求的条数上限。合成一个值是刻意的
     * ——运维界面上"看到的条数"与"能一次选中的条数"本来就该同宽，两个键反而会让人配出"能选 500 条、
     * 一次只能重放 200 条"这种自相矛盾的组合。
     *
     * <p>默认 200 而不是 {@code PageQuery} 的 500：这一页要展示 {@code payload} 与 {@code last_error}
     * 两段长文本，200 条已经足够让一次页面渲染变成几十 MB 的响应体。
     */
    public static class DeadLetter {

        private int queryLimit = 200;

        public int getQueryLimit() {
            return queryLimit;
        }

        public void setQueryLimit(int queryLimit) {
            this.queryLimit = queryLimit;
        }
    }

    /**
     * 单个命名数据源的连接参数（{@code myddd.persistence.datasources.<name>.*}）。
     *
     * <p>只代理建一个池必需的四件；池大小、超时等调优项属 HikariCP 自己的键（{@code spring.datasource.hikari.*}
     * 或等价的 {@code myddd.persistence.datasources.<name>.hikari.*} 由业务侧按 MP/Hikari 文档配），本类不代钉、
     * 不改写（§0.3-6）。{@code username}/{@code password}/{@code driverClassName} 都可空——免密库、集成认证、
     * JDBC 4 自动驱动发现都是合法配法，只有 {@code url} 是硬要求（见 {@code validateDatasource}）。
     */
    public static class Datasource {

        private String url;
        private String username;
        private String password;
        private String driverClassName;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }
    }
}
