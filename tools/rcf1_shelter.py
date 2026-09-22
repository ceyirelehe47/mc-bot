# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 G5 避难策略(生产模块,G5 游玩与 V10 测试共用)。

R3/F03 修复(对照审查 ff29078):
- 判定事实改为逐格三值状态(known_closed/known_open/unknown),
  不再是 open_sides 计数 + 硬编码 inside_enclosure=True。
- 任何一格 unknown → 不能判 sheltered(fail-closed;审查点名
  "查询返回不含 passed 默认头顶实心"的默认安全缺陷)。
- 植物液体无碰撞装饰不算可靠围护(_RELIABLE_* 黑名单)。
- facts["body_cell"] 必须存在且是实际身体格(由调用方从 observe
  取得),没有身体事实 → 不能判避难。
判定事实全部来自只读观察(客户端可见方块/昼夜相位/回执),
不使用透视或管理员信息。
"""

DUSK_PHASES = ("dusk", "sunset")
NIGHT_PHASES = ("night", "midnight")
DAWN_PHASES = ("morning", "day", "noon", "afternoon", "sunrise")

# 无碰撞/不可靠围护:植物、液体、装饰(按 block id 子串判定)
_UNRELIABLE = ("grass", "flower", "sapling", "fern", "bush", "torch",
               "water", "lava", "snow_layer", "carpet", "rail",
               "sign", "button", "pressure_plate", "tripwire", "string",
               "dead_bush", "mushroom", "roots", "vine", "lily", "seagrass",
               "kelp", "cobweb", "bamboo", "sugar", "wheat", "campfire",
               "lantern", "chain", "fence_gate", "trapdoor")
def cell_status(block_id):
    """一格的围护可靠性:closed / open / unknown。"""
    if not block_id:
        return "unknown"
    bid = str(block_id)
    if bid.endswith(":air") or bid == "air":
        return "open"
    low = bid.split(":")[-1]
    if low in ("grass_block", "dirt_path", "snow_block", "moss_block",
               "mud", "packed_mud", "rooted_dirt", "coarse_dirt",
               "podzol", "mycelium"):
        return "closed"  # 完整碰撞方块(名字带 grass 但实心)
    if bid.endswith("_carpet") or bid.endswith("_button"):
        return "unreliable"
    for frag in _UNRELIABLE:
        if frag in low:
            return "unreliable"
    return "closed"


def morning_progress_ok(inv):
    """R3/F03:清晨进度核验——保有本轮默认基本工具(木镐+石镐)。
    空包/缺关键工具 → False(审查点名旧 `<0 and` 永假判定)。
    耐久损耗不影响(数量层面保有);inv 为 observe 聚合计数 dict。"""
    if not isinstance(inv, dict):
        return False
    return (int(inv.get("minecraft:wooden_pickaxe", 0) or 0) >= 1
            and int(inv.get("minecraft:stone_pickaxe", 0) or 0) >= 1)


def should_start_shelter(day_phase):
    """黄昏/夜间 → 需要避难;清晨解除。"""
    return str(day_phase).lower() in DUSK_PHASES + NIGHT_PHASES


def assess_sheltered(facts):
    """三值判定:True=可核验全封闭;False=明确不封闭/不可靠;
    None=存在 unknown(fail-closed,不得当安全)。

    facts 结构(schema=mc.rcf1r3.shelter.v1):
      body_cell: [x,y,z]           实际身体格(observe 取得)
      above:     block id 或 None  头顶格(unknown=None)
      sides:     {"N": id|None, "S":..., "E":..., "W":...}
      side_rows: 1(单层,脚+头同列)或 2(两层各四向)
      lower_sides: 同 sides(两行时第二行)
    """
    if not isinstance(facts, dict):
        return False
    body = facts.get("body_cell")
    if not body:
        return False  # 没有实际身体事实 → 不能判避难
    above_status = cell_status(facts.get("above"))
    if above_status != "closed":
        return False if above_status != "unknown" else None
    rows = [facts.get("sides") or {}]
    lower = facts.get("lower_sides")
    if lower:
        rows.append(lower)
    for row in rows:
        for direction in ("N", "S", "E", "W"):
            status = cell_status(row.get(direction))
            if status == "unknown":
                return None
            if status != "closed":
                return False
    return True


def plan_shelter_action(facts):
    """下一动作建议:未封闭→封口;信息不足→先观察;全封闭→等待。
    只基于 facts 本身,不读任何管理员通道。"""
    verdict = assess_sheltered(facts)
    if verdict is None:
        return {"action": "observe_more",
                "reason": "enclosure_facts_incomplete_fail_closed"}
    if verdict:
        return {"action": "wait", "reason": "sheltered_wait_for_dawn"}
    above = cell_status(facts.get("above"))
    if above != "closed":
        return {"action": "seal_above",
                "reason": "overhead_not_solid"}
    return {"action": "seal_sides",
            "reason": "open_or_unreliable_side"}

class ShelterRun:
    """一次避难的状态机:记录每步动作回执,只有全部关键步真实
    completed 且终局 facts 三值判定为 True 才 sheltered;
    任一关键步 failed/cancelled → False;facts unknown → False
    (fail-closed,不冒充)。"""

    def __init__(self):
        self.events = []

    def record_result(self, step, receipt):
        state = str((receipt or {}).get("state") or "")
        ok = state == "completed"
        self.events.append({"step": step, "state": state,
                            "reason": str((receipt or {}).get(
                                "reason") or "")[:400]})
        return ok

    def finalize(self, facts):
        verdict = assess_sheltered(facts)
        if verdict is not True:
            return False
        if any(e["state"] != "completed" for e in self.events):
            return False
        return True

    def summary(self):
        return {"events": self.events}
