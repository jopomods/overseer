package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import com.mojang.authlib.GameProfile;
import io.github.jopojp.overseer.core.action.Actor;
import io.github.jopojp.overseer.core.storage.WatchStore.Restock;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Model.RestockState;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Writer.Context;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Writer.Refused;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

/**
 * Auto-crafting to a watched item's target (ADR-0021): when the stock of an item falls below its
 * target and the network is not already crafting it, plans the difference and starts it, as the
 * player who switched it on. Checks every {@link #EVERY} collections; waits after a failed attempt.
 * Server thread only.
 *
 * <p>Every check goes through {@link Ae2Writer#grid}, so a player who may no longer act on the
 * network (left the party, lost operator) stops crafting at once.
 */
final class Ae2Restocker {
    /** Collections between checks: every 5 s. */
    static final int EVERY = 5;
    /** Wait after a plan or start that failed (items missing, no CPU) before trying again. */
    static final long RETRY_NANOS = 60L * 1_000_000_000L;
    /** A plan AE2 has not finished by then is abandoned. */
    static final long CALCULATION_NANOS = 30L * 1_000_000_000L;

    /** What an item with auto-crafting is doing, as the dashboard shows it. */
    enum State {
        /** Stock at or above the target. */
        OK,
        /** AE2 is planning the job. */
        PLANNING,
        /** A job for this item runs (ours or another one). */
        CRAFTING,
        /** Ingredients are missing; tried again after a minute. */
        MISSING,
        /** No pattern makes it. */
        NO_PATTERN,
        /** No free CPU is big enough or accepts it; tried again after a minute. */
        NO_CPU,
        /** The player who switched it on may no longer act on the network. */
        DENIED,
        /** The network is not loaded or AE2 failed; tried again after a minute. */
        UNAVAILABLE
    }

    private record Slot(String grid, String key) {}

    private static final class Entry {
        State state;
        Instant since;
        long retryAt;
        Future<ICraftingPlan> plan;
        long planStarted;
        ICraftingLink link;
        IGrid grid;
        Actor actor;
    }

    private final Ae2Reader.StackCache keys;
    private final LongSupplier nanoTime;
    private final Map<Slot, Entry> entries = new HashMap<>();
    private long calls;

    Ae2Restocker(Ae2Reader.StackCache keys, LongSupplier nanoTime) {
        this.keys = keys;
        this.nanoTime = nanoTime;
    }

    /**
     * Hands back finished plans every call and checks the stock every {@link #EVERY} calls.
     *
     * @param targets items to keep in stock per grid id, then stack key; empty when switched off
     */
    void tick(Context ctx, Map<String, Map<String, Restock>> targets, Instant now) {
        long nanos = nanoTime.getAsLong();
        entries.entrySet().removeIf(e -> {
            var forGrid = targets.get(e.getKey().grid());
            boolean gone = forGrid == null || !forGrid.containsKey(e.getKey().key());
            if (gone && e.getValue().plan != null) e.getValue().plan.cancel(true);
            return gone;
        });
        for (var e : entries.entrySet()) {
            if (e.getValue().plan != null) finishPlan(e.getValue(), nanos, now);
        }
        if (calls++ % EVERY != 0) return;
        targets.forEach((grid, items) -> items.forEach((key, restock) -> {
            Entry entry = entries.computeIfAbsent(new Slot(grid, key), s -> new Entry());
            if (entry.plan != null || nanos < entry.retryAt) return;
            check(ctx, grid, key, restock, entry, nanos, now);
        }));
    }

    /** The state of every item with auto-crafting per grid id, for the grid's live table. */
    Map<String, List<RestockState>> states() {
        var result = new HashMap<String, List<RestockState>>();
        entries.forEach((slot, entry) -> {
            if (entry.state != null) {
                result.computeIfAbsent(slot.grid(), g -> new ArrayList<>())
                        .add(new RestockState(slot.key(), entry.state.name().toLowerCase(java.util.Locale.ROOT), entry.since));
            }
        });
        return result;
    }

    private void check(Context ctx, String gridId, String keyId, Restock restock, Entry entry, long nanos, Instant now) {
        try {
            Actor actor = actor(ctx, restock.by());
            IGrid grid = Ae2Writer.grid(ctx, actor, gridId);
            if (entry.link != null && !entry.link.isDone() && !entry.link.isCanceled()) {
                set(entry, State.CRAFTING, now);
                return;
            }
            entry.link = null;
            AEKey key = Ae2Writer.key(keys, keyId);
            long stock = grid.getStorageService().getCachedInventory().get(key);
            var crafting = grid.getCraftingService();
            if (stock >= restock.target()) {
                set(entry, State.OK, now);
            } else if (crafting.isRequesting(key)) {
                set(entry, State.CRAFTING, now);
            } else if (!crafting.isCraftable(key)) {
                set(entry, State.NO_PATTERN, now);
            } else {
                IGridNode pivot = grid.getPivot();
                if (pivot == null || pivot.getLevel() == null) throw new Refused(
                        io.github.jopojp.overseer.core.action.ActionResult.Status.UNAVAILABLE, "not loaded");
                IActionSource source = Ae2Crafting.source(actor, pivot.getLevel());
                // AE2 looks up patterns through the requester's node: without one nothing is craftable.
                var requester = new ICraftingSimulationRequester() {
                    @Override
                    public IActionSource getActionSource() {
                        return source;
                    }

                    @Override
                    public IGridNode getGridNode() {
                        return pivot;
                    }
                };
                long amount = Math.min(restock.target() - stock, Ae2Actions.MAX_CRAFT);
                entry.plan = crafting.beginCraftingCalculation(pivot.getLevel(), requester, key, amount,
                        CalculationStrategy.REPORT_MISSING_ITEMS);
                entry.planStarted = nanos;
                entry.grid = grid;
                entry.actor = actor;
                set(entry, State.PLANNING, now);
            }
        } catch (Refused r) {
            set(entry, switch (r.result.status()) {
                case DENIED -> State.DENIED;
                case INVALID -> State.NO_PATTERN; // an item the server does not know (any more)
                default -> State.UNAVAILABLE;
            }, now);
            entry.retryAt = nanos + RETRY_NANOS;
        } catch (RuntimeException e) {
            set(entry, State.UNAVAILABLE, now);
            entry.retryAt = nanos + RETRY_NANOS;
        }
    }

    private void finishPlan(Entry entry, long nanos, Instant now) {
        if (!entry.plan.isDone()) {
            if (nanos - entry.planStarted > CALCULATION_NANOS) {
                entry.plan.cancel(true);
                fail(entry, State.UNAVAILABLE, nanos, now);
            }
            return;
        }
        ICraftingPlan plan;
        try {
            plan = entry.plan.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(entry, State.UNAVAILABLE, nanos, now);
            return;
        } catch (Exception e) {
            fail(entry, State.UNAVAILABLE, nanos, now);
            return;
        }
        if (plan == null) {
            fail(entry, State.UNAVAILABLE, nanos, now);
        } else if (plan.simulation()) {
            fail(entry, State.MISSING, nanos, now);
        } else {
            IGridNode pivot = entry.grid.getPivot();
            var source = pivot == null || pivot.getLevel() == null ? IActionSource.empty()
                    : Ae2Crafting.source(entry.actor, pivot.getLevel());
            var result = entry.grid.getCraftingService().submitJob(plan, null, null, false, source);
            if (result.successful()) {
                entry.plan = null;
                entry.link = result.link();
                set(entry, State.CRAFTING, now);
            } else {
                fail(entry, result.errorCode() == CraftingSubmitErrorCode.INCOMPLETE_PLAN
                        || result.errorCode() == CraftingSubmitErrorCode.MISSING_INGREDIENT ? State.MISSING : State.NO_CPU,
                        nanos, now);
            }
        }
        entry.grid = null;
        entry.actor = null;
    }

    private void fail(Entry entry, State state, long nanos, Instant now) {
        entry.plan = null;
        entry.grid = null;
        entry.actor = null;
        set(entry, state, now);
        entry.retryAt = nanos + RETRY_NANOS;
    }

    private static void set(Entry entry, State state, Instant now) {
        if (entry.state != state) {
            entry.state = state;
            entry.since = now;
        }
    }

    /** The player who switched it on, with operator rights checked live. */
    private static Actor actor(Context ctx, UUID player) {
        var server = ctx.server();
        String name = server.getProfileCache() == null ? null
                : server.getProfileCache().get(player).map(GameProfile::getName).orElse(null);
        boolean operator = server.getPlayerList().isOp(new GameProfile(player, name == null ? "" : name));
        return new Actor(player, name == null ? "Overseer" : name, operator);
    }
}
