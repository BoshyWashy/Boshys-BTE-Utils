package com.boshys.bteutils;

import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.boshys.bteutils.render.CustomParticleRenderer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.CompletableFuture;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

public class BoshysBTEUtils implements ClientModInitializer {

    public static BoshysBTEUtils INSTANCE;

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

    // Saved markers system
    public static final Map<String, SavedMarkerFile> loadedFiles = new HashMap<>();
    public static final Set<String> hiddenFiles = new HashSet<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path markersSavePath;

    // Cooldown for marker selection (in ticks)
    private static int selectionCooldown = 0;
    private static final int SELECTION_COOLDOWN_TICKS = 5;

    // TPLL tracking variables
    private double posXBeforeTpll = 0;
    private double posYBeforeTpll = 0;
    private double posZBeforeTpll = 0;
    private int tpllCooldownTicks = 0;
    private static final int TPLL_COOLDOWN_MAX = 60; // 3 seconds at 20 ticks per second
    private boolean waitingForTeleport = false;

    // Track last command sent to detect manual TPLL
    private String lastCommandSent = "";
    private int commandCooldownTicks = 0;
    private static final int COMMAND_COOLDOWN_MAX = 5; // 5 ticks to match command to teleport

    // Suggestion providers for filenames
    private static final SuggestionProvider<FabricClientCommandSource> SAVED_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.supplyAsync(() -> suggestSavedFiles(builder, false));
    };

    private static final SuggestionProvider<FabricClientCommandSource> LOADED_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.supplyAsync(() -> suggestLoadedFiles(builder));
    };

    private static final SuggestionProvider<FabricClientCommandSource> ALL_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.supplyAsync(() -> suggestSavedFiles(builder, true));
    };

    private static Suggestions suggestSavedFiles(SuggestionsBuilder builder, boolean includeAll) {
        String remaining = builder.getRemaining().toLowerCase();
        Path savePath = getMarkersSavePath();
        File dir = savePath.toFile();

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().replace(".json", "");
                    // For load command: show files that are NOT currently loaded (including hidden ones)
                    // For delete command: show all files
                    if (includeAll || !loadedFiles.containsKey(name)) {
                        if (name.toLowerCase().startsWith(remaining)) {
                            builder.suggest(name);
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    private static Suggestions suggestLoadedFiles(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String name : loadedFiles.keySet()) {
            if (name.toLowerCase().startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.build();
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        AutoConfig.register(BoshysBTEUtilsConfig.class, GsonConfigSerializer::new);
        config = AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).getConfig();

        // Initialize markers save path
        updateMarkersSavePath();

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
                                // Only clear cache markers (not from loaded files)
                                int count = clearCacheMarkersOnly();
                                context.getSource().sendFeedback(Text.literal("§aCleared " + count + " cache markers! Loaded files remain active."));
                                return 1;
                            })
                            .then(ClientCommandManager.literal("all")
                                    .executes(context -> {
                                        // Clear everything including loaded files
                                        int count = markers.size();
                                        markers.clear();
                                        markerConnections.clear();
                                        selectedMarker = null;
                                        lastAddedMarker = null;
                                        loadedFiles.clear();
                                        hiddenFiles.clear();
                                        context.getSource().sendFeedback(Text.literal("§aCleared " + count + " markers and unloaded all files!"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("addMarker")
                            .executes(context -> {
                                if (!config.enableMarkers) {
                                    context.getSource().sendFeedback(Text.literal("§cMarkers disabled in config!"));
                                    return 0;
                                }

                                ClientPlayerEntity player = context.getSource().getPlayer();
                                double x = player.getX();
                                double y = player.getY();
                                double z = player.getZ();

                                TeleportMarker newMarker = addMarker(new Vec3d(x, y, z));

                                // Auto-connect if enabled
                                if (config.enableAutoLineConnection) {
                                    handleAutoConnect(newMarker);
                                }

                                context.getSource().sendFeedback(Text.literal("§aMarker added at your location!"));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("updateMarkerDesign")
                            .executes(context -> {
                                if (!config.enableMarkers) {
                                    context.getSource().sendFeedback(Text.literal("§cMarkers disabled in config!"));
                                    return 0;
                                }

                                if (markers.isEmpty()) {
                                    context.getSource().sendFeedback(Text.literal("§cNo markers to update!"));
                                    return 0;
                                }

                                int updatedCount = 0;

                                // If a marker is selected, only update that one
                                if (selectedMarker != null) {
                                    updateMarkerDesign(selectedMarker);
                                    updatedCount = 1;
                                    context.getSource().sendFeedback(Text.literal("§aUpdated selected marker's design!"));
                                } else {
                                    // Update all markers
                                    for (TeleportMarker marker : markers) {
                                        updateMarkerDesign(marker);
                                        updatedCount++;
                                    }
                                    context.getSource().sendFeedback(Text.literal("§aUpdated " + updatedCount + " markers to current config design!"));
                                }

                                return 1;
                            }))
                    // Saved Markers Commands
                    .then(ClientCommandManager.literal("saveMarkers")
                            .then(ClientCommandManager.argument("filename", com.mojang.brigadier.arguments.StringArgumentType.string())
                                    .executes(context -> {
                                        String filename = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "filename");
                                        return saveMarkersToFile(context, filename, -1); // -1 means all markers
                                    })
                                    .then(ClientCommandManager.argument("radius", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0))
                                            .executes(context -> {
                                                String filename = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "filename");
                                                double radius = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "radius");
                                                return saveMarkersToFile(context, filename, radius);
                                            }))))
                    .then(ClientCommandManager.literal("updateMarkers")
                            .then(ClientCommandManager.argument("filename", com.mojang.brigadier.arguments.StringArgumentType.string())
                                    .suggests(SAVED_FILE_SUGGESTIONS)
                                    .executes(context -> {
                                        String filename = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "filename");
                                        return updateMarkerFile(context, filename, -1);
                                    })
                                    .then(ClientCommandManager.argument("radius", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0))
                                            .executes(context -> {
                                                String filename = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "filename");
                                                double radius = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "radius");
                                                return updateMarkerFile(context, filename, radius);
                                            }))))
                    .then(ClientCommandManager.literal("load")
                            .then(ClientCommandManager.argument("filename", com.mojang.brigadier.arguments.StringArgumentType.string())
                                    .suggests(SAVED_FILE_SUGGESTIONS)
                                    .executes(context -> {
                                        String filename = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "filename");
                                        return loadMarkerFile(context, filename);
                                    })))
                    .then(ClientCommandManager.literal("hide")
                            .then(ClientCommandManager.argument("filename", com.mojang.brigadier.arguments.StringArgumentType.string())
                                    .suggests(LOADED_FILE_SUGGESTIONS)
                                    .executes(context -> {
                                        String filename = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "filename");
                                        return hideMarkerFile(context, filename);
                                    })))
                    .then(ClientCommandManager.literal("delete")
                            .then(ClientCommandManager.argument("filename", com.mojang.brigadier.arguments.StringArgumentType.string())
                                    .suggests(ALL_FILE_SUGGESTIONS)
                                    .executes(context -> {
                                        String filename = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "filename");
                                        return deleteMarkerFile(context, filename);
                                    })))
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.world == null) return;

            // Decrement cooldowns
            if (selectionCooldown > 0) {
                selectionCooldown--;
            }

            if (commandCooldownTicks > 0) {
                commandCooldownTicks--;
                if (commandCooldownTicks == 0) {
                    lastCommandSent = ""; // Clear command if no teleport happened
                }
            }

            // Handle TPLL teleport detection (both keybind and manual)
            handleTpllTeleportDetection(client);

            // TPLL keybind handler
            while (tpllKeybind.wasPressed()) {
                try {
                    String clip = getClipboard(client);
                    if (clip == null || clip.isEmpty()) {
                        notifyError(client, "§cClipboard empty!");
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
                // Only clear cache markers (not from loaded files)
                int count = clearCacheMarkersOnly();
                notifyError(client, "§aCleared " + count + " cache markers! Loaded files remain active.");
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

    // Called when a command is sent (from mixin) to track potential TPLL commands
    public void onCommandSent(String command) {
        if (!config.enableMarkers || !config.enableAutoTpllMarkers) return;

        String lowerCmd = command.toLowerCase().trim();

        // Check if this is a TPLL-like command (tpll or c, or configured command)
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

    private void handleTpllTeleportDetection(MinecraftClient client) {
        // Handle both keybind-triggered and manual /tpll detection
        if (!waitingForTeleport && commandCooldownTicks == 0) return;

        // Decrement cooldown
        if (tpllCooldownTicks > 0) {
            tpllCooldownTicks--;
        } else if (waitingForTeleport) {
            // Timeout - cancel waiting
            waitingForTeleport = false;
            return;
        }

        // Check if player has moved from the position before TPLL
        double currentX = client.player.getX();
        double currentY = client.player.getY();
        double currentZ = client.player.getZ();

        // Calculate distance moved
        double distanceMoved = Math.sqrt(
                Math.pow(currentX - posXBeforeTpll, 2) +
                        Math.pow(currentY - posYBeforeTpll, 2) +
                        Math.pow(currentZ - posZBeforeTpll, 2)
        );

        // If moved at all (more than 0.1 blocks), consider it a teleport
        // This ensures we catch ALL teleports, no matter how small
        if (distanceMoved > 0.1) {
            // Only place marker if we were waiting for a teleport (keybind or detected command)
            if (waitingForTeleport || commandCooldownTicks > 0) {
                // Place marker at the ACTUAL new position (where player ended up after teleport)
                TeleportMarker newMarker = addMarker(new Vec3d(currentX, currentY, currentZ));

                // Auto-connect if enabled
                if (config.enableAutoLineConnection) {
                    handleAutoConnect(newMarker);
                }

                // Reset tracking
                waitingForTeleport = false;
                commandCooldownTicks = 0;
                lastCommandSent = "";
            }
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

    // Saved Markers System Methods

    public void updateMarkersSavePath() {
        if (config.savedMarkersFolderPath != null && !config.savedMarkersFolderPath.isEmpty()) {
            markersSavePath = Path.of(config.savedMarkersFolderPath);
        } else {
            markersSavePath = Path.of("config/boshysbteutils/markers");
        }

        // Ensure directory exists
        File dir = markersSavePath.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static Path getMarkersSavePath() {
        if (markersSavePath == null) {
            return Path.of("config/boshysbteutils/markers");
        }
        return markersSavePath;
    }

    private int clearCacheMarkersOnly() {
        // Remove markers that aren't part of any loaded file
        Set<Vec3d> protectedPositions = new HashSet<>();

        // Collect all positions from loaded files
        for (SavedMarkerFile file : loadedFiles.values()) {
            for (SavedMarkerData data : file.markers) {
                protectedPositions.add(new Vec3d(data.x, data.y, data.z));
            }
        }

        // Remove markers not in protected positions
        final int[] removedCount = new int[1];
        markers.removeIf(marker -> {
            if (!protectedPositions.contains(marker.position)) {
                removedCount[0]++;
                return true;
            }
            return false;
        });

        // Clean up connections and references
        markerConnections.removeIf(conn -> !markers.contains(conn.marker1) || !markers.contains(conn.marker2));
        if (selectedMarker != null && !markers.contains(selectedMarker)) {
            selectedMarker = null;
        }
        if (lastAddedMarker != null && !markers.contains(lastAddedMarker)) {
            lastAddedMarker = null;
        }

        return removedCount[0];
    }

    private int saveMarkersToFile(CommandContext<FabricClientCommandSource> context,
                                  String filename, double radius) {
        if (!config.enableMarkers) {
            context.getSource().sendFeedback(Text.literal("§cMarkers disabled in config!"));
            return 0;
        }

        if (markers.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cNo markers in cache to save!"));
            return 0;
        }

        // Clean filename
        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        if (filename.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cInvalid filename!"));
            return 0;
        }

        ClientPlayerEntity player = context.getSource().getPlayer();
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        List<SavedMarkerData> markersToSave = new ArrayList<>();

        // Build set of positions already saved to ANY file
        Set<String> alreadySavedPositions = new HashSet<>();
        Path savePath = getMarkersSavePath();
        File dir = savePath.toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] existingFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (existingFiles != null) {
                for (File existingFile : existingFiles) {
                    try (FileReader reader = new FileReader(existingFile)) {
                        SavedMarkerFile existingData = GSON.fromJson(reader, SavedMarkerFile.class);
                        if (existingData != null && existingData.markers != null) {
                            for (SavedMarkerData data : existingData.markers) {
                                alreadySavedPositions.add(formatPosition(data.x, data.y, data.z));
                            }
                        }
                    } catch (IOException e) {
                        // Skip files that can't be read
                    }
                }
            }
        }

        for (TeleportMarker marker : markers) {
            String posKey = formatPosition(marker.position.x, marker.position.y, marker.position.z);

            // Skip if already saved to another file
            if (alreadySavedPositions.contains(posKey)) {
                continue;
            }

            // Check if marker is within radius (or save all if radius is -1)
            if (radius < 0 || marker.position.distanceTo(playerPos) <= radius) {
                markersToSave.add(new SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity
                ));
            }
        }

        if (markersToSave.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cNo new unsaved markers within specified radius!"));
            return 0;
        }

        SavedMarkerFile fileData = new SavedMarkerFile(filename, System.currentTimeMillis(), markersToSave);

        File file = getMarkersSavePath().resolve(filename + ".json").toFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(fileData, writer);
            context.getSource().sendFeedback(Text.literal("§aSaved " + markersToSave.size() + " new markers to '" + filename + "'!"));
            return 1;
        } catch (IOException e) {
            context.getSource().sendFeedback(Text.literal("§cFailed to save markers: " + e.getMessage()));
            return 0;
        }
    }

    private int updateMarkerFile(CommandContext<FabricClientCommandSource> context,
                                 String filename, double radius) {
        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        if (!file.exists()) {
            context.getSource().sendFeedback(Text.literal("§cFile '" + filename + "' not found!"));
            return 0;
        }

        try (FileReader reader = new FileReader(file)) {
            SavedMarkerFile existingData = GSON.fromJson(reader, SavedMarkerFile.class);
            if (existingData == null) existingData = new SavedMarkerFile(filename, System.currentTimeMillis(), new ArrayList<>());

            ClientPlayerEntity player = context.getSource().getPlayer();
            Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            Set<String> existingPositions = new HashSet<>();

            // Build set of existing positions to avoid duplicates
            for (SavedMarkerData data : existingData.markers) {
                existingPositions.add(formatPosition(data.x, data.y, data.z));
            }

            int addedCount = 0;

            // Add current markers that aren't already in the file
            for (TeleportMarker marker : markers) {
                String posKey = formatPosition(marker.position.x, marker.position.y, marker.position.z);

                if (!existingPositions.contains(posKey)) {
                    if (radius < 0 || marker.position.distanceTo(playerPos) <= radius) {
                        existingData.markers.add(new SavedMarkerData(
                                marker.position.x, marker.position.y, marker.position.z,
                                marker.colour, marker.scale, marker.opacity
                        ));
                        existingPositions.add(posKey);
                        addedCount++;
                    }
                }
            }

            existingData.lastModified = System.currentTimeMillis();

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(existingData, writer);
                context.getSource().sendFeedback(Text.literal("§aUpdated '" + filename + "'! Added " + addedCount + " new markers. Total: " + existingData.markers.size()));
                return 1;
            }
        } catch (IOException e) {
            context.getSource().sendFeedback(Text.literal("§cFailed to update file: " + e.getMessage()));
            return 0;
        }
    }

    private int loadMarkerFile(CommandContext<FabricClientCommandSource> context,
                               String filename) {
        if (!config.enableMarkers) {
            context.getSource().sendFeedback(Text.literal("§cMarkers disabled in config!"));
            return 0;
        }

        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        if (!file.exists()) {
            context.getSource().sendFeedback(Text.literal("§cFile '" + filename + "' not found!"));
            return 0;
        }

        try (FileReader reader = new FileReader(file)) {
            SavedMarkerFile fileData = GSON.fromJson(reader, SavedMarkerFile.class);
            if (fileData == null || fileData.markers == null) {
                context.getSource().sendFeedback(Text.literal("§cInvalid file format!"));
                return 0;
            }

            // Remove from hidden if it was hidden
            hiddenFiles.remove(filename);

            // Load markers into world
            int loadedCount = 0;
            for (SavedMarkerData data : fileData.markers) {
                TeleportMarker marker = new TeleportMarker(
                        new Vec3d(data.x, data.y, data.z),
                        data.colour, data.scale, data.opacity
                );
                markers.add(marker);
                loadedCount++;
            }

            // Track loaded file
            loadedFiles.put(filename, fileData);

            context.getSource().sendFeedback(Text.literal("§aLoaded " + loadedCount + " markers from '" + filename + "'!"));
            return 1;
        } catch (IOException e) {
            context.getSource().sendFeedback(Text.literal("§cFailed to load file: " + e.getMessage()));
            return 0;
        }
    }

    private int hideMarkerFile(CommandContext<FabricClientCommandSource> context,
                               String filename) {
        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

        if (!loadedFiles.containsKey(filename)) {
            context.getSource().sendFeedback(Text.literal("§cFile '" + filename + "' is not currently loaded!"));
            return 0;
        }

        SavedMarkerFile fileData = loadedFiles.get(filename);

        // Remove markers from display
        final int[] removedCount = new int[1];
        markers.removeIf(marker -> {
            for (SavedMarkerData data : fileData.markers) {
                if (marker.position.equals(new Vec3d(data.x, data.y, data.z))) {
                    removedCount[0]++;
                    return true;
                }
            }
            return false;
        });

        // Remove connections that involved these markers
        markerConnections.removeIf(conn -> !markers.contains(conn.marker1) || !markers.contains(conn.marker2));

        // Move to hidden
        loadedFiles.remove(filename);
        hiddenFiles.add(filename);

        context.getSource().sendFeedback(Text.literal("§aHidden '" + filename + "'! Removed " + removedCount[0] + " markers from display."));
        return 1;
    }

    private int deleteMarkerFile(CommandContext<FabricClientCommandSource> context,
                                 String filename) {
        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        if (!file.exists()) {
            context.getSource().sendFeedback(Text.literal("§cFile '" + filename + "' not found!"));
            return 0;
        }

        // If file is loaded, hide it first (remove markers from display)
        if (loadedFiles.containsKey(filename)) {
            hideMarkerFile(context, filename);
        }

        if (file.delete()) {
            hiddenFiles.remove(filename);
            context.getSource().sendFeedback(Text.literal("§aDeleted file '" + filename + "' permanently!"));
            return 1;
        } else {
            context.getSource().sendFeedback(Text.literal("§cFailed to delete file!"));
            return 0;
        }
    }

    private String formatPosition(double x, double y, double z) {
        return String.format("%.2f,%.2f,%.2f", x, y, z);
    }

    public static TeleportMarker addMarker(Vec3d pos) {
        TeleportMarker marker = new TeleportMarker(pos, config.markerColour, config.markerScale, config.markerOpacity);
        markers.add(marker);
        return marker;
    }

    // Update a marker's design to match current config
    public static void updateMarkerDesign(TeleportMarker marker) {
        marker.colour = config.markerColour;
        marker.scale = config.markerScale;
        marker.opacity = config.markerOpacity;
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

    // Static setter for config (used when config is updated via Mod Menu)
    public static void setConfig(BoshysBTEUtilsConfig newConfig) {
        config = newConfig;
        if (INSTANCE != null) {
            INSTANCE.updateMarkersSavePath();
        }
    }

    // Getter methods for teleport tracking (used by mixin)
    public boolean isWaitingForTeleport() {
        return waitingForTeleport;
    }

    public void triggerManualTpllWait(MinecraftClient client) {
        // Handled in onCommandSent now
    }

    // Data classes for saved markers
    public static class TeleportMarker {
        public final Vec3d position;
        public int colour;
        public float scale;
        public float opacity;

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

    public static class SavedMarkerData {
        public double x, y, z;
        public int colour;
        public float scale;
        public float opacity;

        public SavedMarkerData(double x, double y, double z, int colour, float scale, float opacity) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.colour = colour;
            this.scale = scale;
            this.opacity = opacity;
        }
    }

    public static class SavedMarkerFile {
        public String name;
        public long lastModified;
        public List<SavedMarkerData> markers;

        public SavedMarkerFile(String name, long lastModified, List<SavedMarkerData> markers) {
            this.name = name;
            this.lastModified = lastModified;
            this.markers = markers;
        }
    }
}
