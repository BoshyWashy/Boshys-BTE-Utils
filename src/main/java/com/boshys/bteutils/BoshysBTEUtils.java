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
import com.mojang.blaze3d.vertex.PoseStack;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

import java.util.*;

import com.boshys.bteutils.console.ConsoleMessageConfig;
import com.boshys.bteutils.console.ConsoleMessageDetector;

public class BoshysBTEUtils implements ClientModInitializer {

    public static BoshysBTEUtils INSTANCE;

    // Config instance - declared here to fix "Cannot resolve symbol 'config'" errors
    private static BoshysBTEUtilsConfig config;

    // Keybindings
    public static KeyMapping tpllKeybind;
    public static KeyMapping addMarkerKeybind;
    public static KeyMapping clearMarkersKeybind;
    public static KeyMapping selectMarkerKeybind;
    public static KeyMapping deleteMarkerKeybind;
    public static KeyMapping toggleOverlayMarkersKeybind;

    public static final KeyMapping.Category BTE_UTILS_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("boshysbteutils", "bteutils")
    );

    // Marker data
    public static final List<MarkerData.TeleportMarker> markers = new ArrayList<>();
    public static final List<MarkerData.MarkerConnection> markerConnections = new ArrayList<>();
    public static final Set<MarkerData.TeleportMarker> selectedMarkers = new HashSet<>();
    public static MarkerData.TeleportMarker lastAddedMarker = null;
    /** The last marker placed that auto-line connection should connect to. */
    public static MarkerData.TeleportMarker lastAutoConnectMarker = null;

    // Selected connections (for line deletion)
    public static final Set<MarkerData.MarkerConnection> selectedConnections = new HashSet<>();

    // Hidden markers storage
    public static final List<MarkerData.TeleportMarker> hiddenMarkers = new ArrayList<>();
    public static final List<MarkerData.MarkerConnection> hiddenConnections = new ArrayList<>();
    public static final Set<MarkerData.TeleportMarker> hiddenSelectedMarkers = new HashSet<>();
    public static final Set<MarkerData.MarkerConnection> hiddenSelectedConnections = new HashSet<>();
    public static MarkerData.TeleportMarker hiddenLastAddedMarker = null;
    public static boolean markersHidden = false;
    public static boolean hideWarningShown = false;

    // File tracking
    public static final Map<MarkerData.TeleportMarker, String> markerOrigins = new HashMap<>();
    public static final Map<MarkerData.TeleportMarker, Vec3> markerOriginalPositions = new HashMap<>();

    // Session state tracking for first-time messages
    public static boolean hasAddedMarkerThisSession = false;
    public static boolean hasSelectedMarkerThisSession = false;

    // Overlay selection state
    public static OverlayData.ImageOverlay selectedOverlayCorner = null;
    public static int selectedCornerIndex = -1; // 0-3 corners, 4 anchor

    // State
    private int selectionCooldown = 0;
    private static final int SELECTION_COOLDOWN_TICKS = 5;

    private double posXBeforeTpll = 0;
    private double posYBeforeTpll = 0;
    private double posZBeforeTpll = 0;
    private int tpllCooldownTicks = 0;
    private static final int TPLL_COOLDOWN_MAX = 60;
    private boolean waitingForTeleport = false;

    // Global teleport cooldown - shared across ALL detection methods to prevent duplicate markers
    private long lastTeleportMarkerTime = 0;
    private static final long TELEPORT_MARKER_COOLDOWN_MS = 1500;
    // Flag to prevent mixin from double-processing keybind-sent commands
    public static boolean keybindCommandBeingSent = false;

    // Manual TPLL WorldEdit lines state (queue-based for ordered execution)
    private boolean manualTpllWeActive = false;
    private boolean manualTpllWeFirstPoint = true;
    private int manualTpllWeCooldown = 0;
    private static final int MANUAL_TPLL_WE_COOLDOWN = 3;

    // Command queue for ordered WorldEdit line execution
    private final List<String> manualWeCommandQueue = new ArrayList<>();
    private int manualWeCommandIndex = 0;
    private boolean manualWeWaitingForCommand = false;
    private int manualWeCommandTickCounter = 0;
    private static final int MANUAL_WE_COMMAND_DELAY = 1; // 1 tick between commands


    private String lastCommandSent = "";
    private int commandCooldownTicks = 0;
    private static final int COMMAND_COOLDOWN_MAX = 5;

    // Components
    private MarkerStorage markerStorage;
    private KmlImportHandler kmlImportHandler;

    // Console message detection components
    private ConsoleMessageConfig consoleMessageConfig;
    private ConsoleMessageDetector consoleMessageDetector;

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

        // Initialize console message detection
        consoleMessageConfig = new ConsoleMessageConfig();
        consoleMessageDetector = new ConsoleMessageDetector(consoleMessageConfig);
        consoleMessageDetector.install();

        registerKeybindings();
        registerEvents();
        registerCommands();

        // CustomParticleRenderer now handles its own event registration internally
        CustomParticleRenderer.register();

        // Overlay renderer - 26.2: LevelRenderContext provides poseStack via context.poseStack()
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
            overlayRenderer.render(context.poseStack());
            overlayRenderer.endFrame();  // Must be called AFTER render() returns
        });
    }

    private void registerKeybindings() {
        tpllKeybind = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.boshysbteutils.tpll",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                BTE_UTILS_CATEGORY
        ));

        addMarkerKeybind = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.boshysbteutils.addmarker",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                BTE_UTILS_CATEGORY
        ));

        clearMarkersKeybind = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.boshysbteutils.clearmarkers",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                BTE_UTILS_CATEGORY
        ));

        selectMarkerKeybind = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.boshysbteutils.selectmarker",
                InputConstants.Type.MOUSE,
                GLFW.GLFW_MOUSE_BUTTON_RIGHT,
                BTE_UTILS_CATEGORY
        ));

        deleteMarkerKeybind = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.boshysbteutils.deletemarker",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_DELETE,
                BTE_UTILS_CATEGORY
        ));

        toggleOverlayMarkersKeybind = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.boshysbteutils.toggleoverlaymarkers",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                BTE_UTILS_CATEGORY
        ));
    }

    private void registerEvents() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            markerStorage.performAutosave();
            // Reset overlay temp hide state so overlays are shown on rejoin
            if (overlayStorage != null) {
                overlayStorage.resetTempHiddenState();
            }
            // Reset marker temp hide state
            if (markersHidden) {
                showAllMarkers();
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            hasAddedMarkerThisSession = false;
            hasSelectedMarkerThisSession = false;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.level == null) return;

            kmlImportHandler.tick(client);
            markerStorage.tickAutosave();
            tickManualWeCommandQueue(client);

            // Process console message detector pending markers
            if (consoleMessageDetector != null) {
                consoleMessageDetector.processPendingMarkers(client);
            }

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
                handleMarkerAndLineSelection(client);
            }
        });
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            new CommandRegistry(this, markerStorage, kmlImportHandler).register(dispatcher);
        });
    }

    private void handleKeybinds(Minecraft client) {
        while (tpllKeybind.consumeClick()) {
            try {
                String clip = getClipboard(client);
                if (clip == null || clip.isEmpty()) {
                    notifyError(client, "command.boshysbteutils.error.clipboard_empty");
                    continue;
                }

                // Check if keybind markers are enabled (DISABLED or MANUAL_ONLY = no keybind markers)
                BoshysBTEUtilsConfig.TpllMarkerMode mode = config.tpllMarkerMode;
                if (mode == BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED || mode == BoshysBTEUtilsConfig.TpllMarkerMode.MANUAL_ONLY) {
                    // Keybind markers disabled - just send the command without marker setup
                    String commandNoSlash = config.commandPrefix + " " + clip.trim();
                    keybindCommandBeingSent = true;
                    try {
                        client.player.connection.sendCommand(commandNoSlash);
                    } finally {
                        keybindCommandBeingSent = false;
                    }
                    continue;
                }

                // Keybind markers enabled (KEYBIND_AND_MANUAL or KEYBIND_ONLY)
                if (config.enableMarkers && (mode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_AND_MANUAL || mode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY)) {
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
                keybindCommandBeingSent = true;
                try {
                    client.player.connection.sendCommand(commandNoSlash);
                } finally {
                    keybindCommandBeingSent = false;
                }

            } catch (Throwable t) {
                notifyError(client, "command.boshysbteutils.error.clipboard_error");
                waitingForTeleport = false;
            }
        }

        while (addMarkerKeybind.consumeClick()) {
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

            MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3(x, y, z));

            if (config.enableAutoLineConnection) {
                MarkerData.handleAutoConnect(newMarker);
            }

            if (!hasAddedMarkerThisSession) {
                sendFirstMarkerMessage(client);
                hasAddedMarkerThisSession = true;
            }
            new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                    Component.translatable("command.boshysbteutils.marker.added_actionbar")
                            .withStyle(net.minecraft.ChatFormatting.GREEN)
            ).handle(client.player.connection);
        }

        while (clearMarkersKeybind.consumeClick()) {
            int cacheCount = markerStorage.getCacheMarkerCount();
            if (config.enableClearConfirmation && cacheCount > config.clearConfirmLimit) {
                markerStorage.setPendingClear(cacheCount, false);
                notifyError(client, "command.boshysbteutils.marker.confirm.required", cacheCount);
                continue;
            }
            int count = markerStorage.clearCacheMarkersOnly();
            notifyActionBar(client, "command.boshysbteutils.marker.cleared", count);
        }

        while (deleteMarkerKeybind.consumeClick()) {
            if (markersHidden) {
                if (!hideWarningShown) {
                    notifyError(client, "command.boshysbteutils.error.markers_hidden");
                    hideWarningShown = true;
                }
                continue;
            }

            // Delete selected connections first (lines)
            if (!selectedConnections.isEmpty()) {
                int count = selectedConnections.size();
                for (MarkerData.MarkerConnection conn : new ArrayList<>(selectedConnections)) {
                    MarkerData.disconnectMarkers(conn.marker1, conn.marker2);
                }
                selectedConnections.clear();
                notifyActionBar(client, "command.boshysbteutils.line.deleted", count);
                continue;
            }

            // Then delete selected markers
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

        while (toggleOverlayMarkersKeybind.consumeClick()) {
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

    private static void sendFirstMarkerMessage(Minecraft client) {
        if (client.player == null) return;

        client.player.sendSystemMessage(Component.literal("§7============= §aBoshy's BT-Utils §7============="));
        client.player.sendSystemMessage(Component.literal(""));
        client.player.sendSystemMessage(Component.translatable("command.boshysbteutils.marker.first_time.select"));
        client.player.sendSystemMessage(Component.literal(""));
        client.player.sendSystemMessage(Component.translatable("command.boshysbteutils.marker.first_time.multiselect"));
        client.player.sendSystemMessage(Component.literal(""));
        client.player.sendSystemMessage(Component.translatable("command.boshysbteutils.marker.first_time.move"));
    }

    private void handleTpllTeleportDetection(Minecraft client) {
        if (kmlImportHandler.isImporting()) {
            return;
        }

        // Decrement manual TPLL WE lines cooldown
        if (manualTpllWeCooldown > 0) {
            manualTpllWeCooldown--;
        }

        if (!waitingForTeleport && commandCooldownTicks == 0) return;

        if (tpllCooldownTicks > 0) {
            tpllCooldownTicks--;
        } else if (waitingForTeleport) {
            waitingForTeleport = false;
            System.out.println("[Boshys-bt-utils] TPLL cooldown expired, teleport detection cancelled");
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
            System.out.println("[Boshys-bt-utils] Movement detected! distanceMoved=" + distanceMoved + " | waitingForTeleport=" + waitingForTeleport + " | commandCooldown=" + commandCooldownTicks);
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

                // Check global cooldown to prevent duplicates from chat/console detection
                if (!tryPlaceTeleportMarker()) {
                    System.out.println("[Boshys-bt-utils] Movement-based marker suppressed by global cooldown (already placed by chat/console detection)");
                    waitingForTeleport = false;
                    commandCooldownTicks = 0;
                    lastCommandSent = "";
                    return;
                }

                MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3(currentX, currentY, currentZ));
                System.out.println("[Boshys-bt-utils] Marker placed at: " + currentX + ", " + currentY + ", " + currentZ);

                if (config.enableAutoLineConnection) {
                    MarkerData.handleAutoConnect(newMarker);
                }

                // Handle auto WorldEdit lines on TPLL
                if (config.enableAutoWorldEditLinesOnTpll && client.player != null) {
                    handleManualTpllWeLines(client);
                }

                waitingForTeleport = false;
                commandCooldownTicks = 0;
                lastCommandSent = "";
            }
        }
    }

    private boolean handleOverlayCornerSelection(Minecraft client) {
        if (client.player == null || client.level == null) return false;

        ItemStack mainHandStack = client.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!mainHandStack.isEmpty()) return false;

        if (selectionCooldown > 0) return false;
        if (!selectMarkerKeybind.isDown()) return false;

        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 lookVec = client.player.getLookAngle();
        double reachDistance = 5.0;
        Vec3 endPos = new Vec3(eyePos.x + lookVec.x * reachDistance, eyePos.y + lookVec.y * reachDistance, eyePos.z + lookVec.z * reachDistance);

        // Check nudge cubes first if something is selected
        if (selectedOverlayCorner != null && selectedCornerIndex != -1) {
            Vec3 markerPos = selectedCornerIndex == 4
                    ? selectedOverlayCorner.anchor
                    : selectedOverlayCorner.corners[selectedCornerIndex];

            double cubeOffset = 1.5;
            double cubeHalf = 0.15;

            AABB pxBox = new AABB(markerPos.x + cubeOffset - cubeHalf, markerPos.y - cubeHalf, markerPos.z - cubeHalf,
                    markerPos.x + cubeOffset + cubeHalf, markerPos.y + cubeHalf, markerPos.z + cubeHalf);
            if (pxBox.clip(eyePos, endPos).isPresent()) {
                nudgeSelectedCorner(client, 1, 0, 0);
                selectionCooldown = SELECTION_COOLDOWN_TICKS;
                return true;
            }

            AABB nxBox = new AABB(markerPos.x - cubeOffset - cubeHalf, markerPos.y - cubeHalf, markerPos.z - cubeHalf,
                    markerPos.x - cubeOffset + cubeHalf, markerPos.y + cubeHalf, markerPos.z + cubeHalf);
            if (nxBox.clip(eyePos, endPos).isPresent()) {
                nudgeSelectedCorner(client, -1, 0, 0);
                selectionCooldown = SELECTION_COOLDOWN_TICKS;
                return true;
            }

            AABB pzBox = new AABB(markerPos.x - cubeHalf, markerPos.y - cubeHalf, markerPos.z + cubeOffset - cubeHalf,
                    markerPos.x + cubeHalf, markerPos.y + cubeHalf, markerPos.z + cubeOffset + cubeHalf);
            if (pzBox.clip(eyePos, endPos).isPresent()) {
                nudgeSelectedCorner(client, 0, 0, 1);
                selectionCooldown = SELECTION_COOLDOWN_TICKS;
                return true;
            }

            AABB nzBox = new AABB(markerPos.x - cubeHalf, markerPos.y - cubeHalf, markerPos.z - cubeOffset - cubeHalf,
                    markerPos.x + cubeHalf, markerPos.y + cubeHalf, markerPos.z - cubeOffset + cubeHalf);
            if (nzBox.clip(eyePos, endPos).isPresent()) {
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
                Vec3 c = overlay.corners[i];
                AABB box = new AABB(c.x - 0.15, c.y - 0.15, c.z - 0.15, c.x + 0.15, c.y + 0.15, c.z + 0.15);
                Optional<Vec3> hit = box.clip(eyePos, endPos);
                if (hit.isPresent()) {
                    double hx = hit.get().x, hy = hit.get().y, hz = hit.get().z;
                    double d = (eyePos.x - hx) * (eyePos.x - hx)
                            + (eyePos.y - hy) * (eyePos.y - hy)
                            + (eyePos.z - hz) * (eyePos.z - hz);
                    if (d < closestDist) {
                        closestDist = d;
                        hitOverlay = overlay;
                        hitIndex = i;
                    }
                }
            }

            Vec3 a = overlay.anchor;
            AABB box = new AABB(a.x - 0.2, a.y - 0.2, a.z - 0.2, a.x + 0.2, a.y + 0.2, a.z + 0.2);
            Optional<Vec3> hit = box.clip(eyePos, endPos);
            if (hit.isPresent()) {
                double hx = hit.get().x, hy = hit.get().y, hz = hit.get().z;
                double d = (eyePos.x - hx) * (eyePos.x - hx)
                        + (eyePos.y - hy) * (eyePos.y - hy)
                        + (eyePos.z - hz) * (eyePos.z - hz);
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

    private void nudgeSelectedCorner(Minecraft client, int dx, int dy, int dz) {
        if (selectedOverlayCorner == null || selectedCornerIndex == -1) return;
        if (selectedCornerIndex == 4) {
            selectedOverlayCorner.anchor = new Vec3(selectedOverlayCorner.anchor.x + dx, selectedOverlayCorner.anchor.y + dy, selectedOverlayCorner.anchor.z + dz);
        } else {
            selectedOverlayCorner.corners[selectedCornerIndex] = selectedOverlayCorner.corners[selectedCornerIndex].add(dx, dy, dz);
        }
        getOverlayStorage().saveOverlay(selectedOverlayCorner);
        String type = selectedCornerIndex == 4 ? "anchor" : OverlayData.cornerName(selectedCornerIndex);
        notifyActionBar(client, "command.boshysbteutils.overlay.nudged", type, dx, dy, dz);
    }

    /**
     * Handles selection of both markers and line connections.
     * Lines can be selected by raycasting against them when nearby.
     */
    private void handleMarkerAndLineSelection(Minecraft client) {
        if (client.player == null || client.level == null) return;

        if (markersHidden) return;

        ItemStack mainHandStack = client.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!mainHandStack.isEmpty()) {
            return;
        }

        if (selectionCooldown > 0) return;
        if (!selectMarkerKeybind.isDown()) return;

        selectionCooldown = SELECTION_COOLDOWN_TICKS;

        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 lookVec = client.player.getLookAngle();
        double reachDistance = 5.0;

        Vec3 endPos = new Vec3(eyePos.x + lookVec.x * reachDistance, eyePos.y + lookVec.y * reachDistance, eyePos.z + lookVec.z * reachDistance);

        long windowHandle = GLFW.glfwGetCurrentContext();
        boolean ctrlPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;

        boolean shiftPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        boolean multiSelect = ctrlPressed || shiftPressed;

        // First, try to select a marker
        MarkerData.TeleportMarker hitMarker = null;
        double closestMarkerDist = Double.MAX_VALUE;

        for (MarkerData.TeleportMarker marker : markers) {
            // Use a consistent hitbox size regardless of marker scale for reliable selection
            // The visual scale can vary, but the selection hitbox stays reasonable
            float hitboxScale = Math.max(0.2f, marker.scale * 1.5f);
            AABB hitbox = new AABB(
                    marker.position.x - hitboxScale, marker.position.y - hitboxScale, marker.position.z - hitboxScale,
                    marker.position.x + hitboxScale, marker.position.y + hitboxScale, marker.position.z + hitboxScale
            );

            Optional<Vec3> hitResult = hitbox.clip(eyePos, endPos);
            if (hitResult.isPresent()) {
                double hx = hitResult.get().x, hy = hitResult.get().y, hz = hitResult.get().z;
                double dist = (eyePos.x - hx) * (eyePos.x - hx)
                        + (eyePos.y - hy) * (eyePos.y - hy)
                        + (eyePos.z - hz) * (eyePos.z - hz);
                if (dist < closestMarkerDist) {
                    closestMarkerDist = dist;
                    hitMarker = marker;
                }
            }
        }

        if (hitMarker != null) {
            // Clear line selection when selecting a marker
            selectedConnections.clear();

            if (selectedMarkers.contains(hitMarker)) {
                selectedMarkers.remove(hitMarker);
                if (lastAutoConnectMarker == hitMarker) {
                    lastAutoConnectMarker = null;
                }
                if (selectedMarkers.isEmpty()) {
                    notifyActionBar(client, "command.boshysbteutils.marker.deselected.none");
                } else {
                    notifyActionBar(client, "command.boshysbteutils.marker.deselected", selectedMarkers.size());
                }
            } else {
                if (selectedMarkers.size() == 1 && !multiSelect) {
                    MarkerData.TeleportMarker selectedMarker = selectedMarkers.iterator().next();

                    if (selectedMarker == hitMarker) {
                        // This shouldn't happen due to the contains check above, but handle it
                        selectedMarkers.clear();
                        if (lastAutoConnectMarker == hitMarker) {
                            lastAutoConnectMarker = null;
                        }
                        notifyActionBar(client, "command.boshysbteutils.marker.deselected.single");
                    } else if (MarkerData.areMarkersConnected(selectedMarker, hitMarker)) {
                        MarkerData.disconnectMarkers(selectedMarker, hitMarker);
                        selectedMarkers.clear();
                        lastAutoConnectMarker = null;
                        notifyActionBar(client, "command.boshysbteutils.marker.disconnected");
                    } else {
                        MarkerData.connectMarkers(selectedMarker, hitMarker);
                        selectedMarkers.clear();
                        lastAutoConnectMarker = null;
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
                    }
                    if (selectedMarkers.size() == 1) {
                        new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                                Component.translatable("command.boshysbteutils.marker.selected_actionbar.single")
                                        .withStyle(net.minecraft.ChatFormatting.GREEN)
                        ).handle(client.player.connection);
                    } else {
                        new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                                Component.translatable("command.boshysbteutils.marker.selected_actionbar.multiple", selectedMarkers.size())
                                        .withStyle(net.minecraft.ChatFormatting.GREEN)
                        ).handle(client.player.connection);
                    }
                }
            }
            return;
        }

        // If no marker hit, try to select a line connection
        MarkerData.MarkerConnection hitConnection = null;
        double closestLineDistSq = Double.MAX_VALUE;
        double lineHitThresholdSq = 0.15 * 0.15; // Tight threshold for precise selection

        for (MarkerData.MarkerConnection conn : markerConnections) {
            // Compute closest distance from the look ray to the line segment
            Double distSq = rayToLineSegmentDistSq(eyePos, endPos, conn.marker1.position, conn.marker2.position);
            if (distSq != null && distSq < lineHitThresholdSq && distSq < closestLineDistSq) {
                closestLineDistSq = distSq;
                hitConnection = conn;
            }
        }

        if (hitConnection != null) {
            if (selectedConnections.contains(hitConnection)) {
                // Toggle off if already selected (even in multi-select mode)
                selectedConnections.remove(hitConnection);
                if (!multiSelect) {
                    selectedMarkers.clear();
                }
                notifyActionBar(client, "command.boshysbteutils.line.deselected");
            } else {
                if (!multiSelect) {
                    selectedConnections.clear();
                    selectedMarkers.clear();
                }
                selectedConnections.add(hitConnection);
                notifyActionBar(client, "command.boshysbteutils.line.selected");
            }
            return;
        }

        // If nothing was hit and not multi-selecting, deselect everything
        if (!multiSelect) {
            if (!selectedMarkers.isEmpty() || !selectedConnections.isEmpty()) {
                selectedMarkers.clear();
                selectedConnections.clear();
                notifyActionBar(client, "command.boshysbteutils.marker.deselected.none");
            }
        }
    }

    /**
     * Computes the squared distance from a ray (eyePos -> endPos) to a line segment.
     * Returns null if the closest approach is outside the segment or ray bounds.
     */
    private Double rayToLineSegmentDistSq(Vec3 rayStart, Vec3 rayEnd, Vec3 lineStart, Vec3 lineEnd) {
        Vec3 rayDir = new Vec3(rayEnd.x - rayStart.x, rayEnd.y - rayStart.y, rayEnd.z - rayStart.z);
        Vec3 lineDir = new Vec3(lineEnd.x - lineStart.x, lineEnd.y - lineStart.y, lineEnd.z - lineStart.z);
        Vec3 diff = new Vec3(rayStart.x - lineStart.x, rayStart.y - lineStart.y, rayStart.z - lineStart.z);

        double rayLenSq = rayDir.x * rayDir.x + rayDir.y * rayDir.y + rayDir.z * rayDir.z;
        double lineLenSq = lineDir.x * lineDir.x + lineDir.y * lineDir.y + lineDir.z * lineDir.z;

        if (rayLenSq < 0.0001 || lineLenSq < 0.0001) return null;

        double a = rayDir.x * rayDir.x + rayDir.y * rayDir.y + rayDir.z * rayDir.z;   // always >= 0
        double b = rayDir.x * lineDir.x + rayDir.y * lineDir.y + rayDir.z * lineDir.z;
        double c = lineDir.x * lineDir.x + lineDir.y * lineDir.y + lineDir.z * lineDir.z; // always >= 0
        double d = rayDir.x * diff.x + rayDir.y * diff.y + rayDir.z * diff.z;
        double e = lineDir.x * diff.x + lineDir.y * diff.y + lineDir.z * diff.z;

        double denom = a * c - b * b;

        double s, t;
        if (denom < 0.0001) {
            // Lines are nearly parallel - pick the best endpoint
            s = 0.0;
            t = Math.max(0.0, Math.min(1.0, e / c));
        } else {
            s = Math.max(0.0, Math.min(1.0, (b * e - c * d) / denom));
            t = (b * s + e) / c;
            if (t < 0.0) {
                t = 0.0;
                s = Math.max(0.0, Math.min(1.0, -d / a));
            } else if (t > 1.0) {
                t = 1.0;
                s = Math.max(0.0, Math.min(1.0, (b - d) / a));
            }
        }

        Vec3 closestOnRay = new Vec3(rayStart.x + rayDir.x * s, rayStart.y + rayDir.y * s, rayStart.z + rayDir.z * s);
        Vec3 closestOnLine = new Vec3(lineStart.x + lineDir.x * t, lineStart.y + lineDir.y * t, lineStart.z + lineDir.z * t);
        return (closestOnRay.x - closestOnLine.x) * (closestOnRay.x - closestOnLine.x)
                + (closestOnRay.y - closestOnLine.y) * (closestOnRay.y - closestOnLine.y)
                + (closestOnRay.z - closestOnLine.z) * (closestOnRay.z - closestOnLine.z);
    }

    private void sendFirstSelectionMessage(Minecraft client) {
        if (client.player == null) return;

        client.player.sendSystemMessage(Component.literal("§7============= §aBoshy's BT-Utils §7============="));
        client.player.sendSystemMessage(Component.literal(""));
        client.player.sendSystemMessage(Component.translatable("command.boshysbteutils.selection.first_time.connect"));
        client.player.sendSystemMessage(Component.literal(""));
        client.player.sendSystemMessage(Component.translatable("command.boshysbteutils.selection.first_time.disconnect"));
        client.player.sendSystemMessage(Component.literal(""));
        client.player.sendSystemMessage(Component.translatable("command.boshysbteutils.selection.first_time.edit_colour"));
    }

    private String getClipboard(Minecraft client) {
        try {
            String data = client.keyboardHandler.getClipboard();
            if (data == null) return null;

            String cleaned = data;
            cleaned = cleaned.replace("\r\n", "\n").replace("\r", "\n").trim();
            int nl = cleaned.indexOf('\n');
            if (nl >= 0) cleaned = cleaned.substring(0, nl).trim();

            if (config.formatCoordinates) {
                cleaned = cleaned.replaceAll("\s*,\s*", ",").replaceAll("\s+", " ");
            }

            return cleaned.isEmpty() ? null : cleaned;
        } catch (Throwable t) {
            return null;
        }
    }

    private void notifyError(Minecraft client, String translationKey, Object... args) {
        if (client == null || client.player == null) return;
        client.player.sendSystemMessage(Component.translatable(translationKey, args).withStyle(net.minecraft.ChatFormatting.RED));
    }

    private void notifyActionBar(Minecraft client, String translationKey, Object... args) {
        if (client == null || client.player == null) return;
        new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                Component.translatable(translationKey, args)
                        .withStyle(net.minecraft.ChatFormatting.GREEN)
        ).handle(client.player.connection);
    }

    /**
     * Called from the ClientPlayNetworkHandlerMixin when a command is sent from the client.
     * This is the ONLY place we detect manual /tpll commands - it intercepts the packet
     * going from client to server, so it works regardless of server chat plugins.
     */
    public void onCommandSent(String command) {
        System.out.println("[Boshys-bt-utils] onCommandSent called with: " + command);

        if (kmlImportHandler.isImporting()) {
            System.out.println("[Boshys-bt-utils] KML importing, skipping");
            return;
        }

        if (!config.enableMarkers) {
            System.out.println("[Boshys-bt-utils] Markers disabled, skipping");
            return;
        }

        if (markersHidden) {
            System.out.println("[Boshys-bt-utils] Markers are temporarily hidden, skipping TPLL marker detection");
            return;
        }

        BoshysBTEUtilsConfig.TpllMarkerMode mode = config.tpllMarkerMode;
        System.out.println("[Boshys-bt-utils] Current TpllMarkerMode: " + mode);

        if (mode == BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED || mode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY) {
            System.out.println("[Boshys-bt-utils] Mode is DISABLED or KEYBIND_ONLY, skipping manual detection");
            return;
        }

        String lowerCmd = command.toLowerCase().trim();
        String[] parts = lowerCmd.split("\s+", 2);
        String cmdName = parts[0].replace("/", "");

        System.out.println("[Boshys-bt-utils] Parsed command name: " + cmdName + " | prefix: " + config.commandPrefix.toLowerCase());

        if (cmdName.equals("tpll") || cmdName.equals(config.commandPrefix.toLowerCase())) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                posXBeforeTpll = client.player.getX();
                posYBeforeTpll = client.player.getY();
                posZBeforeTpll = client.player.getZ();
                lastCommandSent = command;
                commandCooldownTicks = COMMAND_COOLDOWN_MAX;
                waitingForTeleport = true;
                tpllCooldownTicks = TPLL_COOLDOWN_MAX;
                System.out.println("[Boshys-bt-utils] Manual TPLL detected! Setup complete. waitingForTeleport=true, cooldown=" + TPLL_COOLDOWN_MAX);
            } else {
                System.out.println("[Boshys-bt-utils] Client player is null, cannot setup detection");
            }
        } else {
            System.out.println("[Boshys-bt-utils] Command does not match tpll or prefix, ignoring");
        }
    }

    public void onPlayerTeleported(Minecraft client, double oldX, double oldY, double oldZ, double newX, double newY, double newZ) {
        if (kmlImportHandler.isImporting()) {
            kmlImportHandler.onMarkerPlaced(client);
            return;
        }

        if (markersHidden) {
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

            MarkerData.TeleportMarker newMarker = MarkerData.addMarker(new Vec3(newX, newY, newZ));

            if (config.enableAutoLineConnection) {
                MarkerData.handleAutoConnect(newMarker);
            }
        }
    }

    /**
     * Handles automatic WorldEdit line creation on each manual/keybind TPLL teleport.
     * Sequence:
     * - First TPLL: //sel, //sel cuboid, //pos1
     * - Second TPLL: //pos2, //line <block>, //pos1
     * - Third+ TPLL: same as second
     */
    private void handleManualTpllWeLines(Minecraft client) {
        if (client.player == null) return;
        if (manualTpllWeCooldown > 0) return;

        String block = config.worldEditLineBlock;

        if (!manualTpllWeActive) {
            // First TPLL ever with this feature - queue full setup + first point
            manualTpllWeActive = true;
            manualTpllWeFirstPoint = true;

            // Build command queue: //sel -> //sel cuboid -> //pos1
            manualWeCommandQueue.clear();
            manualWeCommandQueue.add("/sel");
            manualWeCommandQueue.add("/sel cuboid");
            manualWeCommandQueue.add("/pos1");
            manualWeCommandIndex = 0;
            manualWeWaitingForCommand = true;
            manualWeCommandTickCounter = 0;

            manualTpllWeCooldown = MANUAL_TPLL_WE_COOLDOWN;
            System.out.println("[Boshys-bt-utils] Manual TPLL WE: First TPLL - queued //sel, //sel cuboid, //pos1");
        } else if (manualTpllWeFirstPoint) {
            // Second TPLL - queue: //pos2 -> //line <block> -> //pos1
            manualTpllWeFirstPoint = false;

            manualWeCommandQueue.clear();
            manualWeCommandQueue.add("/pos2");
            manualWeCommandQueue.add("/line " + block);
            manualWeCommandQueue.add("/pos1");
            manualWeCommandIndex = 0;
            manualWeWaitingForCommand = true;
            manualWeCommandTickCounter = 0;

            manualTpllWeCooldown = MANUAL_TPLL_WE_COOLDOWN;
            System.out.println("[Boshys-bt-utils] Manual TPLL WE: Second TPLL - queued //pos2, //line " + block + ", //pos1");
        } else {
            // Third+ TPLL - queue: //pos2 -> //line <block> -> //pos1
            manualWeCommandQueue.clear();
            manualWeCommandQueue.add("/pos2");
            manualWeCommandQueue.add("/line " + block);
            manualWeCommandQueue.add("/pos1");
            manualWeCommandIndex = 0;
            manualWeWaitingForCommand = true;
            manualWeCommandTickCounter = 0;

            manualTpllWeCooldown = MANUAL_TPLL_WE_COOLDOWN;
            System.out.println("[Boshys-bt-utils] Manual TPLL WE: Nth TPLL - queued //pos2, //line " + block + ", //pos1");
        }
    }

    /**
     * Processes the manual WorldEdit command queue with 1-tick delays between commands.
     * Called from the client tick event.
     */
    private void tickManualWeCommandQueue(Minecraft client) {
        if (!manualWeWaitingForCommand || client.player == null) return;

        if (manualWeCommandTickCounter > 0) {
            manualWeCommandTickCounter--;
            return;
        }

        if (manualWeCommandIndex >= manualWeCommandQueue.size()) {
            manualWeWaitingForCommand = false;
            manualWeCommandIndex = 0;
            return;
        }

        String command = manualWeCommandQueue.get(manualWeCommandIndex);
        manualWeCommandIndex++;

        client.player.connection.sendCommand(command);
        manualWeCommandTickCounter = MANUAL_WE_COMMAND_DELAY;

        System.out.println("[Boshys-bt-utils] Manual TPLL WE: Sent command " + manualWeCommandIndex + "/" + manualWeCommandQueue.size() + ": " + command);
    }

    /**
     * Resets the manual TPLL WorldEdit lines sequence.
     * Called by /boshys-bt-utils resetManualTpllLinesSequence
     */
    public void resetManualTpllWeLinesSequence() {
        manualTpllWeActive = false;
        manualTpllWeFirstPoint = true;
        manualTpllWeCooldown = 0;
        manualWeCommandQueue.clear();
        manualWeCommandIndex = 0;
        manualWeWaitingForCommand = false;
        manualWeCommandTickCounter = 0;
        System.out.println("[Boshys-bt-utils] Manual TPLL WE lines sequence reset");
    }

    /**
     * Resets the auto WorldEdit lines state. Called when markers are cleared or hidden.
     */
    public void resetAutoWeLinesState() {
        resetManualTpllWeLinesSequence();
    }

    public static void hideAllMarkers() {
        if (markersHidden) return;

        hiddenMarkers.clear();
        hiddenMarkers.addAll(markers);
        hiddenConnections.clear();
        hiddenConnections.addAll(markerConnections);
        hiddenSelectedMarkers.clear();
        hiddenSelectedMarkers.addAll(selectedMarkers);
        hiddenSelectedConnections.clear();
        hiddenSelectedConnections.addAll(selectedConnections);
        hiddenLastAddedMarker = lastAddedMarker;

        markers.clear();
        markerConnections.clear();
        selectedMarkers.clear();
        selectedConnections.clear();
        lastAddedMarker = null;
        lastAutoConnectMarker = null;

        // Reset manual TPLL WE lines state when markers are hidden
        if (INSTANCE != null) {
            INSTANCE.resetAutoWeLinesState();
            INSTANCE.resetTeleportMarkerCooldown();
        }

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
        selectedConnections.clear();
        selectedConnections.addAll(hiddenSelectedConnections);
        lastAddedMarker = hiddenLastAddedMarker;

        hiddenMarkers.clear();
        hiddenConnections.clear();
        hiddenSelectedMarkers.clear();
        hiddenSelectedConnections.clear();
        hiddenLastAddedMarker = null;

        // Reset manual TPLL WE lines state when markers are shown
        if (INSTANCE != null) {
            INSTANCE.resetAutoWeLinesState();
        }

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

    public ConsoleMessageConfig getConsoleMessageConfig() {
        return consoleMessageConfig;
    }

    public ConsoleMessageDetector getConsoleMessageDetector() {
        return consoleMessageDetector;
    }

    /**
     * Handles auto WorldEdit lines triggered from console message detection.
     * This is called from ConsoleMessageDetector when a teleport message is detected.
     */
    public void handleAutoWeLinesFromConsole(Minecraft client) {
        if (client.player == null) return;
        if (manualTpllWeCooldown > 0) return;

        String block = config.worldEditLineBlock;

        if (!manualTpllWeActive) {
            manualTpllWeActive = true;
            manualTpllWeFirstPoint = true;

            manualWeCommandQueue.clear();
            manualWeCommandQueue.add("/sel");
            manualWeCommandQueue.add("/sel cuboid");
            manualWeCommandQueue.add("/pos1");
            manualWeCommandIndex = 0;
            manualWeWaitingForCommand = true;
            manualWeCommandTickCounter = 0;

            manualTpllWeCooldown = MANUAL_TPLL_WE_COOLDOWN;
            System.out.println("[Boshys-bt-utils] Console WE: First detection - queued setup + pos1");
        } else if (manualTpllWeFirstPoint) {
            manualTpllWeFirstPoint = false;

            manualWeCommandQueue.clear();
            manualWeCommandQueue.add("/pos2");
            manualWeCommandQueue.add("/line " + block);
            manualWeCommandQueue.add("/pos1");
            manualWeCommandIndex = 0;
            manualWeWaitingForCommand = true;
            manualWeCommandTickCounter = 0;

            manualTpllWeCooldown = MANUAL_TPLL_WE_COOLDOWN;
            System.out.println("[Boshys-bt-utils] Console WE: Second detection - queued pos2, line, pos1");
        } else {
            manualWeCommandQueue.clear();
            manualWeCommandQueue.add("/pos2");
            manualWeCommandQueue.add("/line " + block);
            manualWeCommandQueue.add("/pos1");
            manualWeCommandIndex = 0;
            manualWeWaitingForCommand = true;
            manualWeCommandTickCounter = 0;

            manualTpllWeCooldown = MANUAL_TPLL_WE_COOLDOWN;
            System.out.println("[Boshys-bt-utils] Console WE: Nth detection - queued pos2, line, pos1");
        }
    }

    // ------------------------------------------------------------------
    // Console-based teleport detection
    // Called from ConsoleMessageDetector when a pattern is matched.
    // Sets up the same movement-based detection that the keybind uses.
    // ------------------------------------------------------------------

    /**
     * Called from ConsoleMessageDetector when a "Teleported to" pattern is detected
     * in console output. Saves the current position and enables movement-based detection
     * so the marker is placed AFTER the player actually arrives.
     */
    public void triggerConsoleTeleportDetection(Minecraft client) {
        if (client.player == null) return;
        if (markersHidden) return;
        if (!config.enableMarkers) return;

        // Check global cooldown to prevent duplicates
        if (isTeleportMarkerOnCooldown()) {
            System.out.println("[Boshys-bt-utils] Console teleport detection suppressed by global cooldown");
            return;
        }

        // Save current position (before teleport completes)
        posXBeforeTpll = client.player.getX();
        posYBeforeTpll = client.player.getY();
        posZBeforeTpll = client.player.getZ();

        // Enable movement-based detection (same as keybind)
        waitingForTeleport = true;
        tpllCooldownTicks = TPLL_COOLDOWN_MAX;
        commandCooldownTicks = COMMAND_COOLDOWN_MAX;
        lastCommandSent = "console-detection";

        System.out.println("[Boshys-bt-utils] Console teleport detection triggered. Waiting for movement...");
    }

    // ------------------------------------------------------------------
    // Global teleport marker cooldown - prevents duplicate markers from
    // multiple detection methods (command packet, chat mixin, console)
    // firing for the same teleport event.
    // ------------------------------------------------------------------

    /**
     * Checks if enough time has passed since the last teleport marker was placed.
     * If not, returns false and the caller should skip placing a marker.
     * If yes, updates the timestamp and returns true.
     */
    public boolean tryPlaceTeleportMarker() {
        long now = System.currentTimeMillis();
        if (now - lastTeleportMarkerTime < TELEPORT_MARKER_COOLDOWN_MS) {
            return false; // Too soon - duplicate suppressed
        }
        lastTeleportMarkerTime = now;
        return true;
    }

    /**
     * Returns true if the teleport marker cooldown is currently active.
     */
    public boolean isTeleportMarkerOnCooldown() {
        return System.currentTimeMillis() - lastTeleportMarkerTime < TELEPORT_MARKER_COOLDOWN_MS;
    }

    /**
     * Resets the teleport marker cooldown. Call when markers are cleared/hidden.
     */
    public void resetTeleportMarkerCooldown() {
        lastTeleportMarkerTime = 0;
    }
}