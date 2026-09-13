package io.github.zoyluo.aibot.external.realclient;

import io.github.zoyluo.aibot.external.BridgeFault;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Set;

/** Server-authoritative storage target resolved from one fresh coherent sensor frame. */
final class RealClientStorageTarget {
    private static final Set<String> BARREL_ADAPTERS=Set.of(
            "vanilla_generic_storage_v1",
            "mc2a_fixture_storage_v1");
    private static final Set<String> TOMS_ADAPTERS=Set.of(
            "toms_storage_terminal_v1");

    enum Kind { BARREL, TOMS_STORAGE }

    private final Kind kind;
    private final BlockPos pos;
    private final String face;
    private final Inventory barrel;
    private final RealClientTomsStorageServerCompat.Target toms;

    private RealClientStorageTarget(
            Kind kind,BlockPos pos,String face,
            Inventory barrel,
            RealClientTomsStorageServerCompat.Target toms) {
        this.kind=kind;this.pos=pos;this.face=face;
        this.barrel=barrel;this.toms=toms;
    }

    static RealClientStorageTarget resolve(
            ServerPlayerEntity player,
            RealClientServerTransport transport) {
        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(System.currentTimeMillis()))
            throw new BridgeFault(
                    409,"real_client_sensor_unavailable");
        var sensor=session.sensor();
        if(sensor==null
                ||!sensor.crosshairPresent()
                ||System.currentTimeMillis()-sensor.receivedAtMs()
                        >RealClientOpportunityTracker.FRAME_FRESH_MS)
            throw new BridgeFault(
                    409,"real_client_container_crosshair_required");

        Vec3d framePos=new Vec3d(sensor.x(),sensor.y(),sensor.z());
        if(player.getPos().distanceTo(framePos)
                >RealClientOpportunityTracker.POSITION_TOLERANCE)
            throw new BridgeFault(
                    409,"real_client_container_sensor_position_drift");

        BlockPos pos=new BlockPos(
                sensor.crosshairX(),sensor.crosshairY(),sensor.crosshairZ());
        Vec3d eye=player.getEyePos();
        Vec3d direction=Vec3d.fromPolar(sensor.pitch(),sensor.yaw());
        HitResult ray=player.getServerWorld().raycast(new RaycastContext(
                eye,
                eye.add(direction.multiply(
                        RealClientOpportunityTracker.VALIDATION_RANGE)),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player));
        if(!(ray instanceof BlockHitResult hit)
                ||!hit.getBlockPos().equals(pos))
            throw new BridgeFault(
                    409,"real_client_container_sensor_ray_mismatch");

        var state=player.getServerWorld().getBlockState(pos);
        if(state.isOf(Blocks.BARREL)) {
            var blockEntity=player.getServerWorld().getBlockEntity(pos);
            if(!(blockEntity instanceof Inventory inventory))
                throw new BridgeFault(
                        409,"real_client_deposit_inventory_unavailable");
            return new RealClientStorageTarget(
                    Kind.BARREL,pos,sensor.crosshairSide(),
                    inventory,null);
        }

        var toms=RealClientTomsStorageServerCompat.resolve(player,pos);
        if(toms!=null)
            return new RealClientStorageTarget(
                    Kind.TOMS_STORAGE,pos,sensor.crosshairSide(),
                    null,toms);

        throw new BridgeFault(
                409,"real_client_deposit_target_not_supported");
    }

    Kind kind() { return kind; }
    BlockPos pos() { return pos; }
    String face() { return face; }

    boolean stillValid(ServerPlayerEntity player) {
        if(kind==Kind.BARREL)
            return player.getServerWorld().getBlockState(pos)
                    .isOf(Blocks.BARREL)
                    &&player.getServerWorld().getBlockEntity(pos)==barrel;
        var current=RealClientTomsStorageServerCompat.resolve(player,pos);
        return current!=null
                &&current.blockEntity()==toms.blockEntity();
    }

    long count() {
        if(kind==Kind.BARREL) {
            long total=0L;
            for(int slot=0;slot<barrel.size();slot++)
                if(!barrel.getStack(slot).isEmpty())
                    total+=barrel.getStack(slot).getCount();
            return total;
        }
        return RealClientTomsStorageServerCompat.count(toms);
    }

    boolean handlerOwns(
            ServerPlayerEntity player,
            RealClientServerTransport.ScreenSnapshot screen) {
        if(screen==null || !screen.present())return false;
        if(kind==Kind.BARREL) {
            if(!BARREL_ADAPTERS.contains(screen.adapterId()))return false;
            for(var slot:player.currentScreenHandler.slots)
                if(slot.inventory==barrel)return true;
            return false;
        }
        return TOMS_ADAPTERS.contains(screen.adapterId())
                &&RealClientTomsStorageServerCompat.handlerOwns(player,toms);
    }

    boolean adapterAllowed(String adapterId) {
        return kind==Kind.BARREL
                ?BARREL_ADAPTERS.contains(adapterId)
                :TOMS_ADAPTERS.contains(adapterId);
    }

    String kindWire() {
        return kind==Kind.BARREL?"vanilla_barrel":"toms_storage_terminal";
    }
}
