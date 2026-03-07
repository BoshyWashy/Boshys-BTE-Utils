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

import java.lang.reflect.Field;
import java.util.Set;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = false)
    private void onSendPacket(net.minecraft.network.packet.Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof CommandExecutionC2SPacket commandPacket) {
            String command = commandPacket.command();
            if (BoshysBTEUtils.INSTANCE != null) {
                BoshysBTEUtils.INSTANCE.onCommandSent(command);
            }
        }
    }

    @Inject(method = "onPlayerPositionLook", at = @At("TAIL"))
    private void onPlayerPositionLook(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || BoshysBTEUtils.INSTANCE == null) return;

        if (BoshysBTEUtils.INSTANCE.getKmlImportHandler().isImporting()) {
            try {
                Class<?> packetClass = packet.getClass();

                // Access private fields using reflection (1.21.10 compatible)
                Field xField = packetClass.getDeclaredField("x");
                Field yField = packetClass.getDeclaredField("y");
                Field zField = packetClass.getDeclaredField("z");
                Field relativeArgsField = packetClass.getDeclaredField("relativeArguments");

                xField.setAccessible(true);
                yField.setAccessible(true);
                zField.setAccessible(true);
                relativeArgsField.setAccessible(true);

                double changeX = (double) xField.get(packet);
                double changeY = (double) yField.get(packet);
                double changeZ = (double) zField.get(packet);
                Set<?> relativeArgs = (Set<?>) relativeArgsField.get(packet);

                // Check which coordinates are relative by examining the PositionFlag enum values
                boolean relativeX = false;
                boolean relativeY = false;
                boolean relativeZ = false;

                for (Object flag : relativeArgs) {
                    String flagName = flag.toString();
                    // PositionFlag enum values are X, Y, Z, Y_ROT, X_ROT
                    if (flagName.equals("X")) relativeX = true;
                    if (flagName.equals("Y")) relativeY = true;
                    if (flagName.equals("Z")) relativeZ = true;
                }

                double oldX = client.player.getX();
                double oldY = client.player.getY();
                double oldZ = client.player.getZ();

                double newX = relativeX ? oldX + changeX : changeX;
                double newY = relativeY ? oldY + changeY : changeY;
                double newZ = relativeZ ? oldZ + changeZ : changeZ;

                BoshysBTEUtils.INSTANCE.onPlayerTeleported(client, oldX, oldY, oldZ, newX, newY, newZ);
            } catch (Exception e) {
                // Reflection failed - KML will use 1-tick timeout fallback
            }
        }
    }
}