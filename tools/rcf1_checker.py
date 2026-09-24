# -*- coding: utf-8 -*-
"""MC-RCF-1-R3C 最终 checker(唯一验收入口;R3C 收口重写)。

原则(继承 R3 F01,对照 R3C TASKBOOK C1-C4):
- 只从原始事实(完整回执 + 动作级前后观察快照 + 运行身份)派生结论;
  不接受任何自报 true/verdict/tools_ok/dawn/PASS 字段作为证明。
- I/A 守恒逐动作核验(pre_action/post_action):准备期加物与动作
  效果不混入同一守恒区间;case 中途 fixture 调整不得冒充 move 效果。
- 单一计分身份:同一 execution_id 重复出现在事实文件 → 拒绝。
- 缺失与不确定必须可区分:缺快照≠空包,缺 screen≠空光标,缺
  status≠无未决执行,查询失败≠完整围护;缺字段/错误/过期/冲突
  终态/未解决 unknown 一律拒绝。
- G5 从原始方块/世界时间/生命/位置/库存重算围护、连续性、清晨
  工具与后续采集;model_channel 字符串本身不构成模型决策证明,
  但技能子步必须以 skill 通道标注,不得冒充 LLM 调用。
- 变异/篡改输入(格式正确、单因素改动)必须被同一入口拒绝,
  且拒绝原因与变异相关(ACCEPTANCE §6)。

CLI:
  python tools/rcf1_checker.py judge-ia  <ia-facts.json>
  python tools/rcf1_checker.py judge-g4  <g4-runs.json>
  python tools/rcf1_checker.py judge-g5  <g5-facts.json> <run_id>
  python tools/rcf1_checker.py judge-all <manifest.json>
"""
import json
import re
import sys

IA_SCHEMA = "mc.rcf1r3c.facts.v2"
G4_SCHEMA = "mc.rcf1r3c.g4run.v1"
G5_ALLOWED_OPS = ("goto", "mine_opportunity", "craft", "eat", "place",
                  "deposit", "say")
G5_SKILL_CHANNEL_PREFIX = "skill"

# 必测集合(R3C:I02 拆分为 I02a-d,TASKBOOK B1)
IA_REQUIRED = {
    # id: (需要正例completed+物理效果, 需要负例failed+诚实无副作用)
    "I01": (True, False), "I02a": (True, False), "I02b": (True, False),
    "I02c": (True, False), "I02d": (True, False),
    "I03": (True, False), "I04": (True, False),
    "I05": (False, True), "I06": (False, True),
    "I07": (False, True), "I08": (True, False),
    "A01": (True, False), "A02": (True, False), "A03": (False, True),
    "A04": (True, False), "A05": (False, True), "A06": (True, False),
    "A07": (False, True), "A08": (True, True), "A09": (False, True),
    "A10": (False, True), "A11": (False, True), "A12": (False, True),
    "V01": (False, True), "V02": (True, True), "V04": (False, True),
}

TERMINAL_OK = ("completed", "failed", "cancelled", "rejected")

# 用例级正例操作类别:该 case 的"正例"必须由指定 op 类的动作证明,
# 不得被辅助动作(goto 等导航)冒充(实测 I08 被 goto 顶替的漏洞)。
IA_POSITIVE_OP = {
    "I08": ("deposit", "withdraw", "container_transfer"),
}

# G4 受控门槛(不可被 evidence 的 expect 下调;M09)
G4_DEFAULT_LOGS_NEED = 5
G4_DEFAULT_STONE_NEED = 3
G4_DEFAULT_CRAFTS = {"minecraft:stick": 8, "minecraft:crafting_table": 1,
                     "minecraft:wooden_pickaxe": 2,
                     "minecraft:stone_pickaxe": 1}

# craft 取消的配方折算守恒(材料/产物/中间态任一组合)
CRAFT_FOLD = {
    "minecraft:oak_planks": [("minecraft:oak_log", 4)],
    "minecraft:birch_planks": [("minecraft:birch_log", 4)],
}

# 与 rcf1_shelter 同源的黑名单(checker 自含副本,不 import 生产模块)
_UNRELIABLE_SUBSTR = (
    "grass", "flower", "sapling", "fern", "bush", "torch", "water",
    "lava", "snow_layer", "carpet", "rail", "sign", "button",
    "pressure_plate", "tripwire", "string", "dead_bush", "mushroom",
    "roots", "vine", "lily", "seagrass", "kelp", "cobweb", "bamboo",
    "sugar", "wheat", "campfire", "lantern", "chain", "fence_gate",
    "trapdoor")

_RX_EAT = re.compile(
    r"server_authoritative_food_consumed:([a-z0-9_.]+:[a-z0-9_.]+)"
    r":(\d+)->(\d+)"
    r":claimed=(\d+):witness=(\d+):hunger:(\d+)->(\d+)")
_RX_CRAFT = re.compile(
    r"server_authoritative_native_craft:([a-z0-9_.]+:[a-z0-9_.]+)"
    r":(\d+)->(\d+):delta=(\d+)")
_RX_MOVE = re.compile(
    r"server_authoritative_items_moved:([a-z0-9_.]+:[a-z0-9_.]+)"
    r":hotbar=(\d+)"
    r":baseline=(\d+):after=(\d+):gained=(\d+)")
_RX_CONT = re.compile(
    r"server_authoritative_container_transfer:"
    r"([a-z0-9_.]+:[a-z0-9_.]+):(deposit|withdraw)"
    r":player:(\d+)->(\d+):container:(\d+)->(\d+)")
_RX_GAIN = re.compile(
    r"server_authoritative_block_and_inventory_gain_verified:(\d+)->(\d+)")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_RX_CRAFT_MATERIALS = re.compile(
    r"materials=([a-z0-9_.,: ->]+?)(?:,\s*[a-z0-9_]+[:=]|$)")

# R3D/F05:围护判定改为白名单——不在已知实体/开口集合内的名字按
# unknown(未证)处理,不再默认"不在黑名单里就是坚固围护"。
_SOLID_EXACT = {
    "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt",
    "minecraft:grass_block", "minecraft:oak_log", "minecraft:birch_log",
    "minecraft:spruce_log", "minecraft:jungle_log", "minecraft:acacia_log",
    "minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log",
    "minecraft:oak_planks", "minecraft:birch_planks",
    "minecraft:spruce_planks", "minecraft:jungle_planks",
    "minecraft:acacia_planks", "minecraft:dark_oak_planks",
    "minecraft:deepslate", "minecraft:granite", "minecraft:diorite",
    "minecraft:andesite", "minecraft:tuff", "minecraft:smooth_stone",
    "minecraft:stone_bricks", "minecraft:bricks", "minecraft:sandstone",
    "minecraft:dirt_path", "minecraft:podzol", "minecraft:coarse_dirt",
    "minecraft:rooted_dirt", "minecraft:mud", "minecraft:packed_mud",
    "minecraft:deepslate_bricks", "minecraft:polished_blackstone",
    "minecraft:cobbled_deepslate", "minecraft:obsidian",
    "minecraft:crying_obsidian", "minecraft:netherrack",
    "minecraft:end_stone", "minecraft:calcite", "minecraft:tuff",
    "stone", "cobblestone", "dirt", "grass_block",
}
_AIR_EXACT = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air",
              "air", "cave_air", "void_air"}


def _oracle_passed(result):
    """R3D/F05:oracle 结果严格解析——'not passed'/'failed'/空串必须拒。"""
    s = str(result or "").replace("\x00", "").strip().lower()
    if not s or "fail" in s or "not passed" in s:
        return False
    return s == "passed" or s.endswith("test passed") or \
        s.endswith("passed")


def _craft_material_items(reason):
    """从 craft 回执 materials= 段解析被合法消耗的材料物品集合。"""
    m = _RX_CRAFT_MATERIALS.search(str(reason or ""))
    if not m:
        return set()
    seg = m.group(1)
    return set(re.findall(r"([a-z0-9_.]+:[a-z0-9_.]+)", seg))


def _status_fields(status_fact):
    """status 事实可能是完整响应(含 data 外壳)或已解包字段。"""
    if not isinstance(status_fact, dict):
        return {}
    if isinstance(status_fact.get("data"), dict):
        return status_fact["data"]
    return status_fact


def _inv(snapshot):
    """observe 快照 → {item: count}(checker 自含实现,不 import 采集层)。

    查询失败返回 None:缺失/错误 ≠ 空包(C1)。
    """
    if not isinstance(snapshot, dict):
        return None
    if snapshot.get("error"):
        return None
    if "inventory" not in snapshot:
        return None   # R3D/F01:库存键缺失 ≠ 空库存
    inv = snapshot.get("inventory") or {}
    out = {}
    if isinstance(inv, dict):
        for k, v in inv.items():
            if v:
                out[k] = out.get(k, 0) + int(v)
    elif isinstance(inv, list):
        for entry in inv:
            if isinstance(entry, dict) and entry.get("id"):
                out[entry["id"]] = out.get(entry["id"], 0) + int(
                    entry.get("count") or 1)
    return out


def _receipt_of(action):
    return action.get("terminal") or {}


def _state_of(action):
    return str(_receipt_of(action).get("state") or "").lower()


def _reason_of(action):
    return str(_receipt_of(action).get("reason") or "")


def _action_inv(action, key):
    """动作级快照库存;缺失/错误返回 None(缺失≠空包)。"""
    snap = action.get(key)
    if snap is None or not isinstance(snap, dict):
        return None
    if snap.get("error"):
        return None
    return _inv(snap)


def _minus_others(pre, post, item):
    """除指定物品外的其余库存是否完全一致(保护项核验)。"""
    if pre is None or post is None:
        return None
    a = {k: v for k, v in pre.items() if k != item}
    b = {k: v for k, v in post.items() if k != item}
    return a == b


# ---------- IA/A:单动作物理效果判定 ----------

def _positive_proven(case, action):
    """completed 回执 + 按操作类型的动作级物理效果重算。"""
    if _state_of(action) != "completed":
        return None
    op = action.get("op")
    args = action.get("args") or {}
    pre = _action_inv(action, "pre_action")
    post = _action_inv(action, "post_action")
    reason = _reason_of(action)
    if pre is None or post is None:
        return "action-snapshots-missing-or-errored"
    if op == "craft":
        m = _RX_CRAFT.search(reason)
        item = str(args.get("item") or "")
        want = int(args.get("count") or 0)
        if not m or m.group(1) != item:
            return "craft-receipt-format-or-item-mismatch"
        if int(m.group(4)) < want:
            return "craft-delta-below-request"
        if post.get(item, 0) - pre.get(item, 0) < want:
            return "craft-snapshot-delta-below-request"
        return True
    if op == "eat":
        m = _RX_EAT.search(reason)
        if not m:
            return "eat-receipt-missing-witness-format"
        item, before, after, claimed, witness = (
            m.group(1), int(m.group(2)), int(m.group(3)),
            int(m.group(4)), int(m.group(5)))
        if witness < claimed or claimed < 1:
            return "eat-witness-below-claim"
        if after != before - claimed:
            return "eat-count-inconsistent"
        if pre.get(item, 0) - post.get(item, 0) < claimed:
            return "eat-snapshot-delta-below-claim"
        return True
    if op == "move_items":
        m = _RX_MOVE.search(reason)
        item = str(args.get("item") or "")
        want = int(args.get("count") or 0)
        baseline, after, gained = (int(m.group(3)), int(m.group(4)),
                                   int(m.group(5)))
        want_hotbar = args.get("hotbar")
        if isinstance(want_hotbar, int) and \
                int(m.group(2)) != want_hotbar:
            return "move-receipt-hotbar-not-requested"
        if want == -1:
            # 整堆语义:源堆全量迁移(不可堆叠物=1 件也算);
            # 目标槽增量自洽 + 总量守恒(下方)共同证明。
            if gained < 1 or after != baseline + gained:
                return "move-full-stack-mismatch"
        else:
            if gained != want or after != baseline + gained:
                return "move-gained-not-requested"
        if post.get(item, 0) != pre.get(item, 0):
            return "move-total-count-not-conserved"
        if _minus_others(pre, post, item) is not True:
            return "move-other-items-changed"
        return True
    if op in ("deposit", "withdraw", "container_transfer"):
        m = _RX_CONT.search(reason)
        if not m:
            return "container-receipt-format-mismatch"
        p0, p1, c0, c1 = (int(m.group(3)), int(m.group(4)),
                          int(m.group(5)), int(m.group(6)))
        # R3D/F03:请求方向必须与回执方向一致(请求取出实际存入=拒)
        req_dir = str(args.get("direction") or op)
        rc_dir = m.group(2)
        if req_dir not in ("deposit", "withdraw"):
            req_dir = "deposit" if req_dir in ("deposit",) else \
                ("withdraw" if req_dir == "withdraw" else req_dir)
        if req_dir != rc_dir:
            return "container-direction-not-requested:%s!=%s" % (
                req_dir, rc_dir)
        if (p1 - p0) != -(c1 - c0):
            return "container-conservation-violated"
        item = str(args.get("item") or "")
        if not item:
            return "container-args-missing-item"
        moved_player = p1 - p0
        if moved_player == 0:
            return "container-completed-zero-transfer"
        actual = post.get(item, 0) - pre.get(item, 0)
        if actual != moved_player:
            return "container-player-side-mismatch"
        want = int(args.get("count") or 0)
        if want > 0 and abs(moved_player) < want:
            return "container-moved-below-request:%d<%d" % (
                abs(moved_player), want)
        return True
    if op == "mine_opportunity":
        m = _RX_GAIN.search(reason)
        if not m or int(m.group(2)) - int(m.group(1)) < 1:
            return "mine-no-inventory-gain"
        if sum(post.values()) - sum(pre.values()) < 1:
            return "mine-snapshot-no-gain"
        return True
    if op == "place":
        if "interaction_witnessed=true" not in reason:
            return "place-not-witnessed"
        item = str(args.get("item") or "")
        if item and pre.get(item, 0) - post.get(item, 0) < 1:
            return "place-item-not-consumed"
        oracle = case.get("oracle_blocks") or {}
        try:
            tgt = (int(args.get("x")), int(args.get("y")),
                   int(args.get("z")))
        except (TypeError, ValueError):
            return "place-args-missing-target"
        hit = [v for k, v in oracle.items()
               if k == "table@%d,%d,%d" % tgt]
        if hit and not _oracle_passed(hit[0].get("result")):
            return "place-oracle-block-not-at-target"
        return True
    if op == "goto":
        if "server_authoritative_arrival" not in reason:
            return "goto-arrival-unproven"
        return True
    return "op-%s-no-positive-rule" % op


def _negative_proven(case, action):
    """failed/cancelled 回执 + 按操作类型的动作级副作用核验。

    混合正负例不再豁免:负例副作用按动作级快照核验(M12)。
    outcome_unknown(死亡/断连中断)带前后快照对账时按诚实负例
    计(A11 语义;守恒不适用——死亡掉落是真实物理效果)。
    """
    st = _state_of(action)
    if st == "outcome_unknown":
        if (_action_inv(action, "pre_action") is None
                or _action_inv(action, "post_action") is None):
            return "unknown-without-reconciliation"
        rec = action.get("reconciliation")
        if not isinstance(rec, dict) or rec.get("resolved") is not True:
            return "unknown-reconciliation-missing-or-unresolved"
        if rec.get("active_execution"):
            return "unknown-reconciliation-active-remaining"
        return True
    if st not in ("failed", "cancelled", "rejected"):
        return None
    op = action.get("op")
    args = action.get("args") or {}
    pre = _action_inv(action, "pre_action")
    post = _action_inv(action, "post_action")
    if pre is None or post is None:
        return "negative-action-snapshots-missing"
    if op in ("move_items", "deposit", "withdraw", "place"):
        if pre != post:
            return "negative-with-inventory-side-effect"
        return True
    if op == "container_transfer":
        # 容器满的诚实部分转移(I05b 语义):回执声明 partial 且玩家
        # 侧净变与回执数字一致则合法;否则必须零副作用。
        if pre == post:
            return True
        if "partial" in _reason_of(action):
            m = _RX_CONT.search(_reason_of(action))
            if m:
                p0, p1 = int(m.group(3)), int(m.group(4))
                item = str(args.get("item") or "")
                actual = post.get(item, 0) - pre.get(item, 0)
                if actual == p1 - p0:
                    return True
        return "negative-with-inventory-side-effect"
    if op == "craft":
        # R3D/F04:failed/cancelled craft 允许合法部分产出/网格残料
        #(回执 materials= 声明的材料、CRAFT_FOLD 配方折算成分与产物
        # 本身),但受保护的其他物品凭空消失必须解释——否则拒绝。
        item = str(args.get("item") or "")
        allowed = _craft_material_items(_reason_of(action))
        allowed.add(item)
        for fold_item, _ratio in CRAFT_FOLD.get(item, []):
            allowed.add(fold_item)
        for tool_mat in ("minecraft:stick", "minecraft:string"):
            # 工具类配方的中间材料(stick/string 属于合法折算成分)
            if any(item.endswith(suffix) for suffix in
                   ("_pickaxe", "_axe", "_shovel", "_sword", "_hoe",
                    "_bow", "_crossbow", "_fishing_rod")):
                allowed.add(tool_mat)
        plank_kinds = {i for i in allowed if i.endswith("_planks")}
        for pk in list(plank_kinds):
            for fold_item, _ratio in CRAFT_FOLD.get(pk, []):
                allowed.add(fold_item)
        for k in set(pre) | set(post):
            if k in allowed:
                continue
            if pre.get(k, 0) != post.get(k, 0):
                return ("failed-craft-unexplained-inventory-change"
                        ":%s %d->%d" % (k, pre.get(k, 0),
                                        post.get(k, 0)))
        return True
    if op == "eat":
        if _RX_EAT.search(_reason_of(action)):
            return "negative-eat-with-witness"
        return True
    return True


def judge_ia(evidence):
    try:
        return _judge_ia(evidence)
    except Exception as exc:  # noqa: BLE001 —— M18:malformed 不崩溃
        return False, "malformed-ia-evidence:%s" % exc.__class__.__name__


def _judge_ia(evidence):
    """I/A 整门:格式、身份、必测集、逐动作物理效果重算。"""
    if not isinstance(evidence, dict):
        return False, "not-an-object"
    if evidence.get("schema") != IA_SCHEMA:
        return False, "schema-mismatch-or-legacy-format"
    identity = evidence.get("identity") or {}
    first = identity.get("first_observe") or {}
    if first.get("error"):
        return False, "identity-first-observe-errored"
    jars = {str(first.get("client_mod_jar_sha256") or "")}
    jars |= {str((_status_fields(r) or {}).get(
        "client_mod_jar_sha256") or "")
        for r in (identity.get("bridge_status_start"),
                  identity.get("bridge_status_end"))}
    jars |= {str((identity.get("final_observe") or {}).get(
        "client_mod_jar_sha256") or "")}
    real_jars = {j for j in jars if _SHA256.match(j)}
    if not real_jars:
        return False, "identity-missing-runtime-jar-sha"
    if len(real_jars) != 1:
        return False, "identity-runtime-jar-mixed:%d-distinct" % len(
            real_jars)
    # R3D/F02:body 会话绑定——首观察会话必须存在,且所有动作快照
    # 的会话与之一致(终态换会话/换人拒绝)。
    anchor_session = str(first.get("game_session") or "")
    if not anchor_session:
        return False, "identity-first-observe-game-session-missing"
    cases = evidence.get("cases")
    if not isinstance(cases, list) or not cases:
        return False, "empty-cases"
    seen = {}
    exec_ids = set()
    for c in cases:
        if not isinstance(c, dict) or "id" not in c:
            return False, "case-missing-id"
        cid = str(c["id"])
        if cid not in IA_REQUIRED:
            return False, "unknown-case-id:%s" % cid
        key = (cid, int(c.get("attempt") or 1))
        if key in seen:
            return False, ("duplicate-case-attempt:%s#%d" % key)
        if not c.get("pre_snapshot") or not c.get("post_snapshot"):
            return False, "case-%s-missing-snapshots" % cid
        if (c.get("pre_snapshot") or {}).get("error"):
            return False, "case-%s-pre-snapshot-errored" % cid
        snap_jar = str((c.get("pre_snapshot") or {}).get(
            "client_mod_jar_sha256") or "")
        if snap_jar and _SHA256.match(snap_jar) and snap_jar not in real_jars:
            return False, "case-%s-cross-jar-contamination" % cid
        # M15:同一 execution_id 不得出现两次(跨 case 也不行)
        for a in c.get("actions") or []:
            ident = str(a.get("execution_id") or "")
            if not ident:
                return False, "case-%s-action-missing-identity" % cid
            if ident in exec_ids:
                return False, "duplicate-execution-identity:%s" % ident[:40]
            exec_ids.add(ident)
            # R3D/F02:动作快照会话与首观察一致(换会话=换人拒绝)
            for snap_key in ("pre_action", "post_action"):
                snap = a.get(snap_key) or {}
                sess = str(snap.get("game_session") or "")
                if sess and sess != anchor_session:
                    return False, (
                        "case-%s-action-%s-session-changed"
                        % (cid, snap_key))
        seen[key] = c
    by_id = {}
    for (cid, att), c in seen.items():
        by_id.setdefault(cid, []).append(c)
    missing = sorted(set(IA_REQUIRED) - set(by_id))
    if missing:
        return False, "missing-required-cases:%s" % ",".join(missing)
    has_pos = False
    for cid in sorted(by_id):  # 每组独立初始化(M11 重排免疫)
        need_pos, need_neg = IA_REQUIRED[cid]
        group = by_id[cid]
        pos_ok = False
        neg_ok = False
        for c in sorted(group, key=lambda x: int(x.get("attempt") or 1)):
            for a in c.get("actions") or []:
                st = _state_of(a)
                if st not in TERMINAL_OK and st != "outcome_unknown":
                    return False, ("case-%s-unresolved-state:%s"
                                   % (cid, st or "empty"))
                # R3D:信息类 op(say=感知/扫描辅助,toms_diag=只读
                # 网络诊断)不参与正负例判定——既不证明也不否证。
                if str(a.get("op") or "") in ("say", "toms_diag"):
                    continue
                # M10:outcome_unknown 保留为合法终态当且仅当带独立
                # 对账证据(前后物理快照齐全);无证据的裸 unknown 拒绝。
                if st == "outcome_unknown":
                    if (_action_inv(a, "pre_action") is None
                            or _action_inv(a, "post_action") is None):
                        return False, (
                            "case-%s-unknown-without-reconciliation"
                            % cid)
                pos = _positive_proven(c, a)
                if pos is True:
                    pos_ok = True
                elif isinstance(pos, str) and st == "completed":
                    return False, ("case-%s-completed-unproven:%s"
                                   % (cid, pos))
                neg = _negative_proven(c, a)
                if neg is True:
                    neg_ok = True
                elif isinstance(neg, str):
                    return False, ("case-%s-negative-unproven:%s"
                                   % (cid, neg))
        if need_pos and not pos_ok:
            return False, "case-%s-positive-not-proven" % cid
        need_ops = IA_POSITIVE_OP.get(cid)
        if need_ops and not any(
                str(a.get("op") or "") in need_ops
                and _state_of(a) == "completed"
                and _positive_proven(c, a) is True
                for c2 in group for a in (c2.get("actions") or [])):
            return False, "case-%s-positive-op-not-proven:%s" % (
                cid, "/".join(need_ops))
        if need_neg and not neg_ok:
            return False, "case-%s-negative-not-proven" % cid
        if pos_ok:
            has_pos = True
    if not has_pos:
        return False, "no-positive-case-passed"
    return True, "ok"


# ---------- G4:五次核心链事实重算 ----------

def _snap_of(run, tag):
    for s in run.get("snapshots") or []:
        if s.get("tag") == tag:
            return s.get("facts") or {}
    return None  # R3C:缺失 ≠ 空(M01)


def _g4_expect_guard(expect):
    """M09:evidence 的 expect 不得低于受控门槛。"""
    try:
        if int(expect.get("logs_need") or G4_DEFAULT_LOGS_NEED) \
                < G4_DEFAULT_LOGS_NEED:
            return False
        if int(expect.get("stone_need") or G4_DEFAULT_STONE_NEED) \
                < G4_DEFAULT_STONE_NEED:
            return False
        crafts = expect.get("crafts") or {}
        for item, want in G4_DEFAULT_CRAFTS.items():
            if int(crafts.get(item, want)) < want:
                return False
    except (TypeError, ValueError):
        return False
    return True


def judge_g4(runs_doc):
    try:
        return _judge_g4(runs_doc)
    except Exception as exc:  # noqa: BLE001
        return False, "malformed-g4-evidence:%s" % exc.__class__.__name__


def _judge_g4(runs_doc):
    if not isinstance(runs_doc, dict):
        return False, "not-an-object"
    runs = runs_doc.get("runs")
    if not isinstance(runs, list) or len(runs) != 5:
        return False, "not-five-runs"
    ids = [str(r.get("run_id") or "") for r in runs]
    if len(set(ids)) != 5 or "" in ids:
        return False, "run-id-not-unique"
    commits = {str(r.get("identity", {}).get("candidate_commit") or "")
               for r in runs}
    if len(commits) != 1 or not next(iter(commits)):
        return False, "candidate-commit-missing-or-mixed"
    jar_pairs = set()
    for r in runs:
        first = (r.get("identity") or {}).get("first_observe") or {}
        # R3D/F02:run 内快照会话一致(换会话=换人拒绝)
        g0 = str(first.get("game_session") or "")
        if not g0:
            return False, "run-%s-first-observe-session-missing" % r.get(
                "run_id")
        for s in r.get("snapshots") or []:
            gs = str((s.get("facts") or {}).get("game_session") or "")
            if gs and gs != g0:
                return False, "run-%s-snapshot-session-changed" % r.get(
                    "run_id")
        srv = _status_fields(
            (r.get("identity") or {}).get("bridge_status_start"))
        jar_pairs.add((str(first.get("client_mod_jar_sha256") or ""),
                       str((srv or {}).get("server_mod_jar_sha256")
                           or "")))
        # M06:body/game session 绑定必须是真实运行时事实
        if not str(first.get("game_session") or ""):
            return False, "run-%s-game-session-missing" % r.get("run_id")
    if len(jar_pairs) != 1:
        return False, "runtime-jar-mixed-across-runs"
    client_jar, server_jar = next(iter(jar_pairs))
    if not (_SHA256.match(client_jar) and _SHA256.match(server_jar)):
        return False, "runtime-jar-identity-missing"
    expect = runs_doc.get("expect") or {}
    if not _g4_expect_guard(expect):
        return False, "expect-below-controlled-threshold"
    logs_need = int(expect.get("logs_need") or G4_DEFAULT_LOGS_NEED)
    stone_need = int(expect.get("stone_need") or G4_DEFAULT_STONE_NEED)
    intervals = []
    seen_exec = set()
    for r in runs:
        rid = r["run_id"]
        if r.get("fail_at"):
            return False, "run-%s-failed:%s" % (rid, r["fail_at"])
        armed = None
        for e in r.get("events") or []:
            if e.get("kind") == "armed-timer-start":
                armed = r.get("identity", {}).get("run_started_wall", 0) \
                    + e.get("t", 0)
        ended = r.get("ended_wall")
        if not armed or not ended:
            return False, "run-%s-missing-timing-facts" % rid
        # R3D/F02:结束早于 armed / 时钟无效 → 拒绝
        if not isinstance(armed, (int, float)) or \
                not isinstance(ended, (int, float)) or ended <= 0 \
                or armed <= 0:
            return False, "run-%s-timing-facts-invalid" % rid
        if ended < armed:
            return False, "run-%s-ended-before-armed" % rid
        intervals.append((armed, ended))
        if ended - armed > 720:
            return False, "run-%s-time-limit-exceeded:%ds" % (
                rid, round(ended - armed))
        # M01/M03:必需快照与结束 status 缺失 → 拒绝(缺 ≠ 空)
        pre_snap = _snap_of(r, "pre")
        logs_snap = _snap_of(r, "after-logs")
        final_snap = _snap_of(r, "final")
        if pre_snap is None or logs_snap is None or final_snap is None:
            return False, "run-%s-required-snapshot-missing" % rid
        if (pre_snap or {}).get("error"):
            return False, "run-%s-pre-snapshot-errored" % rid
        st_end_raw = r.get("status_end")
        if not isinstance(st_end_raw, dict) or not st_end_raw:
            return False, "run-%s-status-end-missing-or-empty" % rid
        st_end_chk = _status_fields(st_end_raw)
        if st_end_chk.get("error") or "active_execution" not in \
                st_end_chk or "needs_reconcile" not in st_end_chk:
            return False, "run-%s-status-end-errored-or-incomplete" % rid
        pre_inv = _inv(pre_snap)
        if pre_inv is None:
            return False, "run-%s-pre-snapshot-unreadable" % rid
        if pre_inv:
            return False, "run-%s-initial-inventory-not-empty" % rid
        after_logs = _inv(logs_snap)
        if after_logs is None:
            return False, "run-%s-after-logs-unreadable" % rid
        wood = max(after_logs.get("minecraft:oak_log", 0),
                   after_logs.get("minecraft:birch_log", 0))
        _matrix = runs_doc.get("matrix") or {}
        if isinstance(_matrix, list):
            _matrix = {k: v for k, v in _matrix}
        fx = _matrix.get(rid, "")
        if str(fx).endswith("-layout"):
            wood += after_logs.get("minecraft:oak_planks", 0) // 4
        if wood < logs_need:
            return False, "run-%s-logs-not-collected:%d/%d" % (
                rid, wood, logs_need)
        crafts = {}
        mines = 0
        ev_mined = sum(
            1 for e in r.get("events") or []
            if e.get("kind") in ("mined", "mined-late-reconciled",
                                 "mined-drop-picked",
                                 "logs-reconciled-from-inventory"))
        # M08:重复 execution 不得跨 run 复用
        for rec in r.get("receipts") or []:
            ex = str(rec.get("execution_id")
                     or ((rec.get("submit") or {}).get("execution_id"))
                     or "")
            if ex:
                if ex in seen_exec:
                    return False, ("run-%s-duplicate-execution:%s"
                                   % (rid, ex[:36]))
                seen_exec.add(ex)
            st = str((rec.get("terminal") or {}).get("state") or "")
            if st == "outcome_unknown":
                return False, "run-%s-unresolved-unknown-receipt" % rid
            if st != "completed":
                continue
            op = rec.get("op")
            args = rec.get("args") or {}
            if op == "mine_opportunity":
                mines += 1
            if op == "craft":
                item = str(args.get("item") or "")
                want = int(args.get("count") or 0)
                # M07:completed 的 craft 回执必须携带权威产出串且
                # 权威 delta ≥ 请求数——只汇总 args.count 会放过
                # "标签完成但实际产出矛盾"的变异(verifier 实测)。
                m = _RX_CRAFT.search(
                    str((rec.get("terminal") or {}).get("reason") or ""))
                if not m or m.group(1) != item:
                    return False, ("run-%s-craft-receipt-not-authoritative"
                                   % rid)
                delta = int(m.group(4))
                if delta < want:
                    return False, ("run-%s-craft-delta-below-args:%s"
                                   " delta=%d<want=%d"
                                   % (rid, item, delta, want))
                crafts[item] = crafts.get(item, 0) + want
        if max(mines, ev_mined) < logs_need + stone_need:
            return False, ("run-%s-mines-below-chain:receipts=%d"
                           ",events=%d" % (rid, mines, ev_mined))
        for item, want in G4_DEFAULT_CRAFTS.items():
            if crafts.get(item, 0) < want:
                return False, ("run-%s-craft-%s-below-%d"
                               % (rid, item, want))
        place_ok = any(
            rec.get("op") == "place"
            and str((rec.get("terminal") or {}).get("state") or "")
            == "completed"
            for rec in r.get("receipts") or [])
        if not place_ok:
            return False, "run-%s-table-place-not-completed" % rid
        table_keys = [k for k in (r.get("oracle_blocks") or {})
                      if k.split("@")[0] == "table"]
        for key in table_keys:
            if not _oracle_passed(
                    r["oracle_blocks"][key].get("result")):
                return False, "run-%s-table-block-not-at-target" % rid
        # M05:工作台位置事实必须存在(缺失 ≠ 通过)
        if not table_keys:
            return False, "run-%s-table-oracle-facts-missing" % rid
        final = _snap_of(r, "final")
        inv = _inv(final)
        if inv is None:
            return False, "run-%s-final-snapshot-unreadable" % rid
        if (inv.get("minecraft:wooden_pickaxe", 0) < 1
                or inv.get("minecraft:stone_pickaxe", 0) < 1):
            return False, "run-%s-final-pickaxes-missing" % rid
        # R3D/F01:终态 Screen 事实必须存在(present=false 仍需空光标
        # 事实;缺失 ≠ 关屏)
        if "screen" not in final or not isinstance(final.get("screen"),
                                                  dict):
            return False, "run-%s-final-screen-facts-missing" % rid
        screen = final.get("screen") or {}
        if "cursor_count" not in screen:
            return False, "run-%s-final-cursor-count-missing" % rid
        # M02:present=false 但 cursor 有物同样不可漏检
        if int(screen.get("cursor_count") or 0) > 0:
            return False, "run-%s-final-cursor-not-empty" % rid
        st_end = _status_fields(r.get("status_end"))
        if st_end.get("active_execution"):
            return False, "run-%s-active-execution-remaining" % rid
        # M04:恢复债务/对账未结 → 不通过
        if st_end.get("needs_reconcile"):
            return False, "run-%s-needs-reconcile-remaining" % rid
    intervals.sort()
    for (a1, e1), (a2, e2) in zip(intervals, intervals[1:]):
        if a2 < e1:
            return False, "run-intervals-overlap(evidence-reuse)"
    return True, "ok"


# ---------- G5:自然过夜事实链 ----------

def _cell_status(block_id):
    """一格围护状态:closed/open/unknown(R3D/F05 白名单语义)。

    已知开口(三种命名空间 air、非实体方块)→ open;已知实体
    (固体白名单)→ closed;其余(未知名、查询失败、未加载、Mod
    特殊状态)→ unknown——按未证处理,不得默认坚固。
    """
    if not isinstance(block_id, str) or not block_id \
            or block_id.strip().lower() in (
            "unknown", "none", "null", "unloaded", "error"):
        return "unknown"
    bl = block_id.strip().lower()
    if bl in _AIR_EXACT or bl.endswith(":air"):
        return "open"
    if any(s in bl for s in _UNRELIABLE_SUBSTR):
        return "open"
    if bl in _SOLID_EXACT:
        return "closed"
    return "unknown"


def _recompute_enclosure(facts):
    """从原始方块事实重算围护:True/False/None(unknown→None)。"""
    if not isinstance(facts, dict):
        return None
    sides = facts.get("sides") or {}
    above = facts.get("above")
    statuses = [_cell_status(sides.get(k)) for k in ("N", "S", "E", "W")]
    statuses.append(_cell_status(above))
    if any(s == "unknown" for s in statuses):
        return None
    return all(s == "closed" for s in statuses)


def _g5_inv_of(row_data, key):
    inv = (row_data or {}).get(key)
    if isinstance(inv, dict):
        return {k: int(v) for k, v in inv.items() if v}
    return None


def judge_g5(rows, run_id):
    try:
        return _judge_g5(rows, run_id)
    except Exception as exc:  # noqa: BLE001
        return False, "malformed-g5-evidence:%s" % exc.__class__.__name__


def _judge_g5(rows, run_id):
    if not isinstance(rows, list) or not rows:
        return False, "no-facts"
    kinds = [r.get("kind") for r in rows]
    if "prep" not in kinds or "armed" not in kinds:
        return False, "prep-or-armed-missing"
    if kinds.index("armed") < kinds.index("prep"):
        return False, "armed-before-prep"
    prep = next(r for r in rows if r.get("kind") == "prep")
    armed_idx = next(i for i, r in enumerate(rows)
                     if r.get("kind") == "armed")
    prep_obs = (prep.get("data") or {}).get("observe") or {}
    if prep_obs.get("error"):
        return False, "prep-observe-errored"
    if _inv(prep_obs):
        return False, "initial-inventory-not-empty"
    if not str(prep_obs.get("game_session") or ""):
        return False, "prep-game-session-missing"
    acts = [r for r in rows if r.get("kind") == "act"]
    if not acts:
        return False, "llm-act-rows-missing(fixed-script-is-skill-only)"
    model_acts = []
    for a in acts:
        d = a.get("data") or {}
        ch = str(d.get("model_channel") or "")
        if not ch:
            return False, "act-without-channel-label"
        if not ch.startswith(G5_SKILL_CHANNEL_PREFIX):
            model_acts.append(a)
        op = str(d.get("op") or "")
        if op not in G5_ALLOWED_OPS:
            return False, "privileged-or-unknown-op:%s" % op
        rc = d.get("receipt") or {}
        st = str(rc.get("state") or "").lower()
        if st not in ("completed", "failed", "cancelled"):
            return False, "act-without-clean-terminal-receipt:%s" % (
                st or "empty")
        reason = str(rc.get("reason") or "")
        if "body_session_changed" in reason \
                or "minecraft_connection_lost" in reason:
            return False, "session-or-connection-lost-mid-run"
    if len(model_acts) < 3:
        return False, "model-decision-rows-below-minimum"
    # R3D/R03:模型行必须带非空决策注记(公开调用/选择与简短理由;
    # 纯字符串通道不算充分证明)
    for a in model_acts:
        note = str((a.get("data") or {}).get("decision_note") or "").strip()
        if not note:
            return False, "model-decision-note-missing"
    model_ops = {str((a.get("data") or {}).get("op") or "")
                 for a in model_acts}
    if len(model_ops) < 2:
        return False, "model-decisions-single-op-script"
    # 关键动作链:采木 → 合成 → 封口 place → (夜) → 清晨核验
    ops = [str((a["data"].get("op") or "")) for a in acts]
    if "mine_opportunity" not in ops:
        return False, "no-real-mining-act"
    if "craft" not in ops:
        return False, "no-craft-act"
    logs = 0
    stones = 0
    for a in acts:
        d = a.get("data") or {}
        if str(d.get("op") or "") != "mine_opportunity":
            continue
        if str((d.get("receipt") or {}).get("state") or "") != "completed":
            continue
        pre = _g5_inv_of(d, "pre_inv")
        post = _g5_inv_of(d, "post_inv")
        if pre is None or post is None:
            return False, "mine-act-missing-inv-facts"
        gained_logs = (post.get("minecraft:oak_log", 0)
                       + post.get("minecraft:birch_log", 0)
                       - pre.get("minecraft:oak_log", 0)
                       - pre.get("minecraft:birch_log", 0))
        gained_stone = (post.get("minecraft:cobblestone", 0)
                        - pre.get("minecraft:cobblestone", 0))
        if gained_logs > 0:
            logs += gained_logs
        if gained_stone > 0:
            stones += gained_stone
    if logs < 5:
        return False, "logs-below-g5-minimum:%d" % logs
    if stones < 3:
        return False, "stones-below-g5-minimum:%d" % stones
    dusk = [r for r in rows if r.get("kind") == "dusk-baseline"]
    if not dusk:
        return False, "dusk-baseline-missing"
    shelter_rows = [r for r in rows if r.get("kind") == "shelter-facts"]
    if not shelter_rows:
        return False, "shelter-facts-missing"
    # M19/M20:围护从原始方块重算,不信 verdict 布尔;unknown 不安全
    recompute = [_recompute_enclosure((r.get("data") or {}).get("facts"))
                 for r in shelter_rows]
    if not any(v is True for v in recompute):
        return False, "sheltered-never-proven-from-raw-blocks"
    last_seal_idx = max(
        (i for i, r in enumerate(rows)
         if r.get("kind") == "act" and (r.get("data") or {}).get("op")
         == "place"), default=-1)
    first_true_idx = next(
        (i for i, r in enumerate(rows)
         if r.get("kind") == "shelter-facts"
         and _recompute_enclosure((r.get("data") or {}).get("facts"))
         is True), 10**9)
    if last_seal_idx == -1 or last_seal_idx > first_true_idx:
        return False, "shelter-claimed-before-or-without-sealing"
    if any(r.get("kind") in ("night-death", "night-position-lost")
           for r in rows):
        return False, "death-or-position-lost-during-night"
    night = [r for r in rows if r.get("kind") == "night"]
    if len(night) < 2:
        return False, "night-continuity-rows-missing"
    # R3D/F06:整晚会话连续(同一 body 会话;缺失/换人拒绝)
    prep_sess = str(prep_obs.get("game_session") or "")
    for r in night:
        ns = str((r.get("data") or {}).get("game_session") or "")
        if not ns or ns != prep_sess:
            return False, "night-session-missing-or-changed"
    # M21:原始世界时间连续、单调(24000 回绕)、无跳跃
    wts = []
    for r in night:
        wt = (r.get("data") or {}).get("world_time")
        if not isinstance(wt, int):
            return False, "night-rows-missing-world-time"
        wts.append(wt)
    for a, b in zip(wts, wts[1:]):
        dt = (b - a) % 24000
        if dt > 900:
            return False, "night-world-time-jump:%d" % dt
    # R3D/R03:时钟语义自检——世界 tick 增量不得显著快于墙钟
    # (20 tps;跳时/睡眠跳夜会表现为 tick 超前)。tick 只会滞后,
    # 给 1.35x 宽容(卡顿回落+采样漂移)。
    for (r1, r2), (a, b) in zip(zip(night, night[1:]),
                                zip(wts, wts[1:])):
        dt_tick = (b - a) % 24000
        dt_wall = r2.get("t_wall", 0) - r1.get("t_wall", 0)
        if dt_wall > 0 and dt_tick > dt_wall * 20 * 1.35 + 40:
            return False, "night-clock-semantics-mismatch:%dt/%dw" % (
                dt_tick, round(dt_wall))
    for r1, r2 in zip(night, night[1:]):
        gap = r2.get("t_wall", 0) - r1.get("t_wall", 0)
        if gap > 90:
            return False, "night-observation-gap:%ds" % round(gap)
    mod = [w % 24000 for w in wts]
    span = (wts[-1] - wts[0]) % 24000 or (
        24000 if wts[-1] != wts[0] else 0)
    if span < 9000:
        return False, "night-observed-span-too-short:%d" % span
    if not any(12000 <= w < 13800 for w in mod):
        return False, "dusk-phase-not-observed"
    if not any(13800 <= w < 22200 for w in mod):
        return False, "night-phase-not-observed"
    if not any(18000 <= w < 22200 for w in mod):
        return False, "midnight-phase-not-observed"
    night_end = [r for r in rows if r.get("kind") == "night-end"]
    if not night_end:
        return False, "night-end-missing"
    ne = night_end[-1].get("data") or {}
    ne_wt = ne.get("world_time")
    if not isinstance(ne_wt, int):
        return False, "night-end-missing-world-time"
    if not (ne_wt % 24000 >= 23030 or ne_wt % 24000 < 60):
        return False, "night-end-not-at-dawn:%d" % (ne_wt % 24000)
    # M22:清晨从实际库存重算(不信 tools_ok)
    vm = [r for r in rows if r.get("kind") == "verify-morning"]
    if not vm:
        return False, "verify-morning-missing"
    last_vm_idx = max(
        (i for i, r in enumerate(rows)
         if r.get("kind") == "verify-morning"), default=-1)
    vm_inv = _g5_inv_of(vm[-1].get("data"), "inv")
    if vm_inv is None:
        return False, "verify-morning-inventory-missing"
    if (vm_inv.get("minecraft:wooden_pickaxe", 0) < 1
            or vm_inv.get("minecraft:stone_pickaxe", 0) < 1):
        return False, "morning-tools-not-retained"
    gather = []
    for i, r in enumerate(rows):
        if r.get("kind") != "act" or i <= last_vm_idx:
            continue
        d = r.get("data") or {}
        if str(d.get("op") or "") != "mine_opportunity":
            continue
        if str((d.get("receipt") or {}).get("state") or "") != "completed":
            continue
        pre = _g5_inv_of(d, "pre_inv")
        post = _g5_inv_of(d, "post_inv")
        if pre is None or post is None:
            continue
        if sum(post.values()) - sum(pre.values()) >= 1:
            gather.append(i)
    if not gather:
        return False, "morning-gather-no-real-gain"
    # armed 后无 rcon/特权事实行(harness 结构性禁止;再核一次记录)
    for i, r in enumerate(rows):
        if i <= armed_idx:
            continue
        if "rcon" in str(r.get("kind")) or "priv" in str(r.get("kind")):
            return False, "rcon-row-after-armed"
    return True, "ok"


# ---------- 汇总入口 ----------

_C_REQUIRED = {"C%02d" % i for i in range(1, 8)}
_N_REQUIRED = {"N%02d" % i for i in range(1, 7)}
_V_REQUIRED = {"V%02d" % i for i in range(1, 11)}
_L_REQUIRED = {"L%02d" % i for i in range(1, 15)}


def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def _load_jsonl(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def _gate_suite(rows, required, attempts_required=None,
                allow_not_run=()):
    """JSONL 用例行 → (ok, reason)。required 必须全部出现且通过。"""
    if not isinstance(rows, list) or not rows:
        return False, "rows-empty"
    by_id = {}
    _seen_attempts = set()
    for r in rows:
        if not isinstance(r, dict) or "id" not in r:
            return False, "row-missing-id"
        rid = str(r["id"])
        if r.get("not_run"):
            if rid not in allow_not_run:
                return False, "unexpected-not-run:%s" % rid
            if not str(r.get("reason") or "").strip():
                return False, "not-run-without-reason:%s" % rid
            continue
        val = r.get("pass", r.get("verdict"))
        if val is None:
            return False, "row-%s-missing-verdict" % rid
        if val is not True:
            # R3D/R03:失败不可被同 ID 后补 pass 覆盖
            return False, "row-%s-failed:%s" % (
                rid, str(r.get("reason") or "unspecified")[:80])
        att = r.get("attempt")
        if attempts_required:
            if att is None:
                return False, "row-%s-attempt-missing" % rid
            key = (rid, str(att))
            if key in _seen_attempts:
                return False, ("row-%s-duplicate-attempt:%s"
                               % (rid, att))
            _seen_attempts.add(key)
        by_id[rid] = by_id.get(rid, 0) + 1
    missing = sorted(set(required) - set(by_id))
    if missing:
        return False, "missing-required:%s" % ",".join(missing[:6])
    if attempts_required:
        short = ["%sx%d/%d" % (k, v, attempts_required)
                 for k, v in sorted(by_id.items())
                 if k in required and v < attempts_required]
        if short:
            return False, "attempts-below-required:%s" % ";".join(
                short[:4])
    failed = [k for k, v in by_id.items() if k in required and v == 0]
    if failed:
        return False, "failed:%s" % ",".join(sorted(failed)[:6])
    return True, "ok"


def _gate_i08(doc):
    """I08:Tom's 真实网络终端双向存/取事实。

    BLOCKED 文档(带证据的环境阻断声明)如实报告阻断原因,
    不伪称通过,也不吞掉细节。
    """
    if not isinstance(doc, dict):
        return False, "not-an-object"
    if doc.get("blocked"):
        ev = doc.get("evidence") or {}
        return False, ("BLOCKED:tom's-network-zero-transfer"
                       "(attempts=%s,topologies=%s,upstream=%s)"
                       % (ev.get("attempts"), ev.get("topologies_tried")
                          and len(ev["topologies_tried"]),
                          "issue-381"))
    cases = doc.get("cases") or []
    i08 = [c for c in cases if c.get("id") == "I08"]
    if not i08:
        return False, "i08-case-missing"
    directions = {}
    for c in i08:
        for a in c.get("actions") or []:
            op = str(a.get("op") or "")
            if op not in ("deposit", "withdraw",
                          "container_transfer"):
                continue
            rc = a.get("terminal") or {}
            if str(rc.get("state") or "") != "completed":
                continue
            reason = str(rc.get("reason") or "")
            if "toms_storage_terminal" not in reason:
                continue
            m = _RX_CONT.search(reason)
            if not m:
                continue
            moved = abs(int(m.group(4)) - int(m.group(3)))
            want = int((a.get("args") or {}).get("count") or 0)
            if moved <= 0 or (want > 0 and moved < want):
                continue
            directions[m.group(2)] = directions.get(m.group(2), 0) + 1
    if "deposit" not in directions:
        return False, "no-completed-toms-deposit"
    if "withdraw" not in directions:
        return False, "no-completed-toms-withdraw"
    return True, "ok"


_BUILD_REQUIRED_SECTIONS = ("junit", "python")


def _gate_build(doc):
    """构建门:必需测试入口(junit/python)实跑全过;GameTest 未跑
    须给真实依据(R3D/R03:任意 not_applicable 不能替代执行)。"""
    if not isinstance(doc, dict):
        return False, "not-an-object"
    sections = doc.get("sections") or {}
    bad = []
    for name in _BUILD_REQUIRED_SECTIONS:
        if name not in sections:
            return False, "build-section-missing:%s" % name
    for name, sec in sections.items():
        if not isinstance(sec, dict):
            return False, "build-section-malformed:%s" % name
        if sec.get("not_applicable"):
            reason = str(sec.get("reason") or "").strip()
            if name in _BUILD_REQUIRED_SECTIONS:
                return False, "build-section-not-applicable:%s" % name
            if len(reason) < 20:
                return False, ("build-na-without-real-basis:%s"
                               % name)
            continue
        if not str(sec.get("command") or "").strip():
            return False, "build-section-command-missing:%s" % name
        total = int(sec.get("total") or 0)
        passed = int(sec.get("passed") or 0)
        if total <= 0 or passed != total:
            bad.append(name)
    if bad:
        return False, "build-sections-failed:%s" % ",".join(bad)
    if not sections:
        return False, "build-sections-empty"
    return True, "ok"


def _gate_fresh(doc):
    """fresh 部署门:新源码/新部署身份链完整,差异逐项解释。"""
    if not isinstance(doc, dict):
        return False, "not-an-object"
    for key in ("upstream_commit", "patch_ref", "deployment_root",
                "client_jar_sha256", "server_jar_sha256",
                "rebuild_started_wall"):
        if not str(doc.get(key) or ""):
            return False, "fresh-deployment-field-missing:%s" % key
    cj = str(doc.get("client_jar_sha256"))
    sj = str(doc.get("server_jar_sha256"))
    if not (_SHA256.match(cj) and _SHA256.match(sj)):
        return False, "fresh-deployment-jar-sha-malformed"
    diffs = doc.get("content_diffs") or []
    for d in diffs:
        if not isinstance(d, dict):
            return False, "fresh-diff-entry-malformed"
        path = str(d.get("path") or "")
        if not path or "*" in path or "?" in path:
            # R3D/R04:条目级路径;*.class 之类总括不算逐项比较
            return False, "fresh-diff-wildcard-or-missing-path"
        if not d.get("explained"):
            return False, "fresh-deployment-unexplained-diff:%s" % path
        if not str(d.get("reason") or "").strip():
            return False, "fresh-diff-reason-missing:%s" % path
    deps = doc.get("dependency_cache") or []
    if not isinstance(deps, list) or not deps:
        return False, "fresh-deployment-dependency-cache-undeclared"
    for d in deps:
        if not isinstance(d, dict) or not (
                _SHA256.match(str(d.get("sha256") or ""))
                and str(d.get("source") or "")):
            return False, "fresh-deployment-dependency-entry-invalid"
    world = doc.get("world") or {}
    if not (str(world.get("declared") or "") and (
            _SHA256.match(str(world.get("sha256") or ""))
            or str(world.get("origin") or "").startswith(
                "fresh_generated"))):
        return False, "fresh-deployment-world-undeclared"
    return True, "ok"


def judge_all(manifest):
    """manifest: 全门路径表(见 deliveries REPRODUCE)。

    无条件门:ia/g4/g5_s01/g5_s02/tests_c(C01-C07;C08 条件项单列)/
    tests_n(N01-N06×3)/tests_v/lifecycle/i08/build/fresh_deployment。
    任一门缺失 → evidence-missing,不得静默降为 PASS(M24)。
    """
    def load(name):
        path = manifest.get(name)
        if not path:
            return None
        try:
            return _load(path)
        except (OSError, ValueError):
            return None
    gates = {}

    ia = load("ia_facts")
    gates["ia"] = judge_ia(ia) if ia is not None else (
        False, "evidence-missing")
    g4 = load("g4_runs")
    gates["g4"] = judge_g4(g4) if g4 is not None else (
        False, "evidence-missing")
    for sid in ("s01", "s02"):
        rows = None
        path = manifest.get("g5_" + sid)
        if path:
            rows = _load_jsonl(path)
        gates["g5_" + sid] = (judge_g5(rows, sid)
                              if rows is not None
                              else (False, "evidence-missing"))
    path = manifest.get("tests_c")
    gates["tests_c"] = (
        _gate_suite(_load_jsonl(path), _C_REQUIRED,
                    allow_not_run=("C08",)) if path
        else (False, "evidence-missing"))
    for key, required, attempts in (("tests_n", _N_REQUIRED, 3),
                                    ("tests_v", _V_REQUIRED, None),
                                    ("lifecycle", _L_REQUIRED, None)):
        path = manifest.get(key)
        gates[key] = (_gate_suite(_load_jsonl(path), required,
                                  attempts_required=attempts)
                      if path else (False, "evidence-missing"))
    i08 = load("i08")
    gates["i08"] = _gate_i08(i08) if i08 is not None else (
        False, "evidence-missing")
    build = load("build")
    gates["build"] = _gate_build(build) if build is not None else (
        False, "evidence-missing")
    fresh = load("fresh_deployment")
    gates["fresh_deployment"] = _gate_fresh(fresh) if fresh is not None \
        else (False, "evidence-missing")
    mut = load("mutations")
    gates["mutations"] = _gate_mutations(mut) if mut is not None else (
        False, "evidence-missing")
    return gates


def _gate_mutations(doc):
    """R3D/R03:成对变异门——每组:同一正式 CLI 先接受有效原始正例,
    再单因素变异被拒。无合格正例的组 = INCONCLUSIVE(不算通过)。
    """
    if not isinstance(doc, dict):
        return False, "not-an-object"
    groups = doc.get("groups") or {}
    if not groups:
        return False, "mutation-groups-empty"
    for name, g in groups.items():
        if not isinstance(g, dict):
            return False, "mutation-group-malformed:%s" % name
        accepted = int(g.get("accepted") or 0)
        rejected = int(g.get("rejected") or 0)
        inconclusive = int(g.get("inconclusive") or 0)
        if accepted < 1:
            return False, ("INCONCLUSIVE:mutation-group-%s"
                           "-no-valid-positive" % name)
        if rejected < 1:
            return False, ("FAIL:mutation-group-%s"
                           "-no-rejection" % name)
        if inconclusive > 0 and int(g.get("required") or 1) > \
                accepted:
            return False, ("INCONCLUSIVE:mutation-group-%s"
                           "-%d-inconclusive" % (name, inconclusive))
    return True, "ok"


# ---------- CLI ----------

def main(argv):
    if not argv or argv[0] not in ("judge-ia", "judge-g4", "judge-g5",
                                   "judge-all"):
        print(__doc__)
        return 2
    try:
        if argv[0] == "judge-ia":
            ok, reason = judge_ia(_load(argv[1]))
        elif argv[0] == "judge-g4":
            ok, reason = judge_g4(_load(argv[1]))
        elif argv[0] == "judge-g5":
            rows = _load_jsonl(argv[1])
            ok, reason = judge_g5(rows, argv[2] if len(argv) > 2 else "?")
        else:
            gates = judge_all(_load(argv[1]))
            ok = all(v[0] for v in gates.values())
            print(json.dumps(gates, ensure_ascii=False, indent=1))
            return 0 if ok else 1
    except (OSError, ValueError, IndexError) as exc:
        print("checker-error: %r" % exc)
        return 2
    print(json.dumps({"accept": bool(ok), "reason": reason},
                     ensure_ascii=False))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
