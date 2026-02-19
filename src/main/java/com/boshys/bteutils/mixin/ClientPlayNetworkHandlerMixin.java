package com.boshys.bteutils.mixin;

import com.boshys.bteutils.BoshysBTEUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    // Track when player sends a command packet
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = false)
    private void onSendPacket(net.minecraft.network.packet.Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof CommandExecutionC2SPacket commandPacket) {
            String command = commandPacket.command();
            if (BoshysBTEUtils.INSTANCE != null) {
                BoshysBTEUtils.INSTANCE.onCommandSent(command);
            }
        }
    }
}
