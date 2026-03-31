package eu.pb4.softwaregl.mixin;

import com.mojang.blaze3d.platform.Window;
import eu.pb4.softwaregl.SoftwareGL;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;D)D", at = @At("HEAD"), cancellable = true)
    private static void replaceScalingX(Window window, double x, CallbackInfoReturnable<Double> cir) {
        if (window.getWidth() == SoftwareGL.framebufferWidth) return;
        cir.setReturnValue(SoftwareGL.remapMouseX(window, x));
    }

    @Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;D)D", at = @At("HEAD"), cancellable = true)
    private static void replaceScalingY(Window window, double x, CallbackInfoReturnable<Double> cir) {
        if (window.getWidth() == SoftwareGL.framebufferHeight) return;
        cir.setReturnValue(SoftwareGL.remapMouseY(window, x));
    }
}
