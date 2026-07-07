package me.cortex.nvidium.mixin.minecraft;

import me.cortex.nvidium.Nvidium;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    //1.20.1 Yarn has no friendly name for this getter (it is getFarPlaneDistance only from 1.21);
    //target the stable intermediary name so the inject resolves on 1.20.1.
    @Inject(method = "method_32796", at = @At("HEAD"), cancellable = true)
    public void method_32796(CallbackInfoReturnable<Float> cir) {
        if (Nvidium.IS_ENABLED) {
            cir.setReturnValue(16 * 512f);
            cir.cancel();
        }
    }
}