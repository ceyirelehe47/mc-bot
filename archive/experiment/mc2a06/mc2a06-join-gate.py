# -*- coding: utf-8 -*-
"""MC-2A0.6 JOIN 门控:监听 25598。
gate 文件存在时 accept 后立即断(客户端 DisconnectedScreen 重试循环);
文件删除后双向转发到 127.0.0.1:25599。用于 LIVE-A 的 control-connected-no-JOIN 窗口。"""
import pathlib, socket, threading, time

LISTEN = ("127.0.0.1", 25598)
TARGET = ("127.0.0.1", 25599)
GATE = pathlib.Path(r"D:\code\mc-experiment\mc2a06-join-gate.on")

def relay(a, b):
    try:
        while True:
            d = a.recv(8192)
            if not d: break
            b.sendall(d)
    except Exception:
        pass
    finally:
        try: a.shutdown(socket.SHUT_RDWR)
        except Exception: pass
        try: b.shutdown(socket.SHUT_RDWR)
        except Exception: pass

def handle(c):
    if GATE.exists():
        try: c.close()
        except Exception: pass
        return
    try:
        up = socket.create_connection(TARGET, timeout=10)
    except Exception:
        try: c.close()
        except Exception: pass
        return
    t1 = threading.Thread(target=relay, args=(c, up), daemon=True)
    t2 = threading.Thread(target=relay, args=(up, c), daemon=True)
    t1.start(); t2.start(); t1.join(); t2.join()
    c.close(); up.close()

def main():
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(LISTEN); srv.listen(8)
    print("join-gate listening %s target=%s gate_file=%s exists=%s" % (LISTEN, TARGET, GATE, GATE.exists()), flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()

if __name__ == "__main__":
    main()
