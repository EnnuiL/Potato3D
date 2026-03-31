package eu.pb4.softwaregl.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.platform.Window;
import eu.pb4.softwaregl.SoftwareGL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class WindowMixin {
    @Shadow
    private int framebufferWidth;

    @Shadow
    private int framebufferHeight;

    @Inject(method = "refreshFramebufferSize", at = @At("TAIL"))
    private void modifyFramebufferSizePostRefresh(CallbackInfo ci) {
        var out = SoftwareGL.updateRealResolutionAndScaleDown(this.framebufferWidth, this.framebufferHeight);
        this.framebufferWidth = out.x;
        this.framebufferHeight = out.y;
    }

    @Inject(method = "onFramebufferResize", at = @At("HEAD"))
    private void modifyNewFramebufferSize(CallbackInfo ci,
                                          @Local(argsOnly = true, ordinal = 0) LocalIntRef newWidth,
                                          @Local(argsOnly = true, ordinal = 1) LocalIntRef newHeight) {
        var out = SoftwareGL.updateRealResolutionAndScaleDown(newWidth.get(), newHeight.get());
        newWidth.set(out.x);
        newHeight.set(out.y);
    }
}
