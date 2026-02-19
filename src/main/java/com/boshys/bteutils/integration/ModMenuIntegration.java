package com.boshys.bteutils.integration;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.autoconfig.AutoConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

import java.awt.Color;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BoshysBTEUtilsConfigScreen(parent);
    }

    public static class BoshysBTEUtilsConfigScreen extends Screen {
        private final Screen parent;

        public BoshysBTEUtilsConfigScreen(Screen parent) {
            super(Text.literal("Boshys BTE Utils Config"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int startY = this.height / 4;

            // TPLL Keybind Customisation section - White and Bold
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("TPLL Keybind Customisation").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new TPLLKeybindScreen(this))
            ).dimensions(centerX - 150, startY, 300, 20).build());

            // TPLL Marker Settings section - White and Bold
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("TPLL Marker Settings").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new TPLLMarkerScreen(this))
            ).dimensions(centerX - 150, startY + 30, 300, 20).build());

            // Line Connection Settings section - White and Bold
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Line Connection Settings").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new LineConnectionScreen(this))
            ).dimensions(centerX - 150, startY + 60, 300, 20).build());

            // Saved Markers section - White and Bold
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Saved Markers").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new SavedMarkersScreen(this))
            ).dimensions(centerX - 150, startY + 90, 300, 20).build());

            // Done button
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Done"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, this.height - 40, 200, 20).build());
        }

        @Override
        public void close() {
            this.client.setScreen(parent);
        }
    }

    // TPLL Keybind Customisation Screen
    public static class TPLLKeybindScreen extends Screen {
        private final Screen parent;

        public TPLLKeybindScreen(Screen parent) {
            super(Text.literal("TPLL Keybind Customisation"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int startY = this.height / 6;

            // Keybind display and link to controls
            KeyBinding keybind = BoshysBTEUtils.tpllKeybind;
            String keyName = keybind.getBoundKeyLocalizedText().getString();
            if (keyName.equalsIgnoreCase("key.keyboard.unknown")) {
                keyName = "Not Bound";
            }

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("TPLL Keybind: " + keyName),
                    button -> {
                        this.client.setScreen(new ControlsOptionsScreen(this, this.client.options));
                    }
            ).dimensions(centerX - 150, startY, 300, 20).build());

            // Back button
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, this.height - 40, 200, 20).build());
        }

        @Override
        public void close() {
            this.client.setScreen(parent);
        }
    }

    // TPLL Marker Settings Screen
    public static class TPLLMarkerScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;
        private TextFieldWidget hexField;
        private TextFieldWidget opacityField;
        private TextFieldWidget scaleField;

        public TPLLMarkerScreen(Screen parent) {
            super(Text.literal("TPLL Marker Settings"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearChildren();
            int centerX = this.width / 2;
            int startY = this.height / 6 - scrollOffset;

            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            // Enable markers toggle
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Enable Markers: " + (config.enableMarkers ? "ON" : "OFF")),
                    button -> {
                        config.enableMarkers = !config.enableMarkers;
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY, 300, 20).build());

            // Enable automatic TPLL markers toggle
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Auto TPLL Markers: " + (config.enableAutoTpllMarkers ? "ON" : "OFF")),
                    button -> {
                        config.enableAutoTpllMarkers = !config.enableAutoTpllMarkers;
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY + 25, 300, 20).build());

            // Hex Colour input field
            Color colour = new Color(config.markerColour);
            String colourHex = String.format("%02X%02X%02X", colour.getRed(), colour.getGreen(), colour.getBlue());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Marker Colour (Hex):"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 55, 140, 20).build());

            hexField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 55, 140, 20, Text.literal("Hex"));
            hexField.setText(colourHex);
            hexField.setChangedListener(text -> {
                try {
                    if (text.length() == 6) {
                        config.markerColour = Integer.parseInt(text, 16);
                    }
                } catch (NumberFormatException e) {
                    // Invalid hex, ignore
                }
            });
            this.addDrawableChild(hexField);

            // Opacity input field
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Opacity (0.0-1.0):"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 85, 140, 20).build());

            opacityField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 85, 140, 20, Text.literal("Opacity"));
            opacityField.setText(String.format("%.2f", config.markerOpacity));
            opacityField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.0f && val <= 1.0f) {
                        config.markerOpacity = val;
                    }
                } catch (NumberFormatException e) {
                    // Invalid number, ignore
                }
            });
            this.addDrawableChild(opacityField);

            // Scale input field
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Scale (0.01-1.0):"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 115, 140, 20).build());

            scaleField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 115, 140, 20, Text.literal("Scale"));
            scaleField.setText(String.format("%.2f", config.markerScale));
            scaleField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.01f && val <= 1.0f) {
                        config.markerScale = val;
                    }
                } catch (NumberFormatException e) {
                    // Invalid number, ignore
                }
            });
            this.addDrawableChild(scaleField);

            // Update Marker Design button - NOT BOLD (as requested)
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Update Marker Design"), // Removed .styled(style -> style.withBold(true))
                    button -> {
                        if (!config.enableMarkers) {
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.literal("§cMarkers disabled in config!"), false);
                            }
                            return;
                        }

                        if (BoshysBTEUtils.markers.isEmpty()) {
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.literal("§cNo markers to update!"), false);
                            }
                            return;
                        }

                        int updatedCount = 0;

                        // If a marker is selected, only update that one
                        if (BoshysBTEUtils.selectedMarker != null) {
                            BoshysBTEUtils.updateMarkerDesign(BoshysBTEUtils.selectedMarker);
                            updatedCount = 1;
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.literal("§aUpdated selected marker's design!"), false);
                            }
                        } else {
                            // Update all markers
                            for (BoshysBTEUtils.TeleportMarker marker : BoshysBTEUtils.markers) {
                                BoshysBTEUtils.updateMarkerDesign(marker);
                                updatedCount++;
                            }
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.literal("§aUpdated " + updatedCount + " markers to current config design!"), false);
                            }
                        }
                    }
            ).dimensions(centerX - 150, startY + 150, 300, 20).build());

            // Clear markers button
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Clear All Markers"),
                    button -> {
                        BoshysBTEUtils.clearAllMarkers();
                        if (this.client.player != null) {
                            this.client.player.sendMessage(Text.literal("§aCleared all TPLL markers!"), false);
                        }
                    }
            ).dimensions(centerX - 150, startY + 180, 300, 20).build());

            // Help section - How to add markers
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("How to Add Markers:").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 220, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Use /boshys-bt-utils addMarker"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 245, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Or press your Add Marker keybind"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 263, 300, 15).build());

            // Help section - How to clear markers
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("How to Clear Markers:").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 311, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Use /boshys-bt-utils clearMarkers"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 336, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Or press your Clear Markers keybind"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 354, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Or use the button above"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 372, 300, 15).build());

            // Back button - Now part of scrollable content
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, startY + 410, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                // Max scroll calculated based on content height minus screen height
                int contentHeight = 450; // Increased for new button
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            this.client.setScreen(parent);
        }
    }

    // Line Connection Settings Screen
    public static class LineConnectionScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;
        private TextFieldWidget hexField;
        private TextFieldWidget opacityField;
        private TextFieldWidget thicknessField;

        public LineConnectionScreen(Screen parent) {
            super(Text.literal("Line Connection Settings"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearChildren();
            int centerX = this.width / 2;
            int startY = this.height / 6 - scrollOffset;

            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            // Enable auto line connection toggle
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Auto-Connect Markers: " + (config.enableAutoLineConnection ? "ON" : "OFF")),
                    button -> {
                        config.enableAutoLineConnection = !config.enableAutoLineConnection;
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY, 300, 20).build());

            // Line Colour input field
            Color colour = new Color(config.lineColour);
            String colourHex = String.format("%02X%02X%02X", colour.getRed(), colour.getGreen(), colour.getBlue());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Line Colour (Hex):"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 30, 140, 20).build());

            hexField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 30, 140, 20, Text.literal("Hex"));
            hexField.setText(colourHex);
            hexField.setChangedListener(text -> {
                try {
                    if (text.length() == 6) {
                        config.lineColour = Integer.parseInt(text, 16);
                    }
                } catch (NumberFormatException e) {
                    // Invalid hex, ignore
                }
            });
            this.addDrawableChild(hexField);

            // Line Opacity input field
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Line Opacity (0.0-1.0):"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 60, 140, 20).build());

            opacityField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 60, 140, 20, Text.literal("Opacity"));
            opacityField.setText(String.format("%.2f", config.lineOpacity));
            opacityField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.0f && val <= 1.0f) {
                        config.lineOpacity = val;
                    }
                } catch (NumberFormatException e) {
                    // Invalid number, ignore
                }
            });
            this.addDrawableChild(opacityField);

            // Line Thickness input field
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Line Thickness (0.1-10.0):"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 90, 140, 20).build());

            thicknessField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 90, 140, 20, Text.literal("Thickness"));
            thicknessField.setText(String.format("%.1f", config.lineThickness));
            thicknessField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.1f && val <= 10.0f) {
                        config.lineThickness = val;
                    }
                } catch (NumberFormatException e) {
                    // Invalid number, ignore
                }
            });
            this.addDrawableChild(thicknessField);

            // Tutorial section - How line connections work
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("How Line Connections Work:").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 130, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Auto-Connect: Links new markers automatically"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 155, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Manual: Right-click two markers to connect/disconnect"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 173, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Select Marker: Right-click a marker to select it"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 191, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Connect: Right-click another marker to connect them"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 209, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Disconnect: Right-click connected markers in order"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 227, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Delete Marker: Press Delete key with marker selected"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 245, 300, 15).build());

            // Keybind info
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Keybinds:").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 275, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Right Click: Select/Connect markers"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 300, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Delete Key: Delete selected marker"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 318, 300, 15).build());

            // Back button - Now part of scrollable content
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, startY + 360, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                // Max scroll calculated based on content height minus screen height
                int contentHeight = 400;
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            this.client.setScreen(parent);
        }
    }

    // Saved Markers Screen - NEW
    public static class SavedMarkersScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;
        private TextFieldWidget pathField;

        public SavedMarkersScreen(Screen parent) {
            super(Text.literal("Saved Markers"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearChildren();
            int centerX = this.width / 2;
            int startY = this.height / 6 - scrollOffset;

            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            // Current save location
            Path currentPath = BoshysBTEUtils.getMarkersSavePath();
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Current Save Location:").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(currentPath.toString()),
                    button -> {}
            ).dimensions(centerX - 150, startY + 25, 300, 20).build());

            // Change save path
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Custom Path (blank for default):"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 55, 300, 20).build());

            pathField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + 80, 300, 20, Text.literal("Path"));
            pathField.setText(config.savedMarkersFolderPath != null ? config.savedMarkersFolderPath : "");
            pathField.setChangedListener(text -> {
                config.savedMarkersFolderPath = text;
                // Update path immediately
                if (BoshysBTEUtils.INSTANCE != null) {
                    BoshysBTEUtils.INSTANCE.updateMarkersSavePath();
                }
            });
            this.addDrawableChild(pathField);

            // Commands help section
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Available Commands:").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 120, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("/boshys-bt-utils saveMarkers <name> [radius]"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 145, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("  Saves markers to file. Radius optional."),
                    button -> {}
            ).dimensions(centerX - 150, startY + 163, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("/boshys-bt-utils updateMarkers <name> [radius]"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 186, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("  Adds new markers to existing file."),
                    button -> {}
            ).dimensions(centerX - 150, startY + 204, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("/boshys-bt-utils load <name>"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 227, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("  Loads markers from file to world."),
                    button -> {}
            ).dimensions(centerX - 150, startY + 245, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("/boshys-bt-utils hide <name>"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 268, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("  Hides loaded markers (keeps file)."),
                    button -> {}
            ).dimensions(centerX - 150, startY + 286, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("/boshys-bt-utils delete <name>"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 309, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("  Permanently deletes the file."),
                    button -> {}
            ).dimensions(centerX - 150, startY + 327, 300, 15).build());

            // Loaded files section
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Currently Loaded Files:").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 360, 300, 20).build());

            int fileY = startY + 385;
            if (BoshysBTEUtils.loadedFiles.isEmpty()) {
                this.addDrawableChild(ButtonWidget.builder(
                        Text.literal("No files currently loaded"),
                        button -> {}
                ).dimensions(centerX - 150, fileY, 300, 15).build());
                fileY += 20;
            } else {
                for (String filename : BoshysBTEUtils.loadedFiles.keySet()) {
                    BoshysBTEUtils.SavedMarkerFile file = BoshysBTEUtils.loadedFiles.get(filename);
                    this.addDrawableChild(ButtonWidget.builder(
                            Text.literal("• " + filename + " (" + file.markers.size() + " markers)"),
                            button -> {}
                    ).dimensions(centerX - 150, fileY, 300, 15).build());
                    fileY += 18;
                }
            }

            // Back button
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, fileY + 20, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                // Calculate max scroll based on content
                int contentHeight = 500;
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            this.client.setScreen(parent);
        }
    }
}
