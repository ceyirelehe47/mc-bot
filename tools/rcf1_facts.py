# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 事实采集层(F01/F02)。

采集器记录实际发生的请求、完整未截断回执、前后观察快照与运行身份;
主 checker(tools/rcf1_checker.py)从这些原始事实派生结论——本层
绝不写 passed/true 之类的判定字段。

结构(schema=mc.rcf1r3.facts.v1):
  identity : 运行身份(status/observe 的真实运行时事实,非自报字符串)
  cases[]  : {id, attempt, actions[]{op,args,submit,terminal},
              pre_snapshot, post_snapshot, oracle_blocks{}}
"""
import json
import time

import rcf1_env as E


def _strip_secrets(obj):
    """脱敏:桥 token 等不出现在事实文件。"""
    if isinstance(obj, dict):
        return {k: _strip_secrets(v) for k, v in obj.items()
                if k not in ("token", "bridge_token")}
    if isinstance(obj, list):
        return [_strip_secrets(v) for v in obj]
    return obj


def observe_facts(session):
    """一次 observe 的可用事实(身份/库存/光标/位置/生命/饥饿)。

    game_session 来自 body_session_epoch(= Minecraft JOIN incarnation,
    由 Binding.sessionEpoch 携带),不是自报字符串。
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


class FactsCollector:
    """按用例聚拢原始事实;自动在首个动作前取 pre、结束取 post。"""

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

    def pre_snapshot_if_needed(self):
        cur = self._current
        if cur is not None and cur["pre_snapshot"] is None \
                and not cur["actions"]:
            cur["pre_snapshot"] = observe_facts(self.session)

    def record_action(self, case_id, op, args, submit_echo, terminal):
        cur = self._ensure(case_id)
        self.pre_snapshot_if_needed()
        cur["actions"].append({
            "op": op, "args": args,
            "submit": _strip_secrets(submit_echo),
            "terminal": _strip_secrets(terminal),
            "t_wall": round(time.time(), 3),
        })

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
        return {"schema": "mc.rcf1r3.facts.v1",
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
    for row in inv:
        if isinstance(row, dict) and row.get("item"):
            out[row["item"]] = out.get(row["item"], 0) + int(
                row.get("count", 0))
    return out


def case_delta(case):
    """(post, pre) 库存计数——原始事实推导,checker 用。"""
    return (inv_counts_of(case.get("post_snapshot")),
            inv_counts_of(case.get("pre_snapshot")))
