package io.github.jopojp.overseer.forge.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.server.timings.TimeTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge 1.20.1 starts its block entity timer ({@code TimeTracker.BLOCK_ENTITY_UPDATE.trackStart})
 * in this method but never ends it, so block entity timings stay empty (also for
 * {@code /forge track start te}). This adds the missing {@code trackEnd} for the object Forge
 * started, so no Minecraft field has to be named (ADR-0014). Costs nothing while no timer runs.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @Unique
    private Object overseer$tracked;

    /** Remembers what Forge starts timing; passes it on unchanged. */
    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/server/timings/TimeTracker;trackStart(Ljava/lang/Object;)V", remap = false))
    private Object overseer$remember(Object tracked) {
        overseer$tracked = tracked;
        return tracked;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void overseer$trackEnd(CallbackInfo ci) {
        Object tracked = overseer$tracked;
        if (tracked == null) return;
        overseer$tracked = null;
        if (tracked instanceof BlockEntity blockEntity) TimeTracker.BLOCK_ENTITY_UPDATE.trackEnd(blockEntity);
    }
}
