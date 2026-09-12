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
 'task/FarmTask.java': ('ce82c371f75cfeed591442e37a862490f78ec5ed', [
  ('''                .filter(pos -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos)
                        || io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos.up()))
''',
   '''                .filter(pos -> io.github.zoyluo.aibot.external.SemanticWorldRegistry
                        .farmSurveyCellObservable(bot, areaCenter, radius, crop, pos))
''', 1),
 ]),
 'task/GatherQuotaTask.java': ('f3b4d48379024e859a8844dc6b870de9dccf4d16', [
  # MC-1C-A 补口:OreProspector 的 prospect/explore 选目标路径也要过自然树分类器,
  # 否则保护区原木/ambiguous log 会被选为 harvest 目标(挖掘层硬门仍会拦,但会
  # 反复 prospect→pathfinding 超时→拉黑好树,实测导致配额无法达成)。
  ('''        BlockPos found = OreProspector.nearest(bot, PROSPECT_RANGE,
                state -> harvestBlocks.contains(state.getBlock()),
                pos -> !EpisodeMemory.INSTANCE.isExcluded(botId, pos, now));''',
   '''        BlockPos found = OreProspector.nearest(bot, PROSPECT_RANGE,
                state -> harvestBlocks.contains(state.getBlock()),
                pos -> !EpisodeMemory.INSTANCE.isExcluded(botId, pos, now)
                        && io.github.zoyluo.aibot.external.NaturalTreeClassifier.isHarvestCandidate(bot, pos));''', 1),
  ('''            BlockPos seen = OreProspector.nearest(bot, 16,
                    state -> harvestBlocks.contains(state.getBlock()));''',
   '''            BlockPos seen = OreProspector.nearest(bot, 16,
                    state -> harvestBlocks.contains(state.getBlock()),
                    pos -> io.github.zoyluo.aibot.external.NaturalTreeClassifier.isHarvestCandidate(bot, pos));''', 1),
  # MC-2A0.2: one exact bridge execution may own one frozen natural-tree transaction.
  ('''        EXPLORE,
        DONE''',
   '''        EXPLORE,
        TREE_CLEANUP,
        DONE''', 1),
  ('''    private final Item targetItem;
    private final int targetCount;''',
   '''    private final Item targetItem;
    private final int targetCount;
    private final String externalExecutionId;''', 1),
  ('''    private int gotoStuckTick;

    public GatherQuotaTask(Item targetItem, int targetCount) {
        this.targetItem = targetItem;
        this.targetCount = Math.max(1, targetCount);
        this.acceptItems = acceptItemsFor(targetItem);
        this.harvestBlocks = harvestBlocksFor(this.acceptItems);
        this.probabilisticDrop = harvestBlocks.contains(Blocks.SHORT_GRASS)
                || harvestBlocks.contains(Blocks.SWEET_BERRY_BUSH);
    }''',
   '''    private int gotoStuckTick;
    private io.github.zoyluo.aibot.external.TreeHarvestWorkset treeWorkset;
    private String treeTerminalReason = "";

    public GatherQuotaTask(Item targetItem, int targetCount) {
        this(targetItem, targetCount, "");
    }

    /** Exact external execution identity is optional so every internal/upstream caller stays source-compatible. */
    public GatherQuotaTask(Item targetItem, int targetCount, String externalExecutionId) {
        this.targetItem = targetItem;
        this.targetCount = Math.max(1, targetCount);
        this.externalExecutionId = externalExecutionId == null ? "" : externalExecutionId;
        this.acceptItems = acceptItemsFor(targetItem);
        this.harvestBlocks = harvestBlocksFor(this.acceptItems);
        this.probabilisticDrop = harvestBlocks.contains(Blocks.SHORT_GRASS)
                || harvestBlocks.contains(Blocks.SWEET_BERRY_BUSH);
    }''', 1),
  ('''    public double progress() {
        return Math.min(1.0D, (double) countSoFar / targetCount);
    }''',
   '''    public double progress() {
        double quotaProgress = Math.min(1.0D, (double) countSoFar / targetCount);
        // A satisfied inventory quota is not terminal while this execution still owes the current tree.
        if (treeWorkset != null && quotaProgress >= 1.0D && !treeWorkset.treeComplete()) return 0.99D;
        return quotaProgress;
    }

    /** Read-only diagnostic seam used by GameTests/evidence; no mutation or controller authority. */
    public io.github.zoyluo.aibot.external.TreeHarvestWorkset.Snapshot treeWorksetSnapshot() {
        return treeWorkset == null ? null : treeWorkset.snapshot();
    }''', 1),
  ('''        countSoFar = countAccepted(bot);
        if (countSoFar >= targetCount) {
            clearPickupLedger();
            phase = Phase.DONE;
            return;
        }
        if (phase != Phase.HARVEST || targetPos == null || !isHarvestBlock(bot, targetPos)) {''',
   '''        countSoFar = countAccepted(bot);
        if (treeWorkset != null) treeWorkset.onResume();
        if (countSoFar >= targetCount && treeWorkset == null) {
            clearPickupLedger();
            phase = Phase.DONE;
            return;
        }
        if (phase != Phase.HARVEST || targetPos == null || !isHarvestBlock(bot, targetPos)) {''', 1),
  ('''        if (countSoFar >= targetCount) {
            bot.getActionPack().stopAll();
            clearPickupLedger();
            phase = Phase.DONE;
        }
        if (elapsed > 6000) {
            fail("gather_timeout");
            return;
        }
        // Do not re-enter SURVEY/EXPLORE every tick while swimming. That used to burn all eight
        // exploration hops in place before NavSafetyNet's low-air threshold could take control.
        if (waitForDryGround(bot)) {
            return;
        }''',
   '''        if (countSoFar >= targetCount && treeWorkset == null) {
            bot.getActionPack().stopAll();
            clearPickupLedger();
            phase = Phase.DONE;
        }
        if (elapsed > 6000 && phase != Phase.TREE_CLEANUP) {
            if (treeWorkset != null) {
                treeTerminalReason = "gather_timeout";
                bot.getActionPack().stopAll();
                phase = Phase.TREE_CLEANUP;
            } else {
                fail("gather_timeout");
            }
            return;
        }
        // Do not re-enter SURVEY/EXPLORE every tick while swimming. That used to burn all eight
        // exploration hops in place before NavSafetyNet's low-air threshold could take control.
        // Cleanup pauses the same way: the workset/support ledger lives on the task instance, so
        // waiting for the shared water rescue cannot discard owned supports (TREE-5 semantics).
        if (waitForDryGround(bot)) {
            return;
        }''', 1),
  ('''        if (phase == Phase.SURVEY || phase == Phase.GOTO) {''',
   '''        if (treeWorkset == null && (phase == Phase.SURVEY || phase == Phase.GOTO)) {''', 1),
  ('''            case EXPLORE -> exploreMove(bot);
            case DONE -> complete();''',
   '''            case EXPLORE -> exploreMove(bot);
            case TREE_CLEANUP -> treeCleanup(bot);
            case DONE -> complete();''', 1),
  ('''    private void survey(AIPlayerEntity bot) {
        if (harvestBlocks.isEmpty()) {''',
   '''    private void survey(AIPlayerEntity bot) {
        if (treeWorkset != null) {
            surveyCommittedTree(bot);
            return;
        }
        if (harvestBlocks.isEmpty()) {''', 1),
  ('''    private void harvest(AIPlayerEntity bot) {
        if (targetPos == null || !isHarvestBlock(bot, targetPos)) {
            invalidateConsumedResource(bot);
            bot.getActionPack().stopAll(); // 砍倒后停稳,别带移动惯性漂离掉落物(实测砍完从树位漂走→捡不到)
            pickupTicks = probabilisticDrop ? 30 : 120; // 概率掉落资源(种子/浆果)掉脚边、捡得快,少等
            phase = Phase.PICKUP;
            return;
        }
        if (elapsed - harvestStartedTick > HARVEST_LIMIT) {
            bot.getActionPack().stopAll();
            EpisodeMemory.INSTANCE.exclude(bot.getUuid(), targetPos,
                    bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
            BotLog.action(bot, "gather_harvest_timeout", "pos", targetPos.toShortString());
            targetPos = null;
            clearPickupLedger();
            resetSurveyWatchdog();
            phase = Phase.SURVEY;
            return;
        }''',
   '''    private void harvest(AIPlayerEntity bot) {
        if (targetPos == null || !isHarvestBlock(bot, targetPos)) {
            BlockPos harvested = targetPos == null ? null : targetPos.toImmutable();
            if (treeWorkset != null && harvested != null) treeWorkset.noteHarvested(bot, harvested);
            invalidateConsumedResource(bot);
            bot.getActionPack().stopAll(); // 砍倒后停稳,别带移动惯性漂离掉落物(实测砍完从树位漂走→捡不到)
            pickupTicks = probabilisticDrop ? 30 : 120; // 概率掉落资源(种子/浆果)掉脚边、捡得快,少等
            phase = treeWorkset != null && treeWorkset.hasTemporarySupports()
                    ? Phase.TREE_CLEANUP : Phase.PICKUP;
            return;
        }
        if (elapsed - harvestStartedTick > HARVEST_LIMIT) {
            bot.getActionPack().stopAll();
            EpisodeMemory.INSTANCE.exclude(bot.getUuid(), targetPos,
                    bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
            BotLog.action(bot, "gather_harvest_timeout", "pos", targetPos.toShortString());
            if (treeWorkset != null) {
                treeWorkset.markBlocked(targetPos, "harvest_timeout");
                treeTerminalReason = "committed_log_harvest_timeout:" + targetPos.toShortString();
                targetPos = null;
                clearPickupLedger();
                phase = Phase.TREE_CLEANUP;
            } else {
                targetPos = null;
                clearPickupLedger();
                resetSurveyWatchdog();
                phase = Phase.SURVEY;
            }
            return;
        }''', 1),
  ('''            if (confirmPickup(bot, pickupStatNow)) {
                return;
            } else if (probabilisticDrop) {''',
   '''            if (confirmPickup(bot, pickupStatNow)) {
                return;
            } else if (treeWorkset != null && pickupOrigin != null) {
                // Exact tree pickup debt expired after the bounded physical chase. Reconcile as an
                // explicit loss instead of silently forgetting this tree transaction.
                BlockPos lost = pickupOrigin.toImmutable();
                treeWorkset.resolvePickupLoss(lost, "bounded_pickup_not_recovered");
                clearPickupLedger();
                pickupMisses = 0;
                continueAfterTreePickup(bot);
            } else if (probabilisticDrop) {''', 1),
  ('''        bot.getActionPack().stopAll();
        pickupMisses = 0;
        clearPickupLedger();
        if (countSoFar >= targetCount) {
            phase = Phase.DONE;
        } else {''',
   '''        bot.getActionPack().stopAll();
        pickupMisses = 0;
        if (treeWorkset != null && pickupOrigin != null) {
            treeWorkset.resolvePickup(pickupOrigin,
                    inventoryGain ? "inventory_delta" : "vanilla_pickup_stat_delta");
            clearPickupLedger();
            continueAfterTreePickup(bot);
            return true;
        }
        clearPickupLedger();
        if (countSoFar >= targetCount) {
            phase = Phase.DONE;
        } else {''', 1),
  ('''    private void clearPickupLedger() {
        pickupOrigin = null;
        pickupOriginApproachLogged = false;
    }''',
   '''    /** Drive only the frozen log cells of an already acquired natural tree. */
    private void surveyCommittedTree(AIPlayerEntity bot) {
        treeWorkset.reconcile(bot);
        if (treeWorkset.hasCleanupDebt()) {
            fail("tree_cleanup_debt:" + treeWorkset.cleanupDebt());
            return;
        }
        if (treeWorkset.hasTemporarySupports()) {
            phase = Phase.TREE_CLEANUP;
            return;
        }
        if (treeWorkset.hasPendingPickup()) {
            phase = Phase.PICKUP;
            return;
        }
        if (treeWorkset.hasBlockedLogs()) {
            treeTerminalReason = "committed_log_blocked:" + treeWorkset.blockedSummary();
            phase = Phase.TREE_CLEANUP;
            return;
        }
        if (treeWorkset.logsResolved()) {
            finishOrContinueTree(bot);
            return;
        }
        if (HarvestCore.isInventoryFull(bot)) {
            phase = Phase.DEPOSIT;
            return;
        }
        BlockPos next = treeWorkset.nextRemaining(bot);
        if (next == null) {
            treeTerminalReason = "committed_tree_has_no_runnable_log";
            phase = Phase.TREE_CLEANUP;
            return;
        }
        var access = treeWorkset.tickAccess(bot, next);
        switch (access.state()) {
            case READY -> {
                targetPos = next;
                startHarvest(bot);
            }
            case IN_PROGRESS -> { }
            case BLOCKED -> {
                treeWorkset.markBlocked(next, access.reason());
                treeTerminalReason = "committed_log_access_blocked:" + access.reason();
                bot.getActionPack().stopAll();
                phase = Phase.TREE_CLEANUP;
            }
            case DEBT -> fail("tree_cleanup_debt:" + access.reason());
            case COMPLETE -> { }
        }
    }

    /** Reverse-clean exact TREE_ACCESS receipts before pickup or terminal failure. */
    private void treeCleanup(AIPlayerEntity bot) {
        var cleanup = treeWorkset == null
                ? io.github.zoyluo.aibot.external.TreeHarvestWorkset.StepResult.complete()
                : treeWorkset.tickCleanup(bot);
        if (cleanup.state() == io.github.zoyluo.aibot.external.TreeHarvestWorkset.StepState.IN_PROGRESS) return;
        if (cleanup.state() == io.github.zoyluo.aibot.external.TreeHarvestWorkset.StepState.DEBT) {
            fail("tree_cleanup_debt:" + cleanup.reason());
            return;
        }
        if (cleanup.state() == io.github.zoyluo.aibot.external.TreeHarvestWorkset.StepState.BLOCKED) {
            fail("tree_cleanup_blocked:" + cleanup.reason());
            return;
        }
        if (!treeTerminalReason.isBlank()) {
            fail(treeTerminalReason);
            return;
        }
        if (treeWorkset != null && treeWorkset.hasPendingPickup()) {
            phase = Phase.PICKUP;
            return;
        }
        finishOrContinueTree(bot);
    }

    private void continueAfterTreePickup(AIPlayerEntity bot) {
        if (treeWorkset == null) {
            resetSurveyWatchdog();
            phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
            return;
        }
        if (treeWorkset.hasTemporarySupports()) {
            phase = Phase.TREE_CLEANUP;
            return;
        }
        finishOrContinueTree(bot);
    }

    private void finishOrContinueTree(AIPlayerEntity bot) {
        if (treeWorkset == null) {
            phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
            return;
        }
        treeWorkset.reconcile(bot);
        if (treeWorkset.hasCleanupDebt()) {
            fail("tree_cleanup_debt:" + treeWorkset.cleanupDebt());
            return;
        }
        if (treeWorkset.hasBlockedLogs()) {
            fail("tree_committed_log_blocked:" + treeWorkset.blockedSummary());
            return;
        }
        if (!treeWorkset.logsResolved()) {
            resetSurveyWatchdog();
            phase = Phase.SURVEY;
            return;
        }
        if (treeWorkset.hasTemporarySupports()) {
            phase = Phase.TREE_CLEANUP;
            return;
        }
        if (treeWorkset.hasPendingPickup()) {
            phase = Phase.PICKUP;
            return;
        }
        if (!treeWorkset.treeComplete()) {
            fail("tree_transaction_not_complete");
            return;
        }
        BotLog.action(bot, "tree_workset_complete",
                "tree", treeWorkset.treeId(),
                "execution", treeWorkset.ownerExecution(),
                "have", countSoFar + "/" + targetCount);
        treeWorkset = null;
        treeTerminalReason = "";
        targetPos = null;
        resetSurveyWatchdog();
        phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
    }

    private void clearPickupLedger() {
        pickupOrigin = null;
        pickupOriginApproachLogged = false;
    }''', 1),
  ('''    private void startHarvest(AIPlayerEntity bot) {
        countBeforeHarvest = countAccepted(bot);''',
   '''    private void startHarvest(AIPlayerEntity bot) {
        if (treeWorkset == null) {
            if (!externalExecutionId.isBlank()) {
                treeWorkset = io.github.zoyluo.aibot.external.TreeHarvestWorkset
                        .acquire(bot, targetPos, externalExecutionId).orElse(null);
            } else {
                // Preserve the R2 lease for legacy/internal gathers; only external bounded executions
                // opt into the stronger MC-2A0.2 transaction semantics.
                io.github.zoyluo.aibot.external.NaturalTreeClassifier.acquireHarvestCluster(bot, targetPos);
            }
        }
        countBeforeHarvest = countAccepted(bot);''', 1),
  ('''    private int countAccepted(AIPlayerEntity bot) {
        return acceptedInventoryCount(bot, targetItem);
    }''',
   '''    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (treeWorkset != null) {
            int supports = (int) treeWorkset.snapshot().supports().stream()
                    .filter(s -> s.state() == io.github.zoyluo.aibot.external.TreeHarvestWorkset.SupportState.PLACED)
                    .count();
            treeWorkset.abandon(failureReason == null || failureReason.isBlank() ? "task_aborted" : failureReason);
            if (supports > 0) {
                String base = failureReason == null || failureReason.isBlank() ? "aborted" : failureReason;
                failureReason = base + ":tree_cleanup_debt_supports=" + supports
                        + ":" + treeWorkset.unresolvedSupportSummary();
            }
        }
        super.onAbort(bot);
    }

    private int countAccepted(AIPlayerEntity bot) {
        return acceptedInventoryCount(bot, targetItem);
    }''', 1),
  # MC-2A0.2F closure: a placed TREE_ACCESS support is continuation state, not a
  # cleanup trigger. The old generated state machine entered TREE_CLEANUP on the
  # very next tick after placing support #1, removed it, then returned to SURVEY
  # and placed the same cell again forever. TreeHarvestWorkset.tickAccess already
  # proves/owns the top pose and can stack support #2+; keep those receipts live
  # until the current committed log is resolved (harvest() then explicitly enters
  # TREE_CLEANUP), or until a blocker/debt path explicitly requests cleanup.
  ('''    private void surveyCommittedTree(AIPlayerEntity bot) {
        treeWorkset.reconcile(bot);
        if (treeWorkset.hasCleanupDebt()) {
            fail("tree_cleanup_debt:" + treeWorkset.cleanupDebt());
            return;
        }
        if (treeWorkset.hasTemporarySupports()) {
            phase = Phase.TREE_CLEANUP;
            return;
        }
        if (treeWorkset.hasPendingPickup()) {''',
   '''    private void surveyCommittedTree(AIPlayerEntity bot) {
        treeWorkset.reconcile(bot);
        if (treeWorkset.hasCleanupDebt()) {
            fail("tree_cleanup_debt:" + treeWorkset.cleanupDebt());
            return;
        }
        if (treeWorkset.hasPendingPickup()) {''', 1),
 ]),
 'action/BlockMiner.java': ('9daf997724dddecdf363611a50c904315a3c6077', [
  ('''            Direction face = faceToward(bot, target);
            MiningAction.startMining(bot, target, face);
            started = true;
''',
   '''            Direction face = faceToward(bot, target);
            ActionResult startResult = MiningAction.startMining(bot, target, face);
            if (startResult.isFailed()) {
                bot.getActionPack().stopMining();
                failureReason = startResult.reason();
                target = null;
                started = false;
                return Status.FAILED;
            }
            started = true;
''', 1),
 ]),
 'action/MiningController.java': ('2bdc648d49c9d49a20fd74231efee3803c1b3326', [
  ('''        if (state.isAir()) {
            resetProgress(player);
            return ActionResult.SUCCESS;
        }
''',
   '''        if (state.isAir()) {
            resetProgress(player);
            return ActionResult.SUCCESS;
        }
        var bodyBreakDecision = io.github.zoyluo.aibot.external.BreakPolicy.decide(player, pos);
        if (!bodyBreakDecision.allowed()) {
            abort(player);
            return ActionResult.failed(bodyBreakDecision.reason());
        }
''', 1),
 ]),
 'action/MiningAction.java': ('5316998b8c65d69052a1d5e20e0c1ad033ad8805', [
  ('''public static ActionResult startMining(AIPlayerEntity player, BlockPos pos, Direction face) {
        return player.getActionPack().startMining(pos, face);
    }''',
   '''public static ActionResult startMining(AIPlayerEntity player, BlockPos pos, Direction face) {
        var decision = io.github.zoyluo.aibot.external.BreakPolicy.decide(player, pos);
        if (!decision.allowed()) return ActionResult.failed(decision.reason());
        return player.getActionPack().startMining(pos, face);
    }''', 1),
  ('''public static ActionResult mineOnceInstant(AIPlayerEntity player, BlockPos pos, Direction face) {
        player.interactionManager.processBlockBreakingAction(''',
   '''public static ActionResult mineOnceInstant(AIPlayerEntity player, BlockPos pos, Direction face) {
        var decision = io.github.zoyluo.aibot.external.BreakPolicy.decide(player, pos);
        if (!decision.allowed()) return ActionResult.failed(decision.reason());
        player.interactionManager.processBlockBreakingAction(''', 1),
 ]),
 'action/HarvestCore.java': ('582535c114b1c444ec1bbfb7f35665da62061db0', [
  ('''.filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                        .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(targetBlock))''',
   '''.filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                        .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(targetBlock))
                        .filter(pos -> io.github.zoyluo.aibot.external.NaturalTreeClassifier.isHarvestCandidate(bot, pos))''', 1),
  ('''.filter(pos -> targetBlocks.contains(bot.getServerWorld().getBlockState(pos).getBlock()))
                        .filter(pos -> posFilter == null || posFilter.test(pos))''',
   '''.filter(pos -> targetBlocks.contains(bot.getServerWorld().getBlockState(pos).getBlock()))
                        .filter(pos -> io.github.zoyluo.aibot.external.NaturalTreeClassifier.isHarvestCandidate(bot, pos))
                        .filter(pos -> posFilter == null || posFilter.test(pos))''', 1),
 ]),
 'perception/PerceptionCollector.java': ('aed2328a8f73a33f70f7c46c09c476f80766be8e', [
  ('''addHighlights(highlights, state, pos, round(distance), water);''',
   '''addHighlights(bot, highlights, state, pos, round(distance), water);''', 1),
  ('''private static void addHighlights(Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights,
                                      BlockState state,''',
   '''private static void addHighlights(AIPlayerEntity bot,
                                      Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights,
                                      BlockState state,''', 1),
  ('''if (state.isIn(BlockTags.LOGS)) {
            addHighlight(highlights, "nearest_tree", Registries.BLOCK.getId(state.getBlock()).toString(), pos, distance);
        }''',
   '''if (state.isIn(BlockTags.LOGS)
                && io.github.zoyluo.aibot.external.NaturalTreeClassifier.isNaturalTreeLog(bot, pos)) {
            addHighlight(highlights, "nearest_tree", Registries.BLOCK.getId(state.getBlock()).toString(), pos, distance);
        }
        io.github.zoyluo.aibot.external.SemanticWorldRegistry.observeVisibleBlock(bot, pos, state);''', 1),
 ]),
 'task/BuildTask.java': ('9a2e3704b3fabc18f8c1781d12b3405fb7fb9bc0', [
  ('import net.minecraft.util.math.Direction;\n\n',
   'import net.minecraft.util.math.Direction;\nimport net.minecraft.util.math.Vec3d;\n\n', 1),
  ('    private boolean buildMiningStarted;\n    private String note = "";\n',
   '    private boolean buildMiningStarted;\n    private BlockPos cachedStand;          // R2.1: stand 搜索含 raycast,同目标短窗口缓存\n    private BlockPos cachedStandTarget;\n    private int cachedStandTick = -100;\n    private int workPoseDiagTick = -100;   // 诊断行节流:独立计数器(与 retryTicks 解耦)\n    private int pathHoldTicks;             // 因 path 活跃而顺延的累计 tick(防无限顺延)\n    private String note = "";\n', 1),
  ('            retryTicks = 0;\n            if (currentFlattenTarget == null) {\n',
   '            retryTicks = 0;\n            pathHoldTicks = 0;\n            if (currentFlattenTarget == null) {\n', 1),
  ('            note = "flatten_skip=" + compact(currentFlattenTarget.pos()); // 够不到的整地格跳过(防原地死循环)\n            currentFlattenTarget = null;\n            return;\n',
   '            if (!bot.getActionPack().isPathExecutorIdle() && pathHoldTicks < 600) {\n                // R2.1: 走向整地格途中顺延预算,正常路途不算空转;与 build 相位同款上限,防长期顺延。\n                flattenTargetTick = elapsed;\n                pathHoldTicks++;\n            } else {\n                note = "flatten_skip=" + compact(currentFlattenTarget.pos()); // 够不到的整地格跳过(防原地死循环)\n                currentFlattenTarget = null;\n                return;\n            }\n', 1),
  ('            retryTicks = 0;\n            buildMiningStarted = false;\n',
   '            retryTicks = 0;\n            pathHoldTicks = 0;\n            buildMiningStarted = false;\n', 1),
  ('            skipBuildTarget(bot, pos, "target_timeout");\n            return;\n',
   '            // R2.1: 走向工作位是进展而非空转。HOME repair 的工作位常在结构另一侧,绕行 10+ 格的\n            // 正常路途会吃满 80t 预算被误判 target_timeout(R2-7 残留)。path executor 活跃时顺延\n            // 预算;真正的空转(path idle 且无放置)仍由 80t 窗口兜底。\n            if (!bot.getActionPack().isPathExecutorIdle() && pathHoldTicks < 600) {\n                // 走向工作位算进展,但顺延有上限:path 若长期不 idle(反复重规划),600t 后仍按空转处理。\n                buildTargetTick = elapsed;\n                pathHoldTicks++;\n            } else {\n                skipBuildTarget(bot, pos, "target_timeout");\n                return;\n            }\n', 1),
  ('        if (ObservableWorldQuery.canObserveCell(bot, pos)\n                && moveWithinReach(bot, pos, reason, reach * reach)) {\n',
   '        boolean observable = ObservableWorldQuery.canObserveCell(bot, pos);\n        if (observable && moveWithinReach(bot, pos, reason, reach * reach)) {\n', 1),
  ('            return true;\n        }\n',
   '            return true;\n        }\n        if (!observable && elapsed - workPoseDiagTick >= 40) {\n            workPoseDiagTick = elapsed;\n            BotLog.action(bot, "build_target_not_observable", "pos", compact(pos), "phase", reason,\n                    "bot", compact(bot.getBlockPos()), "retry", retryTicks);\n        }\n', 1),
  ('            BlockPos stand = nearbyStand(bot, pos);\n            if (stand != null) {\n',
   '            BlockPos stand = nearbyStand(bot, pos);\n            if (elapsed - workPoseDiagTick >= 40) {\n                workPoseDiagTick = elapsed;\n                BotLog.action(bot, "build_work_pose_state",\n                        "pos", compact(pos), "phase", reason,\n                        "stand", stand == null ? "none" : compact(stand),\n                        "retry", retryTicks);\n            }\n            if (stand != null) {\n', 1),
  ('                    placeDelayTicks = 4;\n                }\n',
   '                    placeDelayTicks = 4;\n                } else if (path.isFailed()) {\n                    // R2.1: fallback 寻路失败不再被静默丢弃——typed 计数,与 moveWithinReach 同预算判死。\n                    retryTicks++;\n                    if (retryTicks > 12) {\n                        fail("path_to_" + reason + "_failed:" + path.reason());\n                    }\n                }\n            } else {\n                // R2.1: 无可观察工作位不再 80t 静默空转——节流 typed 诊断(L0/L1 journal,不 wake DSH),\n                // 40t 预算内仍无工作位则快速失败,替代事后才出现的 target_timeout。\n                retryTicks++;\n                if (elapsed - workPoseDiagTick >= 40) {\n                    workPoseDiagTick = elapsed;\n                    BotLog.action(bot, "build_work_pose_blocked",\n                            "pos", compact(pos),\n                            "reason", reason,\n                            "bot", compact(bot.getBlockPos()),\n                            "retry", retryTicks);\n                }\n                if (retryTicks > 40) {\n                    fail("no_observable_work_pose_for_" + reason + ":" + compact(pos));\n                }\n', 1),
  ('    private BlockPos nearbyStand(AIPlayerEntity bot, BlockPos pos) {\n        Standability.clearCache();\n',
   '    private BlockPos nearbyStand(AIPlayerEntity bot, BlockPos pos) {\n        return nearbyStand(bot, pos, bot.getBlockInteractionRange() * bot.getBlockInteractionRange());\n    }\n\n    private BlockPos nearbyStand(AIPlayerEntity bot, BlockPos pos, double reachSquared) {\n        if (cachedStandTarget != null && cachedStandTarget.equals(pos) && elapsed - cachedStandTick < 20) {\n            return cachedStand; // includes null: a fruitless scan stays fruitless for a short window\n        }\n        BlockPos found = nearbyStandUncached(bot, pos, reachSquared);\n        cachedStand = found;\n        cachedStandTarget = pos;\n        cachedStandTick = elapsed;\n        return found;\n    }\n\n    private BlockPos nearbyStandUncached(AIPlayerEntity bot, BlockPos pos, double reachSquared) {\n        Standability.clearCache();\n', 1),
  ('        BlockPos exterior = preferredExteriorStand(bot, pos, current);\n',
   '        BlockPos exterior = preferredExteriorStand(bot, pos, current, reachSquared);\n', 1),
  ('                                || !isObservableStandable(bot, candidate)) {\n',
   '                                || !isWorkPoseUsable(bot, candidate, pos, reachSquared)) {\n', 1),
  ('    private BlockPos preferredExteriorStand(AIPlayerEntity bot, BlockPos target, BlockPos current) {\n',
   '    private BlockPos preferredExteriorStand(AIPlayerEntity bot, BlockPos target, BlockPos current,\n                                            double reachSquared) {\n', 1),
  ('                        && isObservableStandable(bot, candidate)) {\n',
   '                        && isWorkPoseUsable(bot, candidate, target, reachSquared)) {\n', 1),
  ("     * Work-pose selection is part of planning, not the pathfinder's local collision adapter. In\n     * strict survival it therefore has to prove the ground, feet, and head cells observable before\n     * consulting raw standability. Operator mode passes these queries through its explicit hidden\n     * scan capability.\n",
   '     * R2.1: a work pose is chosen by what the bot can see AFTER arriving there, not by whether the\n     * stand cells happen to be visible from the CURRENT eye position. The old rule (ground/feet/\n     * head observable from here) dead-locked HOME repair: with a missing cell on the far facade of\n     * a closed cabin every candidate — inside the cabin and behind the far wall alike — was\n     * "unobservable from here", nearbyStand returned null forever, and the build budget burned\n     * down as a silent 80t target_timeout with zero placement and zero diagnostics (LIVE-R2-7).\n     * The strict observation policy itself is NOT relaxed: the candidate is accepted only if the\n     * same strict raycast proves the TARGET cell observable from the candidate\'s eye height and\n     * within interaction reach once the bot stands there.\n', 1),
  ('    private static boolean isObservableStandable(AIPlayerEntity bot, BlockPos candidate) {\n        return ObservableWorldQuery.canObserveBlock(bot, candidate.down())\n                && ObservableWorldQuery.canObserveCell(bot, candidate)\n                && ObservableWorldQuery.canObserveCell(bot, candidate.up())\n                && Standability.isStandable(bot.getServerWorld(), candidate);\n',
   '    private static boolean isWorkPoseUsable(AIPlayerEntity bot, BlockPos candidate, BlockPos target,\n                                            double reachSquared) {\n        Vec3d eye = new Vec3d(candidate.getX() + 0.5D, candidate.getY() + 1.62D, candidate.getZ() + 0.5D);\n        if (eye.squaredDistanceTo(target.toCenterPos()) > reachSquared) {\n            return false;\n        }\n        // Observe first: the strict raycast is the boundary that must gate any later raw world read\n        // (upstream rule), so raw standability is consulted only after the target is proven visible.\n        if (!ObservableWorldQuery.canObserveCellFrom(bot, eye, target)) {\n            return false;\n        }\n        return Standability.isStandable(bot.getServerWorld(), candidate);\n', 1),
 ]),
 'action/FarmAction.java': ('b9ecd3f9adffd031951eb8ff4637f7299a19dca2', [
  ('    public static ActionResult till(AIPlayerEntity bot, BlockPos ground) {\n        ServerWorld world = bot.getServerWorld();\n',
   '    public static ActionResult till(AIPlayerEntity bot, BlockPos ground) {\n        // R2.1: reserved external bodies may only till inside a registered exact farm mask.\n        String denial = io.github.zoyluo.aibot.external.SemanticWorldRegistry.farmMutationDenial(bot, ground, "till");\n        if (denial != null) {\n            BotLog.action(bot, "farm_mutation_denied", "pos", ground, "reason", denial);\n            return ActionResult.failed(denial);\n        }\n        ServerWorld world = bot.getServerWorld();\n', 1),
  ('    public static ActionResult plant(AIPlayerEntity bot, BlockPos farmland, Item seed, Block crop) {\n        ServerWorld world = bot.getServerWorld();\n',
   '    public static ActionResult plant(AIPlayerEntity bot, BlockPos farmland, Item seed, Block crop) {\n        // R2.1: reserved external bodies may only plant on registered exact farm cells.\n        String denial = io.github.zoyluo.aibot.external.SemanticWorldRegistry.farmMutationDenial(bot, farmland, "plant");\n        if (denial != null) {\n            BotLog.action(bot, "farm_mutation_denied", "pos", farmland, "reason", denial);\n            return ActionResult.failed(denial);\n        }\n        ServerWorld world = bot.getServerWorld();\n', 1),
  ('    public static ActionResult harvest(AIPlayerEntity bot, BlockPos cropPos) {\n        ServerWorld world = bot.getServerWorld();\n',
   '    public static ActionResult harvest(AIPlayerEntity bot, BlockPos cropPos) {\n        // R2.1: reserved external bodies may only harvest crops standing on registered farm cells\n        // (the crop cell resolves to its farmland through cell.down()).\n        String denial = io.github.zoyluo.aibot.external.SemanticWorldRegistry.farmMutationDenial(bot, cropPos, "harvest");\n        if (denial != null) {\n            BotLog.action(bot, "farm_mutation_denied", "pos", cropPos, "reason", denial);\n            return ActionResult.failed(denial);\n        }\n        ServerWorld world = bot.getServerWorld();\n', 1),
 ]),
 'mode/ObservableWorldQuery.java': ('e10b86a4fb50ad21b968a496374215014232e636', [
  ('    /**\n     * Prey-grounding cell observation at surface-search range; see\n',
   '    /**\n     * R2.1 planner variant of {@link #canObserveCell(AIPlayerEntity, BlockPos)}: the same strict\n     * cell-observation rule (capability gate, perception radius, collider/fluid raycast, MISS or\n     * exact hit on the target cell) evaluated from a hypothetical eye position, so work-pose\n     * selection can prove the bot will see the target AFTER walking to a candidate stand. No\n     * observation rule is relaxed; only the eye the planner evaluates from is substituted.\n     */\n    public static boolean canObserveCellFrom(AIPlayerEntity bot, Vec3d eye, BlockPos pos) {\n        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,\n                "observable_cell_query").allowed()) {\n            return true;\n        }\n        int radius = Math.max(1, AIBotConfig.get().perception().radius());\n        if (eye.squaredDistanceTo(pos.toCenterPos()) > (double) radius * radius) {\n            return false;\n        }\n        BlockHitResult hit = bot.getServerWorld().raycast(new RaycastContext(\n                eye, pos.toCenterPos(),\n                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, bot));\n        return hit.getType() == HitResult.Type.MISS\n                || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos));\n    }\n\n    /**\n     * Prey-grounding cell observation at surface-search range; see\n', 1),
 ]),
}

# 不在 PREFIX 下的补充锚点补丁(带同样的 blob 校验)。MC-1C-A:把专项 GameTest 注册进 gametest 入口,
# 否则 fabric-gametest 不会发现它,干净重建后测试静默消失。
EXTRA_CHANGES={
 'src/gametest/resources/fabric.mod.json': ('25ecb31ca127ed2e9d57ccb9b5c223066092f3e5', [
  ('"io.github.zoyluo.aibot.gametest.AIBotDeterministicGameTests",',
   '"io.github.zoyluo.aibot.gametest.AIBotDeterministicGameTests",\n            "io.github.zoyluo.aibot.gametest.MC1CASemanticsGameTests",\n            "io.github.zoyluo.aibot.gametest.MC1CAR2GameTests",\n            "io.github.zoyluo.aibot.gametest.MC1CAR21GameTests",\n            "io.github.zoyluo.aibot.gametest.MC2A0CognitiveViewGameTests",\n            "io.github.zoyluo.aibot.gametest.MC2A01CognitiveBoundaryGameTests",\n            "io.github.zoyluo.aibot.gametest.MC2A01FClosureGameTests",\n            "io.github.zoyluo.aibot.gametest.MC2A02TreeHarvestGameTests",\n            "io.github.zoyluo.aibot.gametest.MC2A03OpportunityIncarnationGameTests",\n            "io.github.zoyluo.aibot.gametest.MC2A03ASessionBoundaryGameTests",', 1),
 ]),
 'src/test/java/io/github/zoyluo/aibot/mode/PrivilegedBoundarySourceTest.java': ('07d61c3c7b180d12361b8ab6bbe8983f42ed30f4', [
  ('        assertEquals(2, occurrences(buildTask, "isObservableStandable(bot, candidate)"),\n                "both work-pose scans must cross the observable-world boundary");\n',
   '        // R2.1: work-pose selection proves the TARGET cell observable through the strict boundary\n        // (ObservableWorldQuery.canObserveCellFrom) evaluated from the candidate stand eye — the\n        // old "stand visible from the CURRENT eye" rule dead-locked HOME repair (LIVE-R2-7).\n        assertEquals(2, occurrences(buildTask, "isWorkPoseUsable(bot, candidate,"),\n                "both work-pose scans must prove the target observable through the strict boundary");\n', 1),
  ('                "raw standability must stay inside the observable work-pose adapter");\n        assertFalse(StructureVerifier.matches(\n',
   '                "raw standability must stay inside the observable work-pose adapter");\n        assertTrue(buildTask.contains("ObservableWorldQuery.canObserveCellFrom"),\n                "work-pose acceptance must run the strict observation raycast, not raw reads");\n        assertFalse(StructureVerifier.matches(\n', 1),
  ('    private static Map<String, String> matchingSources(Pattern pattern) throws IOException {\n',
   '''    @Test
    void cognitiveViewStaysAReadModel() throws IOException {
        // MC-2A0 (VIEW-1/READ-1): the cognitive view reports evidence only. Builders must not
        // mutate the world, the registry ledger or the body inventory from the query path.
        String[] cognitionSources = {
                "external/cognition/CognitiveViewBuilder.java",
                "external/cognition/CognitiveInspector.java",
                "external/cognition/CanonicalJson.java",
                "external/cognition/EvidenceRef.java",
                "external/cognition/CognitiveSnapshot.java",
                "external/cognition/StructureKnowledge.java"};
        for (String relative : cognitionSources) {
            String source = read(relative);
            assertFalse(source.contains(".setBlockState("), relative + " must not place blocks");
            assertFalse(source.contains(".breakBlock("), relative + " must not break blocks");
            assertFalse(source.contains(".insertStack("), relative + " must not inject inventory");
            assertFalse(source.contains(".assign("), relative + " must not assign tasks");
        }
        String backend = read("external/MinecraftBodyBackend.java");
        // MC-2A0.3: 调用点改为 FQN 前缀+换行布局,匹配串放宽为方法调用尾部(语义不变)。
        assertTrue(backend.contains(".build(bot,journal,semanticSnapshot)"),
                "the view must reuse the observe semantic cache, never run a second full scan");
    }

    @Test
    void cognitiveLocalScanProvesObservabilityBeforeBlockRead() throws IOException {
        // MC-2A0.1 (BOUND-1/SEC-2): raw block reads in the cognition local-scan path may only
        // happen after the strict-survival observability proof. The SOURCE ORDER itself is the
        // contract — a black-box no-leak test cannot prove the read boundary.
        String inspector = read("external/cognition/CognitiveInspector.java");
        int scan = inspector.indexOf("inspectLocalJson");
        assertTrue(scan >= 0, "inspectLocalJson must exist");
        int proof = inspector.indexOf("canObserveBlock", scan);
        int read = inspector.indexOf("getBlockState", scan);
        assertTrue(proof >= 0, "inspectLocalJson must gate through canObserveBlock");
        assertTrue(read >= 0, "inspectLocalJson must read block state");
        assertTrue(proof < read,
                "canObserveBlock proof must occur BEFORE the first getBlockState read in inspectLocalJson");
        String knowledge = read("external/cognition/StructureKnowledge.java");
        int verifyProof = knowledge.indexOf("canObserveBlock");
        int verifyRead = knowledge.indexOf("getBlockState");
        assertTrue(verifyProof >= 0 && verifyRead >= 0 && verifyProof < verifyRead,
                "structure verification must prove all cells before reading any cell");
    }

    @Test
    void cognitiveInspectIsLazyAndStaysInsideEvidenceScope() throws IOException {
        // MC-2A0.1 (LAZY-1/2, SCOPE-1): the periodic view snapshot must not materialize inspect
        // details; homeRepairPlan may only run inside the explicit baseline materialization; no
        // autonomy runtime symbols may appear in the cognition package.
        String builder = read("external/cognition/CognitiveViewBuilder.java");
        assertFalse(builder.contains("CognitiveInspector.buildIndex"),
                "view build must not precompute the inspect detail index");
        assertFalse(builder.contains("homeRepairPlan"),
                "a periodic cognitive snapshot refresh must never trigger homeRepairPlan (LAZY-2)");
        assertTrue(builder.contains("EvidenceDescriptor"),
                "the snapshot must carry lightweight evidence descriptors instead of detail JSON");
        String inspector = read("external/cognition/CognitiveInspector.java");
        assertTrue(inspector.indexOf("private static Map<String, Object> structureBaselineDetail") >= 0
                        && inspector.indexOf("SemanticWorldRegistry.homeRepairPlan")
                        > inspector.indexOf("private static Map<String, Object> structureBaselineDetail"),
                "homeRepairPlan may only be reached from the explicit baseline detail path");
        String backend = read("external/MinecraftBodyBackend.java");
        // MC-2A0.3: 调用点改为 FQN 前缀+换行布局,匹配串放宽为方法调用尾部(语义不变)。
        assertTrue(backend.contains(".materialize(bot,semanticSnapshot,gameTime,ref,detail)"),
                "on-demand materialization must reuse the same cached semantic snapshot");
        for (String banned : new String[]{"AgendaItem", "TaskGraphStore", "GraphFragment", "GraphProducer", "AutonomyScheduler"}) {
            for (String relative : new String[]{
                    "external/cognition/CognitiveViewBuilder.java",
                    "external/cognition/CognitiveInspector.java",
                    "external/cognition/CognitiveSnapshot.java",
                    "external/cognition/StructureKnowledge.java",
                    "external/cognition/CanonicalJson.java",
                    "external/cognition/EvidenceRef.java"}) {
                assertFalse(read(relative).contains(banned),
                        relative + " must not contain autonomy runtime symbol " + banned);
            }
        }
    }

    @Test
    void structureKnowledgeCacheKeyIncludesWorldId() throws IOException {
        // MC-2A0.1F (COG-AQ-6/SPATIAL-CACHE-1): LAST_KNOWN verification identity must never
        // alias across saves/worlds. The CACHE has no lifecycle eviction, so only binding the
        // per-save world_id into the key can keep two same-named structures in different
        // worlds from sharing a verification.
        String knowledge = read("external/cognition/StructureKnowledge.java");
        assertTrue(knowledge.contains("SemanticWorldRegistry.worldId() + \\"/\\" + structure.dimension() + \\"/\\" + structure.id()"),
                "StructureKnowledge cache key must be world_id/dimension/object-id scoped");
    }

    @Test
    void automaticObservationUsesBoundedSemanticSnapshot() throws IOException {
        // MC-2A0.1F (AUTO-OBS-1..3/COG-AQ-1): the automatic refresh path must take the bounded
        // registry entry — the omniscient observe() variant remote-scans registered structure
        // baselines with no observability proof and may only serve EXPLICIT operations.
        String backend = read("external/MinecraftBodyBackend.java");
        assertTrue(backend.contains("SemanticWorldRegistry.observeBounded(bot)"),
                "automatic semantic cache refresh must use observeBounded, never omniscient observe");
        assertFalse(backend.contains("SemanticWorldRegistry.observe(bot)"),
                "the automatic backend must not call the omniscient observe variant at all");
        String builder = read("external/cognition/CognitiveViewBuilder.java");
        assertTrue(builder.contains("SemanticWorldRegistry.observeBounded(bot)"),
                "the cognitive view fallback must use the bounded semantic snapshot");
    }

    private static Map<String, String> matchingSources(Pattern pattern) throws IOException {
''', 1),
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
    for relative,(expected,rules) in EXTRA_CHANGES.items():
        path=repo/relative
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
