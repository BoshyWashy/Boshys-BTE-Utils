package com.boshys.bteutils.mixin;

import com.boshys.bteutils.BoshysBTEUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Intercepts ALL outbound packets at the ClientConnection level.
 * This is the most reliable method for catching command packets in 1.21.10.
 */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;)V",
            at = @At("HEAD"),
            cancellable = false)
    private void onSendPacket(Packet<?> packet, PacketCallbacks callbacks, CallbackInfo ci) {
        if (BoshysBTEUtils.INSTANCE == null) return;
        if (BoshysBTEUtils.keybindCommandBeingSent) return;

        // Check if this is a command packet
        if (packet instanceof CommandExecutionC2SPacket commandPacket) {
            String command = commandPacket.command();
            System.out.println("[Boshys-bt-utils] ClientConnection intercepted command packet: " + command);
            BoshysBTEUtils.INSTANCE.onCommandSent(command);
        }
    }
}