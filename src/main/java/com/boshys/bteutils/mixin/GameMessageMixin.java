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
 * This is a fallback for when command packet interception fails.
 * Works by detecting "Teleported to ..." messages from the server.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class GameMessageMixin {

    private static final java.util.regex.Pattern TPLL_PATTERN = java.util.regex.Pattern.compile(
            "Teleported to.*"
    );

    @Inject(method = "onGameMessage", at = @At("HEAD"), cancellable = false)
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        if (BoshysBTEUtils.INSTANCE == null) return;
        if (!BoshysBTEUtils.getConfig().enableMarkers) return;

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
                // Place marker at current position (where player teleported to)
                double x = client.player.getX();
                double y = client.player.getY();
                double z = client.player.getZ();

                System.out.println("[Boshys-bt-utils] Placing marker from chat detection at: " + x + ", " + y + ", " + z);

                com.boshys.bteutils.data.MarkerData.TeleportMarker newMarker =
                        com.boshys.bteutils.data.MarkerData.addMarker(
                                new net.minecraft.util.math.Vec3d(x, y, z)
                        );

                if (BoshysBTEUtils.getConfig().enableAutoLineConnection) {
                    com.boshys.bteutils.data.MarkerData.handleAutoConnect(newMarker);
                }

                // Also notify the player
                client.player.sendMessage(
                        net.minecraft.text.Text.literal("\u00a7a[Boshys BTE Utils] Marker placed from teleport detection!"),
                        true
                );
            }
        }
    }
}