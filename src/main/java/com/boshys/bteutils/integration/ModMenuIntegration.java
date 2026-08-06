package com.boshys.bteutils.integration;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.boshys.bteutils.storage.MarkerStorage;
import com.boshys.bteutils.data.MarkerData;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.autoconfig.AutoConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.io.File;
import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BoshysBTEUtilsConfigScreen(parent);
    }

    public static class BoshysBTEUtilsConfigScreen extends Screen {

        public void addRenderableWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
            // 26.2 FIX: Strict bounds check - widgets must be FULLY within screen.
            // Partial off-screen widgets crash the scissor system in 26.2.
            if (widget.getHeight() <= 0 || widget.getWidth() <= 0) return;
            if (widget.getY() < 0) return;
            if (widget.getY() + widget.getHeight() > this.height) return;
            super.addRenderableWidget(widget);
        }

        private final Screen parent;
        private int scrollOffset = 0;

        public BoshysBTEUtilsConfigScreen(Screen parent) {
            super(Component.translatable("text.autoconfig.boshysbteutils.title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearWidgets();
            int centerX = this.width / 2;
            int startY = this.height / 4 - scrollOffset;

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.category.keybinds").withStyle(style -> style.withBold(true)),
                    button -> this.minecraft.setScreenAndShow(new TPLLKeybindScreen(this))
            ).bounds(centerX - 150, startY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.category.markers").withStyle(style -> style.withBold(true)),
                    button -> this.minecraft.setScreenAndShow(new TPLLMarkerScreen(this))
            ).bounds(centerX - 150, startY + 30, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.category.lines").withStyle(style -> style.withBold(true)),
                    button -> this.minecraft.setScreenAndShow(new LineConnectionScreen(this))
            ).bounds(centerX - 150, startY + 60, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.category.saved").withStyle(style -> style.withBold(true)),
                    button -> this.minecraft.setScreenAndShow(new SavedMarkersScreen(this))
            ).bounds(centerX - 150, startY + 90, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.category.kml").withStyle(style -> style.withBold(true)),
                    button -> this.minecraft.setScreenAndShow(new KMLImportingScreen(this))
            ).bounds(centerX - 150, startY + 120, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.category.overlays").withStyle(style -> style.withBold(true)),
                    button -> this.minecraft.setScreenAndShow(new OverlaysScreen(this))
            ).bounds(centerX - 150, startY + 150, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.done"),
                    button -> {
                        saveConfig();
                        this.minecraft.setScreenAndShow(parent);
                    }
            ).bounds(centerX - 100, startY + 190, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                // content: 6 buttons * 30 spacing + 190 done button offset = ~220 from startY
                // Ensure we can always see at least the done button (20px tall + padding)
                int minVisible = 60; // space needed for done button at bottom
                int contentHeight = 220;
                int maxScroll = Math.max(0, contentHeight - (this.height - minVisible));
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        public void close() {
            saveConfig();
            this.minecraft.setScreenAndShow(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class TPLLKeybindScreen extends Screen {

        public void addRenderableWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
            // 26.2 FIX: Strict bounds check - widgets must be FULLY within screen.
            // Partial off-screen widgets crash the scissor system in 26.2.
            if (widget.getHeight() <= 0 || widget.getWidth() <= 0) return;
            if (widget.getY() < 0) return;
            if (widget.getY() + widget.getHeight() > this.height) return;
            super.addRenderableWidget(widget);
        }

        private final Screen parent;

        public TPLLKeybindScreen(Screen parent) {
            super(Component.translatable("gui.boshysbteutils.config.screen.keybinds"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int startY = this.height / 6;

            KeyMapping keybind = BoshysBTEUtils.tpllKeybind;
            String keyName = keybind.getTranslatedKeyMessage().getString();
            if (keyName.equalsIgnoreCase("key.keyboard.unknown")) {
                keyName = Component.translatable("gui.boshysbteutils.config.keybind.not_bound").getString();
            }

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.tpll_keybind_dynamic", keyName),
                    button -> {
                        this.minecraft.setScreenAndShow(new ControlsScreen(this, this.minecraft.options));
                    }
            ).bounds(centerX - 150, startY, 300, 20).build());

            // ── Auto WorldEdit Lines on TPLL ──────────────────────────────
            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.option.enable_auto_we_lines_tpll",
                            config.enableAutoWorldEditLinesOnTpll ? Component.translatable("gui.boshysbteutils.config.button.on") : Component.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableAutoWorldEditLinesOnTpll = !config.enableAutoWorldEditLinesOnTpll;
                        saveConfig();
                        this.minecraft.setScreenAndShow(new TPLLKeybindScreen(parent));
                    }
            ).bounds(centerX - 150, startY + 35, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.auto_we_lines_tpll.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 60, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.minecraft.setScreenAndShow(parent)
            ).bounds(centerX - 100, this.height - 40, 200, 20).build());
        }

        public void close() {
            saveConfig();
            this.minecraft.setScreenAndShow(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class TPLLMarkerScreen extends Screen {

        public void addRenderableWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
            // 26.2 FIX: Strict bounds check - widgets must be FULLY within screen.
            // Partial off-screen widgets crash the scissor system in 26.2.
            if (widget.getHeight() <= 0 || widget.getWidth() <= 0) return;
            if (widget.getY() < 0) return;
            if (widget.getY() + widget.getHeight() > this.height) return;
            super.addRenderableWidget(widget);
        }

        private final Screen parent;
        private int scrollOffset = 0;
        private EditBox hexField;
        private EditBox opacityField;
        private EditBox scaleField;
        private EditBox confirmLimitField;

        public TPLLMarkerScreen(Screen parent) {
            super(Component.translatable("gui.boshysbteutils.config.screen.markers"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearWidgets();
            int centerX = this.width / 2;
            int startY = this.height / 6 - scrollOffset;

            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.option.enable_markers",
                            config.enableMarkers ? Component.translatable("gui.boshysbteutils.config.button.on") : Component.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableMarkers = !config.enableMarkers;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, startY, 300, 20).build());

            // ── TPLL Marker Mode (4-option selector) ─────────────────────────────
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.tpll_mode.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 25, 300, 20).build());

            String modeDisabled = "[ " + (config.tpllMarkerMode == BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED ? "X" : " ") + " ] " +
                    Component.translatable("gui.boshysbteutils.config.tpll_mode.disabled").getString();
            String modeKeybindAndManual = "[ " + (config.tpllMarkerMode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_AND_MANUAL ? "X" : " ") + " ] " +
                    Component.translatable("gui.boshysbteutils.config.tpll_mode.keybind_and_manual").getString();
            String modeKeybindOnly = "[ " + (config.tpllMarkerMode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY ? "X" : " ") + " ] " +
                    Component.translatable("gui.boshysbteutils.config.tpll_mode.keybind_only").getString();
            String modeManualOnly = "[ " + (config.tpllMarkerMode == BoshysBTEUtilsConfig.TpllMarkerMode.MANUAL_ONLY ? "X" : " ") + " ] " +
                    Component.translatable("gui.boshysbteutils.config.tpll_mode.manual_only").getString();

            // Row 1: Disabled | Keybind & Manual
            this.addRenderableWidget(Button.builder(
                    Component.literal(modeDisabled),
                    button -> {
                        config.tpllMarkerMode = BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, startY + 50, 145, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal(modeKeybindAndManual),
                    button -> {
                        config.tpllMarkerMode = BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_AND_MANUAL;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX + 5, startY + 50, 145, 20).build());

            // Row 2: Keybind Only | Manual Only
            this.addRenderableWidget(Button.builder(
                    Component.literal(modeKeybindOnly),
                    button -> {
                        config.tpllMarkerMode = BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, startY + 75, 145, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal(modeManualOnly),
                    button -> {
                        config.tpllMarkerMode = BoshysBTEUtilsConfig.TpllMarkerMode.MANUAL_ONLY;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX + 5, startY + 75, 145, 20).build());

            // Mode description
            String modeDescription = switch(config.tpllMarkerMode) {
                case DISABLED -> Component.translatable("gui.boshysbteutils.config.tpll_mode.desc.disabled").getString();
                case KEYBIND_AND_MANUAL -> Component.translatable("gui.boshysbteutils.config.tpll_mode.desc.keybind_and_manual").getString();
                case KEYBIND_ONLY -> Component.translatable("gui.boshysbteutils.config.tpll_mode.desc.keybind_only").getString();
                case MANUAL_ONLY -> Component.translatable("gui.boshysbteutils.config.tpll_mode.desc.manual_only").getString();
            };
            this.addRenderableWidget(Button.builder(
                    Component.literal("§7" + modeDescription),
                    button -> {}
            ).bounds(centerX - 150, startY + 100, 300, 15).build());

            // Warning for manual TPLL detection - always visible
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.tpll_mode.warning.line1"),
                    button -> {}
            ).bounds(centerX - 150, startY + 150, 300, 15).build());
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.tpll_mode.warning.line2"),
                    button -> {}
            ).bounds(centerX - 150, startY + 165, 300, 15).build());
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.tpll_mode.warning.line3"),
                    button -> {}
            ).bounds(centerX - 150, startY + 180, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.option.enable_clear_confirmation",
                            config.enableClearConfirmation ? Component.translatable("gui.boshysbteutils.config.button.on") : Component.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableClearConfirmation = !config.enableClearConfirmation;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, startY + 125, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.confirm_limit"),
                    button -> {}
            ).bounds(centerX - 150, startY + 200, 140, 20).build());

            confirmLimitField = new EditBox(this.font, centerX + 10, startY + 200, 140, 20, Component.translatable("gui.boshysbteutils.config.label.confirm_limit"));
            confirmLimitField.setValue(String.valueOf(config.clearConfirmLimit));
            confirmLimitField.setResponder(text -> {
                try {
                    int val = Integer.parseInt(text);
                    if (val >= 1 && val <= 1000) {
                        config.clearConfirmLimit = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(confirmLimitField);

            Color colour = new Color(config.markerColour);
            String colourHex = String.format("%02X%02X%02X", colour.getRed(), colour.getGreen(), colour.getBlue());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.marker_colour"),
                    button -> {}
            ).bounds(centerX - 150, startY + 230, 140, 20).build());

            hexField = new EditBox(this.font, centerX + 10, startY + 230, 140, 20, Component.translatable("gui.boshysbteutils.config.label.marker_colour"));
            hexField.setValue(colourHex);
            hexField.setResponder(text -> {
                try {
                    if (text.length() == 6) {
                        config.markerColour = Integer.parseInt(text, 16);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(hexField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.opacity"),
                    button -> {}
            ).bounds(centerX - 150, startY + 260, 140, 20).build());

            opacityField = new EditBox(this.font, centerX + 10, startY + 260, 140, 20, Component.translatable("gui.boshysbteutils.config.label.opacity"));
            opacityField.setValue(String.format("%.2f", config.markerOpacity));
            opacityField.setResponder(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.0f && val <= 1.0f) {
                        config.markerOpacity = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(opacityField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.scale"),
                    button -> {}
            ).bounds(centerX - 150, startY + 290, 140, 20).build());

            scaleField = new EditBox(this.font, centerX + 10, startY + 290, 140, 20, Component.translatable("gui.boshysbteutils.config.label.scale"));
            scaleField.setValue(String.format("%.2f", config.markerScale));
            scaleField.setResponder(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.01f && val <= 1.0f) {
                        config.markerScale = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(scaleField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.update_design"),
                    button -> {
                        if (!config.enableMarkers) {
                            if (this.minecraft.player != null) {
                                this.minecraft.player.sendSystemMessage(Component.translatable("gui.boshysbteutils.config.error.markers_disabled"));
                            }
                            return;
                        }

                        if (BoshysBTEUtils.markers.isEmpty()) {
                            if (this.minecraft.player != null) {
                                this.minecraft.player.sendSystemMessage(Component.translatable("gui.boshysbteutils.config.error.no_markers"));
                            }
                            return;
                        }

                        boolean hasUnloadedMarkers = false;
                        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                            String origin = BoshysBTEUtils.markerOrigins.get(marker);
                            if (origin == null || origin.equals("autosave") || origin.startsWith("autosave_")) {
                                hasUnloadedMarkers = true;
                                break;
                            }
                        }

                        if (hasUnloadedMarkers) {
                            if (this.minecraft.player != null) {
                                this.minecraft.player.sendSystemMessage(Component.translatable("command.boshysbteutils.marker.update.unloaded_error"));
                            }
                            return;
                        }

                        int updatedCount = 0;

                        if (!BoshysBTEUtils.selectedMarkers.isEmpty()) {
                            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.selectedMarkers) {
                                MarkerData.updateMarkerDesign(marker);
                                updatedCount++;
                            }
                            if (this.minecraft.player != null) {
                                this.minecraft.player.sendSystemMessage(Component.translatable("command.boshysbteutils.marker.updated.selected", updatedCount));
                            }
                        } else {
                            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                                MarkerData.updateMarkerDesign(marker);
                                updatedCount++;
                            }
                            if (this.minecraft.player != null) {
                                this.minecraft.player.sendSystemMessage(Component.translatable("command.boshysbteutils.marker.updated", updatedCount));
                            }
                        }
                    }
            ).bounds(centerX - 150, startY + 325, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.clear_all"),
                    button -> {
                        MarkerData.clearAllMarkers();
                        if (this.minecraft.player != null) {
                            this.minecraft.player.sendSystemMessage(Component.translatable("gui.boshysbteutils.config.message.cleared_all"));
                        }
                    }
            ).bounds(centerX - 150, startY + 355, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.add_markers").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 395, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.add_markers.command"),
                    button -> {}
            ).bounds(centerX - 150, startY + 420, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.add_markers.keybind"),
                    button -> {}
            ).bounds(centerX - 150, startY + 438, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.clear_markers").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 486, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.clear_markers.command"),
                    button -> {}
            ).bounds(centerX - 150, startY + 511, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.clear_markers.keybind"),
                    button -> {}
            ).bounds(centerX - 150, startY + 529, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.clear_markers.button"),
                    button -> {}
            ).bounds(centerX - 150, startY + 547, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.multiselect").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 577, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.multiselect.ctrl"),
                    button -> {}
            ).bounds(centerX - 150, startY + 602, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.multiselect.mac"),
                    button -> {}
            ).bounds(centerX - 150, startY + 620, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.minecraft.setScreenAndShow(parent)
            ).bounds(centerX - 100, startY + 660, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                // Last content is at ~660, back button at ~660+20, need some padding
                int minVisible = 80; // ensure back button and some content visible
                int contentHeight = 695;
                int maxScroll = Math.max(0, contentHeight - (this.height - minVisible));
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        public void close() {
            saveConfig();
            this.minecraft.setScreenAndShow(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class LineConnectionScreen extends Screen {

        public void addRenderableWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
            // 26.2 FIX: Strict bounds check - widgets must be FULLY within screen.
            // Partial off-screen widgets crash the scissor system in 26.2.
            if (widget.getHeight() <= 0 || widget.getWidth() <= 0) return;
            if (widget.getY() < 0) return;
            if (widget.getY() + widget.getHeight() > this.height) return;
            super.addRenderableWidget(widget);
        }

        private final Screen parent;
        private int scrollOffset = 0;
        private EditBox hexField;
        private EditBox opacityField;
        private EditBox thicknessField;
        private EditBox circleThicknessField;
        private EditBox circleDensityField;

        public LineConnectionScreen(Screen parent) {
            super(Component.translatable("gui.boshysbteutils.config.screen.lines"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearWidgets();
            int centerX = this.width / 2;
            int startY = this.height / 6 - scrollOffset;

            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.option.enable_auto_line_connection",
                            config.enableAutoLineConnection ? Component.translatable("gui.boshysbteutils.config.button.on") : Component.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableAutoLineConnection = !config.enableAutoLineConnection;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, startY, 300, 20).build());

            Color colour = new Color(config.lineColour);
            String colourHex = String.format("%02X%02X%02X", colour.getRed(), colour.getGreen(), colour.getBlue());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.line_colour"),
                    button -> {}
            ).bounds(centerX - 150, startY + 30, 140, 20).build());

            hexField = new EditBox(this.font, centerX + 10, startY + 30, 140, 20, Component.translatable("gui.boshysbteutils.config.label.line_colour"));
            hexField.setValue(colourHex);
            hexField.setResponder(text -> {
                try {
                    if (text.length() == 6) {
                        config.lineColour = Integer.parseInt(text, 16);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(hexField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.line_opacity"),
                    button -> {}
            ).bounds(centerX - 150, startY + 60, 140, 20).build());

            opacityField = new EditBox(this.font, centerX + 10, startY + 60, 140, 20, Component.translatable("gui.boshysbteutils.config.label.line_opacity"));
            opacityField.setValue(String.format("%.2f", config.lineOpacity));
            opacityField.setResponder(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.0f && val <= 1.0f) {
                        config.lineOpacity = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(opacityField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.line_thickness"),
                    button -> {}
            ).bounds(centerX - 150, startY + 90, 140, 20).build());

            thicknessField = new EditBox(this.font, centerX + 10, startY + 90, 140, 20, Component.translatable("gui.boshysbteutils.config.label.line_thickness"));
            thicknessField.setValue(String.format("%.1f", config.lineThickness));
            thicknessField.setResponder(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.1f && val <= 10.0f) {
                        config.lineThickness = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(thicknessField);

            // ── Circle settings ──────────────────────────────────────────────
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.circle.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 125, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.circle_thickness"),
                    button -> {}
            ).bounds(centerX - 150, startY + 150, 140, 20).build());

            circleThicknessField = new EditBox(this.font, centerX + 10, startY + 150, 140, 20, Component.translatable("gui.boshysbteutils.config.label.circle_thickness"));
            circleThicknessField.setValue(String.format("%.2f", config.circleThickness));
            circleThicknessField.setResponder(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.01f && val <= 10.0f) {
                        config.circleThickness = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(circleThicknessField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.circle_density"),
                    button -> {}
            ).bounds(centerX - 150, startY + 180, 140, 20).build());

            circleDensityField = new EditBox(this.font, centerX + 10, startY + 180, 140, 20, Component.translatable("gui.boshysbteutils.config.label.circle_density"));
            circleDensityField.setValue(String.format("%.2f", config.circleSegmentPercent));
            circleDensityField.setResponder(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.1f && val <= 50.0f) {
                        config.circleSegmentPercent = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(circleDensityField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.circle.density.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 205, 300, 15).build());

            // ── Help sections ──────────────────────────────────────────────
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.lines.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 240, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.lines.auto"),
                    button -> {}
            ).bounds(centerX - 150, startY + 265, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.lines.manual"),
                    button -> {}
            ).bounds(centerX - 150, startY + 283, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.lines.select"),
                    button -> {}
            ).bounds(centerX - 150, startY + 301, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.lines.connect"),
                    button -> {}
            ).bounds(centerX - 150, startY + 319, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.lines.disconnect"),
                    button -> {}
            ).bounds(centerX - 150, startY + 337, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.lines.delete"),
                    button -> {}
            ).bounds(centerX - 150, startY + 355, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.keybinds.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 385, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.keybinds.right_click"),
                    button -> {}
            ).bounds(centerX - 150, startY + 410, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.keybinds.delete"),
                    button -> {}
            ).bounds(centerX - 150, startY + 428, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.help.keybinds.multiselect"),
                    button -> {}
            ).bounds(centerX - 150, startY + 446, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.minecraft.setScreenAndShow(parent)
            ).bounds(centerX - 100, startY + 490, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int minVisible = 80;
                int contentHeight = 530;
                int maxScroll = Math.max(0, contentHeight - (this.height - minVisible));
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        public void close() {
            saveConfig();
            this.minecraft.setScreenAndShow(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }
    public static class SavedMarkersScreen extends Screen {

        public void addRenderableWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
            // 26.2 FIX: Strict bounds check - widgets must be FULLY within screen.
            // Partial off-screen widgets crash the scissor system in 26.2.
            if (widget.getHeight() <= 0 || widget.getWidth() <= 0) return;
            if (widget.getY() < 0) return;
            if (widget.getY() + widget.getHeight() > this.height) return;
            super.addRenderableWidget(widget);
        }

        private final Screen parent;
        private int scrollOffset = 0;
        private EditBox pathField;
        private EditBox autosaveIntervalField;

        public SavedMarkersScreen(Screen parent) {
            super(Component.translatable("gui.boshysbteutils.config.screen.saved"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearWidgets();
            int centerX = this.width / 2;
            int startY = this.height / 6 - scrollOffset;

            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            Path currentPath = BoshysBTEUtils.INSTANCE.getMarkerStorage().getMarkersSavePath();
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.save_location").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal(currentPath.toString()),
                    button -> {}
            ).bounds(centerX - 150, startY + 25, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.custom_path"),
                    button -> {}
            ).bounds(centerX - 150, startY + 55, 300, 20).build());

            pathField = new EditBox(this.font, centerX - 150, startY + 80, 300, 20, Component.translatable("gui.boshysbteutils.config.label.custom_path"));
            pathField.setValue(config.savedMarkersFolderPath != null ? config.savedMarkersFolderPath : "");
            pathField.setMaxLength(2000);
            pathField.setResponder(text -> {
                config.savedMarkersFolderPath = text;
                if (BoshysBTEUtils.INSTANCE != null) {
                    BoshysBTEUtils.INSTANCE.getMarkerStorage().updateMarkersSavePath();
                }
                saveConfig();
            });
            this.addRenderableWidget(pathField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.autosave.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 115, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.option.enable_autosave",
                            config.enableAutosave ? Component.translatable("gui.boshysbteutils.config.button.on") : Component.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableAutosave = !config.enableAutosave;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, startY + 140, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.autosave_interval"),
                    button -> {}
            ).bounds(centerX - 150, startY + 170, 300, 20).build());

            autosaveIntervalField = new EditBox(this.font, centerX - 150, startY + 195, 300, 20, Component.translatable("gui.boshysbteutils.config.label.autosave_interval"));
            autosaveIntervalField.setValue(String.valueOf(config.autosaveIntervalMinutes));
            autosaveIntervalField.setResponder(text -> {
                try {
                    int val = Integer.parseInt(text);
                    if (val >= 0 && val <= 1440) {
                        config.autosaveIntervalMinutes = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(autosaveIntervalField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 230, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.save"),
                    button -> {}
            ).bounds(centerX - 150, startY + 255, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.save.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 273, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.update"),
                    button -> {}
            ).bounds(centerX - 150, startY + 296, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.update.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 314, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.load"),
                    button -> {}
            ).bounds(centerX - 150, startY + 337, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.load.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 355, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.hide"),
                    button -> {}
            ).bounds(centerX - 150, startY + 378, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.hide.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 396, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.delete"),
                    button -> {}
            ).bounds(centerX - 150, startY + 419, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.delete.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 437, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.merge"),
                    button -> {}
            ).bounds(centerX - 150, startY + 460, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.merge.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 478, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.move"),
                    button -> {}
            ).bounds(centerX - 150, startY + 501, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.move.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 519, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.loaded_files.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 549, 300, 20).build());

            int fileY = startY + 574;
            if (BoshysBTEUtils.INSTANCE.getMarkerStorage().getLoadedFiles().isEmpty()) {
                this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.boshysbteutils.config.loaded_files.none"),
                        button -> {}
                ).bounds(centerX - 150, fileY, 300, 15).build());
                fileY += 20;
            } else {
                for (String filename : BoshysBTEUtils.INSTANCE.getMarkerStorage().getLoadedFiles().keySet()) {
                    MarkerData.SavedMarkerFile file = BoshysBTEUtils.INSTANCE.getMarkerStorage().getLoadedFiles().get(filename);
                    this.addRenderableWidget(Button.builder(
                            Component.translatable("gui.boshysbteutils.config.loaded_files.entry", filename, file.markers.size()),
                            button -> {}
                    ).bounds(centerX - 150, fileY, 300, 15).build());
                    fileY += 18;
                }
            }

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.minecraft.setScreenAndShow(parent)
            ).bounds(centerX - 100, fileY + 20, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int minVisible = 80;
                int contentHeight = 620;
                int maxScroll = Math.max(0, contentHeight - (this.height - minVisible));
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        public void close() {
            saveConfig();
            this.minecraft.setScreenAndShow(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class KMLImportingScreen extends Screen {

        public void addRenderableWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
            // 26.2 FIX: Strict bounds check - widgets must be FULLY within screen.
            // Partial off-screen widgets crash the scissor system in 26.2.
            if (widget.getHeight() <= 0 || widget.getWidth() <= 0) return;
            if (widget.getY() < 0) return;
            if (widget.getY() + widget.getHeight() > this.height) return;
            super.addRenderableWidget(widget);
        }

        private final Screen parent;
        private int scrollOffset = 0;
        private EditBox delayField;
        private EditBox startDelayField;
        private EditBox pathField;
        private EditBox postCommandsField;
        private EditBox worldEditBlockField;
        private EditBox lockedAltitudeField;
        private EditBox altitudeOffsetField;

        public KMLImportingScreen(Screen parent) {
            super(Component.translatable("gui.boshysbteutils.config.screen.kml"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearWidgets();
            int centerX = this.width / 2;
            int startY = this.height / 6 - scrollOffset;

            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            Path currentPath = MarkerStorage.getKmlSavePath();
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.kml_folder").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal(currentPath.toString()),
                    button -> {}
            ).bounds(centerX - 150, startY + 25, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.kml_custom_path"),
                    button -> {}
            ).bounds(centerX - 150, startY + 55, 300, 20).build());

            pathField = new EditBox(this.font, centerX - 150, startY + 80, 300, 20, Component.translatable("gui.boshysbteutils.config.label.kml_custom_path"));
            pathField.setValue(config.kmlFolderPath != null ? config.kmlFolderPath : "");
            pathField.setMaxLength(2000);
            pathField.setResponder(text -> {
                config.kmlFolderPath = text;
                saveConfig();
            });
            this.addRenderableWidget(pathField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.import_settings").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 115, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.kml_delay"),
                    button -> {}
            ).bounds(centerX - 150, startY + 140, 300, 20).build());

            delayField = new EditBox(this.font, centerX - 150, startY + 165, 300, 20, Component.translatable("gui.boshysbteutils.config.label.kml_delay"));
            delayField.setValue(String.valueOf(config.kmlImportDelayTicks));
            delayField.setResponder(text -> {
                try {
                    int val = Integer.parseInt(text);
                    if (val >= 1 && val <= 100) {
                        config.kmlImportDelayTicks = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(delayField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.kml_start_delay"),
                    button -> {}
            ).bounds(centerX - 150, startY + 195, 300, 20).build());

            startDelayField = new EditBox(this.font, centerX - 150, startY + 220, 300, 20, Component.translatable("gui.boshysbteutils.config.label.kml_start_delay"));
            startDelayField.setValue(String.valueOf(config.kmlImportStartDelaySeconds));
            startDelayField.setResponder(text -> {
                try {
                    int val = Integer.parseInt(text);
                    if (val >= 0 && val <= 10) {
                        config.kmlImportStartDelaySeconds = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(startDelayField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.kml_post_commands").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 255, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.post_commands.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 275, 300, 15).build());

            postCommandsField = new EditBox(this.font, centerX - 150, startY + 295, 300, 20, Component.translatable("gui.boshysbteutils.config.label.kml_post_commands"));
            postCommandsField.setValue(config.kmlPostImportCommands != null ? config.kmlPostImportCommands : "");
            postCommandsField.setResponder(text -> {
                config.kmlPostImportCommands = text;
                saveConfig();
            });
            this.addRenderableWidget(postCommandsField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.altitude.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + 330, 300, 20).build());

            String automaticText = "[ " + (config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.AUTOMATIC ? "X" : " ") + " ] " +
                    Component.translatable("gui.boshysbteutils.config.kml.altitude.automatic").getString();
            String kmlAltitudesText = "[ " + (config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.KML_ALTITUDES ? "X" : " ") + " ] " +
                    Component.translatable("gui.boshysbteutils.config.kml.altitude.kml").getString();
            String lockedText = "[ " + (config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.LOCKED ? "X" : " ") + " ] " +
                    Component.translatable("gui.boshysbteutils.config.kml.altitude.locked").getString();

            this.addRenderableWidget(Button.builder(
                    Component.literal(automaticText),
                    button -> {
                        config.kmlAltitudeMode = BoshysBTEUtilsConfig.AltitudeMode.AUTOMATIC;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, startY + 380, 145, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal(kmlAltitudesText),
                    button -> {
                        config.kmlAltitudeMode = BoshysBTEUtilsConfig.AltitudeMode.KML_ALTITUDES;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX + 5, startY + 380, 145, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal(lockedText),
                    button -> {
                        config.kmlAltitudeMode = BoshysBTEUtilsConfig.AltitudeMode.LOCKED;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, startY + 360, 145, 20).build());

            String modeDescription = switch(config.kmlAltitudeMode) {
                case AUTOMATIC -> Component.translatable("gui.boshysbteutils.config.kml.altitude.desc.automatic").getString();
                case KML_ALTITUDES -> Component.translatable("gui.boshysbteutils.config.kml.altitude.desc.kml").getString();
                case LOCKED -> Component.translatable("gui.boshysbteutils.config.kml.altitude.desc.locked").getString();
            };
            this.addRenderableWidget(Button.builder(
                    Component.literal("§7" + modeDescription),
                    button -> {}
            ).bounds(centerX - 150, startY + 405, 300, 15).build());

            if (config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.LOCKED) {
                this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.boshysbteutils.config.label.locked_altitude"),
                        button -> {}
                ).bounds(centerX - 150, startY + 425, 140, 20).build());

                lockedAltitudeField = new EditBox(this.font, centerX + 10, startY + 425, 140, 20, Component.translatable("gui.boshysbteutils.config.label.locked_altitude"));
                lockedAltitudeField.setValue(String.valueOf(config.kmlLockedAltitudeValue));
                lockedAltitudeField.setResponder(text -> {
                    try {
                        double val = Double.parseDouble(text);
                        config.kmlLockedAltitudeValue = val;
                        saveConfig();
                    } catch (NumberFormatException e) {
                    }
                });
                this.addRenderableWidget(lockedAltitudeField);
            }

            int offsetY = config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.LOCKED ? 455 : 425;
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.altitude_offset").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY + offsetY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.altitude_offset.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + offsetY + 20, 300, 15).build());

            altitudeOffsetField = new EditBox(this.font, centerX - 150, startY + offsetY + 40, 300, 20, Component.translatable("gui.boshysbteutils.config.label.altitude_offset"));
            altitudeOffsetField.setValue(String.valueOf(config.kmlAltitudeOffset));
            altitudeOffsetField.setResponder(text -> {
                try {
                    double val = Double.parseDouble(text);
                    config.kmlAltitudeOffset = val;
                    saveConfig();
                } catch (NumberFormatException e) {
                }
            });
            this.addRenderableWidget(altitudeOffsetField);

            int worldEditY = startY + offsetY + 75;

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.worldedit.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, worldEditY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.option.enable_worldedit_lines",
                            config.enableWorldEditLines ? Component.translatable("gui.boshysbteutils.config.button.on") : Component.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableWorldEditLines = !config.enableWorldEditLines;
                        saveConfig();
                        rebuildButtons();
                    }
            ).bounds(centerX - 150, worldEditY + 25, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.worldedit.warning"),
                    button -> {}
            ).bounds(centerX - 150, worldEditY + 50, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.label.worldedit_block"),
                    button -> {}
            ).bounds(centerX - 150, worldEditY + 75, 140, 20).build());

            worldEditBlockField = new EditBox(this.font, centerX + 10, worldEditY + 75, 140, 20, Component.translatable("gui.boshysbteutils.config.label.worldedit_block"));
            worldEditBlockField.setValue(config.worldEditLineBlock);
            worldEditBlockField.setResponder(text -> {
                if (text != null && !text.trim().isEmpty()) {
                    config.worldEditLineBlock = text.trim().replaceAll("\s+", "_");
                    saveConfig();
                }
            });
            this.addRenderableWidget(worldEditBlockField);

            int cmdY = worldEditY + 110;
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.command.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, cmdY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.commands.importkml"),
                    button -> {}
            ).bounds(centerX - 150, cmdY + 25, 300, 15).build());

            int tutorialY = cmdY + 55;
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.help.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, tutorialY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.help.step1"),
                    button -> {}
            ).bounds(centerX - 150, tutorialY + 25, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.help.step2"),
                    button -> {}
            ).bounds(centerX - 150, tutorialY + 43, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.help.step3"),
                    button -> {}
            ).bounds(centerX - 150, tutorialY + 61, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.help.step4"),
                    button -> {}
            ).bounds(centerX - 150, tutorialY + 79, 300, 15).build());

            int formatsY = tutorialY + 109;
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.formats.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, formatsY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.formats.regular"),
                    button -> {}
            ).bounds(centerX - 150, formatsY + 25, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.formats.3d"),
                    button -> {}
            ).bounds(centerX - 150, formatsY + 43, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.formats.multiple"),
                    button -> {}
            ).bounds(centerX - 150, formatsY + 61, 300, 15).build());

            int howY = formatsY + 91;
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.how.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, howY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.how.coordinates"),
                    button -> {}
            ).bounds(centerX - 150, howY + 25, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.how.tpll"),
                    button -> {}
            ).bounds(centerX - 150, howY + 43, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.how.markers"),
                    button -> {}
            ).bounds(centerX - 150, howY + 61, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.how.connect"),
                    button -> {}
            ).bounds(centerX - 150, howY + 79, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.kml.how.post_commands"),
                    button -> {}
            ).bounds(centerX - 150, howY + 97, 300, 15).build());

            int backY = howY + 130;
            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.minecraft.setScreenAndShow(parent)
            ).bounds(centerX - 100, backY, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int minVisible = 80;
                int contentHeight = 1100;
                int maxScroll = Math.max(0, contentHeight - (this.height - minVisible));
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        public void close() {
            saveConfig();
            this.minecraft.setScreenAndShow(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class OverlaysScreen extends Screen {

        public void addRenderableWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
            // 26.2 FIX: Strict bounds check - widgets must be FULLY within screen.
            // Partial off-screen widgets crash the scissor system in 26.2.
            if (widget.getHeight() <= 0 || widget.getWidth() <= 0) return;
            if (widget.getY() < 0) return;
            if (widget.getY() + widget.getHeight() > this.height) return;
            super.addRenderableWidget(widget);
        }

        private final Screen parent;
        private int scrollOffset = 0;
        private EditBox renderDistanceField;
        private Button currentValueLabel;

        public OverlaysScreen(Screen parent) {
            super(Component.translatable("gui.boshysbteutils.config.screen.overlays"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearWidgets();
            int centerX = this.width / 2;
            int startY = this.height / 6 - scrollOffset;

            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.overlays.title").withStyle(style -> style.withBold(true)),
                    button -> {}
            ).bounds(centerX - 150, startY, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.overlays.render_distance.label"),
                    button -> {}
            ).bounds(centerX - 150, startY + 30, 300, 20).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.overlays.render_distance.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 55, 300, 15).build());

            // Current value label — updated dynamically without rebuilding the whole UI
            currentValueLabel = Button.builder(
                    getRenderDistanceText(config),
                    button -> {}
            ).bounds(centerX - 150, startY + 75, 300, 20).build();
            this.addRenderableWidget(currentValueLabel);

            renderDistanceField = new EditBox(this.font, centerX - 150, startY + 100, 300, 20,
                    Component.translatable("gui.boshysbteutils.config.overlays.render_distance.label"));
            renderDistanceField.setValue(String.valueOf(config.overlayRenderDistance));
            renderDistanceField.setMaxLength(3);
            renderDistanceField.setTextColor(0xFFFFFF);
            renderDistanceField.setResponder(text -> {
                try {
                    if (text == null || text.isEmpty()) {
                        // Empty is fine during typing, don't update config yet
                        return;
                    }
                    int val = Integer.parseInt(text);
                    if (val >= -1 && val <= 64) {
                        config.overlayRenderDistance = val;
                        saveConfig();
                        // Only update the label text, DON'T rebuild all buttons
                        currentValueLabel.setMessage(getRenderDistanceText(config));
                    }
                } catch (NumberFormatException e) {
                    // Invalid input (e.g. "-" while typing negative), ignore
                }
            });
            this.addRenderableWidget(renderDistanceField);

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.overlays.subdivision.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 135, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.overlays.culling.desc"),
                    button -> {}
            ).bounds(centerX - 150, startY + 155, 300, 15).build());

            this.addRenderableWidget(Button.builder(
                    Component.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.minecraft.setScreenAndShow(parent)
            ).bounds(centerX - 100, startY + 200, 200, 20).build());
        }

        private Component getRenderDistanceText(BoshysBTEUtilsConfig config) {
            if (config.overlayRenderDistance < 0) {
                return Component.translatable("gui.boshysbteutils.config.overlays.render_distance.current",
                        Component.translatable("gui.boshysbteutils.config.overlays.render_distance.simulation"));
            } else if (config.overlayRenderDistance == 0) {
                return Component.translatable("gui.boshysbteutils.config.overlays.render_distance.current",
                        Component.translatable("gui.boshysbteutils.config.overlays.render_distance.unlimited"));
            } else {
                return Component.translatable("gui.boshysbteutils.config.overlays.render_distance.current",
                        Component.literal(config.overlayRenderDistance + " chunks"));
            }
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int minVisible = 80;
                int contentHeight = 250;
                int maxScroll = Math.max(0, contentHeight - (this.height - minVisible));
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        public void close() {
            saveConfig();
            this.minecraft.setScreenAndShow(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }
}