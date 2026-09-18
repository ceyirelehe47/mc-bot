import socket, struct, sys
def pkt(rid, ptype, body):
    data = struct.pack('<ii', rid, ptype) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data
def rcon(cmd, host='127.0.0.1', port=25575, pwd='aibot-p0c'):
    s = socket.create_connection((host, port), timeout=10)
    s.sendall(pkt(1, 3, pwd)); r = s.recv(4096)
    s.sendall(pkt(2, 2, cmd))
    out = b''
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk: break
            out += chunk
            if len(chunk) < 4096: break
    except socket.timeout: pass
    s.close()
    return out[8:-2].decode('utf-8', 'replace')
if __name__ == '__main__':
    print(rcon(' '.join(sys.argv[1:])))
