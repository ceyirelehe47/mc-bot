#!/usr/bin/env bash
# Offline regression suite: portable core + real HTTP transport, fake Minecraft/DSH owners.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p .build evidence
J=aibot-overlay/src/main/java/io/github/zoyluo/aibot/external
{
  java -version 2>&1
  node --version
  python3 --version
} | tee evidence/environment.txt
javac -d .build "$J"/{JsonOutput,BridgeFault,BridgeJournal,BodyBackend,BridgeKernel,BridgeHttpServer}.java \
  bridge-tests/{BridgeCoreTest,FakeBridgeServer}.java
java -cp .build io.github.zoyluo.aibot.external.BridgeCoreTest | tee evidence/java-core-tests.txt
node --test dsh-plugin/test/*.test.mjs | tee evidence/node-tests.txt
python3 scripts/test_installers.py 2>&1 | tee evidence/installer-tests.txt
printf '\nOffline suites passed. NOT a real Minecraft GameTest or actual DSH loading result.\n'
