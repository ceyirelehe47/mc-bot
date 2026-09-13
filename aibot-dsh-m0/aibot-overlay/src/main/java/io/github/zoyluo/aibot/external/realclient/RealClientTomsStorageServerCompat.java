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
            "com.tom.storagemod.inventory.TerminalItemStack";

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
