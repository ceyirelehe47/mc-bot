#!/usr/bin/env python3
"""Install a local scratch overlay into an exact DSH checkout. Never modifies DSH core."""
from __future__ import annotations
import argparse,json,pathlib,shutil,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='5dda764ed3aa172535a7967b06ff95d9cbfe536a'
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',required=True,type=pathlib.Path);args=p.parse_args()
    repo=args.repo.resolve()
    head=subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip()
    if head!=BASE:raise SystemExit('DSH HEAD mismatch: expected '+BASE)
    dest=repo/'scratch-aibot-body'
    if dest.exists():raise SystemExit('Destination already exists; refusing to overwrite: '+str(dest))
    shutil.copytree(ROOT/'dsh-plugin',dest,ignore=shutil.ignore_patterns('node_modules','.env'))
    overlay=dest/'cordis.yml'
    overlay.write_text('- insert:\n    - id: aibot-body\n      name: '+json.dumps(str(dest/'src/index.ts'))+'\n',encoding='utf-8')
    print('Installed local overlay (no DSH core files modified).')
    print('Set AIBOT_BRIDGE_TOKEN and AIBOT_BRIDGE_URL in the DSH process environment, then:')
    print('pnpm dsh web --patch '+str(overlay))
if __name__=='__main__':main()
