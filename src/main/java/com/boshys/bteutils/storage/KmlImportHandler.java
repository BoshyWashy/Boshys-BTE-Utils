package com.boshys.bteutils.storage;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.boshys.bteutils.data.MarkerData;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KmlImportHandler {
    private final BoshysBTEUtils mod;

    private boolean isKmlImporting = false;
    private boolean kmlImportWaitingToStart = false;
    private int kmlImportStartDelayTicks = 0;
    private List<MarkerData.KmlPoint> pendingKmlPoints = new ArrayList<>();
    private int kmlCurrentPointIndex = 0;
    private String currentKmlFileName = "";
    private List<String> kmlPostCommandsList = new ArrayList<>();
    private int kmlPostCommandIndex = 0;
    private boolean kmlWaitingForPostCommand = false;
    private int kmlPostCommandTickCounter = 0;

    private boolean worldEditLinesActive = false;
    private int worldEditCommandIndex = 0;
    private List<String> worldEditCommandQueue = new ArrayList<>();
    private int worldEditCommandTickCounter = 0;
    private boolean waitingForWorldEditCommand = false;
    private boolean worldEditSetupComplete = false;

    // Enhanced teleport detection for bad ping
    private boolean waitingForTeleport = false;
    private Vec3d positionBeforeTpll = null;
    private Vec3d lastCheckedPosition = null;
    private int teleportCheckTicks = 0;
    private static final int TELEPORT_TIMEOUT_TICKS = 100; // 5 seconds max wait
    private static final double MINIMUM_MOVEMENT = 0.001; // Changed from 0.5 to 0.001 for precise detection
    private int stablePositionTicks = 0; // How long position has been stable after movement

    private boolean inWorldEditSetupPhase = false;
    private boolean inSpectatorSetupPhase = false;
    private int setupCommandIndex = 0;
    private List<String> setupCommands = new ArrayList<>();

    // Cooldown counter
    private int cooldownTicks = 0;

    // Multiple KML import queue
    private List<String> kmlFileQueue = new ArrayList<>();
    private int currentKmlFileIndex = 0;
    private boolean isProcessingQueue = false;
    private int queueDelayTicks = 0;
    private boolean waitingBetweenFiles = false;

    public KmlImportHandler(BoshysBTEUtils mod) {
        this.mod = mod;
    }

    public boolean isImporting() {
        return isKmlImporting || kmlImportWaitingToStart;
    }

    public boolean isProcessingQueue() {
        return isProcessingQueue;
    }

    public void tick(MinecraftClient client) {
        if (kmlImportWaitingToStart) {
            if (kmlImportStartDelayTicks > 0) {
                kmlImportStartDelayTicks--;
                return;
            }

            kmlImportWaitingToStart = false;
            isKmlImporting = true;
            kmlCurrentPointIndex = 0;
            worldEditSetupComplete = false;
            waitingForTeleport = false;
            teleportCheckTicks = 0;
            stablePositionTicks = 0;
            inWorldEditSetupPhase = false;
            inSpectatorSetupPhase = false;
            cooldownTicks = 0;
            positionBeforeTpll = null;
            lastCheckedPosition = null;

            showImportTitle(client,
                    Text.translatable("command.boshysbteutils.kml.import.title.active").getString(),
                    Text.translatable("command.boshysbteutils.kml.import.subtitle.dont_move").getString()
            );

            // Always start with spectator setup, regardless of WorldEdit lines setting
            startSpectatorSetup(client);
            return;
        }

        if (!isKmlImporting || pendingKmlPoints.isEmpty()) {
            return;
        }

        // Handle queue delay between files
        if (waitingBetweenFiles) {
            if (queueDelayTicks > 0) {
                queueDelayTicks--;
                return;
            }

            // Delay finished, start next file
            waitingBetweenFiles = false;
            startNextKmlInQueue(client);
            return;
        }

        // Handle spectator setup phase (runs for both WorldEdit enabled and disabled)
        if (inSpectatorSetupPhase) {
            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }
            executeNextSpectatorSetupCommand(client);
            return;
        }

        // Handle WorldEdit setup phase (only when WorldEdit enabled)
        if (inWorldEditSetupPhase) {
            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }
            executeNextSetupCommand(client);
            return;
        }

        // Enhanced teleport detection with bad ping compensation
        if (waitingForTeleport) {
            if (client.player == null || positionBeforeTpll == null) {
                // Safety fallback - player is null, skip this point
                waitingForTeleport = false;
                teleportCheckTicks = 0;
                placeMarkerAndContinue(client, true); // true = forced/skip
                return;
            }

            Vec3d currentPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
            double distanceMoved = currentPos.distanceTo(positionBeforeTpll);

            // Update title with progress
            if (teleportCheckTicks % 20 == 0) { // Update every second
                showImportTitle(client,
                        Text.translatable("command.boshysbteutils.kml.import.title.active").getString(),
                        Text.translatable("command.boshysbteutils.kml.import.progress",
                                kmlCurrentPointIndex + 1, pendingKmlPoints.size()).getString()
                );
            }

            // Check if we've moved significantly (teleport completed)
            if (distanceMoved > MINIMUM_MOVEMENT) {
                // We've moved! But wait a moment to ensure position is stable (server lag compensation)
                if (lastCheckedPosition != null) {
                    double movementSinceLastTick = currentPos.distanceTo(lastCheckedPosition);
                    if (movementSinceLastTick < 0.01) {
                        // Position is stable (not still moving)
                        stablePositionTicks++;
                        if (stablePositionTicks >= 2) { // Wait 2 ticks of stability
                            waitingForTeleport = false;
                            placeMarkerAndContinue(client, false);
                            return;
                        }
                    } else {
                        // Still moving, reset stability counter
                        stablePositionTicks = 0;
                    }
                } else {
                    // Haven't moved yet, reset stability
                    stablePositionTicks = 0;
                }
            }

            lastCheckedPosition = currentPos;
            teleportCheckTicks++;

            // Timeout check - if we've waited too long, force continue
            if (teleportCheckTicks >= TELEPORT_TIMEOUT_TICKS) {
                waitingForTeleport = false;
                placeMarkerAndContinue(client, true); // true = forced due to timeout
            }
            return;
        }

        // Handle WorldEdit command queue
        if (waitingForWorldEditCommand) {
            if (worldEditCommandTickCounter > 0) {
                worldEditCommandTickCounter--;
                return;
            }
            executeNextWorldEditCommand(client);
            return;
        }

        // Handle post-import commands
        if (kmlWaitingForPostCommand) {
            if (kmlPostCommandTickCounter > 0) {
                kmlPostCommandTickCounter--;
                return;
            }
            executeNextPostCommand(client);
            return;
        }

        // Cooldown between points
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // Ready for next point
        processNextKmlPoint(client);
    }

    /**
     * Stops any active KML import, including queued imports.
     * Clears all import state so a new import can be started cleanly.
     */
    public void stopImport(MinecraftClient client) {
        boolean wasActive = isKmlImporting || kmlImportWaitingToStart || isProcessingQueue;

        // Reset all import state
        isKmlImporting = false;
        kmlImportWaitingToStart = false;
        kmlImportStartDelayTicks = 0;
        pendingKmlPoints.clear();
        kmlCurrentPointIndex = 0;
        currentKmlFileName = "";
        kmlPostCommandsList.clear();
        kmlPostCommandIndex = 0;
        kmlWaitingForPostCommand = false;
        kmlPostCommandTickCounter = 0;

        worldEditLinesActive = false;
        worldEditCommandIndex = 0;
        worldEditCommandQueue.clear();
        worldEditCommandTickCounter = 0;
        waitingForWorldEditCommand = false;
        worldEditSetupComplete = false;

        waitingForTeleport = false;
        positionBeforeTpll = null;
        lastCheckedPosition = null;
        teleportCheckTicks = 0;
        stablePositionTicks = 0;

        inWorldEditSetupPhase = false;
        inSpectatorSetupPhase = false;
        setupCommandIndex = 0;
        setupCommands.clear();

        cooldownTicks = 0;

        kmlFileQueue.clear();
        currentKmlFileIndex = 0;
        isProcessingQueue = false;
        queueDelayTicks = 0;
        waitingBetweenFiles = false;

        // Clear the import title/subtitle from screen
        if (client != null && client.inGameHud != null) {
            client.inGameHud.setTitle(Text.literal(""));
            client.inGameHud.setSubtitle(Text.literal(""));
        }

        // Notify user
        if (client != null && client.player != null) {
            if (wasActive) {
                client.player.sendMessage(
                        Text.translatable("command.boshysbteutils.kml.import.stopped").formatted(net.minecraft.util.Formatting.YELLOW),
                        false
                );
            } else {
                client.player.sendMessage(
                        Text.translatable("command.boshysbteutils.kml.import.no_active").formatted(net.minecraft.util.Formatting.RED),
                        false
                );
            }
        }
    }

    private void startSpectatorSetup(MinecraftClient client) {
        inSpectatorSetupPhase = true;
        setupCommandIndex = 0;
        setupCommands.clear();

        // Always switch to spectator mode first
        setupCommands.add("gamemode spectator");

        cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
        executeNextSpectatorSetupCommand(client);
    }

    private void executeNextSpectatorSetupCommand(MinecraftClient client) {
        if (!isKmlImporting || client.player == null) {
            inSpectatorSetupPhase = false;
            return;
        }

        if (setupCommandIndex >= setupCommands.size()) {
            inSpectatorSetupPhase = false;

            // Now check if we need WorldEdit setup or go straight to processing
            if (BoshysBTEUtils.getConfig().enableWorldEditLines) {
                startWorldEditSetup(client);
            } else {
                worldEditSetupComplete = true;
                cooldownTicks = 0;
                processNextKmlPoint(client);
            }
            return;
        }

        String command = setupCommands.get(setupCommandIndex);
        setupCommandIndex++;

        client.player.networkHandler.sendChatCommand(command);
        cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
    }

    private void startWorldEditSetup(MinecraftClient client) {
        inWorldEditSetupPhase = true;
        setupCommandIndex = 0;
        setupCommands.clear();

        setupCommands.add("/sel");
        setupCommands.add("/sel cuboid");

        cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
        executeNextSetupCommand(client);
    }

    private void executeNextSetupCommand(MinecraftClient client) {
        if (!isKmlImporting || client.player == null) {
            inWorldEditSetupPhase = false;
            return;
        }

        if (setupCommandIndex >= setupCommands.size()) {
            inWorldEditSetupPhase = false;
            worldEditSetupComplete = true;
            worldEditLinesActive = true;
            cooldownTicks = 0;
            processNextKmlPoint(client);
            return;
        }

        String command = setupCommands.get(setupCommandIndex);
        setupCommandIndex++;

        client.player.networkHandler.sendChatCommand(command);
        cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
    }

    private void processNextKmlPoint(MinecraftClient client) {
        if (!isKmlImporting || client.player == null) {
            return;
        }

        if (kmlCurrentPointIndex >= pendingKmlPoints.size()) {
            finishKmlImport(client);
            return;
        }

        MarkerData.KmlPoint point = pendingKmlPoints.get(kmlCurrentPointIndex);

        // Build TPLL command based on altitude mode
        String tpllCommand = buildTpllCommand(point);

        // Store position before TPLL for teleport detection
        positionBeforeTpll = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        lastCheckedPosition = null;
        stablePositionTicks = 0;
        teleportCheckTicks = 0;

        client.player.networkHandler.sendChatCommand(tpllCommand);

        waitingForTeleport = true;

        // Show initial progress
        if (kmlCurrentPointIndex % 5 == 0 || kmlCurrentPointIndex >= pendingKmlPoints.size() - 1) {
            showImportTitle(client,
                    Text.translatable("command.boshysbteutils.kml.import.title.active").getString(),
                    Text.translatable("command.boshysbteutils.kml.import.progress",
                            kmlCurrentPointIndex + 1, pendingKmlPoints.size()).getString()
            );
        }
    }

    /**
     * Builds the TPLL command based on the configured altitude mode and offset
     */
    private String buildTpllCommand(MarkerData.KmlPoint point) {
        String commandPrefix = BoshysBTEUtils.getConfig().commandPrefix;
        double altitude = 0;
        boolean includeAltitude = false;

        BoshysBTEUtilsConfig.AltitudeMode mode = BoshysBTEUtils.getConfig().kmlAltitudeMode;
        double offset = BoshysBTEUtils.getConfig().kmlAltitudeOffset;

        switch (mode) {
            case AUTOMATIC:
                // No altitude argument - places at highest non-air block
                return commandPrefix + " " + point.latitude + ", " + point.longitude;

            case KML_ALTITUDES:
                // Use altitude from KML file plus offset
                altitude = point.altitude + offset;
                includeAltitude = true;
                break;

            case LOCKED:
                // Use locked altitude value plus offset
                altitude = BoshysBTEUtils.getConfig().kmlLockedAltitudeValue + offset;
                includeAltitude = true;
                break;
        }

        if (includeAltitude) {
            // Format: /tpll <lat>, <lon> <altitude>
            return commandPrefix + " " + point.latitude + ", " + point.longitude + " " + altitude;
        }

        return commandPrefix + " " + point.latitude + ", " + point.longitude;
    }

    public void onMarkerPlaced(MinecraftClient client) {
        // Backup detection method via mixin
        if (!isKmlImporting || !waitingForTeleport) return;

        if (client.player != null && positionBeforeTpll != null) {
            Vec3d currentPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
            if (currentPos.distanceTo(positionBeforeTpll) > MINIMUM_MOVEMENT) {
                waitingForTeleport = false;
                placeMarkerAndContinue(client, false);
            }
        }
    }

    private void placeMarkerAndContinue(MinecraftClient client) {
        placeMarkerAndContinue(client, false);
    }

    private void placeMarkerAndContinue(MinecraftClient client, boolean forced) {
        if (client.player == null) {
            kmlCurrentPointIndex++;
            return;
        }

        // Place marker at current position (where player teleported to)
        MarkerData.TeleportMarker newMarker = MarkerData.addMarker(
                new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ())
        );

        // Auto-connect with previous marker only if auto-line-connection is enabled
        if (BoshysBTEUtils.getConfig().enableAutoLineConnection
                && BoshysBTEUtils.lastAddedMarker != null
                && BoshysBTEUtils.lastAddedMarker != newMarker) {
            MarkerData.connectMarkers(BoshysBTEUtils.lastAddedMarker, newMarker);
        }

        BoshysBTEUtils.lastAddedMarker = newMarker;
        BoshysBTEUtils.selectedMarkers.clear();
        BoshysBTEUtils.selectedMarkers.add(newMarker);

        // Send action bar message if forced (timeout)
        if (forced && client.player != null) {
            client.player.sendMessage(
                    Text.translatable("command.boshysbteutils.kml.import.timeout_warning",
                            kmlCurrentPointIndex + 1).formatted(net.minecraft.util.Formatting.YELLOW),
                    true
            );
        }

        // Advance to next point
        kmlCurrentPointIndex++;

        // Handle WorldEdit lines if enabled
        if (BoshysBTEUtils.getConfig().enableWorldEditLines && worldEditLinesActive) {
            buildWorldEditCommandQueue();
            if (!worldEditCommandQueue.isEmpty()) {
                waitingForWorldEditCommand = true;
                worldEditCommandTickCounter = 0;
                executeNextWorldEditCommand(client);
                return;
            }
        }

        // Handle post-import commands
        if (!kmlPostCommandsList.isEmpty()) {
            kmlWaitingForPostCommand = true;
            kmlPostCommandIndex = 0;
            kmlPostCommandTickCounter = 0;
            executeNextPostCommand(client);
            return;
        }

        // Set cooldown for next TPLL
        cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
    }

    private void buildWorldEditCommandQueue() {
        worldEditCommandQueue.clear();
        worldEditCommandIndex = 0;

        String block = BoshysBTEUtils.getConfig().worldEditLineBlock;
        int currentPoint = kmlCurrentPointIndex;

        if (currentPoint == 1) { // First point (index already incremented)
            worldEditCommandQueue.add("/pos1");
        } else if (currentPoint > 1 && currentPoint <= pendingKmlPoints.size()) {
            worldEditCommandQueue.add("/pos2");
            worldEditCommandQueue.add("/line " + block);
            worldEditCommandQueue.add("/pos1");
        }
    }

    private void executeNextWorldEditCommand(MinecraftClient client) {
        if (!isKmlImporting || client.player == null) {
            waitingForWorldEditCommand = false;
            return;
        }

        if (worldEditCommandIndex >= worldEditCommandQueue.size()) {
            waitingForWorldEditCommand = false;

            if (!kmlPostCommandsList.isEmpty()) {
                kmlWaitingForPostCommand = true;
                kmlPostCommandIndex = 0;
                kmlPostCommandTickCounter = 0;
                executeNextPostCommand(client);
                return;
            }

            cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
            return;
        }

        String command = worldEditCommandQueue.get(worldEditCommandIndex);
        worldEditCommandIndex++;

        client.player.networkHandler.sendChatCommand(command);
        worldEditCommandTickCounter = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
    }

    private void executeNextPostCommand(MinecraftClient client) {
        if (!isKmlImporting || client.player == null) {
            kmlWaitingForPostCommand = false;
            return;
        }

        if (kmlPostCommandIndex >= kmlPostCommandsList.size()) {
            kmlWaitingForPostCommand = false;
            kmlPostCommandIndex = 0;
            cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
            return;
        }

        String cmd = kmlPostCommandsList.get(kmlPostCommandIndex);
        kmlPostCommandIndex++;

        String commandToSend = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        if (cmd.startsWith("//")) {
            commandToSend = cmd.substring(1);
        }
        client.player.networkHandler.sendChatCommand(commandToSend);

        kmlPostCommandTickCounter = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
    }

    private void showImportTitle(MinecraftClient client, String title, String subtitle) {
        if (client.player != null && client.inGameHud != null) {
            client.inGameHud.setTitle(Text.literal(title));
            client.inGameHud.setSubtitle(Text.literal(subtitle));
        }
    }

    public int importKmlFile(FabricClientCommandSource source, String filename) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
            return 0;
        }

        if (BoshysBTEUtils.markersHidden) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
            return 0;
        }

        if (isKmlImporting || kmlImportWaitingToStart) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.in_progress"));
            return 0;
        }

        String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        if (cleanFilename.isEmpty()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.file.invalid_name"));
            return 0;
        }

        Path kmlPath = MarkerStorage.getKmlSavePath();
        File kmlFile = kmlPath.resolve(cleanFilename + ".kml").toFile();

        if (!kmlFile.exists()) {
            kmlFile = kmlPath.resolve(cleanFilename + ".KML").toFile();
        }

        if (!kmlFile.exists()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.file_not_found",
                    cleanFilename, kmlPath.toString()));
            return 0;
        }

        List<MarkerData.KmlPoint> points = parseKmlFile(kmlFile);

        if (points.isEmpty()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.no_points"));
            return 0;
        }

        kmlPostCommandsList.clear();
        if (BoshysBTEUtils.getConfig().kmlPostImportCommands != null &&
                !BoshysBTEUtils.getConfig().kmlPostImportCommands.isEmpty()) {
            String[] commands = BoshysBTEUtils.getConfig().kmlPostImportCommands.split(";");
            for (String cmd : commands) {
                cmd = cmd.trim();
                if (!cmd.isEmpty()) {
                    kmlPostCommandsList.add(cmd);
                }
            }
        }

        pendingKmlPoints = new ArrayList<>(points);
        currentKmlFileName = cleanFilename;
        kmlCurrentPointIndex = 0;
        kmlPostCommandIndex = 0;
        kmlWaitingForPostCommand = false;
        waitingForWorldEditCommand = false;
        worldEditLinesActive = false;
        worldEditSetupComplete = false;
        waitingForTeleport = false;
        inWorldEditSetupPhase = false;
        inSpectatorSetupPhase = false;
        cooldownTicks = 0;
        positionBeforeTpll = null;
        lastCheckedPosition = null;
        isProcessingQueue = false;
        kmlFileQueue.clear();

        // FIX: Reset marker connection state to prevent spurious connections
        // between consecutive KML imports
        BoshysBTEUtils.lastAddedMarker = null;
        BoshysBTEUtils.lastAutoConnectMarker = null;
        BoshysBTEUtils.selectedMarkers.clear();

        kmlImportWaitingToStart = true;
        kmlImportStartDelayTicks = BoshysBTEUtils.getConfig().kmlImportStartDelaySeconds * 20;

        source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.started").formatted(net.minecraft.util.Formatting.YELLOW, net.minecraft.util.Formatting.BOLD));
        source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.warning").formatted(net.minecraft.util.Formatting.YELLOW, net.minecraft.util.Formatting.BOLD));
        source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.details",
                points.size(), BoshysBTEUtils.getConfig().kmlImportDelayTicks));

        // Show altitude mode info
        BoshysBTEUtilsConfig.AltitudeMode mode = BoshysBTEUtils.getConfig().kmlAltitudeMode;
        double offset = BoshysBTEUtils.getConfig().kmlAltitudeOffset;
        String modeStr = switch(mode) {
            case AUTOMATIC -> "Automatic (surface level)";
            case KML_ALTITUDES -> "KML Altitudes";
            case LOCKED -> "Locked (" + BoshysBTEUtils.getConfig().kmlLockedAltitudeValue + ")";
        };

        String offsetStr = offset != 0 ? " (offset: " + (offset > 0 ? "+" : "") + offset + ")" : "";
        source.sendFeedback(Text.literal("§eAltitude mode: " + modeStr + offsetStr));

        if (BoshysBTEUtils.getConfig().enableWorldEditLines) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.worldedit_enabled",
                    BoshysBTEUtils.getConfig().worldEditLineBlock));
        }

        return 1;
    }

    public int importMultipleKmlFiles(FabricClientCommandSource source, List<String> filenames) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.error.markers_disabled"));
            return 0;
        }

        if (BoshysBTEUtils.markersHidden) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.error.markers_hidden"));
            return 0;
        }

        if (isKmlImporting || kmlImportWaitingToStart || isProcessingQueue) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.in_progress"));
            return 0;
        }

        if (filenames.isEmpty()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.kml.queue.no_files"));
            return 0;
        }

        // Validate all files exist first
        Path kmlPath = MarkerStorage.getKmlSavePath();
        List<String> validFiles = new ArrayList<>();

        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
            if (cleanFilename.isEmpty()) continue;

            File kmlFile = kmlPath.resolve(cleanFilename + ".kml").toFile();
            if (!kmlFile.exists()) {
                kmlFile = kmlPath.resolve(cleanFilename + ".KML").toFile();
            }

            if (kmlFile.exists()) {
                validFiles.add(cleanFilename);
            }
        }

        if (validFiles.isEmpty()) {
            source.sendFeedback(Text.translatable("command.boshysbteutils.kml.queue.no_valid_files"));
            return 0;
        }

        // Set up queue
        kmlFileQueue = new ArrayList<>(validFiles);
        currentKmlFileIndex = 0;
        isProcessingQueue = true;
        waitingBetweenFiles = false;

        source.sendFeedback(Text.translatable("command.boshysbteutils.kml.queue.started", validFiles.size()).formatted(net.minecraft.util.Formatting.YELLOW, net.minecraft.util.Formatting.BOLD));
        source.sendFeedback(Text.translatable("command.boshysbteutils.kml.import.warning").formatted(net.minecraft.util.Formatting.YELLOW, net.minecraft.util.Formatting.BOLD));

        // Start first file
        startKmlFileImport(source, kmlFileQueue.get(0));

        return 1;
    }

    private void startKmlFileImport(FabricClientCommandSource source, String filename) {
        Path kmlPath = MarkerStorage.getKmlSavePath();
        File kmlFile = kmlPath.resolve(filename + ".kml").toFile();

        if (!kmlFile.exists()) {
            kmlFile = kmlPath.resolve(filename + ".KML").toFile();
        }

        List<MarkerData.KmlPoint> points = parseKmlFile(kmlFile);

        if (points.isEmpty()) {
            // Skip this file and move to next
            source.sendFeedback(Text.translatable("command.boshysbteutils.kml.queue.skipping_empty", filename));
            advanceQueueOrFinish(source.getClient());
            return;
        }

        kmlPostCommandsList.clear();
        if (BoshysBTEUtils.getConfig().kmlPostImportCommands != null &&
                !BoshysBTEUtils.getConfig().kmlPostImportCommands.isEmpty()) {
            String[] commands = BoshysBTEUtils.getConfig().kmlPostImportCommands.split(";");
            for (String cmd : commands) {
                cmd = cmd.trim();
                if (!cmd.isEmpty()) {
                    kmlPostCommandsList.add(cmd);
                }
            }
        }

        pendingKmlPoints = new ArrayList<>(points);
        currentKmlFileName = filename;
        kmlCurrentPointIndex = 0;
        kmlPostCommandIndex = 0;
        kmlWaitingForPostCommand = false;
        waitingForWorldEditCommand = false;
        worldEditLinesActive = false;
        worldEditSetupComplete = false;
        waitingForTeleport = false;
        inWorldEditSetupPhase = false;
        inSpectatorSetupPhase = false;
        cooldownTicks = 0;
        positionBeforeTpll = null;
        lastCheckedPosition = null;

        // FIX: Reset marker connection state for each queued file import
        // to prevent spurious connections between files
        BoshysBTEUtils.lastAddedMarker = null;
        BoshysBTEUtils.lastAutoConnectMarker = null;
        BoshysBTEUtils.selectedMarkers.clear();

        kmlImportWaitingToStart = true;
        kmlImportStartDelayTicks = BoshysBTEUtils.getConfig().kmlImportStartDelaySeconds * 20;

        source.sendFeedback(Text.translatable("command.boshysbteutils.kml.queue.processing", currentKmlFileIndex + 1, kmlFileQueue.size(), filename));
    }

    private void finishKmlImport(MinecraftClient client) {
        int importedCount = kmlCurrentPointIndex;

        if (isProcessingQueue) {
            // We're in queue mode, prepare for next file
            if (client.player != null) {
                client.player.sendMessage(
                        Text.translatable("command.boshysbteutils.kml.queue.file_complete", currentKmlFileName, importedCount).formatted(net.minecraft.util.Formatting.GREEN),
                        false
                );
            }

            advanceQueueOrFinish(client);
            return;
        }

        // Single file import - finish completely
        isKmlImporting = false;

        if (client.player != null && client.inGameHud != null) {
            client.inGameHud.setTitle(Text.literal(""));
            client.inGameHud.setSubtitle(Text.literal(""));
        }

        if (client.player != null) {
            // Send advancement-like toast message (simulated via chat for now, but distinct)
            client.player.sendMessage(
                    Text.translatable("command.boshysbteutils.kml.import.complete").formatted(net.minecraft.util.Formatting.GREEN, net.minecraft.util.Formatting.BOLD),
                    false
            );
            client.player.sendMessage(
                    Text.translatable("command.boshysbteutils.kml.import.success", importedCount, currentKmlFileName).formatted(net.minecraft.util.Formatting.GREEN),
                    false
            );
            client.player.sendMessage(
                    Text.translatable("command.boshysbteutils.kml.import.normal").formatted(net.minecraft.util.Formatting.GREEN),
                    true // Action bar
            );
        }

        // FIX: Reset marker connection state when import finishes to prevent
        // spurious connections on the next import
        BoshysBTEUtils.lastAddedMarker = null;
        BoshysBTEUtils.lastAutoConnectMarker = null;
        BoshysBTEUtils.selectedMarkers.clear();

        pendingKmlPoints.clear();
        kmlCurrentPointIndex = 0;
        kmlPostCommandIndex = 0;
        currentKmlFileName = "";
        kmlPostCommandsList.clear();
        kmlWaitingForPostCommand = false;
        waitingForWorldEditCommand = false;
        worldEditLinesActive = false;
        worldEditSetupComplete = false;
        waitingForTeleport = false;
        inWorldEditSetupPhase = false;
        inSpectatorSetupPhase = false;
        cooldownTicks = 0;
        positionBeforeTpll = null;
        lastCheckedPosition = null;
        worldEditCommandQueue.clear();
        setupCommands.clear();
    }

    private void advanceQueueOrFinish(MinecraftClient client) {
        currentKmlFileIndex++;

        if (currentKmlFileIndex >= kmlFileQueue.size()) {
            // All files done
            isProcessingQueue = false;
            isKmlImporting = false;
            kmlFileQueue.clear();

            if (client.player != null && client.inGameHud != null) {
                client.inGameHud.setTitle(Text.literal(""));
                client.inGameHud.setSubtitle(Text.literal(""));
            }

            if (client.player != null) {
                client.player.sendMessage(
                        Text.translatable("command.boshysbteutils.kml.queue.complete").formatted(net.minecraft.util.Formatting.GREEN, net.minecraft.util.Formatting.BOLD),
                        false
                );
                client.player.sendMessage(
                        Text.translatable("command.boshysbteutils.kml.import.normal").formatted(net.minecraft.util.Formatting.GREEN),
                        true
                );
            }

            // FIX: Reset marker connection state when queue finishes completely
            BoshysBTEUtils.lastAddedMarker = null;
            BoshysBTEUtils.lastAutoConnectMarker = null;
            BoshysBTEUtils.selectedMarkers.clear();

            pendingKmlPoints.clear();
            kmlCurrentPointIndex = 0;
            kmlPostCommandIndex = 0;
            currentKmlFileName = "";
            kmlPostCommandsList.clear();
            kmlWaitingForPostCommand = false;
            waitingForWorldEditCommand = false;
            worldEditLinesActive = false;
            worldEditSetupComplete = false;
            waitingForTeleport = false;
            inWorldEditSetupPhase = false;
            inSpectatorSetupPhase = false;
            cooldownTicks = 0;
            positionBeforeTpll = null;
            lastCheckedPosition = null;
            worldEditCommandQueue.clear();
            setupCommands.clear();
        } else {
            // Prepare for next file
            waitingBetweenFiles = true;
            queueDelayTicks = 20; // 1 second delay between files

            // Deselect all markers
            BoshysBTEUtils.selectedMarkers.clear();

            // CRITICAL FIX: Clear lastAddedMarker to prevent auto-connection between files
            BoshysBTEUtils.lastAddedMarker = null;

            // Run //sel if WorldEdit lines are enabled
            if (BoshysBTEUtils.getConfig().enableWorldEditLines && client.player != null) {
                client.player.networkHandler.sendChatCommand("sel");
            }
        }
    }

    private void startNextKmlInQueue(MinecraftClient client) {
        if (!isProcessingQueue || currentKmlFileIndex >= kmlFileQueue.size()) {
            return;
        }

        String nextFile = kmlFileQueue.get(currentKmlFileIndex);

        // Create a fake source for the next file (we'll use the client directly where needed)
        // Actually, we need to handle this differently since we don't have the source anymore
        // We'll store the feedback messages and display them directly

        Path kmlPath = MarkerStorage.getKmlSavePath();
        File kmlFile = kmlPath.resolve(nextFile + ".kml").toFile();

        if (!kmlFile.exists()) {
            kmlFile = kmlPath.resolve(nextFile + ".KML").toFile();
        }

        List<MarkerData.KmlPoint> points = parseKmlFile(kmlFile);

        if (points.isEmpty()) {
            if (client.player != null) {
                client.player.sendMessage(
                        Text.translatable("command.boshysbteutils.kml.queue.skipping_empty", nextFile).formatted(net.minecraft.util.Formatting.YELLOW),
                        false
                );
            }
            advanceQueueOrFinish(client);
            return;
        }

        kmlPostCommandsList.clear();
        if (BoshysBTEUtils.getConfig().kmlPostImportCommands != null &&
                !BoshysBTEUtils.getConfig().kmlPostImportCommands.isEmpty()) {
            String[] commands = BoshysBTEUtils.getConfig().kmlPostImportCommands.split(";");
            for (String cmd : commands) {
                cmd = cmd.trim();
                if (!cmd.isEmpty()) {
                    kmlPostCommandsList.add(cmd);
                }
            }
        }

        pendingKmlPoints = new ArrayList<>(points);
        currentKmlFileName = nextFile;
        kmlCurrentPointIndex = 0;
        kmlPostCommandIndex = 0;
        kmlWaitingForPostCommand = false;
        waitingForWorldEditCommand = false;
        worldEditLinesActive = false;
        worldEditSetupComplete = false;
        waitingForTeleport = false;
        inWorldEditSetupPhase = false;
        inSpectatorSetupPhase = false;
        cooldownTicks = 0;
        positionBeforeTpll = null;
        lastCheckedPosition = null;

        // FIX: Reset marker connection state for each queued file import
        BoshysBTEUtils.lastAddedMarker = null;
        BoshysBTEUtils.lastAutoConnectMarker = null;
        BoshysBTEUtils.selectedMarkers.clear();

        // Don't use start delay between queued files
        kmlImportWaitingToStart = false;
        isKmlImporting = true;

        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable("command.boshysbteutils.kml.queue.processing", currentKmlFileIndex + 1, kmlFileQueue.size(), nextFile),
                    false
            );
        }

        // Always start with spectator setup, regardless of WorldEdit lines setting
        startSpectatorSetup(client);
    }

    private List<MarkerData.KmlPoint> parseKmlFile(File kmlFile) {
        List<MarkerData.KmlPoint> points = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(kmlFile), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }

            String kmlContent = content.toString();

            Pattern coordPattern = Pattern.compile("<coordinates>([^<]+)</coordinates>", Pattern.CASE_INSENSITIVE);
            Matcher matcher = coordPattern.matcher(kmlContent);

            while (matcher.find()) {
                String coordBlock = matcher.group(1).trim();
                String[] coordEntries = coordBlock.split("\\s+");

                for (String entry : coordEntries) {
                    entry = entry.trim();
                    if (entry.isEmpty()) continue;

                    String[] parts = entry.split(",");
                    if (parts.length >= 2) {
                        try {
                            double longitude = Double.parseDouble(parts[0].trim());
                            double latitude = Double.parseDouble(parts[1].trim());
                            double altitude = 0;

                            if (parts.length >= 3) {
                                try {
                                    altitude = Double.parseDouble(parts[2].trim());
                                } catch (NumberFormatException e) {
                                }
                            }

                            points.add(new MarkerData.KmlPoint(longitude, latitude, altitude));
                        } catch (NumberFormatException e) {
                        }
                    }
                }
            }

            Pattern gxCoordPattern = Pattern.compile("<gx:coord>([^<]+)</gx:coord>", Pattern.CASE_INSENSITIVE);
            Matcher gxMatcher = gxCoordPattern.matcher(kmlContent);

            while (gxMatcher.find()) {
                String coordEntry = gxMatcher.group(1).trim();
                String[] parts = coordEntry.split("\\s+");

                if (parts.length >= 2) {
                    try {
                        double longitude = Double.parseDouble(parts[0].trim());
                        double latitude = Double.parseDouble(parts[1].trim());
                        double altitude = 0;

                        if (parts.length >= 3) {
                            try {
                                altitude = Double.parseDouble(parts[2].trim());
                            } catch (NumberFormatException e) {
                            }
                        }

                        points.add(new MarkerData.KmlPoint(longitude, latitude, altitude));
                    } catch (NumberFormatException e) {
                    }
                }
            }

        } catch (IOException e) {
        }

        return points;
    }
}