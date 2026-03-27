/*package com.boshys.bteutils.overlay;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.data.ImageOverlay;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ImageOverlayManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final List<ImageOverlay> overlays = new ArrayList<>();
    private ImageOverlay selectedOverlay = null;
    private int selectedCorner = -1;

    // Blueprint storage
    private final Map<String, OverlayBlueprint> blueprints = new HashMap<>();

    public List<ImageOverlay> getOverlays() {
        return Collections.unmodifiableList(overlays);
    }

    public ImageOverlay getSelectedOverlay() {
        return selectedOverlay;
    }

    public int getSelectedCorner() {
        return selectedCorner;
    }

    public ImageOverlay createOverlay(String name, Vec3d position, String imageFileName) {
        Path imagesDir = getImagesDirectory();
        File imageFile = imagesDir.resolve(imageFileName).toFile();

        if (!imageFile.exists()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(
                        Text.translatable("command.boshysbteutils.overlay.error.image_not_found", imageFileName).formatted(Formatting.RED),
                        false
                );
            }
            return null;
        }

        ImageOverlay overlay = new ImageOverlay(name, position, imageFileName);
        overlays.add(overlay);
        saveOverlays();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable("command.boshysbteutils.overlay.created", name).formatted(Formatting.GREEN),
                    true
            );
        }

        return overlay;
    }

    public boolean deleteOverlay(String idOrName) {
        Iterator<ImageOverlay> it = overlays.iterator();
        while (it.hasNext()) {
            ImageOverlay overlay = it.next();
            if (overlay.id.equalsIgnoreCase(idOrName) || overlay.name.equalsIgnoreCase(idOrName)) {
                it.remove();
                if (selectedOverlay == overlay) {
                    selectedOverlay = null;
                    selectedCorner = -1;
                }
                saveOverlays();
                return true;
            }
        }
        return false;
    }

    public void selectOverlay(ImageOverlay overlay) {
        this.selectedOverlay = overlay;
        this.selectedCorner = -1;
    }

    public void selectCorner(int corner) {
        this.selectedCorner = corner;
    }

    public void moveSelected(Vec3d delta) {
        if (selectedOverlay != null && !selectedOverlay.locked) {
            if (selectedCorner == -1) {
                selectedOverlay.position = selectedOverlay.position.add(delta);
            } else {
                switch (selectedCorner) {
                    case 0 -> selectedOverlay.cornerNW = selectedOverlay.cornerNW != null ?
                            selectedOverlay.cornerNW.add(delta) : new Vec3d(-selectedOverlay.width/2, 0, -selectedOverlay.height/2).add(delta);
                    case 1 -> selectedOverlay.cornerNE = selectedOverlay.cornerNE != null ?
                            selectedOverlay.cornerNE.add(delta) : new Vec3d(selectedOverlay.width/2, 0, -selectedOverlay.height/2).add(delta);
                    case 2 -> selectedOverlay.cornerSW = selectedOverlay.cornerSW != null ?
                            selectedOverlay.cornerSW.add(delta) : new Vec3d(-selectedOverlay.width/2, 0, selectedOverlay.height/2).add(delta);
                    case 3 -> selectedOverlay.cornerSE = selectedOverlay.cornerSE != null ?
                            selectedOverlay.cornerSE.add(delta) : new Vec3d(selectedOverlay.width/2, 0, selectedOverlay.height/2).add(delta);
                }
            }
            saveOverlays();
        }
    }

    public void moveOverlayToPlayer(String idOrName, Vec3d playerPos) {
        for (ImageOverlay overlay : overlays) {
            if (overlay.id.equalsIgnoreCase(idOrName) || overlay.name.equalsIgnoreCase(idOrName)) {
                if (overlay.locked) {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.translatable("command.boshysbteutils.overlay.locked", overlay.name).formatted(Formatting.RED),
                                false
                        );
                    }
                    return;
                }
                overlay.position = playerPos;
                saveOverlays();

                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.translatable("command.boshysbteutils.overlay.moved_to_player", overlay.name, (int)playerPos.x, (int)playerPos.y, (int)playerPos.z).formatted(Formatting.GREEN),
                            true
                    );
                }
                return;
            }
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable("command.boshysbteutils.overlay.not_found", idOrName).formatted(Formatting.RED),
                    false
            );
        }
    }

    public void rotateSelected(double degrees) {
        if (selectedOverlay != null && !selectedOverlay.locked) {
            selectedOverlay.rotation = (selectedOverlay.rotation + degrees) % 360;
            saveOverlays();
        }
    }

    public void scaleSelected(double factor) {
        if (selectedOverlay != null && !selectedOverlay.locked) {
            selectedOverlay.width *= factor;
            selectedOverlay.height *= factor;
            saveOverlays();
        }
    }

    public void setOverlayOpacity(String idOrName, float opacity) {
        for (ImageOverlay overlay : overlays) {
            if (overlay.id.equalsIgnoreCase(idOrName) || overlay.name.equalsIgnoreCase(idOrName)) {
                overlay.opacity = Math.max(0.0f, Math.min(1.0f, opacity));
                saveOverlays();
                break;
            }
        }
    }

    public void toggleOverlayVisibility(String idOrName) {
        for (ImageOverlay overlay : overlays) {
            if (overlay.id.equalsIgnoreCase(idOrName) || overlay.name.equalsIgnoreCase(idOrName)) {
                overlay.visible = !overlay.visible;
                saveOverlays();
                break;
            }
        }
    }

    public void toggleOverlayLock(String idOrName) {
        for (ImageOverlay overlay : overlays) {
            if (overlay.id.equalsIgnoreCase(idOrName) || overlay.name.equalsIgnoreCase(idOrName)) {
                overlay.locked = !overlay.locked;
                saveOverlays();
                break;
            }
        }
    }

    public void clearAll() {
        overlays.clear();
        selectedOverlay = null;
        selectedCorner = -1;
    }

    // Blueprint methods
    public boolean saveBlueprint(String overlayIdOrName, String blueprintName) {
        ImageOverlay target = null;
        for (ImageOverlay overlay : overlays) {
            if (overlay.id.equalsIgnoreCase(overlayIdOrName) || overlay.name.equalsIgnoreCase(overlayIdOrName)) {
                target = overlay;
                break;
            }
        }

        if (target == null) return false;

        OverlayBlueprint blueprint = new OverlayBlueprint(
                blueprintName,
                target.imagePath,
                target.width,
                target.height,
                target.rotation,
                target.opacity,
                target.renderMode,
                target.yOffset,
                target.cornerNW,
                target.cornerNE,
                target.cornerSW,
                target.cornerSE
        );

        blueprints.put(blueprintName.toLowerCase(), blueprint);
        saveBlueprintsToDisk();
        return true;
    }

    public ImageOverlay loadBlueprint(String blueprintName, Vec3d position) {
        OverlayBlueprint blueprint = blueprints.get(blueprintName.toLowerCase());
        if (blueprint == null) {
            // Try loading from disk if not in memory
            loadBlueprintsFromDisk();
            blueprint = blueprints.get(blueprintName.toLowerCase());
        }

        if (blueprint == null) return null;

        // Check if image still exists
        Path imagesDir = getImagesDirectory();
        File imageFile = imagesDir.resolve(blueprint.imagePath).toFile();
        if (!imageFile.exists()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(
                        Text.translatable("command.boshysbteutils.overlay.blueprint.image_missing", blueprint.imagePath).formatted(Formatting.RED),
                        false
                );
            }
            return null;
        }

        ImageOverlay overlay = new ImageOverlay(blueprint.name + "_" + System.currentTimeMillis() % 1000, position, blueprint.imagePath);
        overlay.width = blueprint.width;
        overlay.height = blueprint.height;
        overlay.rotation = blueprint.rotation;
        overlay.opacity = blueprint.opacity;
        overlay.renderMode = blueprint.renderMode;
        overlay.yOffset = blueprint.yOffset;
        overlay.cornerNW = blueprint.cornerNW;
        overlay.cornerNE = blueprint.cornerNE;
        overlay.cornerSW = blueprint.cornerSW;
        overlay.cornerSE = blueprint.cornerSE;

        overlays.add(overlay);
        saveOverlays();

        return overlay;
    }

    public List<String> listBlueprints() {
        if (blueprints.isEmpty()) {
            loadBlueprintsFromDisk();
        }
        return new ArrayList<>(blueprints.keySet());
    }

    public boolean deleteBlueprint(String blueprintName) {
        if (blueprints.remove(blueprintName.toLowerCase()) != null) {
            saveBlueprintsToDisk();
            return true;
        }
        return false;
    }

    public void saveOverlays() {
        try {
            Path configDir = Path.of("config/boshysbteutils");
            Files.createDirectories(configDir);
            Path file = configDir.resolve("image_overlays.json");

            try (FileWriter writer = new FileWriter(file.toFile())) {
                GSON.toJson(overlays, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save image overlays: " + e.getMessage());
        }
    }

    public void loadOverlays() {
        try {
            Path file = Path.of("config/boshysbteutils/image_overlays.json");
            if (file.toFile().exists()) {
                try (FileReader reader = new FileReader(file.toFile())) {
                    List<ImageOverlay> loaded = GSON.fromJson(reader, new TypeToken<List<ImageOverlay>>(){}.getType());
                    if (loaded != null) {
                        overlays.clear();
                        overlays.addAll(loaded);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load image overlays: " + e.getMessage());
        }

        // Also load blueprints into memory
        loadBlueprintsFromDisk();
    }

    private void saveBlueprintsToDisk() {
        try {
            Path blueprintDir = Path.of("config/boshysbteutils/blueprints");
            Files.createDirectories(blueprintDir);

            for (Map.Entry<String, OverlayBlueprint> entry : blueprints.entrySet()) {
                Path file = blueprintDir.resolve(entry.getKey() + ".json");
                try (FileWriter writer = new FileWriter(file.toFile())) {
                    GSON.toJson(entry.getValue(), writer);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to save blueprints: " + e.getMessage());
        }
    }

    private void loadBlueprintsFromDisk() {
        blueprints.clear();
        try {
            Path blueprintDir = Path.of("config/boshysbteutils/blueprints");
            if (!Files.exists(blueprintDir)) return;

            File[] files = blueprintDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return;

            for (File file : files) {
                try (FileReader reader = new FileReader(file)) {
                    OverlayBlueprint blueprint = GSON.fromJson(reader, OverlayBlueprint.class);
                    if (blueprint != null && blueprint.name != null) {
                        blueprints.put(blueprint.name.toLowerCase(), blueprint);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to load blueprint: " + file.getName());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load blueprints: " + e.getMessage());
        }
    }

    public static Path getImagesDirectory() {
        Path path = Path.of("config/boshysbteutils/images");
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return path;
    }

    public Optional<ImageOverlay> findOverlayAt(Vec3d point, double tolerance) {
        for (ImageOverlay overlay : overlays) {
            if (!overlay.visible) continue;

            if (overlay.position.isInRange(point, Math.max(overlay.width, overlay.height) / 2 + tolerance)) {
                return Optional.of(overlay);
            }
        }
        return Optional.empty();
    }

    // Inner class for blueprint data
    public static class OverlayBlueprint {
        public String name;
        public String imagePath;
        public double width;
        public double height;
        public double rotation;
        public float opacity;
        public String renderMode;
        public double yOffset;
        public Vec3d cornerNW, cornerNE, cornerSW, cornerSE;

        public OverlayBlueprint(String name, String imagePath, double width, double height,
                                double rotation, float opacity, String renderMode, double yOffset,
                                Vec3d cornerNW, Vec3d cornerNE, Vec3d cornerSW, Vec3d cornerSE) {
            this.name = name;
            this.imagePath = imagePath;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.opacity = opacity;
            this.renderMode = renderMode;
            this.yOffset = yOffset;
            this.cornerNW = cornerNW;
            this.cornerNE = cornerNE;
            this.cornerSW = cornerSW;
            this.cornerSE = cornerSE;
        }
    }
}
*/