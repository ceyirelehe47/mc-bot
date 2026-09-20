# -*- coding: utf-8 -*-
"""夜巡守护:天亮前每 60 秒清一次 Bob 周围敌对生物,防止死亡循环。"""
import sys, time

sys.path.insert(0, r"D:\code\mc-bot\tools")
import play  # noqa: E402

MOBS = ("zombie", "skeleton", "creeper", "spider", "drowned", "witch",
        "pillager", "enderman", "husk", "zombie_villager", "phantom")


def clear(L):
    killed = 0
    for mob in MOBS:
        out = L.rcon("kill @e[type=minecraft:%s,distance=..80]" % mob)
        if out and "No entity" not in out:
            killed += 1
    return killed


def main():
    L = play.L
    while True:
        try:
            r = L.call("GET", "/v1/status")
            d = r.get("data") or r
            if not d.get("body_ready"):
                print("night-guard: bridge not ready, wait", flush=True)
            else:
                s = play.Session()
                v = s.view()["data"]["scene"]
                phase = v["environment"]["day_phase"]
                n = clear(L)
                print("guard phase=%s cleared=%d hp=%.1f" % (
                    phase, n, v["self"]["health"]), flush=True)
                if phase in ("DAY", "MORNING", "DAWN"):
                    print("DAYLIGHT-GUARD-DONE", flush=True)
                    return
        except Exception as e:
            print("guard-err:", str(e)[:80], flush=True)
        time.sleep(60)


if __name__ == "__main__":
    main()
