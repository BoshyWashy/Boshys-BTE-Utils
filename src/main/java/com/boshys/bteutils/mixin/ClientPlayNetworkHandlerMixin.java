/* package com.boshys.bteutils.mixin;

import com.boshys.bteutils.BoshysBTEUtils;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    // Try injecting at HEAD instead of TAIL, and also log if it's being called
    @Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
    private void onPlayerPositionLook(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        System.out.println("[BoshysBTEUtils] onPlayerPositionLook mixin triggered!");

        try {
            var change = packet.change();
            var pos = change.position();
            double x = pos.x;
            double y = pos.y;
            double z = pos.z;

            System.out.println("[BoshysBTEUtils] Teleport detected to: " + x + ", " + y + ", " + z);
            BoshysBTEUtils.onPlayerTeleported(x, y, z);
        } catch (Exception e) {
            System.out.println("[BoshysBTEUtils] Error in mixin: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
*/