# -*- coding: utf-8 -*-
"""Graph LIVE 驱动公共模块: 稳健租约(固定 owner, journal 恢复的旧租约会过期)+桥调用。"""
import importlib.util
import json
import time

_spec = importlib.util.spec_from_file_location(
    "mc2a0_live", r"D:\code\mc-experiment\mc2a0_live.py")
live = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(live)

OWNER = "graph-core-live-driver"


def call(method, path, lease=None, body=None, headers=None, timeout=20):
    return live.call(method, path, lease, body, headers, timeout)


def get_lease():
    """固定 owner;先续缓存,失败则等待服务端旧租约过期后新建。"""
    import os
    for attempt in range(20):
        if os.path.exists(live.LEASE_FILE):
            try:
                st = json.load(open(live.LEASE_FILE, encoding="utf-8"))
                live.call("POST", "/v1/lease/renew", st["token"])
                st["ts"] = time.time()
                json.dump(st, open(live.LEASE_FILE, "w", encoding="utf-8"))
                return st["token"]
            except Exception:
                os.remove(live.LEASE_FILE)
        try:
            data = live.call("POST", "/v1/lease", headers={"X-Owner-Id": OWNER})["data"]
            st = {"token": data["token"], "ts": time.time()}
            json.dump(st, open(live.LEASE_FILE, "w", encoding="utf-8"))
            return st["token"]
        except Exception as e:
            if "body_controlled_by_another" in str(e):
                time.sleep(5)
                continue
            raise
    raise RuntimeError("lease unavailable after retries")
