package com.boshys.bteutils.overlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages persisting and loading overlay data from
 * config/boshysbteutils/overlays/*.json
 * and keeps track of all currently loaded overlays.
 */
public class OverlayStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path IMAGES_PATH = Path.of("config/boshysbteutils/images");
    private static final Path OVERLAYS_PATH = Path.of("config/boshysbteutils/overlays");

    private final Map<String, OverlayData.ImageOverlay> loadedOverlays = new LinkedHashMap<>();

    public OverlayStorage() {
        ensureDirectories();
    }

    private void ensureDirectories() {
        IMAGES_PATH.toFile().mkdirs();
        OVERLAYS_PATH.toFile().mkdirs();
    }

    public static Path getImagesPath() {
        return IMAGES_PATH;
    }

    public static Path getOverlaysPath() {
        return OVERLAYS_PATH;
    }

    public Map<String, OverlayData.ImageOverlay> getLoadedOverlays() {
        return loadedOverlays;
    }

    public OverlayData.ImageOverlay getOverlay(String safeKey) {
        return loadedOverlays.get(safeKey);
    }

    public static List<String> listImageFiles() {
        List<String> result = new ArrayList<>();
        File dir = IMAGES_PATH.toFile();
        if (!dir.exists() || !dir.isDirectory()) return result;
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        });
        if (files != null) {
            for (File f : files) result.add(f.getName());
        }
        return result;
    }

    public static List<String> listSavedOverlayKeys() {
        List<String> result = new ArrayList<>();
        File dir = OVERLAYS_PATH.toFile();
        if (!dir.exists() || !dir.isDirectory()) return result;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                result.add(f.getName().replace(".json", ""));
            }
        }
        return result;
    }

    public boolean saveOverlay(OverlayData.ImageOverlay overlay) {
        ensureDirectories();
        String safeKey = OverlayData.toSafeFilename(overlay.displayName);
        File file = OVERLAYS_PATH.resolve(safeKey + ".json").toFile();
        try (FileWriter w = new FileWriter(file)) {
            GSON.toJson(new OverlayData.SavedOverlayData(overlay), w);
            w.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public OverlayData.ImageOverlay loadOverlay(String safeKey) {
        ensureDirectories();
        File file = OVERLAYS_PATH.resolve(safeKey + ".json").toFile();
        if (!file.exists()) return null;
        try (FileReader r = new FileReader(file)) {
            OverlayData.SavedOverlayData saved = GSON.fromJson(r, OverlayData.SavedOverlayData.class);
            if (saved == null) return null;
            OverlayData.ImageOverlay overlay = saved.toOverlay();
            loadedOverlays.put(safeKey, overlay);
            return overlay;
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            return null;
        }
    }

    public OverlayData.ImageOverlay createOverlay(String displayName, String imageFilename, Vec3d anchor, double size) {
        String safeKey = OverlayData.toSafeFilename(displayName);
        OverlayData.ImageOverlay overlay = new OverlayData.ImageOverlay(displayName, imageFilename, anchor, size);
        loadedOverlays.put(safeKey, overlay);
        saveOverlay(overlay);
        return overlay;
    }

    public void unloadOverlay(String safeKey) {
        loadedOverlays.remove(safeKey);
    }

    public boolean deleteOverlay(String safeKey) {
        unloadOverlay(safeKey);
        File file = OVERLAYS_PATH.resolve(safeKey + ".json").toFile();
        return file.exists() && file.delete();
    }

    public void saveAll() {
        for (OverlayData.ImageOverlay o : loadedOverlays.values()) {
            saveOverlay(o);
        }
    }
}