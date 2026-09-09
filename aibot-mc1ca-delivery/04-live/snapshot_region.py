"""逐块枚举 HOME/区域快照:execute if block 探测(air 优先,再材料判定),输出 JSON+sha256。"""
import sys, json, hashlib, time
sys.path.insert(0, r'D:/code/mc-experiment')
from rcon import rcon

MATS = ['minecraft:air', 'minecraft:oak_log', 'minecraft:oak_planks', 'minecraft:dirt',
        'minecraft:grass_block', 'minecraft:stone', 'minecraft:cobblestone',
        'minecraft:farmland', 'minecraft:oak_leaves', 'minecraft:oak_leaves[persistent=true]',
        'minecraft:wheat', 'minecraft:water']

def probe(x, y, z):
    for m in MATS:
        out = rcon(f'execute if block {x} {y} {z} {m}')
        if 'passed' in out or 'Test passed' in out:
            return m
    return 'unknown'

def snapshot(x1, y1, z1, x2, y2, z2):
    cells = {}
    n = 0
    t0 = time.time()
    for y in range(y1, y2 + 1):
        for z in range(z1, z2 + 1):
            for x in range(x1, x2 + 1):
                cells[f'{x},{y},{z}'] = probe(x, y, z)
                n += 1
    blob = json.dumps(cells, sort_keys=True, separators=(',', ':'))
    return cells, hashlib.sha256(blob.encode()).hexdigest(), n, time.time() - t0

if __name__ == '__main__':
    out_path = sys.argv[1]
    x1, y1, z1, x2, y2, z2 = map(int, sys.argv[2:8])
    cells, digest, n, secs = snapshot(x1, y1, z1, x2, y2, z2)
    summary = {}
    for v in cells.values():
        summary[v] = summary.get(v, 0) + 1
    doc = {'bounds': [x1, y1, z1, x2, y2, z2], 'sha256': digest, 'cells': n,
           'elapsed_sec': round(secs, 1), 'summary': summary, 'snapshot': cells}
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    print(json.dumps({'sha256': digest, 'cells': n, 'summary': summary,
                      'elapsed_sec': round(secs, 1)}, ensure_ascii=False))
