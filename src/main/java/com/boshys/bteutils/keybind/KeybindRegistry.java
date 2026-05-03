package com.boshys.bteutils.keybind;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;

import java.util.EnumMap;
import java.util.Map;

public class KeybindRegistry {

    private static final Map<Keybind, KeyBinding> KEYBINDS = new EnumMap<>(Keybind.class);

    private static final KeyBinding.Category CATEGORY =
            new KeyBinding.Category(Identifier.of("boshysbteutils", "bteutils"));

    public static void registerAll() {
        for (Keybind keybind : Keybind.values()) {
            KeyBinding binding = new KeyBinding(
                    "key.boshysbteutils." + keybind.name,
                    keybind.type,
                    keybind.defaultKey,
                    CATEGORY
            );

            KEYBINDS.put(keybind, KeyBindingHelper.registerKeyBinding(binding));
        }
    }

    public static KeyBinding get(Keybind keybind) {
        return KEYBINDS.get(keybind);
    }

    public static boolean wasPressed(Keybind keybind) {
        return get(keybind).wasPressed();
    }

    public static boolean isPressed(Keybind keybind) {
        return get(keybind).isPressed();
    }
}
