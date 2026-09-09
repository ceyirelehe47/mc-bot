#!/usr/bin/env python3
"""Apply audited, exact-blob anchored changes without altering unrelated code or committing."""
from __future__ import annotations
import argparse, difflib, hashlib, json, pathlib, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = 'a029fa6a3760fd0f83834c104051b041d986da60'
PREFIX = 'src/main/java/io/github/zoyluo/aibot/'
ACCESS = 'io.github.zoyluo.aibot.external.ExternalBodyAccess'
RUNTIME = 'io.github.zoyluo.aibot.external.ExternalBodyRuntime'

# SHA-1 values are Git blob IDs read directly from the frozen upstream files.
CHANGES = {
 'AIBotMod.java': ('0a9c0f2568d4f5ea334335e2ff2c1f4138e8a639', [
  ('RuntimeLifecycleCoordinator.INSTANCE.onServerStarted(server, config);',
   'RuntimeLifecycleCoordinator.INSTANCE.onServerStarted(server, config);\n            '+RUNTIME+'.start(server);', 1),
  ('ServerLifecycleEvents.SERVER_STOPPING.register(RuntimeLifecycleCoordinator.INSTANCE::onServerStopping);',
   'ServerLifecycleEvents.SERVER_STOPPING.register(server -> {\n            '+RUNTIME+'.stop();\n            RuntimeLifecycleCoordinator.INSTANCE.onServerStopping(server);\n        });', 1),
  ('BotTickCoordinator.INSTANCE.tick(server);',
   'BotTickCoordinator.INSTANCE.tick(server);\n            '+RUNTIME+'.tick(server);', 1),
 ]),
 'brain/BrainCoordinator.java': ('140280e3a0a93fc7381843a3ee262a448e43197c', [
  ('public boolean handleMessage(AIPlayerEntity bot, String senderName, String text) {',
   'public boolean handleMessage(AIPlayerEntity bot, String senderName, String text) {\n        if ('+ACCESS+'.reserved(bot)) {\n            '+ACCESS+'.message(bot, senderName, text);\n            return true;\n        }', 1),
  ('private void onResponse(AIPlayerEntity bot, DecisionLease lease, ChatResponse response) {',
   'private void onResponse(AIPlayerEntity bot, DecisionLease lease, ChatResponse response) {\n        if ('+ACCESS+'.reserved(bot)) return;', 1),
  ('public boolean maybeWakeForFailureOrGoal(AIPlayerEntity bot) {',
   'public boolean maybeWakeForFailureOrGoal(AIPlayerEntity bot) {\n        if ('+ACCESS+'.reserved(bot)) return false;', 1),
  ('private void submit(AIPlayerEntity bot, BotConversation conversation, DecisionLease lease) {',
   'private void submit(AIPlayerEntity bot, BotConversation conversation, DecisionLease lease) {\n        if ('+ACCESS+'.reserved(bot)) {\n            invalidateDecision(bot, "external_mode");\n            return;\n        }', 1),
 ]),
 'brain/ToolRegistry.java': ('2ef8ee3f685a4d5e85d7fc5fbd93b281186881e8', [
  ('return Optional.ofNullable(tools.get(name));',
   'return Optional.ofNullable(tools.get(name)).map(tool -> new ToolDefinition(\n                tool.name(), tool.description(), tool.parametersSchema(), (bot, args) -> {\n                    '+ACCESS+'.checkTool(bot);\n                    return tool.handler().invoke(bot, args);\n                }, tool.group()));', 1),
 ]),
 'task/TaskManager.java': ('1d27e8dd977886814113216ca1ba453a944b7225', [
  ('private void assign(AIPlayerEntity bot, Task task, TaskOrigin origin,\n                        boolean publishStatus) {',
   'private void assign(AIPlayerEntity bot, Task task, TaskOrigin origin,\n                        boolean publishStatus) {\n        '+ACCESS+'.checkAssignment(bot, origin);', 1),
 ]),
 'coordination/IdleCoordinator.java': ('50e04e05734541fc1828ed6ae1c144aa3cc4e2d7', [
  ('public boolean tickBot(AIPlayerEntity bot) {',
   'public boolean tickBot(AIPlayerEntity bot) {\n        if ('+ACCESS+'.reserved(bot)) return false;', 1),
 ]),
 'task/BotTickCoordinator.java': ('0a6bfc2f38814433e5c930cdfec37616a7e0dfd1', [
  ('if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {',
   'if (!handled && !'+ACCESS+'.reserved(bot) && GoalExecutor.INSTANCE.tickBot(server, bot)) {', 1),
 ]),
 'brain/ChatCaptureListener.java': ('87775610e00abafdb1e10b3077ad93fd137916ca', [
  ('''            String text = message.getContent().getString();
            var matcher = MENTION.matcher(text);
            if (!matcher.find()) {
                return;
            }
''',
   '''            String text = message.getContent().getString();
            var matcher = MENTION.matcher(text);
            if (!matcher.find()) {
                // 无 @ 前缀:对保留身体(外部大脑控制)放宽为免 @ 直聊;授权门与控制短语语义不变
                String plainSender = sender.getGameProfile().getName();
                for (var bot : AIPlayerManager.INSTANCE.all()) {
                    if (!'''+ACCESS+'''.reserved(bot)) continue;
                    if (!BotAuthorizationGate.INSTANCE.authorize(
                            sender, bot, BotAuthorizationPolicy.Operation.COMMAND, "chat:@bot")) {
                        return;
                    }
                    BotLog.comm(bot, "chat_in", "sender", plainSender, "text", text);
                    if (io.github.zoyluo.aibot.runtime.IntentController.INSTANCE.routePlayerControlPhrase(
                            bot, io.github.zoyluo.aibot.runtime.IntentController.ControlOrigin.PLAYER_COMMAND, text)) {
                        return;
                    }
                    BrainCoordinator.INSTANCE.handleMessage(bot, plainSender, text);
                    return;
                }
                return;
            }
''', 1),
  ('''                    if (!BotAuthorizationGate.INSTANCE.authorize(
                            sender, bot, BotAuthorizationPolicy.Operation.COMMAND, "chat:@bot")) {
                        return;
                    }
                    BotLog.comm(bot, "chat_in", "sender", plainSender, "text", text);
                    if (io.github.zoyluo.aibot.runtime.IntentController.INSTANCE.routePlayerControlPhrase(
                            bot, io.github.zoyluo.aibot.runtime.IntentController.ControlOrigin.PLAYER_COMMAND, text)) {
                        return;
                    }
                    BrainCoordinator.INSTANCE.handleMessage(bot, plainSender, text);
                    return;
''',
   '''                    if (!BotAuthorizationGate.INSTANCE.authorize(
                            sender, bot, BotAuthorizationPolicy.Operation.COMMAND, "chat:plain_external")) {
                        return;
                    }
                    BotLog.comm(bot, "chat_in", "sender", plainSender, "text", text);
                    io.github.zoyluo.aibot.external.ExternalBodyAccess.playerMessage(
                            bot, sender.getUuid(), plainSender, "chat:plain", true, text);
                    return;
''', 1),
  ('''                BotLog.comm(bot, "chat_in", "sender", sender.getGameProfile().getName(), "text", body);
                if (io.github.zoyluo.aibot.runtime.IntentController.INSTANCE.routePlayerControlPhrase(
                        bot, io.github.zoyluo.aibot.runtime.IntentController.ControlOrigin.PLAYER_COMMAND, body)) {
                    return;
                }
                BrainCoordinator.INSTANCE.handleMessage(bot, sender.getGameProfile().getName(), body);
''',
   '''                String senderName = sender.getGameProfile().getName();
                BotLog.comm(bot, "chat_in", "sender", senderName, "text", body);
                if (io.github.zoyluo.aibot.external.ExternalBodyAccess.reserved(bot)) {
                    io.github.zoyluo.aibot.external.ExternalBodyAccess.playerMessage(
                            bot, sender.getUuid(), senderName, "chat:@bot", true, body);
                    return;
                }
                if (io.github.zoyluo.aibot.runtime.IntentController.INSTANCE.routePlayerControlPhrase(
                        bot, io.github.zoyluo.aibot.runtime.IntentController.ControlOrigin.PLAYER_COMMAND, body)) {
                    return;
                }
                BrainCoordinator.INSTANCE.handleMessage(bot, senderName, body);
''', 1),
 ]),
 'task/DangerWatcher.java': ('16b7aeb058b2b557e4af9ad514d2736c318884fd', [
  ('private boolean maybeResupply(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {',
   'private boolean maybeResupply(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {\n        if ('+ACCESS+'.reserved(bot)) return false; // 外部身体:后台维持任务交给 DSH;SAFETY 路径(threat/lava/recover 等)不受影响', 1),
  ('boolean urgent = critical || healingEmergency;',
   'boolean urgent = critical || healingEmergency;\n        if ('+ACCESS+'.reserved(bot) && !urgent) return false; // 外部身体只保留 critical hunger / healing 的 SAFETY EatTask', 1),
  ('if (InventoryAction.findFoodSlot(bot) < 0) {\n            // 第2层 饥饿链:没有任何食物 → 若周围有可猎动物,主动猎杀获取生肉,而非干等饿死。',
   'if (InventoryAction.findFoodSlot(bot) < 0) {\n            if ('+ACCESS+'.reserved(bot)) {\n                '+RUNTIME+'.survivalAlert(bot, "critical_hunger_no_food");\n                nextEatAttemptTick.put(bot.getUuid(), now + 100);\n                return false;\n            }\n            // 第2层 饥饿链:没有任何食物 → 若周围有可猎动物,主动猎杀获取生肉,而非干等饿死。', 1),
  ('private boolean maybeStartNightTask(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {',
   'private boolean maybeStartNightTask(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {\n        if ('+ACCESS+'.reserved(bot)) return false; // 外部身体:后台维持任务交给 DSH;SAFETY 路径(threat/lava/recover 等)不受影响', 1),
  ('private boolean maybeLightDarkArea(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {',
   'private boolean maybeLightDarkArea(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {\n        if ('+ACCESS+'.reserved(bot)) return false; // 外部身体:后台维持任务交给 DSH;SAFETY 路径(threat/lava/recover 等)不受影响', 1),
 ]),
 'runtime/RuntimeLifecycleCoordinator.java': ('4bf50391b4dabff1501c97f01344cd8d0d859ff2', [
  ('public void onBotDeath(AIPlayerEntity bot) {',
   'public void onBotDeath(AIPlayerEntity bot) {\n        '+RUNTIME+'.death(bot);', 1),
 ]),
 'auth/BotAuthorizationGate.java': ('43e1c1b20eecc6f10af9e747f6dbd1599320e1ae', [
  ('return decision.allowed();',
   'return decision.allowed() && '+ACCESS+'.permitsLegacyOperation(bot, operation.name(), channel);', 3),
  ('permitsLegacyOperation(bot, operation.name(), channel);\n    }\n\n    public boolean requireGlobalAdmin',
   'permitsLegacyOperation(targetBot, operation.name(), channel);\n    }\n\n    public boolean requireGlobalAdmin', 1),
 ]),
 'network/AIBotServerNetworking.java': ('baa72f575d832fbfa200d02af610104fd0018535', [
  ('String action = payload.action().toLowerCase(Locale.ROOT);',
   'String action = payload.action().toLowerCase(Locale.ROOT);\n        if ('+ACCESS+'.reserved(bot) && !"chat".equals(action)) {\n            sendSystem(player, bot.getGameProfile().getName(), "External body: use DSH controls; legacy panel mutation is disabled.");\n            return;\n        }', 1),
  ('''            case "chat" -> {
                sendBotChat(bot, "user", payload.arg1());
                if (!IntentController.INSTANCE.routePlayerControlPhrase(
                        bot, IntentController.ControlOrigin.PLAYER_PANEL, payload.arg1())) {
                    BrainCoordinator.INSTANCE.handleMessage(bot, player.getGameProfile().getName(), payload.arg1());
                }
            }
''',
   '''            case "chat" -> {
                sendBotChat(bot, "user", payload.arg1());
                if (io.github.zoyluo.aibot.external.ExternalBodyAccess.reserved(bot)) {
                    io.github.zoyluo.aibot.external.ExternalBodyAccess.playerMessage(
                            bot, player.getUuid(), player.getGameProfile().getName(),
                            "network:panel_chat", true, payload.arg1());
                } else if (!IntentController.INSTANCE.routePlayerControlPhrase(
                        bot, IntentController.ControlOrigin.PLAYER_PANEL, payload.arg1())) {
                    BrainCoordinator.INSTANCE.handleMessage(bot, player.getGameProfile().getName(), payload.arg1());
                }
            }
''', 1),
 ]),
 'runtime/IntentController.java': ('69878ada6beca2e502dc4bd05d351d8682f902a7', [
  ('Objects.requireNonNull(origin, "origin");',
   'Objects.requireNonNull(origin, "origin");\n        if (origin != ControlOrigin.SYSTEM) '+ACCESS+'.checkTool(bot);', 1),
  ('String normalized = normalizeReason(origin, reason);',
   'if (origin != ControlOrigin.SYSTEM) '+ACCESS+'.checkTool(bot);\n        String normalized = normalizeReason(origin, reason);', 2),
 ]),
}

def git(repo: pathlib.Path, *args: str) -> str:
    return subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()

def blob_id(data: bytes) -> str:
    return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()

def transform(original: str, rules: list[tuple[str,str,int]]) -> str:
    text=original
    for old,new,count in rules:
        actual=text.count(old)
        if actual!=count:
            raise ValueError(f'anchor count mismatch: expected {count}, found {actual}: {old[:100]}')
        text=text.replace(old,new)
    return text

def plan(repo: pathlib.Path, *, validate_head: bool=True) -> tuple[dict[pathlib.Path,bytes],dict[pathlib.Path,bytes]]:
    if validate_head:
        if git(repo,'rev-parse','HEAD')!=BASE: raise ValueError('Wrong AIBot HEAD; refusing to guess at a newer API.')
        if git(repo,'status','--porcelain','--untracked-files=no'): raise ValueError('Tracked worktree is dirty; refusing to overlay.')
    old_files: dict[pathlib.Path,bytes]={}; writes: dict[pathlib.Path,bytes]={}
    for relative,(expected,rules) in CHANGES.items():
        path=repo/(PREFIX+relative)
        original=path.read_bytes()
        if blob_id(original)!=expected: raise ValueError(f'Frozen blob mismatch: {path}')
        old_files[path]=original
        writes[path]=transform(original.decode('utf-8'),rules).encode('utf-8')
    for source in sorted((ROOT/'aibot-overlay').rglob('*')):
        if not source.is_file():continue
        target=repo/source.relative_to(ROOT/'aibot-overlay')
        if target.exists():raise ValueError(f'Overlay target already exists: {target}')
        writes[target]=source.read_bytes()
    return old_files,writes

def main() -> int:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo',required=True,type=pathlib.Path)
    p.add_argument('--apply',action='store_true',help='Default is validation only.')
    p.add_argument('--patch-output',type=pathlib.Path,help='Optional full unified patch from the real checkout.')
    args=p.parse_args();repo=args.repo.resolve()
    try:old,writes=plan(repo)
    except (ValueError,OSError,subprocess.CalledProcessError) as e:print(f'REFUSED: {e}',file=sys.stderr);return 2
    if args.patch_output:
        diff=[]
        for path,new in writes.items():
            rel=path.relative_to(repo).as_posix()
            diff.extend(difflib.unified_diff(old.get(path,b'').decode().splitlines(True),new.decode().splitlines(True),
                fromfile='a/'+rel if path in old else '/dev/null',tofile='b/'+rel))
        if args.patch_output.exists(): raise SystemExit('REFUSED: patch output already exists')
        args.patch_output.write_text(''.join(diff),encoding='utf-8')
    if not args.apply:
        print(f'CHECKED: {len(old)} exact upstream blobs; {len(writes)-len(old)} new files. No files changed.');return 0
    changed=[]
    try:
        for path,data in writes.items():
            path.parent.mkdir(parents=True,exist_ok=True);changed.append(path);path.write_bytes(data)
    except OSError:
        for path in reversed(changed):
            if path in old:path.write_bytes(old[path])
            else:path.unlink(missing_ok=True)
        raise
    print(f'APPLIED to {repo}. No commit, branch change, push, or deployment was performed.')
    print('Next: ./gradlew compileJava test ; then real GameTests and isolated gameplay acceptance.')
    return 0

if __name__=='__main__':raise SystemExit(main())
