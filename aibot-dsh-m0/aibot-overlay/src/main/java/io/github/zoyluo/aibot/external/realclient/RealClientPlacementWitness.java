package io.github.zoyluo.aibot.external.realclient;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * MC-RCF-1-R2 R07: server-side witness of accepted block interactions.
 *
 * <p>Fabric's {@code UseBlockCallback} fires on the SERVER thread inside the
 * vanilla interaction handler (the same path a real player's click takes).
 * Each server-side record binds (player uuid, tick, clicked cell, clicked
 * face). A block placement into cell C is attributable to player P only when
 * a recorded interaction of P clicked an adjacent cell on the face pointing
 * at C — block-type plus inventory decrease alone cannot prove authorship
 * (another actor may place the same block type while P's items drop for an
 * unrelated reason; that combination must NOT verify).</p>
 */
public final class RealClientPlacementWitness {
    private RealClientPlacementWitness() {}

    /** Server keeps at most this many recent interactions. */
    static final int CAPACITY=512;

    public record Interaction(
            String playerUuid,long tick,BlockPos clicked,Direction face) {}

    private static final Deque<Interaction> RECENT=new ArrayDeque<>();
    private static boolean registered;

    /** Idempotent; call once on the server thread at backend start. */
    public static synchronized void register() {
        if(registered)return;
        registered=true;
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT
                .register((player,world,hand,hitResult)->{
                    if(world.isClient
                            ||hand!=Hand.MAIN_HAND
                            ||!(player instanceof ServerPlayerEntity server)
                            ||!(hitResult instanceof BlockHitResult block))
                        return ActionResult.PASS;
                    record(new Interaction(
                            server.getUuidAsString(),
                            server.getServer().getTicks(),
                            block.getBlockPos().toImmutable(),
                            block.getSide()));
                    return ActionResult.PASS;
                });
    }

    static synchronized void record(Interaction interaction) {
        RECENT.addLast(interaction);
        while(RECENT.size()>CAPACITY)
            RECENT.pollFirst();
    }

    /**
     * True when the player clicked an adjacent cell on the face whose offset
     * is the target, within the execution tick window — i.e. the placement
     * of {@code target} can be attributed to this player's own interaction.
     */
    public static synchronized boolean attributesPlacementTo(
            String playerUuid,BlockPos target,long sinceTick,long nowTick) {
        for(Interaction interaction:RECENT) {
            if(interaction.tick()<sinceTick||interaction.tick()>nowTick)
                continue;
            if(!interaction.playerUuid().equals(playerUuid))
                continue;
            if(interaction.clicked().offset(interaction.face()).equals(target))
                return true;
        }
        return false;
    }

    /** Test/inspection hook. */
    public static synchronized int size() {
        return RECENT.size();
    }

    static synchronized void clear() {
        RECENT.clear();
    }
}
