package com.boshys.bteutils;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

public class BoshysBTEUtils implements ClientModInitializer {

    private static KeyBinding tpllKeybind;

    @Override
    public void onInitializeClient() {
        // Register keybind (default unbound)
        tpllKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bteutils.tpll",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(), // default unbound
                "category.bteutils"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.world == null || client.player.networkHandler == null) return;

            while (tpllKeybind.wasPressed()) {
                try {
                    String clip = getClipboard(client);
                    if (clip == null || clip.isEmpty()) {
                        notifyError(client, "§cClipboard empty!");
                        continue;
                    }

                    String commandNoSlash = "tpll " + clip.trim();
                    client.player.networkHandler.sendChatCommand(commandNoSlash);

                    // No success message
                } catch (Throwable t) {
                    notifyError(client, "§cError reading clipboard or sending command.");
                }
            }
        });
    }

    private String getClipboard(MinecraftClient client) {
        try {
            String data = client.keyboard.getClipboard();
            if (data == null) return null;

            String cleaned = data.replace("\r\n", "\n").replace("\r", "\n").trim();
            int nl = cleaned.indexOf('\n');
            if (nl >= 0) cleaned = cleaned.substring(0, nl).trim();
            cleaned = cleaned.replaceAll("\\s*,\\s*", ",").replaceAll("\\s+", " ");
            return cleaned.isEmpty() ? null : cleaned;
        } catch (Throwable t) {
            return null;
        }
    }

    private void notifyError(MinecraftClient client, String msg) {
        if (client == null || client.player == null) return;
        String safe = Objects.toString(msg, "");
        if (safe.length() > 300) safe = safe.substring(0, 300) + "...";
        client.player.sendMessage(Text.literal(safe), false);
    }
}
