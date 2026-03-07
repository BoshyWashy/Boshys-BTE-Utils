package com.boshys.bteutils.storage;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.data.MarkerData;
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
    private boolean waitingForTeleport = false;
    private int teleportWaitTicks = 0;
    private static final int TELEPORT_WAIT_MAX = 40; // Safety timeout only

    // Position tracking for immediate teleport detection
    private Vec3d positionBeforeTpll = null;
    private boolean teleportDetected = false;

    private boolean inWorldEditSetupPhase = false;
    private int setupCommandIndex = 0;
    private List<String> setupCommands = new ArrayList<>();

    // Cooldown counter - time to wait before sending next TPLL (after marker is placed)
    private int cooldownTicks = 0;

    public KmlImportHandler(BoshysBTEUtils mod) {
        this.mod = mod;
    }

    public boolean isImporting() {
        return isKmlImporting || kmlImportWaitingToStart;
    }

    public void tick(MinecraftClient client) {
        // Handle initial start delay
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
            teleportWaitTicks = 0;
            teleportDetected = false;
            positionBeforeTpll = null;
            inWorldEditSetupPhase = false;
            cooldownTicks = 0;

            showImportTitle(client, "§c§lImport in progress", "§eDo not move until finished!");

            if (BoshysBTEUtils.getConfig().enableWorldEditLines) {
                startWorldEditSetup(client);
            } else {
                // Start immediately - no cooldown before first TPLL
                processNextKmlPoint(client);
            }
            return;
        }

        if (!isKmlImporting || pendingKmlPoints.isEmpty()) {
            return;
        }

        // Handle WorldEdit setup phase
        if (inWorldEditSetupPhase) {
            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }
            executeNextSetupCommand(client);
            return;
        }

        // Handle waiting for teleport to complete
        // Check every tick if player has moved from where they were when TPLL was sent
        if (waitingForTeleport) {
            if (client.player != null && positionBeforeTpll != null) {
                Vec3d currentPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
                double distanceMoved = currentPos.distanceTo(positionBeforeTpll);

                // If player has moved more than 0.1 blocks, teleport has occurred
                if (distanceMoved > 0.1) {
                    waitingForTeleport = false;
                    teleportWaitTicks = 0;
                    teleportDetected = true;
                    // Place marker immediately at current position
                    placeMarkerAndContinue(client);
                    return;
                }
            }

            // Player hasn't moved yet, check timeout
            teleportWaitTicks++;
            if (teleportWaitTicks >= TELEPORT_WAIT_MAX) {
                // Timeout fallback - place marker anyway
                waitingForTeleport = false;
                teleportWaitTicks = 0;
                placeMarkerAndContinue(client);
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

        // Cooldown between TPLL commands - this runs AFTER marker is placed
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // Ready to send next TPLL
        processNextKmlPoint(client);
    }

    private void startWorldEditSetup(MinecraftClient client) {
        inWorldEditSetupPhase = true;
        setupCommandIndex = 0;
        setupCommands.clear();

        setupCommands.add("/sel");
        setupCommands.add("/sel cuboid");
        setupCommands.add("gamemode spectator");

        cooldownTicks = 0;
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

        String commandPrefix = BoshysBTEUtils.getConfig().commandPrefix;
        String tpllCommand = commandPrefix + " " + point.latitude + ", " + point.longitude;

        // Store position before sending TPLL
        positionBeforeTpll = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        teleportDetected = false;

        client.player.networkHandler.sendChatCommand(tpllCommand);

        waitingForTeleport = true;
        teleportWaitTicks = 0;

        if (kmlCurrentPointIndex % 5 == 0 || kmlCurrentPointIndex >= pendingKmlPoints.size() - 1) {
            showImportTitle(client, "§c§lImport in progress",
                    "§ePoint " + (kmlCurrentPointIndex + 1) + "/" + pendingKmlPoints.size() + " - Do not move!");
        }
    }

    // Called when teleport is detected via mixin (backup method)
    public void onMarkerPlaced(MinecraftClient client) {
        if (!isKmlImporting) return;
        if (!waitingForTeleport) return;
        if (teleportDetected) return; // Already detected via position check

        // Double-check position changed
        if (client.player != null && positionBeforeTpll != null) {
            Vec3d currentPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
            if (currentPos.distanceTo(positionBeforeTpll) > 0.1) {
                waitingForTeleport = false;
                teleportWaitTicks = 0;
                teleportDetected = true;
                placeMarkerAndContinue(client);
            }
        }
    }

    private void placeMarkerAndContinue(MinecraftClient client) {
        // Place marker at player's current position (where they teleported to)
        if (client.player != null) {
            MarkerData.TeleportMarker newMarker = MarkerData.addMarker(
                    new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ())
            );

            // Auto-connect with previous marker
            if (BoshysBTEUtils.lastAddedMarker != null && BoshysBTEUtils.lastAddedMarker != newMarker) {
                MarkerData.connectMarkers(BoshysBTEUtils.lastAddedMarker, newMarker);
            }

            // Update tracking for next marker connection
            BoshysBTEUtils.lastAddedMarker = newMarker;

            // Select the marker so it can connect to the next one
            BoshysBTEUtils.selectedMarkers.clear();
            BoshysBTEUtils.selectedMarkers.add(newMarker);
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

        // Set cooldown for next TPLL - cooldown starts NOW, AFTER marker is placed
        cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
    }

    private void buildWorldEditCommandQueue() {
        worldEditCommandQueue.clear();
        worldEditCommandIndex = 0;

        String block = BoshysBTEUtils.getConfig().worldEditLineBlock;
        int currentPoint = kmlCurrentPointIndex;

        if (currentPoint == 0) {
            // First point - just set pos1
            worldEditCommandQueue.add("/pos1");
        } else if (currentPoint > 0 && currentPoint < pendingKmlPoints.size()) {
            // Middle points - set pos2, draw line, then set pos1 for next
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
            // WorldEdit commands done
            waitingForWorldEditCommand = false;

            // Check for post commands
            if (!kmlPostCommandsList.isEmpty()) {
                kmlWaitingForPostCommand = true;
                kmlPostCommandIndex = 0;
                kmlPostCommandTickCounter = 0;
                executeNextPostCommand(client);
                return;
            }

            // Set cooldown for next TPLL - cooldown starts after WorldEdit commands complete
            cooldownTicks = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
            return;
        }

        String command = worldEditCommandQueue.get(worldEditCommandIndex);
        worldEditCommandIndex++;

        client.player.networkHandler.sendChatCommand(command);

        // Set cooldown for next WorldEdit command
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

            // Set cooldown for next TPLL - cooldown starts after post commands complete
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

        // Set cooldown for next post command
        kmlPostCommandTickCounter = BoshysBTEUtils.getConfig().kmlImportDelayTicks;
    }

    private void showImportTitle(MinecraftClient client, String title, String subtitle) {
        if (client.player != null && client.inGameHud != null) {
            client.inGameHud.setTitle(Text.literal(title));
            client.inGameHud.setSubtitle(Text.literal(subtitle));
        }
    }

    public int importKmlFile(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String filename) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) {
            source.sendFeedback(Text.literal("§cMarkers disabled in config!"));
            return 0;
        }

        if (isKmlImporting || kmlImportWaitingToStart) {
            source.sendFeedback(Text.literal("§cKML import already in progress! Please wait..."));
            return 0;
        }

        String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        if (cleanFilename.isEmpty()) {
            source.sendFeedback(Text.literal("§cInvalid filename!"));
            return 0;
        }

        Path kmlPath = MarkerStorage.getKmlSavePath();
        File kmlFile = kmlPath.resolve(cleanFilename + ".kml").toFile();

        if (!kmlFile.exists()) {
            kmlFile = kmlPath.resolve(cleanFilename + ".KML").toFile();
        }

        if (!kmlFile.exists()) {
            source.sendFeedback(Text.literal("§cKML file '" + cleanFilename + "' not found in " + kmlPath.toString()));
            return 0;
        }

        List<MarkerData.KmlPoint> points = parseKmlFile(kmlFile);

        if (points.isEmpty()) {
            source.sendFeedback(Text.literal("§cNo valid coordinates found in KML file!"));
            return 0;
        }

        kmlPostCommandsList.clear();
        if (BoshysBTEUtils.getConfig().kmlPostImportCommands != null && !BoshysBTEUtils.getConfig().kmlPostImportCommands.isEmpty()) {
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
        teleportWaitTicks = 0;
        teleportDetected = false;
        positionBeforeTpll = null;
        inWorldEditSetupPhase = false;
        cooldownTicks = 0;

        kmlImportWaitingToStart = true;
        kmlImportStartDelayTicks = BoshysBTEUtils.getConfig().kmlImportStartDelaySeconds * 20;

        source.sendFeedback(Text.literal("§e§l=== KML IMPORT STARTING ==="));
        source.sendFeedback(Text.literal("§e§lDO NOT TOUCH YOUR MINECRAFT UNTIL IMPORT IS COMPLETE!"));
        source.sendFeedback(Text.literal("§eImporting " + points.size() + " points with " + BoshysBTEUtils.getConfig().kmlImportDelayTicks + " tick delay"));

        if (BoshysBTEUtils.getConfig().enableWorldEditLines) {
            source.sendFeedback(Text.literal("§eWorldEdit lines enabled with block: " + BoshysBTEUtils.getConfig().worldEditLineBlock));
        }

        return 1;
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

    private void finishKmlImport(MinecraftClient client) {
        isKmlImporting = false;
        int importedCount = kmlCurrentPointIndex;

        if (client.player != null && client.inGameHud != null) {
            client.inGameHud.setTitle(Text.literal(""));
            client.inGameHud.setSubtitle(Text.literal(""));
        }

        if (client.player != null) {
            client.player.sendMessage(Text.literal("§a§l=== KML IMPORT COMPLETE ==="), false);
            client.player.sendMessage(Text.literal("§aImported " + importedCount + " points from '" + currentKmlFileName + "'"), false);
            client.player.sendMessage(Text.literal("§aYou can now use Minecraft normally."), false);
        }

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
        cooldownTicks = 0;
        positionBeforeTpll = null;
        teleportDetected = false;
        worldEditCommandQueue.clear();
        setupCommands.clear();
    }
}