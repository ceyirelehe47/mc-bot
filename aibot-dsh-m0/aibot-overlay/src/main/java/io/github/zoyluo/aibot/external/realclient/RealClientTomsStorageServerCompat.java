package io.github.zoyluo.aibot.external.realclient;

import io.github.zoyluo.aibot.external.BridgeFault;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Exact server-side compatibility layer for Tom's Simple Storage 1.21.3 terminal.
 *
 * <p>All reflection targets are fixed, bounded and private to this adapter. No generic reflective
 * invocation is exposed through the bridge.</p>
 */
final class RealClientTomsStorageServerCompat {
    static final String BLOCK_ID="toms_storage:storage_terminal";
    static final String HANDLER_CLASS=
            "com.tom.storagemod.menu.StorageTerminalMenu";
    static final String BLOCK_ENTITY_CLASS=
            "com.tom.storagemod.block.entity.StorageTerminalBlockEntity";
    static final String STORED_ITEM_CLASS=
            "com.tom.storagemod.inventory.StoredItemStack";
    static final String BLOCK_ENTITY_CONNECTOR_CLASS=
            "com.tom.storagemod.block.entity"
            +".InventoryConnectorBlockEntity";

    record Target(BlockEntity blockEntity,BlockPos pos) {}

    private RealClientTomsStorageServerCompat() {}

    static Target resolve(ServerPlayerEntity player,BlockPos pos) {
        var state=player.getServerWorld().getBlockState(pos);
        String blockId=net.minecraft.registry.Registries.BLOCK
                .getId(state.getBlock()).toString();
        if(!BLOCK_ID.equals(blockId))return null;
        BlockEntity blockEntity=player.getServerWorld().getBlockEntity(pos);
        if(blockEntity==null
                ||!BLOCK_ENTITY_CLASS.equals(blockEntity.getClass().getName()))
            throw new BridgeFault(
                    409,"real_client_toms_storage_block_entity_unavailable");
        return new Target(blockEntity,pos);
    }

    static boolean handlerOwns(
            ServerPlayerEntity player,Target target) {
        Object handler=player.currentScreenHandler;
        if(handler==null
                ||!HANDLER_CLASS.equals(handler.getClass().getName()))
            return false;
        try {
            Field terminalField=handler.getClass().getDeclaredField("te");
            terminalField.setAccessible(true);
            return terminalField.get(handler)==target.blockEntity();
        } catch(ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    static java.util.Map<String,Object> diagnose(
            ServerPlayerEntity player,BlockPos pos) {
        java.util.Map<String,Object> out=new java.util.LinkedHashMap<>();
        Target target=resolve(player,pos);
        if(target==null) {
            out.put("kind","not_a_storage_terminal");
            return out;
        }
        out.put("kind","toms_storage_terminal");
        out.put("terminal_stacks_total",count(target));
        // 邻域连接器诊断(只读反射;R3D/I08 网络不组链排查)
        for(net.minecraft.util.math.Direction d:
                net.minecraft.util.math.Direction.values()) {
            BlockPos np=pos.offset(d);
            var be=player.getServerWorld().getBlockEntity(np);
            if(be==null)continue;
            if(!BLOCK_ENTITY_CONNECTOR_CLASS.equals(
                    be.getClass().getName()))continue;
            java.util.Map<String,Object> cd=
                    new java.util.LinkedHashMap<>();
            try {
                cd.put("has_connected_inventories",be.getClass()
                        .getMethod("hasConnectedInventories")
                        .invoke(be));
                Object blocks=be.getClass()
                        .getMethod("getConnectedBlocks").invoke(be);
                cd.put("connected_blocks",String.valueOf(blocks));
                Object invs=be.getClass()
                        .getMethod("getConnectedInventories")
                        .invoke(be);
                cd.put("connected_inventories_size",
                        invs instanceof java.util.Collection<?> c
                                ?c.size():-1);
                Object conn=be.getClass()
                        .getMethod("getConnectedConnectors").invoke(be);
                cd.put("connected_connectors_size",
                        conn instanceof java.util.Collection<?> c2
                                ?c2.size():-1);
            } catch(ReflectiveOperationException
                    |RuntimeException failure) {
                cd.put("reflect_error",failure.toString());
            }
            out.put("connector_at_"+np.getX()+","+np.getY()
                    +","+np.getZ(),cd);
        }
        return out;
    }

    static long count(Target target) {
        try {
            Method getStacks=target.blockEntity().getClass()
                    .getMethod("getStacks");
            Object value=getStacks.invoke(target.blockEntity());
            if(!(value instanceof Map<?,?> map))
                throw new ReflectiveOperationException("getStacks_not_map");
            long total=0L;
            int seen=0;
            for(Object entry:map.values()) {
                if(entry==null
                        ||!STORED_ITEM_CLASS.equals(entry.getClass().getName()))
                    continue;
                Method getQuantity=entry.getClass().getMethod("getQuantity");
                Object quantityValue=getQuantity.invoke(entry);
                if(!(quantityValue instanceof Number quantity))
                    continue;
                long count=quantity.longValue();
                if(count>0)total=Math.addExact(total,count);
                if(++seen>4096)
                    throw new ReflectiveOperationException(
                            "toms_storage_network_entry_bound_exceeded");
            }
            return total;
        } catch(ReflectiveOperationException | RuntimeException failure) {
            throw new BridgeFault(
                    503,"real_client_toms_storage_reflection_failed:"
                            +failure.getClass().getSimpleName());
        }
    }
}
