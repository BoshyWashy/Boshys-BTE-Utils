package com.boshys.bteutils.mixin;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.message.MessageHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts ALL game/system messages at the MessageHandler level.
 * This catches messages regardless of which packet type delivered them.
 * More reliable than packet-level interception for 1.21.10.
 */
@Mixin(MessageHandler.class)
public class MessageHandlerMixin {

    @Inject(
            method = "onGameMessage",
            at = @At("HEAD"),
            cancellable = false
    )
    private void onGameMessage(Text message, boolean overlay, CallbackInfo ci) {
        if (BoshysBTEUtils.INSTANCE == null) return;
        if (!BoshysBTEUtils.getConfig().enableMarkers) return;

        BoshysBTEUtilsConfig.TpllMarkerMode mode = BoshysBTEUtils.getConfig().tpllMarkerMode;
        // Only trigger for manual modes (MANUAL_ONLY or KEYBIND_AND_MANUAL)
        if (mode == BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED ||
                mode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY) {
            return;
        }

        if (message == null) return;

        String msgString = message.getString();
        if (msgString == null || msgString.isEmpty()) return;

        // Check if this is a teleport message - be very lenient with matching
        if (msgString.contains("Teleported to")) {
            System.out.println("[Boshys-bt-utils] MessageHandler detected teleport message: " + msgString);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                // Place marker at current position (where player teleported to)
                double x = client.player.getX();
                double y = client.player.getY();
                double z = client.player.getZ();

                System.out.println("[Boshys-bt-utils] Placing marker from message detection at: " + x + ", " + y + ", " + z);

                com.boshys.bteutils.data.MarkerData.TeleportMarker newMarker =
                        com.boshys.bteutils.data.MarkerData.addMarker(
                                new net.minecraft.util.math.Vec3d(x, y, z)
                        );

                if (BoshysBTEUtils.getConfig().enableAutoLineConnection) {
                    com.boshys.bteutils.data.MarkerData.handleAutoConnect(newMarker);
                }

                // Notify player via action bar
                client.player.sendMessage(
                        net.minecraft.text.Text.literal("\u00a7a[Boshys BTE Utils] Marker placed from teleport detection!"),
                        true
                );
            }
        }
    }
}