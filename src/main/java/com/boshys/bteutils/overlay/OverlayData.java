package com.boshys.bteutils.overlay;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model for image overlays rendered as warped quads on the ground.
 */
public class OverlayData {

    /**
     * A single active overlay (loaded into the world).
     */
    public static class ImageOverlay {
        /** Display name chosen by the player */
        public String displayName;
        /** Filename of the image (relative to the images folder, e.g. "mymap.png") */
        public String imageFilename;
        /** Four corners in world space: 0=NW, 1=NE, 2=SE, 3=SW */
        public Vec3[] corners;
        /** Anchor / pivot point. Moving this via commands moves the whole overlay.
         *  Moving this via arrows adjusts the pivot without moving corners. */
        public Vec3 anchor;
        /** Whether the overlay image is currently visible */
        public boolean visible;
        /** Whether the image is vertically flipped */
        public boolean flipped;
        /** Whether the edit markers (corners + anchor) are visible */
        public boolean markersVisible;
        /** Per-overlay image opacity (0.0 to 1.0) */
        public float imageOpacity;

        public ImageOverlay(String displayName, String imageFilename, Vec3 anchor, double size) {
            this.displayName = displayName;
            this.imageFilename = imageFilename;
            this.anchor = anchor;
            this.corners = new Vec3[4];
            this.corners[0] = new Vec3(anchor.x - size, anchor.y, anchor.z - size); // NW
            this.corners[1] = new Vec3(anchor.x + size, anchor.y, anchor.z - size); // NE
            this.corners[2] = new Vec3(anchor.x + size, anchor.y, anchor.z + size); // SE
            this.corners[3] = new Vec3(anchor.x - size, anchor.y, anchor.z + size); // SW
            this.visible = true;
            this.flipped = false;
            this.markersVisible = true;
            this.imageOpacity = 1.0f;
        }
    }

    /**
     * Persistent JSON data saved to disk for an overlay.
     */
    public static class SavedOverlayData {
        public String displayName;
        public String imageFilename;
        /** New format: corners[4][3] */
        public double[][] corners;
        /** New format: anchor[3] */
        public double[] anchor;
        public boolean visible;
        public boolean flipped;
        public boolean markersVisible = true;
        public float imageOpacity = 1.0f;

        /** Legacy fields (kept for backward compat) */
        public double x, y, z;
        public double size;
        public double rotation;

        public SavedOverlayData() {}

        public SavedOverlayData(ImageOverlay overlay) {
            this.displayName = overlay.displayName;
            this.imageFilename = overlay.imageFilename;
            this.corners = new double[4][3];
            for (int i = 0; i < 4; i++) {
                this.corners[i][0] = overlay.corners[i].x;
                this.corners[i][1] = overlay.corners[i].y;
                this.corners[i][2] = overlay.corners[i].z;
            }
            this.anchor = new double[] { overlay.anchor.x, overlay.anchor.y, overlay.anchor.z };
            this.visible = overlay.visible;
            this.flipped = overlay.flipped;
            this.markersVisible = overlay.markersVisible;
            this.imageOpacity = overlay.imageOpacity;
        }

        public ImageOverlay toOverlay() {
            if (corners != null && anchor != null) {
                Vec3 a = new Vec3(anchor[0], anchor[1], anchor[2]);
                Vec3[] c = new Vec3[4];
                for (int i = 0; i < 4; i++) {
                    c[i] = new Vec3(corners[i][0], corners[i][1], corners[i][2]);
                }
                ImageOverlay o = new ImageOverlay(displayName, imageFilename, a, 1.0);
                o.corners = c;
                o.anchor = a;
                o.visible = visible;
                o.flipped = flipped;
                o.markersVisible = markersVisible;
                o.imageOpacity = imageOpacity;
                return o;
            } else {
                // Legacy fallback
                Vec3 pos = new Vec3(x, y, z);
                ImageOverlay o = new ImageOverlay(displayName, imageFilename, pos, size > 0 ? size : 10.0);
                o.visible = visible;
                o.flipped = flipped;
                o.markersVisible = true;
                o.imageOpacity = 1.0f;
                return o;
            }
        }
    }

    public static String toSafeFilename(String displayName) {
        return displayName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    public static int parseCorner(String s) {
        return switch (s.toLowerCase()) {
            case "nw", "0" -> 0;
            case "ne", "1" -> 1;
            case "se", "2" -> 2;
            case "sw", "3" -> 3;
            default -> -1;
        };
    }

    public static String cornerName(int i) {
        return switch (i) {
            case 0 -> "NW";
            case 1 -> "NE";
            case 2 -> "SE";
            case 3 -> "SW";
            default -> "unknown";
        };
    }
}