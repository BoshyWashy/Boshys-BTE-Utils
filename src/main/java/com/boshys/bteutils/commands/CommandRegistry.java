package com.boshys.bteutils.commands;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.overlay.OverlayCommands;
import com.boshys.bteutils.overlay.OverlayStorage;
import com.boshys.bteutils.storage.MarkerStorage;
import com.boshys.bteutils.storage.KmlImportHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CommandRegistry {
    private final BoshysBTEUtils mod;
    private final MarkerStorage markerStorage;
    private final KmlImportHandler kmlImportHandler;
    private final OverlayStorage overlayStorage;

    // -----------------------------------------------------------------------
    // Suggestion providers
    // -----------------------------------------------------------------------

    private final SuggestionProvider<FabricClientCommandSource> SAVED_FILE_SUGGESTIONS = (context, builder) ->
            CompletableFuture.completedFuture(suggestSavedFiles(builder, true, false));

    private final SuggestionProvider<FabricClientCommandSource> LOADABLE_FILE_SUGGESTIONS = (context, builder) ->
            CompletableFuture.completedFuture(suggestLoadableFilesWithWildcard(builder));

    private final SuggestionProvider<FabricClientCommandSource> LOADED_FILE_SUGGESTIONS = (context, builder) ->
            CompletableFuture.completedFuture(suggestLoadedFilesWithWildcard(builder));

    private final SuggestionProvider<FabricClientCommandSource> ALL_FILE_SUGGESTIONS = (context, builder) ->
            CompletableFuture.completedFuture(suggestAllFilesWithExtensions(builder));

    private final SuggestionProvider<FabricClientCommandSource> MERGE_FILE_SUGGESTIONS = (context, builder) ->
            CompletableFuture.completedFuture(suggestSavedFiles(builder, true, true));

    private final SuggestionProvider<FabricClientCommandSource> INCLUDE_EXCLUDE_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();
        if ("includecachedmarkers".startsWith(remaining)) builder.suggest("includeCachedMarkers");
        if ("excludecachedmarkers".startsWith(remaining)) builder.suggest("excludeCachedMarkers");
        return CompletableFuture.completedFuture(builder.build());
    };

    private final SuggestionProvider<FabricClientCommandSource> KML_FILE_SUGGESTIONS = (context, builder) ->
            CompletableFuture.completedFuture(suggestKmlFiles(builder));

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CommandRegistry(BoshysBTEUtils mod, MarkerStorage markerStorage, KmlImportHandler kmlImportHandler) {
        this.mod = mod;
        this.markerStorage = markerStorage;
        this.kmlImportHandler = kmlImportHandler;
        this.overlayStorage = BoshysBTEUtils.getOverlayStorage();
    }

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {

        LiteralArgumentBuilder<FabricClientCommandSource> root =
                ClientCommandManager.literal("boshys-bt-utils");

        // ── clearMarkers ─────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("clearMarkers")
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
                        })));

        // ── confirmClear ─────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("confirmClear")
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
                }));

        // ── deselect ─────────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("deselect")
                .executes(context -> {
                    if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.deselect.none"));
                        return 0;
                    }
                    int count = BoshysBTEUtils.selectedMarkers.size();
                    BoshysBTEUtils.selectedMarkers.clear();
                    context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.deselected.all", count));
                    return 1;
                }));

        // ── addMarker ────────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("addMarker")
                .executes(context -> {
                    if (!BoshysBTEUtils.getConfig().enableMarkers) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                        return 0;
                    }
                    if (BoshysBTEUtils.markersHidden) {
                        if (!BoshysBTEUtils.hideWarningShown) {
                            context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
                            BoshysBTEUtils.hideWarningShown = true;
                        }
                        return 0;
                    }
                    ClientPlayerEntity player = context.getSource().getPlayer();
                    com.boshys.bteutils.data.MarkerData.TeleportMarker newMarker =
                            com.boshys.bteutils.data.MarkerData.addMarker(
                                    new net.minecraft.util.math.Vec3d(player.getX(), player.getY(), player.getZ()));
                    if (BoshysBTEUtils.getConfig().enableAutoLineConnection) {
                        com.boshys.bteutils.data.MarkerData.handleAutoConnect(newMarker);
                    }
                    if (!BoshysBTEUtils.hasAddedMarkerThisSession) {
                        sendFirstMarkerMessage(context.getSource());
                        BoshysBTEUtils.hasAddedMarkerThisSession = true;
                    } else {
                        player.sendMessage(Text.translatable("command.boshysbteutils.marker.added_actionbar")
                                .formatted(net.minecraft.util.Formatting.GREEN), true);
                    }
                    return 1;
                }));

        // ── tempHide ─────────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("tempHide")
                .then(ClientCommandManager.argument("hide", BoolArgumentType.bool())
                        .executes(context -> {
                            boolean shouldHide = BoolArgumentType.getBool(context, "hide");
                            if (shouldHide) {
                                if (BoshysBTEUtils.markersHidden) {
                                    context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.temphide.already_hidden"));
                                    return 0;
                                }
                                BoshysBTEUtils.hideAllMarkers();
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.temphide.hidden"));
                            } else {
                                if (!BoshysBTEUtils.markersHidden) {
                                    context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.temphide.already_shown"));
                                    return 0;
                                }
                                BoshysBTEUtils.showAllMarkers();
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.temphide.shown"));
                            }
                            return 1;
                        })));

        // ── updateMarkerDesign ───────────────────────────────────────────────
        root.then(ClientCommandManager.literal("updateMarkerDesign")
                .executes(context -> {
                    if (BoshysBTEUtils.markersHidden) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
                        return 0;
                    }
                    if (!BoshysBTEUtils.getConfig().enableMarkers) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                        return 0;
                    }
                    if (BoshysBTEUtils.markers.isEmpty()) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.no_markers"));
                        return 0;
                    }
                    if (!BoshysBTEUtils.selectedMarkers.isEmpty()) {
                        int n = 0;
                        for (com.boshys.bteutils.data.MarkerData.TeleportMarker m : BoshysBTEUtils.selectedMarkers) {
                            com.boshys.bteutils.data.MarkerData.updateMarkerDesign(m); n++;
                        }
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.updated.selected", n));
                    } else {
                        int n = 0;
                        for (com.boshys.bteutils.data.MarkerData.TeleportMarker m : BoshysBTEUtils.markers) {
                            com.boshys.bteutils.data.MarkerData.updateMarkerDesign(m); n++;
                        }
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.updated", n));
                    }
                    return 1;
                }));

        // ── moveMarker ───────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("moveMarker")
                .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                        .then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
                                .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(context -> {
                                            if (BoshysBTEUtils.markersHidden) {
                                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden")); return 0;
                                            }
                                            if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled")); return 0;
                                            }
                                            if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
                                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.no_selection")); return 0;
                                            }
                                            return markerStorage.moveSelectedMarkers(context.getSource(),
                                                    DoubleArgumentType.getDouble(context, "x"),
                                                    DoubleArgumentType.getDouble(context, "y"),
                                                    DoubleArgumentType.getDouble(context, "z"));
                                        }))))
                .executes(context -> {
                    if (BoshysBTEUtils.markersHidden) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden")); return 0;
                    }
                    if (!BoshysBTEUtils.getConfig().enableMarkers) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled")); return 0;
                    }
                    if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.marker.no_selection")); return 0;
                    }
                    ClientPlayerEntity player = context.getSource().getPlayer();
                    return markerStorage.moveSelectedMarkersToPosition(context.getSource(), player.getX(), player.getY(), player.getZ());
                }));

        // ── moveAllSavedMarkers ──────────────────────────────────────────────
        // (previously "moveAllMarkers" — renamed to avoid confusion with the new moveAllTempMarkers)
        root.then(ClientCommandManager.literal("moveAllSavedMarkers")
                .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                        .suggests(LOADABLE_FILE_SUGGESTIONS)
                        .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                                .then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
                                        .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> {
                                                    if (BoshysBTEUtils.markersHidden) {
                                                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden")); return 0;
                                                    }
                                                    if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled")); return 0;
                                                    }
                                                    return markerStorage.moveAllMarkersInFile(context.getSource(),
                                                            StringArgumentType.getString(context, "filename"),
                                                            DoubleArgumentType.getDouble(context, "x"),
                                                            DoubleArgumentType.getDouble(context, "y"),
                                                            DoubleArgumentType.getDouble(context, "z"));
                                                }))))));

        // ── moveAllTempMarkers ───────────────────────────────────────────────
        // Moves all cached (unsaved) markers — optionally filtered by radius.
        root.then(ClientCommandManager.literal("moveAllTempMarkers")
                .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                        .then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
                                .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(context -> {
                                            if (BoshysBTEUtils.markersHidden) {
                                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden")); return 0;
                                            }
                                            if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled")); return 0;
                                            }
                                            return moveAllTempMarkers(context.getSource(),
                                                    DoubleArgumentType.getDouble(context, "x"),
                                                    DoubleArgumentType.getDouble(context, "y"),
                                                    DoubleArgumentType.getDouble(context, "z"),
                                                    -1);
                                        })
                                        .then(ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0))
                                                .executes(context -> {
                                                    if (BoshysBTEUtils.markersHidden) {
                                                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden")); return 0;
                                                    }
                                                    if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled")); return 0;
                                                    }
                                                    return moveAllTempMarkers(context.getSource(),
                                                            DoubleArgumentType.getDouble(context, "x"),
                                                            DoubleArgumentType.getDouble(context, "y"),
                                                            DoubleArgumentType.getDouble(context, "z"),
                                                            DoubleArgumentType.getDouble(context, "radius"));
                                                }))))));

        // ── saveMarkers ──────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("saveMarkers")
                .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                        .executes(context -> markerStorage.saveMarkersToFile(context.getSource(),
                                StringArgumentType.getString(context, "filename"), -1))
                        .then(ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0))
                                .executes(context -> markerStorage.saveMarkersToFile(context.getSource(),
                                        StringArgumentType.getString(context, "filename"),
                                        DoubleArgumentType.getDouble(context, "radius"))))));

        // ── updateMarkers ────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("updateMarkers")
                .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                        .suggests(SAVED_FILE_SUGGESTIONS)
                        .executes(context -> markerStorage.updateMarkerFile(context.getSource(),
                                StringArgumentType.getString(context, "filename"), -1))
                        .then(ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0))
                                .executes(context -> markerStorage.updateMarkerFile(context.getSource(),
                                        StringArgumentType.getString(context, "filename"),
                                        DoubleArgumentType.getDouble(context, "radius"))))));

        // ── load ─────────────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("load")
                .then(ClientCommandManager.argument("filename", StringArgumentType.greedyString())
                        .suggests(LOADABLE_FILE_SUGGESTIONS)
                        .executes(context -> {
                            String filename = StringArgumentType.getString(context, "filename").trim();
                            if (filename.equals("*")) return loadAllFiles(context.getSource());
                            return markerStorage.loadMarkerFile(context.getSource(), filename);
                        })));

        // ── hide ─────────────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("hide")
                .then(ClientCommandManager.argument("filename", StringArgumentType.greedyString())
                        .suggests(LOADED_FILE_SUGGESTIONS)
                        .executes(context -> {
                            String filename = StringArgumentType.getString(context, "filename").trim();
                            if (filename.equals("*")) return hideAllFiles(context.getSource());
                            return markerStorage.hideMarkerFile(context.getSource(), filename);
                        })));

        // ── delete ───────────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("delete")
                .then(ClientCommandManager.argument("filename", StringArgumentType.greedyString())
                        .suggests(ALL_FILE_SUGGESTIONS)
                        .executes(context -> markerStorage.deleteMarkerFile(context.getSource(),
                                StringArgumentType.getString(context, "filename")))));

        // ── mergeMarkers ─────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("mergeMarkers")
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
                                                                        .executes(context -> executeMerge(context))))))))));

        // ── importKML ────────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("importKML")
                .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                        .suggests(KML_FILE_SUGGESTIONS)
                        .executes(context -> {
                            if (BoshysBTEUtils.markersHidden) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
                                return 0;
                            }
                            return kmlImportHandler.importKmlFile(context.getSource(),
                                    StringArgumentType.getString(context, "filename"));
                        })));

        // ── importMultipleKMLs ───────────────────────────────────────────────
        root.then(ClientCommandManager.literal("importMultipleKMLs")
                .executes(context -> {
                    if (BoshysBTEUtils.markersHidden) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
                        return 0;
                    }
                    context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.kml.queue.no_files"));
                    return 0;
                })
                .then(buildImportMultipleKmls()));

        // ── stopImport ───────────────────────────────────────────────────────
        // Stops any active KML import or queued imports immediately.
        root.then(ClientCommandManager.literal("stopImport")
                .executes(context -> {
                    kmlImportHandler.stopImport(context.getSource().getClient());
                    return 1;
                }));

        // ── markerFileLocation ───────────────────────────────────────────────
        root.then(ClientCommandManager.literal("markerFileLocation")
                .executes(context -> {
                    Path savePath = MarkerStorage.getMarkersSavePath();
                    File dir = savePath.toFile();
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    // Open the folder in the OS file manager
                    boolean opened = false;
                    try {
                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                            Desktop.getDesktop().open(dir);
                            opened = true;
                        }
                    } catch (IOException | UnsupportedOperationException ignored) {
                        // Desktop API unavailable, will try fallback
                    }
                    // Fallback: try to reveal the folder using OS-specific commands
                    if (!opened) {
                        String os = System.getProperty("os.name").toLowerCase();
                        try {
                            if (os.contains("win")) {
                                Runtime.getRuntime().exec("explorer.exe \"" + dir.getAbsolutePath() + "\"");
                                opened = true;
                            } else if (os.contains("mac")) {
                                Runtime.getRuntime().exec(new String[]{"open", dir.getAbsolutePath()});
                                opened = true;
                            } else {
                                Runtime.getRuntime().exec(new String[]{"xdg-open", dir.getAbsolutePath()});
                                opened = true;
                            }
                        } catch (IOException ignored) {
                            // Fallback also failed
                        }
                    }
                    if (opened) {
                        context.getSource().sendFeedback(Text.translatable(
                                "command.boshysbteutils.file.location.opened", savePath.toAbsolutePath().toString()));
                    } else {
                        context.getSource().sendFeedback(Text.translatable(
                                "command.boshysbteutils.file.location.failed", savePath.toAbsolutePath().toString()));
                    }
                    return 1;
                }));

        // ── createCircle ─────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("createCircle")
                .then(ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0.1))
                        .executes(context -> {
                            if (BoshysBTEUtils.markersHidden) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
                                return 0;
                            }
                            if (!BoshysBTEUtils.getConfig().enableMarkers) {
                                context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                                return 0;
                            }
                            double radius = DoubleArgumentType.getDouble(context, "radius");
                            ClientPlayerEntity player = context.getSource().getPlayer();

                            if (!BoshysBTEUtils.selectedMarkers.isEmpty()) {
                                // Attach circle to each selected marker
                                int count = 0;
                                for (com.boshys.bteutils.data.MarkerData.TeleportMarker marker : BoshysBTEUtils.selectedMarkers) {
                                    marker.circleRadius = radius;
                                    count++;
                                }
                                context.getSource().sendFeedback(Text.translatable(
                                        "command.boshysbteutils.circle.created.selected", count, radius));
                            } else {
                                // Create a new marker at player position and attach circle
                                Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
                                com.boshys.bteutils.data.MarkerData.TeleportMarker newMarker =
                                        com.boshys.bteutils.data.MarkerData.addMarker(pos);
                                newMarker.circleRadius = radius;
                                context.getSource().sendFeedback(Text.translatable(
                                        "command.boshysbteutils.circle.created.new", radius));
                            }
                            return 1;
                        })));

        // ── removeCircle ─────────────────────────────────────────────────────
        root.then(ClientCommandManager.literal("removeCircle")
                .executes(context -> {
                    if (BoshysBTEUtils.markersHidden) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
                        return 0;
                    }
                    if (!BoshysBTEUtils.getConfig().enableMarkers) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
                        return 0;
                    }
                    if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.circle.no_selection"));
                        return 0;
                    }
                    int count = 0;
                    for (com.boshys.bteutils.data.MarkerData.TeleportMarker marker : BoshysBTEUtils.selectedMarkers) {
                        if (marker.circleRadius > 0) {
                            marker.circleRadius = 0;
                            count++;
                        }
                    }
                    if (count == 0) {
                        context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.circle.none_had_circle"));
                        return 0;
                    }
                    context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.circle.removed", count));
                    return 1;
                }));

        // ── resetManualTpllLinesSequence ────────────────────────────────────
        root.then(ClientCommandManager.literal("resetManualTpllLinesSequence")
                .executes(context -> {
                    BoshysBTEUtils.INSTANCE.resetManualTpllWeLinesSequence();
                    context.getSource().sendFeedback(Text.translatable(
                            "command.boshysbteutils.manual_we_lines.reset"));
                    return 1;
                }));

        // ── overlay ──────────────────────────────────────────────────────────
        root.then(new OverlayCommands(overlayStorage).build());

        dispatcher.register(root);
    }

    // -----------------------------------------------------------------------
    // moveAllTempMarkers helper
    // -----------------------------------------------------------------------

    private int moveAllTempMarkers(FabricClientCommandSource source, double dx, double dy, double dz, double radius) {
        ClientPlayerEntity player = source.getPlayer();
        Vec3d playerPos = player != null ? new Vec3d(player.getX(), player.getY(), player.getZ()) : null;

        int movedCount = 0;
        for (com.boshys.bteutils.data.MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            // Only move cache (unsaved) markers
            if (origin != null && !origin.equals("autosave") && !origin.startsWith("autosave_")) {
                continue;
            }
            // Apply optional radius filter centred on the player
            if (radius >= 0 && playerPos != null && marker.position.distanceTo(playerPos) > radius) {
                continue;
            }
            marker.position = marker.position.add(dx, dy, dz);
            movedCount++;
        }

        if (movedCount == 0) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.error.no_cache_markers"));
            return 0;
        }

        source.sendFeedback(Text.translatable("command.boshysbteutils.marker.temp_moved", movedCount, dx, dy, dz));
        return 1;
    }

    // -----------------------------------------------------------------------
    // importMultipleKMLs builder — extracted to keep nesting manageable
    // -----------------------------------------------------------------------

    private LiteralArgumentBuilder<FabricClientCommandSource> buildImportMultipleKmls() {
        var file10 = ClientCommandManager.argument("file10", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 10));

        var file9 = ClientCommandManager.argument("file9", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 9))
                .then(file10);

        var file8 = ClientCommandManager.argument("file8", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 8))
                .then(file9);

        var file7 = ClientCommandManager.argument("file7", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 7))
                .then(file8);

        var file6 = ClientCommandManager.argument("file6", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 6))
                .then(file7);

        var file5 = ClientCommandManager.argument("file5", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 5))
                .then(file6);

        var file4 = ClientCommandManager.argument("file4", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 4))
                .then(file5);

        var file3 = ClientCommandManager.argument("file3", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 3))
                .then(file4);

        var file2 = ClientCommandManager.argument("file2", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 2))
                .then(file3);

        var file1 = ClientCommandManager.argument("file1", StringArgumentType.string())
                .suggests(KML_FILE_SUGGESTIONS)
                .executes(ctx -> executeMultipleKmlImport(ctx, 1))
                .then(file2);

        return ClientCommandManager.literal("importMultipleKMLs").then(file1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int executeMultipleKmlImport(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, int argCount) {
        if (BoshysBTEUtils.markersHidden) {
            context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
            return 0;
        }
        List<String> files = new ArrayList<>();
        try { files.add(StringArgumentType.getString(context, "file1")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file2")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file3")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file4")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file5")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file6")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file7")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file8")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file9")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file10")); } catch (Exception ignored) {}

        if (files.isEmpty()) {
            context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.kml.queue.no_files"));
            return 0;
        }
        return kmlImportHandler.importMultipleKmlFiles(context.getSource(), files);
    }

    private void sendFirstMarkerMessage(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("§7============= §aBoshy's BT-Utils §7============="));
        source.sendFeedback(Text.literal(""));
        source.sendFeedback(Text.translatable("command.boshysbteutils.marker.first_time.select"));
        source.sendFeedback(Text.literal(""));
        source.sendFeedback(Text.translatable("command.boshysbteutils.marker.first_time.multiselect"));
        source.sendFeedback(Text.literal(""));
        source.sendFeedback(Text.translatable("command.boshysbteutils.marker.first_time.move"));
    }

    private int loadAllFiles(FabricClientCommandSource source) {
        Path savePath = MarkerStorage.getMarkersSavePath();
        File dir = savePath.toFile();

        if (!dir.exists() || !dir.isDirectory()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.file.not_found", "*"));
            return 0;
        }

        File[] files = dir.listFiles((d, name) ->
                name.endsWith(".json") && !name.equals("autosave.json") && !name.startsWith("autosave_"));

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
            if (markerStorage.hideMarkerFile(source, filename) == 1) hiddenCount++;
        }
        source.sendFeedback(Text.translatable("command.boshysbteutils.file.hidden.multiple", hiddenCount));
        return hiddenCount > 0 ? 1 : 0;
    }

    private int executeMerge(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        String mergedFileName = StringArgumentType.getString(context, "mergedFileName");
        String includeCached = StringArgumentType.getString(context, "includeCached");

        List<String> files = new ArrayList<>();
        try { files.add(StringArgumentType.getString(context, "file1")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file2")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file3")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file4")); } catch (Exception ignored) {}
        try { files.add(StringArgumentType.getString(context, "file5")); } catch (Exception ignored) {}

        if (files.isEmpty()) {
            context.getSource().sendFeedback(Text.translatable("command.boshysbteutils.merge.no_files"));
            return 0;
        }
        return markerStorage.mergeMarkerFiles(context.getSource(), mergedFileName,
                includeCached.equalsIgnoreCase("includeCachedMarkers"), files);
    }

    // -----------------------------------------------------------------------
    // Suggestion helpers
    // -----------------------------------------------------------------------

    private com.mojang.brigadier.suggestion.Suggestions suggestSavedFiles(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder, boolean includeAll, boolean includeAutosave) {
        String remaining = builder.getRemaining().toLowerCase();
        File dir = MarkerStorage.getMarkersSavePath().toFile();

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
                        if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
                    }
                }
            }
        }
        return builder.build();
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestLoadableFilesWithWildcard(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        if ("*".startsWith(remaining)) builder.suggest("*");

        File dir = MarkerStorage.getMarkersSavePath().toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) ->
                    name.endsWith(".json") && !name.equals("autosave.json") && !name.startsWith("autosave_"));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().replace(".json", "");
                    if (!markerStorage.getLoadedFiles().containsKey(name) && name.toLowerCase().startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
        }
        return builder.build();
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestLoadedFilesWithWildcard(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        if ("*".startsWith(remaining)) builder.suggest("*");
        for (String name : markerStorage.getLoadedFiles().keySet()) {
            if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
        }
        return builder.build();
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestAllFilesWithExtensions(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        File dir = MarkerStorage.getMarkersSavePath().toFile();

        if (dir.exists() && dir.isDirectory()) {
            File[] jsonFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (jsonFiles != null) {
                for (File f : jsonFiles) {
                    if (f.getName().toLowerCase().startsWith(remaining)) builder.suggest(f.getName());
                }
            }
            File[] kmlFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".kml"));
            if (kmlFiles != null) {
                for (File f : kmlFiles) {
                    if (f.getName().toLowerCase().startsWith(remaining)) builder.suggest(f.getName());
                }
            }
        }
        return builder.build();
    }

    private com.mojang.brigadier.suggestion.Suggestions suggestKmlFiles(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        File dir = MarkerStorage.getKmlSavePath().toFile();

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".kml"));
            if (files != null) {
                for (File f : files) {
                    String name = f.getName().replaceAll("(?i)\\.kml$", "");
                    if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
                }
            }
        }
        return builder.build();
    }
}