import pathlib, sys
import xml.etree.ElementTree as ET

NS = "{http://maven.apache.org/POM/4.0.0}"
JACOCO = "jacoco-maven-plugin"
FLOOR_KERNEL, FLOOR_DEFAULT = 0.80, 0.60
errors = []

def name(e):
    return e.tag[len(NS):] if e.tag.startswith(NS) else e.tag

def text(e):
    return (e.text or "").strip()

def kids(e, tag):
    return [c for c in e if name(c) == tag]

def allof(e, tag):
    return [c for c in e.iter() if name(c) == tag]

def artifact_id(root):
    for c in root:
        if name(c) == "artifactId":
            return text(c)
    return ""

def as_ratio(where, raw):
    try:
        return float(raw)
    except ValueError:
        errors.append(f"{where}：阈值 {raw!r} 不是数字")
        return None

poms = sorted(p for p in pathlib.Path(".").rglob("pom.xml") if "target" not in p.parts)
if not poms:
    print("::error::未发现任何 pom.xml（守卫扫描范围失效）")
    sys.exit(1)
for p in poms:
    raw = p.read_text(encoding="utf-8")
    if "<!DOCTYPE" in raw or "<!ENTITY" in raw:
        print(f"::error::{p} 含 DOCTYPE/ENTITY 声明：拒绝解析（防实体膨胀/外部实体）")
        sys.exit(1)
roots = {p: ET.parse(p).getroot() for p in poms}

# 1) 内核模块：按 artifactId 前缀识别（不依赖目录位置），六个家族成员必须齐全
REQUIRED_KERNEL = {"myddd-kernel-common", "myddd-kernel-ddd", "myddd-kernel-port",
                   "myddd-kernel-extension", "myddd-kernel-statemachine", "myddd-kernel-rule"}
kernel = {p: (artifact_id(r), r) for p, r in roots.items()
          if artifact_id(r).startswith("myddd-kernel-") or "myddd-kernel" in p.parts}
missing = sorted(REQUIRED_KERNEL - {aid for _, (aid, _) in kernel.items()})
if missing:
    errors.append(f"缺少内核模块 {missing}：扫描范围失效（模块被删除或改名），内核门槛覆盖不全")

# 2) 阈值声明：内核 ≥0.80、其余 ≥0.60；内核模块必须显式声明，所有声明（含 profile 覆写）都要达标
for p, root in sorted(roots.items()):
    aid = artifact_id(root)
    is_kernel = p in kernel
    decls = [text(e) for e in allof(root, "myddd.jacoco.minimum")]
    if is_kernel and not decls:
        errors.append(f"{p}（{aid}）未声明 <myddd.jacoco.minimum>：门槛会静默继承父级默认值")
        continue
    for raw in decls:
        v = as_ratio(str(p), raw)
        if v is None:
            continue
        if is_kernel and v < FLOOR_KERNEL:
            errors.append(f"{p}（{aid}）的 myddd.jacoco.minimum={raw} 低于 {FLOOR_KERNEL:.2f}（含 profile 覆写在内，全部声明都必须达标）")
        elif not is_kernel and v < FLOOR_DEFAULT:
            errors.append(f"{p}（{aid}）的 myddd.jacoco.minimum={raw} 低于 {FLOOR_DEFAULT:.2f}（非内核模块不得自行调低门槛）")

# 3) 任何 POM 都不得用 <skip>/<haltOnFailure>/全吞 <exclude>/硬编码 <minimum> 绕开门槛
ALL_MATCHING = {"**", "**/*", "*", "**/*.class", "**/*.*"}
FORBIDDEN_PROPS = ("jacoco.skip", "jacoco.haltOnFailure", "myddd.jacoco.skip", "myddd.jacoco.haltOnFailure")
for p, root in sorted(roots.items()):
    for fp in FORBIDDEN_PROPS:
        if allof(root, fp):
            errors.append(f"{p} 声明了会关掉门槛的属性 <{fp}>")
    for pl in allof(root, "plugin"):
        if not any(name(c) == "artifactId" and text(c) == JACOCO for c in pl):
            continue
        for ex in allof(pl, "exclude"):
            if text(ex) in ALL_MATCHING:
                errors.append(f"{p} 的 JaCoCo <exclude>{text(ex)}</exclude> 会排除全部类（门槛形同虚设）")
        for sk in allof(pl, "skip"):
            if text(sk).lower() != "false":
                errors.append(f"{p} 的 JaCoCo <skip>{text(sk)}</skip> 会跳过门槛（只允许写 false 或删除该元素）")
        for hn in allof(pl, "haltOnFailure"):
            if text(hn).lower() != "true":
                errors.append(f"{p} 的 JaCoCo <haltOnFailure>{text(hn)}</haltOnFailure> 会把门槛失败降级为告警")
        for inh in allof(pl, "inherited"):
            if text(inh).lower() == "false":
                errors.append(f"{p} 的 JaCoCo <inherited>false</inherited> 会让子模块失去门槛")
        for ph in allof(pl, "phase"):
            if text(ph).lower() in ("none", ""):
                errors.append(f"{p} 的 JaCoCo <phase>{text(ph)}</phase> 把门槛执行摘出了构建生命周期")
        for m in allof(pl, "minimum"):
            if not text(m).startswith("${"):
                errors.append(f"{p} 出现硬编码门槛 <minimum>{text(m)}</minimum>：必须引用属性")
        if p != pathlib.Path("pom.xml") and allof(pl, "rules"):
            errors.append(f"{p} 覆写了 JaCoCo check 规则（<rules>）：模块只能通过 myddd.jacoco.minimum 属性调阈值，规则结构由父 POM 统一")

# 4) 父 POM 的 check 执行必须同时约束 LINE 与 BRANCH 的 COVEREDRATIO，且阈值走属性
root_pom = roots[pathlib.Path("pom.xml")]
jacoco_plugins = [pl for pl in allof(root_pom, "plugin")
                  if any(name(c) == "artifactId" and text(c) == JACOCO for c in pl)]
if len(jacoco_plugins) != 1:
    errors.append(f"父 POM 中 JaCoCo 插件声明数 = {len(jacoco_plugins)}（期望 1）")
else:
    checks = [ex for ex in allof(jacoco_plugins[0], "execution")
              if any(name(c) == "id" and text(c) == "check" for c in ex)]
    if len(checks) != 1:
        errors.append(f"父 POM 中 JaCoCo check 执行数 = {len(checks)}（期望 1）")
    else:
        ce = checks[0]
        if "check" not in [text(g) for g in allof(ce, "goal")]:
            errors.append("父 POM 的 check 执行未绑定 check 目标")
        if [text(e) for e in allof(ce, "element")] != ["BUNDLE"]:
            errors.append("父 POM 的 check 规则 element 必须是 BUNDLE")
        limits = {}
        for lim in allof(ce, "limit"):
            cs = [text(x) for x in kids(lim, "counter")]
            vs = [text(x) for x in kids(lim, "value")]
            ms = [text(x) for x in kids(lim, "minimum")]
            if len(cs) != 1:
                errors.append("父 POM 存在无 counter（或 counter 重复）的 limit")
                continue
            limits[cs[0]] = (vs[0] if len(vs) == 1 else "", ms[0] if len(ms) == 1 else "")
        for counter in ("LINE", "BRANCH"):
            if counter not in limits:
                errors.append(f"父 POM 的 check 规则缺少 {counter} 门槛（该项被删除即失守）")
                continue
            value, minimum = limits[counter]
            if value != "COVEREDRATIO":
                errors.append(f"父 POM 的 {counter} 门槛 value={value or '缺失'}（必须是 COVEREDRATIO，否则门槛无效）")
            if minimum != "${myddd.jacoco.minimum}":
                errors.append(f"父 POM 的 {counter} 门槛 minimum={minimum or '缺失'}（必须引用 ${{myddd.jacoco.minimum}}）")
        for counter in limits:
            if counter not in ("LINE", "BRANCH"):
                errors.append(f"父 POM 的 check 规则含意外 counter：{counter}")

if errors:
    for e in errors:
        print(f"::error::{e}")
    sys.exit(1)
print(f"覆盖率门槛值守卫通过：内核模块 {len(kernel)} 个阈值均 ≥ {FLOOR_KERNEL:.2f}，父 POM 的 LINE/BRANCH 双门槛均走属性，无硬编码/跳过/全排除")
