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

    // Clear Confirmation Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("Enable confirmation prompt when clearing many markers")
    public boolean enableClearConfirmation = true;

    @ConfigEntry.Gui.Tooltip
    @Comment("Maximum markers before requiring confirmation (default: 3)")
    public int clearConfirmLimit = 3;

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

    // Saved Markers Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("Custom folder path for saved marker files (leave empty for default: config/boshysbteutils/markers)")
    public String savedMarkersFolderPath = "";

    // Autosave Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("Enable autosave functionality")
    public boolean enableAutosave = true;

    @ConfigEntry.Gui.Tooltip
    @Comment("Autosave interval in minutes (0 or empty = only on disconnect)")
    public int autosaveIntervalMinutes = 0;

    // KML Import Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("Custom folder path for KML files (leave empty to use same as marker save location)")
    public String kmlFolderPath = "";

    @ConfigEntry.Gui.Tooltip
    @Comment("Delay between KML import operations in ticks (default: 20, min: 1, max: 100)")
    public int kmlImportDelayTicks = 20;

    @ConfigEntry.Gui.Tooltip
    @Comment("Delay before starting KML import in seconds (default: 1, min: 0, max: 10)")
    public int kmlImportStartDelaySeconds = 1;

    @ConfigEntry.Gui.Tooltip
    @Comment("Commands to run after each TPLL during KML import (semicolon separated, e.g., //pos1;//pos2)")
    public String kmlPostImportCommands = "";

    // Altitude Mode Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("Altitude mode for KML imports: AUTOMATIC, KML_ALTITUDES, or LOCKED")
    public AltitudeMode kmlAltitudeMode = AltitudeMode.AUTOMATIC;

    @ConfigEntry.Gui.Tooltip
    @Comment("Locked altitude value when using LOCKED altitude mode (can be any number, positive or negative)")
    public double kmlLockedAltitudeValue = 64.0;

    @ConfigEntry.Gui.Tooltip
    @Comment("Altitude offset added to all KML points (positive or negative)")
    public double kmlAltitudeOffset = 0.0;

    // WorldEdit Lines Settings
    @ConfigEntry.Gui.Tooltip
    @Comment("Enable automatic WorldEdit line creation during KML import")
    public boolean enableWorldEditLines = false;

    @ConfigEntry.Gui.Tooltip
    @Comment("Block to use for WorldEdit lines (default: diamond_block)")
    public String worldEditLineBlock = "diamond_block";

    /**
     * Altitude mode enum for KML imports
     */
    public enum AltitudeMode {
        AUTOMATIC,      // No altitude argument, places at highest non-air block
        KML_ALTITUDES,  // Uses altitude from KML file
        LOCKED          // Uses fixed altitude value
    }

    @Override
    public void validatePostLoad() {
        // Ensure commandPrefix is never null or empty
        if (commandPrefix == null || commandPrefix.trim().isEmpty()) {
            commandPrefix = "tpll";
        }
        commandPrefix = commandPrefix.trim().replaceAll("\s+", "");

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

        // Validate clear confirmation limit
        if (clearConfirmLimit < 1) clearConfirmLimit = 1;
        if (clearConfirmLimit > 1000) clearConfirmLimit = 1000;

        // Validate autosave interval
        if (autosaveIntervalMinutes < 0) autosaveIntervalMinutes = 0;
        if (autosaveIntervalMinutes > 1440) autosaveIntervalMinutes = 1440; // Max 24 hours

        // Validate KML import delay
        if (kmlImportDelayTicks < 1) kmlImportDelayTicks = 1;
        if (kmlImportDelayTicks > 100) kmlImportDelayTicks = 100;

        // Validate KML start delay
        if (kmlImportStartDelaySeconds < 0) kmlImportStartDelaySeconds = 0;
        if (kmlImportStartDelaySeconds > 10) kmlImportStartDelaySeconds = 10;

        // Validate post-import commands
        if (kmlPostImportCommands == null) {
            kmlPostImportCommands = "";
        }

        // Validate altitude mode
        if (kmlAltitudeMode == null) {
            kmlAltitudeMode = AltitudeMode.AUTOMATIC;
        }

        // Validate WorldEdit line block
        if (worldEditLineBlock == null || worldEditLineBlock.trim().isEmpty()) {
            worldEditLineBlock = "diamond_block";
        }
        worldEditLineBlock = worldEditLineBlock.trim().replaceAll("\s+", "_");
    }
}