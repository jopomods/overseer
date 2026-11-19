package io.github.jopojp.overseer.forge.integration.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.jopojp.overseer.core.action.ActionResult;
import io.github.jopojp.overseer.core.action.ActionResult.Change;
import io.github.jopojp.overseer.core.action.ActionResult.Status;
import io.github.jopojp.overseer.core.action.Actor;
import io.github.jopojp.overseer.core.provider.Stack;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions.CraftPlan;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions.CraftStart;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Writer.Context;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Writer.Refused;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Remote crafting (ADR-0017, ADR-0019): plans a job like AE2's craft confirm screen, keeps the plan
 * for a few minutes, starts it on request. AE2 computes plans on a thread of its own; this class
 * hands them back on the server thread in {@link #tick()} (every collection). Server thread only.
 *
 * <p>Jobs are requested as the player (online, or a stand-in with their profile while offline), so
 * CPUs reserved for players take them and AE2 records who asked. Results go into the network, as
 * from a terminal; nothing is ever moved into a player's inventory.
 */
final class Ae2Crafting {
    /** How long a plan may be started after it was computed. */
    static final long KEEP_NANOS = 5L * 60 * 1_000_000_000L;
    /** A plan AE2 has not finished by then is abandoned (well below the caller's wait limit). */
    static final long CALCULATION_NANOS = 30L * 1_000_000_000L;
    /** Plans kept at once; the oldest goes first. */
    static final int MAX_PLANS = 64;
    /** Lines per list in a plan answer; huge plans are cut (most first). */
    static final int MAX_LINES = 300;

    private record Pending(String gridId, UUID player, long startedAt, Future<ICraftingPlan> future,
            CompletableFuture<ActionResult> answer, IGrid grid) {}

    private record Kept(String gridId, UUID player, long createdAt, ICraftingPlan plan) {}

    private final Ae2Reader.StackCache keys;
    private final LongSupplier nanoTime;
    private final List<Pending> pending = new ArrayList<>();
    private final Map<String, Kept> kept = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();

    Ae2Crafting(Ae2Reader.StackCache keys, LongSupplier nanoTime) {
        this.keys = keys;
        this.nanoTime = nanoTime;
    }

    /** Starts AE2's calculation; the answer comes with a later {@link #tick()}. */
    CompletionStage<ActionResult> plan(Context ctx, Actor actor, String gridId, CraftPlan request) {
        try {
            IGrid grid = Ae2Writer.grid(ctx, actor, gridId);
            AEKey key = key(request.key());
            if (!grid.getCraftingService().isCraftable(key)) {
                throw new Refused(Status.INVALID, "No pattern in this network makes " + name(key) + ".");
            }
            IGridNode pivot = grid.getPivot();
            if (pivot == null || pivot.getLevel() == null) throw new Refused(Status.UNAVAILABLE, "The network is not loaded.");
            Level level = pivot.getLevel();
            IActionSource source = source(actor, level);
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
            Future<ICraftingPlan> future = grid.getCraftingService().beginCraftingCalculation(level, requester, key,
                    request.amount(), CalculationStrategy.REPORT_MISSING_ITEMS);
            var answer = new CompletableFuture<ActionResult>();
            pending.add(new Pending(gridId, actor.player(), nanoTime.getAsLong(), future, answer, grid));
            return answer;
        } catch (Refused r) {
            return CompletableFuture.completedFuture(r.result);
        }
    }

    /** Hands finished plans back and forgets old ones. Server thread, every collection. */
    void tick() {
        long now = nanoTime.getAsLong();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.future().isDone()) {
                it.remove();
                p.answer().complete(finish(p, now));
            } else if (now - p.startedAt() > CALCULATION_NANOS) {
                it.remove();
                p.future().cancel(true);
                p.answer().complete(ActionResult.error(Status.FAILED, "AE2 took too long to plan this job."));
            }
        }
        kept.values().removeIf(k -> now - k.createdAt() > KEEP_NANOS);
    }

    private ActionResult finish(Pending p, long now) {
        ICraftingPlan plan;
        try {
            plan = p.future().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.error(Status.FAILED, "Interrupted while planning.");
        } catch (ExecutionException | RuntimeException e) {
            return ActionResult.error(Status.FAILED, "AE2 could not plan this job.");
        }
        if (plan == null) return ActionResult.error(Status.FAILED, "AE2 could not plan this job.");
        String id = newId();
        while (kept.size() >= MAX_PLANS) kept.remove(kept.keySet().iterator().next());
        kept.put(id, new Kept(p.gridId(), p.player(), now, plan));
        return ActionResult.ok(List.of(), describe(id, plan, p.grid(), p.gridId()));
    }

    /** Starts a kept plan as the player who planned it (operators may start any). */
    ActionResult start(Context ctx, Actor actor, String gridId, CraftStart request) {
        return Ae2Writer.run(() -> {
            Kept plan = kept.get(request.plan());
            if (plan == null || !plan.gridId().equals(gridId)) {
                throw new Refused(Status.INVALID, "This plan expired. Plan the job again.");
            }
            if (!plan.player().equals(actor.player()) && !actor.operator()) {
                throw new Refused(Status.DENIED, "Only the player who planned the job may start it.");
            }
            IGrid grid = Ae2Writer.grid(ctx, actor, gridId);
            ICraftingCPU target = request.cpu() == null ? null : Ae2Writer.cpu(ctx, grid, request.cpu());
            if (plan.plan().simulation()) {
                throw new Refused(Status.INVALID, "Items are missing; the job cannot start.");
            }
            IGridNode pivot = grid.getPivot();
            if (pivot == null || pivot.getLevel() == null) throw new Refused(Status.UNAVAILABLE, "The network is not loaded.");
            Set<ICraftingCPU> busyBefore = new HashSet<>();
            for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) if (cpu.isBusy()) busyBefore.add(cpu);

            var result = grid.getCraftingService().submitJob(plan.plan(), null, target, true,
                    source(actor, pivot.getLevel()));
            if (!result.successful()) throw new Refused(Status.INVALID, message(result.errorCode()));
            kept.remove(request.plan());

            String output = Ae2Reader.StackCache.id(plan.plan().finalOutput().what());
            var changes = new ArrayList<Change>();
            int index = 0;
            for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
                if (cpu.isBusy() && !busyBefore.contains(cpu)) {
                    changes.add(new Change(Ae2Mapping.cpu(Ae2Reader.cpuId(cpu, gridId, index)), "job", "", output));
                }
                index++;
            }
            return ActionResult.ok(changes);
        });
    }

    /** Keys the dashboard knows from scans and patterns; plain items and fluids also by registry id. */
    private AEKey key(String id) {
        AEKey known = keys.byId(id);
        if (known != null) return known;
        int colon = id.indexOf(':');
        String kind = id.substring(0, colon);
        ResourceLocation location = id.contains("#") ? null : ResourceLocation.tryParse(id.substring(colon + 1));
        if (location != null && kind.equals("item")) {
            Item item = ForgeRegistries.ITEMS.getValue(location);
            if (item != null && item != Items.AIR) return AEItemKey.of(item);
        } else if (location != null && kind.equals("fluid")) {
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(location);
            if (fluid != null && fluid != Fluids.EMPTY) return AEFluidKey.of(fluid);
        }
        throw new Refused(Status.INVALID, "Unknown item: " + id);
    }

    /**
     * The player as AE2 sees them: online, or a stand-in with their profile while offline, so
     * player-only CPUs take the job and AE2 knows who asked.
     */
    private static IActionSource source(Actor actor, Level level) {
        if (level instanceof ServerLevel server) {
            ServerPlayer online = server.getServer().getPlayerList().getPlayer(actor.player());
            if (online != null) return IActionSource.ofPlayer(online);
            return IActionSource.ofPlayer(FakePlayerFactory.get(server, new GameProfile(actor.player(), actor.name())));
        }
        return IActionSource.empty();
    }

    /** The plan as the dashboard shows it, like AE2's craft confirm screen. */
    private JsonObject describe(String id, ICraftingPlan plan, IGrid grid, String gridId) {
        var data = new JsonObject();
        data.addProperty("plan", id);
        data.add("output", stack(keys.stack(plan.finalOutput().what(), plan.finalOutput().amount())));
        data.addProperty("bytes", plan.bytes());
        data.addProperty("missing", plan.simulation());
        data.addProperty("multiplePaths", plan.multiplePaths());
        data.addProperty("expiresInSeconds", KEEP_NANOS / 1_000_000_000L);
        data.add("used", lines(plan.usedItems()));
        data.add("missingItems", lines(plan.missingItems()));
        data.add("crafted", lines(plan.emittedItems()));
        var cpus = new JsonArray();
        int index = 0;
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            var c = new JsonObject();
            c.addProperty("id", Ae2Reader.cpuId(cpu, gridId, index));
            c.addProperty("number", index + 1);
            c.addProperty("name", cpu.getName() == null ? null : cpu.getName().getString());
            c.addProperty("storage", cpu.getAvailableStorage());
            c.addProperty("coProcessors", cpu.getCoProcessors());
            c.addProperty("busy", cpu.isBusy());
            c.addProperty("mode", cpu.getSelectionMode().name().toLowerCase(Locale.ROOT));
            c.addProperty("fits", cpu.getAvailableStorage() >= plan.bytes());
            cpus.add(c);
            index++;
        }
        data.add("cpus", cpus);
        return data;
    }

    private JsonArray lines(KeyCounter counter) {
        var entries = new ArrayList<Object2LongMap.Entry<AEKey>>();
        for (Object2LongMap.Entry<AEKey> e : counter) if (e.getLongValue() > 0) entries.add(e);
        entries.sort(Comparator.comparingLong((Object2LongMap.Entry<AEKey> e) -> -e.getLongValue()));
        var array = new JsonArray();
        for (int i = 0; i < entries.size() && i < MAX_LINES; i++) {
            array.add(stack(keys.stack(entries.get(i).getKey(), entries.get(i).getLongValue())));
        }
        return array;
    }

    private static JsonObject stack(Stack s) {
        var o = new JsonObject();
        o.addProperty("key", s.key());
        o.addProperty("kind", s.kind());
        o.addProperty("name", s.name());
        o.addProperty("amount", s.amount());
        return o;
    }

    private String name(AEKey key) {
        return keys.stack(key, 0).name();
    }

    private String newId() {
        String id;
        do {
            id = String.format(Locale.ROOT, "%016x", random.nextLong());
        } while (kept.containsKey(id));
        return id;
    }

    private static String message(appeng.api.networking.crafting.CraftingSubmitErrorCode code) {
        if (code == null) return "AE2 refused the job.";
        return switch (code) {
            case INCOMPLETE_PLAN, MISSING_INGREDIENT -> "Items are missing; the job cannot start. Plan it again.";
            case NO_CPU_FOUND -> "This network has no crafting CPU.";
            case NO_SUITABLE_CPU_FOUND -> "No free CPU is big enough or accepts jobs from players.";
            case CPU_BUSY -> "That CPU is busy.";
            case CPU_OFFLINE -> "That CPU is offline.";
            case CPU_TOO_SMALL -> "That CPU is too small for this job.";
        };
    }
}
