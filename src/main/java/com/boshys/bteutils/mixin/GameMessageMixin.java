package com.boshys.bteutils.mixin;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts incoming system/game messages to detect TPLL completion.
 * When "Teleported to" is seen in chat, triggers the same movement-based
 * teleport detection that the keybind and console detector use.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class GameMessageMixin {

    @Inject(method = "onGameMessage", at = @At("HEAD"), cancellable = false)
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        if (BoshysBTEUtils.INSTANCE == null) return;
        if (!BoshysBTEUtils.getConfig().enableMarkers) return;
        if (BoshysBTEUtils.markersHidden) return;

        BoshysBTEUtilsConfig.TpllMarkerMode mode = BoshysBTEUtils.getConfig().tpllMarkerMode;
        // Only trigger for manual modes (MANUAL_ONLY or KEYBIND_AND_MANUAL)
        if (mode == BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED || mode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY) {
            return;
        }

        Text text = packet.content();
        if (text == null) return;

        String message = text.getString();
        if (message == null || message.isEmpty()) return;

        // Check if this is a teleport message
        if (message.contains("Teleported to")) {
            System.out.println("[Boshys-bt-utils] GameMessage detected teleport message: " + message);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                // Trigger movement-based detection (same as keybind/console)
                BoshysBTEUtils.INSTANCE.triggerConsoleTeleportDetection(client);
            }
        }
    }
}