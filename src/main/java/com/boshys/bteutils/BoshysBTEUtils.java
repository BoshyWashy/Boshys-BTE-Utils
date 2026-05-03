package com.boshys.bteutils;

import com.boshys.bteutils.commands.CommandRegistry;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.boshys.bteutils.data.MarkerData;
import com.boshys.bteutils.overlay.OverlayData;
import com.boshys.bteutils.overlay.OverlayRenderer;
import com.boshys.bteutils.overlay.OverlayStorage;
import com.boshys.bteutils.overlay.OverlayTextureManager;
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
    public static KeyBinding toggleOverlayMarkersKeybind;

    public static final KeyBinding.Category BTE_UTILS_CATEGORY = new KeyBinding.Category(Identifier.of("boshysbteutils", "bteutils"));

    // Marker data
    public static final List<MarkerData.TeleportMarker> markers = new ArrayList<>();
    public static final List<MarkerData.MarkerConnection> markerConnections = new ArrayList<>();
    public static final Set<MarkerData.TeleportMarker> selectedMarkers = new HashSet<>();
    public static MarkerData.TeleportMarker lastAddedMarker = null;

    // Hidden markers storage
    public static final List<MarkerData.TeleportMarker> hiddenMarkers = new ArrayList<>();
    public static final List<MarkerData.MarkerConnection> hiddenConnections = new ArrayList<>();
    public static final Set<MarkerData.TeleportMarker> hiddenSelectedMarkers = new HashSet<>();
    public static MarkerData.TeleportMarker hiddenLastAddedMarker = null;
    public static boolean markersHidden = false;
    public static boolean hideWarningShown = false;

    // File tracking
    public static final Map<MarkerData.TeleportMarker, String> markerOrigins = new HashMap<>();
    public static final Map<MarkerData.TeleportMarker, Vec3d> markerOriginalPositions = new HashMap<>();

    // Session state tracking for first-time messages
    public static boolean hasAddedMarkerThisSession = false;
    public static boolean hasSelectedMarkerThisSession = false;

    // Overlay selection state
    public static OverlayData.ImageOverlay selectedOverlayCorner = null;
    public static int selectedCornerIndex = -1; // 0-3 corners, 4 anchor

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

    // Overlay components
    private static OverlayStorage overlayStorage;
    private static OverlayTextureManager overlayTextureManager;
    private static OverlayRenderer overlayRenderer;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        AutoConfig.register(BoshysBTEUtilsConfig.class, GsonConfigSerializer::new);
        config = AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).getConfig();

        markerStorage = new MarkerStorage(this);
        kmlImportHandler = new KmlImportHandler(this);
        markerStorage.updateMarkersSavePath();

        overlayStorage = new OverlayStorage();
        overlayTextureManager = new OverlayTextureManager();
        overlayRenderer = new OverlayRenderer(overlayStorage, overlayTextureManager);

        registerKeybindings();
        registerEvents();
        registerCommands();

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            CustomParticleRenderer.render(context);
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            overlayRenderer.render(context);
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

        toggleOverlayMarkersKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.boshysbteutils.toggleoverlaymarkers",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                BTE_UTILS_CATEGORY
        ));
    }

    private void registerEvents() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            markerStorage.performAutosave();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
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

            if (!handleOverlayCornerSelection(client)) {
                handleMarkerSelection(client);
            }
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
                    if (markersHidden) {
                        if (!hideWarningShown) {
                            notifyError(client, "command.boshysbteutils.error.markers_hidden");
                            hideWarningShown = true;
                        }
                        continue;
                    }
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

            if (markersHidden) {
                if (!hideWarningShown) {
                    notifyError(client, "command.boshysbteutils.error.markers_hidden");
                    hideWarningShown = true;
                }
                continue;
            }

            double x = client.player.getX();
            double y = client.player.getY();
            double z = client.player.getZ();

            MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3d(x, y, z));

            if (config.enableAutoLineConnection) {
                MarkerData.handleAutoConnect(newMarker);
            }

            if (!hasAddedMarkerThisSession) {
                sendFirstMarkerMessage(client);
                hasAddedMarkerThisSession = true;
            } else {
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
            if (markersHidden) {
                if (!hideWarningShown) {
                    notifyError(client, "command.boshysbteutils.error.markers_hidden");
                    hideWarningShown = true;
                }
                continue;
            }

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

        while (toggleOverlayMarkersKeybind.wasPressed()) {
            if (getOverlayStorage().getLoadedOverlays().isEmpty()) {
                notifyActionBar(client, "command.boshysbteutils.overlay.no_loaded");
                continue;
            }
            boolean anyVisible = false;
            for (OverlayData.ImageOverlay o : getOverlayStorage().getLoadedOverlays().values()) {
                if (o.markersVisible) { anyVisible = true; break; }
            }
            int count = 0;
            for (OverlayData.ImageOverlay o : getOverlayStorage().getLoadedOverlays().values()) {
                o.markersVisible = !anyVisible;
                getOverlayStorage().saveOverlay(o);
                count++;
            }
            if (anyVisible) {
                notifyActionBar(client, "command.boshysbteutils.overlay.markers_hidden_all", count);
            } else {
                notifyActionBar(client, "command.boshysbteutils.overlay.markers_shown_all", count);
            }
        }
    }

    private static void sendFirstMarkerMessage(MinecraftClient client) {
        if (client.player == null) return;

        client.player.sendMessage(Text.literal("§7============= §aBoshy's BT-Utils §7============="), false);
        client.player.sendMessage(Text.literal(""), false);
        client.player.sendMessage(Text.translatable("command.boshysbteutils.marker.first_time.select"), false);
        client.player.sendMessage(Text.literal(""), false);
        client.player.sendMessage(Text.translatable("command.boshysbteutils.marker.first_time.multiselect"), false);
        client.player.sendMessage(Text.literal(""), false);
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
                if (markersHidden) {
                    if (!hideWarningShown) {
                        notifyError(client, "command.boshysbteutils.error.markers_hidden");
                        hideWarningShown = true;
                    }
                    waitingForTeleport = false;
                    commandCooldownTicks = 0;
                    lastCommandSent = "";
                    return;
                }

                MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3d(currentX, currentY, currentZ));

                if (config.enableAutoLineConnection) {
                    MarkerData.handleAutoConnect(newMarker);
                }

                waitingForTeleport = false;
                commandCooldownTicks = 0;
                lastCommandSent = "";
            }
        }
    }

    private boolean handleOverlayCornerSelection(MinecraftClient client) {
        if (client.player == null || client.world == null) return false;

        ItemStack mainHandStack = client.player.getStackInHand(Hand.MAIN_HAND);
        if (!mainHandStack.isEmpty()) return false;

        if (selectionCooldown > 0) return false;
        if (!selectMarkerKeybind.isPressed()) return false;

        Vec3d eyePos = client.player.getEyePos();
        Vec3d lookVec = client.player.getRotationVector();
        double reachDistance = 5.0;
        Vec3d endPos = eyePos.add(lookVec.x * reachDistance, lookVec.y * reachDistance, lookVec.z * reachDistance);

        // Check nudge cubes first if something is selected
        if (selectedOverlayCorner != null && selectedCornerIndex != -1) {
            Vec3d markerPos = selectedCornerIndex == 4
                    ? selectedOverlayCorner.anchor
                    : selectedOverlayCorner.corners[selectedCornerIndex];

            double cubeOffset = 1.5;
            double cubeHalf = 0.15;

            Box pxBox = new Box(markerPos.x + cubeOffset - cubeHalf, markerPos.y - cubeHalf, markerPos.z - cubeHalf,
                    markerPos.x + cubeOffset + cubeHalf, markerPos.y + cubeHalf, markerPos.z + cubeHalf);
            if (pxBox.raycast(eyePos, endPos).isPresent()) {
                nudgeSelectedCorner(client, 1, 0, 0);
                selectionCooldown = SELECTION_COOLDOWN_TICKS;
                return true;
            }

            Box nxBox = new Box(markerPos.x - cubeOffset - cubeHalf, markerPos.y - cubeHalf, markerPos.z - cubeHalf,
                    markerPos.x - cubeOffset + cubeHalf, markerPos.y + cubeHalf, markerPos.z + cubeHalf);
            if (nxBox.raycast(eyePos, endPos).isPresent()) {
                nudgeSelectedCorner(client, -1, 0, 0);
                selectionCooldown = SELECTION_COOLDOWN_TICKS;
                return true;
            }

            Box pzBox = new Box(markerPos.x - cubeHalf, markerPos.y - cubeHalf, markerPos.z + cubeOffset - cubeHalf,
                    markerPos.x + cubeHalf, markerPos.y + cubeHalf, markerPos.z + cubeOffset + cubeHalf);
            if (pzBox.raycast(eyePos, endPos).isPresent()) {
                nudgeSelectedCorner(client, 0, 0, 1);
                selectionCooldown = SELECTION_COOLDOWN_TICKS;
                return true;
            }

            Box nzBox = new Box(markerPos.x - cubeHalf, markerPos.y - cubeHalf, markerPos.z - cubeOffset - cubeHalf,
                    markerPos.x + cubeHalf, markerPos.y + cubeHalf, markerPos.z - cubeOffset + cubeHalf);
            if (nzBox.raycast(eyePos, endPos).isPresent()) {
                nudgeSelectedCorner(client, 0, 0, -1);
                selectionCooldown = SELECTION_COOLDOWN_TICKS;
                return true;
            }
        }

        // Check corners and anchor
        OverlayData.ImageOverlay hitOverlay = null;
        int hitIndex = -1;
        double closestDist = Double.MAX_VALUE;

        for (OverlayData.ImageOverlay overlay : getOverlayStorage().getLoadedOverlays().values()) {
            if (!overlay.visible || !overlay.markersVisible) continue;

            for (int i = 0; i < 4; i++) {
                Vec3d c = overlay.corners[i];
                Box box = new Box(c.x - 0.15, c.y - 0.15, c.z - 0.15, c.x + 0.15, c.y + 0.15, c.z + 0.15);
                Optional<Vec3d> hit = box.raycast(eyePos, endPos);
                if (hit.isPresent()) {
                    double d = eyePos.squaredDistanceTo(hit.get());
                    if (d < closestDist) {
                        closestDist = d;
                        hitOverlay = overlay;
                        hitIndex = i;
                    }
                }
            }

            Vec3d a = overlay.anchor;
            Box box = new Box(a.x - 0.2, a.y - 0.2, a.z - 0.2, a.x + 0.2, a.y + 0.2, a.z + 0.2);
            Optional<Vec3d> hit = box.raycast(eyePos, endPos);
            if (hit.isPresent()) {
                double d = eyePos.squaredDistanceTo(hit.get());
                if (d < closestDist) {
                    closestDist = d;
                    hitOverlay = overlay;
                    hitIndex = 4;
                }
            }
        }

        if (hitOverlay != null) {
            selectionCooldown = SELECTION_COOLDOWN_TICKS;
            if (selectedOverlayCorner == hitOverlay && selectedCornerIndex == hitIndex) {
                selectedOverlayCorner = null;
                selectedCornerIndex = -1;
                notifyActionBar(client, "command.boshysbteutils.overlay.deselected");
            } else {
                selectedOverlayCorner = hitOverlay;
                selectedCornerIndex = hitIndex;
                String type = hitIndex == 4 ? "anchor" : OverlayData.cornerName(hitIndex);
                notifyActionBar(client, "command.boshysbteutils.overlay.selected", type, hitOverlay.displayName);
            }
            return true;
        }

        if (selectedOverlayCorner != null) {
            selectedOverlayCorner = null;
            selectedCornerIndex = -1;
            selectionCooldown = SELECTION_COOLDOWN_TICKS;
            notifyActionBar(client, "command.boshysbteutils.overlay.deselected");
            return true;
        }

        return false;
    }

    private void nudgeSelectedCorner(MinecraftClient client, int dx, int dy, int dz) {
        if (selectedOverlayCorner == null || selectedCornerIndex == -1) return;
        if (selectedCornerIndex == 4) {
            selectedOverlayCorner.anchor = selectedOverlayCorner.anchor.add(dx, dy, dz);
        } else {
            selectedOverlayCorner.corners[selectedCornerIndex] = selectedOverlayCorner.corners[selectedCornerIndex].add(dx, dy, dz);
        }
        getOverlayStorage().saveOverlay(selectedOverlayCorner);
        String type = selectedCornerIndex == 4 ? "anchor" : OverlayData.cornerName(selectedCornerIndex);
        notifyActionBar(client, "command.boshysbteutils.overlay.nudged", type, dx, dy, dz);
    }

    private void handleMarkerSelection(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        if (markersHidden) return;

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

                    if (!hasSelectedMarkerThisSession) {
                        sendFirstSelectionMessage(client);
                        hasSelectedMarkerThisSession = true;
                    } else {
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

        client.player.sendMessage(Text.literal("§7============= §aBoshy's BT-Utils §7============="), false);
        client.player.sendMessage(Text.literal(""), false);
        client.player.sendMessage(Text.translatable("command.boshysbteutils.selection.first_time.connect"), false);
        client.player.sendMessage(Text.literal(""), false);
        client.player.sendMessage(Text.translatable("command.boshysbteutils.selection.first_time.disconnect"), false);
        client.player.sendMessage(Text.literal(""), false);
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
            if (markersHidden) {
                if (!hideWarningShown) {
                    notifyError(client, "command.boshysbteutils.error.markers_hidden");
                    hideWarningShown = true;
                }
                return;
            }

            MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3d(newX, newY, newZ));

            if (config.enableAutoLineConnection) {
                MarkerData.handleAutoConnect(newMarker);
            }
        }
    }

    public static void hideAllMarkers() {
        if (markersHidden) return;

        hiddenMarkers.clear();
        hiddenMarkers.addAll(markers);
        hiddenConnections.clear();
        hiddenConnections.addAll(markerConnections);
        hiddenSelectedMarkers.clear();
        hiddenSelectedMarkers.addAll(selectedMarkers);
        hiddenLastAddedMarker = lastAddedMarker;

        markers.clear();
        markerConnections.clear();
        selectedMarkers.clear();
        lastAddedMarker = null;

        markersHidden = true;
        hideWarningShown = false;
    }

    public static void showAllMarkers() {
        if (!markersHidden) return;

        markers.clear();
        markers.addAll(hiddenMarkers);
        markerConnections.clear();
        markerConnections.addAll(hiddenConnections);
        selectedMarkers.clear();
        selectedMarkers.addAll(hiddenSelectedMarkers);
        lastAddedMarker = hiddenLastAddedMarker;

        hiddenMarkers.clear();
        hiddenConnections.clear();
        hiddenSelectedMarkers.clear();
        hiddenLastAddedMarker = null;

        markersHidden = false;
        hideWarningShown = false;
    }

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

    public static OverlayStorage getOverlayStorage() {
        return overlayStorage;
    }

    public static OverlayTextureManager getOverlayTextureManager() {
        return overlayTextureManager;
    }
}