# -*- coding: utf-8 -*-
"""R3D/I08 诊断:Tom's 终端屏幕/授权/网络逐相观察(非计分)。

复现 R3C fixture(305..308,120,296)并逐秒轮询:deposit 期间客户端
Screen 状态(present/adapter/sync)、库存、箱子/网络内容,定位
R3C"screen_opens=true 但零转移"与本次"authorization 不来"的确切
阶段差。
"""
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402


def rcon(cmd):
    return (E.rcon(cmd) or "").strip()


def build():
    tx, ty, tz = 305, 120, 296
    rcon("tp Bob 306.5 122 297.5")
    time.sleep(3)
    for cmd in (
            ["setblock 306 120 297 minecraft:dirt",
             "setblock 305 121 296 minecraft:air",
             "setblock 306 121 296 minecraft:air",
             "setblock 306 122 296 minecraft:air",
             "setblock 305 122 296 minecraft:air",
             "setblock 306 122 297 minecraft:air",
             "setblock 307 120 297 minecraft:dirt",
             "setblock 307 121 297 minecraft:air",
             "setblock 306 121 297 minecraft:air",
             "setblock 305 123 296 minecraft:air",
             "setblock 306 123 296 minecraft:air",
             "setblock 306 123 297 minecraft:air",
             "setblock 306 120 296 minecraft:air",
             "setblock 307 120 296 minecraft:air",
             "setblock %d %d %d minecraft:chest" % (tx + 3, ty, tz),
             "setblock %d %d %d toms_storage:inventory_connector"
             "[facing=east]" % (tx + 2, ty, tz),
             "setblock %d %d %d toms_storage:inventory_cable"
             % (tx + 1, ty, tz),
             "setblock %d %d %d toms_storage:storage_terminal"
             % (tx, ty, tz)]):
        rcon(cmd)
    rcon("item replace block %d %d %d container.0 with minecraft:dirt 8"
         % (tx + 3, ty, tz))
    rcon("clear Bob")
    rcon("item replace entity Bob inventory.9 with minecraft:cobblestone 16")
    rcon("tp Bob 306.5 121 297.5")
    time.sleep(3)


def main():
    build()
    s = play.Session("r3d-i08-probe")
    ex, err = s.submit("goto", {"x": 306, "y": 121, "z": 297,
                                "face_x": 305, "face_y": 120,
                                "face_z": 296}, tag="i08-face")
    r = s.term(ex, timeout_s=60)[0]
    print(json.dumps({"face": r.get("state"),
                      "reason": str(r.get("reason"))[:90]}), flush=True)
    ex, err = s.submit("deposit", {}, tag="i08-probe")
    if ex is None:
        print(json.dumps({"submit_error": str(err)}))
        return 1
    t0 = time.time()
    samples = []
    while time.time() - t0 < 40:
        st = s.poll(ex)
        obs = ((s.observe().get("data") or {}).get(
            "observation") or {})
        screen = obs.get("screen") or {}
        samples.append({
            "t": round(time.time() - t0, 1),
            "exec": st.get("state"),
            "reason": str(st.get("reason"))[:70],
            "screen_present": screen.get("present"),
            "screen_adapter": screen.get("adapter_id"),
            "screen_title": str(screen.get("title"))[:40],
            "cobble": (obs.get("inventory") or {}).get(
                "minecraft:cobblestone")})
        if st.get("state") in ("completed", "failed", "cancelled"):
            break
        time.sleep(1.5)
    final = s.poll(ex)
    print(json.dumps({"final": final, "samples": samples},
                     ensure_ascii=False, indent=1))
    print("chest:", rcon("data get block 308 120 296 Items")[:120])
    return 0


if __name__ == "__main__":
    sys.exit(main())
