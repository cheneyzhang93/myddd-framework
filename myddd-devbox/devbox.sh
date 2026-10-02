#!/usr/bin/env bash
# myddd 本地环境的唯一入口：起/停/查全走这一个脚本，不直接敲 docker compose。
set -euo pipefail

DEVBOX_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$DEVBOX_DIR/compose.yaml"
ENV_FILE="$DEVBOX_DIR/.env"
VALID_PROFILES=(kafka rocketmq job limiter)

die() {
    printf '[devbox] %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
用法：bash myddd-devbox/devbox.sh <命令> [参数]

  up [all | 档位...] [--skip-schema-check]   启动（默认只起 mysql + redis）
                                             档位：kafka rocketmq job limiter
  ps                                         查看状态与健康度
  logs [服务名]                              跟随日志（默认全部）
  down                                       停止并移除容器，保留数据卷
  reset --yes                                停止并清空数据卷（不可撤销）
  verify                                     校验 compose 镜像 tag 与父 POM 一致
EOF
}

env_value() {
    sed -n "s/^$1=//p" "$ENV_FILE" | tail -1
}

require_docker() {
    command -v docker >/dev/null 2>&1 \
        || die "未找到 docker 命令。本机需先安装 Docker Desktop（或 Docker Engine）并能 `docker ps`，再回来跑这个脚本。"
}

require_env_file() {
    [ -f "$ENV_FILE" ] \
        || die "缺少 $ENV_FILE：执行 cp myddd-devbox/.env.example myddd-devbox/.env，然后填 MYSQL_ROOT_PASSWORD（.env 不入库）。"
    [ -n "$(env_value MYSQL_ROOT_PASSWORD)" ] \
        || die ".env 里 MYSQL_ROOT_PASSWORD 为空：compose 会以 \${MYSQL_ROOT_PASSWORD:?} 直接拒绝启动。请填一个仅本机使用的口令。"
}

compose_run() {
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

# down/reset 必须显式带上所有档位：不带 --profile 时 compose 只选中无档位的服务，
# 实测后果是 reset 清掉了 mysql 卷却把 kafka/rocketmq/sentinel 容器原地留下，
# 网络删不掉并报 "Resource is still in use"，与"停止并清空"的字面承诺不符。
compose_stop() {
    local flags=() p
    for p in "${VALID_PROFILES[@]}"; do
        flags+=(--profile "$p")
    done
    compose_run "${flags[@]}" "$@"
}

known_profile() {
    local want="$1" p
    for p in "${VALID_PROFILES[@]}"; do
        [ "$p" = "$want" ] && return 0
    done
    return 1
}

xxljob_schema_present() {
    [ -s "$DEVBOX_DIR/seed/mysql/03-xxl-job.sql" ]
}

print_endpoints() {
    local v
    echo
    echo "[devbox] 宿主机端点（端口取自 .env，改 .env 即改这里）："
    v="$(env_value MYSQL_HOST_PORT)";      echo "  MySQL            127.0.0.1:${v:-3306}"
    v="$(env_value REDIS_HOST_PORT)";      echo "  Redis            127.0.0.1:${v:-6379}"
    v="$(env_value KAFKA_HOST_PORT)";      echo "  Kafka            127.0.0.1:${v:-9092}"
    v="$(env_value ROCKETMQ_NAMESRV_HOST_PORT)"; echo "  RocketMQ namesrv 127.0.0.1:${v:-9876}"
    v="$(env_value ROCKETMQ_BROKER_HOST_PORT)";  echo "  RocketMQ broker  127.0.0.1:${v:-10911}"
    v="$(env_value ROCKETMQ_CONSOLE_HOST_PORT)"; echo "  RocketMQ 控制台  http://127.0.0.1:${v:-8088}"
    v="$(env_value XXLJOB_HOST_PORT)";     echo "  XXL-Job 控制台   http://127.0.0.1:${v:-9099}/"
    v="$(env_value SENTINEL_HOST_PORT)";   echo "  Sentinel 控制台  http://127.0.0.1:${v:-8858}（客户端上报同端口）"
}

cmd_up() {
    require_docker
    require_env_file

    local skip_schema_check=0 profiles=() p
    while [ $# -gt 0 ]; do
        case "$1" in
            --skip-schema-check) skip_schema_check=1 ;;
            all) profiles=("${VALID_PROFILES[@]}") ;;
            *)
                known_profile "$1" || die "未知档位 '$1'。可选：all ${VALID_PROFILES[*]}"
                profiles+=("$1")
                ;;
        esac
        shift
    done

    for p in "${profiles[@]}"; do
        if [ "$p" = "job" ] && [ "$skip_schema_check" -eq 0 ] && ! xxljob_schema_present; then
            die "档位 job 需要 XXL-Job 表结构：把与 BOM 属性 xuxueli/xxl-job-admin 同版本的发行包里 doc/db/tables_xxl_job.sql 复制为 myddd-devbox/seed/mysql/03-xxl-job.sql（MySQL 首次初始化时自动执行，该文件不入库）。表结构已用别的方式备好时改用 up job --skip-schema-check。"
        fi
    done

    local flags=()
    for p in "${profiles[@]}"; do
        flags+=(--profile "$p")
    done

    if [ ${#flags[@]} -gt 0 ]; then
        compose_run "${flags[@]}" up -d --wait
        compose_run "${flags[@]}" ps
    else
        compose_run up -d --wait
        compose_run ps
    fi
    print_endpoints
}

main() {
    local cmd="${1:-}"
    [ $# -gt 0 ] && shift
    case "$cmd" in
        up) cmd_up "$@" ;;
        ps)
            require_docker
            compose_run ps
            ;;
        logs)
            require_docker
            compose_run logs -f "$@"
            ;;
        down)
            require_docker
            compose_stop down --remove-orphans
            ;;
        reset)
            require_docker
            [ "${1:-}" = "--yes" ] || die "reset 会删除数据卷（MySQL 本地数据全丢），不可撤销。确认请加 --yes。"
            compose_stop down -v --remove-orphans
            ;;
        verify)
            bash "$DEVBOX_DIR/verify-images.sh"
            ;;
        "")
            usage
            exit 1
            ;;
        *)
            usage
            die "未知命令 '$cmd'"
            ;;
    esac
}

main "$@"
