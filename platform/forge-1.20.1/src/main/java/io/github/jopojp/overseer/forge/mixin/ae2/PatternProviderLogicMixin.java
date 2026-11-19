package io.github.jopojp.overseer.forge.mixin.ae2;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import io.github.jopojp.overseer.forge.integration.ae2.ProviderIcons;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AE2 picks the icon of a pattern provider in its Pattern Access Terminal itself (the provider
 * item, or the machine next to it) and has no setting for it. This swaps in the icon chosen from
 * the dashboard, keeping AE2's name and tooltip (ADR-0018). The group is built on the server and
 * sent to clients, so players need no Overseer on the client. Applied only when AE2 is installed
 * ({@code Ae2MixinPlugin}); if the method is missing in another AE2 build, nothing is changed.
 */
@Mixin(value = PatternProviderLogic.class, remap = false)
public abstract class PatternProviderLogicMixin {
    @Shadow
    @Final
    private PatternProviderLogicHost host;

    @Inject(method = "getTerminalGroup", at = @At("RETURN"), cancellable = true, require = 0)
    private void overseer$chosenIcon(CallbackInfoReturnable<PatternContainerGroup> result) {
        AEItemKey icon = ProviderIcons.icon(host);
        PatternContainerGroup group = result.getReturnValue();
        if (icon == null || group == null || icon.equals(group.icon())) return;
        result.setReturnValue(new PatternContainerGroup(icon, group.name(), group.tooltip()));
    }
}
