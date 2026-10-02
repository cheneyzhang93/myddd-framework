#!/bin/bash
# 由 mysql 镜像入口以 root 身份在容器内执行（只在数据卷为空时跑一次）。
# 口令一律来自 .env 注入的环境变量，本文件不出现任何口令字面量。
set -euo pipefail

if [ -z "${MYDDD_DB_PASSWORD:-}" ]; then
    echo "[devbox] 未设置 MYDDD_DB_PASSWORD：跳过本地应用账号创建，用 root 连接即可" >&2
    exit 0
fi

run() {
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "$1"
}

run "CREATE USER IF NOT EXISTS 'myddd'@'%' IDENTIFIED BY '$MYDDD_DB_PASSWORD'"
for db in myddd_app myddd_app_test xxl_job; do
    run "GRANT ALL PRIVILEGES ON ${db}.* TO 'myddd'@'%'"
done
run "FLUSH PRIVILEGES"
