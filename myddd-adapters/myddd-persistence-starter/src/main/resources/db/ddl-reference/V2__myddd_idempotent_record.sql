-- myddd_idempotent_record：幂等占位表的参考 DDL（store=db 时使用，框架交付，不参与自动注入迁移）。
-- 列语义与 org.deep361.myddd.kernel.port.consistency.IdempotentPort 一一对应，
-- 由 myddd-persistence-starter 的 DdlReferenceTest 核对；改名会直接让那条测试红。
create table myddd_idempotent_record (
    idem_key varchar(255) not null comment '幂等键：@Idempotent 解析出的完整键（含 key-prefix 段）',
    token varchar(64) not null comment '本次请求的占位凭据，释放时按它比对',
    created_at datetime(3) not null comment '占位写入时间',
    expires_at datetime(3) not null comment '占位过期时间，过点视为不存在（与 Redis TTL 同义）',
    primary key (idem_key),
    key idx_myddd_idempotent_expires_at (expires_at),
    constraint ck_myddd_idempotent_ttl check (expires_at > created_at)
) engine = innodb default charset = utf8mb4 collate = utf8mb4_0900_ai_ci comment = 'myddd 幂等占位表（参考 DDL，复制改名后由业务自己的迁移执行）';
