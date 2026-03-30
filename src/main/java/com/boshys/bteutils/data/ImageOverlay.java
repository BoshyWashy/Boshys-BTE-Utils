/* package com.boshys.bteutils.data;

import net.minecraft.util.math.Vec3d;

import java.util.UUID;

public class ImageOverlay {
    public final String id;
    public String name;
    public Vec3d position; // Center position
    public double width;   // Width in blocks
    public double height;  // Height in blocks
    public double rotation; // Rotation in degrees (around Y axis)
    public float opacity;
    public boolean visible;
    public boolean locked; // If true, can't be moved by accident

    // Corner control points for distortion (relative to center)
    // If null, uses standard rectangle rendering
    public Vec3d cornerNW, cornerNE, cornerSW, cornerSE;

    // Texture path (relative to config/boshysbteutils/images/)
    public String imagePath;

    // Rendering mode: "flat" (on ground), "vertical" (standing up), "billboard" (always facing player)
    public String renderMode;

    // Height offset from ground (for floating images)
    public double yOffset;

    public ImageOverlay(String name, Vec3d position, String imagePath) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.position = position;
        this.imagePath = imagePath;
        this.width = 10.0;
        this.height = 10.0;
        this.rotation = 0.0;
        this.opacity = 1.0f;
        this.visible = true;
        this.locked = false;
        this.renderMode = "flat";
        this.yOffset = 0.0;
        this.cornerNW = null;
        this.cornerNE = null;
        this.cornerSW = null;
        this.cornerSE = null;
    }

    public boolean isDistorted() {
        return cornerNW != null || cornerNE != null || cornerSW != null || cornerSE != null;
    }

    public ImageOverlay copy() {
        ImageOverlay copy = new ImageOverlay(this.name + "_copy", this.position, this.imagePath);
        copy.width = this.width;
        copy.height = this.height;
        copy.rotation = this.rotation;
        copy.opacity = this.opacity;
        copy.visible = this.visible;
        copy.locked = this.locked;
        copy.renderMode = this.renderMode;
        copy.yOffset = this.yOffset;
        if (this.cornerNW != null) copy.cornerNW = this.cornerNW;
        if (this.cornerNE != null) copy.cornerNE = this.cornerNE;
        if (this.cornerSW != null) copy.cornerSW = this.cornerSW;
        if (this.cornerSE != null) copy.cornerSE = this.cornerSE;
        return copy;
    }
}
*/