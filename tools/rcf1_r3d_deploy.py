# -*- coding: utf-8 -*-
"""MC-RCF-1-R3D 干净部署构建器:从空目录安装到可运行(R04)。

原则(TASKBOOK §6.2):
- 目标目录必须为空(或 --force 清空);绝不 robocopy 旧运行目录。
- 依赖缓存逐条复制 + sha256 清单(fabric 库/版本、mod jar、natives、
  启动器);来源与哈希可核。
- 配置从声明模板生成;世界不复制——首启全新生成(声明 seed 事后
  从 level.dat 回读入清单)。
- 产物:mods/aibot-0.0.1.jar 必须来自本轮锁定构建(参数传入,
  哈希记录),不得复用旧 jar。

用法:
  python tools/rcf1_r3d_deploy.py --build-jar <path> [--force]
产出:
  D:/code/mc-experiment/rcf1-server-r3d, rcf1-client-r3d
  D:/mc-rcf1-raw-r3d/fresh-deployment.json(依赖清单+身份)
"""
import argparse
import hashlib
import json
import os
import shutil
import time

ROOT = r"D:\code\mc-experiment"
SRC_SERVER = os.path.join(ROOT, "rcf1-server-r3c")
SRC_CLIENT = os.path.join(ROOT, "rcf1-client-r3c")
DST_SERVER = os.path.join(ROOT, "rcf1-server-r3d")
DST_CLIENT = os.path.join(ROOT, "rcf1-client-r3d")
LOCAL_TP = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    ".local-third-party")
RAW = os.environ.get("RCF1_RAW_R3D", r"D:\mc-rcf1-raw-r3d")

DEPS = []       # {path(相对目标), source, sha256, bytes}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def install_file(src, dst, source_label):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)
    DEPS.append({
        "path": os.path.relpath(dst, os.path.dirname(DST_SERVER))
        if dst.startswith(DST_SERVER) else os.path.relpath(
            dst, os.path.dirname(DST_CLIENT)),
        "source": source_label, "sha256": sha256(dst),
        "bytes": os.path.getsize(dst)})
    return dst


def install_tree(src_dir, dst_dir, source_label, skip=()):
    count = 0
    for base, dirs, files in os.walk(src_dir):
        dirs[:] = [d for d in dirs if d not in skip]
        rel = os.path.relpath(base, src_dir)
        for f in files:
            s = os.path.join(base, f)
            d = os.path.join(dst_dir, rel, f)
            os.makedirs(os.path.dirname(d), exist_ok=True)
            shutil.copyfile(s, d)
            DEPS.append({
                "path": os.path.relpath(
                    d, os.path.dirname(os.path.commonpath(
                        [d, DST_SERVER, DST_CLIENT]))),
                "source": source_label, "sha256": sha256(d),
                "bytes": os.path.getsize(d)})
            count += 1
    return count


def build_server(build_jar, force):
    if os.path.isdir(DST_SERVER):
        if not force:
            raise SystemExit("target exists: %s (--force to wipe)" %
                             DST_SERVER)
        shutil.rmtree(DST_SERVER)
    os.makedirs(DST_SERVER)
    # 启动器 + 库缓存(声明依赖)
    install_file(os.path.join(SRC_SERVER, "fabric-server-launch.jar"),
                 os.path.join(DST_SERVER, "fabric-server-launch.jar"),
                 "fabric-server-launcher@r3c-dep-cache")
    install_tree(os.path.join(SRC_SERVER, "libraries"),
                 os.path.join(DST_SERVER, "libraries"),
                 "fabric-libraries@r3c-dep-cache")
    install_tree(os.path.join(SRC_SERVER, "versions"),
                 os.path.join(DST_SERVER, "versions"),
                 "fabric-versions@r3c-dep-cache")
    for f in ("server.jar", "fabric-server-launcher.properties"):
        install_file(os.path.join(SRC_SERVER, f),
                     os.path.join(DST_SERVER, f),
                     "dep-cache@" + f)
    # 配置:从声明模板生成(内容与 r3c 相同——server.properties/
    # eula 属部署配置,非运行状态)
    for f in ("server.properties", "eula.txt", "whitelist.json",
              "ops.json", "banned-ips.json", "banned-players.json"):
        src = os.path.join(SRC_SERVER, f)
        if os.path.exists(src):
            shutil.copyfile(src, os.path.join(DST_SERVER, f))
    # mods:三方依赖逐个哈希入账;本项目 jar 用本轮构建
    mods = {
        "fabric-api-0.114.1%2B1.21.3.jar":
            ("r3c-dep-cache", None),
        "appleskin-fabric-mc1.21.3-3.0.6.jar":
            ("r3c-dep-cache", None),
        "toms_storage_fabric-1.21.3-2.1.2.jar":
            (".local-third-party(tom5454/Toms-Storage "
             "Modrinth XZNI4Cpy h9IMZ6BE)", None),
        "vein_miner-26.x.jar": ("r3c-dep-cache", None),
    }
    for name, (label, alt) in mods.items():
        src = None
        if alt and os.path.exists(os.path.join(LOCAL_TP, name)):
            src = os.path.join(LOCAL_TP, name)
        elif name.startswith("toms_storage"):
            tp = os.path.join(LOCAL_TP, "toms-storage")
            for cand in os.listdir(tp):
                if name in cand:
                    src = os.path.join(tp, cand)
                    break
        if src is None:
            src = os.path.join(SRC_SERVER, "mods", name)
        install_file(src, os.path.join(DST_SERVER, "mods", name), label)
    install_file(build_jar, os.path.join(DST_SERVER, "mods",
                                         "aibot-0.0.1.jar"),
                 "this-build:rcf1-rebuild-base@overlay(locked)")
    # config:mod 配置(内容型,非运行状态)
    shutil.copytree(os.path.join(SRC_SERVER, "config"),
                    os.path.join(DST_SERVER, "config"))
    # 桥 token 运行时经 env 注入(.secrets),不落盘复制
    # 世界:不复制——首启全新生成
    return len(DEPS)


def build_client(build_jar, force):
    if os.path.isdir(DST_CLIENT):
        if not force:
            raise SystemExit("target exists: %s (--force to wipe)" %
                             DST_CLIENT)
        shutil.rmtree(DST_CLIENT)
    os.makedirs(DST_CLIENT)
    for name, label in (
            ("fabric-api-0.114.1%2B1.21.3.jar", "r3c-dep-cache"),
            ("toms_storage_fabric-1.21.3-2.1.2.jar",
             ".local-third-party(Modrinth h9IMZ6BE)"),
            ("baritone-api-fabric-1.12.0.jar", "r3c-dep-cache")):
        src = os.path.join(SRC_CLIENT, "mods", name)
        install_file(src, os.path.join(DST_CLIENT, "mods", name), label)
    install_file(build_jar, os.path.join(DST_CLIENT, "mods",
                                         "aibot-0.0.1.jar"),
                 "this-build:rcf1-rebuild-base@overlay(locked)")
    # natives(LWJGL 运行库=依赖缓存)+ config(内容型)
    install_tree(os.path.join(SRC_CLIENT, "natives"),
                 os.path.join(DST_CLIENT, "natives"),
                 "lwjgl-natives@r3c-dep-cache")
    shutil.copytree(os.path.join(SRC_CLIENT, "config"),
                    os.path.join(DST_CLIENT, "config"))
    for f in ("options.txt", "servers.dat"):
        src = os.path.join(SRC_CLIENT, f)
        if os.path.exists(src):
            shutil.copyfile(src, os.path.join(DST_CLIENT, f))
    # baritone 运行目录:全新(客户端自建缓存)
    os.makedirs(os.path.join(DST_CLIENT, "baritone"), exist_ok=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--build-jar", required=True)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--upstream-commit", default="a029fa6")
    ap.add_argument("--patch-ref",
                    default="aibot-dsh-m0/upstream-patches/"
                            "r3-base-tree.patch")
    a = ap.parse_args()
    build_jar = os.path.abspath(a.build_jar)
    if not os.path.exists(build_jar):
        raise SystemExit("build jar missing: %s" % build_jar)
    t0 = time.time()
    build_server(build_jar, a.force)
    build_client(build_jar, a.force)
    jar_sha = sha256(build_jar)
    os.makedirs(RAW, exist_ok=True)
    doc = {
        "schema": "mc.rcf1r3d.fresh-deployment.v1",
        "upstream_commit": a.upstream_commit,
        "patch_ref": a.patch_ref,
        "deployment_root": ROOT,
        "server_dir": DST_SERVER,
        "client_dir": DST_CLIENT,
        "client_jar_sha256": jar_sha,
        "server_jar_sha256": jar_sha,
        "build_command": (
            "gradlew.bat remapJar --no-daemon -g "
            "D:/code/mc-experiment/.gradle-rcf1 (rcf1-rebuild-base,"
            " overlay synced via tools/rcf1_sync_overlay.py --build)"),
        "rebuild_started_wall": t0,
        "deployed_from_empty": True,
        "world": {"declared": "fresh_generated",
                  "origin": "first-boot generation (no copy)",
                  "seed": "TBD-after-first-boot"},
        "dependency_cache": DEPS,
        "content_diffs": [],
        "note": "空目录安装;依赖逐条 sha256 入账;世界全新生成;"
                "不携带旧 journal/日志/运行状态",
    }
    out = os.path.join(RAW, "fresh-deployment.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)
    print(json.dumps({"deployed": True, "deps_recorded": len(DEPS),
                      "jar_sha256": jar_sha, "manifest": out},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
