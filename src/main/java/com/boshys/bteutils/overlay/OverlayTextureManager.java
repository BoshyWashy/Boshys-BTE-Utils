package com.boshys.bteutils.overlay;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Caches and provides raw ARGB pixel grid data along with OpenGL/RenderPearl handles.
 */
public class OverlayTextureManager {

    private static final String NAMESPACE = "boshysbteutils";
    private final Map<String, Identifier> textureCache = new HashMap<>();
    private final Map<String, PixelData> pixelDataCache = new HashMap<>();

    public static class PixelData {
        public final int width;
        public final int height;
        public final int[] pixels; // ARGB pixel values (0xAARRGGBB)

        public PixelData(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    public OverlayTextureManager() {}

    /**
     * Processes texture creation and pixel data caching during client tick.
     */
    public void tick(OverlayStorage storage) {
        if (storage == null) return;
        for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
            if (overlay.imageFilename != null && !pixelDataCache.containsKey(overlay.imageFilename)) {
                loadAndUploadTexture(overlay.imageFilename);
            }
        }
    }

    /**
     * Retrieves the cached raw ARGB pixel grid data for an image overlay.
     * Auto-loads synchronously if not already cached.
     */
    public PixelData getPixelData(String imageFilename) {
        if (imageFilename == null) return null;
        if (!pixelDataCache.containsKey(imageFilename)) {
            loadAndUploadTexture(imageFilename);
        }
        return pixelDataCache.get(imageFilename);
    }

    public Identifier getTexture(String imageFilename) {
        return textureCache.get(imageFilename);
    }

    public GpuTextureView getTextureView(String imageFilename) {
        Identifier id = textureCache.get(imageFilename);
        if (id == null) return null;
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(id);
        if (texture == null) return null;
        try {
            return texture.getTextureView();
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method m = texture.getClass().getMethod("getTextureView");
                return (GpuTextureView) m.invoke(texture);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private void loadAndUploadTexture(String imageFilename) {
        File imageFile = OverlayStorage.getImagesPath().resolve(imageFilename).toFile();
        if (!imageFile.exists()) {
            textureCache.put(imageFilename, null);
            pixelDataCache.put(imageFilename, null);
            return;
        }

        try {
            String lower = imageFilename.toLowerCase();
            NativeImage nativeImage;
            int width, height;
            int[] argbPixels;

            if (lower.endsWith(".png")) {
                try (InputStream is = new FileInputStream(imageFile)) {
                    nativeImage = NativeImage.read(is);
                    width = nativeImage.getWidth();
                    height = nativeImage.getHeight();
                    argbPixels = new int[width * height];
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            // Standard ARGB extraction to prevent Red/Blue channel swapping
                            int pixel = nativeImage.getPixel(x, y);
                            int a = (pixel >> 24) & 0xFF;
                            int r = (pixel >> 16) & 0xFF;
                            int g = (pixel >> 8) & 0xFF;
                            int b = pixel & 0xFF;
                            argbPixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                        }
                    }
                }
            } else {
                BufferedImage buffered = ImageIO.read(imageFile);
                if (buffered == null) {
                    textureCache.put(imageFilename, null);
                    pixelDataCache.put(imageFilename, null);
                    return;
                }
                width = buffered.getWidth();
                height = buffered.getHeight();
                argbPixels = new int[width * height];
                buffered.getRGB(0, 0, width, height, argbPixels, 0, width);

                nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = argbPixels[y * width + x];
                        int a = (argb >> 24) & 0xFF;
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                        nativeImage.setPixel(x, y, abgr);
                    }
                }
            }

            pixelDataCache.put(imageFilename, new PixelData(width, height, argbPixels));

            DynamicTexture texture = new DynamicTexture(() -> "boshysbteutils/overlay/" + imageFilename, nativeImage);
            String safeName = imageFilename.toLowerCase().replaceAll("[^a-z0-9_./]", "_");
            Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, "overlays/" + safeName);

            Minecraft.getInstance().getTextureManager().register(id, texture);
            textureCache.put(imageFilename, id);
        } catch (Exception e) {
            textureCache.put(imageFilename, null);
            pixelDataCache.put(imageFilename, null);
        }
    }

    public void evict(String imageFilename) {
        pixelDataCache.remove(imageFilename);
        Identifier id = textureCache.remove(imageFilename);
        if (id != null) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    public void evictAll() {
        pixelDataCache.clear();
        for (Map.Entry<String, Identifier> entry : textureCache.entrySet()) {
            if (entry.getValue() != null) {
                Minecraft.getInstance().getTextureManager().release(entry.getValue());
            }
        }
        textureCache.clear();
    }
}