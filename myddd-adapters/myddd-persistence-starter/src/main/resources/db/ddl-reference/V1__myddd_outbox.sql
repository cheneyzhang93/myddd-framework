-- myddd_outbox：领域事件 Outbox 表的参考 DDL（框架交付，不参与自动注入迁移）。
-- 用法见同目录 README.md：复制进你自己的 Flyway 目录，按你工程的迁移序列改名（例如 V7__myddd_outbox.sql）。
-- 列名单一真源是 org.deep361.myddd.domain.outbox.OutboxRecord 的 record 组件，
-- 由 myddd-persistence-starter 的 DdlReferenceTest 逐列核对；dispatch_lease_until 是存储侧的租约列。
create table myddd_outbox (
    event_id varchar(64) not null comment '事件唯一 id，同时作为投递消息头 myddd-event-id',
    event_type varchar(255) not null comment '事件逻辑类型名（默认为二进制类名）',
    aggregate_type varchar(255) not null comment '聚合类型',
    aggregate_id varchar(128) not null comment '聚合 id 字符串形式',
    trace_id varchar(64) null comment '发布时的链路 id，允许为空',
    occurred_at datetime(3) not null comment '事件发生时间（业务时间，消费端按它排序）',
    channel varchar(64) not null comment '投递通道',
    payload mediumtext not null comment '事件 JSON 原文，投递与重放都按原样读取',
    status varchar(32) not null comment 'PENDING、DISPATCHING、DISPATCHED、DEAD',
    retry_count int not null default 0 comment '已失败的投递尝试次数',
    next_retry_at datetime(3) not null comment '最早可投递时间',
    last_error varchar(1000) null comment '最近一次投递失败原因',
    created_at datetime(3) not null comment '记录创建时间，投递器按它先进先出',
    dispatch_lease_until datetime(3) null comment '投递租约到期时间，过期即可被重新认领（崩溃恢复）',
    primary key (event_id),
    key idx_myddd_outbox_dispatchable (status, next_retry_at),
    key idx_myddd_outbox_lease (status, dispatch_lease_until),
    constraint ck_myddd_outbox_retry_count check (retry_count >= 0)
) engine = innodb default charset = utf8mb4 collate = utf8mb4_0900_ai_ci comment = 'myddd 领域事件 Outbox 表（参考 DDL，复制改名后由业务自己的迁移执行）';
