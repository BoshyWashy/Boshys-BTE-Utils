package com.boshys.bteutils.storage;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.data.MarkerData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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

    // CRITICAL FIX: Track markers by a unique file entry ID that persists across moves
    // Maps: filename -> (fileEntryIndex -> marker)
    public final Map<String, Map<Integer, MarkerData.TeleportMarker>> fileMarkerIndexMap = new HashMap<>();
    // Maps: marker -> (filename, fileEntryIndex)
    public final Map<MarkerData.TeleportMarker, FileMarkerId> markerToFileId = new HashMap<>();

    private int pendingClearCount = 0;
    private boolean pendingClearAll = false;

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
        if (BoshysBTEUtils.getConfig().savedMarkersFolderPath != null && !BoshysBTEUtils.getConfig().savedMarkersFolderPath.isEmpty()) {
            markersSavePath = Path.of(BoshysBTEUtils.getConfig().savedMarkersFolderPath);
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
        if (BoshysBTEUtils.getConfig().kmlFolderPath != null && !BoshysBTEUtils.getConfig().kmlFolderPath.isEmpty()) {
            return Path.of(BoshysBTEUtils.getConfig().kmlFolderPath);
        }
        return getMarkersSavePath();
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
    }

    public int clearCacheMarkersOnly() {
        final int[] removedCount = new int[1];
        BoshysBTEUtils.markers.removeIf(marker -> {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                removedCount[0]++;
                BoshysBTEUtils.markerOrigins.remove(marker);
                BoshysBTEUtils.markerOriginalPositions.remove(marker);
                // CRITICAL FIX: Clean up file ID tracking
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
            // CRITICAL FIX: Clear file ID tracking
            fileMarkerIndexMap.clear();
            markerToFileId.clear();
            pendingClearCount = 0;
            pendingClearAll = false;
            return count;
        } else {
            int count = clearCacheMarkersOnly();
            pendingClearCount = 0;
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

    // Helper method to create position key for matching (since Vec3d doesn't implement equals)
    private String posKey(Vec3d pos) {
        return String.format("%.6f,%.6f,%.6f", pos.x, pos.y, pos.z);
    }

    private String posKey(double x, double y, double z) {
        return String.format("%.6f,%.6f,%.6f", x, y, z);
    }

    // FIXED: Save markers with proper file handling to prevent EOFException
    public int saveMarkersToFile(FabricClientCommandSource source, String filename, double radius) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) {
            source.sendFeedback(Text.literal("§cMarkers disabled in config!"));
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
            source.sendFeedback(Text.literal("§cNo markers in cache to save!"));
            return 0;
        }

        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        if (filename.isEmpty()) {
            source.sendFeedback(Text.literal("§cInvalid filename!"));
            return 0;
        }

        ClientPlayerEntity player = source.getPlayer();
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        List<MarkerData.SavedMarkerData> markersToSave = new ArrayList<>();
        List<MarkerData.SavedConnectionData> connectionsToSave = new ArrayList<>();
        List<MarkerData.TeleportMarker> savedCacheMarkers = new ArrayList<>();

        // Build set of positions already saved to ANY file (except autosave)
        Set<String> alreadySavedPositions = new HashSet<>();
        Path savePath = getMarkersSavePath();
        File dir = savePath.toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] existingFiles = dir.listFiles((d, name) -> name.endsWith(".json") && !name.equals("autosave.json") && !name.startsWith("autosave_"));
            if (existingFiles != null) {
                for (File existingFile : existingFiles) {
                    try (FileReader reader = new FileReader(existingFile)) {
                        MarkerData.SavedMarkerFile existingData = GSON.fromJson(reader, MarkerData.SavedMarkerFile.class);
                        if (existingData != null && existingData.markers != null) {
                            for (MarkerData.SavedMarkerData data : existingData.markers) {
                                alreadySavedPositions.add(posKey(data.x, data.y, data.z));
                            }
                        }
                    } catch (IOException e) {
                    }
                }
            }
        }

        Map<MarkerData.TeleportMarker, Integer> markerIndexMap = new HashMap<>();
        int index = 0;

        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            // Only save cache markers (no origin, autosave, or autosave_ prefix)
            if (origin != null && !origin.equals("autosave") && !origin.startsWith("autosave_")) {
                continue;
            }

            String posKey = posKey(marker.position);

            if (alreadySavedPositions.contains(posKey)) {
                continue;
            }

            if (radius < 0 || marker.position.distanceTo(playerPos) <= radius) {
                markersToSave.add(new MarkerData.SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity
                ));
                markerIndexMap.put(marker, index);
                savedCacheMarkers.add(marker);
                index++;
            }
        }

        if (markersToSave.isEmpty()) {
            source.sendFeedback(Text.literal("§cNo new unsaved markers within specified radius!"));
            return 0;
        }

        for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
            Integer idx1 = markerIndexMap.get(conn.marker1);
            Integer idx2 = markerIndexMap.get(conn.marker2);
            if (idx1 != null && idx2 != null) {
                connectionsToSave.add(new MarkerData.SavedConnectionData(idx1, idx2));
            }
        }

        MarkerData.SavedMarkerFile fileData = new MarkerData.SavedMarkerFile(filename, System.currentTimeMillis(), markersToSave, connectionsToSave);

        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        // FIXED: Use try-with-resources properly to ensure file is fully written
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(fileData, writer);
            writer.flush(); // Explicitly flush to ensure all data is written
        } catch (IOException e) {
            source.sendFeedback(Text.literal("§cFailed to save markers: " + e.getMessage()));
            return 0;
        }

        // Only remove markers and load file if save was successful
        for (MarkerData.TeleportMarker savedMarker : savedCacheMarkers) {
            BoshysBTEUtils.markers.remove(savedMarker);
            BoshysBTEUtils.markerOrigins.remove(savedMarker);
            BoshysBTEUtils.markerOriginalPositions.remove(savedMarker);
        }

        // Remove connections that involved removed markers
        BoshysBTEUtils.markerConnections.removeIf(conn ->
                !BoshysBTEUtils.markers.contains(conn.marker1) || !BoshysBTEUtils.markers.contains(conn.marker2));
        BoshysBTEUtils.selectedMarkers.removeIf(marker -> !BoshysBTEUtils.markers.contains(marker));
        if (BoshysBTEUtils.lastAddedMarker != null && !BoshysBTEUtils.markers.contains(BoshysBTEUtils.lastAddedMarker)) {
            BoshysBTEUtils.lastAddedMarker = null;
        }

        // Load the file we just saved
        boolean loadSuccess = loadMarkerFileInternal(filename, true);

        Text message = Text.literal("§aSaved " + markersToSave.size() + " markers to '")
                .append(Text.literal(filename).styled(style -> style.withBold(true)))
                .append(Text.literal("' §aand loaded it!"))
                .styled(style -> style
                        .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("§eClick to open folder")))
                );

        source.sendFeedback(message);
        return 1;
    }

    // CRITICAL FIX: Completely rewritten updateMarkerFile to properly handle moved markers
    public int updateMarkerFile(FabricClientCommandSource source, String filename, double radius) {
        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

        // Check if file is loaded first - if not, we can't update it
        if (!loadedFiles.containsKey(filename)) {
            source.sendFeedback(Text.literal("§cFile '" + filename + "' is not loaded! You must load it first with /boshys-bt-utils load " + filename));
            return 0;
        }

        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        if (!file.exists()) {
            source.sendFeedback(Text.literal("§cFile '" + filename + "' not found!"));
            return 0;
        }

        try (FileReader reader = new FileReader(file)) {
            MarkerData.SavedMarkerFile existingData = GSON.fromJson(reader, MarkerData.SavedMarkerFile.class);
            if (existingData == null) {
                existingData = new MarkerData.SavedMarkerFile(filename, System.currentTimeMillis(), new ArrayList<>(), new ArrayList<>());
            }
            if (existingData.markers == null) {
                existingData.markers = new ArrayList<>();
            }
            if (existingData.connections == null) {
                existingData.connections = new ArrayList<>();
            }

            ClientPlayerEntity player = source.getPlayer();
            Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());

            // CRITICAL FIX: Build a map from file entry index to current marker using persistent ID tracking
            Map<Integer, MarkerData.TeleportMarker> indexToMarker = new HashMap<>();

            // First, try to use the persistent file ID mapping
            Map<Integer, MarkerData.TeleportMarker> fileIndexMap = fileMarkerIndexMap.get(filename);
            if (fileIndexMap != null) {
                for (Map.Entry<Integer, MarkerData.TeleportMarker> entry : fileIndexMap.entrySet()) {
                    MarkerData.TeleportMarker marker = entry.getValue();
                    // Only include if marker still exists and belongs to this file
                    if (BoshysBTEUtils.markers.contains(marker)) {
                        String origin = BoshysBTEUtils.markerOrigins.get(marker);
                        if (filename.equals(origin)) {
                            indexToMarker.put(entry.getKey(), marker);
                        }
                    }
                }
            }

            // Fallback: Try to match by original position for backwards compatibility
            if (indexToMarker.isEmpty()) {
                for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                    String origin = BoshysBTEUtils.markerOrigins.get(marker);
                    if (filename.equals(origin)) {
                        Vec3d originalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                        if (originalPos != null) {
                            // Find which index this corresponds to in the file
                            for (int i = 0; i < existingData.markers.size(); i++) {
                                MarkerData.SavedMarkerData data = existingData.markers.get(i);
                                String dataPosKey = posKey(data.x, data.y, data.z);
                                String markerPosKey = posKey(originalPos);
                                if (dataPosKey.equals(markerPosKey)) {
                                    indexToMarker.put(i, marker);
                                    // Update the persistent mapping
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

            // Process existing markers from file - match them to current markers by persistent ID
            for (int i = 0; i < existingData.markers.size(); i++) {
                MarkerData.TeleportMarker currentMarker = indexToMarker.get(i);

                if (currentMarker == null) {
                    // Marker was deleted - don't include in updated file
                    removedCount++;
                    continue;
                }

                // Create new saved data with CURRENT position (where marker is now)
                MarkerData.SavedMarkerData oldData = existingData.markers.get(i);
                MarkerData.SavedMarkerData newData = new MarkerData.SavedMarkerData(
                        currentMarker.position.x, currentMarker.position.y, currentMarker.position.z,
                        currentMarker.colour, currentMarker.scale, currentMarker.opacity
                );

                // Check if marker was modified (moved or design changed)
                boolean wasModified = currentMarker.colour != oldData.colour ||
                        currentMarker.scale != oldData.scale ||
                        currentMarker.opacity != oldData.opacity ||
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

                // CRITICAL FIX: Update the persistent ID mapping
                fileMarkerIndexMap.computeIfAbsent(filename, k -> new HashMap<>()).put(newIndex - 1, currentMarker);
                markerToFileId.put(currentMarker, new FileMarkerId(filename, newIndex - 1));
                // Also update original position tracking
                BoshysBTEUtils.markerOriginalPositions.put(currentMarker, new Vec3d(currentMarker.position.x, currentMarker.position.y, currentMarker.position.z));
            }

            // Add new cache markers to the file (markers without file origin or autosave markers)
            int addedCount = 0;
            List<MarkerData.TeleportMarker> newlyAddedMarkers = new ArrayList<>();

            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                String origin = BoshysBTEUtils.markerOrigins.get(marker);
                // Only process cache markers (no origin, autosave, or autosave_ prefix)
                if (origin != null && !origin.equals("autosave") && !origin.startsWith("autosave_")) {
                    continue;
                }

                // Check if this marker is already tracked in the file
                boolean alreadyInFile = indexToMarker.values().contains(marker);

                if (alreadyInFile) {
                    continue;
                }

                // Check radius if specified
                if (radius >= 0 && marker.position.distanceTo(playerPos) > radius) {
                    continue;
                }

                // Check for duplicate positions
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

                // Add new marker to file
                MarkerData.SavedMarkerData newData = new MarkerData.SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity
                );
                newMarkersList.add(newData);

                // CRITICAL FIX: Assign this marker to the file and track its position
                BoshysBTEUtils.markerOrigins.put(marker, filename);
                BoshysBTEUtils.markerOriginalPositions.put(marker, new Vec3d(marker.position.x, marker.position.y, marker.position.z));
                newlyAddedMarkers.add(marker);

                // Update persistent ID mapping
                int newMarkerIndex = newIndex;
                fileMarkerIndexMap.computeIfAbsent(filename, k -> new HashMap<>()).put(newMarkerIndex, marker);
                markerToFileId.put(marker, new FileMarkerId(filename, newMarkerIndex));

                newIndex++;
                addedCount++;
            }

            // Build final index map for connection remapping
            Map<MarkerData.TeleportMarker, Integer> finalIndexMap = new HashMap<>();

            // Rebuild index map based on new markers list
            for (int newIdx = 0; newIdx < newMarkersList.size(); newIdx++) {
                // Find which marker corresponds to this new index
                for (Map.Entry<MarkerData.TeleportMarker, FileMarkerId> entry : markerToFileId.entrySet()) {
                    if (entry.getValue().filename.equals(filename) && entry.getValue().index == newIdx) {
                        finalIndexMap.put(entry.getKey(), newIdx);
                        break;
                    }
                }
            }

            // Remap connections - preserve existing connections that still exist
            Set<String> addedConnections = new HashSet<>();

            for (MarkerData.SavedConnectionData oldConn : existingData.connections) {
                Integer newFrom = indexRemap.get(oldConn.fromIndex);
                Integer newTo = indexRemap.get(oldConn.toIndex);

                if (newFrom != null && newTo != null && !newFrom.equals(newTo)) {
                    String connKey = newFrom < newTo ? newFrom + ":" + newTo : newTo + ":" + newFrom;
                    if (!addedConnections.contains(connKey)) {
                        newConnectionsList.add(new MarkerData.SavedConnectionData(newFrom, newTo));
                        addedConnections.add(connKey);
                    }
                }
            }

            // Add current connections between markers in this file
            for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
                Integer idx1 = finalIndexMap.get(conn.marker1);
                Integer idx2 = finalIndexMap.get(conn.marker2);

                if (idx1 != null && idx2 != null && !idx1.equals(idx2)) {
                    String connKey = idx1 < idx2 ? idx1 + ":" + idx2 : idx2 + ":" + idx1;
                    if (!addedConnections.contains(connKey)) {
                        newConnectionsList.add(new MarkerData.SavedConnectionData(idx1, idx2));
                        addedConnections.add(connKey);
                    }
                }
            }

            // Update file data
            existingData.markers = newMarkersList;
            existingData.connections = newConnectionsList;
            existingData.lastModified = System.currentTimeMillis();

            // Write updated file
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(existingData, writer);
                writer.flush();
            }

            // Update the loaded file data in memory
            loadedFiles.put(filename, existingData);
            modifiedLoadedFiles.remove(filename);

            StringBuilder msg = new StringBuilder();
            msg.append("§aUpdated '").append(filename).append("'!");
            if (preservedCount > 0) msg.append(" Preserved: ").append(preservedCount);
            if (updatedCount > 0) msg.append(" Updated: ").append(updatedCount);
            if (removedCount > 0) msg.append(" Removed: ").append(removedCount);
            if (addedCount > 0) msg.append(" Added: ").append(addedCount);
            msg.append(". Total: ").append(newMarkersList.size());

            Text message = Text.literal(msg.toString())
                    .styled(style -> style
                            .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                            .withHoverEvent(new HoverEvent.ShowText(Text.literal("§eClick to open folder")))
                    );

            source.sendFeedback(message);
            return 1;
        } catch (IOException e) {
            source.sendFeedback(Text.literal("§cFailed to update file: " + e.getMessage()));
            return 0;
        }
    }

    public int loadMarkerFile(FabricClientCommandSource source, String filename) {
        return loadMarkerFileInternal(filename, false) ? 1 : 0;
    }

    // FIXED: Improved loadMarkerFileInternal with better error handling and persistent ID tracking
    public boolean loadMarkerFileInternal(String filename, boolean silent) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) {
            if (!silent) {
                MinecraftClient.getInstance().player.sendMessage(Text.literal("§cMarkers disabled in config!"), false);
            }
            return false;
        }

        filename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
        File file = getMarkersSavePath().resolve(filename + ".json").toFile();

        if (!file.exists()) {
            if (!silent) {
                MinecraftClient.getInstance().player.sendMessage(Text.literal("§cFile '" + filename + "' not found!"), false);
            }
            return false;
        }

        // Check if file is empty
        if (file.length() == 0) {
            if (!silent) {
                MinecraftClient.getInstance().player.sendMessage(Text.literal("§cFile '" + filename + "' is empty!"), false);
            }
            return false;
        }

        try (FileReader reader = new FileReader(file)) {
            MarkerData.SavedMarkerFile fileData = GSON.fromJson(reader, MarkerData.SavedMarkerFile.class);
            if (fileData == null || fileData.markers == null) {
                if (!silent) {
                    MinecraftClient.getInstance().player.sendMessage(Text.literal("§cInvalid file format!"), false);
                }
                return false;
            }

            hiddenFiles.remove(filename);

            int loadedCount = 0;
            List<MarkerData.TeleportMarker> loadedMarkers = new ArrayList<>();

            // CRITICAL FIX: Initialize persistent ID tracking for this file
            Map<Integer, MarkerData.TeleportMarker> indexMap = new HashMap<>();

            boolean isAutosave = filename.equals("autosave") || filename.startsWith("autosave_");

            for (int i = 0; i < fileData.markers.size(); i++) {
                MarkerData.SavedMarkerData data = fileData.markers.get(i);
                MarkerData.TeleportMarker marker = new MarkerData.TeleportMarker(
                        new Vec3d(data.x, data.y, data.z),
                        data.colour, data.scale, data.opacity
                );
                BoshysBTEUtils.markers.add(marker);
                loadedMarkers.add(marker);
                loadedCount++;

                if (!isAutosave) {
                    BoshysBTEUtils.markerOrigins.put(marker, filename);
                    // CRITICAL: Store the original position from file
                    BoshysBTEUtils.markerOriginalPositions.put(marker, new Vec3d(data.x, data.y, data.z));
                    // CRITICAL FIX: Set up persistent ID tracking
                    indexMap.put(i, marker);
                    markerToFileId.put(marker, new FileMarkerId(filename, i));
                }
            }

            // Store the index map for this file
            if (!isAutosave) {
                fileMarkerIndexMap.put(filename, indexMap);
            }

            int loadedConnections = 0;
            if (fileData.connections != null) {
                for (MarkerData.SavedConnectionData connData : fileData.connections) {
                    if (connData.fromIndex >= 0 && connData.fromIndex < loadedMarkers.size() &&
                            connData.toIndex >= 0 && connData.toIndex < loadedMarkers.size()) {
                        MarkerData.connectMarkers(loadedMarkers.get(connData.fromIndex), loadedMarkers.get(connData.toIndex));
                        loadedConnections++;
                    }
                }
            }

            if (!isAutosave) {
                loadedFiles.put(filename, fileData);
                modifiedLoadedFiles.remove(filename);
            }

            if (!silent) {
                Text message = Text.literal("§aLoaded " + loadedCount + " markers")
                        .append(loadedConnections > 0 ? Text.literal(" with " + loadedConnections + " connections") : Text.literal(""))
                        .append(Text.literal(" from '"))
                        .append(Text.literal(filename).styled(style -> style.withBold(true)))
                        .append(Text.literal("'!"))
                        .append(isAutosave ? Text.literal(" (as cache markers)") : Text.literal(""))
                        .styled(style -> style
                                .withClickEvent(new ClickEvent.OpenFile(file.getParentFile().getAbsolutePath()))
                                .withHoverEvent(new HoverEvent.ShowText(Text.literal("§eClick to open folder")))
                        );

                MinecraftClient.getInstance().player.sendMessage(message, false);
            }
            return true;
        } catch (Exception e) {
            if (!silent) {
                MinecraftClient.getInstance().player.sendMessage(Text.literal("§cFailed to load file '" + filename + "': " + e.getMessage()), false);
            }
            return false;
        }
    }

    public int hideMarkerFile(FabricClientCommandSource source, String filename) {
        final String finalFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

        if (!loadedFiles.containsKey(finalFilename)) {
            source.sendFeedback(Text.literal("§cFile '" + finalFilename + "' is not currently loaded!"));
            return 0;
        }

        MarkerData.SavedMarkerFile fileData = loadedFiles.get(finalFilename);

        Set<MarkerData.TeleportMarker> protectedMarkers = new HashSet<>();
        for (Map.Entry<String, MarkerData.SavedMarkerFile> entry : loadedFiles.entrySet()) {
            String otherFilename = entry.getKey();
            if (otherFilename.equals(finalFilename)) continue;

            MarkerData.SavedMarkerFile otherFile = entry.getValue();
            for (MarkerData.SavedMarkerData data : otherFile.markers) {
                for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                    String markerOrigin = BoshysBTEUtils.markerOrigins.get(marker);
                    if (otherFilename.equals(markerOrigin)) {
                        // CRITICAL FIX: Use position key for comparison
                        Vec3d markerOriginalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
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
                            Vec3d markerOriginalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                            if (markerOriginalPos != null) {
                                String markerPosKey = posKey(markerOriginalPos);
                                String dataPosKey = posKey(data.x, data.y, data.z);
                                if (markerPosKey.equals(dataPosKey)) {
                                    BoshysBTEUtils.markerOrigins.put(marker, otherFilename);
                                    BoshysBTEUtils.markerOriginalPositions.put(marker, new Vec3d(data.x, data.y, data.z));
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
                // CRITICAL FIX: Clean up file ID tracking
                markerToFileId.remove(marker);
                return true;
            }
            return false;
        });

        BoshysBTEUtils.markerConnections.removeIf(conn -> !BoshysBTEUtils.markers.contains(conn.marker1) || !BoshysBTEUtils.markers.contains(conn.marker2));
        BoshysBTEUtils.selectedMarkers.removeIf(marker -> !BoshysBTEUtils.markers.contains(marker));

        // CRITICAL FIX: Clean up file ID tracking for this file
        fileMarkerIndexMap.remove(finalFilename);

        loadedFiles.remove(finalFilename);
        modifiedLoadedFiles.remove(finalFilename);
        hiddenFiles.add(finalFilename);

        StringBuilder msg = new StringBuilder("§aHidden '" + finalFilename + "'! Removed " + removedCount[0] + " markers from display.");
        if (protectedCount[0] > 0) {
            msg.append(" (").append(protectedCount[0]).append(" markers kept from other files)");
        }
        source.sendFeedback(Text.literal(msg.toString()));
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
            source.sendFeedback(Text.literal("§cInvalid filename!"));
            return 0;
        }

        File file = getMarkersSavePath().resolve(baseName + ".json").toFile();

        if (!file.exists()) {
            source.sendFeedback(Text.literal("§cFile '" + cleanName + "' not found!"));
            return 0;
        }

        if (loadedFiles.containsKey(baseName)) {
            hideMarkerFile(source, baseName);
        }

        if (file.delete()) {
            hiddenFiles.remove(baseName);
            source.sendFeedback(Text.literal("§aDeleted file '" + cleanName + "' permanently!"));
            return 1;
        } else {
            source.sendFeedback(Text.literal("§cFailed to delete file!"));
            return 0;
        }
    }

    // FIXED: mergeMarkerFiles now properly removes old markers, cleans origins, and deletes old files
    public int mergeMarkerFiles(FabricClientCommandSource source, String mergedFileName, boolean includeCached, List<String> filenames) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) {
            source.sendFeedback(Text.literal("§cMarkers disabled in config!"));
            return 0;
        }

        if (filenames.isEmpty()) {
            source.sendFeedback(Text.literal("§cNeed at least one file to merge!"));
            return 0;
        }

        mergedFileName = mergedFileName.replaceAll("[^a-zA-Z0-9_-]", "");
        if (mergedFileName.isEmpty()) {
            source.sendFeedback(Text.literal("§cInvalid merged filename!"));
            return 0;
        }

        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
            File file = getMarkersSavePath().resolve(cleanFilename + ".json").toFile();
            if (!file.exists()) {
                source.sendFeedback(Text.literal("§cFile '" + cleanFilename + "' not found!"));
                return 0;
            }
        }

        List<MarkerData.SavedMarkerData> allMarkers = new ArrayList<>();
        List<MarkerData.SavedConnectionData> allConnections = new ArrayList<>();
        int baseIndex = 0;

        // Track which source markers need to be removed from the world after merge
        Set<MarkerData.TeleportMarker> markersToRemove = new HashSet<>();
        // Track which cache markers need to be removed
        Set<MarkerData.TeleportMarker> cacheMarkersToRemove = new HashSet<>();
        // Track cache markers for connection preservation
        List<MarkerData.TeleportMarker> cacheMarkersInOrder = new ArrayList<>();

        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
            File file = getMarkersSavePath().resolve(cleanFilename + ".json").toFile();

            try (FileReader reader = new FileReader(file)) {
                MarkerData.SavedMarkerFile fileData = GSON.fromJson(reader, MarkerData.SavedMarkerFile.class);
                if (fileData != null && fileData.markers != null) {
                    // Find all markers currently in-world that belong to this source file
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
                                data.circleRadius
                        ));
                    }

                    if (fileData.connections != null) {
                        for (MarkerData.SavedConnectionData conn : fileData.connections) {
                            allConnections.add(new MarkerData.SavedConnectionData(
                                    conn.fromIndex + baseIndex,
                                    conn.toIndex + baseIndex
                            ));
                        }
                    }

                    baseIndex += fileData.markers.size();
                }
            } catch (IOException e) {
                source.sendFeedback(Text.literal("§cError reading file '" + cleanFilename + "': " + e.getMessage()));
                return 0;
            }
        }

        // CRITICAL FIX: Preserve connections between cache markers and between cache markers and file markers
        if (includeCached) {
            // Collect all cache markers that will be included
            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                String origin = BoshysBTEUtils.markerOrigins.get(marker);
                if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                    cacheMarkersInOrder.add(marker);
                }
            }

            // Add cache markers to the merged list
            int cacheBaseIndex = baseIndex;
            for (MarkerData.TeleportMarker marker : cacheMarkersInOrder) {
                allMarkers.add(new MarkerData.SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity,
                        marker.circleRadius
                ));
                cacheMarkersToRemove.add(marker);
            }

            // CRITICAL FIX: Preserve connections among all markers being merged (file + cache)
            // Build a unified index map for connection remapping
            List<MarkerData.TeleportMarker> allMarkersInOrder = new ArrayList<>();

            // Add file markers first (in order of files)
            for (String filename : filenames) {
                String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");
                for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                    String origin = BoshysBTEUtils.markerOrigins.get(marker);
                    if (cleanFilename.equals(origin)) {
                        allMarkersInOrder.add(marker);
                    }
                }
            }
            // Add cache markers
            allMarkersInOrder.addAll(cacheMarkersInOrder);

            // Build index map
            Map<MarkerData.TeleportMarker, Integer> unifiedIndexMap = new HashMap<>();
            for (int i = 0; i < allMarkersInOrder.size(); i++) {
                unifiedIndexMap.put(allMarkersInOrder.get(i), i);
            }

            // CRITICAL FIX: Remap all existing connections into the unified index space
            Set<String> addedConnections = new HashSet<>();
            for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
                Integer idx1 = unifiedIndexMap.get(conn.marker1);
                Integer idx2 = unifiedIndexMap.get(conn.marker2);
                if (idx1 != null && idx2 != null && !idx1.equals(idx2)) {
                    String connKey = Math.min(idx1, idx2) + ":" + Math.max(idx1, idx2);
                    if (!addedConnections.contains(connKey)) {
                        allConnections.add(new MarkerData.SavedConnectionData(idx1, idx2));
                        addedConnections.add(connKey);
                    }
                }
            }

            baseIndex += cacheMarkersInOrder.size();
        }

        if (allMarkers.isEmpty()) {
            source.sendFeedback(Text.literal("§cNo markers to merge!"));
            return 0;
        }

        MarkerData.SavedMarkerFile mergedData = new MarkerData.SavedMarkerFile(mergedFileName, System.currentTimeMillis(), allMarkers, allConnections);
        File mergedFile = getMarkersSavePath().resolve(mergedFileName + ".json").toFile();

        // Write merged file BEFORE modifying world state (so we can rollback if save fails)
        try (FileWriter writer = new FileWriter(mergedFile)) {
            GSON.toJson(mergedData, writer);
            writer.flush();
        } catch (IOException e) {
            source.sendFeedback(Text.literal("§cFailed to save merged file: " + e.getMessage()));
            return 0;
        }

        // === SAVE SUCCESSFUL — now clean up old state ===

        hiddenFiles.remove(mergedFileName);

        // Remove old source file markers from the world
        for (MarkerData.TeleportMarker marker : markersToRemove) {
            BoshysBTEUtils.markers.remove(marker);
            BoshysBTEUtils.markerOrigins.remove(marker);
            BoshysBTEUtils.markerOriginalPositions.remove(marker);
            markerToFileId.remove(marker);
        }

        // Remove cache markers that were included in merge
        for (MarkerData.TeleportMarker marker : cacheMarkersToRemove) {
            BoshysBTEUtils.markers.remove(marker);
            BoshysBTEUtils.markerOrigins.remove(marker);
            BoshysBTEUtils.markerOriginalPositions.remove(marker);
            markerToFileId.remove(marker);
        }

        // Clean up connections that involved removed markers
        BoshysBTEUtils.markerConnections.removeIf(conn ->
                !BoshysBTEUtils.markers.contains(conn.marker1) || !BoshysBTEUtils.markers.contains(conn.marker2));
        BoshysBTEUtils.selectedMarkers.removeIf(marker -> !BoshysBTEUtils.markers.contains(marker));
        if (BoshysBTEUtils.lastAddedMarker != null && !BoshysBTEUtils.markers.contains(BoshysBTEUtils.lastAddedMarker)) {
            BoshysBTEUtils.lastAddedMarker = null;
        }

        // CRITICAL FIX: Remove old files from loaded state and tracking
        // BUT skip the merged file name to avoid deleting the file we just created
        for (String filename : filenames) {
            String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

            // CRITICAL FIX: Don't delete the merged file if it shares a name with a source file
            if (cleanFilename.equals(mergedFileName)) {
                continue;
            }

            loadedFiles.remove(cleanFilename);
            modifiedLoadedFiles.remove(cleanFilename);
            fileMarkerIndexMap.remove(cleanFilename);
            hiddenFiles.remove(cleanFilename);

            // Delete the old source file from disk (only after successful merge)
            File oldFile = getMarkersSavePath().resolve(cleanFilename + ".json").toFile();
            if (oldFile.exists()) {
                oldFile.delete();
            }
        }

        // Load the merged file
        int loadedCount = 0;
        List<MarkerData.TeleportMarker> loadedMarkers = new ArrayList<>();
        Map<Integer, MarkerData.TeleportMarker> mergedIndexMap = new HashMap<>();

        for (int i = 0; i < mergedData.markers.size(); i++) {
            MarkerData.SavedMarkerData data = mergedData.markers.get(i);
            MarkerData.TeleportMarker marker = new MarkerData.TeleportMarker(
                    new Vec3d(data.x, data.y, data.z),
                    data.colour, data.scale, data.opacity
            );
            marker.circleRadius = data.circleRadius;
            BoshysBTEUtils.markers.add(marker);
            loadedMarkers.add(marker);
            loadedCount++;

            BoshysBTEUtils.markerOrigins.put(marker, mergedFileName);
            BoshysBTEUtils.markerOriginalPositions.put(marker, new Vec3d(data.x, data.y, data.z));
            mergedIndexMap.put(i, marker);
            markerToFileId.put(marker, new FileMarkerId(mergedFileName, i));
        }

        fileMarkerIndexMap.put(mergedFileName, mergedIndexMap);

        int loadedConnections = 0;
        if (mergedData.connections != null) {
            for (MarkerData.SavedConnectionData connData : mergedData.connections) {
                if (connData.fromIndex >= 0 && connData.fromIndex < loadedMarkers.size() &&
                        connData.toIndex >= 0 && connData.toIndex < loadedMarkers.size()) {
                    MarkerData.connectMarkers(loadedMarkers.get(connData.fromIndex), loadedMarkers.get(connData.toIndex));
                    loadedConnections++;
                }
            }
        }

        loadedFiles.put(mergedFileName, mergedData);
        modifiedLoadedFiles.remove(mergedFileName);

        Text message = Text.literal("§aMerged " + allMarkers.size() + " markers")
                .append(allConnections.size() > 0 ? Text.literal(" with " + allConnections.size() + " connections") : Text.literal(""))
                .append(Text.literal(" into '"))
                .append(Text.literal(mergedFileName).styled(style -> style.withBold(true)))
                .append(Text.literal("' and loaded!"))
                .styled(style -> style
                        .withClickEvent(new ClickEvent.OpenFile(mergedFile.getParentFile().getAbsolutePath()))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("§eClick to open folder")))
                );

        source.sendFeedback(message);
        return 1;
    }
    public int moveSelectedMarkers(FabricClientCommandSource source, double dx, double dy, double dz) {
        if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
            source.sendFeedback(Text.literal("§cNo markers selected!"));
            return 0;
        }

        int movedCount = 0;
        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.selectedMarkers) {
            if (BoshysBTEUtils.markers.contains(marker)) {
                // Move the marker
                marker.position = marker.position.add(dx, dy, dz);
                movedCount++;

                // CRITICAL FIX: Update the persistent ID tracking - the marker keeps its ID but position changes
                FileMarkerId fileId = markerToFileId.get(marker);
                if (fileId != null) {
                    // Update the position in the file index map
                    Map<Integer, MarkerData.TeleportMarker> indexMap = fileMarkerIndexMap.get(fileId.filename);
                    if (indexMap != null && indexMap.get(fileId.index) == marker) {
                        // Marker is still at the same index, just moved position
                        // No need to change the mapping, but we should mark file as modified
                        MarkerData.SavedMarkerFile file = loadedFiles.get(fileId.filename);
                        if (file != null) {
                            modifiedLoadedFiles.put(fileId.filename, file);
                        }
                    }
                }

                // Also update original position tracking for backwards compatibility
                String origin = BoshysBTEUtils.markerOrigins.get(marker);
                if (origin != null && !origin.equals("autosave") && !origin.startsWith("autosave_")) {
                    Vec3d currentOriginal = BoshysBTEUtils.markerOriginalPositions.get(marker);
                    if (currentOriginal != null) {
                        BoshysBTEUtils.markerOriginalPositions.put(marker, currentOriginal.add(dx, dy, dz));
                    }

                    // Mark file as modified
                    MarkerData.SavedMarkerFile file = loadedFiles.get(origin);
                    if (file != null) {
                        modifiedLoadedFiles.put(origin, file);
                    }
                }
            }
        }

        source.sendFeedback(Text.literal("§aMoved " + movedCount + " marker(s) by (" + dx + ", " + dy + ", " + dz + ")!"));
        return 1;
    }

    public int moveSelectedMarkersToPosition(FabricClientCommandSource source, double x, double y, double z) {
        if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
            source.sendFeedback(Text.literal("§cNo markers selected!"));
            return 0;
        }

        MarkerData.TeleportMarker firstMarker = BoshysBTEUtils.selectedMarkers.iterator().next();
        double dx = x - firstMarker.position.x;
        double dy = y - firstMarker.position.y;
        double dz = z - firstMarker.position.z;

        return moveSelectedMarkers(source, dx, dy, dz);
    }

    // FIXED: moveAllMarkersInFile now properly updates persistent ID tracking
    public int moveAllMarkersInFile(FabricClientCommandSource source, String filename, double dx, double dy, double dz) {
        String cleanFilename = filename.replaceAll("[^a-zA-Z0-9_-]", "");

        if (cleanFilename.isEmpty()) {
            source.sendFeedback(Text.literal("§cInvalid filename!"));
            return 0;
        }

        // Check if file is loaded
        if (!loadedFiles.containsKey(cleanFilename)) {
            source.sendFeedback(Text.literal("§cFile '" + cleanFilename + "' is not loaded! Load it first with /boshys-bt-utils load " + cleanFilename));
            return 0;
        }

        int movedCount = 0;
        List<MarkerData.TeleportMarker> markersToMove = new ArrayList<>();

        // Find all markers belonging to this file using persistent ID tracking
        Map<Integer, MarkerData.TeleportMarker> indexMap = fileMarkerIndexMap.get(cleanFilename);
        if (indexMap != null) {
            for (MarkerData.TeleportMarker marker : indexMap.values()) {
                if (BoshysBTEUtils.markers.contains(marker)) {
                    markersToMove.add(marker);
                }
            }
        }

        // Fallback: scan all markers
        if (markersToMove.isEmpty()) {
            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                String origin = BoshysBTEUtils.markerOrigins.get(marker);
                if (cleanFilename.equals(origin)) {
                    markersToMove.add(marker);
                }
            }
        }

        if (markersToMove.isEmpty()) {
            source.sendFeedback(Text.literal("§cNo markers found from file '" + cleanFilename + "'!"));
            return 0;
        }

        // Move all markers from this file
        for (MarkerData.TeleportMarker marker : markersToMove) {
            if (BoshysBTEUtils.markers.contains(marker)) {
                // Move the marker
                marker.position = marker.position.add(dx, dy, dz);
                movedCount++;

                // CRITICAL FIX: Update original position tracking
                Vec3d originalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                if (originalPos != null) {
                    BoshysBTEUtils.markerOriginalPositions.put(marker, originalPos.add(dx, dy, dz));
                }
            }
        }

        // Mark file as modified for autosave
        MarkerData.SavedMarkerFile file = loadedFiles.get(cleanFilename);
        if (file != null) {
            modifiedLoadedFiles.put(cleanFilename, file);
        }

        source.sendFeedback(Text.literal("§aMoved " + movedCount + " marker(s) from '" + cleanFilename + "' by (" + dx + ", " + dy + ", " + dz + ")!"));
        return 1;
    }

    // FIXED: Autosave now properly includes line connections for modified loaded files
    public void performAutosave() {
        if (!BoshysBTEUtils.getConfig().enableAutosave) return;

        Path savePath = getMarkersSavePath();
        boolean savedAnything = false;

        // Autosave cache markers
        List<MarkerData.SavedMarkerData> cacheMarkers = new ArrayList<>();
        List<MarkerData.TeleportMarker> cacheMarkerObjects = new ArrayList<>();

        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            String origin = BoshysBTEUtils.markerOrigins.get(marker);
            if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                cacheMarkers.add(new MarkerData.SavedMarkerData(
                        marker.position.x, marker.position.y, marker.position.z,
                        marker.colour, marker.scale, marker.opacity
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
                    cacheConnections.add(new MarkerData.SavedConnectionData(idx1, idx2));
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

        // FIXED: Autosave modified loaded files with proper connection tracking
        for (Map.Entry<String, MarkerData.SavedMarkerFile> entry : modifiedLoadedFiles.entrySet()) {
            String filename = entry.getKey();
            MarkerData.SavedMarkerFile fileData = entry.getValue();

            String dateStr = DATE_FORMAT.format(new Date());
            File autosaveFile = savePath.resolve("autosave_" + dateStr + "_" + filename + ".json").toFile();

            try (FileWriter writer = new FileWriter(autosaveFile)) {
                List<MarkerData.SavedMarkerData> updatedMarkers = new ArrayList<>();
                List<MarkerData.SavedConnectionData> updatedConnections = new ArrayList<>();

                // FIXED: Build index map using persistent ID tracking
                Map<MarkerData.TeleportMarker, Integer> markerIndexMap = new HashMap<>();
                Map<Integer, MarkerData.TeleportMarker> indexToMarker = new HashMap<>();
                int index = 0;

                // First, try to use persistent ID mapping
                Map<Integer, MarkerData.TeleportMarker> fileIndexMap = fileMarkerIndexMap.get(filename);
                if (fileIndexMap != null) {
                    for (Map.Entry<Integer, MarkerData.TeleportMarker> entry2 : fileIndexMap.entrySet()) {
                        MarkerData.TeleportMarker marker = entry2.getValue();
                        if (BoshysBTEUtils.markers.contains(marker)) {
                            String origin = BoshysBTEUtils.markerOrigins.get(marker);
                            if (filename.equals(origin)) {
                                updatedMarkers.add(new MarkerData.SavedMarkerData(
                                        marker.position.x, marker.position.y, marker.position.z,
                                        marker.colour, marker.scale, marker.opacity
                                ));
                                markerIndexMap.put(marker, index);
                                indexToMarker.put(index, marker);
                                index++;
                            }
                        }
                    }
                }

                // Fallback: scan by original position
                if (updatedMarkers.isEmpty()) {
                    for (MarkerData.SavedMarkerData originalData : fileData.markers) {
                        Vec3d originalPos = new Vec3d(originalData.x, originalData.y, originalData.z);

                        boolean found = false;
                        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                            Vec3d markerOriginalPos = BoshysBTEUtils.markerOriginalPositions.get(marker);
                            String origin = BoshysBTEUtils.markerOrigins.get(marker);

                            if (filename.equals(origin) && markerOriginalPos != null &&
                                    posKey(markerOriginalPos).equals(posKey(originalPos))) {
                                updatedMarkers.add(new MarkerData.SavedMarkerData(
                                        marker.position.x, marker.position.y, marker.position.z,
                                        marker.colour, marker.scale, marker.opacity
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

                // FIXED: Save connections between markers in this file
                // First, preserve existing connections that are still valid
                if (fileData.connections != null) {
                    for (MarkerData.SavedConnectionData oldConn : fileData.connections) {
                        MarkerData.TeleportMarker fromMarker = indexToMarker.get(oldConn.fromIndex);
                        MarkerData.TeleportMarker toMarker = indexToMarker.get(oldConn.toIndex);

                        if (fromMarker != null && toMarker != null) {
                            Integer newFromIdx = markerIndexMap.get(fromMarker);
                            Integer newToIdx = markerIndexMap.get(toMarker);
                            if (newFromIdx != null && newToIdx != null) {
                                updatedConnections.add(new MarkerData.SavedConnectionData(newFromIdx, newToIdx));
                            }
                        }
                    }
                }

                // Then add any new connections between markers in this file
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
                            updatedConnections.add(new MarkerData.SavedConnectionData(idx1, idx2));
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
        if (BoshysBTEUtils.getConfig().enableAutosave && BoshysBTEUtils.getConfig().autosaveIntervalMinutes > 0) {
            long currentTime = System.currentTimeMillis();
            long intervalMs = BoshysBTEUtils.getConfig().autosaveIntervalMinutes * 60 * 1000;
            if (currentTime - lastAutosaveTime >= intervalMs) {
                performAutosave();
                lastAutosaveTime = currentTime;
            }
        }
    }

    private String formatPosition(double x, double y, double z) {
        return String.format("%.2f,%.2f,%.2f", x, y, z);
    }

    // Getters for loaded files
    public Map<String, MarkerData.SavedMarkerFile> getLoadedFiles() {
        return loadedFiles;
    }

    public Set<String> getHiddenFiles() {
        return hiddenFiles;
    }
}