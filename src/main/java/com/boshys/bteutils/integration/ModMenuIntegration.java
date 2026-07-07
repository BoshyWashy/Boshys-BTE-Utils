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

@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BoshysBTEUtilsConfigScreen(parent);
    }

    public static class BoshysBTEUtilsConfigScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;

        public BoshysBTEUtilsConfigScreen(Screen parent) {
            super(Text.translatable("text.autoconfig.boshysbteutils.title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            rebuildButtons();
        }

        private void rebuildButtons() {
            this.clearChildren();
            int centerX = this.width / 2;
            int startY = this.height / 4 - scrollOffset;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.category.keybinds").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new TPLLKeybindScreen(this))
            ).dimensions(centerX - 150, startY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.category.markers").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new TPLLMarkerScreen(this))
            ).dimensions(centerX - 150, startY + 30, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.category.lines").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new LineConnectionScreen(this))
            ).dimensions(centerX - 150, startY + 60, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.category.saved").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new SavedMarkersScreen(this))
            ).dimensions(centerX - 150, startY + 90, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.category.kml").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new KMLImportingScreen(this))
            ).dimensions(centerX - 150, startY + 120, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.category.overlays").styled(style -> style.withBold(true)),
                    button -> this.client.setScreen(new OverlaysScreen(this))
            ).dimensions(centerX - 150, startY + 150, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.done"),
                    button -> {
                        saveConfig();
                        this.client.setScreen(parent);
                    }
            ).dimensions(centerX - 100, startY + 190, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int contentHeight = 220;
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            saveConfig();
            this.client.setScreen(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class TPLLKeybindScreen extends Screen {
        private final Screen parent;

        public TPLLKeybindScreen(Screen parent) {
            super(Text.translatable("gui.boshysbteutils.config.screen.keybinds"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int startY = this.height / 6;

            KeyBinding keybind = BoshysBTEUtils.tpllKeybind;
            String keyName = keybind.getBoundKeyLocalizedText().getString();
            if (keyName.equalsIgnoreCase("key.keyboard.unknown")) {
                keyName = Text.translatable("gui.boshysbteutils.config.keybind.not_bound").getString();
            }

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.tpll_keybind_dynamic", keyName),
                    button -> {
                        this.client.setScreen(new ControlsOptionsScreen(this, this.client.options));
                    }
            ).dimensions(centerX - 150, startY, 300, 20).build());

            // ── Auto WorldEdit Lines on TPLL ──────────────────────────────
            BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.option.enable_auto_we_lines_tpll",
                            config.enableAutoWorldEditLinesOnTpll ? Text.translatable("gui.boshysbteutils.config.button.on") : Text.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableAutoWorldEditLinesOnTpll = !config.enableAutoWorldEditLinesOnTpll;
                        saveConfig();
                        this.client.setScreen(new TPLLKeybindScreen(parent));
                    }
            ).dimensions(centerX - 150, startY + 35, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.auto_we_lines_tpll.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 60, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, this.height - 40, 200, 20).build());
        }

        @Override
        public void close() {
            saveConfig();
            this.client.setScreen(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class TPLLMarkerScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;
        private TextFieldWidget hexField;
        private TextFieldWidget opacityField;
        private TextFieldWidget scaleField;
        private TextFieldWidget confirmLimitField;

        public TPLLMarkerScreen(Screen parent) {
            super(Text.translatable("gui.boshysbteutils.config.screen.markers"));
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

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.option.enable_markers",
                            config.enableMarkers ? Text.translatable("gui.boshysbteutils.config.button.on") : Text.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableMarkers = !config.enableMarkers;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY, 300, 20).build());

            // ── TPLL Marker Mode (4-option selector) ─────────────────────────────
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.tpll_mode.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 25, 300, 20).build());

            String modeDisabled = "[ " + (config.tpllMarkerMode == BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED ? "X" : " ") + " ] " +
                    Text.translatable("gui.boshysbteutils.config.tpll_mode.disabled").getString();
            String modeKeybindAndManual = "[ " + (config.tpllMarkerMode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_AND_MANUAL ? "X" : " ") + " ] " +
                    Text.translatable("gui.boshysbteutils.config.tpll_mode.keybind_and_manual").getString();
            String modeKeybindOnly = "[ " + (config.tpllMarkerMode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY ? "X" : " ") + " ] " +
                    Text.translatable("gui.boshysbteutils.config.tpll_mode.keybind_only").getString();
            String modeManualOnly = "[ " + (config.tpllMarkerMode == BoshysBTEUtilsConfig.TpllMarkerMode.MANUAL_ONLY ? "X" : " ") + " ] " +
                    Text.translatable("gui.boshysbteutils.config.tpll_mode.manual_only").getString();

            // Row 1: Disabled | Keybind & Manual
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(modeDisabled),
                    button -> {
                        config.tpllMarkerMode = BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY + 50, 145, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(modeKeybindAndManual),
                    button -> {
                        config.tpllMarkerMode = BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_AND_MANUAL;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX + 5, startY + 50, 145, 20).build());

            // Row 2: Keybind Only | Manual Only
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(modeKeybindOnly),
                    button -> {
                        config.tpllMarkerMode = BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY + 75, 145, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(modeManualOnly),
                    button -> {
                        config.tpllMarkerMode = BoshysBTEUtilsConfig.TpllMarkerMode.MANUAL_ONLY;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX + 5, startY + 75, 145, 20).build());

            // Mode description
            String modeDescription = switch(config.tpllMarkerMode) {
                case DISABLED -> Text.translatable("gui.boshysbteutils.config.tpll_mode.desc.disabled").getString();
                case KEYBIND_AND_MANUAL -> Text.translatable("gui.boshysbteutils.config.tpll_mode.desc.keybind_and_manual").getString();
                case KEYBIND_ONLY -> Text.translatable("gui.boshysbteutils.config.tpll_mode.desc.keybind_only").getString();
                case MANUAL_ONLY -> Text.translatable("gui.boshysbteutils.config.tpll_mode.desc.manual_only").getString();
            };
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§7" + modeDescription),
                    button -> {}
            ).dimensions(centerX - 150, startY + 100, 300, 15).build());

            // Warning for manual TPLL detection - always visible
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.tpll_mode.warning.line1"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 150, 300, 15).build());
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.tpll_mode.warning.line2"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 165, 300, 15).build());
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.tpll_mode.warning.line3"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 180, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.option.enable_clear_confirmation",
                            config.enableClearConfirmation ? Text.translatable("gui.boshysbteutils.config.button.on") : Text.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableClearConfirmation = !config.enableClearConfirmation;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY + 125, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.confirm_limit"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 200, 140, 20).build());

            confirmLimitField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 200, 140, 20, Text.translatable("gui.boshysbteutils.config.label.confirm_limit"));
            confirmLimitField.setText(String.valueOf(config.clearConfirmLimit));
            confirmLimitField.setChangedListener(text -> {
                try {
                    int val = Integer.parseInt(text);
                    if (val >= 1 && val <= 1000) {
                        config.clearConfirmLimit = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(confirmLimitField);

            Color colour = new Color(config.markerColour);
            String colourHex = String.format("%02X%02X%02X", colour.getRed(), colour.getGreen(), colour.getBlue());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.marker_colour"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 230, 140, 20).build());

            hexField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 230, 140, 20, Text.translatable("gui.boshysbteutils.config.label.marker_colour"));
            hexField.setText(colourHex);
            hexField.setChangedListener(text -> {
                try {
                    if (text.length() == 6) {
                        config.markerColour = Integer.parseInt(text, 16);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(hexField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.opacity"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 260, 140, 20).build());

            opacityField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 260, 140, 20, Text.translatable("gui.boshysbteutils.config.label.opacity"));
            opacityField.setText(String.format("%.2f", config.markerOpacity));
            opacityField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.0f && val <= 1.0f) {
                        config.markerOpacity = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(opacityField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.scale"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 290, 140, 20).build());

            scaleField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 290, 140, 20, Text.translatable("gui.boshysbteutils.config.label.scale"));
            scaleField.setText(String.format("%.2f", config.markerScale));
            scaleField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.01f && val <= 1.0f) {
                        config.markerScale = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(scaleField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.update_design"),
                    button -> {
                        if (!config.enableMarkers) {
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.translatable("gui.boshysbteutils.config.error.markers_disabled"), false);
                            }
                            return;
                        }

                        if (BoshysBTEUtils.markers.isEmpty()) {
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.translatable("gui.boshysbteutils.config.error.no_markers"), false);
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
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.translatable("command.boshysbteutils.marker.update.unloaded_error"), false);
                            }
                            return;
                        }

                        int updatedCount = 0;

                        if (!BoshysBTEUtils.selectedMarkers.isEmpty()) {
                            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.selectedMarkers) {
                                MarkerData.updateMarkerDesign(marker);
                                updatedCount++;
                            }
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.translatable("command.boshysbteutils.marker.updated.selected", updatedCount), false);
                            }
                        } else {
                            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                                MarkerData.updateMarkerDesign(marker);
                                updatedCount++;
                            }
                            if (this.client.player != null) {
                                this.client.player.sendMessage(Text.translatable("command.boshysbteutils.marker.updated", updatedCount), false);
                            }
                        }
                    }
            ).dimensions(centerX - 150, startY + 325, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.clear_all"),
                    button -> {
                        MarkerData.clearAllMarkers();
                        if (this.client.player != null) {
                            this.client.player.sendMessage(Text.translatable("gui.boshysbteutils.config.message.cleared_all"), false);
                        }
                    }
            ).dimensions(centerX - 150, startY + 355, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.add_markers").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 395, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.add_markers.command"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 420, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.add_markers.keybind"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 438, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.clear_markers").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 486, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.clear_markers.command"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 511, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.clear_markers.keybind"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 529, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.clear_markers.button"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 547, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.multiselect").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 577, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.multiselect.ctrl"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 602, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.multiselect.mac"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 620, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, startY + 660, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int contentHeight = 695;
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            saveConfig();
            this.client.setScreen(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class LineConnectionScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;
        private TextFieldWidget hexField;
        private TextFieldWidget opacityField;
        private TextFieldWidget thicknessField;
        private TextFieldWidget circleThicknessField;
        private TextFieldWidget circleDensityField;

        public LineConnectionScreen(Screen parent) {
            super(Text.translatable("gui.boshysbteutils.config.screen.lines"));
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

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.option.enable_auto_line_connection",
                            config.enableAutoLineConnection ? Text.translatable("gui.boshysbteutils.config.button.on") : Text.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableAutoLineConnection = !config.enableAutoLineConnection;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY, 300, 20).build());

            Color colour = new Color(config.lineColour);
            String colourHex = String.format("%02X%02X%02X", colour.getRed(), colour.getGreen(), colour.getBlue());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.line_colour"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 30, 140, 20).build());

            hexField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 30, 140, 20, Text.translatable("gui.boshysbteutils.config.label.line_colour"));
            hexField.setText(colourHex);
            hexField.setChangedListener(text -> {
                try {
                    if (text.length() == 6) {
                        config.lineColour = Integer.parseInt(text, 16);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(hexField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.line_opacity"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 60, 140, 20).build());

            opacityField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 60, 140, 20, Text.translatable("gui.boshysbteutils.config.label.line_opacity"));
            opacityField.setText(String.format("%.2f", config.lineOpacity));
            opacityField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.0f && val <= 1.0f) {
                        config.lineOpacity = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(opacityField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.line_thickness"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 90, 140, 20).build());

            thicknessField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 90, 140, 20, Text.translatable("gui.boshysbteutils.config.label.line_thickness"));
            thicknessField.setText(String.format("%.1f", config.lineThickness));
            thicknessField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.1f && val <= 10.0f) {
                        config.lineThickness = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(thicknessField);

            // ── Circle settings ──────────────────────────────────────────────
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.circle.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 125, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.circle_thickness"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 150, 140, 20).build());

            circleThicknessField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 150, 140, 20, Text.translatable("gui.boshysbteutils.config.label.circle_thickness"));
            circleThicknessField.setText(String.format("%.2f", config.circleThickness));
            circleThicknessField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.01f && val <= 10.0f) {
                        config.circleThickness = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(circleThicknessField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.circle_density"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 180, 140, 20).build());

            circleDensityField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 180, 140, 20, Text.translatable("gui.boshysbteutils.config.label.circle_density"));
            circleDensityField.setText(String.format("%.2f", config.circleSegmentPercent));
            circleDensityField.setChangedListener(text -> {
                try {
                    float val = Float.parseFloat(text);
                    if (val >= 0.1f && val <= 50.0f) {
                        config.circleSegmentPercent = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(circleDensityField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.circle.density.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 205, 300, 15).build());

            // ── Help sections ──────────────────────────────────────────────
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.lines.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 240, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.lines.auto"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 265, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.lines.manual"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 283, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.lines.select"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 301, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.lines.connect"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 319, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.lines.disconnect"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 337, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.lines.delete"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 355, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.keybinds.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 385, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.keybinds.right_click"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 410, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.keybinds.delete"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 428, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.help.keybinds.multiselect"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 446, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, startY + 490, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int contentHeight = 530;
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            saveConfig();
            this.client.setScreen(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }
    public static class SavedMarkersScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;
        private TextFieldWidget pathField;
        private TextFieldWidget autosaveIntervalField;

        public SavedMarkersScreen(Screen parent) {
            super(Text.translatable("gui.boshysbteutils.config.screen.saved"));
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

            Path currentPath = BoshysBTEUtils.INSTANCE.getMarkerStorage().getMarkersSavePath();
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.save_location").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(currentPath.toString()),
                    button -> {}
            ).dimensions(centerX - 150, startY + 25, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.custom_path"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 55, 300, 20).build());

            pathField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + 80, 300, 20, Text.translatable("gui.boshysbteutils.config.label.custom_path"));
            pathField.setText(config.savedMarkersFolderPath != null ? config.savedMarkersFolderPath : "");
            pathField.setMaxLength(2000);
            pathField.setChangedListener(text -> {
                config.savedMarkersFolderPath = text;
                if (BoshysBTEUtils.INSTANCE != null) {
                    BoshysBTEUtils.INSTANCE.getMarkerStorage().updateMarkersSavePath();
                }
                saveConfig();
            });
            this.addDrawableChild(pathField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.autosave.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 115, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.option.enable_autosave",
                            config.enableAutosave ? Text.translatable("gui.boshysbteutils.config.button.on") : Text.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableAutosave = !config.enableAutosave;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY + 140, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.autosave_interval"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 170, 300, 20).build());

            autosaveIntervalField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + 195, 300, 20, Text.translatable("gui.boshysbteutils.config.label.autosave_interval"));
            autosaveIntervalField.setText(String.valueOf(config.autosaveIntervalMinutes));
            autosaveIntervalField.setChangedListener(text -> {
                try {
                    int val = Integer.parseInt(text);
                    if (val >= 0 && val <= 1440) {
                        config.autosaveIntervalMinutes = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(autosaveIntervalField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 230, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.save"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 255, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.save.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 273, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.update"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 296, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.update.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 314, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.load"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 337, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.load.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 355, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.hide"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 378, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.hide.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 396, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.delete"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 419, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.delete.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 437, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.merge"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 460, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.merge.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 478, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.move"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 501, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.move.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 519, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.loaded_files.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 549, 300, 20).build());

            int fileY = startY + 574;
            if (BoshysBTEUtils.INSTANCE.getMarkerStorage().getLoadedFiles().isEmpty()) {
                this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.boshysbteutils.config.loaded_files.none"),
                        button -> {}
                ).dimensions(centerX - 150, fileY, 300, 15).build());
                fileY += 20;
            } else {
                for (String filename : BoshysBTEUtils.INSTANCE.getMarkerStorage().getLoadedFiles().keySet()) {
                    MarkerData.SavedMarkerFile file = BoshysBTEUtils.INSTANCE.getMarkerStorage().getLoadedFiles().get(filename);
                    this.addDrawableChild(ButtonWidget.builder(
                            Text.translatable("gui.boshysbteutils.config.loaded_files.entry", filename, file.markers.size()),
                            button -> {}
                    ).dimensions(centerX - 150, fileY, 300, 15).build());
                    fileY += 18;
                }
            }

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, fileY + 20, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int contentHeight = 620;
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            saveConfig();
            this.client.setScreen(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class KMLImportingScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;
        private TextFieldWidget delayField;
        private TextFieldWidget startDelayField;
        private TextFieldWidget pathField;
        private TextFieldWidget postCommandsField;
        private TextFieldWidget worldEditBlockField;
        private TextFieldWidget lockedAltitudeField;
        private TextFieldWidget altitudeOffsetField;

        public KMLImportingScreen(Screen parent) {
            super(Text.translatable("gui.boshysbteutils.config.screen.kml"));
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

            Path currentPath = MarkerStorage.getKmlSavePath();
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.kml_folder").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(currentPath.toString()),
                    button -> {}
            ).dimensions(centerX - 150, startY + 25, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.kml_custom_path"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 55, 300, 20).build());

            pathField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + 80, 300, 20, Text.translatable("gui.boshysbteutils.config.label.kml_custom_path"));
            pathField.setText(config.kmlFolderPath != null ? config.kmlFolderPath : "");
            pathField.setMaxLength(2000);
            pathField.setChangedListener(text -> {
                config.kmlFolderPath = text;
                saveConfig();
            });
            this.addDrawableChild(pathField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.import_settings").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 115, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.kml_delay"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 140, 300, 20).build());

            delayField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + 165, 300, 20, Text.translatable("gui.boshysbteutils.config.label.kml_delay"));
            delayField.setText(String.valueOf(config.kmlImportDelayTicks));
            delayField.setChangedListener(text -> {
                try {
                    int val = Integer.parseInt(text);
                    if (val >= 1 && val <= 100) {
                        config.kmlImportDelayTicks = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(delayField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.kml_start_delay"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 195, 300, 20).build());

            startDelayField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + 220, 300, 20, Text.translatable("gui.boshysbteutils.config.label.kml_start_delay"));
            startDelayField.setText(String.valueOf(config.kmlImportStartDelaySeconds));
            startDelayField.setChangedListener(text -> {
                try {
                    int val = Integer.parseInt(text);
                    if (val >= 0 && val <= 10) {
                        config.kmlImportStartDelaySeconds = val;
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(startDelayField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.kml_post_commands").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 255, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.post_commands.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 275, 300, 15).build());

            postCommandsField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + 295, 300, 20, Text.translatable("gui.boshysbteutils.config.label.kml_post_commands"));
            postCommandsField.setText(config.kmlPostImportCommands != null ? config.kmlPostImportCommands : "");
            postCommandsField.setChangedListener(text -> {
                config.kmlPostImportCommands = text;
                saveConfig();
            });
            this.addDrawableChild(postCommandsField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.altitude.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + 330, 300, 20).build());

            String automaticText = "[ " + (config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.AUTOMATIC ? "X" : " ") + " ] " +
                    Text.translatable("gui.boshysbteutils.config.kml.altitude.automatic").getString();
            String kmlAltitudesText = "[ " + (config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.KML_ALTITUDES ? "X" : " ") + " ] " +
                    Text.translatable("gui.boshysbteutils.config.kml.altitude.kml").getString();
            String lockedText = "[ " + (config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.LOCKED ? "X" : " ") + " ] " +
                    Text.translatable("gui.boshysbteutils.config.kml.altitude.locked").getString();

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(automaticText),
                    button -> {
                        config.kmlAltitudeMode = BoshysBTEUtilsConfig.AltitudeMode.AUTOMATIC;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY + 355, 145, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(kmlAltitudesText),
                    button -> {
                        config.kmlAltitudeMode = BoshysBTEUtilsConfig.AltitudeMode.KML_ALTITUDES;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX + 5, startY + 355, 145, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(lockedText),
                    button -> {
                        config.kmlAltitudeMode = BoshysBTEUtilsConfig.AltitudeMode.LOCKED;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, startY + 380, 145, 20).build());

            String modeDescription = switch(config.kmlAltitudeMode) {
                case AUTOMATIC -> Text.translatable("gui.boshysbteutils.config.kml.altitude.desc.automatic").getString();
                case KML_ALTITUDES -> Text.translatable("gui.boshysbteutils.config.kml.altitude.desc.kml").getString();
                case LOCKED -> Text.translatable("gui.boshysbteutils.config.kml.altitude.desc.locked").getString();
            };
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§7" + modeDescription),
                    button -> {}
            ).dimensions(centerX - 150, startY + 405, 300, 15).build());

            if (config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.LOCKED) {
                this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.boshysbteutils.config.label.locked_altitude"),
                        button -> {}
                ).dimensions(centerX - 150, startY + 425, 140, 20).build());

                lockedAltitudeField = new TextFieldWidget(this.textRenderer, centerX + 10, startY + 425, 140, 20, Text.translatable("gui.boshysbteutils.config.label.locked_altitude"));
                lockedAltitudeField.setText(String.valueOf(config.kmlLockedAltitudeValue));
                lockedAltitudeField.setChangedListener(text -> {
                    try {
                        double val = Double.parseDouble(text);
                        config.kmlLockedAltitudeValue = val;
                        saveConfig();
                    } catch (NumberFormatException e) {
                    }
                });
                this.addDrawableChild(lockedAltitudeField);
            }

            int offsetY = config.kmlAltitudeMode == BoshysBTEUtilsConfig.AltitudeMode.LOCKED ? 455 : 425;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.altitude_offset").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY + offsetY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.altitude_offset.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + offsetY + 20, 300, 15).build());

            altitudeOffsetField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + offsetY + 40, 300, 20, Text.translatable("gui.boshysbteutils.config.label.altitude_offset"));
            altitudeOffsetField.setText(String.valueOf(config.kmlAltitudeOffset));
            altitudeOffsetField.setChangedListener(text -> {
                try {
                    double val = Double.parseDouble(text);
                    config.kmlAltitudeOffset = val;
                    saveConfig();
                } catch (NumberFormatException e) {
                }
            });
            this.addDrawableChild(altitudeOffsetField);

            int worldEditY = startY + offsetY + 75;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.worldedit.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, worldEditY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.option.enable_worldedit_lines",
                            config.enableWorldEditLines ? Text.translatable("gui.boshysbteutils.config.button.on") : Text.translatable("gui.boshysbteutils.config.button.off")),
                    button -> {
                        config.enableWorldEditLines = !config.enableWorldEditLines;
                        saveConfig();
                        rebuildButtons();
                    }
            ).dimensions(centerX - 150, worldEditY + 25, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.worldedit.warning"),
                    button -> {}
            ).dimensions(centerX - 150, worldEditY + 50, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.label.worldedit_block"),
                    button -> {}
            ).dimensions(centerX - 150, worldEditY + 75, 140, 20).build());

            worldEditBlockField = new TextFieldWidget(this.textRenderer, centerX + 10, worldEditY + 75, 140, 20, Text.translatable("gui.boshysbteutils.config.label.worldedit_block"));
            worldEditBlockField.setText(config.worldEditLineBlock);
            worldEditBlockField.setChangedListener(text -> {
                if (text != null && !text.trim().isEmpty()) {
                    config.worldEditLineBlock = text.trim().replaceAll("\s+", "_");
                    saveConfig();
                }
            });
            this.addDrawableChild(worldEditBlockField);

            int cmdY = worldEditY + 110;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.command.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, cmdY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.commands.importkml"),
                    button -> {}
            ).dimensions(centerX - 150, cmdY + 25, 300, 15).build());

            int tutorialY = cmdY + 55;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.help.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, tutorialY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.help.step1"),
                    button -> {}
            ).dimensions(centerX - 150, tutorialY + 25, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.help.step2"),
                    button -> {}
            ).dimensions(centerX - 150, tutorialY + 43, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.help.step3"),
                    button -> {}
            ).dimensions(centerX - 150, tutorialY + 61, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.help.step4"),
                    button -> {}
            ).dimensions(centerX - 150, tutorialY + 79, 300, 15).build());

            int formatsY = tutorialY + 109;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.formats.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, formatsY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.formats.regular"),
                    button -> {}
            ).dimensions(centerX - 150, formatsY + 25, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.formats.3d"),
                    button -> {}
            ).dimensions(centerX - 150, formatsY + 43, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.formats.multiple"),
                    button -> {}
            ).dimensions(centerX - 150, formatsY + 61, 300, 15).build());

            int howY = formatsY + 91;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.how.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, howY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.how.coordinates"),
                    button -> {}
            ).dimensions(centerX - 150, howY + 25, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.how.tpll"),
                    button -> {}
            ).dimensions(centerX - 150, howY + 43, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.how.markers"),
                    button -> {}
            ).dimensions(centerX - 150, howY + 61, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.how.connect"),
                    button -> {}
            ).dimensions(centerX - 150, howY + 79, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.kml.how.post_commands"),
                    button -> {}
            ).dimensions(centerX - 150, howY + 97, 300, 15).build());

            int backY = howY + 130;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, backY, 200, 20).build());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int contentHeight = 1100;
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            saveConfig();
            this.client.setScreen(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }

    public static class OverlaysScreen extends Screen {
        private final Screen parent;
        private int scrollOffset = 0;
        private TextFieldWidget renderDistanceField;
        private ButtonWidget currentValueLabel;

        public OverlaysScreen(Screen parent) {
            super(Text.translatable("gui.boshysbteutils.config.screen.overlays"));
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

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.overlays.title").styled(style -> style.withBold(true)),
                    button -> {}
            ).dimensions(centerX - 150, startY, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.overlays.render_distance.label"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 30, 300, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.overlays.render_distance.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 55, 300, 15).build());

            // Current value label — updated dynamically without rebuilding the whole UI
            currentValueLabel = ButtonWidget.builder(
                    getRenderDistanceText(config),
                    button -> {}
            ).dimensions(centerX - 150, startY + 75, 300, 20).build();
            this.addDrawableChild(currentValueLabel);

            renderDistanceField = new TextFieldWidget(this.textRenderer, centerX - 150, startY + 100, 300, 20,
                    Text.translatable("gui.boshysbteutils.config.overlays.render_distance.label"));
            renderDistanceField.setText(String.valueOf(config.overlayRenderDistance));
            renderDistanceField.setMaxLength(3);
            renderDistanceField.setEditableColor(0xFFFFFF);
            renderDistanceField.setChangedListener(text -> {
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
            this.addDrawableChild(renderDistanceField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.overlays.subdivision.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 135, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.overlays.culling.desc"),
                    button -> {}
            ).dimensions(centerX - 150, startY + 155, 300, 15).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.boshysbteutils.config.button.back"),
                    button -> this.client.setScreen(parent)
            ).dimensions(centerX - 100, startY + 200, 200, 20).build());
        }

        private Text getRenderDistanceText(BoshysBTEUtilsConfig config) {
            if (config.overlayRenderDistance < 0) {
                return Text.translatable("gui.boshysbteutils.config.overlays.render_distance.current",
                        Text.translatable("gui.boshysbteutils.config.overlays.render_distance.simulation"));
            } else if (config.overlayRenderDistance == 0) {
                return Text.translatable("gui.boshysbteutils.config.overlays.render_distance.current",
                        Text.translatable("gui.boshysbteutils.config.overlays.render_distance.unlimited"));
            } else {
                return Text.translatable("gui.boshysbteutils.config.overlays.render_distance.current",
                        Text.literal(config.overlayRenderDistance + " chunks"));
            }
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (verticalAmount != 0) {
                scrollOffset -= (int)(verticalAmount * 20);
                if (scrollOffset < 0) scrollOffset = 0;
                int contentHeight = 250;
                int maxScroll = Math.max(0, contentHeight - this.height + 100);
                if (scrollOffset > maxScroll) scrollOffset = maxScroll;
                rebuildButtons();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public void close() {
            saveConfig();
            this.client.setScreen(parent);
        }

        private void saveConfig() {
            AutoConfig.getConfigHolder(BoshysBTEUtilsConfig.class).save();
        }
    }
}