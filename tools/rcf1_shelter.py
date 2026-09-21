# -*- coding: utf-8 -*-
"""MC-RCF-1-R2 G5 避难策略(生产模块,G5 游玩循环与 V10 测试共用)。

这是 G5 实际运行的策略:黄昏前选择避难动作(挖洞/走入/封口),
sheltered 只能由可核验世界条件建立。V10 的负例注入直接调用本模块
——不允许在测试里另写一个"正确的 if"来顶替(审查 R01/V10)。

判定事实全部来自只读观察(头顶/四周方块、昼夜相位、place/mine 回执),
不使用透视或管理员信息。
"""
import json

DUSK_PHASES = ("dusk", "sunset")
NIGHT_PHASES = ("night", "midnight")


def should_start_shelter(day_phase):
    """黄昏/夜间 → 需要避难;清晨解除。"""
    return str(day_phase).lower() in DUSK_PHASES + NIGHT_PHASES


def assess_sheltered(facts):
    """真实可核验条件:头顶实心盖 + 四周封闭(除入口已封)。

    facts: {
      "solid_above": bool,       # 头顶有实心方块(可多格)
      "open_sides": int,         # 未封闭水平面数(0=全封)
      "inside_enclosure": bool,  # 身处洞穴/挖出的掩体
    }
    任何一项缺失/为假 → 不 sheltered(fail-closed)。
    """
    if not isinstance(facts, dict):
        return False
    if not facts.get("solid_above"):
        return False
    open_sides = facts.get("open_sides")
    if open_sides is None:
        open_sides = 99
    if int(open_sides) != 0:
        return False
    if not facts.get("inside_enclosure"):
        return False
    return True


def plan_shelter_action(facts):
    """下一动作:未入掩体→挖入/走入;未封口→封口;全封闭→等待天亮。

    返回 dict: {"action": "dig_in"|"enter"|"seal"|"wait", "reason": str}
    place/mine 的执行结果由调用方回填,再经 record_result 核对。
    """
    if not assess_sheltered(facts):
        if not facts.get("inside_enclosure"):
            return {"action": "dig_in",
                    "reason": "no_enclosure_dig_in_before_dark"}
        open_sides = facts.get("open_sides")
        if open_sides is None or int(open_sides) > 0:
            return {"action": "seal",
                    "reason": "seal_open_sides"}
        return {"action": "enter",
                "reason": "enclosure_exists_enter"}
    return {"action": "wait", "reason": "sheltered_wait_for_dawn"}


class ShelterRun:
    """一次避难的状态机:记录每步动作回执,只有全部关键步真实
    completed 且最终 assess_sheltered 成立才置 sheltered。"""

    def __init__(self):
        self.sheltered = False
        self.events = []
        self._sealed = False
        self._entered = False

    def record_result(self, action, result):
        """action ∈ dig_in/enter/seal;result = 桥执行终态 dict。"""
        if not isinstance(result, dict):
            return False
        state = str(result.get("state") or "")
        reason = str(result.get("reason") or "")
        self.events.append({"action": action, "state": state})
        if state != "completed":
            # place/mine 返回 failed(不抛异常)/cancelled/文案伪造
            # completed:一律不推进关键步。文案含 server_authoritative
            # 不是独立物理事实,还需最终世界核验。
            return False
        if action == "dig_in" or action == "enter":
            self._entered = True
        elif action == "seal":
            self._sealed = True
        return True

    def finalize(self, facts):
        """清晨/入夜核验:关键步齐全 + 世界条件成立才 sheltered。"""
        self.sheltered = bool(self._entered and self._sealed
                              and assess_sheltered(facts))
        return self.sheltered

    def summary(self):
        return json.dumps({"sheltered": self.sheltered,
                           "events": self.events}, ensure_ascii=False)
