# -*- coding: utf-8 -*-
"""MC-RCF-1-R3D D01–D06 受阻恢复真实验证切片(非计分诊断)。

平台区 (146..174, 100..108, 146..174):远离 S01/S02 自然场景区
(94..104/~93-97/-78..-64),诊断不污染正式场景。fixture 全部经 RCON
在切片标记前布置(armed 前准备期语义);技能调用走生产路径
tools/rcf1_g5_drive.mine_blocks(与正式 G5 完全同一控制流)。

切片定义(ACCEPTANCE §1):
  D01 首项正常到达并采集(正对照)
  D02 首选目标全站位受阻、次目标可用 → 次目标被真实采到
  D03 同目标主站位被挡、备用站位可达 → 经备用站位采到
  D04 全部候选/站位不可用 → 有界 blocked 返回,无第三次同组合盲发
  D05 取消/驱动进程死亡 → 旧请求不复生、无残留,后续技能正常
  D06 目标禁尽后环境变化(次目标被采/障碍移除)→ 重新候选并采到

用法:python tools/rcf1_d_diag.py <d01|d02|d03|d04|d05|d06|d06b|all>
输出:每切片 JSON 结果;原始时间线在 D:\\mc-rcf1-raw\\g5r3-<slice>.jsonl
"""
import json
import subprocess
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402
import rcf1_g5 as G  # noqa: E402
import rcf1_g5_drive as DRV  # noqa: E402

OAK = "minecraft:oak_log"
STONE = "minecraft:stone"
# 平台用 dirt:blockCategory 无类别 → 感知不跟踪;石平台会把
# BLOCK_CANDIDATE_LIMIT=128 全部挤占,原木永远进不了候选(D01 实测)。
DIRT = "minecraft:dirt"

# 站位偏移(与 rcf1_mine_skill.STANCES 一致)
STANCE_OFFS = ((0, -3), (0, -2), (0, -4), (0, 2), (-2, 0), (2, 0))

PLATFORM_Y = 100          # 平台面(站立地面)


def rcon(cmd):
    out = (E.rcon(cmd) or "").strip()
    return out


def _note(run_id, kind, data):
    G._append(run_id, kind, data)
    print(json.dumps({"slice": run_id, "kind": kind, "data": data},
                     ensure_ascii=False), flush=True)





def clear_zone():
    """清除上一切片的构造物(恢复平台面)。"""
    rcon("fill 146 101 146 174 109 175 minecraft:air")
    rcon("fill 146 100 146 174 100 174 %s" % DIRT)
    time.sleep(0.5)


def build_platform():
    """一次性平台 + 清空(诊断准备期)。
    先把 Bob 传到目标区上空加载区块(fill 在未加载区块静默无效,
    D01 首跑实测教训),再构建,最后读回验证。"""
    rcon("difficulty peaceful")   # 诊断 fixture:排除敌怪干扰(非生存
    #                                # 测试;D05/D06 实测 3 次骷髅击杀翻转会话)
    rcon("tp Bob 160 104 160")
    time.sleep(4)
    rcon("fill 145 99 145 175 99 175 %s" % DIRT)      # 基座
    rcon("fill 145 100 145 175 109 175 minecraft:air")  # 清空
    rcon("fill 146 100 146 174 100 174 %s" % DIRT)     # 站立面
    time.sleep(1)
    readback = rcon("execute if block 152 100 152 %s" % DIRT)
    if "Test passed" not in readback:
        raise RuntimeError("platform-build-unverified: %r" % readback[:80])
    time.sleep(1.5)

def occupy_stances(tx, tz, py=PLATFORM_Y):
    """占用目标全部站位格(站位格+头顶格置石)→ 预检/导航均不可达。"""
    for dx, dz in STANCE_OFFS:
        for y in (py, py + 1):
            rcon("setblock %d %d %d %s" % (tx + dx, y, tz + dz, STONE))


def pillar_log(tx, tz, top_y=104, capped=False):
    """柱顶原木:地面合法站位无法命中(射线被柱身挡);柱体 dirt
    不占感知候选预算,顶原木可见 → 真实'可见但不可采'目标。
    capped=True 加 3×3 泥盖:封死柱底贴脸仰角射线(D04 实测第 9
    次尝试从柱底掠过柱顶边缘真实采到——技能合法但非本切片目标)。"""
    for y in range(101, top_y):
        rcon("setblock %d %d %d %s" % (tx, y, tz, DIRT))
    rcon("setblock %d %d %d %s" % (tx, top_y, tz, OAK))
    if capped:
        rcon("fill %d %d %d %d %d %d %s" % (
            tx - 1, top_y + 1, tz - 1, tx + 1, top_y + 1, tz + 1, DIRT))


def encased_log(tx, tz, ty=104):
    """全封闭原木:柱+同层四邻+顶盖全 dirt → 任何合法站位射线均
    不可达,感知也不可见 → '观察持续空'分支的有界返回(D04)。
    注:无盖柱顶原木的 log 层水平暴露,第 9 次尝试会从缝隙真实
    采到(实测;禁令合同仍成立:primary/s2 各 2 次即禁)。"""
    for y in range(101, ty):
        rcon("setblock %d %d %d %s" % (tx, y, tz, DIRT))
    rcon("setblock %d %d %d %s" % (tx, ty, tz, OAK))
    rcon("fill %d %d %d %d %d %d %s" % (
        tx - 1, ty, tz - 1, tx + 1, ty, tz + 1, DIRT))
    rcon("setblock %d %d %d %s" % (tx, ty, tz, OAK))
    rcon("fill %d %d %d %d %d %d %s" % (
        tx - 1, ty + 1, tz - 1, tx + 1, ty + 1, tz + 1, DIRT))


def walled_log(tx, tz):
    """墙+站位格阻挡的地面原木:南 3 站位被 5 高石墙真实挡死
    (goto stall/facing timeout),北/西/东站位格被石占(预检瞬断)。
    全部 6 站位快速失败(D03 实测 south 站位真实失败;D02 实测
    预检跳过)。"""
    rcon("setblock %d 101 %d %s" % (tx, tz, OAK))
    rcon("fill %d 100 %d %d 104 %d %s" % (
        tx - 2, tz - 5, tx + 2, tz - 1, STONE))
    for dx, dz in ((0, 2), (-2, 0), (2, 0)):
        for y in (PLATFORM_Y, PLATFORM_Y + 1):
            rcon("setblock %d %d %d %s" % (tx + dx, y, tz + dz, STONE))


def tp_bob(x, z):
    rcon("tp Bob %0.1f %d %0.1f" % (x, PLATFORM_Y + 1, z))
    time.sleep(1.5)


def day():
    rcon("time set day")


def clear_mobs():
    """诊断准备期清怪(D05 实测:平台白天被骷髅击杀,死亡重生翻转
    body session → mine outcome_unknown)。仅诊断 fixture;正式 G5
    无此保护。"""
    for t in ("skeleton", "zombie", "creeper", "spider", "witch",
              "drowned", "stray", "husk", "enderman", "phantom"):
        rcon("kill @e[type=minecraft:%s,distance=..64]" % t)


def run_slice(slice_id, s, block_id, near, need, note, budget_s):
    _note(slice_id, "d-armed", {"note": note, "near": list(near),
                                "need": need, "budget_s": budget_s})
    t0 = time.time()
    res = DRV.mine_blocks(slice_id, s, block_id, tuple(near), need,
                          note, budget_s=budget_s)
    res["wall_s"] = round(time.time() - t0, 1)
    _note(slice_id, "d-result", {"result": res})
    return res


def _post_check(slice_id, s):
    """切片后环境核验:无活动执行/无未清债务。"""
    st = ((s.status().get("data") or {}))
    _note(slice_id, "d-post-check", {
        "active_execution": st.get("active_execution"),
        "needs_reconcile": st.get("needs_reconcile")})


def d01(s):
    """正对照:单柱原木,主站位可用。"""
    clear_zone()
    day()
    rcon("setblock 160 101 150 %s" % OAK)
    tp_bob(160, 154)
    res = run_slice("r3d-d01", s, OAK, (160, 101, 150), 1,
                    "D01 正对照:首项正常", 240)
    _post_check("r3d-d01", s)
    ok = (res["state"] == "completed" and res["got"] == 1
          and res["attempts"] <= 2)
    return ok, res


def d02(s):
    """首选全站位受阻、次选可用:必须真实采到次选。"""
    clear_zone()
    day()
    pillar_log(162, 150)                # B1:可见但全站位不可达
    rcon("setblock 168 101 150 %s" % OAK)  # B2:正常可用
    tp_bob(163, 154)
    res = run_slice("r3d-d02", s, OAK, (163, 101, 150), 1,
                    "D02 首选受阻次选可用", 300)
    _post_check("r3d-d02", s)
    # 次选被真实提交并可从日志复核
    log = open(G._log_path("r3d-d02"), encoding="utf-8").read()
    mined_168 = '"at": [168, 101, 150]' in log or \
                '"at":[168,101,150]' in log
    ok = (res["state"] == "completed" and res["got"] == 1
          and mined_168)
    return ok, res


def d03(s):
    """同目标主站位被挡、备用站位可达。"""
    clear_zone()
    day()
    rcon("setblock 160 101 158 %s" % OAK)
    # primary 站位 (160,101,155):goto 宽松到达半径 2.5 ⇒ 必须盖住
    # 整个 2.5 半径球(x158..162 × z145..149 不对——目标在 z158:
    # 球 = x158..162 × z153..157,y100..102)才能真失败。
    rcon("fill 158 100 153 162 104 157 %s" % STONE)
    tp_bob(166, 160)
    res = run_slice("r3d-d03", s, OAK, (160, 101, 158), 1,
                    "D03 主站位被挡换备用", 240)
    _post_check("r3d-d03", s)
    ok = res["state"] == "completed" and res["got"] == 1
    return ok, res


def d04(s):
    """全部站位不可用:有界 blocked,无第三次同组合盲发。"""
    clear_zone()
    day()
    encased_log(160, 150)
    tp_bob(160, 154)
    res = run_slice("r3d-d04", s, OAK, (160, 101, 150), 1,
                    "D04 全候选不可达", 420)
    _post_check("r3d-d04", s)
    ok = (res["state"] == "blocked" and res["got"] == 0
          and res["reason"] in ("all-combos-banned",
                                "no-visible-candidate"))
    return ok, res


def d05(s):
    """取消 + 驱动进程死亡:旧请求不复生、无残留,后续技能正常。"""
    clear_zone()
    day()
    clear_mobs()
    slice_id = "r3d-d05"
    _note(slice_id, "d-armed", {"note": "D05 取消/会话失效"})
    # (a) 显式取消长导航(远角 ~40 格,2s 时仍在途中)
    ex, err = s.submit("goto", {"x": 146, "y": PLATFORM_Y, "z": 174},
                       tag="g5r3-%s" % slice_id)
    time.sleep(2)
    c = s.ctl(ex, "cancel")
    term_state = None
    for _ in range(15):
        st = s.poll(ex)
        if st.get("state") in ("cancelled", "failed", "completed"):
            term_state = st.get("state")
            break
        time.sleep(1)
    st_end = ((s.status().get("data") or {}))
    _note(slice_id, "d05-cancel", {
        "execution_id": ex, "cancel_ok": c.get("ok"),
        "terminal_state": term_state,
        "active_after": st_end.get("active_execution"),
        "needs_reconcile": st_end.get("needs_reconcile")})
    ok_a = (term_state == "cancelled"
            and not st_end.get("active_execution")
            and not st_end.get("needs_reconcile"))
    # (b) 正常小切片证明无残留输入/导航干扰
    rcon("setblock 160 101 150 %s" % OAK)
    tp_bob(160, 154)
    res = run_slice(slice_id, s, OAK, (160, 101, 150), 1,
                    "D05 取消后继续正常采集", 240)
    ok = ok_a and res["state"] == "completed" and res["got"] == 1
    _post_check(slice_id, s)
    return ok, {"cancel_clean": ok_a, "mine": res}


def d05b():
    """驱动进程死亡(mid-skill kill):旧请求不复活,新驱动正常。"""
    clear_zone()
    day()
    slice_id = "r3d-d05b"
    _note(slice_id, "d-armed", {"note": "D05b 驱动进程死亡"})
    pillar_log(160, 150, capped=True)  # 全站位不可达 → 技能长时间运行
    tp_bob(160, 154)
    proc = subprocess.Popen(
        [sys.executable, "tools/rcf1_g5_drive.py", "mine", slice_id,
         "--block", OAK, "--near", "160", "101", "150", "--need", "1",
         "--note", "D05b 待杀驱动", "--budget", "360"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    time.sleep(25)             # 进入站位轮试中段
    proc.kill()
    proc.wait(timeout=10)
    time.sleep(8)              # 桥侧租约/执行收敛
    st = None
    s = play.Session("r3d-diag2")
    st = ((s.status().get("data") or {}))
    _note(slice_id, "d05b-after-kill", {
        "active_execution": st.get("active_execution"),
        "needs_reconcile": st.get("needs_reconcile")})
    # 清障碍后新驱动正常完成(柱降为地面原木)
    rcon("fill 160 101 150 160 103 150 minecraft:air")
    rcon("setblock 160 101 150 %s" % OAK)
    tp_bob(160, 154)
    res = DRV.mine_blocks(slice_id, s, OAK, (160, 101, 150), 1,
                          "D05b 杀后新驱动采集", budget_s=240)
    _note(slice_id, "d-result", {"result": res})
    _post_check(slice_id, s)
    ok = (res["state"] == "completed" and res["got"] == 1
          and not st.get("active_execution"))
    return ok, {"after_kill_status": st, "mine": res}


def d06(s):
    """次目标被采(净进展)→ 指纹变化 → 禁令解除,原目标重新候选。"""
    clear_zone()
    day()
    walled_log(160, 158)                # E1:6 站位快速失败(3 墙挡+3 预检)
    rcon("setblock 160 101 162 %s" % OAK)  # E2:可用(E1 北侧,采后仍见 E1)
    rcon("setblock 164 101 162 %s" % OAK)  # E3:可用(制造禁后净进展)
    tp_bob(163, 160)
    res = run_slice("r3d-d06", s, OAK, (161, 101, 158), 3,
                    "D06 净进展解禁+重试", 420)
    _post_check("r3d-d06", s)
    log = open(G._log_path("r3d-d06"), encoding="utf-8").read()
    lifted = "bans-lifted-new-facts" in log
    e1_banned_evt = '"what": "combo-banned"' in log and \
        '"160,101,158|' in log
    ok = (res["got"] >= 2 and lifted and e1_banned_evt)
    return ok, {"got": res["got"], "state": res["state"],
                "bans_lifted_seen": lifted,
                "e1_banned_seen": e1_banned_evt, "result": res}


def d06b(s):
    """障碍移除(诊断期环境变化)→ 原失败目标恢复可用。"""
    slice_id = "r3d-d06b"
    # 前置:E1 仍被围(来自 d06 的残局),先证明仍 blocked
    clear_zone()
    day()
    walled_log(160, 158)
    tp_bob(163, 162)
    res1 = run_slice(slice_id, s, OAK, (160, 101, 158), 1,
                     "D06b 阶段1:仍受阻", 360)
    # 环境变化:拆墙+清站位格(armed 后无任何 tp/give 救动作本身)
    rcon("fill 158 100 153 162 105 160 minecraft:air")
    rcon("setblock 160 101 158 %s" % OAK)
    _note(slice_id, "d06b-env-change",
          {"change": "wall+stance-stones-removed"})
    res2 = run_slice(slice_id, s, OAK, (160, 101, 158), 1,
                     "D06b 阶段2:障碍移除后", 240)
    _post_check(slice_id, s)
    ok = (res1["state"] in ("blocked", "budget")
          and res1["got"] == 0
          and res2["state"] == "completed" and res2["got"] == 1)
    return ok, {"before": res1, "after": res2}


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    s = play.Session("r3d-diag")
    build_platform()
    slices = {"d01": d01, "d02": d02, "d03": d03, "d04": d04,
              "d05": d05, "d05b": d05b, "d06": d06, "d06b": d06b}
    order = ["d01", "d02", "d03", "d04", "d05", "d05b", "d06", "d06b"]
    todo = order if which == "all" else [which]
    summary = {}
    for name in todo:
        fn = slices[name]
        try:
            if name == "d05b":
                ok, detail = d05b()
            else:
                ok, detail = fn(s)
        except Exception as exc:  # noqa: BLE001
            ok, detail = False, {"error": "%s: %s"
                                        % (exc.__class__.__name__, exc)}
        summary[name] = {"ok": bool(ok), "detail": detail}
        print("== %s: %s ==" % (name, "OK" if ok else "FAIL"), flush=True)
    rcon("difficulty normal")     # 恢复;正式 G5 场景不得用
    print(json.dumps({"summary": summary}, ensure_ascii=False, indent=1))
    return 0 if all(v["ok"] for v in summary.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
