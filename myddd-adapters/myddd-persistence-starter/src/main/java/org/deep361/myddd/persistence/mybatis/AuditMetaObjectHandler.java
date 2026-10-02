package org.deep361.myddd.persistence.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;

/**
 * 按 {@link AbstractPO} 的 {@code @TableField(fill = …)} 约定自动写入审计字段。
 *
 * <p>填充范围由实体上的注解决定：没有声明 {@code FieldFill} 的字段一个都不写，因此框架不会
 * 触碰未继承本约定的 PO。
 *
 * <p>两种时机的<b>覆盖语义不同</b>，这是本类唯一需要绕过 MP 默认实现的地方：
 * <ul>
 *   <li>插入用 {@code strictInsertFill}——它只在字段当前值为 {@code null} 时才写，于是历史数据导入
 *       与数据迁移显式赋过的 {@code creator/createTime} 会原样保留。</li>
 *   <li>更新必须<b>无条件覆盖</b>：{@code strictUpdateFill} 同样带那个判空，而从库里读出来的实体
 *       {@code updateTime} 必然非空，用它会让"更新永远不改时间戳"——审计列退化成"只有插入那天对"。
 *       {@link #forceUpdateFill} 复用 MP 的三个筛选谓词（属性名、类型、字段标了 {@code fill=UPDATE}），
 *       只去掉判空那一条。</li>
 * </ul>
 *
 * <p>一次插入里四个字段取自<em>同一时刻</em>（{@code createTime == updateTime}），所以
 * {@link Clock} 每次调用只读一次；时钟可注入是这里唯一的可测性依赖。
 *
 * <p>操作人取自 {@link PrincipalProvider}；拿不到（无登录上下文、批量作业、Outbox 投递线程）
 * 时写入配置的兜底身份而<b>不抛异常</b>，否则任何后台任务都无法落库。
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    static final String CREATE_TIME = "createTime";
    static final String UPDATE_TIME = "updateTime";
    static final String CREATOR = "creator";
    static final String UPDATER = "updater";

    private final PrincipalProvider principalProvider;
    private final Clock clock;
    private final String unknownPrincipal;

    public AuditMetaObjectHandler(PrincipalProvider principalProvider, Clock clock, String unknownPrincipal) {
        this.principalProvider = principalProvider;
        this.clock = clock;
        this.unknownPrincipal = unknownPrincipal;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = this.clock.instant();
        String principal = currentPrincipal();
        strictInsertFill(metaObject, CREATE_TIME, Instant.class, now);
        strictInsertFill(metaObject, CREATOR, String.class, principal);
        strictInsertFill(metaObject, UPDATE_TIME, Instant.class, now);
        strictInsertFill(metaObject, UPDATER, String.class, principal);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        Instant now = this.clock.instant();
        String principal = currentPrincipal();
        forceUpdateFill(metaObject, UPDATE_TIME, Instant.class, now);
        forceUpdateFill(metaObject, UPDATER, String.class, principal);
    }

    /**
     * 只按 {@code fill} 声明筛字段、不看当前值是否为 {@code null} 的更新填充（原因见类注释）。
     *
     * <p>{@code findTableInfo} 在实体未注册元数据时抛异常，与 MP 自带的 {@code strictUpdateFill}
     * 行为一致，因此这里不额外判空。
     */
    private void forceUpdateFill(MetaObject metaObject, String property, Class<?> type, Object value) {
        findTableInfo(metaObject).getFieldList().stream()
                .filter(field -> property.equals(field.getProperty())
                        && type.equals(field.getPropertyType())
                        && field.isWithUpdateFill())
                .findFirst()
                .ifPresent(field -> metaObject.setValue(property, value));
    }

    private String currentPrincipal() {
        return this.principalProvider.currentPrincipal()
                .filter(StringUtils::hasText)
                .orElse(this.unknownPrincipal);
    }
}
