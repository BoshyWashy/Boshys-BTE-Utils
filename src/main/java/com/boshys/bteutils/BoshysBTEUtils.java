package com.boshys.bteutils;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;

public class BoshysBTEUtils implements ClientModInitializer {

    private static KeyBinding tpllKeybind;

    @Override
    public void onInitializeClient() {
        tpllKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bteutils.tpll",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "category.bteutils"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (tpllKeybind.wasPressed()) {
                String clip = getClipboard();
                if (clip == null || clip.length() == 0) {
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("[BoshysBTEUtils] Clipboard empty!"), false);
                    }
                    continue;
                }
                sendTpllCommand(client, clip);
            }
        });
    }

    private String getClipboard() {
        try {
            String data = (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            if (data != null) {
                return data.trim();
            }
        } catch (Exception e) {
            // optional: log or ignore
        }
        return null;
    }

    private void sendTpllCommand(MinecraftClient client, String clipboard) {
        if (client == null || client.player == null) return;

        String commandNoSlash = "tpll " + clipboard.trim();

        try {
            // Preferred: send a command packet (no leading slash)
            client.player.networkHandler.sendChatCommand(commandNoSlash);
        } catch (Exception ex) {
            // Fallback: send raw chat with slash
            client.player.networkHandler.sendChatMessage("/" + commandNoSlash);
        }

        // Feedback in chat
        client.player.sendMessage(Text.literal("[BoshysBTEUtils] Ran /tpll " + clipboard.trim()), false);
    }
}
