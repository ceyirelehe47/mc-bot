# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 阶段2:洞内连续放置小切片(非计分)。

复现 R2 G5 阻断点(洞内 seal 首格成功后续全败),验证:
- 南开口几何(目标在南向 yaw≈0,规避当前客户端东/北向旋转链路问题)
- 封口顺序:先上格(站走廊,支撑=侧壁)→走入内部→下格(支撑=地面)
- PlaceAction 就近站位修复不把 Bob 引出洞
事实落盘 D:/mc-rcf1-raw/place-slice-r3.jsonl
"""
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402

OUT = r"D:\mc-rcf1-raw\place-slice-r3.jsonl"
ROWS = []


def evt(kind, **kw):
    row = {"t": round(time.time(), 3), "kind": kind}
    row.update(kw)
    ROWS.append(row)
    print(json.dumps(row, ensure_ascii=False), flush=True)


def rcon(cmd):
    return (E.rcon(cmd) or "").strip()


def setup():
    # 准备期(rcon 允许):南开口两格高走廊;内部 z6,走廊 z5,口 z4
    cols = []
    for z in (4, 5, 6):
        cols += ["setblock 5 %d %d minecraft:air" % (y, z)
                 for y in (106, 107)]
        cols.append("setblock 5 105 %d minecraft:dirt" % z)
        for x in (4, 6):
            cols += ["setblock %d %d %d minecraft:dirt" % (x, y, z)
                     for y in (105, 106, 107, 108)]
        cols.append("setblock 5 108 %d minecraft:dirt" % z)
    cols += ["clear Bob",
             "give Bob minecraft:oak_planks 8",
             "tp Bob 5.5 106 6.5",
             "time set day", "weather clear"]
    for c in cols:
        rcon(c)
    evt("setup-done", cells=cols[:3])


def main():
    setup()
    time.sleep(2)
    s = play.Session("r3-place-slice")
    evt("session-open")

    def act(op, args, note, timeout=90):
        ex, err = s.submit(op, args)
        if ex is None:
            evt("act", op=op, note=note, submit_err=str(err)[:120])
            return {"state": "failed", "reason": "submit-error"}
        res, _ = s.term(ex, timeout_s=timeout)
        evt("act", op=op, note=note, args=args,
            state=res.get("state"), reason=str(res.get("reason"))[:200])
        return res

    # 1) 站走廊(口内),先封上格 (5,107,4):支撑=侧壁 (4/6,107,4)
    act("goto", {"x": 5, "y": 106, "z": 5}, "走到走廊口")
    r1 = act("place", {"x": 5, "y": 107, "z": 4,
                       "item": "minecraft:oak_planks"},
             "封上格(先上后下)")
    top_ok = r1.get("state") == "completed"
    top_blk = rcon("execute if block 5 107 4 minecraft:oak_planks")
    evt("verify-top", receipt=top_ok, block="passed" in top_blk)

    # 2) 走入内部 (5,106,6),封下格 (5,106,4):支撑=地面 (5,105,4)
    act("goto", {"x": 5, "y": 106, "z": 6}, "退回洞内")
    r2 = act("place", {"x": 5, "y": 106, "z": 4,
                       "item": "minecraft:oak_planks"},
             "封下格(从洞内)")
    bot_ok = r2.get("state") == "completed"
    bot_blk = rcon("execute if block 5 106 4 minecraft:oak_planks")
    evt("verify-bottom", receipt=bot_ok, block="passed" in bot_blk)

    obs = s.observe().get("data", {}).get("observation", {})
    evt("final", pos=obs.get("position"),
        inv=obs.get("inventory"),
        health=obs.get("health"))
    result = {
        "top_ok": top_ok and "passed" in top_blk,
        "bottom_ok": bot_ok and "passed" in bot_blk,
        "consecutive": (top_ok and bot_ok
                        and "passed" in top_blk and "passed" in bot_blk),
    }
    evt("RESULT", **result)
    with open(OUT, "w", encoding="utf-8") as fh:
        for r in ROWS:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    return 0 if result["consecutive"] else 1


if __name__ == "__main__":
    sys.exit(main())
