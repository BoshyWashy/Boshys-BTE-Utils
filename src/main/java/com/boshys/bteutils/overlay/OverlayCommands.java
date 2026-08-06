package com.boshys.bteutils.overlay;

import com.boshys.bteutils.BoshysBTEUtils;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OverlayCommands {

    private final OverlayStorage storage;

    public OverlayCommands(OverlayStorage storage) {
        this.storage = storage;
    }

    private SuggestionProvider<FabricClientCommandSource> imageFiles() {
        return (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase();
            for (String name : OverlayStorage.listImageFiles()) {
                if (name.toLowerCase().startsWith(rem)) builder.suggest(name);
            }
            return CompletableFuture.completedFuture(builder.build());
        };
    }

    private SuggestionProvider<FabricClientCommandSource> savedOverlays() {
        return (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase();
            for (String key : OverlayStorage.listSavedOverlayKeys()) {
                if (key.toLowerCase().startsWith(rem)) builder.suggest(key);
            }
            return CompletableFuture.completedFuture(builder.build());
        };
    }

    private SuggestionProvider<FabricClientCommandSource> loadedOverlaysWithWildcard() {
        return (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase();
            if ("*".startsWith(rem)) builder.suggest("*");
            for (String key : storage.getLoadedOverlays().keySet()) {
                if (key.toLowerCase().startsWith(rem)) builder.suggest(key);
            }
            return CompletableFuture.completedFuture(builder.build());
        };
    }

    private SuggestionProvider<FabricClientCommandSource> loadedOverlaysOnly() {
        return (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase();
            for (String key : storage.getLoadedOverlays().keySet()) {
                if (key.toLowerCase().startsWith(rem)) builder.suggest(key);
            }
            return CompletableFuture.completedFuture(builder.build());
        };
    }

    public LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("overlay")
                .then(buildNew())
                .then(buildLoad())
                .then(buildHide())
                .then(buildTempHide())
                .then(buildMoveToPlayer())
                .then(buildDisplace())
                .then(buildRotate())
                .then(buildScale())
                .then(buildFlip())
                .then(buildDelete())
                .then(buildReanchor())
                .then(buildCorner())
                .then(buildResetCorners())
                .then(buildToggleMarkers())
                .then(buildOpacity());
    }


    // ------------------------------------------------------------------
    // Temporarily hide/show overlays (like marker tempHide)
    // ------------------------------------------------------------------
    private LiteralArgumentBuilder<FabricClientCommandSource> buildTempHide() {
        return ClientCommands.literal("tempHide")
                .then(ClientCommands.argument("hide", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean shouldHide = BoolArgumentType.getBool(ctx, "hide");
                            if (shouldHide) {
                                if (storage.getTempHiddenOverlays().containsAll(storage.getLoadedOverlays().keySet())
                                        && !storage.getLoadedOverlays().isEmpty()) {
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.temphide.already_hidden_all"));
                                    return 0;
                                }
                                int count = storage.tempHideAll();
                                if (count > 0) {
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.temphide.hidden", count));
                                    return 1;
                                } else {
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.temphide.none_visible"));
                                    return 0;
                                }
                            } else {
                                if (storage.getTempHiddenOverlays().isEmpty()) {
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.temphide.already_shown"));
                                    return 0;
                                }
                                int count = storage.tempShowAll();
                                if (count > 0) {
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.temphide.shown", count));
                                    return 1;
                                } else {
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.temphide.none_hidden"));
                                    return 0;
                                }
                            }
                        }));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildNew() {
        return ClientCommands.literal("new")
                .then(ClientCommands.argument("displayName", StringArgumentType.string())
                        .then(ClientCommands.argument("size", DoubleArgumentType.doubleArg(0.1))
                                .then(ClientCommands.argument("imageFile", StringArgumentType.string())
                                        .suggests(imageFiles())
                                        .executes(ctx -> {
                                            String displayName = StringArgumentType.getString(ctx, "displayName");
                                            double size = DoubleArgumentType.getDouble(ctx, "size");
                                            String imageFile = StringArgumentType.getString(ctx, "imageFile");

                                            List<String> available = OverlayStorage.listImageFiles();
                                            if (!available.contains(imageFile)) {
                                                ctx.getSource().sendFeedback(Component.translatable(
                                                        "command.boshysbteutils.overlay.image_not_found", imageFile));
                                                return 0;
                                            }

                                            String safeKey = OverlayData.toSafeFilename(displayName);
                                            if (storage.getOverlay(safeKey) != null ||
                                                    OverlayStorage.listSavedOverlayKeys().contains(safeKey)) {
                                                ctx.getSource().sendFeedback(Component.translatable(
                                                        "command.boshysbteutils.overlay.name_taken", safeKey));
                                                return 0;
                                            }

                                            LocalPlayer player = ctx.getSource().getPlayer();
                                            Vec3 pos = new Vec3(player.getX(), player.getY(), player.getZ());

                                            storage.createOverlay(displayName, imageFile, pos, size);

                                            ctx.getSource().sendFeedback(Component.translatable(
                                                    "command.boshysbteutils.overlay.created",
                                                    displayName, imageFile,
                                                    String.format("%.1f", size)));
                                            return 1;
                                        }))));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildLoad() {
        return ClientCommands.literal("load")
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(savedOverlays())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String safeKey = OverlayData.toSafeFilename(name);

                            if (storage.getOverlay(safeKey) != null) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.already_loaded", safeKey));
                                return 0;
                            }

                            OverlayData.ImageOverlay loaded = storage.loadOverlay(safeKey);
                            if (loaded == null) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.not_found", safeKey));
                                return 0;
                            }

                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.loaded", loaded.displayName));
                            return 1;
                        }));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildHide() {
        return ClientCommands.literal("hide")
                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                        .suggests(loadedOverlaysWithWildcard())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").trim();

                            if (name.equals("*")) {
                                int count = storage.getLoadedOverlays().size();
                                List<String> keys = List.copyOf(storage.getLoadedOverlays().keySet());
                                for (String k : keys) storage.unloadOverlay(k);
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.hidden_all", count));
                                return 1;
                            }

                            String safeKey = OverlayData.toSafeFilename(name);
                            if (storage.getOverlay(safeKey) == null) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.not_loaded", safeKey));
                                return 0;
                            }

                            storage.unloadOverlay(safeKey);
                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.hidden", safeKey));
                            return 1;
                        }));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildMoveToPlayer() {
        return ClientCommands.literal("moveToPlayer")
                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                        .suggests(loadedOverlaysWithWildcard())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").trim();

                            if (name.equals("*")) {
                                LocalPlayer player = ctx.getSource().getPlayer();
                                Vec3 pos = new Vec3(player.getX(), player.getY(), player.getZ());
                                int count = 0;
                                for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
                                    Vec3 delta = pos.subtract(overlay.anchor);
                                    overlay.anchor = pos;
                                    for (int i = 0; i < 4; i++) {
                                        overlay.corners[i] = overlay.corners[i].add(delta);
                                    }
                                    storage.saveOverlay(overlay);
                                    count++;
                                }
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.moved_to_player",
                                        "all overlays",
                                        String.format("%.2f, %.2f, %.2f", pos.x, pos.y, pos.z)));
                                return count > 0 ? 1 : 0;
                            }

                            String safeKey = OverlayData.toSafeFilename(name);
                            OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);

                            if (overlay == null) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.not_loaded", safeKey));
                                return 0;
                            }

                            LocalPlayer player = ctx.getSource().getPlayer();
                            Vec3 pos = new Vec3(player.getX(), player.getY(), player.getZ());
                            Vec3 delta = pos.subtract(overlay.anchor);
                            overlay.anchor = pos;
                            for (int i = 0; i < 4; i++) {
                                overlay.corners[i] = overlay.corners[i].add(delta);
                            }
                            storage.saveOverlay(overlay);

                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.moved_to_player",
                                    overlay.displayName,
                                    String.format("%.2f, %.2f, %.2f", pos.x, pos.y, pos.z)));
                            return 1;
                        }));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildDisplace() {
        return ClientCommands.literal("displace")
                .then(ClientCommands.literal("*")
                        .then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
                                .then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(ClientCommands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> {
                                                    double dx = DoubleArgumentType.getDouble(ctx, "x");
                                                    double dy = DoubleArgumentType.getDouble(ctx, "y");
                                                    double dz = DoubleArgumentType.getDouble(ctx, "z");
                                                    int count = 0;
                                                    for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
                                                        overlay.anchor = overlay.anchor.add(dx, dy, dz);
                                                        for (int i = 0; i < 4; i++) {
                                                            overlay.corners[i] = overlay.corners[i].add(dx, dy, dz);
                                                        }
                                                        storage.saveOverlay(overlay);
                                                        count++;
                                                    }
                                                    ctx.getSource().sendFeedback(Component.translatable(
                                                            "command.boshysbteutils.overlay.displaced",
                                                            "all overlays",
                                                            String.format("%.2f, %.2f, %.2f", dx, dy, dz)));
                                                    return count > 0 ? 1 : 0;
                                                })))))
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(loadedOverlaysOnly())
                        .then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
                                .then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(ClientCommands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> {
                                                    String name = StringArgumentType.getString(ctx, "name");
                                                    String safeKey = OverlayData.toSafeFilename(name);
                                                    OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);

                                                    if (overlay == null) {
                                                        ctx.getSource().sendFeedback(Component.translatable(
                                                                "command.boshysbteutils.overlay.not_loaded", safeKey));
                                                        return 0;
                                                    }

                                                    double dx = DoubleArgumentType.getDouble(ctx, "x");
                                                    double dy = DoubleArgumentType.getDouble(ctx, "y");
                                                    double dz = DoubleArgumentType.getDouble(ctx, "z");

                                                    overlay.anchor = overlay.anchor.add(dx, dy, dz);
                                                    for (int i = 0; i < 4; i++) {
                                                        overlay.corners[i] = overlay.corners[i].add(dx, dy, dz);
                                                    }
                                                    storage.saveOverlay(overlay);

                                                    ctx.getSource().sendFeedback(Component.translatable(
                                                            "command.boshysbteutils.overlay.displaced",
                                                            overlay.displayName,
                                                            String.format("%.2f, %.2f, %.2f", dx, dy, dz)));
                                                    return 1;
                                                })))));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildRotate() {
        return ClientCommands.literal("rotate")
                .then(ClientCommands.literal("*")
                        .then(ClientCommands.argument("degrees", DoubleArgumentType.doubleArg())
                                .executes(ctx -> {
                                    double deg = DoubleArgumentType.getDouble(ctx, "degrees");
                                    int count = 0;
                                    double rad = Math.toRadians(deg);
                                    double cos = Math.cos(rad);
                                    double sin = Math.sin(rad);
                                    for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
                                        for (int i = 0; i < 4; i++) {
                                            Vec3 off = overlay.corners[i].subtract(overlay.anchor);
                                            double nx = off.x * cos - off.z * sin;
                                            double nz = off.x * sin + off.z * cos;
                                            overlay.corners[i] = overlay.anchor.add(new Vec3(nx, off.y, nz));
                                        }
                                        storage.saveOverlay(overlay);
                                        count++;
                                    }
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.rotated",
                                            "all overlays", deg));
                                    return count > 0 ? 1 : 0;
                                })))
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(loadedOverlaysOnly())
                        .then(ClientCommands.argument("degrees", DoubleArgumentType.doubleArg())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    String safeKey = OverlayData.toSafeFilename(name);
                                    OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);

                                    if (overlay == null) {
                                        ctx.getSource().sendFeedback(Component.translatable(
                                                "command.boshysbteutils.overlay.not_loaded", safeKey));
                                        return 0;
                                    }

                                    double deg = DoubleArgumentType.getDouble(ctx, "degrees");
                                    double rad = Math.toRadians(deg);
                                    double cos = Math.cos(rad);
                                    double sin = Math.sin(rad);
                                    for (int i = 0; i < 4; i++) {
                                        Vec3 off = overlay.corners[i].subtract(overlay.anchor);
                                        double nx = off.x * cos - off.z * sin;
                                        double nz = off.x * sin + off.z * cos;
                                        overlay.corners[i] = overlay.anchor.add(new Vec3(nx, off.y, nz));
                                    }
                                    storage.saveOverlay(overlay);

                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.rotated",
                                            overlay.displayName, deg));
                                    return 1;
                                })));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildScale() {
        return ClientCommands.literal("scale")
                .then(ClientCommands.literal("*")
                        .then(ClientCommands.argument("factor", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    double factor = DoubleArgumentType.getDouble(ctx, "factor");
                                    int count = 0;
                                    for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
                                        for (int i = 0; i < 4; i++) {
                                            Vec3 off = overlay.corners[i].subtract(overlay.anchor);
                                            overlay.corners[i] = overlay.anchor.add(off.scale(factor));
                                        }
                                        storage.saveOverlay(overlay);
                                        count++;
                                    }
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.scaled",
                                            "all overlays", factor));
                                    return count > 0 ? 1 : 0;
                                })))
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(loadedOverlaysOnly())
                        .then(ClientCommands.argument("factor", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    String safeKey = OverlayData.toSafeFilename(name);
                                    OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);

                                    if (overlay == null) {
                                        ctx.getSource().sendFeedback(Component.translatable(
                                                "command.boshysbteutils.overlay.not_loaded", safeKey));
                                        return 0;
                                    }

                                    double factor = DoubleArgumentType.getDouble(ctx, "factor");
                                    for (int i = 0; i < 4; i++) {
                                        Vec3 off = overlay.corners[i].subtract(overlay.anchor);
                                        overlay.corners[i] = overlay.anchor.add(off.scale(factor));
                                    }
                                    storage.saveOverlay(overlay);

                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.scaled",
                                            overlay.displayName, factor));
                                    return 1;
                                })));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildFlip() {
        return ClientCommands.literal("flip")
                .then(ClientCommands.literal("*")
                        .executes(ctx -> {
                            int count = 0;
                            for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
                                overlay.flipped = !overlay.flipped;
                                storage.saveOverlay(overlay);
                                count++;
                            }
                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.flipped_all", count));
                            return count > 0 ? 1 : 0;
                        }))
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(loadedOverlaysOnly())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String safeKey = OverlayData.toSafeFilename(name);
                            OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);

                            if (overlay == null) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.not_loaded", safeKey));
                                return 0;
                            }

                            overlay.flipped = !overlay.flipped;
                            storage.saveOverlay(overlay);

                            String state = overlay.flipped
                                    ? Component.translatable("command.boshysbteutils.overlay.flip_state.on").getString()
                                    : Component.translatable("command.boshysbteutils.overlay.flip_state.off").getString();
                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.flipped",
                                    overlay.displayName, state));
                            return 1;
                        }));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> buildDelete() {
        return ClientCommands.literal("delete")
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(savedOverlays())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").trim();

                            if (name.equals("*")) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.delete_confirm", "all overlays"));
                                return 0;
                            }

                            String safeKey = OverlayData.toSafeFilename(name);
                            if (!OverlayStorage.listSavedOverlayKeys().contains(safeKey)) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.not_found", safeKey));
                                return 0;
                            }
                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.delete_confirm", safeKey));
                            return 0;
                        })
                        .then(ClientCommands.literal("confirm")
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name").trim();

                                    if (name.equals("*")) {
                                        int count = 0;
                                        List<String> keys = List.copyOf(OverlayStorage.listSavedOverlayKeys());
                                        for (String safeKey : keys) {
                                            storage.deleteOverlay(safeKey);
                                            count++;
                                        }
                                        ctx.getSource().sendFeedback(Component.translatable(
                                                "command.boshysbteutils.overlay.deleted", "all overlays"));
                                        return 1;
                                    }

                                    String safeKey = OverlayData.toSafeFilename(name);

                                    if (!OverlayStorage.listSavedOverlayKeys().contains(safeKey)) {
                                        ctx.getSource().sendFeedback(Component.translatable(
                                                "command.boshysbteutils.overlay.not_found", safeKey));
                                        return 0;
                                    }

                                    boolean deleted = storage.deleteOverlay(safeKey);
                                    if (deleted) {
                                        ctx.getSource().sendFeedback(Component.translatable(
                                                "command.boshysbteutils.overlay.deleted", safeKey));
                                        return 1;
                                    } else {
                                        ctx.getSource().sendFeedback(Component.translatable(
                                                "command.boshysbteutils.overlay.delete_failed", safeKey));
                                        return 0;
                                    }
                                })));
    }

    // ------------------------------------------------------------------
    // Reanchor: move anchor to player without moving corners
    // ------------------------------------------------------------------
    private LiteralArgumentBuilder<FabricClientCommandSource> buildReanchor() {
        return ClientCommands.literal("reanchor")
                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                        .suggests(loadedOverlaysWithWildcard())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").trim();

                            if (name.equals("*")) {
                                LocalPlayer player = ctx.getSource().getPlayer();
                                Vec3 pos = new Vec3(player.getX(), player.getY(), player.getZ());
                                int count = 0;
                                for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
                                    overlay.anchor = pos;
                                    storage.saveOverlay(overlay);
                                    count++;
                                }
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.reanchored_all", count));
                                return count > 0 ? 1 : 0;
                            }

                            String safeKey = OverlayData.toSafeFilename(name);
                            OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);
                            if (overlay == null) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.not_loaded", safeKey));
                                return 0;
                            }

                            LocalPlayer player = ctx.getSource().getPlayer();
                            overlay.anchor = new Vec3(player.getX(), player.getY(), player.getZ());
                            storage.saveOverlay(overlay);

                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.reanchored", overlay.displayName));
                            return 1;
                        }));
    }

    // ------------------------------------------------------------------
    // Corner manipulation
    // ------------------------------------------------------------------
    private LiteralArgumentBuilder<FabricClientCommandSource> buildCorner() {
        SuggestionProvider<FabricClientCommandSource> cornerSuggestions = (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase();
            for (String s : new String[]{"selected", "nw", "ne", "se", "sw"}) {
                if (s.startsWith(rem)) builder.suggest(s);
            }
            return CompletableFuture.completedFuture(builder.build());
        };

        return ClientCommands.literal("corner")
                .then(ClientCommands.argument("corner", StringArgumentType.string())
                        .suggests(cornerSuggestions)
                        .then(ClientCommands.literal("toPlayer")
                                .then(ClientCommands.argument("name", StringArgumentType.string())
                                        .suggests(loadedOverlaysOnly())
                                        .executes(ctx -> {
                                            int corner = resolveCorner(ctx);
                                            if (corner == -1) {
                                                ctx.getSource().sendFeedback(Component.translatable(
                                                        "command.boshysbteutils.overlay.invalid_corner"));
                                                return 0;
                                            }
                                            if (corner == -2) {
                                                ctx.getSource().sendFeedback(Component.translatable(
                                                        "command.boshysbteutils.overlay.no_corner_selected"));
                                                return 0;
                                            }
                                            String name = StringArgumentType.getString(ctx, "name");
                                            String safeKey = OverlayData.toSafeFilename(name);
                                            OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);
                                            if (overlay == null) {
                                                ctx.getSource().sendFeedback(Component.translatable(
                                                        "command.boshysbteutils.overlay.not_loaded", safeKey));
                                                return 0;
                                            }
                                            LocalPlayer player = ctx.getSource().getPlayer();
                                            overlay.corners[corner] = new Vec3(player.getX(), player.getY(), player.getZ());
                                            storage.saveOverlay(overlay);
                                            ctx.getSource().sendFeedback(Component.translatable(
                                                    "command.boshysbteutils.overlay.corner_moved",
                                                    OverlayData.cornerName(corner), overlay.displayName));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("displace")
                                .then(ClientCommands.argument("name", StringArgumentType.string())
                                        .suggests(loadedOverlaysOnly())
                                        .then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
                                                        .then(ClientCommands.argument("z", DoubleArgumentType.doubleArg())
                                                                .executes(ctx -> {
                                                                    int corner = resolveCorner(ctx);
                                                                    if (corner == -1) {
                                                                        ctx.getSource().sendFeedback(Component.translatable(
                                                                                "command.boshysbteutils.overlay.invalid_corner"));
                                                                        return 0;
                                                                    }
                                                                    if (corner == -2) {
                                                                        ctx.getSource().sendFeedback(Component.translatable(
                                                                                "command.boshysbteutils.overlay.no_corner_selected"));
                                                                        return 0;
                                                                    }
                                                                    String name = StringArgumentType.getString(ctx, "name");
                                                                    String safeKey = OverlayData.toSafeFilename(name);
                                                                    OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);
                                                                    if (overlay == null) {
                                                                        ctx.getSource().sendFeedback(Component.translatable(
                                                                                "command.boshysbteutils.overlay.not_loaded", safeKey));
                                                                        return 0;
                                                                    }
                                                                    double dx = DoubleArgumentType.getDouble(ctx, "x");
                                                                    double dy = DoubleArgumentType.getDouble(ctx, "y");
                                                                    double dz = DoubleArgumentType.getDouble(ctx, "z");
                                                                    overlay.corners[corner] = overlay.corners[corner].add(dx, dy, dz);
                                                                    storage.saveOverlay(overlay);
                                                                    ctx.getSource().sendFeedback(Component.translatable(
                                                                            "command.boshysbteutils.overlay.corner_displaced",
                                                                            OverlayData.cornerName(corner), overlay.displayName,
                                                                            String.format("%.2f", dx), String.format("%.2f", dy), String.format("%.2f", dz)));
                                                                    return 1;
                                                                })))))));
    }

    private int resolveCorner(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx) {
        String cornerStr = StringArgumentType.getString(ctx, "corner");
        if (cornerStr.equalsIgnoreCase("selected")) {
            if (BoshysBTEUtils.selectedOverlayCorner != null && BoshysBTEUtils.selectedCornerIndex >= 0 && BoshysBTEUtils.selectedCornerIndex <= 3) {
                return BoshysBTEUtils.selectedCornerIndex;
            }
            return -2;
        }
        return OverlayData.parseCorner(cornerStr);
    }

    // ------------------------------------------------------------------
    // Reset corners to a square around anchor
    // ------------------------------------------------------------------
    private LiteralArgumentBuilder<FabricClientCommandSource> buildResetCorners() {
        return ClientCommands.literal("resetCorners")
                .then(ClientCommands.literal("*")
                        .executes(ctx -> executeResetAll(ctx, 10.0))
                        .then(ClientCommands.argument("size", DoubleArgumentType.doubleArg(0.1))
                                .executes(ctx -> executeResetAll(ctx, DoubleArgumentType.getDouble(ctx, "size")))))
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(loadedOverlaysOnly())
                        .executes(ctx -> executeResetOne(ctx, 10.0))
                        .then(ClientCommands.argument("size", DoubleArgumentType.doubleArg(0.1))
                                .executes(ctx -> executeResetOne(ctx, DoubleArgumentType.getDouble(ctx, "size")))));
    }

    private int executeResetAll(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, double size) {
        int count = 0;
        for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
            resetCorners(overlay, size);
            storage.saveOverlay(overlay);
            count++;
        }
        ctx.getSource().sendFeedback(Component.translatable(
                "command.boshysbteutils.overlay.reset_all", count));
        return count > 0 ? 1 : 0;
    }

    private int executeResetOne(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx, double size) {
        String name = StringArgumentType.getString(ctx, "name");
        String safeKey = OverlayData.toSafeFilename(name);
        OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);
        if (overlay == null) {
            ctx.getSource().sendFeedback(Component.translatable(
                    "command.boshysbteutils.overlay.not_loaded", safeKey));
            return 0;
        }
        resetCorners(overlay, size);
        storage.saveOverlay(overlay);
        ctx.getSource().sendFeedback(Component.translatable(
                "command.boshysbteutils.overlay.reset", overlay.displayName, size));
        return 1;
    }

    private void resetCorners(OverlayData.ImageOverlay overlay, double size) {
        Vec3 a = overlay.anchor;
        overlay.corners[0] = new Vec3(a.x - size, a.y, a.z - size);
        overlay.corners[1] = new Vec3(a.x + size, a.y, a.z - size);
        overlay.corners[2] = new Vec3(a.x + size, a.y, a.z + size);
        overlay.corners[3] = new Vec3(a.x - size, a.y, a.z + size);
    }

    // ------------------------------------------------------------------
    // Toggle edit markers visibility
    // ------------------------------------------------------------------
    private LiteralArgumentBuilder<FabricClientCommandSource> buildToggleMarkers() {
        return ClientCommands.literal("toggleMarkers")
                .then(ClientCommands.literal("*")
                        .executes(ctx -> {
                            boolean anyVisible = false;
                            for (OverlayData.ImageOverlay o : storage.getLoadedOverlays().values()) {
                                if (o.markersVisible) { anyVisible = true; break; }
                            }
                            int count = 0;
                            for (OverlayData.ImageOverlay o : storage.getLoadedOverlays().values()) {
                                o.markersVisible = !anyVisible;
                                storage.saveOverlay(o);
                                count++;
                            }
                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.markers_toggled_all",
                                    anyVisible ? "hidden" : "shown"));
                            return count > 0 ? 1 : 0;
                        }))
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(loadedOverlaysOnly())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String safeKey = OverlayData.toSafeFilename(name);
                            OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);
                            if (overlay == null) {
                                ctx.getSource().sendFeedback(Component.translatable(
                                        "command.boshysbteutils.overlay.not_loaded", safeKey));
                                return 0;
                            }
                            overlay.markersVisible = !overlay.markersVisible;
                            storage.saveOverlay(overlay);
                            ctx.getSource().sendFeedback(Component.translatable(
                                    "command.boshysbteutils.overlay.markers_toggled_single",
                                    overlay.displayName,
                                    overlay.markersVisible ? "shown" : "hidden"));
                            return 1;
                        }));
    }

    // ------------------------------------------------------------------
    // Opacity: per-overlay image opacity
    // ------------------------------------------------------------------
    private LiteralArgumentBuilder<FabricClientCommandSource> buildOpacity() {
        return ClientCommands.literal("opacity")
                .then(ClientCommands.literal("*")
                        .then(ClientCommands.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f))
                                .executes(ctx -> {
                                    float opacity = FloatArgumentType.getFloat(ctx, "value");
                                    int count = 0;
                                    for (OverlayData.ImageOverlay overlay : storage.getLoadedOverlays().values()) {
                                        overlay.imageOpacity = opacity;
                                        storage.saveOverlay(overlay);
                                        count++;
                                    }
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.opacity_all", count, opacity));
                                    return count > 0 ? 1 : 0;
                                })))
                .then(ClientCommands.argument("name", StringArgumentType.string())
                        .suggests(loadedOverlaysOnly())
                        .then(ClientCommands.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f))
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    String safeKey = OverlayData.toSafeFilename(name);
                                    OverlayData.ImageOverlay overlay = storage.getOverlay(safeKey);
                                    if (overlay == null) {
                                        ctx.getSource().sendFeedback(Component.translatable(
                                                "command.boshysbteutils.overlay.not_loaded", safeKey));
                                        return 0;
                                    }
                                    float opacity = FloatArgumentType.getFloat(ctx, "value");
                                    overlay.imageOpacity = opacity;
                                    storage.saveOverlay(overlay);
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "command.boshysbteutils.overlay.opacity_set",
                                            overlay.displayName, opacity));
                                    return 1;
                                })));
    }
}