package com.boshys.bteutils.mixin;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts ALL game/system messages at the ChatListener level.
 * When "Teleported to" is seen, triggers the same movement-based
 * teleport detection that the keybind and console detector use.
 */
@Mixin(ChatListener.class)
public class MessageHandlerMixin {

    @Inject(
            method = "handleSystemMessage",
            at = @At("HEAD"),
            cancellable = false
    )
    private void onGameMessage(Component message, boolean overlay, CallbackInfo ci) {
        if (BoshysBTEUtils.INSTANCE == null) return;
        if (!BoshysBTEUtils.getConfig().enableMarkers) return;
        if (BoshysBTEUtils.markersHidden) return;

        BoshysBTEUtilsConfig.TpllMarkerMode mode = BoshysBTEUtils.getConfig().tpllMarkerMode;
        // Only trigger for manual modes (MANUAL_ONLY or KEYBIND_AND_MANUAL)
        if (mode == BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED ||
                mode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY) {
            return;
        }

        if (message == null) return;

        String msgString = message.getString();
        if (msgString == null || msgString.isEmpty()) return;

        // Check if this is a teleport message
        if (msgString.contains("Teleported to")) {
            System.out.println("[Boshys-bt-utils] ChatListener detected teleport message: " + msgString);

            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                // Trigger movement-based detection (same as keybind/console)
                BoshysBTEUtils.INSTANCE.triggerConsoleTeleportDetection(client);
            }
        }
    }
}