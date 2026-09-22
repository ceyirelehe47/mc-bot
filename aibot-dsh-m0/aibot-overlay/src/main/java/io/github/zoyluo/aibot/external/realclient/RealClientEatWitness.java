package io.github.zoyluo.aibot.external.realclient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * MC-RCF-1-R3 F04: server-side read-only witness of ACTUAL normal food
 * consumption completion processing.
 *
 * <p>The witness is fed exclusively by
 * {@code ConsumableComponentEatWitnessMixin}, which observes the game's own
 * {@code ConsumableComponent#finishConsumption} processing on the server
 * thread. External inventory mutation (clear, drop, container moves) never
 * runs that processing, so it can never fabricate a witness entry. The hook
 * only observes; it never decrements stacks, restores hunger or produces
 * effects itself.</p>
 *
 * <p>Each entry binds: player identity (UUID), item id, consumed count
 * (1 per completion call — the game consumes exactly one stack unit per
 * finished use), active hand, world-scoped game time and wall-clock
 * milliseconds. {@link RealClientExecutionDriver#eatSnapshot} correlates
 * entries with the current execution by player + item + time window; the
 * historical defect (client claimed count == server-side inventory
 * subtraction on the same decrease) is closed because completion now
 * requires a matching count of real completion events.</p>
 *
 * <p>Bounded ring: at most {@link #MAX_ENTRIES} entries are retained; the
 * oldest are dropped. This is a runtime correlation aid, not a durable
 * ledger — durable receipts remain the bridge journal's job.</p>
 */
public final class RealClientEatWitness {
    private RealClientEatWitness() {}

    public static final int MAX_ENTRIES=64;

    /** One actual normal consumption completion observed by the game hook. */
    public record Completion(
            String playerUuid,
            String itemId,
            int count,
            String hand,
            String worldId,
            long gameTime,
            long wallMs) {}

    private static final ArrayDeque<Completion> RING=new ArrayDeque<>();

    /** Called only from the mixin observing real game processing. */
    public static synchronized void record(
            String playerUuid,String itemId,String hand,
            String worldId,long gameTime) {
        if(playerUuid==null||playerUuid.isBlank()||itemId==null||itemId.isBlank())
            return;
        RING.addLast(new Completion(
                playerUuid,itemId,1,hand==null?"unknown":hand,
                worldId==null?"":worldId,gameTime,System.currentTimeMillis()));
        while(RING.size()>MAX_ENTRIES)
            RING.removeFirst();
    }

    /** Count of real completions for (player,item) at/after sinceWallMs. */
    public static synchronized int countFor(
            String playerUuid,String itemId,long sinceWallMs) {
        int n=0;
        for(Completion c:RING)
            if(c.playerUuid().equalsIgnoreCase(playerUuid)
                    &&c.itemId().equals(itemId)
                    &&c.wallMs()>=sinceWallMs)
                n+=c.count();
        return n;
    }

    /** Bounded snapshot for evidence capture (newest last). */
    public static synchronized List<Completion> snapshot() {
        return new ArrayList<>(RING);
    }
}
