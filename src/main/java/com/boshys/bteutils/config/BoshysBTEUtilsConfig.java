package com.boshys.bteutils.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

@Config(name = "boshysbteutilsconfig")
public class BoshysBTEUtilsConfig implements ConfigData {

    // TPLL Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("The command that runs with clipboard content (e.g., tpll)")
    public String commandPrefix = "tpll";

    @ConfigEntry.Gui.Tooltip
    @Comment("Format coordinates by removing spaces around commas")
    public boolean formatCoordinates = true;

    // Marker Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("Enable custom cube markers when teleporting with TPLL")
    public boolean enableMarkers = true;

    @ConfigEntry.Gui.Tooltip
    @Comment("Automatically place markers when using TPLL keybind")
    public boolean enableAutoTpllMarkers = true;

    @ConfigEntry.Gui.Tooltip
    @Comment("Marker colour in hex format (default: 0xFF0000 = red)")
    public int markerColour = 0xFF0000;

    @ConfigEntry.Gui.Tooltip
    @Comment("Marker opacity (0.0 to 1.0, default: 0.8)")
    public float markerOpacity = 0.8f;

    @ConfigEntry.Gui.Tooltip
    @Comment("Marker scale/size (default: 0.05 = small cube)")
    public float markerScale = 0.05f;

    // Line Connection Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("Enable automatic line connections between markers")
    public boolean enableAutoLineConnection = false;

    @ConfigEntry.Gui.Tooltip
    @Comment("Line colour in hex format (default: 0x00FF00 = green)")
    public int lineColour = 0x00FF00;

    @ConfigEntry.Gui.Tooltip
    @Comment("Line opacity (0.0 to 1.0, default: 0.6)")
    public float lineOpacity = 0.6f;

    @ConfigEntry.Gui.Tooltip
    @Comment("Line thickness/width (default: 0.1)")
    public float lineThickness = 0.1f;

    @Override
    public void validatePostLoad() {
        if (commandPrefix == null || commandPrefix.trim().isEmpty()) {
            commandPrefix = "tpll";
        }
        commandPrefix = commandPrefix.trim().replaceAll("\\s+", "");

        // Validate marker scale
        if (markerScale < 0.01f) markerScale = 0.01f;
        if (markerScale > 1.0f) markerScale = 1.0f;

        // Validate marker opacity
        if (markerOpacity < 0.0f) markerOpacity = 0.0f;
        if (markerOpacity > 1.0f) markerOpacity = 1.0f;

        // Validate line opacity
        if (lineOpacity < 0.0f) lineOpacity = 0.0f;
        if (lineOpacity > 1.0f) lineOpacity = 1.0f;

        // Validate line thickness
        if (lineThickness < 0.1f) lineThickness = 0.1f;
        if (lineThickness > 10.0f) lineThickness = 10.0f;
    }
}