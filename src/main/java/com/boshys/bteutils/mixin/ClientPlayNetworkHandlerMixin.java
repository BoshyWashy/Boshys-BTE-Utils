package com.boshys.bteutils.mixin;

import com.boshys.bteutils.BoshysBTEUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Set;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {

    // Prevents double-processing when sendChatCommand calls sendPacket internally
    private static boolean processingCommand = false;

    /**
     * Intercept commands sent via sendChatCommand BEFORE they are packetized.
     * This is the primary interception point for manual /tpll commands.
     * In 26.2, this is more reliable than packet-level interception.
     */
    @Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = false)
    private void onSendChatCommand(String command, CallbackInfo ci) {
        if (processingCommand) return;
        // Skip if this command was sent by the keybind (keybind handles its own detection)
        if (BoshysBTEUtils.keybindCommandBeingSent) return;
        if (BoshysBTEUtils.INSTANCE != null) {
            processingCommand = true;
            try {
                BoshysBTEUtils.INSTANCE.onCommandSent(command);
            } finally {
                processingCommand = false;
            }
        }
    }

    /**
     * Fallback packet-level interception for commands sent directly as packets.
     * The sendChatCommand injection above handles most cases; this catches edge cases.
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = false)
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (processingCommand) return;
        if (BoshysBTEUtils.keybindCommandBeingSent) return;
        if (packet instanceof ServerboundChatCommandPacket commandPacket) {
            String command = commandPacket.command();
            if (BoshysBTEUtils.INSTANCE != null) {
                processingCommand = true;
                try {
                    BoshysBTEUtils.INSTANCE.onCommandSent(command);
                } finally {
                    processingCommand = false;
                }
            }
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void onPlayerPositionLook(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || BoshysBTEUtils.INSTANCE == null) return;

        if (BoshysBTEUtils.INSTANCE.getKmlImportHandler().isImporting()) {
            try {
                Class<?> packetClass = packet.getClass();

                // Access private fields using reflection (26.2 compatible)
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
                // Reflection failed - KML will use timeout fallback
            }
        }
    }
}