package io.github.jopojp.overseer.forge.mixin.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.storage.NetworkStorage;
import io.github.jopojp.overseer.forge.integration.ae2.FlowRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every item that enters or leaves an AE2 network passes its {@code NetworkStorage}, together with
 * who asked (a machine or a player). AE2 counts none of it; this reports applied operations to
 * {@link FlowRecorder} (ADR-0020). Only with AE2 installed ({@code Ae2MixinPlugin}); does nothing
 * if the methods are missing in another AE2 build (ADR-0018).
 */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkStorageMixin {
    @Inject(method = "insert", at = @At("RETURN"), require = 0)
    private void overseer$inserted(AEKey what, long amount, Actionable mode, IActionSource source,
            CallbackInfoReturnable<Long> result) {
        if (mode == Actionable.MODULATE) FlowRecorder.record(this, what, result.getReturnValueJ(), source, true);
    }

    @Inject(method = "extract", at = @At("RETURN"), require = 0)
    private void overseer$extracted(AEKey what, long amount, Actionable mode, IActionSource source,
            CallbackInfoReturnable<Long> result) {
        if (mode == Actionable.MODULATE) FlowRecorder.record(this, what, result.getReturnValueJ(), source, false);
    }
}
