#!/usr/bin/env bash
# devbox compose 防漂移守卫（无需 Docker）。盯的是"改 compose 时最容易悄悄走样"的四件事：
#   1) 凭据出现明文（含 PARAMS 块里的 --xxx.password=）——一律必须由 ${VAR} 从 .env 注入；
#   2) compose 新引用了 .env.example 没登记的变量——开发机照示例文件复制出的 .env 会缺键；
#   3) 宿主端口写死成字面量——多工程共用一台机器时改不动，且 .env 的 *_HOST_PORT 形同虚设；
#   4) depends_on 指向已被改名/删除的服务——`docker compose up` 到运行期才炸。
# 与 verify-images.sh 的分工：那个管"镜像 tag ↔ 父 POM 真源"，本脚本管"编排文本自身的卫生"。
# 用法：bash myddd-devbox/check-compose.sh
set -euo pipefail

DEVBOX_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="${MYDDD_DEVBOX_COMPOSE:-$DEVBOX_DIR/compose.yaml}"
ENV_EXAMPLE="${MYDDD_DEVBOX_ENV_EXAMPLE:-$DEVBOX_DIR/.env.example}"

fail() {
    printf '[check-compose] 不一致：%s\n' "$*" >&2
    exit 1
}

for f in "$COMPOSE" "$ENV_EXAMPLE"; do
    [ -f "$f" ] || fail "缺少文件 $f"
done

# ---------- 1. 顶层键白名单：只允许 name / services / volumes ----------
top_keys="$(grep -E '^[A-Za-z_][A-Za-z0-9_-]*:' "$COMPOSE" | cut -d: -f1 | sort -u | tr '\n' ' ' || true)"
[ -n "$top_keys" ] || fail "compose.yaml 一个顶层键都没解析到（文件被清空或解析式失效，不许把空集当通过）"
for key in $top_keys; do
    case " name services volumes " in
        *" $key "*) ;;
        *) fail "顶层键 $key 不在白名单 name/services/volumes 内（devbox 正本不分版本字段、不放 x-anchors 之类的额外顶层结构）" ;;
    esac
done

# ---------- 2. 一次结构化扫描：服务名、depends_on 目标、ports 条目 ----------
# 按缩进推进上下文（不猜固定缩进宽度），因此新增服务/换键序都不需要改这里。
rows="$(awk '
    function indent(s) { match(s, /^ */); return RLENGTH }
    function keyname(s) { v = s; sub(/^[ ]+/, "", v); sub(/:.*/, "", v); return v }
    function payload(s) { v = s; sub(/^[^:]+:[ ]*/, "", v); gsub(/[ "]*/, "", v); return v }
    function ends_ctx() { ctx = ""; ctx_ind = -1 }
    $0 ~ /^[^ \t#]/ && $0 !~ /^[ \t]*$/ {          # 顶层键行
        insvc = ($0 ~ /^services:[ \t]*$/)
        svc = ""; ends_ctx()
        next
    }
    !insvc || $0 ~ /^[ \t]*#/ || $0 ~ /^[ \t]*$/ { next }
    indent($0) == 2 && $0 ~ /^[ \t]+[A-Za-z0-9._-]+:[ \t]*$/ {   # 服务名
        svc = keyname($0); printf "S|%s\n", svc; ends_ctx(); next
    }
    $0 ~ /^[ \t]+(depends_on|ports|environment|labels|healthcheck|command|volumes|profiles|image)[ \t]*:/ {
        k = keyname($0)
        if (k == "depends_on" || k == "ports") { ctx = k; ctx_ind = indent($0) } else { ends_ctx() }
        next
    }
    ctx != "" && indent($0) <= ctx_ind { ends_ctx() }
    ctx == "depends_on" && indent($0) > ctx_ind {
        if ($0 ~ /^[ \t]+[A-Za-z0-9._-]+:[ \t]*$/) { printf "D|%s\n", keyname($0) }
        else if ($0 ~ /^[ \t]+-[ \t]*[A-Za-z0-9._-]+[ \t]*$/) {
            v = $0; sub(/^[ \t]+-[ \t]*/, "", v); gsub(/[ \t]+$/, "", v); printf "D|%s\n", v
        }
        next
    }
    ctx == "ports" && indent($0) > ctx_ind && $0 ~ /^[ \t]+-/ {
        v = $0; sub(/^[ \t]+-[ \t]*/, "", v); gsub(/["\047]/, "", v); gsub(/[ \t]+$/, "", v)
        printf "P|%s\n", v
        next
    }
    { svc = svc }
' "$COMPOSE")"

services="$(printf '%s\n' "$rows" | sed -n 's/^S|//p')"
service_count="$(printf '%s\n' "$services" | grep -c . || true)"
[ "$service_count" -gt 0 ] || fail "compose.yaml 里一个服务都没解析到（解析式已失效，不许把空集当通过）"

depends="$(printf '%s\n' "$rows" | sed -n 's/^D|//p')"
dep_count="$(printf '%s\n' "$depends" | grep -c . || true)"
[ "$dep_count" -gt 0 ] \
    || fail "compose.yaml 一条 depends_on 都没解析到：依赖关系（broker 等 namesrv、xxljob 等 mysql）被整体删掉时本守卫会静默放行"
while IFS= read -r dep; do
    [ -n "$dep" ] || continue
    case "
$services
" in
        *"
$dep
"*) ;;
        *) fail "depends_on 指向不存在的服务 $dep（改名或删除服务时忘了同步引用，运行期才炸）" ;;
    esac
done <<<"$depends"

# ---------- 3. 宿主端口必须是 ${VAR:-默认} 形态 ----------
port_rows="$(printf '%s\n' "$rows" | sed -n 's/^P|//p')"
port_count="$(printf '%s\n' "$port_rows" | grep -c . || true)"
[ "$port_count" -gt 0 ] || fail "compose.yaml 一条 ports 都没解析到（解析式已失效，端口形态检查会静默放行）"
while IFS= read -r row; do
    [ -n "$row" ] || continue
    host="${row%:*}"          # 最短后缀切割：形如 ${VAR:-3306} 的宿主侧才能被 .env 覆盖
    [[ "$host" =~ ^\$\{[A-Z0-9_]+:-[0-9]+\}$ ]] \
        || fail "端口映射 '$row' 的宿主侧不是 \${VAR:-数字} 形态：宿主端口必须可由 .env 覆盖"
done <<<"$port_rows"

# ---------- 4. 凭据零明文 ----------
# 4a 是 environment 映射里的凭据键；4b 兜住 PARAM 这类块标量里的 --xxx.password= 形态。
secret_re='passwd|password|secret|token|access[-_]?key|access[-_]?token|credential'
secret_rows="$(awk -v re="$secret_re" '
    { line = $0; low = tolower(line) }
    # 4a：缩进的 KEY: value 行，键名命中凭据字样
    line ~ /^[ \t]+[A-Za-z0-9_]+:[ \t]*[^ \t]/ {
        k = line; sub(/^[ \t]+/, "", k); sub(/:.*/, "", k)
        v = line; sub(/^[ \t]+[A-Za-z0-9_]+:[ \t]*/, "", v); sub(/[ \t]+$/, "", v)
        if (tolower(k) ~ re && v !~ /\$\{/ && v != ">-") print "环境键 " k " 是明文值"
    }
    # 4b：任意行里的 --xxx.password=VALUE / access-token=VALUE
    low ~ /(--|&)[a-z.]*(passwd|password|secret|token|access[-_]?key|access[-_]?token)[a-z.]*=/ {
        v = line; sub(/.*=/, "", v); sub(/[ \t]+$/, "", v)
        if (v !~ /\$\{/) print "参数行明文：" line
    }
' "$COMPOSE")"
[ -z "$secret_rows" ] || fail "compose.yaml 出现明文凭据（凭据一律 \${VAR} 从 .env 注入）：$(printf '%s' "$secret_rows" | tr '\n' ' | ')"
secret_hits="$(grep -icE "$secret_re" "$COMPOSE" || true)"
[ "$secret_hits" -gt 0 ] || fail "compose.yaml 里一个凭据字样都没扫到：扫描式已失效，不许把空集当通过"

# ---------- 5. compose 引用的变量必须全部在 .env.example 登记 ----------
# 每一处 $(grep …) 都要 || true：set -euo pipefail 下无匹配会让赋值静默退出 1，
# 于是"空集不许当通过"的检查根本没机会说话——崩得没有诊断比放行更难查。
# 注释行必须先剔掉再扫：compose 里的注释会举例如真实 entrypoint（`${LOG_HOME:+...}`），
# 那是说明文字不是接线，算成引用就会要求 .env.example 登记一个永不被使用的键。
referenced="$(sed '/^[ \t]*#/d' "$COMPOSE" | grep -oE '\$\{[A-Z0-9_]+' | cut -c 3- | sort -u || true)"
ref_count="$(printf '%s\n' "$referenced" | grep -c . || true)"
[ "$ref_count" -gt 0 ] || fail "compose.yaml 一个 \${VAR} 引用都没解析到：扫描式已失效，不许把空集当通过"
env_keys="$(grep -oE '^[A-Z0-9_]+=' "$ENV_EXAMPLE" | sed 's/=$//' | sort -u || true)"
key_count="$(printf '%s\n' "$env_keys" | grep -c . || true)"
[ "$key_count" -gt 0 ] || fail ".env.example 一个键都没解析到（示例文件被清空即等于没有前置说明）"
stray=""
while IFS= read -r var; do
    [ -n "$var" ] || continue
    case "
$env_keys
" in
        *"
$var
"*) ;;
        *) stray="$stray$var," ;;
    esac
done <<<"$referenced"
[ -z "$stray" ] || fail "compose 引用的 ${stray%,} 未在 .env.example 登记：照示例复制出的 .env 缺这些键，起服务时才报未设置"

# ---------- 6. 示例文件必须把口令位留空 ----------
root_line="$(grep -E '^MYSQL_ROOT_PASSWORD=' "$ENV_EXAMPLE" || true)"
[ -n "$root_line" ] || fail ".env.example 缺少 MYSQL_ROOT_PASSWORD 这一行：示例文件必须显式给出这个必填位（值留空）"
[ "$root_line" = "MYSQL_ROOT_PASSWORD=" ] || fail ".env.example 的 MYSQL_ROOT_PASSWORD 必须留空（当前 '$root_line'）：示例文件不得携带口令值"

echo "[check-compose] 通过：顶层键白名单、服务 $service_count 个与依赖 $dep_count 条自洽、端口 $port_count 条可覆盖、凭据 $secret_hits 行含凭据字样且全为注入、引用变量 $ref_count 个全在 .env.example 登记"
