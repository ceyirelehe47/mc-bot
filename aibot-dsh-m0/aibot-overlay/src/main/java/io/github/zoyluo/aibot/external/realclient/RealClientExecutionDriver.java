package io.github.zoyluo.aibot.external.realclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.external.BodyBackend;
import io.github.zoyluo.aibot.external.BridgeFault;
import io.github.zoyluo.aibot.external.JsonOutput;
import io.github.zoyluo.aibot.external.PhysicalExecutionDriver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class RealClientExecutionDriver
        implements PhysicalExecutionDriver {
    public static final Set<String> OPERATIONS=
            Set.of("say","goto","mine_opportunity","deposit");

    private static final int MAX_GOTO_DISTANCE=32;
    private static final int EXECUTION_TIMEOUT_TICKS=20*120;
    private static final long SCREEN_FRESH_MS=2500L;
    private static final Set<String> DEPOSIT_ADAPTERS=Set.of(
            "vanilla_generic_storage_v1",
            "mc2a_fixture_storage_v1");

    private final MinecraftServer server;
    private final RealClientServerTransport transport;
    private final RealClientOpportunityTracker tracker;
    private final Supplier<ServerPlayerEntity> body;
    private String activeExecution="";

    public RealClientExecutionDriver(
            MinecraftServer server,
            RealClientServerTransport transport,
            RealClientOpportunityTracker tracker,
            Supplier<ServerPlayerEntity> body) {
        this.server=Objects.requireNonNull(server);
        this.transport=Objects.requireNonNull(transport);
        this.tracker=Objects.requireNonNull(tracker);
        this.body=Objects.requireNonNull(body);
    }

    @Override public BodyBackend.Handle start(
            Request request) {
        onThread();
        if(!OPERATIONS.contains(request.operation()))
            throw new BridgeFault(
                    409,
                    "operation_not_supported_by_real_client_backend");
        ServerPlayerEntity player=requireBody();
        JsonObject args=parse(request.argumentsJson());
        long startedAt=server.getTicks();
        activeExecution=request.executionId();
        return switch(request.operation()) {
            case "say" ->
                    startSay(request,args,startedAt);
            case "goto" ->
                    startGoto(
                            request,args,player,startedAt);
            case "mine_opportunity" ->
                    startMine(
                            request,args,player,startedAt);
            case "deposit" ->
                    startDeposit(
                            request,args,player,startedAt);
            default -> throw new BridgeFault(
                    409,
                    "operation_not_supported_by_real_client_backend");
        };
    }

    private BodyBackend.Handle startSay(
            Request request,JsonObject args,long startedAt) {
        only(args,Set.of("message"));
        String message=string(args,"message",1000);
        if(!transport.sendCommand(
                request.executionId(),"say",
                JsonOutput.encode(Map.of(
                        "message",message))))
            throw new BridgeFault(
                    503,
                    "real_client_command_queue_unavailable");
        return ()->remoteSnapshot(
                request.executionId(),startedAt,null,true);
    }

    private BodyBackend.Handle startGoto(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of(
                "x","y","z",
                "allow_terrain_changes",
                "face_x","face_y","face_z"));
        if(args.has("allow_terrain_changes")
                &&args.get(
                        "allow_terrain_changes").getAsBoolean())
            throw new BridgeFault(
                    400,
                    "real_client_goto_mvp_disallows_terrain_changes");

        BlockPos target=new BlockPos(
                integer(
                        args,"x",
                        -29999984,29999984),
                integer(
                        args,"y",
                        player.getServerWorld().getBottomY(),
                        player.getServerWorld().getBottomY()
                                +player.getServerWorld()
                                        .getHeight()-1),
                integer(
                        args,"z",
                        -29999984,29999984));
        if(player.getBlockPos().getSquaredDistance(target)
                >MAX_GOTO_DISTANCE*MAX_GOTO_DISTANCE)
            throw new BridgeFault(
                    400,
                    "real_client_goto_distance_limit_32");

        boolean hasFace=
                args.has("face_x")
                ||args.has("face_y")
                ||args.has("face_z");
        if(hasFace
                &&(!args.has("face_x")
                ||!args.has("face_y")
                ||!args.has("face_z")))
            throw new BridgeFault(
                    400,
                    "real_client_goto_face_requires_all_axes");

        BlockPos faceTarget=null;
        if(hasFace) {
            faceTarget=new BlockPos(
                    integer(
                            args,"face_x",
                            -29999984,29999984),
                    integer(
                            args,"face_y",
                            player.getServerWorld().getBottomY(),
                            player.getServerWorld().getBottomY()
                                    +player.getServerWorld()
                                            .getHeight()-1),
                    integer(
                            args,"face_z",
                            -29999984,29999984));
            if(player.getBlockPos()
                    .getSquaredDistance(faceTarget)>16D*16D)
                throw new BridgeFault(
                        400,
                        "real_client_goto_face_distance_limit_16");
        }

        Map<String,Object> command=
                new LinkedHashMap<>();
        command.put("x",target.getX());
        command.put("y",target.getY());
        command.put("z",target.getZ());
        command.put("arrival_radius",2.5D);
        if(faceTarget!=null) {
            command.put("face_x",faceTarget.getX());
            command.put("face_y",faceTarget.getY());
            command.put("face_z",faceTarget.getZ());
        }
        if(!transport.sendCommand(
                request.executionId(),"goto",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,
                    "real_client_command_queue_unavailable");

        BlockPos finalFace=faceTarget;
        return ()->gotoSnapshot(
                request.executionId(),startedAt,
                target,finalFace);
    }

    private BodyBackend.Snapshot gotoSnapshot(
            String executionId,long startedAt,
            BlockPos target,BlockPos faceTarget) {
        onThread();
        ServerPlayerEntity current=body.get();
        if(current==null)
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_body_unavailable");

        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(
                        System.currentTimeMillis()))
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_session_unavailable");

        var remote=transport.execution(
                executionId).orElse(null);
        if(remote!=null
                &&Set.of(
                        "failed","cancelled","outcome_unknown")
                        .contains(remote.state()))
            return new BodyBackend.Snapshot(
                    remote.state(),remote.progress(),
                    remote.reason());

        boolean arrived=current.getPos()
                .squaredDistanceTo(target.toCenterPos())
                <=6.25D;
        if(faceTarget==null && arrived)
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_arrival_within_2_5_blocks");

        if(faceTarget!=null
                &&arrived
                &&remote!=null
                &&"completed".equals(remote.state())
                &&"client_arrival_and_facing_reported"
                        .equals(remote.reason())) {
            var sensor=session.sensor();
            long now=System.currentTimeMillis();
            if(sensor!=null
                    &&sensor.gameSession()
                            .equals(session.gameSession())
                    &&now-sensor.receivedAtMs()
                            <=RealClientOpportunityTracker
                                    .FRAME_FRESH_MS
                    &&sensor.crosshairPresent()
                    &&sensor.crosshairX()==faceTarget.getX()
                    &&sensor.crosshairY()==faceTarget.getY()
                    &&sensor.crosshairZ()==faceTarget.getZ())
                return new BodyBackend.Snapshot(
                        "completed",1D,
                        "server_authoritative_arrival_and_facing_verified");
        }

        if(server.getTicks()-startedAt
                >EXECUTION_TIMEOUT_TICKS) {
            transport.sendControl(
                    executionId,"cancel",
                    "real_client_execution_timeout");
            return new BodyBackend.Snapshot(
                    "failed",0D,
                    "real_client_execution_timeout");
        }

        String reason=faceTarget!=null && arrived
                ?"awaiting_client_final_facing_ack"
                :remote==null
                        ?"awaiting_real_client_ack"
                        :remote.reason();
        return new BodyBackend.Snapshot(
                "running",
                remote==null
                        ?0D
                        :Math.min(.99D,remote.progress()),
                reason);
    }

    private BodyBackend.Handle startMine(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of("id"));
        String opportunityId=string(args,"id",64);
        RealClientOpportunityTracker.Opportunity opportunity=
                tracker.opportunity(player,opportunityId)
                        .orElseThrow(
                                ()->new BridgeFault(
                                        404,
                                        "real_client_resource_"
                                                +"opportunity_not_found"));
        var state=player.getServerWorld()
                .getBlockState(opportunity.pos());
        String actualBlock=
                Registries.BLOCK.getId(
                        state.getBlock()).toString();
        if(!actualBlock.equals(opportunity.blockId())) {
            tracker.markStale(
                    request.executionId(),
                    opportunity,
                    "target_cell_changed_before_dispatch");
            throw new BridgeFault(
                    409,
                    "real_client_resource_opportunity_stale");
        }

        Identifier expectedId=
                Identifier.tryParse(
                        opportunity.expectedItem());
        if(expectedId==null
                ||!Registries.ITEM.containsId(expectedId)
                ||"minecraft:air".equals(
                        opportunity.expectedItem()))
            throw new BridgeFault(
                    409,
                    "real_client_expected_drop_not_supported");

        int slot=selectSuitableHotbar(player,state);
        if(slot<0)
            throw new BridgeFault(
                    409,
                    "real_client_required_hotbar_tool_missing");

        int baseline=countItem(
                player,opportunity.expectedItem());
        String face=transport.session()
                .map(RealClientServerTransport
                        .SessionSnapshot::sensor)
                .filter(sensor->
                        sensor!=null
                        &&sensor.crosshairPresent()
                        &&sensor.crosshairX()
                                ==opportunity.pos().getX()
                        &&sensor.crosshairY()
                                ==opportunity.pos().getY()
                        &&sensor.crosshairZ()
                                ==opportunity.pos().getZ())
                .map(RealClientServerTransport
                        .SensorSnapshot::crosshairSide)
                .orElse("UP");

        Map<String,Object> command=
                new LinkedHashMap<>();
        command.put("x",opportunity.pos().getX());
        command.put("y",opportunity.pos().getY());
        command.put("z",opportunity.pos().getZ());
        command.put("face",face);
        command.put("slot",slot);
        command.put("block",opportunity.blockId());
        command.put(
                "expected_item",
                opportunity.expectedItem());
        command.put("baseline_count",baseline);
        if(!transport.sendCommand(
                request.executionId(),
                "mine_opportunity",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,
                    "real_client_command_queue_unavailable");
        return ()->mineSnapshot(
                request.executionId(),startedAt,
                opportunity,baseline);
    }

    private BodyBackend.Snapshot mineSnapshot(
            String executionId,long startedAt,
            RealClientOpportunityTracker.Opportunity opportunity,
            int baseline) {
        onThread();
        ServerPlayerEntity player=body.get();
        if(player==null)
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_body_unavailable");

        var currentState=player.getServerWorld()
                .getBlockState(opportunity.pos());
        String currentBlock=
                Registries.BLOCK.getId(
                        currentState.getBlock()).toString();
        int current=countItem(
                player,opportunity.expectedItem());

        if(!currentState.isAir()
                &&!currentBlock.equals(
                        opportunity.blockId())) {
            tracker.markStale(
                    executionId,opportunity,
                    "target_cell_replaced_during_execution");
            return new BodyBackend.Snapshot(
                    "failed",0D,
                    "real_client_resource_opportunity_stale");
        }

        if(currentState.isAir()&&current>baseline) {
            tracker.markConsumed(
                    executionId,opportunity,
                    player,baseline,current);
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_block_and_"
                            +"inventory_gain_verified:"
                            +baseline+"->"+current);
        }

        if(server.getTicks()-startedAt
                >EXECUTION_TIMEOUT_TICKS) {
            transport.sendControl(
                    executionId,"cancel",
                    "real_client_execution_timeout");
            if(currentState.isAir()) {
                tracker.markStale(
                        executionId,opportunity,
                        "block_gone_without_inventory_"
                                +"gain_before_timeout");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "real_client_resource_opportunity_stale");
            }
            return new BodyBackend.Snapshot(
                    "failed",0D,
                    "real_client_execution_timeout");
        }

        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(
                        System.currentTimeMillis()))
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_session_unavailable");

        var remote=transport.execution(
                executionId).orElse(null);
        if(remote!=null
                &&Set.of(
                        "failed","cancelled","outcome_unknown")
                        .contains(remote.state())) {
            if(currentState.isAir()
                    &&!"outcome_unknown".equals(
                            remote.state())) {
                tracker.markStale(
                        executionId,opportunity,
                        "block_gone_without_inventory_gain:"
                                +remote.state());
                return new BodyBackend.Snapshot(
                        "failed",remote.progress(),
                        "real_client_resource_opportunity_stale");
            }
            return new BodyBackend.Snapshot(
                    remote.state(),remote.progress(),
                    remote.reason());
        }

        String reason=remote!=null
                &&"completed".equals(remote.state())
                ?"client_completed_awaiting_server_"
                        +"block_inventory_proof"
                :remote==null
                        ?"awaiting_real_client_ack"
                        :remote.reason();
        return new BodyBackend.Snapshot(
                "running",
                remote==null
                        ?0D
                        :Math.min(.99D,remote.progress()),
                reason);
    }

    private record ContainerTarget(
            BlockPos pos,String face,Inventory inventory) {}

    private BodyBackend.Handle startDeposit(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of());
        ContainerTarget target=
                validatedBarrelTarget(player);

        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(
                        System.currentTimeMillis()))
            throw new BridgeFault(
                    409,
                    "real_client_sensor_unavailable");
        var priorScreen=session.screen();
        if(priorScreen!=null && priorScreen.present())
            throw new BridgeFault(
                    409,
                    "real_client_screen_already_open");
        long baselineScreenSeq=
                priorScreen==null
                        ?-1L
                        :priorScreen.screenSeq();

        int playerBaseline=
                countPlayerInventory(player);
        int containerBaseline=
                countInventory(target.inventory());

        Map<String,Object> command=
                new LinkedHashMap<>();
        command.put("phase","open");
        command.put("x",target.pos().getX());
        command.put("y",target.pos().getY());
        command.put("z",target.pos().getZ());
        command.put("face",target.face());
        command.put(
                "baseline_screen_seq",
                baselineScreenSeq);

        if(!transport.sendCommand(
                request.executionId(),"deposit",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,
                    "real_client_command_queue_unavailable");

        return new DepositHandle(
                request.executionId(),startedAt,
                target,playerBaseline,
                containerBaseline,baselineScreenSeq);
    }

    private final class DepositHandle
            implements BodyBackend.Handle {
        private final String executionId;
        private final long startedAt;
        private final ContainerTarget target;
        private final int playerBaseline;
        private final int containerBaseline;
        private final long baselineScreenSeq;

        private boolean commitSent;
        private boolean mutationAckSeen;
        private String ownedScreenEpoch="";
        private String ownedAdapterId="";
        private long ownedScreenSeq=-1L;
        private int ownedSyncId=-1;

        DepositHandle(
                String executionId,long startedAt,
                ContainerTarget target,
                int playerBaseline,int containerBaseline,
                long baselineScreenSeq) {
            this.executionId=executionId;
            this.startedAt=startedAt;
            this.target=target;
            this.playerBaseline=playerBaseline;
            this.containerBaseline=containerBaseline;
            this.baselineScreenSeq=baselineScreenSeq;
        }

        @Override public BodyBackend.Snapshot snapshot() {
            onThread();
            ServerPlayerEntity player=body.get();
            if(player==null)
                return new BodyBackend.Snapshot(
                        "outcome_unknown",0D,
                        "real_client_body_unavailable");

            if(!player.getServerWorld()
                    .getBlockState(target.pos())
                    .isOf(Blocks.BARREL))
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "real_client_deposit_target_changed");

            var blockEntity=player.getServerWorld()
                    .getBlockEntity(target.pos());
            if(!(blockEntity instanceof Inventory inventory))
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "real_client_deposit_inventory_unavailable");

            int playerCurrent=
                    countPlayerInventory(player);
            int containerCurrent=
                    countInventory(inventory);
            int fromPlayer=
                    playerBaseline-playerCurrent;
            int intoContainer=
                    containerCurrent-containerBaseline;

            var session=transport.session().orElse(null);
            if(session==null
                    ||!session.fresh(
                            System.currentTimeMillis()))
                return new BodyBackend.Snapshot(
                        "outcome_unknown",0D,
                        "real_client_session_unavailable");

            var remote=transport.execution(
                    executionId).orElse(null);
            if(commitSent && remote!=null
                    &&("client_owned_screen_quick_move"
                            .equals(remote.reason())
                    ||"client_owned_screen_settling"
                            .equals(remote.reason())
                    ||"client_owned_screen_quick_move_finished"
                            .equals(remote.reason())
                    ||"completed".equals(remote.state())))
                mutationAckSeen=true;

            // A coincidental external inventory change before the ownership commit, or without a
            // client mutation acknowledgement, can never satisfy this execution.
            if(commitSent && mutationAckSeen
                    &&fromPlayer>0&&fromPlayer==intoContainer)
                return new BodyBackend.Snapshot(
                        "completed",1D,
                        "server_authoritative_owned_screen_"
                                +"container_transfer_verified:"
                                +fromPlayer);

            if(remote!=null
                    &&Set.of(
                            "failed","cancelled","outcome_unknown")
                            .contains(remote.state()))
                return new BodyBackend.Snapshot(
                        remote.state(),remote.progress(),
                        remote.reason());

            if(server.getTicks()-startedAt
                    >EXECUTION_TIMEOUT_TICKS) {
                transport.sendControl(
                        executionId,"cancel",
                        "real_client_execution_timeout");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "real_client_container_transfer_not_proven");
            }

            var screen=session.screen();
            if(!commitSent) {
                if(screen!=null
                        &&screen.present()
                        &&screen.screenSeq()>baselineScreenSeq) {
                    authorizeOwnedScreen(
                            player,inventory,screen);
                }
                if(!commitSent)
                    return new BodyBackend.Snapshot(
                            "running",
                            remote==null
                                    ?.25D
                                    :Math.min(
                                            .49D,
                                            remote.progress()),
                            "awaiting_server_owned_target_screen");
            }

            // MC-2A0.6 修正:客户端 completed 后按完成语义关闭 Screen 属正常路径,
            // 不得判为所有权丢失;此时保持 running 等待服务器库存证明或超时
            // (PROOF-1: client_completed_awaiting_server_container_inventory_proof)。
            boolean clientCompleted=remote!=null
                    &&"completed".equals(remote.state());
            if(!clientCompleted
                    &&(screen==null
                    ||!screen.present()
                    ||!ownedScreenEpoch.equals(
                            screen.screenEpoch())
                    ||!ownedAdapterId.equals(
                            screen.adapterId())
                    ||ownedSyncId!=screen.syncId()
                    ||screen.screenSeq()<ownedScreenSeq)) {
                transport.sendControl(
                        executionId,"cancel",
                        "real_client_screen_ownership_lost");
                return new BodyBackend.Snapshot(
                        "failed",
                        remote==null?0D:remote.progress(),
                        "real_client_screen_ownership_lost");
            }

            String reason=remote!=null
                    &&"completed".equals(remote.state())
                    ?"client_completed_awaiting_server_"
                            +"container_inventory_proof"
                    :remote==null
                            ?"awaiting_real_client_ack"
                            :remote.reason();
            return new BodyBackend.Snapshot(
                    "running",
                    remote==null
                            ?.5D
                            :Math.min(.99D,remote.progress()),
                    reason);
        }

        private void authorizeOwnedScreen(
                ServerPlayerEntity player,
                Inventory targetInventory,
                RealClientServerTransport.ScreenSnapshot screen) {
            if(System.currentTimeMillis()
                    -screen.receivedAtMs()>SCREEN_FRESH_MS)
                return;
            if(!sessionMatchesCurrentGame(screen))
                return;
            if(!DEPOSIT_ADAPTERS.contains(
                    screen.adapterId()))
                return;
            if(!screen.capabilities().contains(
                    "deposit_quick_move"))
                return;
            if(player.currentScreenHandler
                    ==player.playerScreenHandler)
                return;
            if(player.currentScreenHandler.syncId
                    !=screen.syncId())
                return;
            if(!handlerOwnsTargetInventory(
                    player,targetInventory))
                return;

            Map<String,Object> commit=
                    new LinkedHashMap<>();
            commit.put("phase","commit");
            commit.put(
                    "screen_epoch",
                    screen.screenEpoch());
            commit.put(
                    "screen_seq",
                    screen.screenSeq());
            commit.put(
                    "sync_id",screen.syncId());
            commit.put(
                    "adapter_id",screen.adapterId());
            if(!transport.sendCommand(
                    executionId,"deposit",
                    JsonOutput.encode(commit)))
                throw new BridgeFault(
                        503,
                        "real_client_command_queue_unavailable");

            commitSent=true;
            ownedScreenEpoch=screen.screenEpoch();
            ownedAdapterId=screen.adapterId();
            ownedScreenSeq=screen.screenSeq();
            ownedSyncId=screen.syncId();
        }

        private boolean sessionMatchesCurrentGame(
                RealClientServerTransport.ScreenSnapshot screen) {
            var session=transport.session().orElse(null);
            return session!=null
                    &&screen.gameSession()
                            .equals(session.gameSession());
        }
    }

    private static boolean handlerOwnsTargetInventory(
            ServerPlayerEntity player,
            Inventory targetInventory) {
        int ownedSlots=0;
        for(var slot:player.currentScreenHandler.slots)
            if(slot.inventory==targetInventory)
                ownedSlots++;
        return ownedSlots>0;
    }

    private ContainerTarget validatedBarrelTarget(
            ServerPlayerEntity player) {
        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(
                        System.currentTimeMillis()))
            throw new BridgeFault(
                    409,
                    "real_client_sensor_unavailable");

        var sensor=session.sensor();
        if(sensor==null
                ||!sensor.crosshairPresent()
                ||System.currentTimeMillis()
                        -sensor.receivedAtMs()
                        >RealClientOpportunityTracker
                                .FRAME_FRESH_MS)
            throw new BridgeFault(
                    409,
                    "real_client_container_crosshair_required");

        Vec3d framePos=new Vec3d(
                sensor.x(),sensor.y(),sensor.z());
        if(player.getPos().distanceTo(framePos)
                >RealClientOpportunityTracker
                        .POSITION_TOLERANCE)
            throw new BridgeFault(
                    409,
                    "real_client_container_sensor_position_drift");

        BlockPos pos=new BlockPos(
                sensor.crosshairX(),
                sensor.crosshairY(),
                sensor.crosshairZ());
        Vec3d eye=player.getEyePos();
        Vec3d direction=Vec3d.fromPolar(
                sensor.pitch(),sensor.yaw());
        HitResult ray=player.getServerWorld().raycast(
                new RaycastContext(
                        eye,
                        eye.add(direction.multiply(
                                RealClientOpportunityTracker
                                        .VALIDATION_RANGE)),
                        RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE,
                        player));
        if(!(ray instanceof BlockHitResult hit)
                ||!hit.getBlockPos().equals(pos))
            throw new BridgeFault(
                    409,
                    "real_client_container_sensor_ray_mismatch");

        BlockState state=player.getServerWorld()
                .getBlockState(pos);
        if(!state.isOf(Blocks.BARREL))
            throw new BridgeFault(
                    409,
                    "real_client_deposit_mvp_requires_barrel_crosshair");

        var blockEntity=player.getServerWorld()
                .getBlockEntity(pos);
        if(!(blockEntity instanceof Inventory inventory))
            throw new BridgeFault(
                    409,
                    "real_client_deposit_inventory_unavailable");

        return new ContainerTarget(
                pos,sensor.crosshairSide(),inventory);
    }

    private BodyBackend.Snapshot remoteSnapshot(
            String executionId,long startedAt,
            Supplier<BodyBackend.Snapshot> proof,
            boolean allowRemoteCompleted) {
        onThread();
        BodyBackend.Snapshot proven=
                proof==null?null:proof.get();
        if(proven!=null)return proven;

        if(server.getTicks()-startedAt
                >EXECUTION_TIMEOUT_TICKS) {
            transport.sendControl(
                    executionId,"cancel",
                    "real_client_execution_timeout");
            return new BodyBackend.Snapshot(
                    "failed",0D,
                    "real_client_execution_timeout");
        }

        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(
                        System.currentTimeMillis()))
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_session_unavailable");

        var remote=transport.execution(
                executionId).orElse(null);
        if(remote==null)
            return new BodyBackend.Snapshot(
                    "running",0D,
                    "awaiting_real_client_ack");
        if("completed".equals(remote.state())
                &&!allowRemoteCompleted)
            return new BodyBackend.Snapshot(
                    "running",
                    Math.min(.99D,remote.progress()),
                    "client_completed_awaiting_server_postcondition");
        return new BodyBackend.Snapshot(
                remote.state(),remote.progress(),
                remote.reason());
    }

    @Override public void pause() {
        onThread();
        if(!activeExecution.isBlank())
            transport.sendControl(
                    activeExecution,"pause",
                    "external_pause");
    }

    @Override public void resume() {
        onThread();
        if(!activeExecution.isBlank())
            transport.sendControl(
                    activeExecution,"resume",
                    "external_resume");
    }

    @Override public void cancel(String reason) {
        onThread();
        if(!activeExecution.isBlank())
            transport.sendControl(
                    activeExecution,"cancel",
                    reason==null
                            ?"external_cancel":reason);
    }

    private ServerPlayerEntity requireBody() {
        ServerPlayerEntity player=body.get();
        if(player==null || !player.isAlive())
            throw new BridgeFault(
                    409,"body_unavailable");
        return player;
    }

    private void onThread() {
        if(!server.isOnThread())
            throw new IllegalStateException(
                    "minecraft_access_off_server_thread");
    }

    private static int selectSuitableHotbar(
            ServerPlayerEntity player,BlockState state) {
        for(int slot=0;slot<9;slot++) {
            ItemStack stack=
                    player.getInventory().getStack(slot);
            if(!stack.isEmpty()
                    &&stack.isSuitableFor(state))
                return slot;
        }
        return -1;
    }

    private static int countItem(
            ServerPlayerEntity player,String itemId) {
        int count=0;
        for(int slot=0;
                slot<player.getInventory().size();
                slot++) {
            ItemStack stack=
                    player.getInventory().getStack(slot);
            if(!stack.isEmpty()
                    &&Registries.ITEM.getId(
                            stack.getItem()).toString()
                            .equals(itemId))
                count+=stack.getCount();
        }
        return count;
    }

    private static int countPlayerInventory(
            ServerPlayerEntity player) {
        int count=0;
        int selected=
                player.getInventory().selectedSlot;
        for(int slot=0;slot<36;slot++) {
            if(slot==selected)continue;
            ItemStack stack=
                    player.getInventory().getStack(slot);
            if(!stack.isEmpty())
                count+=stack.getCount();
        }
        return count;
    }

    private static int countInventory(
            Inventory inventory) {
        int count=0;
        for(int slot=0;slot<inventory.size();slot++)
            if(!inventory.getStack(slot).isEmpty())
                count+=inventory.getStack(slot).getCount();
        return count;
    }

    private static JsonObject parse(String raw) {
        try {
            JsonElement value=
                    JsonParser.parseString(raw);
            if(!value.isJsonObject())
                throw new IllegalArgumentException();
            return value.getAsJsonObject();
        } catch(RuntimeException invalid) {
            throw new BridgeFault(
                    400,
                    "invalid_arguments_json_object");
        }
    }

    private static void only(
            JsonObject object,Set<String> allowed) {
        if(!allowed.containsAll(object.keySet()))
            throw new BridgeFault(
                    400,"unknown_argument");
    }

    private static String string(
            JsonObject object,String key,int max) {
        JsonElement value=object.get(key);
        if(value==null
                ||!value.isJsonPrimitive()
                ||!value.getAsJsonPrimitive().isString())
            throw new BridgeFault(
                    400,"string_required:"+key);
        String result=value.getAsString();
        if(result.isBlank() || result.length()>max)
            throw new BridgeFault(
                    400,"invalid_string_length:"+key);
        return result;
    }

    private static int integer(
            JsonObject object,String key,int min,int max) {
        try {
            JsonElement value=object.get(key);
            if(value==null
                    ||!value.isJsonPrimitive()
                    ||!value.getAsJsonPrimitive()
                            .isNumber())
                throw new IllegalArgumentException();
            int result=value.getAsBigDecimal()
                    .intValueExact();
            if(result<min || result>max)
                throw new IllegalArgumentException();
            return result;
        } catch(RuntimeException invalid) {
            throw new BridgeFault(
                    400,"integer_out_of_range:"+key);
        }
    }
}
