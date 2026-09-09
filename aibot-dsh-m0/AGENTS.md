# Scope

This is an experimental AIBot-to-DSH source overlay, not the Iris Core repository.
Read `VERIFICATION.json`, `README.zh-CN.md`, and `docs/GLM_HANDOFF.zh-CN.md` first.

Do not redesign the bridge or rewrite AIBot Tasks before attempting actual pinned builds.
Preserve exclusive ownership, game-thread execution, durable acceptance, idempotency,
unknown outcomes, plugin provenance, and DSH persistence-before-cursor ordering.

`bash scripts/test.sh` exercises the portable core, actual HTTP transport and fake-world /
fake-DSH boundaries. It does NOT verify Minecraft gameplay, full mod compilation, native
DSH package resolution, or DSH crash recovery. Keep those claims separate.

Never commit live tokens, game journals, important world saves, or model API keys.
Do not deploy to an existing server or push to unrelated Iris repositories.
