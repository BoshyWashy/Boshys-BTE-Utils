package com.boshys.bteutils.mixin;

import com.boshys.bteutils.BoshysBTEUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Intercepts ALL outbound packets at the Connection level.
 * This is the most reliable method for catching command packets in 26.2.
 */
@Mixin(Connection.class)
public class ClientConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            cancellable = false)
    private void onSendPacket(Packet<?> packet, PacketSendListener callbacks, CallbackInfo ci) {
        if (BoshysBTEUtils.INSTANCE == null) return;
        if (BoshysBTEUtils.keybindCommandBeingSent) return;

        // Check if this is a command packet
        if (packet instanceof ServerboundChatCommandPacket commandPacket) {
            String command = commandPacket.command();
            System.out.println("[Boshys-bt-utils] Connection intercepted command packet: " + command);
            BoshysBTEUtils.INSTANCE.onCommandSent(command);
        }
    }
}