package com.boshys.bteutils.overlay;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Caches and provides OpenGL {@link Identifier} handles for overlay images.
 * Images are loaded from config/boshysbtutils/images/ on first use.
 *
 * <p>All operations that touch OpenGL must be called from the render thread.
 * Loading the raw bytes is safe from any thread; uploading is deferred until
 * the next render call.</p>
 */
public class OverlayTextureManager {

    /** namespace used for all overlay texture identifiers */
    private static final String NAMESPACE = "boshysbteutils";

    /** cache: image filename → registered texture identifier */
    private final Map<String, Identifier> textureCache = new HashMap<>();
    /** tracks filenames queued for loading but not yet uploaded */
    private final Map<String, byte[]> pendingLoads = new HashMap<>();

    public OverlayTextureManager() {}

    /**
     * Returns the {@link Identifier} for the given image filename, loading and
     * uploading the texture if necessary.
     *
     * Must be called from the render thread.
     *
     * @param imageFilename e.g. "mymap.png"
     * @return identifier, or null if the image couldn't be loaded
     */
    public Identifier getOrLoadTexture(String imageFilename) {
        if (textureCache.containsKey(imageFilename)) {
            return textureCache.get(imageFilename);
        }

        // Try to load from disk
        File imageFile = OverlayStorage.getImagesPath().resolve(imageFilename).toFile();
        if (!imageFile.exists()) {
            return null;
        }

        try {
            Identifier id = uploadTexture(imageFilename, imageFile);
            if (id != null) {
                textureCache.put(imageFilename, id);
            }
            return id;
        } catch (Exception e) {
            // Put null in cache so we don't retry every frame
            textureCache.put(imageFilename, null);
            return null;
        }
    }

    /**
     * Loads a JPEG or PNG from disk and registers it with Minecraft's texture manager
     * as a {@link DynamicTexture}.
     */
    private Identifier uploadTexture(String imageFilename, File imageFile) throws Exception {
        String lower = imageFilename.toLowerCase();

        NativeImage nativeImage;

        if (lower.endsWith(".png")) {
            // NativeImage can load PNGs directly
            try (InputStream is = new FileInputStream(imageFile)) {
                nativeImage = NativeImage.read(is);
            }
        } else {
            // JPEG: decode with AWT then convert to NativeImage (ARGB/RGBA)
            BufferedImage buffered = ImageIO.read(imageFile);
            if (buffered == null) {
                return null;
            }
            int w = buffered.getWidth();
            int h = buffered.getHeight();
            nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = buffered.getRGB(x, y);
                    // AWT gives ARGB; NativeImage.setPixelRGBA expects ABGR
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // Pack as ABGR
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    nativeImage.setPixel(x, y, abgr);
                }
            }
        }

        DynamicTexture texture = new DynamicTexture(() -> "boshysbteutils/overlay/" + imageFilename, nativeImage);

        // Create a deterministic identifier from the filename
        String safeName = imageFilename.toLowerCase().replaceAll("[^a-z0-9_./]", "_");
        Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, "overlays/" + safeName);

        Minecraft.getInstance().getTextureManager().register(id, texture);
        return id;
    }

    /**
     * Evict a cached texture (e.g. when the image file is deleted).
     * The texture is also destroyed in the texture manager.
     */
    public void evict(String imageFilename) {
        Identifier id = textureCache.remove(imageFilename);
        if (id != null) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** Evict all cached textures. */
    public void evictAll() {
        for (Map.Entry<String, Identifier> entry : textureCache.entrySet()) {
            if (entry.getValue() != null) {
                Minecraft.getInstance().getTextureManager().release(entry.getValue());
            }
        }
        textureCache.clear();
    }
}