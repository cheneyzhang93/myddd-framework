#!/usr/bin/env bash
# devbox 镜像 tag 防漂移守卫。唯一真源 = 父 POM 的 myddd.devbox.image.*；
# compose.yaml（image + labels.myddd.devbox.image-key）与 test-starter 的模板各消费它一次。
# 本脚本不需要 Docker：bash myddd-devbox/verify-images.sh
# 有 Docker 时加 --docker，逐个去重 tag 向注册表核验存在性。
set -euo pipefail

DEVBOX_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
POM="${MYDDD_DEVBOX_POM:-$DEVBOX_DIR/../pom.xml}"
COMPOSE="${MYDDD_DEVBOX_COMPOSE:-$DEVBOX_DIR/compose.yaml}"
IMAGE_TEMPLATE="${MYDDD_DEVBOX_PROPERTIES:-$DEVBOX_DIR/../myddd-test-starter/src/main/resources/myddd-devbox-images.properties}"

fail() {
    printf '[verify-images] 不一致：%s\n' "$*" >&2
    exit 1
}

for f in "$POM" "$COMPOSE" "$IMAGE_TEMPLATE"; do
    [ -f "$f" ] || fail "缺少文件 $f"
done

# ---------- 1. 父 POM：KEY=TAG 对 ----------
pom_open_tags="$(grep -c '<myddd\.devbox\.image\.' "$POM" || true)"
pom_pairs="$(sed -n -E 's|.*<myddd\.devbox\.image\.([a-z0-9_-]+)>([^<]*)</myddd\.devbox\.image\.[a-z0-9_-]+>.*|\1=\2|p' "$POM")"
pom_parsed="$(printf '%s\n' "$pom_pairs" | grep -c . || true)"
[ "$pom_parsed" -gt 0 ] || fail "POM 里一条 myddd.devbox.image.* 都没解析到（解析式已失效，不许把空集当通过）"
[ "$pom_parsed" -eq "$pom_open_tags" ] \
    || fail "POM 有 $pom_open_tags 个 myddd.devbox.image.* 开标签，只解析出 $pom_parsed 对（多半是值换行或为空）"

pom_value() {
    printf '%s\n' "$pom_pairs" | sed -n -E "s|^$1=||p"
}

while IFS='=' read -r key val; do
    [ -n "$val" ] || fail "POM 属性 myddd.devbox.image.$key 的值为空"
    case "$val" in
        *:*) ;;
        *) fail "POM 属性 myddd.devbox.image.$key 的值 '$val' 不是 <镜像>:<tag> 形态" ;;
    esac
done <<<"$pom_pairs"

# ---------- 2. compose：每个服务声明它消费的 POM 键，且 image 必须等于该键的真源值 ----------
compose_rows="$(awk '
    function flush() { if (svc != "") printf "%s|%s|%s\n", svc, key, img }
    $0 ~ /^services:[ \t]*$/ { insvc = 1; next }
    insvc && $0 ~ /^[^ \t#]/ { flush(); svc = ""; insvc = 0; next }
    insvc && $0 ~ /^  [A-Za-z0-9._-]+:[ \t]*$/ {
        flush(); svc = $0; sub(/^  /, "", svc); sub(/:[ \t]*$/, "", svc); key = ""; img = ""; next
    }
    insvc && svc != "" && $0 ~ /^[ \t]+image:[ \t]*/ {
        v = $0; sub(/^[ \t]+image:[ \t]*/, "", v); gsub(/["\047 ]/, "", v); img = v; next
    }
    insvc && svc != "" && $0 ~ /myddd\.devbox\.image-key:/ {
        v = $0; sub(/.*image-key:[ \t]*/, "", v); gsub(/["\047 ]/, "", v); key = v; next
    }
    END { flush() }
' "$COMPOSE")"

service_count=0
image_list=""
consumed_keys=" "
while IFS='|' read -r svc key img; do
    [ -n "$svc" ] || continue
    service_count=$((service_count + 1))
    [ -n "$key" ] || fail "服务 $svc 没有 labels.myddd.devbox.image-key：新服务必须声明它消费的 POM 属性"
    [ -n "$img" ] || fail "服务 $svc 没有 image 行"
    pv="$(pom_value "$key")"
    [ -n "$pv" ] || fail "服务 $svc 声明的键 myddd.devbox.image.$key 在 POM 里不存在"
    [ "$img" = "$pv" ] || fail "服务 $svc 的 image '$img' ≠ POM 属性 myddd.devbox.image.$key 的 '$pv'（真源与消费侧必须同改）"
    image_list="$image_list$img
"
    consumed_keys="$consumed_keys$key "
done <<<"$compose_rows"
[ "$service_count" -gt 0 ] || fail "compose.yaml 里一个服务都没解析到（解析式已失效，不许把空集当通过）"

# ---------- 3. 真源侧：每个 POM 属性都得被至少一个服务消费 ----------
missing=""
while IFS='=' read -r key _; do
    case "$consumed_keys" in
        *" $key "*) ;;
        *) missing="$missing$key," ;;
    esac
done <<<"$pom_pairs"
[ -z "$missing" ] || fail "POM 属性 ${missing%,} 已无 compose 服务消费（真源与消费侧集合不等）"

# ---------- 4. test-starter 模板：只许占位符，不许写死值 ----------
bad_template="$(grep -vE '^[a-z0-9_-]+=@myddd\.devbox\.image\.[a-z0-9_-]+@$' "$IMAGE_TEMPLATE" | grep -vE '^[[:space:]]*(#.*)?$' || true)"
[ -z "$bad_template" ] \
    || fail "模板 myddd-devbox-images.properties 有非占位符行：$(printf '%s' "$bad_template" | tr '\n' ' | ')"

template_keys="$(grep -E '^[a-z0-9_-]+=@myddd\.devbox\.image\.' "$IMAGE_TEMPLATE" | cut -d= -f1 || true)"
template_count=0
while IFS= read -r key; do
    [ -n "$key" ] || continue
    template_count=$((template_count + 1))
    [ -n "$(pom_value "$key")" ] \
        || fail "模板键 $key 在 POM 里没有对应属性 myddd.devbox.image.$key"
done <<<"$template_keys"
[ "$template_count" -gt 0 ] || fail "模板一条键都没解析到（解析式已失效，不许把空集当通过）"

echo "[verify-images] 通过：POM 属性 $pom_parsed 个 ↔ compose 服务 $service_count 个逐一对齐，模板键 $template_count 个全为占位符"

if [ "${1:-}" = "--docker" ]; then
    command -v docker >/dev/null 2>&1 || fail "--docker 需要 Docker，而本机没有 docker 命令"
    distinct="$(printf '%s' "$image_list" | grep . | sort -u)"
    # 三级探测，且把"tag 不存在"与"核验不到"分开说：只查注册表会在配了镜像源的机器上把存在的 tag 误报成不存在
    # （实测：`docker manifest inspect` 不走 registry-mirrors，镜像源可达而直连 docker.io 超时的机器上，7 个 tag 全红）。
    # 结论写进 probe_state：存在 / 不存在 / 不可达（探测函数一律返回 0，避免 set -e 把非零状态当致命错误）。
    probe_tag() {
        local tag="$1" out
        if docker image inspect "$tag" >/dev/null 2>&1; then probe_state=存在; probe_via=本地已在; return 0; fi
        if docker manifest inspect "$tag" >/dev/null 2>&1; then probe_state=存在; probe_via=注册表; return 0; fi
        if out="$(docker pull "$tag" 2>&1)"; then probe_state=存在; probe_via=镜像源; return 0; fi
        case "$out" in
            *"not found"*|*"not known"*|*"manifest unknown"*|*"no such image"*) probe_state=不存在; return 0 ;;
        esac
        probe_state=不可达
        return 0
    }
    checked=0
    unverifiable=""
    while IFS= read -r tag; do
        [ -n "$tag" ] || continue
        probe_tag "$tag"
        case "$probe_state" in
            存在) checked=$((checked + 1)); echo "  存在：$tag（$probe_via）" ;;
            不存在) fail "注册表与镜像源都回答『这个 tag 不存在』：$tag" ;;
            *) unverifiable="$unverifiable$tag
" ;;
        esac
    done <<<"$distinct"
    [ -z "$unverifiable" ] || fail "有 $(printf '%s' "$unverifiable" | grep -c .) 个 tag 核验不到（注册表与镜像源均不可达，不是 tag 不存在）：$(printf '%s' "$unverifiable" | tr '\n' ' ')——请在能连注册表的机器或 CI 上重跑 --docker"
    echo "[verify-images] --docker：$checked 个去重 tag 全部可核验存在"
fi
