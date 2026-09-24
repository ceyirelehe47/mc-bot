# -*- coding: utf-8 -*-
"""MC-RCF-1-R3C 事实采集层 v2(F01/F02 + R3C B1/B2 收口)。

采集器记录实际发生的请求、完整未截断回执、动作级前后观察快照与
运行身份;主 checker(tools/rcf1_checker.py)从这些原始事实派生
结论——本层绝不写 passed/true 之类的判定字段。

R3C 相对 R3 的关键变化(对照 TASKBOOK B1/B2):
- 每个计分动作自带 pre_action/post_action 快照:pre 在 submit 前
  采(准备期已由场景函数完成并同步),post 在终态+短暂落定后采。
  准备期加物与动作效果的守恒区间从此逐动作成立,case 中途的
  fixture 调整不再污染守恒判定。
- 单一计分身份:同一 execution_id 的重复 record_action 被拒绝
  (term/do 双路径实测存在重复计分漏洞)。
- observe 增加 world_time(G5 夜晚连续性核验需要原始世界时间)。

结构(schema=mc.rcf1r3c.facts.v2):
  identity : 运行身份(status/observe 的真实运行时事实,非自报字符串)
  cases[]  : {id, attempt, actions[]{op,args,submit,terminal,
              pre_action,post_action}, pre_snapshot, post_snapshot,
              oracle_blocks{}}
  case 级 pre/post 仍保留(取自首个动作 pre / 用例收口),用作整体
  对账边界;checker 的守恒判定以动作级为准。
"""
import json
import time

import rcf1_env as E

SCHEMA = "mc.rcf1r3c.facts.v2"

# 终态后的落定等待:服务器 tick 同步/掉落回收的最后一拍。
POST_SETTLE_S = 0.6


def _strip_secrets(obj):
    """脱敏:桥 token 等不出现在事实文件。"""
    if isinstance(obj, dict):
        return {k: _strip_secrets(v) for k, v in obj.items()
                if k not in ("token", "bridge_token")}
    if isinstance(obj, list):
        return [_strip_secrets(v) for v in obj]
    return obj


def observe_facts(session):
    """一次 observe 的可用事实(身份/库存/光标/位置/生命/饥饿/世界时间)。

    game_session 来自 body_session_epoch(= Minecraft JOIN incarnation,
    由 Binding.sessionEpoch 携带),不是自报字符串。
    world_time 是服务端世界 tick 原始值(G5 夜晚连续性判定依据)。
    """
    try:
        data = session.observe().get("data", {})
    except Exception as exc:  # noqa: BLE001
        return {"error": repr(exc)}
    obs = data.get("observation", {}) or {}
    out = {
        "runtime_epoch": data.get("runtime_epoch"),
        "body_instance_id": data.get("body_instance_id"),
        "body_session_epoch": data.get("body_session_epoch"),
        "snapshot_age_ms": data.get("snapshot_age_ms"),
        "game_session": obs.get("body_session_epoch"),
        "control_session_epoch": obs.get("control_session_epoch"),
        "client_mod_jar_sha256": obs.get("client_mod_jar_sha256"),
        "minecraft_profile_uuid": obs.get("minecraft_profile_uuid"),
        "name": obs.get("name"),
        "dimension": obs.get("dimension"),
        "position": obs.get("position"),
        "health": obs.get("health"),
        "food": obs.get("food"),
        "world_time": obs.get("world_time"),
        "inventory": obs.get("inventory"),
        "screen": obs.get("screen"),
        "t_wall": round(time.time(), 3),
    }
    return _strip_secrets(out)


def status_facts():
    try:
        st = E.status()
    except Exception as exc:  # noqa: BLE001
        return {"error": repr(exc)}
    st = dict(st)
    st.pop("token", None)
    st["t_wall"] = round(time.time(), 3)
    return st


def _action_identity(submit_echo):
    """动作的计分身份:execution_id(无提交则为错误串)。"""
    if isinstance(submit_echo, dict) and submit_echo.get("execution_id"):
        return str(submit_echo["execution_id"])
    return "nosubmit:" + json.dumps(submit_echo, ensure_ascii=False)[:80]


class FactsCollector:
    """按用例聚拢原始事实;动作级 pre/post 由录制会话在正确时点供给。"""

    def __init__(self, session, owner):
        self.session = session
        self.owner = owner
        self.identity = {
            "collected_for": owner,
            "bridge_status_start": status_facts(),
            "first_observe": observe_facts(session),
        }
        self.cases = []
        self._current = None
        self._recorded_ids = set()

    def case(self, case_id, attempt=1):
        """结束上一用例(补 post 快照),开启新用例。"""
        self._close_case()
        self._current = {"id": case_id, "attempt": attempt,
                         "actions": [], "oracle_blocks": {},
                         "pre_snapshot": None, "post_snapshot": None,
                         "opened_wall": round(time.time(), 3)}
        self.cases.append(self._current)

    def _ensure(self, case_id):
        if self._current is None or self._current["id"] != case_id:
            self.case(case_id)
        return self._current

    def record_action(self, case_id, op, args, submit_echo, terminal,
                      pre=None, post=None, reconciliation=None):
        """记录一个逻辑动作(单一计分身份:execution_id 去重)。

        pre/post 由调用方在正确时点采集:pre=submit 前,post=终态落定后。
        同一 execution_id 重复记录 → ValueError(录制链回归须暴露,
        不静默吞掉重复计分)。
        reconciliation:仅 outcome_unknown 需要——对账结论(债务解除
        与效果归因的原始事实),由录制层在终态后有界采集;checker
        要求 unknown 动作携带 resolved=true 的对账记录。
        """
        cur = self._ensure(case_id)
        ident = _action_identity(submit_echo)
        if ident in self._recorded_ids:
            raise ValueError(
                "duplicate-action-identity:%s:%s" % (case_id, ident))
        self._recorded_ids.add(ident)
        # case 级 pre = 首个动作的 pre(准备期结束后、首个 submit 前)
        if cur["pre_snapshot"] is None and pre is not None:
            cur["pre_snapshot"] = pre
        action = {
            "op": op, "args": args,
            "execution_id": ident,
            "submit": _strip_secrets(submit_echo),
            "terminal": _strip_secrets(terminal),
            "pre_action": pre,
            "post_action": post,
            "t_wall": round(time.time(), 3),
        }
        if reconciliation is not None:
            action["reconciliation"] = reconciliation
        cur["actions"].append(action)

    def oracle_block(self, case_id, tag, x, y, z, result):
        cur = self._ensure(case_id)
        cur["oracle_blocks"]["%s@%d,%d,%d" % (tag, x, y, z)] = {
            "result": (result or "").strip(),
            "t_wall": round(time.time(), 3)}

    def _close_case(self):
        if self._current is not None:
            self._current["post_snapshot"] = observe_facts(self.session)
            self._current["closed_wall"] = round(time.time(), 3)
            self._current = None

    def finish(self):
        self._close_case()
        self.identity["bridge_status_end"] = status_facts()
        self.identity["final_observe"] = observe_facts(self.session)
        return {"schema": SCHEMA,
                "identity": self.identity, "cases": self.cases}

    def save(self, path):
        doc = self.finish()
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, ensure_ascii=False, indent=1)
        return doc


def inv_counts_of(snapshot):
    inv = (snapshot or {}).get("inventory") or {}
    if isinstance(inv, dict):
        return {k: int(v) for k, v in inv.items() if v}
    out = {}
    for entry in inv or []:
        if isinstance(entry, dict) and entry.get("id"):
            out[entry["id"]] = out.get(entry["id"], 0) + int(
                entry.get("count") or 1)
    return out


def case_delta(case):
    """(post, pre) 库存计数——原始事实推导,checker 用。"""
    return (inv_counts_of(case.get("post_snapshot")),
            inv_counts_of(case.get("pre_snapshot")))
