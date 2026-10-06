package com.boshys.bteutils.storage;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.boshys.bteutils.data.MarkerData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;

public class MarkerStorage {
    private final BoshysBTEUtils mod;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private Path markersSavePath;
    private long lastAutosaveTime = 0;

    // State
    public final Map<String, MarkerData.SavedMarkerFile> loadedFiles = new HashMap<>();
    public final Map<String, MarkerData.SavedMarkerFile> modifiedLoadedFiles = new HashMap<>();
    public final Set<String> hiddenFiles = new HashSet<>();

    // Track markers by a unique file entry ID that persists across moves
    public final Map<String, Map<Integer, MarkerData.TeleportMarker>> fileMarkerIndexMap = new HashMap<>();
    public final Map<MarkerData.TeleportMarker, FileMarkerId> markerToFileId = new HashMap<>();

    private int pendingClearCount = 0;
    private boolean pendingClearAll = false;
    private long pendingClearTimestamp = 0;
    private static final long CLEAR_EXPIRATION_MS = 30000;

    // Helper class to track which file and index a marker came from
    public static class FileMarkerId {
        public final String filename;
        public final int index;

        public FileMarkerId(String filename, int index) {
            this.filename = filename;
            this.index = index;
        }
    }

    public MarkerStorage(BoshysBTEUtils mod) {
        this.mod = mod;
    }

    public void updateMarkersSavePath() {
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();
        if (config.savedMarkersFolderPath != null && !config.savedMarkersFolderPath.isEmpty()) {
            markersSavePath = Path.of(config.savedMarkersFolderPath);
        } else {
            markersSavePath = Path.of("config/boshysbteutils/markers");
        }

        File dir = markersSavePath.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static Path getMarkersSavePath() {
        if (BoshysBTEUtils.INSTANCE != null && BoshysBTEUtils.INSTANCE.getMarkerStorage() != null
                && BoshysBTEUtils.INSTANCE.getMarkerStorage().markersSavePath != null) {
            return BoshysBTEUtils.INSTANCE.getMarkerStorage().markersSavePath;
        }
        return Path.of("config/boshysbteutils/markers");
    }

    public static Path getKmlSavePath() {
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();
        if (config != null && config.kmlFolderPath != null && !config.kmlFolderPath.isEmpty()) {
            return Path.of(config.kmlFolderPath);
        }
        return getMarkersSavePath();
    }

    private MarkerData.SavedMarkerFile readMarkerFile(File file, String fallbackName) {
        try (FileReader reader = new FileReader(file)) {
            MarkerData.SavedMarkerFile fileData = GSON.fromJson(reader, MarkerData.SavedMarkerFile.class);
            if (fileData != null && fileData.markers != null) {
                if (fileData.connections == null) {
                    fileData.connections = new ArrayList<>();
                }
                return fileData;
            }
        } catch (Exception ignored) {}

        try (FileReader reader = new FileReader(file)) {
            Type listType = new TypeToken<List<MarkerData.SavedMarkerData>>(){}.getType();
            List<MarkerData.SavedMarkerData> legacyMarkers = GSON.fromJson(reader, listType);
            if (legacyMarkers != null) {
                return new MarkerData.SavedMarkerFile(fallbackName, file.lastModified(), legacyMarkers, new ArrayList<>());
            }
        } catch (Exception ignored) {}

        return null;
    }

    public int getCacheMarkerCount() {
        int count = 0;
        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                count++;
            }
        }
        return count;
    }

    public void setPendingClear(int count, boolean all) {
        this.pendingClearCount = count;
        this.pendingClearAll = all;
        this.pendingClearTimestamp = System.currentTimeMillis();
    }

    public int clearCacheMarkersOnly() {
        final int[] removedCount = new int[1];
        BoshysBTEUtils.markers.removeIf(marker -> {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                removedCount[0]++;
                BoshysBTEUtils.markerOrigins.remove(marker);
                BoshysBTEUtils.markerOriginalPositions.remove(marker);
                markerToFileId.remove(marker);
                return true;
            }
            return false;
        });

        BoshysBTEUtils.markerConnections.removeIf(conn -> !BoshysBTEUtils.markers.contains(conn.marker1) || !BoshysBTEUtils.markers.contains(conn.marker2));
        BoshysBTEUtils.selectedMarkers.removeIf(marker -> !BoshysBTEUtils.markers.contains(marker));
        if (BoshysBTEUtils.lastAddedMarker != null && !BoshysBTEUtils.markers.contains(BoshysBTEUtils.lastAddedMarker)) {
            BoshysBTEUtils.lastAddedMarker = null;
        }

        return removedCount[0];
    }

    public int confirmClear() {
        if (pendingClearCount == 0) return -1;
        if (System.currentTimeMillis() - pendingClearTimestamp > CLEAR_EXPIRATION_MS) {
            pendingClearCount = 0;
            pendingClearAll = false;
            return -1;
        }

        if (pendingClearAll) {
            int count = BoshysBTEUtils.markers.size();
            BoshysBTEUtils.markers.clear();
            BoshysBTEUtils.markerConnections.clear();
            BoshysBTEUtils.selectedMarkers.clear();
            BoshysBTEUtils.lastAddedMarker = null;
            loadedFiles.clear();
            modifiedLoadedFiles.clear();
            hiddenFiles.clear();
            BoshysBTEUtils.markerOrigins.clear();
            BoshysBTEUtils.markerOriginalPositions.clear();
            fileMarkerIndexMap.clear();
            markerToFileId.clear();
            pendingClearCount = 0;
            pendingClearAll = false;
            pendingClearTimestamp = 0;
            return count;
        } else {
            int count = clearCacheMarkersOnly();
            pendingClearCount = 0;
            pendingClearAll = false;
            pendingClearTimestamp = 0;
            return count;
        }
    }

    public boolean hasPendingClear() {
        return pendingClearCount > 0;
    }

    public boolean isPendingClearAll() {
        return pendingClearAll;
    }

    public int getPendingClearCount() {
        return pendingClearCount;
    }

    private String posKey(Vec3 pos) {
        return String.format("%.6f,%.6f,%.6f", pos.x, pos.y, pos.z);
    }

    private String posKey(double x, double y, double z) {
        return String.format("%.6f,%.6f,%.6f", x, y, z);
    }

    public int saveMarkersToFile(FabricClientCommandSource source, String filename, double radius) {
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();
        if (!config.enableMarkers) {
            source.sendError(Component.literal("§cMarkers disabled in config!"));
            return 0;
        }

        boolean hasCacheMarkers = false;
        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                hasCacheMarkers = true;
                break;
            }
        }

        if (!hasCacheMarkers) {
            source.sendError(Component.literal("§cNo markers in cache to save!"));
            return 0;
        }

        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        if (filename.isEmpty()) {
            source.sendError(Component.literal("§cInvalid filename!"));
            return 0;
        }

        LocalPlayer player = source.getPlayer();
        Vec3 playerPos = new Vec3(player.getX(), player.getY(), player.getZ());
        List<MarkerData.SavedMarkerData> markersToSave = new ArrayList<>();
        List<MarkerData.SavedConnectionData> connectionsToSave = new ArrayList<>();
        List<MarkerData.TeleportMarker> savedCacheMarkers = new ArrayList<>();

        Set<String> alreadySavedPositions = new HashSet<>();
        Path savePath = getMarkersSavePath();
        File dir = savePath.toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] existingFiles = dir.listFiles((d, name) -> name.endsWith(".json") && !name.equals("autosave.json") && !name.startsWith("autosave_"));
            if (existingFiles != null) {
                for (File existingFile : existingFiles) {
                    MarkerData.SavedMarkerFile existingData = readMarkerFile(existingFile, existingFile.getName());
                    if (existingData != null && existingData.markers != null) {
                        for (MarkerData.SavedMarkerData data : existingData.markers) {
                            alreadySavedPositions.add(posKey(data.x, data.y, data.z));
                        }
                    }
                }
            }
        }

        Map<MarkerData.TeleportMarker, Integer> markerIndexMap = new HashMap<>();
        int index = 0;

        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (origin != null && !origin.equals("autosave") && !origin.startsWith("autosave_")) {
                continue;
            }

            String posKey = posKey(marker.position);

            if (alreadySavedPositions.contains(posKey)) {
                continue;
            }

            if (radius < 0 || marker.position.distanceTo(playerPos) <= radius) {
                MarkerData.SavedMarkerData savedData = new MarkerData.SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity,
                        marker.circleRadius, marker.circleColour, marker.circleOpacity, marker.circleThickness, marker.circleSegmentPercent
                );
                markersToSave.add(savedData);
                markerIndexMap.put(marker, index);
                savedCacheMarkers.add(marker);
                index++;
            }
        }

        if (markersToSave.isEmpty()) {
            source.sendError(Component.literal("§cNo new unsaved markers within specified radius!"));
            return 0;
        }

        for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
            Integer idx1 = markerIndexMap.get(conn.marker1);
            Integer idx2 = markerIndexMap.get(conn.marker2);
            if (idx1 != null && idx2 != null) {
                connectionsToSave.add(new MarkerData.SavedConnectionData(
                        idx1, idx2, conn.lineColour, conn.lineOpacity, conn.lineThickness
                ));
            }
        }

        MarkerData.SavedMarkerFile fileData = new MarkerData.SavedMarkerFile(filename, System.currentTimeMillis(), markersToSave, connectionsToSave);

        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(fileData, writer);
            writer.flush();
        } catch (IOException e) {
            source.sendError(Component.literal("§cFailed to save markers: " + e.getMessage()));
            return 0;
        }

        for (MarkerData.TeleportMarker savedMarker : savedCacheMarkers) {
            BoshysBTEUtils.markers.remove(savedMarker);
            BoshysBTEUtils.markerOrigins.remove(savedMarker);
            BoshysBTEUtils.markerOriginalPositions.remove(savedMarker);
        }

        BoshysBTEUtils.markerConnections.removeIf(conn ->
                !BoshysBTEUtils.markers.contains(conn.marker1) || !BoshysBTEUtils.markers.contains(conn.marker2));
        BoshysBTEUtils.selectedMarkers.removeIf(marker -> !BoshysBTEUtils.markers.contains(marker));
        if (BoshysBTEUtils.lastAddedMarker != null && !BoshysBTEUtils.markers.contains(BoshysBTEUtils.lastAddedMarker)) {
            BoshysBTEUtils.lastAddedMarker = null;
        }

        loadMarkerFileInternal(filename, true);

        Component message = Component.literal("§aSaved " + markersToSave.size() + " markers to '")
                .append(Component.literal(filename).withStyle(Style.EMPTY.withBold(true)))
                .append(Component.literal("' §aand loaded it!"))
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("§eClick to open folder")))
                );

        source.sendFeedback(message);
        return 1;
    }

    public int updateMarkerFile(FabricClientCommandSource source, String filename, double radius) {
        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

        if (!loadedFiles.containsKey(filename)) {
            source.sendError(Component.literal("§cFile '" + filename + "' is not loaded! You must load it first with /boshys-bt-utils load " + filename));
            return 0;
        }

        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        if (!file.exists()) {
            source.sendError(Component.literal("§cFile '" + filename + "' not found!"));
            return 0;
        }

        MarkerData.SavedMarkerFile existingData = readMarkerFile(file, filename);
        if (existingData == null) {
            existingData = new MarkerData.SavedMarkerFile(filename, System.currentTimeMillis(), new ArrayList<>(), new ArrayList<>());
        }
        if (existingData.markers == null) {
            existingData.markers = new ArrayList<>();
        }
        if (existingData.connections == null) {
            existingData.connections = new ArrayList<>();
        }

        LocalPlayer player = source.getPlayer();
        Vec3 playerPos = new Vec3(player.getX(), player.getY(), player.getZ());

        Map<Integer, MarkerData.TeleportMarker> indexToMarker = new HashMap<>();

        Map<Integer, MarkerData.TeleportMarker> fileIndexMap = fileMarkerIndexMap.get(filename);
        if (fileIndexMap != null) {
            for (Map.Entry<Integer, MarkerData.TeleportMarker> entry : fileIndexMap.entrySet()) {
                MarkerData.TeleportMarker marker = entry.getValue();
                if (BoshysBTEUtils.markers.contains(marker)) {
                    String origin = BoshysBTEUtils.markerOrigins.get(marker);
                    if (filename.equals(origin)) {
                        indexToMarker.put(entry.getKey(), marker);
                    }
                }
            }
        }

        if (indexToMarker.isEmpty()) {
            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                String origin = BoshysBTEUtils.markerOrigins.get(marker);
                if (filename.equals(origin)) {
                    Vec3 originalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                    if (originalPos != null) {
                        for (int i = 0; i < existingData.markers.size(); i++) {
                            MarkerData.SavedMarkerData data = existingData.markers.get(i);
                            String dataPosKey = posKey(data.x, data.y, data.z);
                            String markerPosKey = posKey(originalPos);
                            if (dataPosKey.equals(markerPosKey)) {
                                indexToMarker.put(i, marker);
                                fileMarkerIndexMap.computeIfAbsent(filename, k -> new HashMap<>()).put(i, marker);
                                markerToFileId.put(marker, new FileMarkerId(filename, i));
                                break;
                            }
                        }
                    }
                }
            }
        }

        List<MarkerData.SavedMarkerData> newMarkersList = new ArrayList<>();
        List<MarkerData.SavedConnectionData> newConnectionsList = new ArrayList<>();

        Map<Integer, Integer> indexRemap = new HashMap<>();

        int newIndex = 0;
        int preservedCount = 0;
        int updatedCount = 0;
        int removedCount = 0;

        for (int i = 0; i < existingData.markers.size(); i++) {
            MarkerData.TeleportMarker currentMarker = indexToMarker.get(i);

            if (currentMarker == null) {
                removedCount++;
                continue;
            }

            MarkerData.SavedMarkerData oldData = existingData.markers.get(i);
            MarkerData.SavedMarkerData newData = new MarkerData.SavedMarkerData(
                    currentMarker.position.x, currentMarker.position.y, currentMarker.position.z,
                    currentMarker.colour, currentMarker.scale, currentMarker.opacity,
                    currentMarker.circleRadius, currentMarker.circleColour, currentMarker.circleOpacity, currentMarker.circleThickness, currentMarker.circleSegmentPercent
            );

            boolean wasModified = currentMarker.colour != oldData.colour ||
                    currentMarker.scale != oldData.scale ||
                    currentMarker.opacity != oldData.opacity ||
                    currentMarker.circleRadius != oldData.circleRadius ||
                    currentMarker.circleColour != oldData.circleColour ||
                    currentMarker.circleOpacity != oldData.circleOpacity ||
                    currentMarker.circleThickness != oldData.circleThickness ||
                    currentMarker.circleSegmentPercent != oldData.circleSegmentPercent ||
                    Math.abs(currentMarker.position.x - oldData.x) > 0.0001 ||
                    Math.abs(currentMarker.position.y - oldData.y) > 0.0001 ||
                    Math.abs(currentMarker.position.z - oldData.z) > 0.0001;

            if (wasModified) {
                updatedCount++;
            } else {
                preservedCount++;
            }

            newMarkersList.add(newData);
            indexRemap.put(i, newIndex);
            newIndex++;

            fileMarkerIndexMap.computeIfAbsent(filename, k -> new HashMap<>()).put(newIndex - 1, currentMarker);
            markerToFileId.put(currentMarker, new FileMarkerId(filename, newIndex - 1));
            BoshysBTEUtils.markerOriginalPositions.put(currentMarker, new Vec3(currentMarker.position.x, currentMarker.position.y, currentMarker.position.z));
        }

        int addedCount = 0;
        List<MarkerData.TeleportMarker> newlyAddedMarkers = new ArrayList<>();

        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (origin != null && !origin.equals("autosave") && !origin.startsWith("autosave_")) {
                continue;
            }

            boolean alreadyInFile = indexToMarker.values().contains(marker);

            if (alreadyInFile) {
                continue;
            }

            if (radius >= 0 && marker.position.distanceTo(playerPos) > radius) {
                continue;
            }

            boolean positionExists = false;
            String markerPosKey = posKey(marker.position);
            for (MarkerData.SavedMarkerData existing : newMarkersList) {
                String existingPosKey = posKey(existing.x, existing.y, existing.z);
                if (markerPosKey.equals(existingPosKey)) {
                    positionExists = true;
                    break;
                }
            }

            if (positionExists) {
                continue;
            }

            MarkerData.SavedMarkerData newData = new MarkerData.SavedMarkerData(
                    marker.position.x, marker.position.y, marker.position.z,
                    marker.colour, marker.scale, marker.opacity,
                    marker.circleRadius, marker.circleColour, marker.circleOpacity, marker.circleThickness, marker.circleSegmentPercent
            );
            newMarkersList.add(newData);

            BoshysBTEUtils.markerOrigins.put(marker, filename);
            BoshysBTEUtils.markerOriginalPositions.put(marker, new Vec3(marker.position.x, marker.position.y, marker.position.z));
            newlyAddedMarkers.add(marker);

            int newMarkerIndex = newIndex;
            fileMarkerIndexMap.computeIfAbsent(filename, k -> new HashMap<>()).put(newMarkerIndex, marker);
            markerToFileId.put(marker, new FileMarkerId(filename, newMarkerIndex));

            newIndex++;
            addedCount++;
        }

        Map<MarkerData.TeleportMarker, Integer> finalIndexMap = new HashMap<>();

        for (int newIdx = 0; newIdx < newMarkersList.size(); newIdx++) {
            for (Map.Entry<MarkerData.TeleportMarker, FileMarkerId> entry : markerToFileId.entrySet()) {
                if (entry.getValue().filename.equals(filename) && entry.getValue().index == newIdx) {
                    finalIndexMap.put(entry.getKey(), newIdx);
                    break;
                }
            }
        }

        Set<String> addedConnections = new HashSet<>();

        // Synchronize in-memory line settings to existing file connections, then process surviving connections
        for (MarkerData.SavedConnectionData oldConn : existingData.connections) {
            Integer newFrom = indexRemap.get(oldConn.fromIndex);
            Integer newTo = indexRemap.get(oldConn.toIndex);

            if (newFrom != null && newTo != null && !newFrom.equals(newTo)) {
                String connKey = newFrom < newTo ? newFrom + ":" + newTo : newTo + ":" + newFrom;
                if (!addedConnections.contains(connKey)) {
                    // Look up corresponding live connection to sync current in-memory settings
                    MarkerData.TeleportMarker m1 = indexToMarker.get(oldConn.fromIndex);
                    MarkerData.TeleportMarker m2 = indexToMarker.get(oldConn.toIndex);
                    MarkerData.MarkerConnection liveConn = (m1 != null && m2 != null) ? MarkerData.getConnection(m1, m2) : null;

                    int lineColour = liveConn != null ? liveConn.lineColour : oldConn.lineColour;
                    float lineOpacity = liveConn != null ? liveConn.lineOpacity : oldConn.lineOpacity;
                    float lineThickness = liveConn != null ? liveConn.lineThickness : oldConn.lineThickness;

                    newConnectionsList.add(new MarkerData.SavedConnectionData(
                            newFrom, newTo, lineColour, lineOpacity, lineThickness
                    ));
                    addedConnections.add(connKey);
                }
            }
        }

        // Add any newly created or external connections involving updated markers
        for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
            Integer idx1 = finalIndexMap.get(conn.marker1);
            Integer idx2 = finalIndexMap.get(conn.marker2);

            if (idx1 != null && idx2 != null && !idx1.equals(idx2)) {
                String connKey = idx1 < idx2 ? idx1 + ":" + idx2 : idx2 + ":" + idx1;
                if (!addedConnections.contains(connKey)) {
                    newConnectionsList.add(new MarkerData.SavedConnectionData(
                            idx1, idx2, conn.lineColour, conn.lineOpacity, conn.lineThickness
                    ));
                    addedConnections.add(connKey);
                }
            }
        }

        existingData.markers = newMarkersList;
        existingData.connections = newConnectionsList;
        existingData.lastModified = System.currentTimeMillis();

        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(existingData, writer);
            writer.flush();
        } catch (IOException e) {
            source.sendError(Component.literal("§cFailed to update file: " + e.getMessage()));
            return 0;
        }

        loadedFiles.put(filename, existingData);
        modifiedLoadedFiles.remove(filename);

        StringBuilder msg = new StringBuilder();
        msg.append("§aUpdated '").append(filename).append("'!");
        if (preservedCount > 0) msg.append(" Preserved: ").append(preservedCount);
        if (updatedCount > 0) msg.append(" Updated: ").append(updatedCount);
        if (removedCount > 0) msg.append(" Removed: ").append(removedCount);
        if (addedCount > 0) msg.append(" Added: ").append(addedCount);
        msg.append(". Total: ").append(newMarkersList.size());

        Component message = Component.literal(msg.toString())
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("§eClick to open folder")))
                );

        source.sendFeedback(message);
        return 1;
    }

    public int loadMarkerFile(FabricClientCommandSource source, String filename) {
        return loadMarkerFileInternal(filename, false) ? 1 : 0;
    }

    public boolean loadMarkerFileInternal(String filename, boolean silent) {
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();
        if (!config.enableMarkers) {
            if (!silent) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§cMarkers disabled in config!"));
            }
            return false;
        }

        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        if (!file.exists()) {
            if (!silent) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§cFile '" + filename + "' not found!"));
            }
            return false;
        }

        if (file.length() == 0) {
            if (!silent) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§cFile '" + filename + "' is empty!"));
            }
            return false;
        }

        MarkerData.SavedMarkerFile fileData = readMarkerFile(file, filename);
        if (fileData == null || fileData.markers == null) {
            if (!silent) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§cInvalid file format!"));
            }
            return false;
        }

        // Check and migrate legacy/missing connection line properties in the file
        boolean needsSaveAndReload = false;

        // Auto-reconstruct sequential connections if none exist
        if ((fileData.connections == null || fileData.connections.isEmpty()) && fileData.markers.size() > 1) {
            if (fileData.connections == null) {
                fileData.connections = new ArrayList<>();
            }
            for (int i = 0; i < fileData.markers.size() - 1; i++) {
                fileData.connections.add(new MarkerData.SavedConnectionData(
                        i, i + 1,
                        config.lineColour,
                        config.lineOpacity,
                        config.lineThickness
                ));
            }
            needsSaveAndReload = true;
        } else if (fileData.connections != null) {
            for (MarkerData.SavedConnectionData connData : fileData.connections) {
                if (connData.lineColour <= 0) {
                    connData.lineColour = config.lineColour;
                    needsSaveAndReload = true;
                }
                if (connData.lineOpacity <= 0f) {
                    connData.lineOpacity = config.lineOpacity;
                    needsSaveAndReload = true;
                }
                if (connData.lineThickness <= 0f) {
                    connData.lineThickness = config.lineThickness;
                    needsSaveAndReload = true;
                }
            }
        }

        if (needsSaveAndReload) {
            fileData.lastModified = System.currentTimeMillis();
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(fileData, writer);
                writer.flush();
            } catch (IOException ignored) {}
        }

        hiddenFiles.remove(filename);

        int loadedCount = 0;
        List<MarkerData.TeleportMarker> loadedMarkers = new ArrayList<>();

        Map<Integer, MarkerData.TeleportMarker> indexMap = new HashMap<>();

        boolean isAutosave = filename.equals("autosave") || filename.startsWith("autosave_");

        for (int i = 0; i < fileData.markers.size(); i++) {
            MarkerData.SavedMarkerData data = fileData.markers.get(i);
            MarkerData.TeleportMarker marker = new MarkerData.TeleportMarker(
                    new Vec3(data.x, data.y, data.z),
                    data.colour, data.scale, data.opacity
            );
            marker.circleRadius = data.circleRadius;
            marker.circleColour = data.circleColour;
            marker.circleOpacity = data.circleOpacity;
            marker.circleThickness = data.circleThickness;
            marker.circleSegmentPercent = data.circleSegmentPercent;

            BoshysBTEUtils.markers.add(marker);
            loadedMarkers.add(marker);
            loadedCount++;

            if (!isAutosave) {
                BoshysBTEUtils.markerOrigins.put(marker, filename);
                BoshysBTEUtils.markerOriginalPositions.put(marker, new Vec3(data.x, data.y, data.z));
                indexMap.put(i, marker);
                markerToFileId.put(marker, new FileMarkerId(filename, i));
            }
        }

        if (!isAutosave) {
            fileMarkerIndexMap.put(filename, indexMap);
        }

        int loadedConnections = 0;

        if (fileData.connections != null) {
            for (MarkerData.SavedConnectionData connData : fileData.connections) {
                if (connData.fromIndex >= 0 && connData.fromIndex < loadedMarkers.size() &&
                        connData.toIndex >= 0 && connData.toIndex < loadedMarkers.size()) {
                    MarkerData.MarkerConnection conn = MarkerData.connectMarkers(
                            loadedMarkers.get(connData.fromIndex), loadedMarkers.get(connData.toIndex)
                    );

                    if (conn != null) {
                        conn.lineColour = connData.lineColour > 0 ? connData.lineColour : config.lineColour;
                        conn.lineOpacity = connData.lineOpacity > 0f ? connData.lineOpacity : config.lineOpacity;
                        conn.lineThickness = connData.lineThickness > 0f ? connData.lineThickness : config.lineThickness;
                    }

                    loadedConnections++;
                }
            }
        }

        if (!isAutosave) {
            loadedFiles.put(filename, fileData);
            modifiedLoadedFiles.remove(filename);
        }

        if (!silent) {
            Component message = Component.literal("§aLoaded " + loadedCount + " markers")
                    .append(loadedConnections > 0 ? Component.literal(" with " + loadedConnections + " connections") : Component.literal(""))
                    .append(Component.literal(" from '"))
                    .append(Component.literal(filename).withStyle(Style.EMPTY.withBold(true)))
                    .append(Component.literal("'!"))
                    .append(isAutosave ? Component.literal(" (as cache markers)") : Component.literal(""))
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("§eClick to open folder")))
                    );

            Minecraft.getInstance().player.sendSystemMessage(message);
        }
        return true;
    }

    public int hideMarkerFile(FabricClientCommandSource source, String filename) {
        final String finalFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

        if (!loadedFiles.containsKey(finalFilename)) {
            source.sendError(Component.literal("§cFile '" + finalFilename + "' is not currently loaded!"));
            return 0;
        }

        Set<MarkerData.TeleportMarker> protectedMarkers = new HashSet<>();
        for (Map.Entry<String, MarkerData.SavedMarkerFile> entry : loadedFiles.entrySet()) {
            String otherFilename = entry.getKey();
            if (otherFilename.equals(finalFilename)) continue;

            MarkerData.SavedMarkerFile otherFile = entry.getValue();
            for (MarkerData.SavedMarkerData data : otherFile.markers) {
                for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                    String markerOrigin = BoshysBTEUtils.markerOrigins.get(marker);
                    if (otherFilename.equals(markerOrigin)) {
                        Vec3 markerOriginalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                        if (markerOriginalPos != null) {
                            String markerPosKey = posKey(markerOriginalPos);
                            String dataPosKey = posKey(data.x, data.y, data.z);
                            if (markerPosKey.equals(dataPosKey)) {
                                protectedMarkers.add(marker);
                                break;
                            }
                        }
                    }
                }
            }
        }

        final int[] removedCount = new int[1];
        final int[] protectedCount = new int[1];

        BoshysBTEUtils.markers.removeIf(marker -> {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (finalFilename.equals(origin)) {
                if (protectedMarkers.contains(marker)) {
                    for (Map.Entry<String, MarkerData.SavedMarkerFile> entry : loadedFiles.entrySet()) {
                        String otherFilename = entry.getKey();
                        if (otherFilename.equals(finalFilename)) continue;

                        MarkerData.SavedMarkerFile otherFile = entry.getValue();
                        for (MarkerData.SavedMarkerData data : otherFile.markers) {
                            Vec3 markerOriginalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                            if (markerOriginalPos != null) {
                                String markerPosKey = posKey(markerOriginalPos);
                                String dataPosKey = posKey(data.x, data.y, data.z);
                                if (markerPosKey.equals(dataPosKey)) {
                                    BoshysBTEUtils.markerOrigins.put(marker, otherFilename);
                                    BoshysBTEUtils.markerOriginalPositions.put(marker, new Vec3(data.x, data.y, data.z));
                                    protectedCount[0]++;
                                    return false;
                                }
                            }
                        }
                    }
                }

                removedCount[0]++;
                BoshysBTEUtils.markerOrigins.remove(marker);
                BoshysBTEUtils.markerOriginalPositions.remove(marker);
                markerToFileId.remove(marker);
                return true;
            }
            return false;
        });

        BoshysBTEUtils.markerConnections.removeIf(conn -> !BoshysBTEUtils.markers.contains(conn.marker1) || !BoshysBTEUtils.markers.contains(conn.marker2));
        BoshysBTEUtils.selectedMarkers.removeIf(marker -> !BoshysBTEUtils.markers.contains(marker));

        fileMarkerIndexMap.remove(finalFilename);

        loadedFiles.remove(finalFilename);
        modifiedLoadedFiles.remove(finalFilename);
        hiddenFiles.add(finalFilename);

        StringBuilder msg = new StringBuilder("§aHidden '" + finalFilename + "'! Removed " + removedCount[0] + " markers from display.");
        if (protectedCount[0] > 0) {
            msg.append(" (").append(protectedCount[0]).append(" markers kept from other files)");
        }
        source.sendFeedback(Component.literal(msg.toString()));
        return 1;
    }

    public int deleteMarkerFile(FabricClientCommandSource source, String filename) {
        String cleanName = filename.trim();

        String baseName = cleanName;
        if (baseName.toLowerCase().endsWith(".json")) {
            baseName = baseName.substring(0, baseName.length() - 5);
        }

        baseName = baseName.replaceAll("[^a-zA-Z0-9_-]", "");

        if (baseName.isEmpty()) {
            source.sendError(Component.literal("§cInvalid filename!"));
            return 0;
        }

        File file = getMarkersSavePath().resolve(baseName + ".json").toFile();

        if (!file.exists()) {
            source.sendError(Component.literal("§cFile '" + cleanName + "' not found!"));
            return 0;
        }

        if (loadedFiles.containsKey(baseName)) {
            hideMarkerFile(source, baseName);
        }

        if (file.delete()) {
            hiddenFiles.remove(baseName);
            source.sendFeedback(Component.literal("§aDeleted file '" + cleanName + "' permanently!"));
            return 1;
        } else {
            source.sendError(Component.literal("§cFailed to delete file!"));
            return 0;
        }
    }

    public int mergeMarkerFiles(FabricClientCommandSource source, String mergedFileName, boolean includeCached, List<String> filenames) {
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();
        if (!config.enableMarkers) {
            source.sendError(Component.literal("§cMarkers disabled in config!"));
            return 0;
        }

        if (filenames.isEmpty()) {
            source.sendError(Component.literal("§cNeed at least one file to merge!"));
            return 0;
        }

        mergedFileName = mergedFileName.replaceAll("[^a-zA-Z0-9_-]", "");
        if (mergedFileName.isEmpty()) {
            source.sendError(Component.literal("§cInvalid merged filename!"));
            return 0;
        }

        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
            File file = getMarkersSavePath().resolve(cleanFilename + ".json").toFile();
            if (!file.exists()) {
                source.sendError(Component.literal("§cFile '" + cleanFilename + "' not found!"));
                return 0;
            }
        }

        List<MarkerData.SavedMarkerData> allMarkers = new ArrayList<>();
        List<MarkerData.SavedConnectionData> allConnections = new ArrayList<>();
        int baseIndex = 0;

        Set<MarkerData.TeleportMarker> markersToRemove = new HashSet<>();
        Set<MarkerData.TeleportMarker> cacheMarkersToRemove = new HashSet<>();
        List<MarkerData.TeleportMarker> cacheMarkersInOrder = new ArrayList<>();

        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
            File file = getMarkersSavePath().resolve(cleanFilename + ".json").toFile();

            MarkerData.SavedMarkerFile fileData = readMarkerFile(file, cleanFilename);
            if (fileData != null && fileData.markers != null) {
                for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                    String origin = BoshysBTEUtils.markerOrigins.get(marker);
                    if (cleanFilename.equals(origin)) {
                        markersToRemove.add(marker);
                    }
                }

                for (int i = 0; i < fileData.markers.size(); i++) {
                    MarkerData.SavedMarkerData data = fileData.markers.get(i);
                    allMarkers.add(new MarkerData.SavedMarkerData(
                            data.x, data.y, data.z,
                            data.colour, data.scale, data.opacity,
                            data.circleRadius, data.circleColour, data.circleOpacity, data.circleThickness, data.circleSegmentPercent
                    ));
                }

                if (fileData.connections != null) {
                    for (MarkerData.SavedConnectionData conn : fileData.connections) {
                        allConnections.add(new MarkerData.SavedConnectionData(
                                conn.fromIndex + baseIndex,
                                conn.toIndex + baseIndex,
                                conn.lineColour, conn.lineOpacity, conn.lineThickness
                        ));
                    }
                }

                baseIndex += fileData.markers.size();
            }
        }

        if (includeCached) {
            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                String origin = BoshysBTEUtils.markerOrigins.get(marker);
                if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                    cacheMarkersInOrder.add(marker);
                }
            }

            for (MarkerData.TeleportMarker marker : cacheMarkersInOrder) {
                allMarkers.add(new MarkerData.SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity,
                        marker.circleRadius, marker.circleColour, marker.circleOpacity, marker.circleThickness, marker.circleSegmentPercent
                ));
                cacheMarkersToRemove.add(marker);
            }

            Map<MarkerData.TeleportMarker, Integer> unifiedIndexMap = new HashMap<>();
            int idx = 0;

            for (String filename : filenames) {
                String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
                Map<Integer, MarkerData.TeleportMarker> fileIndexMap = fileMarkerIndexMap.get(cleanFilename);
                if (fileIndexMap != null) {
                    List<Integer> sortedIndices = new ArrayList<>(fileIndexMap.keySet());
                    Collections.sort(sortedIndices);
                    for (int fileIdx : sortedIndices) {
                        MarkerData.TeleportMarker marker = fileIndexMap.get(fileIdx);
                        if (marker != null && BoshysBTEUtils.markers.contains(marker)) {
                            unifiedIndexMap.put(marker, idx++);
                        }
                    }
                } else {
                    for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                        String origin = BoshysBTEUtils.markerOrigins.get(marker);
                        if (cleanFilename.equals(origin) && !unifiedIndexMap.containsKey(marker)) {
                            unifiedIndexMap.put(marker, idx++);
                        }
                    }
                }
            }

            for (MarkerData.TeleportMarker marker : cacheMarkersInOrder) {
                unifiedIndexMap.put(marker, idx++);
            }

            Set<String> addedConnections = new HashSet<>();
            for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
                Integer idx1 = unifiedIndexMap.get(conn.marker1);
                Integer idx2 = unifiedIndexMap.get(conn.marker2);
                if (idx1 != null && idx2 != null && !idx1.equals(idx2)) {
                    String connKey = Math.min(idx1, idx2) + ":" + Math.max(idx1, idx2);
                    if (!addedConnections.contains(connKey)) {
                        allConnections.add(new MarkerData.SavedConnectionData(
                                idx1, idx2, conn.lineColour, conn.lineOpacity, conn.lineThickness
                        ));
                        addedConnections.add(connKey);
                    }
                }
            }

            baseIndex += cacheMarkersInOrder.size();
        }

        if (allMarkers.isEmpty()) {
            source.sendError(Component.literal("§cNo markers to merge!"));
            return 0;
        }

        MarkerData.SavedMarkerFile mergedData = new MarkerData.SavedMarkerFile(mergedFileName, System.currentTimeMillis(), allMarkers, allConnections);
        File mergedFile = getMarkersSavePath().resolve(mergedFileName + ".json").toFile();

        try (FileWriter writer = new FileWriter(mergedFile)) {
            GSON.toJson(mergedData, writer);
            writer.flush();
        } catch (IOException e) {
            source.sendError(Component.literal("§cFailed to save merged file: " + e.getMessage()));
            return 0;
        }

        hiddenFiles.remove(mergedFileName);

        for (MarkerData.TeleportMarker marker : markersToRemove) {
            BoshysBTEUtils.markers.remove(marker);
            BoshysBTEUtils.markerOrigins.remove(marker);
            BoshysBTEUtils.markerOriginalPositions.remove(marker);
            markerToFileId.remove(marker);
        }

        for (MarkerData.TeleportMarker marker : cacheMarkersToRemove) {
            BoshysBTEUtils.markers.remove(marker);
            BoshysBTEUtils.markerOrigins.remove(marker);
            BoshysBTEUtils.markerOriginalPositions.remove(marker);
            markerToFileId.remove(marker);
        }

        BoshysBTEUtils.markerConnections.removeIf(conn ->
                !BoshysBTEUtils.markers.contains(conn.marker1) || !BoshysBTEUtils.markers.contains(conn.marker2));
        BoshysBTEUtils.selectedMarkers.removeIf(marker -> !BoshysBTEUtils.markers.contains(marker));
        if (BoshysBTEUtils.lastAddedMarker != null && !BoshysBTEUtils.markers.contains(BoshysBTEUtils.lastAddedMarker)) {
            BoshysBTEUtils.lastAddedMarker = null;
        }
        if (BoshysBTEUtils.lastAutoConnectMarker != null && !BoshysBTEUtils.markers.contains(BoshysBTEUtils.lastAutoConnectMarker)) {
            BoshysBTEUtils.lastAutoConnectMarker = null;
        }

        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

            if (cleanFilename.equals(mergedFileName)) {
                continue;
            }

            loadedFiles.remove(cleanFilename);
            modifiedLoadedFiles.remove(cleanFilename);
            fileMarkerIndexMap.remove(cleanFilename);
            hiddenFiles.remove(cleanFilename);

            File oldFile = getMarkersSavePath().resolve(cleanFilename + ".json").toFile();
            if (oldFile.exists()) {
                oldFile.delete();
            }
        }

        int loadedCount = 0;
        List<MarkerData.TeleportMarker> loadedMarkers = new ArrayList<>();
        Map<Integer, MarkerData.TeleportMarker> mergedIndexMap = new HashMap<>();

        for (int i = 0; i < mergedData.markers.size(); i++) {
            MarkerData.SavedMarkerData data = mergedData.markers.get(i);
            MarkerData.TeleportMarker marker = new MarkerData.TeleportMarker(
                    new Vec3(data.x, data.y, data.z),
                    data.colour, data.scale, data.opacity
            );
            marker.circleRadius = data.circleRadius;
            marker.circleColour = data.circleColour;
            marker.circleOpacity = data.circleOpacity;
            marker.circleThickness = data.circleThickness;
            marker.circleSegmentPercent = data.circleSegmentPercent;

            BoshysBTEUtils.markers.add(marker);
            loadedMarkers.add(marker);
            loadedCount++;

            BoshysBTEUtils.markerOrigins.put(marker, mergedFileName);
            BoshysBTEUtils.markerOriginalPositions.put(marker, new Vec3(data.x, data.y, data.z));
            mergedIndexMap.put(i, marker);
            markerToFileId.put(marker, new FileMarkerId(mergedFileName, i));
        }

        fileMarkerIndexMap.put(mergedFileName, mergedIndexMap);

        int loadedConnections = 0;
        if (mergedData.connections != null) {
            for (MarkerData.SavedConnectionData connData : mergedData.connections) {
                if (connData.fromIndex >= 0 && connData.fromIndex < loadedMarkers.size() &&
                        connData.toIndex >= 0 && connData.toIndex < loadedMarkers.size()) {
                    MarkerData.MarkerConnection conn = MarkerData.connectMarkers(
                            loadedMarkers.get(connData.fromIndex), loadedMarkers.get(connData.toIndex)
                    );

                    if (conn != null) {
                        conn.lineColour = connData.lineColour;
                        conn.lineOpacity = connData.lineOpacity;
                        conn.lineThickness = connData.lineThickness;
                    }

                    loadedConnections++;
                }
            }
        }

        loadedFiles.put(mergedFileName, mergedData);
        modifiedLoadedFiles.remove(mergedFileName);

        Component message = Component.literal("§aMerged " + allMarkers.size() + " markers")
                .append(allConnections.size() > 0 ? Component.literal(" with " + allConnections.size() + " connections") : Component.literal(""))
                .append(Component.literal(" into '"))
                .append(Component.literal(mergedFileName).withStyle(Style.EMPTY.withBold(true)))
                .append(Component.literal("' and loaded!"))
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.OpenFile(mergedFile.getParentFile().getAbsolutePath()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("§eClick to open folder")))
                );

        source.sendFeedback(message);
        return 1;
    }

    public int moveSelectedMarkers(FabricClientCommandSource source, double dx, double dy, double dz) {
        if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
            source.sendError(Component.literal("§cNo markers selected!"));
            return 0;
        }

        int movedCount = 0;
        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.selectedMarkers) {
            if (BoshysBTEUtils.markers.contains(marker)) {
                marker.position = marker.position.add(dx, dy, dz);
                movedCount++;

                FileMarkerId fileId = markerToFileId.get(marker);
                if (fileId != null) {
                    Map<Integer, MarkerData.TeleportMarker> indexMap = fileMarkerIndexMap.get(fileId.filename);
                    if (indexMap != null && indexMap.get(fileId.index) == marker) {
                        MarkerData.SavedMarkerFile file = loadedFiles.get(fileId.filename);
                        if (file != null) {
                            modifiedLoadedFiles.put(fileId.filename, file);
                        }
                    }
                }

                String origin = BoshysBTEUtils.markerOrigins.get(marker);
                if (origin != null && !origin.equals("autosave") && !origin.startsWith("autosave_")) {
                    Vec3 currentOriginal = BoshysBTEUtils.markerOriginalPositions.get(marker);
                    if (currentOriginal != null) {
                        BoshysBTEUtils.markerOriginalPositions.put(marker, currentOriginal.add(dx, dy, dz));
                    }

                    MarkerData.SavedMarkerFile file = loadedFiles.get(origin);
                    if (file != null) {
                        modifiedLoadedFiles.put(origin, file);
                    }
                }
            }
        }

        source.sendFeedback(Component.literal("§aMoved " + movedCount + " marker(s) by (" + dx + ", " + dy + ", " + dz + ")!"));
        return 1;
    }

    public int moveSelectedMarkersToPosition(FabricClientCommandSource source, double x, double y, double z) {
        if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
            source.sendError(Component.literal("§cNo markers selected!"));
            return 0;
        }

        MarkerData.TeleportMarker firstMarker = BoshysBTEUtils.selectedMarkers.iterator().next();
        double dx = x - firstMarker.position.x;
        double dy = y - firstMarker.position.y;
        double dz = z - firstMarker.position.z;

        return moveSelectedMarkers(source, dx, dy, dz);
    }

    public int moveAllMarkersInFile(FabricClientCommandSource source, String filename, double dx, double dy, double dz) {
        String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

        if (cleanFilename.isEmpty()) {
            source.sendError(Component.literal("§cInvalid filename!"));
            return 0;
        }

        if (!loadedFiles.containsKey(cleanFilename)) {
            source.sendError(Component.literal("§cFile '" + cleanFilename + "' is not loaded! Load it first with /boshys-bt-utils load " + cleanFilename));
            return 0;
        }

        int movedCount = 0;
        List<MarkerData.TeleportMarker> markersToMove = new ArrayList<>();

        Map<Integer, MarkerData.TeleportMarker> indexMap = fileMarkerIndexMap.get(cleanFilename);
        if (indexMap != null) {
            for (MarkerData.TeleportMarker marker : indexMap.values()) {
                if (BoshysBTEUtils.markers.contains(marker)) {
                    markersToMove.add(marker);
                }
            }
        }

        if (markersToMove.isEmpty()) {
            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                String origin = BoshysBTEUtils.markerOrigins.get(marker);
                if (cleanFilename.equals(origin)) {
                    markersToMove.add(marker);
                }
            }
        }

        if (markersToMove.isEmpty()) {
            source.sendError(Component.literal("§cNo markers found from file '" + cleanFilename + "'!"));
            return 0;
        }

        for (MarkerData.TeleportMarker marker : markersToMove) {
            if (BoshysBTEUtils.markers.contains(marker)) {
                marker.position = marker.position.add(dx, dy, dz);
                movedCount++;

                Vec3 originalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                if (originalPos != null) {
                    BoshysBTEUtils.markerOriginalPositions.put(marker, originalPos.add(dx, dy, dz));
                }
            }
        }

        MarkerData.SavedMarkerFile file = loadedFiles.get(cleanFilename);
        if (file != null) {
            modifiedLoadedFiles.put(cleanFilename, file);
        }

        source.sendFeedback(Component.literal("§aMoved " + movedCount + " marker(s) from '" + cleanFilename + "' by (" + dx + ", " + dy + ", " + dz + ")!"));
        return 1;
    }

    public void performAutosave() {
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();
        if (!config.enableAutosave) return;

        Path savePath = getMarkersSavePath();
        boolean savedAnything = false;

        List<MarkerData.SavedMarkerData> cacheMarkers = new ArrayList<>();
        List<MarkerData.TeleportMarker> cacheMarkerObjects = new ArrayList<>();

        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                cacheMarkers.add(new MarkerData.SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity,
                        marker.circleRadius, marker.circleColour, marker.circleOpacity, marker.circleThickness, marker.circleSegmentPercent
                ));
                cacheMarkerObjects.add(marker);
            }
        }

        if (!cacheMarkers.isEmpty()) {
            File autosaveFile = savePath.resolve("autosave.json").toFile();

            List<MarkerData.SavedConnectionData> cacheConnections = new ArrayList<>();
            Map<MarkerData.TeleportMarker, Integer> cacheIndexMap = new HashMap<>();
            for (int i = 0; i < cacheMarkerObjects.size(); i++) {
                cacheIndexMap.put(cacheMarkerObjects.get(i), i);
            }

            for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
                Integer idx1 = cacheIndexMap.get(conn.marker1);
                Integer idx2 = cacheIndexMap.get(conn.marker2);
                if (idx1 != null && idx2 != null) {
                    cacheConnections.add(new MarkerData.SavedConnectionData(
                            idx1, idx2, conn.lineColour, conn.lineOpacity, conn.lineThickness
                    ));
                }
            }

            MarkerData.SavedMarkerFile autosaveData = new MarkerData.SavedMarkerFile("autosave", System.currentTimeMillis(), cacheMarkers, cacheConnections);

            try (FileWriter writer = new FileWriter(autosaveFile)) {
                GSON.toJson(autosaveData, writer);
                writer.flush();
                savedAnything = true;
            } catch (IOException e) {
            }
        } else {
            File autosaveFile = savePath.resolve("autosave.json").toFile();
            if (autosaveFile.exists()) {
                autosaveFile.delete();
            }
        }

        for (Map.Entry<String, MarkerData.SavedMarkerFile> entry : modifiedLoadedFiles.entrySet()) {
            String filename = entry.getKey();
            MarkerData.SavedMarkerFile fileData = entry.getValue();

            String dateStr = DATE_FORMAT.format(new Date());
            File autosaveFile = savePath.resolve("autosave_" + dateStr + "_" + filename + ".json").toFile();

            try (FileWriter writer = new FileWriter(autosaveFile)) {
                List<MarkerData.SavedMarkerData> updatedMarkers = new ArrayList<>();
                List<MarkerData.SavedConnectionData> updatedConnections = new ArrayList<>();

                Map<MarkerData.TeleportMarker, Integer> markerIndexMap = new HashMap<>();
                Map<Integer, MarkerData.TeleportMarker> indexToMarker = new HashMap<>();
                int index = 0;

                Map<Integer, MarkerData.TeleportMarker> fileIndexMap = fileMarkerIndexMap.get(filename);
                if (fileIndexMap != null) {
                    for (Map.Entry<Integer, MarkerData.TeleportMarker> entry2 : fileIndexMap.entrySet()) {
                        MarkerData.TeleportMarker marker = entry2.getValue();
                        if (BoshysBTEUtils.markers.contains(marker)) {
                            String origin = BoshysBTEUtils.markerOrigins.get(marker);
                            if (filename.equals(origin)) {
                                updatedMarkers.add(new MarkerData.SavedMarkerData(
                                        marker.position.x, marker.position.y, marker.position.z,
                                        marker.colour, marker.scale, marker.opacity,
                                        marker.circleRadius, marker.circleColour, marker.circleOpacity, marker.circleThickness, marker.circleSegmentPercent
                                ));
                                markerIndexMap.put(marker, index);
                                indexToMarker.put(index, marker);
                                index++;
                            }
                        }
                    }
                }

                if (updatedMarkers.isEmpty()) {
                    for (MarkerData.SavedMarkerData originalData : fileData.markers) {
                        Vec3 originalPos = new Vec3(originalData.x, originalData.y, originalData.z);

                        boolean found = false;
                        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                            Vec3 markerOriginalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                            String origin = BoshysBTEUtils.markerOrigins.get(marker);

                            if (filename.equals(origin) && markerOriginalPos != null &&
                                    posKey(markerOriginalPos).equals(posKey(originalPos))) {
                                updatedMarkers.add(new MarkerData.SavedMarkerData(
                                        marker.position.x, marker.position.y, marker.position.z,
                                        marker.colour, marker.scale, marker.opacity,
                                        marker.circleRadius, marker.circleColour, marker.circleOpacity, marker.circleThickness, marker.circleSegmentPercent
                                ));
                                markerIndexMap.put(marker, index);
                                indexToMarker.put(index, marker);
                                index++;
                                found = true;
                                break;
                            }
                        }

                        if (!found) {
                            updatedMarkers.add(originalData);
                            index++;
                        }
                    }
                }

                if (fileData.connections != null) {
                    for (MarkerData.SavedConnectionData oldConn : fileData.connections) {
                        MarkerData.TeleportMarker fromMarker = indexToMarker.get(oldConn.fromIndex);
                        MarkerData.TeleportMarker toMarker = indexToMarker.get(oldConn.toIndex);

                        if (fromMarker != null && toMarker != null) {
                            Integer newFromIdx = markerIndexMap.get(fromMarker);
                            Integer newToIdx = markerIndexMap.get(toMarker);
                            if (newFromIdx != null && newToIdx != null) {
                                MarkerData.MarkerConnection liveConn = MarkerData.getConnection(fromMarker, toMarker);
                                int lineColour = liveConn != null ? liveConn.lineColour : oldConn.lineColour;
                                float lineOpacity = liveConn != null ? liveConn.lineOpacity : oldConn.lineOpacity;
                                float lineThickness = liveConn != null ? liveConn.lineThickness : oldConn.lineThickness;

                                updatedConnections.add(new MarkerData.SavedConnectionData(
                                        newFromIdx, newToIdx, lineColour, lineOpacity, lineThickness
                                ));
                            }
                        }
                    }
                }

                Set<String> existingConnections = new HashSet<>();
                for (MarkerData.SavedConnectionData conn : updatedConnections) {
                    String key = Math.min(conn.fromIndex, conn.toIndex) + ":" + Math.max(conn.fromIndex, conn.toIndex);
                    existingConnections.add(key);
                }

                for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
                    Integer idx1 = markerIndexMap.get(conn.marker1);
                    Integer idx2 = markerIndexMap.get(conn.marker2);
                    if (idx1 != null && idx2 != null && !idx1.equals(idx2)) {
                        String key = Math.min(idx1, idx2) + ":" + Math.max(idx1, idx2);
                        if (!existingConnections.contains(key)) {
                            updatedConnections.add(new MarkerData.SavedConnectionData(
                                    idx1, idx2, conn.lineColour, conn.lineOpacity, conn.lineThickness
                            ));
                            existingConnections.add(key);
                        }
                    }
                }

                MarkerData.SavedMarkerFile autosaveData = new MarkerData.SavedMarkerFile(
                        "autosave_" + dateStr + "_" + filename,
                        System.currentTimeMillis(),
                        updatedMarkers,
                        updatedConnections
                );

                GSON.toJson(autosaveData, writer);
                writer.flush();
                savedAnything = true;
            } catch (IOException e) {
            }
        }

        modifiedLoadedFiles.clear();
    }

    public void tickAutosave() {
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();
        if (config.enableAutosave && config.autosaveIntervalMinutes > 0) {
            long currentTime = System.currentTimeMillis();
            long intervalMs = config.autosaveIntervalMinutes * 60 * 1000;
            if (currentTime - lastAutosaveTime >= intervalMs) {
                performAutosave();
                lastAutosaveTime = currentTime;
            }
        }
    }

    private String formatPosition(double x, double y, double z) {
        return String.format("%.2f,%.2f,%.2f", x, y, z);
    }

    public Map<String, MarkerData.SavedMarkerFile> getLoadedFiles() {
        return loadedFiles;
    }

    public Set<String> getHiddenFiles() {
        return hiddenFiles;
    }
}