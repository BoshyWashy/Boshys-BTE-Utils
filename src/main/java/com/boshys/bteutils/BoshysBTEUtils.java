package com.boshys.bteutils;

import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.boshys.bteutils.render.CustomParticleRenderer;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class BoshysBTEUtils implements ClientModInitializer {

    public static KeyBinding tpllKeybind;
    public static KeyBinding addMarkerKeybind;
    public static KeyBinding clearMarkersKeybind;
    public static KeyBinding selectMarkerKeybind;
    public static KeyBinding deleteMarkerKeybind;
    private static BoshysBTEUtilsConfig config;

    public static final KeyBinding.Category BTE_UTILS_CATEGORY = new KeyBinding.Category(Identifier.of("boshysbteutils", "bteutils"));

    public static final List<TeleportMarker> markers = new ArrayList<>();
    public static final List<MarkerConnection> markerConnections = new ArrayList<>();
    public static TeleportMarker selectedMarker = null;
    public static TeleportMarker lastAddedMarker = null;

    // Cooldown for marker selection (in ticks)
    private static int selectionCooldown = 0;
    private static final int SELECTION_COOLDOWN_TICKS = 5;

    // TPLL tracking variables
    private double posXBeforeTpll = 0;
    private double posYBeforeTpll = 0;
    private double posZBeforeTpll = 0;
    private int tpllCooldownTicks = 0;
    private static final int TPLL_COOLDOWN_MAX = 40; // 2 seconds at 20 ticks per second
    private boolean waitingForTeleport = false;

    @Override
    public void onInitializeClient() {
        AutoConfig.register(BoshysBTEUtilsConfig.class, GsonConfigSerializer::new);
        config = AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).getConfig();

        // Register keybindings with proper category
        tpllKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.boshysbteutils.tpll",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                BTE_UTILS_CATEGORY
        ));

        addMarkerKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.boshysbteutils.addmarker",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                BTE_UTILS_CATEGORY
        ));

        clearMarkersKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.boshysbteutils.clearmarkers",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                BTE_UTILS_CATEGORY
        ));

        selectMarkerKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.boshysbteutils.selectmarker",
                InputUtil.Type.MOUSE,
                InputUtil.GLFW_MOUSE_BUTTON_RIGHT,
                BTE_UTILS_CATEGORY
        ));

        deleteMarkerKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.boshysbteutils.deletemarker",
                InputUtil.Type.KEYSYM,
                InputUtil.GLFW_KEY_DELETE,
                BTE_UTILS_CATEGORY
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("boshys-bt-utils")
                    .then(ClientCommandManager.literal("clearMarkers")
                            .executes(context -> {
                                int count = markers.size();
                                markers.clear();
                                markerConnections.clear();
                                selectedMarker = null;
                                lastAddedMarker = null;
                                context.getSource().sendFeedback(Text.literal("§aCleared " + count + " teleport markers!"));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("addMarker")
                            .executes(context -> {
                                if (!config.enableMarkers) {
                                    context.getSource().sendFeedback(Text.literal("§cMarkers disabled in config!"));
                                    return 0;
                                }

                                double x = context.getSource().getPlayer().getX();
                                double y = context.getSource().getPlayer().getY();
                                double z = context.getSource().getPlayer().getZ();

                                TeleportMarker newMarker = addMarker(new Vec3d(x, y, z));

                                // Auto-connect if enabled
                                if (config.enableAutoLineConnection) {
                                    handleAutoConnect(newMarker);
                                }

                                context.getSource().sendFeedback(Text.literal("§aMarker added at your location!"));
                                return 1;
                            }))
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.world == null) return;

            // Decrement cooldown
            if (selectionCooldown > 0) {
                selectionCooldown--;
            }

            // Handle TPLL teleport detection
            handleTpllTeleportDetection(client);

            // TPLL keybind handler
            while (tpllKeybind.wasPressed()) {
                try {
                    String clip = getClipboard(client);
                    if (clip == null || clip.isEmpty()) {
                        notifyError(client, "§cClipboard empty!");
                        continue;
                    }

                    // Parse coordinates to validate format before sending
                    Vec3d coords = parseCoordinates(clip);
                    if (coords == null) {
                        notifyError(client, "§cInvalid coordinates format!");
                        continue;
                    }

                    // Store current position before sending command only if auto TPLL markers are enabled
                    if (config.enableMarkers && config.enableAutoTpllMarkers) {
                        posXBeforeTpll = client.player.getX();
                        posYBeforeTpll = client.player.getY();
                        posZBeforeTpll = client.player.getZ();
                        waitingForTeleport = true;
                        tpllCooldownTicks = TPLL_COOLDOWN_MAX;
                    }

                    String commandNoSlash = config.commandPrefix + " " + clip.trim();
                    client.player.networkHandler.sendChatCommand(commandNoSlash);

                } catch (Throwable t) {
                    notifyError(client, "§cError reading clipboard or sending command.");
                    waitingForTeleport = false;
                }
            }

            // Add Marker keybind handler
            while (addMarkerKeybind.wasPressed()) {
                if (!config.enableMarkers) {
                    notifyError(client, "§cMarkers disabled in config!");
                    continue;
                }

                double x = client.player.getX();
                double y = client.player.getY();
                double z = client.player.getZ();

                TeleportMarker newMarker = addMarker(new Vec3d(x, y, z));

                // Auto-connect if enabled
                if (config.enableAutoLineConnection) {
                    handleAutoConnect(newMarker);
                }

                notifyError(client, "§aMarker added at your location!");
            }

            // Clear Markers keybind handler
            while (clearMarkersKeybind.wasPressed()) {
                int count = markers.size();
                markers.clear();
                markerConnections.clear();
                selectedMarker = null;
                lastAddedMarker = null;
                notifyError(client, "§aCleared " + count + " teleport markers!");
            }

            // Delete marker keybind handler
            while (deleteMarkerKeybind.wasPressed()) {
                if (selectedMarker != null) {
                    deleteMarker(selectedMarker);
                    notifyError(client, "§aDeleted selected marker!");
                } else {
                    notifyError(client, "§cNo marker selected! Right-click a marker to select it.");
                }
            }

            // Select/Connect marker handler (right click)
            handleMarkerSelection(client);
        });

        // Register world renderer using AFTER_ENTITIES event
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            CustomParticleRenderer.render(context);
        });
    }

    private void handleTpllTeleportDetection(MinecraftClient client) {
        if (!waitingForTeleport) return;

        // Decrement cooldown
        if (tpllCooldownTicks > 0) {
            tpllCooldownTicks--;
        } else {
            // Timeout - cancel waiting
            waitingForTeleport = false;
            return;
        }

        // Check if player has moved at all from the position before TPLL
        double currentX = client.player.getX();
        double currentY = client.player.getY();
        double currentZ = client.player.getZ();

        // If position changed at all (even slightly), consider it a teleport
        if (currentX != posXBeforeTpll || currentY != posYBeforeTpll || currentZ != posZBeforeTpll) {
            // Place marker at the ACTUAL new position (where player ended up after teleport)
            TeleportMarker newMarker = addMarker(new Vec3d(currentX, currentY, currentZ));

            // Auto-connect if enabled
            if (config.enableAutoLineConnection) {
                handleAutoConnect(newMarker);
            }

            // Reset tracking
            waitingForTeleport = false;
        }
    }

    private void handleAutoConnect(TeleportMarker newMarker) {
        // If there's a last added marker, connect to it and make new marker selected
        if (lastAddedMarker != null && lastAddedMarker != newMarker) {
            connectMarkers(lastAddedMarker, newMarker);
        }
        // Update: new marker becomes the selected/last one
        selectedMarker = newMarker;
        lastAddedMarker = newMarker;
    }

    private void handleMarkerSelection(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Check if main hand is empty - only allow selection with empty hand
        ItemStack mainHandStack = client.player.getStackInHand(Hand.MAIN_HAND);
        if (!mainHandStack.isEmpty()) {
            return; // Can't select markers while holding something
        }

        // Check cooldown
        if (selectionCooldown > 0) return;

        // Check if right mouse button was clicked
        if (!selectMarkerKeybind.isPressed()) return;

        // Set cooldown
        selectionCooldown = SELECTION_COOLDOWN_TICKS;

        // Raycast to find marker
        Vec3d eyePos = client.player.getEyePos();
        Vec3d lookVec = client.player.getRotationVector();
        double reachDistance = 5.0; // Default reach distance

        Vec3d endPos = eyePos.add(lookVec.x * reachDistance, lookVec.y * reachDistance, lookVec.z * reachDistance);

        TeleportMarker hitMarker = null;
        double closestDist = Double.MAX_VALUE;

        for (TeleportMarker marker : markers) {
            // Create a small hitbox around the marker
            float scale = marker.scale * 2; // Make hitbox slightly larger than visual
            Box hitbox = new Box(
                    marker.position.x - scale, marker.position.y - scale, marker.position.z - scale,
                    marker.position.x + scale, marker.position.y + scale, marker.position.z + scale
            );

            // Check if ray intersects with hitbox
            Optional<Vec3d> hitResult = hitbox.raycast(eyePos, endPos);
            if (hitResult.isPresent()) {
                double dist = eyePos.squaredDistanceTo(hitResult.get());
                if (dist < closestDist) {
                    closestDist = dist;
                    hitMarker = marker;
                }
            }
        }

        if (hitMarker != null) {
            if (selectedMarker == null) {
                // Select first marker
                selectedMarker = hitMarker;
                notifyError(client, "§aMarker selected! Right-click another to connect, or press Delete to remove.");
            } else if (selectedMarker == hitMarker) {
                // Deselect if clicking same marker
                selectedMarker = null;
                notifyError(client, "§eMarker deselected.");
            } else {
                // Try to connect or disconnect
                if (areMarkersConnected(selectedMarker, hitMarker)) {
                    // Disconnect
                    disconnectMarkers(selectedMarker, hitMarker);
                    notifyError(client, "§cDisconnected markers!");
                } else {
                    // Connect
                    connectMarkers(selectedMarker, hitMarker);
                    notifyError(client, "§aConnected markers!");
                }
                selectedMarker = null;
            }
        }
    }

    public static TeleportMarker addMarker(Vec3d pos) {
        TeleportMarker marker = new TeleportMarker(pos, config.markerColour, config.markerScale, config.markerOpacity);
        markers.add(marker);
        return marker;
    }

    public static void deleteMarker(TeleportMarker marker) {
        // Remove all connections involving this marker
        markerConnections.removeIf(conn -> conn.marker1 == marker || conn.marker2 == marker);
        markers.remove(marker);
        if (selectedMarker == marker) {
            selectedMarker = null;
        }
        if (lastAddedMarker == marker) {
            lastAddedMarker = null;
        }
    }

    public static void clearAllMarkers() {
        markers.clear();
        markerConnections.clear();
        selectedMarker = null;
        lastAddedMarker = null;
    }

    public static void connectMarkers(TeleportMarker m1, TeleportMarker m2) {
        if (m1 == m2) return;
        if (!areMarkersConnected(m1, m2)) {
            markerConnections.add(new MarkerConnection(m1, m2));
        }
    }

    public static void disconnectMarkers(TeleportMarker m1, TeleportMarker m2) {
        markerConnections.removeIf(conn ->
                (conn.marker1 == m1 && conn.marker2 == m2) ||
                        (conn.marker1 == m2 && conn.marker2 == m1)
        );
    }

    public static boolean areMarkersConnected(TeleportMarker m1, TeleportMarker m2) {
        for (MarkerConnection conn : markerConnections) {
            if ((conn.marker1 == m1 && conn.marker2 == m2) ||
                    (conn.marker1 == m2 && conn.marker2 == m1)) {
                return true;
            }
        }
        return false;
    }

    private Vec3d parseCoordinates(String coords) {
        try {
            String[] parts = coords.split("[,\\s]+");

            if (parts.length >= 2) {
                double x = Double.parseDouble(parts[0].trim());
                double z = Double.parseDouble(parts[1].trim());
                double y = 64;

                if (parts.length >= 3) {
                    y = Double.parseDouble(parts[2].trim());
                }

                return new Vec3d(x, y, z);
            }
        } catch (Exception e) {
            // Invalid format
        }
        return null;
    }

    private String getClipboard(MinecraftClient client) {
        try {
            String data = client.keyboard.getClipboard();
            if (data == null) return null;

            String cleaned = data;
            cleaned = cleaned.replace("\r\n", "\n").replace("\r", "\n").trim();
            int nl = cleaned.indexOf('\n');
            if (nl >= 0) cleaned = cleaned.substring(0, nl).trim();

            if (config.formatCoordinates) {
                cleaned = cleaned.replaceAll("\\s*,\\s*", ",").replaceAll("\\s+", " ");
            }

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

    public static BoshysBTEUtilsConfig getConfig() {
        return config;
    }

    public static class TeleportMarker {
        public final Vec3d position;
        public final int colour;
        public final float scale;
        public final float opacity;

        public TeleportMarker(Vec3d position, int colour, float scale, float opacity) {
            this.position = position;
            this.colour = colour;
            this.scale = scale;
            this.opacity = opacity;
        }
    }

    public static class MarkerConnection {
        public final TeleportMarker marker1;
        public final TeleportMarker marker2;

        public MarkerConnection(TeleportMarker marker1, TeleportMarker marker2) {
            this.marker1 = marker1;
            this.marker2 = marker2;
        }
    }
}
