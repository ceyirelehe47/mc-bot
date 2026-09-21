# -*- coding: utf-8 -*-
"""LLM 直控玩层:桥 HTTP 客户端 + 操作执行 + 事件轮询(rcf1 隔离环境)。

环境驱动为 tools/rcf1_env.py(端口/凭证/路径全部配置化,不再 import 历史实验目录)。

CLI:
  python play.py observe                       # 全量观察(含 inventory/pos/当前任务)
  python play.py view                          # 紧凑认知视图(机会/家园/事件)
  python play.py local [radius] [detail]       # inspect-local 身体周边
  python play.py inspect <ref> [detail]        # 深查一个 semantic 对象
  python play.py do <op> '<json-args>' [timeout_s] [--preempt]   # 提交操作并等到终态
  python play.py ctl <execution_id> <pause|resume|cancel>
  python play.py events [cursor] [wait_s]      # 事件长轮询
  python play.py status [execution_id]
  python play.py rcon <cmd...>                 # 服务器 RCON 直通(仅观察用)

G1 语义(MC-RCF-1):
- submit 失败按错误类别处理:参数/语义/容量类错误直接失败返回,绝不触碰进行中执行。
- 仅 409 execution_in_progress 且调用方显式 preempt=True 时才允许取消当前执行
  (取消对象 = status 里的确切 execution_id,即"明确授权+目标执行身份")。
- observe_required_before_new_work / body_unavailable 类:观察对账后有限重试,不 cancel。
- keepalive 续租失败只以本会话 owner 重新获取,不做固定 owner 越权重获。
"""
import json
import os
import sys
import time

TOOLS = os.path.dirname(os.path.abspath(__file__))
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)
import rcf1_env as E  # noqa: E402

# 错误类别(桥 BridgeFault 全集实测枚举)
SLOT_BUSY = {"execution_in_progress"}                       # 唯一可(经授权)抢占类
RECONCILE_RETRY = {"observe_required_before_new_work", "body_unavailable"}


def _fault(r):
    """从桥响应提取 (http_code, error_kind)。"""
    code = r.get("_http")
    if code is None and r.get("_unreachable"):
        return 0, "bridge_unreachable"
    body = r.get("_body") or ""
    try:
        kind = json.loads(body).get("error", "unknown")
    except Exception:
        kind = "unknown"
    return code, kind


class Session:
    def __init__(self, owner="llm-play"):
        self.owner = owner
        lease = E.acquire_lease(owner=owner, wait_s=60)
        if not lease:
            raise RuntimeError("no control lease available")
        self.lease = lease
        self.renewed = time.time()
        r = E.observe(self.lease)  # 死亡/重启后对账解锁
        if not r.get("ok"):
            # R1:会话翻转(客户端重启)瞬间拿到的旧 epoch 租约会被拒;
            # 重新获取一次并再对账(权威转移后的新租约)
            lease = E.acquire_lease(owner=owner, wait_s=15, reuse=False)
            if not lease:
                raise RuntimeError("observe reconcile failed: %s"
                                   % json.dumps(r)[:200])
            self.lease = lease
            r2 = E.observe(self.lease)
            if not r2.get("ok"):
                raise RuntimeError("observe reconcile failed twice: %s"
                                   % json.dumps(r2)[:200])

    # ---------- 租约 ----------

    def keepalive(self):
        if time.time() - self.renewed > 15:
            r = E.call("POST", "/v1/lease/renew", self.lease)
            if not r.get("ok"):
                # 只以本会话 owner 重新获取(不做固定 owner 越权重获);
                # 拿不到新租约就抛给上层,不静默继续用失效租约发指令。
                lease = E.acquire_lease(owner=self.owner, wait_s=15, reuse=False)
                if lease:
                    self.lease = lease
                    E.observe(self.lease)
                else:
                    raise RuntimeError("lease lost and not re-acquirable")
            self.renewed = time.time()

    # ---------- 只读查询(不抢控制租约) ----------

    def day_phase(self):
        try:
            v = self.view()
            return v["data"]["scene"]["environment"]["day_phase"]
        except Exception:
            return "?"

    def view(self, retries=3):
        v = None
        for _ in range(retries):
            v = E.view()
            if v.get("ok"):
                return v
            time.sleep(1.5)
        return v

    def observe(self):
        return E.observe(self.lease)

    def overview(self, kind_suffix=None):
        """分层查询·远景层:机会按 方向×类别 聚类成紧凑决策清单。"""
        v = self.view()
        sc = (v.get("data") or {}).get("scene") or {}
        me = (sc.get("self") or {}).get("block_position") or {}
        opps = ((sc.get("semantic_objects") or {}).get("resource_opportunities") or {}).get("items") or []
        buckets = {}
        for o in opps:
            sm = o.get("summary") or {}
            bl = sm.get("block", "")
            if kind_suffix and not bl.endswith(kind_suffix):
                continue
            d = sm.get("distance_blocks", 999)
            ring = "near<8" if d < 8 else "mid<16" if d < 16 else "far<24" if d < 24 else "vfar"
            short = bl.split(":")[-1]
            key = (ring, short)
            cur = buckets.get(key)
            if cur is None or d < cur[1]:
                buckets[key] = [key[0], d, short, 1, o.get("evidence_ref", "")]
            else:
                cur[3] += 1
                if d < cur[1]:
                    cur[1] = d
        out = sorted(buckets.values(), key=lambda r: r[1])
        return {"pos": me, "rings": out}

    def inspect_local(self, radius=6, detail="summary"):
        return E.inspect_local(radius, detail)

    def inspect(self, ref, detail="summary"):
        return E.inspect(ref, detail)

    def graphs(self, graph_id=None):
        return E.graphs(self.lease, graph_id)

    def status(self, ex_id=None):
        return E.execution(self.lease, ex_id) if ex_id else E.status()

    def events(self, cursor=None, wait_s=20):
        return E.events(cursor, wait_s)

    # ---------- 执行 ----------

    def submit(self, op, args, tag=None, preempt=False):
        """异步提交:成功返回 (execution_id, None);失败返回 (None, fault_dict)。

        fault_dict: {"http", "error", "preempted": bool}。失败分类处理:
        - 参数/语义/容量/未知错误:直接返回,不触碰进行中执行(C01)。
        - execution_in_progress + preempt=True:取消 status 中确切 execution_id,
          有界等待释放后重试一次;preempt=False 直接返回失败。
        - observe_required/body_unavailable:观察对账 + 有限重试,不 cancel。
        """
        self.keepalive()
        tag = tag or ("play-" + op)
        preempted = False
        for attempt in range(4):
            r = E.submit(self.lease, op, args, tag)
            if r.get("ok"):
                return r["data"]["execution_id"], None
            code, kind = _fault(r)
            if kind in RECONCILE_RETRY:
                E.observe(self.lease)
                time.sleep(2)
                continue
            if kind in SLOT_BUSY and preempt and attempt == 0:
                st = (self.status().get("data") or {})
                active = st.get("active_execution") or {}
                target = active.get("execution_id")
                if target:
                    try:
                        E.control(self.lease, target, "cancel", "llm-preempt-authorized")
                        preempted = True
                    except Exception:
                        pass
                    for _ in range(15):  # 有界等待释放(<=30s)
                        st = (self.status().get("data") or {})
                        if not st.get("active_execution"):
                            break
                        time.sleep(2)
                continue
            # 其余一切错误:准确失败,不 cancel 不重试
            return None, {"http": code, "error": kind, "preempted": preempted}
        return None, {"http": code, "error": kind, "preempted": preempted}

    def poll(self, ex_id):
        """非阻塞查执行状态。"""
        return (self.status(ex_id).get("data") or {})

    def term(self, ex_id, timeout_s=180, cancel_on_timeout=True):
        """等待终态;超时主动取消自己的执行释放槽位(有界,带对账)。"""
        res, trail = E.wait_terminal(self.lease, ex_id, timeout_s=timeout_s)
        if res.get("state") == "TIMEOUT" and cancel_on_timeout:
            for _ in range(4):
                try:
                    c = E.control(self.lease, ex_id, "cancel", "play-timeout-cancel")
                    if c.get("ok"):
                        break
                except Exception:
                    pass
                try:
                    self.keepalive()
                except RuntimeError:
                    break
                time.sleep(2)
            # 等待确定回执:终态或 outcome_unknown,不是清变量就当空闲
            res2, trail2 = E.wait_terminal(self.lease, ex_id, timeout_s=30)
            if res2.get("state") != "TIMEOUT":
                res, trail = res2, trail + trail2
        return res, trail

    def do(self, op, args, timeout_s=180, tag=None, preempt=False):
        ex_id, err = self.submit(op, args, tag, preempt=preempt)
        if ex_id is None:
            return {"op": op, "submit": err}
        res, trail = self.term(ex_id, timeout_s=timeout_s)
        return {"op": op, "execution_id": ex_id, "terminal": res}

    def do_async(self, op, args, tag=None, preempt=False):
        """异步执行:立即返回 (ex_id, None) 或 (None, fault_dict)。"""
        return self.submit(op, args, tag, preempt=preempt)

    def ctl(self, ex_id, action):
        return E.control(self.lease, ex_id, action, "play-ctl")


def _print(obj):
    print(json.dumps(obj, ensure_ascii=False, indent=1))


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return 0
    cmd = a[0]
    if cmd == "rcon":
        import os
        print(E.rcon(" ".join(a[1:])))
        return 0
    s = Session()
    if cmd == "observe":
        _print(s.observe())
    elif cmd == "view":
        _print(s.view())
    elif cmd == "overview":
        _print(s.overview(a[1].lstrip("_") and ("_" + a[1].lstrip("_")) if len(a) > 1 and a[1] != "-" else None))
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
        rest = a[3:]
        preempt = "--preempt" in rest
        rest = [x for x in rest if x != "--preempt"]
        t = int(rest[0]) if rest else 180
        _print(s.do(op, args, timeout_s=t, preempt=preempt))
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
