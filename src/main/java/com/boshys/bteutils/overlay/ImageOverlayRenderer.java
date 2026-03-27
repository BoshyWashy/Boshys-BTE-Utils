/* package com.boshys.bteutils.overlay;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.data.ImageOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class ImageOverlayRenderer {
    private static final Map<String, Identifier> textureCache = new HashMap<>();
    private static final Set<String> failedToLoad = new HashSet<>(); // Track failed loads to prevent spam

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        ImageOverlayManager manager = BoshysBTEUtils.getImageOverlayManager();
        if (manager == null) return;

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        MatrixStack matrices = context.matrices();
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getPos();

        for (ImageOverlay overlay : manager.getOverlays()) {
            if (!overlay.visible) continue;

            Identifier textureId = getTexture(overlay.imagePath);
            if (textureId == null) continue; // Skip if texture failed to load

            matrices.push();

            // Translate to position relative to camera
            double x = overlay.position.x - cameraPos.x;
            double y = overlay.position.y - cameraPos.y + overlay.yOffset;
            double z = overlay.position.z - cameraPos.z;

            matrices.translate(x, y, z);

            // Apply rotation around Y axis
            if (overlay.rotation != 0) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) -overlay.rotation));
            }

            // Handle render mode
            switch (overlay.renderMode) {
                case "vertical" -> {
                    matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90));
                }
                case "billboard" -> {
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180));
                }
            }

            MatrixStack.Entry entry = matrices.peek();

            // Use entity translucent layer for proper transparency
            VertexConsumer buffer = consumers.getBuffer(RenderLayer.getEntityTranslucent(textureId));

            float halfWidth = (float) overlay.width / 2.0f;
            float halfHeight = (float) overlay.height / 2.0f;
            float alpha = overlay.opacity;
            int light = 15728880;

            // Build quad vertices - counter-clockwise winding
            if (overlay.isDistorted()) {
                Vec3d nw = overlay.cornerNW != null ? overlay.cornerNW : new Vec3d(-halfWidth, 0, -halfHeight);
                Vec3d ne = overlay.cornerNE != null ? overlay.cornerNE : new Vec3d(halfWidth, 0, -halfHeight);
                Vec3d sw = overlay.cornerSW != null ? overlay.cornerSW : new Vec3d(-halfWidth, 0, halfHeight);
                Vec3d se = overlay.cornerSE != null ? overlay.cornerSE : new Vec3d(halfWidth, 0, halfHeight);

                // North-West (0,0)
                buffer.vertex(entry.getPositionMatrix(), (float)nw.x, (float)nw.y, (float)nw.z)
                        .color(1f, 1f, 1f, alpha)
                        .texture(0, 0)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, 0, 1, 0);

                // North-East (1,0)
                buffer.vertex(entry.getPositionMatrix(), (float)ne.x, (float)ne.y, (float)ne.z)
                        .color(1f, 1f, 1f, alpha)
                        .texture(1, 0)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, 0, 1, 0);

                // South-East (1,1)
                buffer.vertex(entry.getPositionMatrix(), (float)se.x, (float)se.y, (float)se.z)
                        .color(1f, 1f, 1f, alpha)
                        .texture(1, 1)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, 0, 1, 0);

                // South-West (0,1)
                buffer.vertex(entry.getPositionMatrix(), (float)sw.x, (float)sw.y, (float)sw.z)
                        .color(1f, 1f, 1f, alpha)
                        .texture(0, 1)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, 0, 1, 0);
            } else {
                // Standard flat quad on XZ plane
                // Order: NW, NE, SE, SW (counter-clockwise when viewed from above)

                // North-West (-halfWidth, 0, -halfHeight) -> (0,0)
                buffer.vertex(entry.getPositionMatrix(), -halfWidth, 0, -halfHeight)
                        .color(1f, 1f, 1f, alpha)
                        .texture(0, 0)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, 0, 1, 0);

                // North-East (halfWidth, 0, -halfHeight) -> (1,0)
                buffer.vertex(entry.getPositionMatrix(), halfWidth, 0, -halfHeight)
                        .color(1f, 1f, 1f, alpha)
                        .texture(1, 0)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, 0, 1, 0);

                // South-East (halfWidth, 0, halfHeight) -> (1,1)
                buffer.vertex(entry.getPositionMatrix(), halfWidth, 0, halfHeight)
                        .color(1f, 1f, 1f, alpha)
                        .texture(1, 1)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, 0, 1, 0);

                // South-West (-halfWidth, 0, halfHeight) -> (0,1)
                buffer.vertex(entry.getPositionMatrix(), -halfWidth, 0, halfHeight)
                        .color(1f, 1f, 1f, alpha)
                        .texture(0, 1)
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, 0, 1, 0);
            }

            // Draw selection outline if selected
            if (manager.getSelectedOverlay() == overlay) {
                renderSelectionBox(consumers, entry, halfWidth, halfHeight);
            }

            matrices.pop();
        }
    }

    private static void renderSelectionBox(VertexConsumerProvider consumers, MatrixStack.Entry entry, float halfWidth, float halfHeight) {
        VertexConsumer lineBuffer = consumers.getBuffer(RenderLayer.getLines());
        float r = 1.0f, g = 1.0f, b = 0.0f, a = 1.0f;
        float y = 0.05f;

        // Draw rectangle outline using lines
        lineBuffer.vertex(entry.getPositionMatrix(), -halfWidth, y, -halfHeight)
                .color(r, g, b, a)
                .normal(entry, 0, 1, 0);
        lineBuffer.vertex(entry.getPositionMatrix(), halfWidth, y, -halfHeight)
                .color(r, g, b, a)
                .normal(entry, 0, 1, 0);

        lineBuffer.vertex(entry.getPositionMatrix(), halfWidth, y, -halfHeight)
                .color(r, g, b, a)
                .normal(entry, 0, 1, 0);
        lineBuffer.vertex(entry.getPositionMatrix(), halfWidth, y, halfHeight)
                .color(r, g, b, a)
                .normal(entry, 0, 1, 0);

        lineBuffer.vertex(entry.getPositionMatrix(), halfWidth, y, halfHeight)
                .color(r, g, b, a)
                .normal(entry, 0, 1, 0);
        lineBuffer.vertex(entry.getPositionMatrix(), -halfWidth, y, halfHeight)
                .color(r, g, b, a)
                .normal(entry, 0, 1, 0);

        lineBuffer.vertex(entry.getPositionMatrix(), -halfWidth, y, halfHeight)
                .color(r, g, b, a)
                .normal(entry, 0, 1, 0);
        lineBuffer.vertex(entry.getPositionMatrix(), -halfWidth, y, -halfHeight)
                .color(r, g, b, a)
                .normal(entry, 0, 1, 0);
    }

    private static Identifier getTexture(String imagePath) {
        // Return cached texture if available
        if (textureCache.containsKey(imagePath)) {
            return textureCache.get(imagePath);
        }

        // If we already tried and failed to load this image, don't try again (prevent log spam)
        if (failedToLoad.contains(imagePath)) {
            return null;
        }

        Path imagesDir = ImageOverlayManager.getImagesDirectory();
        File imageFile = imagesDir.resolve(imagePath).toFile();

        if (!imageFile.exists()) {
            System.err.println("[BoshysBTEUtils] Image file not found: " + imageFile.getAbsolutePath());
            failedToLoad.add(imagePath); // Mark as failed so we don't retry
            return null;
        }

        // Check if file is empty
        if (imageFile.length() == 0) {
            System.err.println("[BoshysBTEUtils] Image file is empty: " + imagePath);
            failedToLoad.add(imagePath);
            return null;
        }

        try (FileInputStream stream = new FileInputStream(imageFile)) {
            NativeImage image = NativeImage.read(stream);
            if (image == null) {
                System.err.println("[BoshysBTEUtils] Failed to load image (NativeImage returned null): " + imagePath);
                failedToLoad.add(imagePath);
                return null;
            }

            Supplier<String> nameSupplier = () -> "boshysbteutils_overlay_" + imagePath.replaceAll("[^a-zA-Z0-9]", "_");
            NativeImageBackedTexture texture = new NativeImageBackedTexture(nameSupplier, image);
            Identifier id = Identifier.of("boshysbteutils", "overlay/" + imagePath.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase());

            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            textureCache.put(imagePath, id);

            System.out.println("[BoshysBTEUtils] Loaded texture: " + id + " (" + image.getWidth() + "x" + image.getHeight() + ")");
            return id;
        } catch (IOException e) {
            System.err.println("[BoshysBTEUtils] Failed to load image: " + imagePath + " - " + e.getMessage());
            failedToLoad.add(imagePath); // CRITICAL FIX: Mark as failed so we don't spam logs every frame
            return null;
        } catch (Exception e) {
            System.err.println("[BoshysBTEUtils] Unexpected error loading image: " + imagePath + " - " + e.getMessage());
            failedToLoad.add(imagePath); // CRITICAL FIX: Also catch any other exceptions
            return null;
        }
    }

    public static void clearCache() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (Identifier id : textureCache.values()) {
            if (id != null) {
                client.getTextureManager().destroyTexture(id);
            }
        }
        textureCache.clear();
        failedToLoad.clear(); // Also clear failed loads so we can retry if needed
    }
} */