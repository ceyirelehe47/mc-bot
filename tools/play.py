# -*- coding: utf-8 -*-
"""LLM 直控玩层:桥 HTTP 客户端 + 操作执行 + 事件轮询。复用 mc2a07ar_live 生命周期。

CLI:
  python play.py observe                       # 全量观察(含 inventory/pos/当前任务)
  python play.py view                          # 紧凑认知视图(机会/家园/事件)
  python play.py local [radius] [detail]       # inspect-local 身体周边
  python play.py inspect <ref> [detail]        # 深查一个 semantic 对象
  python play.py graphs [graph_id]             # TaskGraph 状态
  python play.py do <op> '<json-args>' [timeout_s]   # 提交操作并等到终态
  python play.py ctl <execution_id> <pause|resume|cancel>
  python play.py events [cursor] [wait_s]      # 事件长轮询
  python play.py status [execution_id]
  python play.py rcon <cmd...>                 # 服务器 RCON 直通(仅观察用)
"""
import json, os, sys, time

ROOT = r"D:\code\mc-experiment"
TOOLS = os.path.dirname(os.path.abspath(__file__))
for _p in (os.path.join(ROOT, "mc2a07ar-work", "drivers"), TOOLS):
    if _p not in sys.path:
        sys.path.insert(0, _p)
import mc2a07ar_live as L  # noqa: E402

# 租约状态文件不放实验根,归到 mc-bot/.build/
L.STATE = os.path.join(TOOLS, "..", ".build", "play-lease-state.json")


class Session:
    def __init__(self, owner="llm-play"):
        lease = L.lease_for(owner=owner, wait_s=60)
        if not lease:
            raise RuntimeError("no control lease available")
        self.lease = lease
        self.renewed = time.time()
        r = L.observe(self.lease)  # 死亡/重启后对账解锁
        if not r.get("ok"):
            raise RuntimeError("observe reconcile failed: %s" % json.dumps(r)[:200])

    def keepalive(self):
        if time.time() - self.renewed > 15:
            r = L.call("POST", "/v1/lease/renew", self.lease)
            if not r.get("ok"):
                lease = L.lease_for(owner="llm-play", wait_s=30)
                if lease:
                    self.lease = lease
                    L.observe(self.lease)
            self.renewed = time.time()
    def day_phase(self):
        try:
            v = self.view()
            return v["data"]["scene"]["environment"]["day_phase"]
        except Exception:
            return "?"

    def view(self, retries=3):
        for _ in range(retries):
            v = L.call("POST", "/v1/view", None, {})  # 认知查询无租约也可
            if v.get("ok"):
                return v
            time.sleep(1.5)
        return v

    def observe(self):
        return L.observe(self.lease)

    def observe(self):
        return L.observe(self.lease)

    def inspect_local(self, radius=6, detail="summary"):
        return L.call("POST", "/v1/inspect-local?radius=%d&detail=%s" % (radius, detail), None, {})

    def inspect(self, ref, detail="summary"):
        from urllib.parse import quote
        return L.call("POST", "/v1/inspect?ref=%s&detail=%s" % (quote(ref, safe=""), quote(detail)), None, {})

    def graphs(self, graph_id=None):
        return L.call("GET", "/v1/graphs" + ("/" + graph_id if graph_id else ""), self.lease)

    def status(self, ex_id=None):
        return L.execution(self.lease, ex_id) if ex_id else L.status()

    def do(self, op, args, timeout_s=180, tag=None):
        self.keepalive()
        tag = tag or ("play-" + op)
        r = L.submit(self.lease, op, args, tag)
        if not r.get("ok"):
            # submit 409(执行槽被占,常见于上个进程被杀后 in-flight 执行残留):
            # 等服务器 stall 兜底(<=120s)释放后重试一次
            for _ in range(65):
                st = self.status()["data"]
                if not st.get("active_execution"):
                    break
                time.sleep(2)
            r = L.submit(self.lease, op, args, tag)
            if not r.get("ok"):
                time.sleep(5)  # 退避:防上层空转循环打爆桥
                return {"op": op, "submit": r}
        ex_id = r["data"]["execution_id"]
        res, trail = L.wait_terminal(self.lease, ex_id, timeout_s=timeout_s)
        if res.get("state") == "TIMEOUT":  # 主动取消释放执行槽(带重试+对账)
            for attempt in range(4):
                try:
                    c = L.control(self.lease, ex_id, "cancel", "play-timeout-cancel")
                    if c.get("ok"):
                        break
                except Exception:
                    pass
                self.keepalive()
                time.sleep(2)
        # 等执行槽真正释放(最多 50s),避免下一个动作撞 execution_in_progress
        for _ in range(25):
            st = self.status()["data"]
            if not st.get("active_execution"):
                break
            time.sleep(2)
        return {"op": op, "execution_id": ex_id, "terminal": res}

    def ctl(self, ex_id, action):
        return L.control(self.lease, ex_id, action, "play-ctl")

    def events(self, cursor=None, wait_s=20):
        path = "/v1/events" + ("?cursor=%s&waitMs=%d" % (cursor, wait_s * 1000) if cursor else "?waitMs=%d" % (wait_s * 1000))
        return L.call("GET", path, None, {}, timeout=wait_s + 15)


def _print(obj):
    print(json.dumps(obj, ensure_ascii=False, indent=1))


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return 0
    cmd = a[0]
    if cmd == "rcon":
        print(L.rcon(" ".join(a[1:])))
        return 0
    s = Session()
    if cmd == "observe":
        _print(s.observe())
    elif cmd == "view":
        _print(s.view())
    elif cmd == "local":
        _print(s.inspect_local(int(a[1]) if len(a) > 1 else 6, a[2] if len(a) > 2 else "summary"))
    elif cmd == "inspect":
        _print(s.inspect(a[1], a[2] if len(a) > 2 else "summary"))
    elif cmd == "graphs":
        _print(s.graphs(a[1] if len(a) > 1 else None))
    elif cmd == "status":
        _print(s.status(a[1] if len(a) > 1 else None))
    elif cmd == "do":
        op, args = a[1], (json.loads(a[2]) if len(a) > 2 else {})
        t = int(a[3]) if len(a) > 3 else 180
        _print(s.do(op, args, timeout_s=t))
    elif cmd == "ctl":
        _print(s.ctl(a[1], a[2]))
    elif cmd == "events":
        _print(s.events(a[1] if len(a) > 1 and a[1] != "-" else None, int(a[2]) if len(a) > 2 else 20))
    else:
        print("unknown cmd:", cmd)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
