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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import org.lwjgl.glfw.GLFW;

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
    public static final Set<TeleportMarker> selectedMarkers = new HashSet<>(); // Multiple selection support
    public static TeleportMarker lastAddedMarker = null;

    // Saved markers system
    public static final Map<String, SavedMarkerFile> loadedFiles = new HashMap<>();
    public static final Map<String, SavedMarkerFile> modifiedLoadedFiles = new HashMap<>(); // Track modified loaded files
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

    // Clear confirmation tracking
    private int pendingClearCount = 0;
    private boolean pendingClearAll = false;

    // Autosave tracking
    private long lastAutosaveTime = 0;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd_HHmmss");

    // Suggestion providers for filenames
    private static final SuggestionProvider<FabricClientCommandSource> SAVED_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestSavedFiles(builder, false, false));
    };

    // Suggestion provider that includes autosave files (for load command)
    private static final SuggestionProvider<FabricClientCommandSource> LOADABLE_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestSavedFiles(builder, false, true));
    };

    private static final SuggestionProvider<FabricClientCommandSource> LOADED_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestLoadedFiles(builder));
    };

    private static final SuggestionProvider<FabricClientCommandSource> ALL_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestSavedFiles(builder, true, false));
    };

    // Suggestion provider for merge files (allows multiple)
    private static final SuggestionProvider<FabricClientCommandSource> MERGE_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestSavedFiles(builder, true, true));
    };

    // Suggestion provider for include/exclude cached markers
    private static final SuggestionProvider<FabricClientCommandSource> INCLUDE_EXCLUDE_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();
        if ("includecachedmarkers".toLowerCase().startsWith(remaining)) {
            builder.suggest("includeCachedMarkers");
        }
        if ("excludecachedmarkers".toLowerCase().startsWith(remaining)) {
            builder.suggest("excludeCachedMarkers");
        }
        return CompletableFuture.completedFuture(builder.build());
    };

    private static Suggestions suggestSavedFiles(SuggestionsBuilder builder, boolean includeAll, boolean includeAutosave) {
        String remaining = builder.getRemaining().toLowerCase();
        Path savePath = getMarkersSavePath();
        File dir = savePath.toFile();

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> {
                if (!name.endsWith(".json")) return false;
                // Exclude timestamped autosave files (autosave_*.json) but include main autosave.json if requested
                if (name.startsWith("autosave_")) return false;
                if (name.equals("autosave.json")) return includeAutosave;
                return true;
            });
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

        // Register disconnect event for autosave
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            performAutosave();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("boshys-bt-utils")
                    .then(ClientCommandManager.literal("clearMarkers")
                            .executes(context -> {
                                // Check if confirmation is needed
                                int cacheCount = getCacheMarkerCount();
                                if (config.enableClearConfirmation && cacheCount > config.clearConfirmLimit) {
                                    pendingClearCount = cacheCount;
                                    pendingClearAll = false;
                                    context.getSource().sendFeedback(Text.literal("§eAre you sure? This will clear " + cacheCount + " cache markers. Use /boshys-bt-utils confirmClear to confirm."));
                                    return 1;
                                }
                                // Only clear cache markers (not from loaded files)
                                int count = clearCacheMarkersOnly();
                                context.getSource().sendFeedback(Text.literal("§aCleared " + count + " cache markers! Loaded files remain active."));
                                return 1;
                            })
                            .then(ClientCommandManager.literal("all")
                                    .executes(context -> {
                                        // Check if confirmation is needed
                                        int totalCount = markers.size();
                                        if (config.enableClearConfirmation && totalCount > config.clearConfirmLimit) {
                                            pendingClearCount = totalCount;
                                            pendingClearAll = true;
                                            context.getSource().sendFeedback(Text.literal("§eAre you sure? This will clear " + totalCount + " markers including loaded files. Use /boshys-bt-utils confirmClear to confirm."));
                                            return 1;
                                        }
                                        // Clear everything including loaded files
                                        int count = markers.size();
                                        markers.clear();
                                        markerConnections.clear();
                                        selectedMarkers.clear();
                                        lastAddedMarker = null;
                                        loadedFiles.clear();
                                        modifiedLoadedFiles.clear();
                                        hiddenFiles.clear();
                                        context.getSource().sendFeedback(Text.literal("§aCleared " + count + " markers and unloaded all files!"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("confirmClear")
                            .executes(context -> {
                                if (pendingClearCount == 0) {
                                    context.getSource().sendFeedback(Text.literal("§cNo pending clear operation!"));
                                    return 0;
                                }
                                if (pendingClearAll) {
                                    int count = markers.size();
                                    markers.clear();
                                    markerConnections.clear();
                                    selectedMarkers.clear();
                                    lastAddedMarker = null;
                                    loadedFiles.clear();
                                    modifiedLoadedFiles.clear();
                                    hiddenFiles.clear();
                                    pendingClearCount = 0;
                                    pendingClearAll = false;
                                    context.getSource().sendFeedback(Text.literal("§aCleared " + count + " markers and unloaded all files!"));
                                } else {
                                    int count = clearCacheMarkersOnly();
                                    pendingClearCount = 0;
                                    context.getSource().sendFeedback(Text.literal("§aCleared " + count + " cache markers! Loaded files remain active."));
                                }
                                return 1;
                            }))
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

                                // If markers are selected, only update those
                                if (!selectedMarkers.isEmpty()) {
                                    for (TeleportMarker marker : selectedMarkers) {
                                        updateMarkerDesign(marker);
                                        updatedCount++;
                                    }
                                    context.getSource().sendFeedback(Text.literal("§aUpdated " + updatedCount + " selected markers' design!"));
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
                    .then(ClientCommandManager.literal("moveMarker")
                            .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                                    .then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
                                            .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                                    .executes(context -> {
                                                        if (!config.enableMarkers) {
                                                            context.getSource().sendFeedback(Text.literal("§cMarkers disabled in config!"));
                                                            return 0;
                                                        }
                                                        if (selectedMarkers.isEmpty()) {
                                                            context.getSource().sendFeedback(Text.literal("§cNo markers selected! Right-click markers to select them."));
                                                            return 0;
                                                        }
                                                        double dx = DoubleArgumentType.getDouble(context, "x");
                                                        double dy = DoubleArgumentType.getDouble(context, "y");
                                                        double dz = DoubleArgumentType.getDouble(context, "z");
                                                        return moveSelectedMarkers(context, dx, dy, dz);
                                                    }))))
                            .executes(context -> {
                                if (!config.enableMarkers) {
                                    context.getSource().sendFeedback(Text.literal("§cMarkers disabled in config!"));
                                    return 0;
                                }
                                if (selectedMarkers.isEmpty()) {
                                    context.getSource().sendFeedback(Text.literal("§cNo markers selected! Right-click markers to select them."));
                                    return 0;
                                }
                                // Move to player position
                                ClientPlayerEntity player = context.getSource().getPlayer();
                                return moveSelectedMarkersToPosition(context, player.getX(), player.getY(), player.getZ());
                            }))
                    // Saved Markers Commands
                    .then(ClientCommandManager.literal("saveMarkers")
                            .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                    .executes(context -> {
                                        String filename = StringArgumentType.getString(context, "filename");
                                        return saveMarkersToFile(context, filename, -1); // -1 means all markers
                                    })
                                    .then(ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0))
                                            .executes(context -> {
                                                String filename = StringArgumentType.getString(context, "filename");
                                                double radius = DoubleArgumentType.getDouble(context, "radius");
                                                return saveMarkersToFile(context, filename, radius);
                                            }))))
                    .then(ClientCommandManager.literal("updateMarkers")
                            .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                    .suggests(SAVED_FILE_SUGGESTIONS)
                                    .executes(context -> {
                                        String filename = StringArgumentType.getString(context, "filename");
                                        return updateMarkerFile(context, filename, -1);
                                    })
                                    .then(ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0))
                                            .executes(context -> {
                                                String filename = StringArgumentType.getString(context, "filename");
                                                double radius = DoubleArgumentType.getDouble(context, "radius");
                                                return updateMarkerFile(context, filename, radius);
                                            }))))
                    .then(ClientCommandManager.literal("load")
                            .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                    .suggests(LOADABLE_FILE_SUGGESTIONS) // Now includes autosave
                                    .executes(context -> {
                                        String filename = StringArgumentType.getString(context, "filename");
                                        return loadMarkerFile(context, filename);
                                    })))
                    .then(ClientCommandManager.literal("hide")
                            .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                    .suggests(LOADED_FILE_SUGGESTIONS)
                                    .executes(context -> {
                                        String filename = StringArgumentType.getString(context, "filename");
                                        return hideMarkerFile(context, filename);
                                    })))
                    .then(ClientCommandManager.literal("delete")
                            .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                    .suggests(ALL_FILE_SUGGESTIONS)
                                    .executes(context -> {
                                        String filename = StringArgumentType.getString(context, "filename");
                                        return deleteMarkerFile(context, filename);
                                    })))
                    // Merge command - NEW FORMAT
                    .then(ClientCommandManager.literal("mergeMarkers")
                            .then(ClientCommandManager.argument("mergedFileName", StringArgumentType.string())
                                    .suggests(ALL_FILE_SUGGESTIONS) // Allow any existing file or new name
                                    .then(ClientCommandManager.argument("includeCached", StringArgumentType.string())
                                            .suggests(INCLUDE_EXCLUDE_SUGGESTIONS)
                                            .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                                    .suggests(MERGE_FILE_SUGGESTIONS)
                                                    .executes(context -> {
                                                        String mergedFileName = StringArgumentType.getString(context, "mergedFileName");
                                                        String includeCached = StringArgumentType.getString(context, "includeCached");
                                                        String filename = StringArgumentType.getString(context, "filename");
                                                        List<String> files = new ArrayList<>();
                                                        files.add(filename);
                                                        return mergeMarkerFiles(context, mergedFileName, includeCached.equalsIgnoreCase("includeCachedMarkers"), files);
                                                    })
                                                    .then(ClientCommandManager.argument("additionalFiles", StringArgumentType.greedyString())
                                                            .executes(context -> {
                                                                String mergedFileName = StringArgumentType.getString(context, "mergedFileName");
                                                                String includeCached = StringArgumentType.getString(context, "includeCached");
                                                                String filename = StringArgumentType.getString(context, "filename");
                                                                String additionalFilesStr = StringArgumentType.getString(context, "additionalFiles");

                                                                List<String> files = new ArrayList<>();
                                                                files.add(filename);

                                                                // Parse additional files (space or comma separated)
                                                                String[] additionalFiles = additionalFilesStr.split("[,\\s]+");
                                                                for (String file : additionalFiles) {
                                                                    file = file.trim();
                                                                    if (!file.isEmpty()) {
                                                                        files.add(file);
                                                                    }
                                                                }

                                                                return mergeMarkerFiles(context, mergedFileName, includeCached.equalsIgnoreCase("includeCachedMarkers"), files);
                                                            }))))))
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.world == null) return;

            // Handle autosave timer
            if (config.enableAutosave && config.autosaveIntervalMinutes > 0) {
                long currentTime = System.currentTimeMillis();
                long intervalMs = config.autosaveIntervalMinutes * 60 * 1000;
                if (currentTime - lastAutosaveTime >= intervalMs) {
                    performAutosave();
                    lastAutosaveTime = currentTime;
                }
            }

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
                // Check if confirmation is needed
                int cacheCount = getCacheMarkerCount();
                if (config.enableClearConfirmation && cacheCount > config.clearConfirmLimit) {
                    pendingClearCount = cacheCount;
                    pendingClearAll = false;
                    notifyError(client, "§eAre you sure? This will clear " + cacheCount + " cache markers. Use /boshys-bt-utils confirmClear to confirm.");
                    continue;
                }
                // Only clear cache markers (not from loaded files)
                int count = clearCacheMarkersOnly();
                notifyError(client, "§aCleared " + count + " cache markers! Loaded files remain active.");
            }

            // Delete marker keybind handler
            while (deleteMarkerKeybind.wasPressed()) {
                if (!selectedMarkers.isEmpty()) {
                    int count = selectedMarkers.size();
                    // Delete all selected markers
                    for (TeleportMarker marker : new ArrayList<>(selectedMarkers)) {
                        deleteMarker(marker);
                    }
                    selectedMarkers.clear();
                    notifyError(client, "§aDeleted " + count + " selected marker(s)!");
                } else {
                    notifyError(client, "§cNo markers selected! Right-click markers to select them.");
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
        selectedMarkers.clear();
        selectedMarkers.add(newMarker);
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
            // Check for multi-select modifiers (Ctrl or Shift - works on both Windows and Mac)
            long windowHandle = client.getWindow().getHandle();
            boolean ctrlPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS || // Mac Command
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;  // Mac Command

            boolean shiftPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

            boolean multiSelect = ctrlPressed || shiftPressed;

            if (selectedMarkers.contains(hitMarker)) {
                // Deselect if clicking same marker
                selectedMarkers.remove(hitMarker);
                if (selectedMarkers.isEmpty()) {
                    notifyError(client, "§eMarker deselected. No markers selected.");
                } else {
                    notifyError(client, "§eMarker deselected. " + selectedMarkers.size() + " marker(s) still selected.");
                }
            } else {
                // If we have exactly one marker selected and we're not multi-selecting,
                // this is a connect/disconnect operation
                if (selectedMarkers.size() == 1 && !multiSelect) {
                    TeleportMarker selectedMarker = selectedMarkers.iterator().next();

                    if (selectedMarker == hitMarker) {
                        // Clicked same marker - deselect it
                        selectedMarkers.clear();
                        notifyError(client, "§eMarker deselected.");
                    } else if (areMarkersConnected(selectedMarker, hitMarker)) {
                        // Disconnect the markers
                        disconnectMarkers(selectedMarker, hitMarker);
                        selectedMarkers.clear();
                        notifyError(client, "§cDisconnected markers!");
                    } else {
                        // Connect the markers
                        connectMarkers(selectedMarker, hitMarker);
                        selectedMarkers.clear();
                        notifyError(client, "§aConnected markers!");
                    }
                } else {
                    // Normal selection behavior
                    if (!multiSelect) {
                        // Single select - clear others
                        selectedMarkers.clear();
                    }
                    // Select marker
                    selectedMarkers.add(hitMarker);

                    if (selectedMarkers.size() == 1) {
                        notifyError(client, "§aMarker selected! Right-click another to connect, press Delete to remove, or Ctrl/Shift+click for multi-select.");
                    } else {
                        notifyError(client, "§a" + selectedMarkers.size() + " markers selected! Use /boshys-bt-utils moveMarker to move them.");
                    }
                }
            }
        }
    }

    // Get count of cache-only markers (not from loaded files)
    private int getCacheMarkerCount() {
        Set<Vec3d> protectedPositions = new HashSet<>();

        // Collect all positions from loaded files
        for (SavedMarkerFile file : loadedFiles.values()) {
            for (SavedMarkerData data : file.markers) {
                protectedPositions.add(new Vec3d(data.x, data.y, data.z));
            }
        }

        int count = 0;
        for (TeleportMarker marker : markers) {
            if (!protectedPositions.contains(marker.position)) {
                count++;
            }
        }
        return count;
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
        selectedMarkers.removeIf(marker -> !markers.contains(marker));
        if (lastAddedMarker != null && !markers.contains(lastAddedMarker)) {
            lastAddedMarker = null;
        }

        return removedCount[0];
    }

    private int moveSelectedMarkers(CommandContext<FabricClientCommandSource> context, double dx, double dy, double dz) {
        if (selectedMarkers.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cNo markers selected!"));
            return 0;
        }

        int movedCount = 0;
        for (TeleportMarker marker : selectedMarkers) {
            if (markers.contains(marker)) {
                // Move the marker by displacement
                marker.position = marker.position.add(dx, dy, dz);
                movedCount++;

                // Check if this marker belongs to a loaded file and track modification
                trackMarkerModification(marker);
            }
        }

        // Update connections after moving
        updateConnectionsAfterMove();

        context.getSource().sendFeedback(Text.literal("§aMoved " + movedCount + " marker(s) by (" + dx + ", " + dy + ", " + dz + ")!"));
        return 1;
    }

    private int moveSelectedMarkersToPosition(CommandContext<FabricClientCommandSource> context, double x, double y, double z) {
        if (selectedMarkers.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cNo markers selected!"));
            return 0;
        }

        // Calculate displacement from first selected marker to target position
        TeleportMarker firstMarker = selectedMarkers.iterator().next();
        double dx = x - firstMarker.position.x;
        double dy = y - firstMarker.position.y;
        double dz = z - firstMarker.position.z;

        return moveSelectedMarkers(context, dx, dy, dz);
    }

    private void trackMarkerModification(TeleportMarker marker) {
        // Check which loaded file this marker belongs to
        for (Map.Entry<String, SavedMarkerFile> entry : loadedFiles.entrySet()) {
            String filename = entry.getKey();
            SavedMarkerFile file = entry.getValue();

            for (SavedMarkerData data : file.markers) {
                Vec3d dataPos = new Vec3d(data.x, data.y, data.z);
                // Check if this data point matches the marker's original position (before move)
                // We need to check if the marker was originally from this file
                // Since we can't easily track original positions, we check if the marker
                // is at a position that exists in the file data
                if (marker.position.distanceTo(dataPos) < 0.001 ||
                        marker.position.subtract(dataPos).lengthSquared() < 0.001) {
                    modifiedLoadedFiles.put(filename, file);
                    return;
                }
            }
        }
    }

    private void updateConnectionsAfterMove() {
        // Connections are maintained by reference, so they automatically update
        // No additional action needed since MarkerConnection stores references to markers
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
        List<SavedConnectionData> connectionsToSave = new ArrayList<>();

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

        // Build map of marker to index for connection saving
        Map<TeleportMarker, Integer> markerIndexMap = new HashMap<>();
        int index = 0;

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
                markerIndexMap.put(marker, index);
                index++;
            }
        }

        if (markersToSave.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cNo new unsaved markers within specified radius!"));
            return 0;
        }

        // Save connections between markers that are both being saved
        for (MarkerConnection conn : markerConnections) {
            Integer idx1 = markerIndexMap.get(conn.marker1);
            Integer idx2 = markerIndexMap.get(conn.marker2);
            if (idx1 != null && idx2 != null) {
                connectionsToSave.add(new SavedConnectionData(idx1, idx2));
            }
        }

        SavedMarkerFile fileData = new SavedMarkerFile(filename, System.currentTimeMillis(), markersToSave, connectionsToSave);

        File file = getMarkersSavePath().resolve(filename + ".json").toFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(fileData, writer);

            // Create clickable message to open folder
            Text message = Text.literal("§aSaved " + markersToSave.size() + " new markers to '")
                    .append(Text.literal(filename).styled(style -> style.withBold(true)))
                    .append(Text.literal("'!"))
                    .styled(style -> style
                            .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("§eClick to open folder")))
                    );

            context.getSource().sendFeedback(message);
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
            if (existingData == null) {
                existingData = new SavedMarkerFile(filename, System.currentTimeMillis(), new ArrayList<>(), new ArrayList<>());
            }
            if (existingData.markers == null) {
                existingData.markers = new ArrayList<>();
            }
            if (existingData.connections == null) {
                existingData.connections = new ArrayList<>();
            }

            ClientPlayerEntity player = context.getSource().getPlayer();
            Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            Set<String> existingPositions = new HashSet<>();

            // Build set of existing positions to avoid duplicates
            for (SavedMarkerData data : existingData.markers) {
                existingPositions.add(formatPosition(data.x, data.y, data.z));
            }

            // Build map of existing markers for connection tracking
            Map<Vec3d, Integer> existingMarkerIndices = new HashMap<>();
            for (int i = 0; i < existingData.markers.size(); i++) {
                SavedMarkerData data = existingData.markers.get(i);
                existingMarkerIndices.put(new Vec3d(data.x, data.y, data.z), i);
            }

            int addedCount = 0;
            List<TeleportMarker> newMarkers = new ArrayList<>();

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
                        newMarkers.add(marker);
                        addedCount++;
                    }
                }
            }

            // Add connections between new markers and existing markers
            for (TeleportMarker newMarker : newMarkers) {
                Integer newIdx = existingData.markers.size() - newMarkers.size() + newMarkers.indexOf(newMarker);

                for (MarkerConnection conn : markerConnections) {
                    if (conn.marker1 == newMarker || conn.marker2 == newMarker) {
                        TeleportMarker other = (conn.marker1 == newMarker) ? conn.marker2 : conn.marker1;

                        // Check if other marker is in the file
                        Integer otherIdx = existingMarkerIndices.get(other.position);
                        if (otherIdx == null) {
                            // Check if other is also a new marker
                            int newMarkerIdx = newMarkers.indexOf(other);
                            if (newMarkerIdx != -1) {
                                otherIdx = existingData.markers.size() - newMarkers.size() + newMarkerIdx;
                            }
                        }

                        if (otherIdx != null && !newIdx.equals(otherIdx)) {
                            // Check if connection already exists
                            boolean exists = false;
                            for (SavedConnectionData savedConn : existingData.connections) {
                                if ((savedConn.fromIndex == newIdx && savedConn.toIndex == otherIdx) ||
                                        (savedConn.fromIndex == otherIdx && savedConn.toIndex == newIdx)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                existingData.connections.add(new SavedConnectionData(newIdx, otherIdx));
                            }
                        }
                    }
                }
            }

            existingData.lastModified = System.currentTimeMillis();

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(existingData, writer);

                Text message = Text.literal("§aUpdated '")
                        .append(Text.literal(filename).styled(style -> style.withBold(true)))
                        .append(Text.literal("'! Added " + addedCount + " new markers. Total: " + existingData.markers.size()))
                        .styled(style -> style
                                .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                                .withHoverEvent(new HoverEvent.ShowText(Text.literal("§eClick to open folder")))
                        );

                context.getSource().sendFeedback(message);
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
            List<TeleportMarker> loadedMarkers = new ArrayList<>();

            for (SavedMarkerData data : fileData.markers) {
                TeleportMarker marker = new TeleportMarker(
                        new Vec3d(data.x, data.y, data.z),
                        data.colour, data.scale, data.opacity
                );
                markers.add(marker);
                loadedMarkers.add(marker);
                loadedCount++;
            }

            // Load connections if they exist
            int loadedConnections = 0;
            if (fileData.connections != null) {
                for (SavedConnectionData connData : fileData.connections) {
                    if (connData.fromIndex >= 0 && connData.fromIndex < loadedMarkers.size() &&
                            connData.toIndex >= 0 && connData.toIndex < loadedMarkers.size()) {
                        connectMarkers(loadedMarkers.get(connData.fromIndex), loadedMarkers.get(connData.toIndex));
                        loadedConnections++;
                    }
                }
            }

            // Track loaded file
            loadedFiles.put(filename, fileData);

            // Remove from modified if it was there (fresh load)
            modifiedLoadedFiles.remove(filename);

            Text message = Text.literal("§aLoaded " + loadedCount + " markers")
                    .append(loadedConnections > 0 ? Text.literal(" with " + loadedConnections + " connections") : Text.literal(""))
                    .append(Text.literal(" from '"))
                    .append(Text.literal(filename).styled(style -> style.withBold(true)))
                    .append(Text.literal("'!"))
                    .styled(style -> style
                            .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("§eClick to open folder")))
                    );

            context.getSource().sendFeedback(message);
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
        selectedMarkers.removeIf(marker -> !markers.contains(marker));

        // Move to hidden
        loadedFiles.remove(filename);
        modifiedLoadedFiles.remove(filename);
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

    private int mergeMarkerFiles(CommandContext<FabricClientCommandSource> context, String mergedFileName, boolean includeCached, List<String> filenames) {
        if (!config.enableMarkers) {
            context.getSource().sendFeedback(Text.literal("§cMarkers disabled in config!"));
            return 0;
        }

        if (filenames.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cNeed at least one file to merge!"));
            return 0;
        }

        // Clean merged filename
        mergedFileName = mergedFileName.replaceAll("[^a-zA-Z0-9_-]", "");
        if (mergedFileName.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cInvalid merged filename!"));
            return 0;
        }

        // Validate all source files exist
        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
            File file = getMarkersSavePath().resolve(cleanFilename + ".json").toFile();
            if (!file.exists()) {
                context.getSource().sendFeedback(Text.literal("§cFile '" + cleanFilename + "' not found!"));
                return 0;
            }
        }

        List<SavedMarkerData> allMarkers = new ArrayList<>();
        List<SavedConnectionData> allConnections = new ArrayList<>();
        int baseIndex = 0;

        // Load all files
        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
            File file = getMarkersSavePath().resolve(cleanFilename + ".json").toFile();

            try (FileReader reader = new FileReader(file)) {
                SavedMarkerFile fileData = GSON.fromJson(reader, SavedMarkerFile.class);
                if (fileData != null && fileData.markers != null) {
                    // Add markers
                    for (SavedMarkerData data : fileData.markers) {
                        allMarkers.add(data);
                    }

                    // Add connections with offset
                    if (fileData.connections != null) {
                        for (SavedConnectionData conn : fileData.connections) {
                            allConnections.add(new SavedConnectionData(
                                    conn.fromIndex + baseIndex,
                                    conn.toIndex + baseIndex
                            ));
                        }
                    }

                    baseIndex += fileData.markers.size();
                }
            } catch (IOException e) {
                context.getSource().sendFeedback(Text.literal("§cError reading file '" + cleanFilename + "': " + e.getMessage()));
                return 0;
            }
        }

        // Include cached markers if requested
        if (includeCached) {
            Set<Vec3d> protectedPositions = new HashSet<>();
            for (SavedMarkerFile file : loadedFiles.values()) {
                for (SavedMarkerData data : file.markers) {
                    protectedPositions.add(new Vec3d(data.x, data.y, data.z));
                }
            }

            for (TeleportMarker marker : markers) {
                if (!protectedPositions.contains(marker.position)) {
                    allMarkers.add(new SavedMarkerData(
                            marker.position.x, marker.position.y, marker.position.z,
                            marker.colour, marker.scale, marker.opacity
                    ));
                }
            }
        }

        if (allMarkers.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("§cNo markers to merge!"));
            return 0;
        }

        // Save merged file (overwrites if exists)
        SavedMarkerFile mergedData = new SavedMarkerFile(mergedFileName, System.currentTimeMillis(), allMarkers, allConnections);
        File mergedFile = getMarkersSavePath().resolve(mergedFileName + ".json").toFile();

        try (FileWriter writer = new FileWriter(mergedFile)) {
            GSON.toJson(mergedData, writer);

            Text message = Text.literal("§aMerged " + allMarkers.size() + " markers")
                    .append(allConnections.size() > 0 ? Text.literal(" with " + allConnections.size() + " connections") : Text.literal(""))
                    .append(Text.literal(" into '"))
                    .append(Text.literal(mergedFileName).styled(style -> style.withBold(true)))
                    .append(Text.literal("'!"))
                    .styled(style -> style
                            .withClickEvent(new ClickEvent.OpenFile(mergedFile.getParentFile().getAbsolutePath()))
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("§eClick to open folder")))
                    );

            context.getSource().sendFeedback(message);
            return 1;
        } catch (IOException e) {
            context.getSource().sendFeedback(Text.literal("§cFailed to save merged file: " + e.getMessage()));
            return 0;
        }
    }

    private void performAutosave() {
        if (!config.enableAutosave) return;

        Path savePath = getMarkersSavePath();
        boolean savedAnything = false;

        // Autosave cache markers (markers not from loaded files) - always overwrite "autosave.json"
        Set<Vec3d> protectedPositions = new HashSet<>();
        for (SavedMarkerFile file : loadedFiles.values()) {
            for (SavedMarkerData data : file.markers) {
                protectedPositions.add(new Vec3d(data.x, data.y, data.z));
            }
        }

        List<SavedMarkerData> cacheMarkers = new ArrayList<>();
        List<TeleportMarker> cacheMarkerObjects = new ArrayList<>();

        for (TeleportMarker marker : markers) {
            if (!protectedPositions.contains(marker.position)) {
                cacheMarkers.add(new SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity
                ));
                cacheMarkerObjects.add(marker);
            }
        }

        // Save cache markers to single "autosave.json" file (always overwrite)
        if (!cacheMarkers.isEmpty()) {
            File autosaveFile = savePath.resolve("autosave.json").toFile();

            // Also save connections for cache markers
            List<SavedConnectionData> cacheConnections = new ArrayList<>();
            Map<TeleportMarker, Integer> cacheIndexMap = new HashMap<>();
            for (int i = 0; i < cacheMarkerObjects.size(); i++) {
                cacheIndexMap.put(cacheMarkerObjects.get(i), i);
            }

            for (MarkerConnection conn : markerConnections) {
                Integer idx1 = cacheIndexMap.get(conn.marker1);
                Integer idx2 = cacheIndexMap.get(conn.marker2);
                if (idx1 != null && idx2 != null) {
                    cacheConnections.add(new SavedConnectionData(idx1, idx2));
                }
            }

            SavedMarkerFile autosaveData = new SavedMarkerFile("autosave", System.currentTimeMillis(), cacheMarkers, cacheConnections);

            try (FileWriter writer = new FileWriter(autosaveFile)) {
                GSON.toJson(autosaveData, writer);
                savedAnything = true;
            } catch (IOException e) {
                // Silent fail for autosave
            }
        } else {
            // If no cache markers, delete autosave file if it exists
            File autosaveFile = savePath.resolve("autosave.json").toFile();
            if (autosaveFile.exists()) {
                autosaveFile.delete();
            }
        }

        // Autosave modified loaded files - these get timestamped filenames
        for (Map.Entry<String, SavedMarkerFile> entry : modifiedLoadedFiles.entrySet()) {
            String filename = entry.getKey();
            SavedMarkerFile fileData = entry.getValue();

            // Only create timestamped autosave for modified loaded files
            String dateStr = DATE_FORMAT.format(new Date());
            File autosaveFile = savePath.resolve("autosave_" + dateStr + "_" + filename + ".json").toFile();

            try (FileWriter writer = new FileWriter(autosaveFile)) {
                // Create updated file data with current marker states
                List<SavedMarkerData> updatedMarkers = new ArrayList<>();
                List<SavedConnectionData> updatedConnections = new ArrayList<>();

                // Find all markers that belong to this file
                Map<TeleportMarker, Integer> markerIndexMap = new HashMap<>();
                int index = 0;

                for (SavedMarkerData originalData : fileData.markers) {
                    Vec3d originalPos = new Vec3d(originalData.x, originalData.y, originalData.z);

                    // Find if this marker still exists and get its current state
                    boolean found = false;
                    for (TeleportMarker marker : markers) {
                        // Check if marker is at or near original position (allowing for moves)
                        if (marker.position.distanceTo(originalPos) < 0.001 ||
                                wasMarkerOriginallyFromFile(marker, filename)) {
                            updatedMarkers.add(new SavedMarkerData(
                                    marker.position.x, marker.position.y, marker.position.z,
                                    marker.colour, marker.scale, marker.opacity
                            ));
                            markerIndexMap.put(marker, index);
                            index++;
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        // Marker was deleted, keep original data
                        updatedMarkers.add(originalData);
                        index++;
                    }
                }

                // Save connections
                for (MarkerConnection conn : markerConnections) {
                    Integer idx1 = markerIndexMap.get(conn.marker1);
                    Integer idx2 = markerIndexMap.get(conn.marker2);
                    if (idx1 != null && idx2 != null) {
                        updatedConnections.add(new SavedConnectionData(idx1, idx2));
                    }
                }

                SavedMarkerFile autosaveData = new SavedMarkerFile(
                        "autosave_" + dateStr + "_" + filename,
                        System.currentTimeMillis(),
                        updatedMarkers,
                        updatedConnections
                );

                GSON.toJson(autosaveData, writer);
                savedAnything = true;
            } catch (IOException e) {
                // Silent fail for autosave
            }
        }

        // Clear modified tracking after autosave
        modifiedLoadedFiles.clear();
    }

    private boolean wasMarkerOriginallyFromFile(TeleportMarker marker, String filename) {
        SavedMarkerFile file = loadedFiles.get(filename);
        if (file == null) return false;

        for (SavedMarkerData data : file.markers) {
            Vec3d dataPos = new Vec3d(data.x, data.y, data.z);
            if (marker.position.equals(dataPos)) {
                return true;
            }
        }
        return false;
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
        selectedMarkers.remove(marker);
        if (lastAddedMarker == marker) {
            lastAddedMarker = null;
        }
    }

    public static void clearAllMarkers() {
        markers.clear();
        markerConnections.clear();
        selectedMarkers.clear();
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
        public Vec3d position; // Changed from final to allow moving
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

    public static class SavedConnectionData {
        public int fromIndex;
        public int toIndex;

        public SavedConnectionData(int fromIndex, int toIndex) {
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }
    }

    public static class SavedMarkerFile {
        public String name;
        public long lastModified;
        public List<SavedMarkerData> markers;
        public List<SavedConnectionData> connections; // Added for saving connections

        public SavedMarkerFile(String name, long lastModified, List<SavedMarkerData> markers) {
            this(name, lastModified, markers, new ArrayList<>());
        }

        public SavedMarkerFile(String name, long lastModified, List<SavedMarkerData> markers, List<SavedConnectionData> connections) {
            this.name = name;
            this.lastModified = lastModified;
            this.markers = markers;
            this.connections = connections != null ? connections : new ArrayList<>();
        }
    }
}