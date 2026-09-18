# -*- coding: utf-8 -*-
"""LIVE-4: 后端接缝源码边界审计(对 mc-bot 生产提交 a767826 的工作树)。"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import r203_common as common

REPO = Path(r"D:\code\mc-bot")
EXT = REPO / "aibot-dsh-m0/aibot-overlay/src/main/java/io/github/zoyluo/aibot/external"
OUT = common.OUT / "07-audit"
FROZEN = "3df7fb9a0086f4eb0bd7249e0925f799c47ccb8e"

result = {"gate": "LIVE-4", "asserts": []}


def check(name: str, ok: bool, detail: str = "") -> None:
    result["asserts"].append({"name": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + ("  " + detail if detail else ""))


def git(*args: str) -> str:
    return subprocess.check_output(["git", "-C", str(REPO), *args], text=True).strip()


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    head = git("rev-parse", "HEAD")
    check("A0 审计对象为生产提交 a767826(分支自冻结 3df7fb9)", head.startswith("a767826"),
          f"HEAD={head}")

    backend = (EXT / "MinecraftBodyBackend.java").read_text(encoding="utf-8")
    driver = (EXT / "ServerFakePlayerExecutionDriver.java").read_text(encoding="utf-8")
    port = (EXT / "PhysicalExecutionDriver.java").read_text(encoding="utf-8")
    kernel = (EXT / "BridgeKernel.java").read_text(encoding="utf-8")

    # 1. MinecraftBodyBackend 无 Task 构造
    forbidden = ["new MoveTask(", "new GatherQuotaTask(", "new CraftTask(", "new SmeltTask(",
                 "new EatTask()", "new KnownResourceTask(", "new FarmTask(", "new BuildTask(",
                 "new StockpileTask(", "TaskManager.INSTANCE.assign("]
    hits = [f for f in forbidden if f in backend]
    check("A1 MinecraftBodyBackend 无任何 mutation Task 构造", not hits, f"hits={hits}")

    # 2. 全部 start/pause/resume/cancel 委托
    delegation = ("new ServerFakePlayerExecutionDriver" in backend
                  and "requireExecutionDriver().start(" in backend
                  and "executionDriver.pause()" in backend
                  and "executionDriver.resume()" in backend
                  and "executionDriver.cancel(reason)" in backend)
    check("A2 MinecraftBodyBackend 委托全部四个控制调用", delegation)

    # 3. ServerFakePlayerExecutionDriver 拥有全部 14 操作
    ops = ["goto", "gather", "craft", "smelt", "eat", "set_base", "deposit", "say",
           "register_home", "capture_home", "repair_home", "register_farm", "tend_farm",
           "mine_opportunity"]
    missing = [o for o in ops if f'"{o}"' not in driver]
    check("A3 ServerFakePlayerExecutionDriver 含全部 14 个操作映射", not missing,
          f"missing={missing}")
    check("A4 driver 持有 TaskManager assign 与实例替换哨兵",
          "TaskManager.INSTANCE.assign(" in driver and "currentBody.get()!=executionBody" in driver)

    # 4. PhysicalExecutionDriver 无 Minecraft/AIBot 依赖
    check("A5 PhysicalExecutionDriver 无 net.minecraft/AIBot 类引用",
          "net.minecraft" not in port and "AIPlayerEntity" not in port
          and "MinecraftServer" not in port)

    # 5. TaskGraphStore 与冻结基线字节一致
    diff = subprocess.run(["git", "-C", str(REPO), "diff", "--quiet", FROZEN, "--",
                           "aibot-dsh-m0/aibot-overlay/src/main/java/io/github/zoyluo/aibot/external/TaskGraphStore.java"],
                          capture_output=True)
    check("A6 TaskGraphStore.java 与冻结基线 3df7fb9 字节一致", diff.returncode == 0)

    # 6. graphRunNext dispatch 块不变且仍走 BridgeKernel.submit
    run = kernel.index("Map<String,Object> graphRunNext")
    cancel = kernel.index("Map<String,Object> graphCancel", run)
    block = kernel[run:cancel]
    frozen_kernel = git("show", f"{FROZEN}:aibot-dsh-m0/aibot-overlay/src/main/java/io/github/zoyluo/aibot/external/BridgeKernel.java")
    frun = frozen_kernel.index("Map<String,Object> graphRunNext")
    fcancel = frozen_kernel.index("Map<String,Object> graphCancel", frun)
    frozen_block = frozen_kernel[frun:fcancel]
    check("A7 graphRunNext->graphCancel dispatch 块与冻结基线逐字节一致",
          block == frozen_block)
    check("A8 dispatch 仍经 kernel.submit(supplied,d.requestId(),d.operation(),d.arguments())",
          "submit(supplied,d.requestId(),d.operation(),d.arguments())" in block
          and "backend.start(" not in block)

    # 9. 无越界实现
    scope = ["RealClient", "BotView", "Scheduler", "Agenda", "Streaming"]
    tree = git("diff", "--name-only", FROZEN, "HEAD")
    scope_hits = [s for s in scope if s in tree]
    check("A9 diff 范围无 RealClient/BotView/GUI/Scheduler 越界", not scope_hits,
          f"changed_files={tree.count(chr(10)) + 1}")
    common.write_json(OUT / "changed-files.json", {"files": tree.splitlines()})

    result["ok"] = all(a["ok"] for a in result["asserts"])
    common.write_json(OUT / "LIVE-4-result.json", result)
    print("LIVE-4", "PASS" if result["ok"] else "FAIL")


if __name__ == "__main__":
    main()
