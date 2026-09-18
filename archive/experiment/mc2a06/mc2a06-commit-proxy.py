# -*- coding: utf-8 -*-
"""MC-2A0.6 control-transport 代理:监听 8768,转发 8766。
下行帧含 "phase":"commit" 时按策略文件处理:
  {"mode":"pass"}                     透传(默认)
  {"mode":"hold"}                     扣住 commit 直到 mode 变 release
  {"mode":"release"}                  放行被扣帧
  {"mode":"rewrite",...}              放行前改写元组字段(screen_epoch/screen_seq/sync_id/adapter_id)
  {"mode":"inject_drop"}              丢弃被扣帧(模拟 commit 永不到达)
用途: LIVE-D 受控授权窗口 / LIVE-F 伪造元组负向。全部帧摘要写 proxy-log.jsonl。
"""
import json, socket, struct, threading, time, pathlib, sys

LISTEN = ("127.0.0.1", 8768)
UPSTREAM = ("127.0.0.1", 8766)
POLICY = pathlib.Path(r"D:\code\mc-experiment\mc2a06-proxy-policy.json")
LOG = pathlib.Path(r"D:\code\mc-experiment\mc2a06-proxy-log.jsonl")

def policy():
    try: return json.loads(POLICY.read_text(encoding="utf-8"))
    except Exception: return {"mode": "pass"}

def set_policy(obj):
    POLICY.write_text(json.dumps(obj), encoding="utf-8")

def log(evt):
    with LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps({"t": round(time.time(), 3), **evt}, ensure_ascii=False) + "\n")

def read_frame(sock):
    hdr = b""
    while len(hdr) < 4:
        c = sock.recv(4 - len(hdr))
        if not c: return None
        hdr += c
    n = struct.unpack(">i", hdr)[0]
    body = b""
    while len(body) < n:
        c = sock.recv(n - len(body))
        if not c: return None
        body += c
    return hdr + body

def is_commit(frame):
    try:
        obj = json.loads(frame[4:].decode("utf-8"))
        if not isinstance(obj, dict):
            return False
        if obj.get("phase") == "commit":
            return True
        args = obj.get("arguments_json")
        if isinstance(args, str):
            inner = json.loads(args)
            return isinstance(inner, dict) and inner.get("phase") == "commit"
        if isinstance(args, dict):
            return args.get("phase") == "commit"
        return False
    except Exception:
        return False

def is_cancel(frame):
    try:
        obj = json.loads(frame[4:].decode("utf-8"))
        return (isinstance(obj, dict) and obj.get("type") == "control"
                and obj.get("action") == "cancel")
    except Exception:
        return False

def pump(src, dst, downstream, held):
    try:
        while True:
            frame = read_frame(src)
            if frame is None: break
            matched = downstream and (is_commit(frame) or is_cancel(frame))
            held_kind = "commit" if is_commit(frame) else ("cancel" if is_cancel(frame) else None)
            if matched:
                while True:
                    p = policy()
                    m = p.get("mode", "pass")
                    if m == "pass":
                        break
                    if m == "hold" or (m == "hold_cancel" and held_kind == "cancel") \
                            or (m == "hold_commit" and held_kind == "commit"):
                        time.sleep(0.2); continue
                    if m == "inject_drop":
                        log({"evt": "frame_dropped", "kind": held_kind})
                        held.append(frame); time.sleep(10)
                        continue
                    if m == "rewrite" and held_kind == "commit":
                        obj = json.loads(frame[4:].decode("utf-8"))
                        raw = obj.get("arguments_json")
                        args = None
                        if isinstance(raw, str):
                            args = json.loads(raw)
                        elif isinstance(raw, dict):
                            args = raw
                        if args is None:
                            args = obj.get("args") if isinstance(obj.get("args"), dict) else obj
                            obj["args"] = args
                        for k in ("screen_epoch", "screen_seq", "sync_id", "adapter_id"):
                            if k in p: args[k] = p[k]
                        if isinstance(raw, str):
                            obj["arguments_json"] = json.dumps(args, separators=(",", ":"))
                        body = json.dumps(obj, separators=(",", ":")).encode("utf-8")
                        frame = struct.pack(">i", len(body)) + body
                        log({"evt": "commit_rewritten", "fields": {k: p[k] for k in ("screen_epoch", "screen_seq", "sync_id", "adapter_id") if k in p}})
                        break
                    if m == "release":
                        break
                    time.sleep(0.2)
                log({"evt": "commit_forwarded"})
            dst.sendall(frame)
    except Exception as e:
        log({"evt": "pump_end", "err": str(e)[:120]})
    finally:
        try: dst.shutdown(socket.SHUT_RDWR)
        except Exception: pass

def handle(client):
    try:
        up = socket.create_connection(UPSTREAM, timeout=10)
        up.settimeout(None)
    except Exception as e:
        log({"evt": "upstream_fail", "err": str(e)[:120]})
        client.close(); return
    held = []
    t1 = threading.Thread(target=pump, args=(client, up, False, held), daemon=True)
    t2 = threading.Thread(target=pump, args=(up, client, True, held), daemon=True)
    t1.start(); t2.start(); t1.join(); t2.join()
    client.close(); up.close()

def main():
    POLICY.parent.mkdir(parents=True, exist_ok=True)
    if not POLICY.exists(): set_policy({"mode": "pass"})
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(LISTEN); srv.listen(4)
    log({"evt": "proxy_listening", "listen": LISTEN, "upstream": UPSTREAM})
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()

if __name__ == "__main__":
    main()
