package com.boshys.bteutils.commands;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.storage.MarkerStorage;
import com.boshys.bteutils.storage.KmlImportHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CommandRegistry {
    private final BoshysBTEUtils mod;
    private final MarkerStorage markerStorage;
    private final KmlImportHandler kmlImportHandler;

    // Suggestion providers
    private final SuggestionProvider<FabricClientCommandSource> SAVED_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestSavedFiles(builder, true, false));
    };

    private final SuggestionProvider<FabricClientCommandSource> LOADABLE_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestLoadableFilesWithWildcard(builder));
    };

    private final SuggestionProvider<FabricClientCommandSource> LOADED_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestLoadedFilesWithWildcard(builder));
    };

    private final SuggestionProvider<FabricClientCommandSource> ALL_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestAllFilesWithExtensions(builder));
    };

    private final SuggestionProvider<FabricClientCommandSource> MERGE_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestSavedFiles(builder, true, true));
    };

    private final SuggestionProvider<FabricClientCommandSource> INCLUDE_EXCLUDE_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();
        if ("includecachedmarkers".startsWith(remaining)) {
            builder.suggest("includeCachedMarkers");
        }
        if ("excludecachedmarkers".startsWith(remaining)) {
            builder.suggest("excludeCachedMarkers");
        }
        return CompletableFuture.completedFuture(builder.build());
    };

    private final SuggestionProvider<FabricClientCommandSource> KML_FILE_SUGGESTIONS = (context, builder) -> {
        return CompletableFuture.completedFuture(suggestKmlFiles(builder));
    };

    public CommandRegistry(BoshysBTEUtils mod, MarkerStorage markerStorage, KmlImportHandler kmlImportHandler) {
        this.mod = mod;
        this.markerStorage = markerStorage;
        this.kmlImportHandler = kmlImportHandler;
    }

    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("boshys-bt-utils")
                // Clear markers
                .then(ClientCommandManager.literal("clearMarkers")
                        .executes(context -> {
                            int cacheCount = markerStorage.getCacheMarkerCount();
                            if (BoshysBTEUtils.getConfig().enableClearConfirmation && cacheCount > BoshysBTEUtils.getConfig().clearConfirmLimit) {
                                markerStorage.setPendingClear(cacheCount, false);
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.confirm.required", cacheCount));
                                return 1;
                            }
                            int count = markerStorage.clearCacheMarkersOnly();
                            context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.cleared", count));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("all")
                                .executes(context -> {
                                    int totalCount = BoshysBTEUtils.markers.size();
                                    if (BoshysBTEUtils.getConfig().enableClearConfirmation && totalCount > BoshysBTEUtils.getConfig().clearConfirmLimit) {
                                        markerStorage.setPendingClear(totalCount, true);
                                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.confirm.required.all", totalCount));
                                        return 1;
                                    }
                                    int count = markerStorage.confirmClear();
                                    context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.cleared.all", count));
                                    return 1;
                                })))

                // Confirm clear
                .then(ClientCommandManager.literal("confirmClear")
                        .executes(context -> {
                            if (!markerStorage.hasPendingClear()) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.confirm.none"));
                                return 0;
                            }
                            int count = markerStorage.confirmClear();
                            if (markerStorage.isPendingClearAll()) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.cleared.all", count));
                            } else {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.cleared", count));
                            }
                            return 1;
                        }))

                // Add marker
                .then(ClientCommandManager.literal("addMarker")
                        .executes(context -> {
                            if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                                return 0;
                            }

                            ClientPlayerEntity player = context.getSource().getPlayer();
                            double x = player.getX();
                            double y = player.getY();
                            double z = player.getZ();

                            com.boshys.bteutils.data.MarkerData.TeleportMarker newMarker = com.boshys.bteutils.data.MarkerData.addMarker(new net.minecraft.util.math.Vec3d(x, y, z));

                            if (BoshysBTEUtils.getConfig().enableAutoLineConnection) {
                                com.boshys.bteutils.data.MarkerData.handleAutoConnect(newMarker);
                            }

                            // Check if this is the first marker added this session via command
                            if (!BoshysBTEUtils.hasAddedMarkerThisSession) {
                                sendFirstMarkerMessage(context.getSource());
                                BoshysBTEUtils.hasAddedMarkerThisSession = true;
                            } else {
                                // Subsequent markers - action bar only
                                player.sendMessage(Text.translatable("command.boshysbteutils.marker.added_actionbar").formatted(net.minecraft.util.Formatting.GREEN), true);
                            }

                            return 1;
                        }))

                // Update marker design
                .then(ClientCommandManager.literal("updateMarkerDesign")
                        .executes(context -> {
                            if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                                return 0;
                            }

                            if (BoshysBTEUtils.markers.isEmpty()) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.no_markers"));
                                return 0;
                            }

                            if (!BoshysBTEUtils.selectedMarkers.isEmpty()) {
                                boolean hasUnloadedMarkers = false;
                                for (com.boshys.bteutils.data.MarkerData.TeleportMarker marker : BoshysBTEUtils.selectedMarkers) {
                                    String origin = BoshysBTEUtils.markerOrigins.get(marker);
                                    if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                                        hasUnloadedMarkers = true;
                                        break;
                                    }
                                }

                                if (hasUnloadedMarkers) {
                                    context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.update.unloaded_error"));
                                    return 0;
                                }

                                int updatedCount = 0;
                                for (com.boshys.bteutils.data.MarkerData.TeleportMarker marker : BoshysBTEUtils.selectedMarkers) {
                                    com.boshys.bteutils.data.MarkerData.updateMarkerDesign(marker);
                                    updatedCount++;
                                }
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.updated.selected", updatedCount));
                            } else {
                                boolean hasUnloadedMarkers = false;
                                for (com.boshys.bteutils.data.MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                                    String origin = BoshysBTEUtils.markerOrigins.get(marker);
                                    if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                                        hasUnloadedMarkers = true;
                                        break;
                                    }
                                }

                                if (hasUnloadedMarkers) {
                                    context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.update.unloaded_error"));
                                    return 0;
                                }

                                int updatedCount = 0;
                                for (com.boshys.bteutils.data.MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                                    com.boshys.bteutils.data.MarkerData.updateMarkerDesign(marker);
                                    updatedCount++;
                                }
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.updated", updatedCount));
                            }

                            return 1;
                        }))

                // Move marker
                .then(ClientCommandManager.literal("moveMarker")
                        .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                                .then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
                                        .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> {
                                                    if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                                                        return 0;
                                                    }
                                                    if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
                                                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.no_selection"));
                                                        return 0;
                                                    }
                                                    double dx = DoubleArgumentType.getDouble(context, "x");
                                                    double dy = DoubleArgumentType.getDouble(context, "y");
                                                    double dz = DoubleArgumentType.getDouble(context, "z");
                                                    return markerStorage.moveSelectedMarkers(context.getSource(), dx, dy, dz);
                                                }))))
                        .executes(context -> {
                            if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                                return 0;
                            }
                            if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.no_selection"));
                                return 0;
                            }
                            ClientPlayerEntity player = context.getSource().getPlayer();
                            return markerStorage.moveSelectedMarkersToPosition(context.getSource(), player.getX(), player.getY(), player.getZ());
                        }))

                // Move all markers from a specific file
                .then(ClientCommandManager.literal("moveAllMarkers")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                .suggests(LOADABLE_FILE_SUGGESTIONS)
                                .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                                        .then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
                                                .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(context -> {
                                                            if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                                                                return 0;
                                                            }

                                                            String filename = StringArgumentType.getString(context, "filename");
                                                            double dx = DoubleArgumentType.getDouble(context, "x");
                                                            double dy = DoubleArgumentType.getDouble(context, "y");
                                                            double dz = DoubleArgumentType.getDouble(context, "z");

                                                            return markerStorage.moveAllMarkersInFile(context.getSource(), filename, dx, dy, dz);
                                                        }))))))

                // Save markers
                .then(ClientCommandManager.literal("saveMarkers")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename");
                                    return markerStorage.saveMarkersToFile(context.getSource(), filename, -1);
                                })
                                .then(ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0))
                                        .executes(context -> {
                                            String filename = StringArgumentType.getString(context, "filename");
                                            double radius = DoubleArgumentType.getDouble(context, "radius");
                                            return markerStorage.saveMarkersToFile(context.getSource(), filename, radius);
                                        }))))

                // Update markers
                .then(ClientCommandManager.literal("updateMarkers")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                .suggests(SAVED_FILE_SUGGESTIONS)
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename");
                                    return markerStorage.updateMarkerFile(context.getSource(), filename, -1);
                                })
                                .then(ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0))
                                        .executes(context -> {
                                            String filename = StringArgumentType.getString(context, "filename");
                                            double radius = DoubleArgumentType.getDouble(context, "radius");
                                            return markerStorage.updateMarkerFile(context.getSource(), filename, radius);
                                        }))))

                // Load
                .then(ClientCommandManager.literal("load")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.greedyString())
                                .suggests(LOADABLE_FILE_SUGGESTIONS)
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename").trim();
                                    if (filename.equals("*")) {
                                        return loadAllFiles(context.getSource());
                                    }
                                    return markerStorage.loadMarkerFile(context.getSource(), filename);
                                })))

                // Hide
                .then(ClientCommandManager.literal("hide")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.greedyString())
                                .suggests(LOADED_FILE_SUGGESTIONS)
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename").trim();
                                    if (filename.equals("*")) {
                                        return hideAllFiles(context.getSource());
                                    }
                                    return markerStorage.hideMarkerFile(context.getSource(), filename);
                                })))

                // Delete
                .then(ClientCommandManager.literal("delete")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.greedyString())
                                .suggests(ALL_FILE_SUGGESTIONS)
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename");
                                    return markerStorage.deleteMarkerFile(context.getSource(), filename);
                                })))

                // Merge markers
                .then(ClientCommandManager.literal("mergeMarkers")
                        .then(ClientCommandManager.argument("mergedFileName", StringArgumentType.string())
                                .then(ClientCommandManager.argument("includeCached", StringArgumentType.string())
                                        .suggests(INCLUDE_EXCLUDE_SUGGESTIONS)
                                        .then(ClientCommandManager.argument("file1", StringArgumentType.string())
                                                .suggests(MERGE_FILE_SUGGESTIONS)
                                                .executes(context -> executeMerge(context))
                                                .then(ClientCommandManager.argument("file2", StringArgumentType.string())
                                                        .suggests(MERGE_FILE_SUGGESTIONS)
                                                        .executes(context -> executeMerge(context))
                                                        .then(ClientCommandManager.argument("file3", StringArgumentType.string())
                                                                .suggests(MERGE_FILE_SUGGESTIONS)
                                                                .executes(context -> executeMerge(context))
                                                                .then(ClientCommandManager.argument("file4", StringArgumentType.string())
                                                                        .suggests(MERGE_FILE_SUGGESTIONS)
                                                                        .executes(context -> executeMerge(context))
                                                                        .then(ClientCommandManager.argument("file5", StringArgumentType.string())
                                                                                .suggests(MERGE_FILE_SUGGESTIONS)
                                                                                .executes(context -> executeMerge(context))))))))))

                // Import KML
                .then(ClientCommandManager.literal("importKML")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                .suggests(KML_FILE_SUGGESTIONS)
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename");
                                    return kmlImportHandler.importKmlFile(context.getSource(), filename);
                                })))
        );
    }

    private void sendFirstMarkerMessage(FabricClientCommandSource source) {
        // Send header
        source.sendFeedback(Text.literal("§7============= §aBoshy's BT-Utils §7============="));
        // Empty line
        source.sendFeedback(Text.literal(""));
        // Message 1
        source.sendFeedback(Text.translatable("command.boshysbteutils.marker.first_time.select"));
        // Empty line
        source.sendFeedback(Text.literal(""));
        // Message 2
        source.sendFeedback(Text.translatable("command.boshysbteutils.marker.first_time.multiselect"));
        // Empty line
        source.sendFeedback(Text.literal(""));
        // Message 3
        source.sendFeedback(Text.translatable("command.boshysbteutils.marker.first_time.move"));
    }

    private int loadAllFiles(FabricClientCommandSource source) {
        Path savePath = MarkerStorage.getMarkersSavePath();
        File dir = savePath.toFile();

        if (!dir.exists() || !dir.isDirectory()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.file.not_found", "*"));
            return 0;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".json") && !name.equals("autosave.json") && !name.startsWith("autosave_"));

        if (files == null || files.length == 0) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.file.not_found", "*"));
            return 0;
        }

        int loadedCount = 0;
        List<String> loadedFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();

        for (File file : files) {
            String name = file.getName().replace(".json", "");
            if (markerStorage.loadMarkerFileInternal(name, true)) {
                loadedCount++;
                loadedFiles.add(name);
            } else {
                failedFiles.add(name);
            }
        }

        if (loadedCount > 0) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.file.loaded.multiple", loadedCount, String.join(", ", loadedFiles)));
        }
        if (!failedFiles.isEmpty()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.file.load_failed.multiple", String.join(", ", failedFiles)));
        }

        return loadedCount > 0 ? 1 : 0;
    }

    private int hideAllFiles(FabricClientCommandSource source) {
        if (markerStorage.getLoadedFiles().isEmpty()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.file.none_loaded"));
            return 0;
        }

        List<String> filesToHide = new ArrayList<>(markerStorage.getLoadedFiles().keySet());
        int hiddenCount = 0;

        for (String filename : filesToHide) {
            if (markerStorage.hideMarkerFile(source, filename) == 1) {
                hiddenCount++;
            }
        }

        source.sendFeedback(Text.translatable("command.boshysbteutils.file.hidden.multiple", hiddenCount));
        return hiddenCount > 0 ? 1 : 0;
    }

    private int executeMerge(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        String mergedFileName = StringArgumentType.getString(context, "mergedFileName");
        String includeCached = StringArgumentType.getString(context, "includeCached");

        List<String> files = new ArrayList<>();

        try { files.add(StringArgumentType.getString(context, "file1")); } catch (Exception e) {}
        try { files.add(StringArgumentType.getString(context, "file2")); } catch (Exception e) {}
        try { files.add(StringArgumentType.getString(context, "file3")); } catch (Exception e) {}
        try { files.add(StringArgumentType.getString(context, "file4")); } catch (Exception e) {}
        try { files.add(StringArgumentType.getString(context, "file5")); } catch (Exception e) {}

        if (files.isEmpty()) {
            context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.merge.no_files"));
            return 0;
        }

        return markerStorage.mergeMarkerFiles(context.getSource(), mergedFileName,
                includeCached.equalsIgnoreCase("includeCachedMarkers"), files);
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestSavedFiles(com.mojang.brigadier.suggestion.SuggestionsBuilder builder, boolean includeAll, boolean includeAutosave) {
        String remaining = builder.getRemaining().toLowerCase();
        Path savePath = MarkerStorage.getMarkersSavePath();
        File dir = savePath.toFile();

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> {
                if (!name.endsWith(".json")) return false;
                if (name.equals("autosave.json")) return includeAutosave;
                if (name.startsWith("autosave_")) return includeAutosave;
                return true;
            });
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().replace(".json", "");
                    if (includeAll || !markerStorage.getLoadedFiles().containsKey(name)) {
                        if (name.toLowerCase().startsWith(remaining)) {
                            builder.suggest(name);
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestLoadableFilesWithWildcard(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();

        if ("*".startsWith(remaining)) {
            builder.suggest("*");
        }

        Path savePath = MarkerStorage.getMarkersSavePath();
        File dir = savePath.toFile();

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> {
                if (!name.endsWith(".json")) return false;
                if (name.equals("autosave.json")) return false;
                if (name.startsWith("autosave_")) return false;
                return true;
            });
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().replace(".json", "");
                    if (!markerStorage.getLoadedFiles().containsKey(name)) {
                        if (name.toLowerCase().startsWith(remaining)) {
                            builder.suggest(name);
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestLoadedFilesWithWildcard(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();

        if ("*".startsWith(remaining)) {
            builder.suggest("*");
        }

        for (String name : markerStorage.getLoadedFiles().keySet()) {
            if (name.toLowerCase().startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.build();
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestAllFilesWithExtensions(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        Path savePath = MarkerStorage.getMarkersSavePath();
        File dir = savePath.toFile();

        if (dir.exists() && dir.isDirectory()) {
            File[] jsonFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (jsonFiles != null) {
                for (File file : jsonFiles) {
                    String name = file.getName();
                    if (name.toLowerCase().startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }

            File[] kmlFiles = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".kml");
            });
            if (kmlFiles != null) {
                for (File file : kmlFiles) {
                    String name = file.getName();
                    if (name.toLowerCase().startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
        }
        return builder.build();
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestKmlFiles(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        Path kmlPath = MarkerStorage.getKmlSavePath();
        File dir = kmlPath.toFile();

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> {
                String lowerName = name.toLowerCase();
                return lowerName.endsWith(".kml");
            });
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().replace(".kml", "").replace(".KML", "");
                    if (name.toLowerCase().startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
        }
        return builder.build();
    }
}