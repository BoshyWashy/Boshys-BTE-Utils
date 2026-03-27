package com.boshys.bteutils;

import com.boshys.bteutils.commands.CommandRegistry;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.boshys.bteutils.data.MarkerData;
import com.boshys.bteutils.storage.KmlImportHandler;
import com.boshys.bteutils.storage.MarkerStorage;
import com.boshys.bteutils.rendering.CustomParticleRenderer;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.*;

public class BoshysBTEUtils implements ClientModInitializer {

    public static BoshysBTEUtils INSTANCE;

    // Keybindings
    public static KeyBinding tpllKeybind;
    public static KeyBinding addMarkerKeybind;
    public static KeyBinding clearMarkersKeybind;
    public static KeyBinding selectMarkerKeybind;
    public static KeyBinding deleteMarkerKeybind;

    public static final KeyBinding.Category BTE_UTILS_CATEGORY = new KeyBinding.Category(Identifier.of("boshysbteutils", "bteutils"));

    // Marker data
    public static final List<MarkerData.TeleportMarker> markers = new ArrayList<>();
    public static final List<MarkerData.MarkerConnection> markerConnections = new ArrayList<>();
    public static final Set<MarkerData.TeleportMarker> selectedMarkers = new HashSet<>();
    public static MarkerData.TeleportMarker lastAddedMarker = null;

    // File tracking
    public static final Map<MarkerData.TeleportMarker, String> markerOrigins = new HashMap<>();
    public static final Map<MarkerData.TeleportMarker, Vec3d> markerOriginalPositions = new HashMap<>();

    // Session state tracking for first-time messages
    public static boolean hasAddedMarkerThisSession = false;
    public static boolean hasSelectedMarkerThisSession = false;

    // State
    private static BoshysBTEUtilsConfig config;
    private int selectionCooldown = 0;
    private static final int SELECTION_COOLDOWN_TICKS = 5;

    private double posXBeforeTpll = 0;
    private double posYBeforeTpll = 0;
    private double posZBeforeTpll = 0;
    private int tpllCooldownTicks = 0;
    private static final int TPLL_COOLDOWN_MAX = 60;
    private boolean waitingForTeleport = false;

    private String lastCommandSent = "";
    private int commandCooldownTicks = 0;
    private static final int COMMAND_COOLDOWN_MAX = 5;

    // Components
    private MarkerStorage markerStorage;
    private KmlImportHandler kmlImportHandler;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        AutoConfig.register(BoshysBTEUtilsConfig.class, GsonConfigSerializer::new);
        config = AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).getConfig();

        // Initialize components
        markerStorage = new MarkerStorage(this);
        kmlImportHandler = new KmlImportHandler(this);

        markerStorage.updateMarkersSavePath();

        registerKeybindings();
        registerEvents();
        registerCommands();

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            CustomParticleRenderer.render(context);
        });
    }

    private void registerKeybindings() {
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
                GLFW.GLFW_KEY_DELETE,
                BTE_UTILS_CATEGORY
        ));
    }

    private void registerEvents() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            markerStorage.performAutosave();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Reset session state when joining a server
            hasAddedMarkerThisSession = false;
            hasSelectedMarkerThisSession = false;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.world == null) return;

            kmlImportHandler.tick(client);
            markerStorage.tickAutosave();

            if (selectionCooldown > 0) {
                selectionCooldown--;
            }

            if (commandCooldownTicks > 0) {
                commandCooldownTicks--;
                if (commandCooldownTicks == 0) {
                    lastCommandSent = "";
                }
            }

            handleTpllTeleportDetection(client);
            handleKeybinds(client);
            handleMarkerSelection(client);
        });
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            new CommandRegistry(this, markerStorage, kmlImportHandler).register(dispatcher);
        });
    }

    private void handleKeybinds(MinecraftClient client) {
        while (tpllKeybind.wasPressed()) {
            try {
                String clip = getClipboard(client);
                if (clip == null || clip.isEmpty()) {
                    notifyError(client, "command.boshysbteutils.error.clipboard_empty");
                    continue;
                }

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
                notifyError(client, "command.boshysbteutils.error.clipboard_error");
                waitingForTeleport = false;
            }
        }

        while (addMarkerKeybind.wasPressed()) {
            if (!config.enableMarkers) {
                notifyError(client, "command.boshysbteutils.error.markers_disabled");
                continue;
            }

            double x = client.player.getX();
            double y = client.player.getY();
            double z = client.player.getZ();

            MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3d(x, y, z));

            if (config.enableAutoLineConnection) {
                MarkerData.handleAutoConnect(newMarker);
            }

            // Check if this is the first marker added this session via manual add
            if (!hasAddedMarkerThisSession) {
                sendFirstMarkerMessage(client);
                hasAddedMarkerThisSession = true;
            } else {
                // Subsequent markers - action bar only
                notifyActionBar(client, "command.boshysbteutils.marker.added_actionbar");
            }
        }

        while (clearMarkersKeybind.wasPressed()) {
            int cacheCount = markerStorage.getCacheMarkerCount();
            if (config.enableClearConfirmation && cacheCount > config.clearConfirmLimit) {
                markerStorage.setPendingClear(cacheCount, false);
                notifyError(client, "command.boshysbteutils.marker.confirm.required", cacheCount);
                continue;
            }
            int count = markerStorage.clearCacheMarkersOnly();
            notifyActionBar(client, "command.boshysbteutils.marker.cleared", count);
        }

        while (deleteMarkerKeybind.wasPressed()) {
            if (!selectedMarkers.isEmpty()) {
                int count = selectedMarkers.size();
                for (MarkerData.TeleportMarker marker : new ArrayList<>(selectedMarkers)) {
                    MarkerData.deleteMarker(marker);
                }
                selectedMarkers.clear();
                notifyActionBar(client, "command.boshysbteutils.marker.deleted", count);
            } else {
                notifyError(client, "command.boshysbteutils.marker.no_selection");
            }
        }
    }

    private static void sendFirstMarkerMessage(MinecraftClient client) {
        if (client.player == null) return;

        // Send header
        client.player.sendMessage(Text.literal("§7============= §aBoshy's BT-Utils §7============="), false);
        // Empty line
        client.player.sendMessage(Text.literal(""), false);
        // Message 1
        client.player.sendMessage(Text.translatable("command.boshysbteutils.marker.first_time.select"), false);
        // Empty line
        client.player.sendMessage(Text.literal(""), false);
        // Message 2
        client.player.sendMessage(Text.translatable("command.boshysbteutils.marker.first_time.multiselect"), false);
        // Empty line
        client.player.sendMessage(Text.literal(""), false);
        // Message 3
        client.player.sendMessage(Text.translatable("command.boshysbteutils.marker.first_time.move"), false);
    }

    private void handleTpllTeleportDetection(MinecraftClient client) {
        if (kmlImportHandler.isImporting()) {
            return;
        }

        if (!waitingForTeleport && commandCooldownTicks == 0) return;

        if (tpllCooldownTicks > 0) {
            tpllCooldownTicks--;
        } else if (waitingForTeleport) {
            waitingForTeleport = false;
            return;
        }

        double currentX = client.player.getX();
        double currentY = client.player.getY();
        double currentZ = client.player.getZ();

        double distanceMoved = Math.sqrt(
                Math.pow(currentX - posXBeforeTpll, 2) +
                        Math.pow(currentY - posYBeforeTpll, 2) +
                        Math.pow(currentZ - posZBeforeTpll, 2)
        );

        if (distanceMoved > 0.1) {
            if (waitingForTeleport || commandCooldownTicks > 0) {
                MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3d(currentX, currentY, currentZ));

                if (config.enableAutoLineConnection) {
                    MarkerData.handleAutoConnect(newMarker);
                }

                // Auto TPLL markers - no message (original behavior)
                // Only manual addMarker shows messages

                waitingForTeleport = false;
                commandCooldownTicks = 0;
                lastCommandSent = "";
            }
        }
    }

    private void handleMarkerSelection(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        ItemStack mainHandStack = client.player.getStackInHand(Hand.MAIN_HAND);
        if (!mainHandStack.isEmpty()) {
            return;
        }

        if (selectionCooldown > 0) return;
        if (!selectMarkerKeybind.isPressed()) return;

        selectionCooldown = SELECTION_COOLDOWN_TICKS;

        Vec3d eyePos = client.player.getEyePos();
        Vec3d lookVec = client.player.getRotationVector();
        double reachDistance = 5.0;

        Vec3d endPos = eyePos.add(lookVec.x * reachDistance, lookVec.y * reachDistance, lookVec.z * reachDistance);

        MarkerData.TeleportMarker hitMarker = null;
        double closestDist = Double.MAX_VALUE;

        for (MarkerData.TeleportMarker marker : markers) {
            float scale = marker.scale * 2;
            Box hitbox = new Box(
                    marker.position.x - scale, marker.position.y - scale, marker.position.z - scale,
                    marker.position.x + scale, marker.position.y + scale, marker.position.z + scale
            );

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
            long windowHandle = client.getWindow().getHandle();
            boolean ctrlPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;

            boolean shiftPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

            boolean multiSelect = ctrlPressed || shiftPressed;

            if (selectedMarkers.contains(hitMarker)) {
                selectedMarkers.remove(hitMarker);
                if (selectedMarkers.isEmpty()) {
                    notifyActionBar(client, "command.boshysbteutils.marker.deselected.none");
                } else {
                    notifyActionBar(client, "command.boshysbteutils.marker.deselected", selectedMarkers.size());
                }
            } else {
                if (selectedMarkers.size() == 1 && !multiSelect) {
                    MarkerData.TeleportMarker selectedMarker = selectedMarkers.iterator().next();

                    if (selectedMarker == hitMarker) {
                        selectedMarkers.clear();
                        notifyActionBar(client, "command.boshysbteutils.marker.deselected.single");
                    } else if (MarkerData.areMarkersConnected(selectedMarker, hitMarker)) {
                        MarkerData.disconnectMarkers(selectedMarker, hitMarker);
                        selectedMarkers.clear();
                        notifyActionBar(client, "command.boshysbteutils.marker.disconnected");
                    } else {
                        MarkerData.connectMarkers(selectedMarker, hitMarker);
                        selectedMarkers.clear();
                        notifyActionBar(client, "command.boshysbteutils.marker.connected");
                    }
                } else {
                    if (!multiSelect) {
                        selectedMarkers.clear();
                    }
                    selectedMarkers.add(hitMarker);

                    // Check if this is the first selection this session
                    if (!hasSelectedMarkerThisSession) {
                        sendFirstSelectionMessage(client);
                        hasSelectedMarkerThisSession = true;
                    } else {
                        // Subsequent selections - action bar only
                        if (selectedMarkers.size() == 1) {
                            notifyActionBar(client, "command.boshysbteutils.marker.selected_actionbar.single");
                        } else {
                            notifyActionBar(client, "command.boshysbteutils.marker.selected_actionbar.multiple", selectedMarkers.size());
                        }
                    }
                }
            }
        }
    }

    private void sendFirstSelectionMessage(MinecraftClient client) {
        if (client.player == null) return;

        // Send header
        client.player.sendMessage(Text.literal("§7============= §aBoshy's BT-Utils §7============="), false);
        // Empty line
        client.player.sendMessage(Text.literal(""), false);
        // Message 1
        client.player.sendMessage(Text.translatable("command.boshysbteutils.selection.first_time.connect"), false);
        // Empty line
        client.player.sendMessage(Text.literal(""), false);
        // Message 2
        client.player.sendMessage(Text.translatable("command.boshysbteutils.selection.first_time.disconnect"), false);
        // Empty line
        client.player.sendMessage(Text.literal(""), false);
        // Message 3
        client.player.sendMessage(Text.translatable("command.boshysbteutils.selection.first_time.edit_colour"), false);
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

    private void notifyError(MinecraftClient client, String translationKey, Object... args) {
        if (client == null || client.player == null) return;
        client.player.sendMessage(Text.translatable(translationKey, args).formatted(net.minecraft.util.Formatting.RED), false);
    }

    private void notifyActionBar(MinecraftClient client, String translationKey, Object... args) {
        if (client == null || client.player == null) return;
        client.player.sendMessage(Text.translatable(translationKey, args).formatted(net.minecraft.util.Formatting.GREEN), true);
    }

    public void onCommandSent(String command) {
        if (kmlImportHandler.isImporting()) {
            return;
        }

        if (!config.enableMarkers || !config.enableAutoTpllMarkers) return;

        String lowerCmd = command.toLowerCase().trim();
        String[] parts = lowerCmd.split("\\s+", 2);
        String cmdName = parts[0].replace("/", "");

        if (cmdName.equals("tpll") || cmdName.equals("c") || cmdName.equals(config.commandPrefix.toLowerCase())) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                posXBeforeTpll = client.player.getX();
                posYBeforeTpll = client.player.getY();
                posZBeforeTpll = client.player.getZ();
                lastCommandSent = command;
                commandCooldownTicks = COMMAND_COOLDOWN_MAX;
                waitingForTeleport = true;
                tpllCooldownTicks = TPLL_COOLDOWN_MAX;
            }
        }
    }

    public void onPlayerTeleported(MinecraftClient client, double oldX, double oldY, double oldZ, double newX, double newY, double newZ) {
        if (kmlImportHandler.isImporting()) {
            kmlImportHandler.onMarkerPlaced(client);
            return;
        }

        double distanceMoved = Math.sqrt(
                Math.pow(newX - oldX, 2) +
                        Math.pow(newY - oldY, 2) +
                        Math.pow(newZ - oldZ, 2)
        );

        if (distanceMoved > 0.1) {
            MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3d(newX, newY, newZ));

            if (config.enableAutoLineConnection) {
                MarkerData.handleAutoConnect(newMarker);
            }

            // Auto TPLL markers from mixin - no message (original behavior)
            // Only manual addMarker shows messages
        }
    }

    // Getters
    public static BoshysBTEUtilsConfig getConfig() {
        return config;
    }

    public static void setConfig(BoshysBTEUtilsConfig newConfig) {
        config = newConfig;
        if (INSTANCE != null && INSTANCE.markerStorage != null) {
            INSTANCE.markerStorage.updateMarkersSavePath();
        }
        AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
    }

    public MarkerStorage getMarkerStorage() {
        return markerStorage;
    }

    public KmlImportHandler getKmlImportHandler() {
        return kmlImportHandler;
    }

    public boolean isWaitingForTeleport() {
        return waitingForTeleport;
    }
}