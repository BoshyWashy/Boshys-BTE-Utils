package com.boshys.bteutils.commands;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import com.boshys.bteutils.data.MarkerData;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles the /boshys-bt-utils customise command for markers, lines, and circles.
 */
public class CustomiseCommands {

    // -----------------------------------------------------------------------
    // Suggestion providers
    // -----------------------------------------------------------------------

    private static final SuggestionProvider<FabricClientCommandSource> TARGET_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("selected");
        builder.suggest("all");
        builder.suggest("inRange");
        return CompletableFuture.completedFuture(builder.build());
    };

    private static final SuggestionProvider<FabricClientCommandSource> MARKER_PROPERTY_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("opacity", new com.mojang.brigadier.LiteralMessage("0.0 -> 1.0"));
        builder.suggest("colour", new com.mojang.brigadier.LiteralMessage("Hex-Code"));
        builder.suggest("scale", new com.mojang.brigadier.LiteralMessage("0.01 -> 1.0"));
        builder.suggest("default");
        return CompletableFuture.completedFuture(builder.build());
    };

    private static final SuggestionProvider<FabricClientCommandSource> LINE_PROPERTY_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("opacity", new com.mojang.brigadier.LiteralMessage("0.0 -> 1.0"));
        builder.suggest("colour", new com.mojang.brigadier.LiteralMessage("Hex-Code"));
        builder.suggest("thickness", new com.mojang.brigadier.LiteralMessage("0.1 -> 10.0"));
        builder.suggest("default");
        return CompletableFuture.completedFuture(builder.build());
    };

    private static final SuggestionProvider<FabricClientCommandSource> CIRCLE_PROPERTY_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("opacity", new com.mojang.brigadier.LiteralMessage("0.0 -> 1.0"));
        builder.suggest("colour", new com.mojang.brigadier.LiteralMessage("Hex-Code"));
        builder.suggest("thickness", new com.mojang.brigadier.LiteralMessage("0.01 -> 10.0"));
        builder.suggest("segments", new com.mojang.brigadier.LiteralMessage("<value>"));
        builder.suggest("default");
        return CompletableFuture.completedFuture(builder.build());
    };

    private static final SuggestionProvider<FabricClientCommandSource> DEFAULT_SUB_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("opacity");
        builder.suggest("scale");
        builder.suggest("colour");
        return CompletableFuture.completedFuture(builder.build());
    };

    private static final SuggestionProvider<FabricClientCommandSource> LINE_DEFAULT_SUB_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("opacity");
        builder.suggest("thickness");
        builder.suggest("colour");
        return CompletableFuture.completedFuture(builder.build());
    };

    private static final SuggestionProvider<FabricClientCommandSource> CIRCLE_DEFAULT_SUB_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("opacity");
        builder.suggest("thickness");
        builder.suggest("colour");
        builder.suggest("segments");
        return CompletableFuture.completedFuture(builder.build());
    };

    // -----------------------------------------------------------------------
    // Build the customise command tree
    // -----------------------------------------------------------------------

    public static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("customise")
                .then(buildMarkerBranch())
                .then(buildLineBranch())
                .then(buildCircleBranch());
    }

    // ===================== MARKER BRANCH =====================

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildMarkerBranch() {
        return ClientCommandManager.literal("marker")
                // selected / all (no radius needed)
                .then(buildMarkerTargetBranch("selected", -1))
                .then(buildMarkerTargetBranch("all", -1))
                // inRange <radius>
                .then(buildMarkerTargetBranch("inRange", 0));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildMarkerTargetBranch(String targetType, int radiusFlag) {
        var target = ClientCommandManager.literal(targetType.toLowerCase());

        if (radiusFlag == 0) {
            var radiusArg = ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0.1));
            attachMarkerProperties(radiusArg, targetType, radiusFlag);
            target.then(radiusArg);
        } else {
            attachMarkerProperties(target, targetType, radiusFlag);
        }

        return target;
    }

    private static void attachMarkerProperties(ArgumentBuilder<FabricClientCommandSource, ?> parent, String targetType, int radiusFlag) {
        // opacity <value>
        parent.then(ClientCommandManager.literal("opacity")
                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f))
                        .executes(ctx -> executeMarkerCustomise(ctx, "opacity", targetType, radiusFlag))));

        // colour <hex>
        parent.then(ClientCommandManager.literal("colour")
                .then(ClientCommandManager.argument("hex", StringArgumentType.word())
                        .executes(ctx -> executeMarkerCustomise(ctx, "colour", targetType, radiusFlag))));

        // scale <value>
        parent.then(ClientCommandManager.literal("scale")
                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.01f, 1.0f))
                        .executes(ctx -> executeMarkerCustomise(ctx, "scale", targetType, radiusFlag))));

        // default [subproperty]
        parent.then(ClientCommandManager.literal("default")
                .executes(ctx -> executeMarkerCustomise(ctx, "default", targetType, radiusFlag))
                .then(ClientCommandManager.argument("subproperty", StringArgumentType.word())
                        .suggests(DEFAULT_SUB_SUGGESTIONS)
                        .executes(ctx -> executeMarkerCustomise(ctx, "default_sub", targetType, radiusFlag))));
    }


    // ===================== LINE BRANCH =====================

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildLineBranch() {
        return ClientCommandManager.literal("line")
                .then(buildLineTargetBranch("selected", -1))
                .then(buildLineTargetBranch("all", -1))
                .then(buildLineTargetBranch("inRange", 0));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildLineTargetBranch(String targetType, int radiusFlag) {
        var target = ClientCommandManager.literal(targetType.toLowerCase());

        if (radiusFlag == 0) {
            var radiusArg = ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0.1));
            attachLineProperties(radiusArg, targetType, radiusFlag);
            target.then(radiusArg);
        } else {
            attachLineProperties(target, targetType, radiusFlag);
        }

        return target;
    }

    private static void attachLineProperties(ArgumentBuilder<FabricClientCommandSource, ?> parent, String targetType, int radiusFlag) {
        // opacity <value>
        parent.then(ClientCommandManager.literal("opacity")
                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f))
                        .executes(ctx -> executeLineCustomise(ctx, "opacity", targetType, radiusFlag))));

        // colour <hex>
        parent.then(ClientCommandManager.literal("colour")
                .then(ClientCommandManager.argument("hex", StringArgumentType.word())
                        .executes(ctx -> executeLineCustomise(ctx, "colour", targetType, radiusFlag))));

        // thickness <value>
        parent.then(ClientCommandManager.literal("thickness")
                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.1f, 10.0f))
                        .executes(ctx -> executeLineCustomise(ctx, "thickness", targetType, radiusFlag))));

        // default [subproperty]
        parent.then(ClientCommandManager.literal("default")
                .executes(ctx -> executeLineCustomise(ctx, "default", targetType, radiusFlag))
                .then(ClientCommandManager.argument("subproperty", StringArgumentType.word())
                        .suggests(LINE_DEFAULT_SUB_SUGGESTIONS)
                        .executes(ctx -> executeLineCustomise(ctx, "default_sub", targetType, radiusFlag))));
    }

    // ===================== CIRCLE BRANCH =====================

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildCircleBranch() {
        return ClientCommandManager.literal("circle")
                .then(buildCircleTargetBranch("selected", -1))
                .then(buildCircleTargetBranch("all", -1))
                .then(buildCircleTargetBranch("inRange", 0));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildCircleTargetBranch(String targetType, int radiusFlag) {
        var target = ClientCommandManager.literal(targetType.toLowerCase());

        if (radiusFlag == 0) {
            var radiusArg = ClientCommandManager.argument("radius", DoubleArgumentType.doubleArg(0.1));
            attachCircleProperties(radiusArg, targetType, radiusFlag);
            target.then(radiusArg);
        } else {
            attachCircleProperties(target, targetType, radiusFlag);
        }

        return target;
    }

    private static void attachCircleProperties(ArgumentBuilder<FabricClientCommandSource, ?> parent, String targetType, int radiusFlag) {
        // opacity <value>
        parent.then(ClientCommandManager.literal("opacity")
                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f))
                        .executes(ctx -> executeCircleCustomise(ctx, "opacity", targetType, radiusFlag))));

        // colour <hex>
        parent.then(ClientCommandManager.literal("colour")
                .then(ClientCommandManager.argument("hex", StringArgumentType.word())
                        .executes(ctx -> executeCircleCustomise(ctx, "colour", targetType, radiusFlag))));

        // thickness <value>
        parent.then(ClientCommandManager.literal("thickness")
                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.01f, 10.0f))
                        .executes(ctx -> executeCircleCustomise(ctx, "thickness", targetType, radiusFlag))));

        // segments <value>
        parent.then(ClientCommandManager.literal("segments")
                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.01f))
                        .executes(ctx -> executeCircleCustomise(ctx, "segments", targetType, radiusFlag))));

        // default [subproperty]
        parent.then(ClientCommandManager.literal("default")
                .executes(ctx -> executeCircleCustomise(ctx, "default", targetType, radiusFlag))
                .then(ClientCommandManager.argument("subproperty", StringArgumentType.word())
                        .suggests(CIRCLE_DEFAULT_SUB_SUGGESTIONS)
                        .executes(ctx -> executeCircleCustomise(ctx, "default_sub", targetType, radiusFlag))));
    }

    // -----------------------------------------------------------------------
    // Execution helpers
    // -----------------------------------------------------------------------

    private static int executeMarkerCustomise(CommandContext<FabricClientCommandSource> ctx, String property, String targetType, int radiusFlag) {
        ClientPlayerEntity player = ctx.getSource().getPlayer();
        Vec3d playerPos = player != null ? new Vec3d(player.getX(), player.getY(), player.getZ()) : null;
        double radius = radiusFlag == 0 ? DoubleArgumentType.getDouble(ctx, "radius") : -1;

        List<MarkerData.TeleportMarker> targets = resolveMarkerTargets(targetType, playerPos, radius, ctx);
        if (targets == null) return 0;

        int count = 0;
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

        switch (property) {
            case "opacity" -> {
                float value = FloatArgumentType.getFloat(ctx, "value");
                for (MarkerData.TeleportMarker m : targets) {
                    m.opacity = value;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.marker.opacity", count, value));
            }
            case "colour" -> {
                String hex = StringArgumentType.getString(ctx, "hex").trim();
                int colour;
                try {
                    colour = Integer.parseInt(hex.replace("#", ""), 16);
                } catch (NumberFormatException e) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_hex", hex));
                    return 0;
                }
                for (MarkerData.TeleportMarker m : targets) {
                    m.colour = colour;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.marker.colour", count, hex));
            }
            case "scale" -> {
                float value = FloatArgumentType.getFloat(ctx, "value");
                for (MarkerData.TeleportMarker m : targets) {
                    m.scale = value;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.marker.scale", count, value));
            }
            case "default" -> {
                for (MarkerData.TeleportMarker m : targets) {
                    m.colour = config.markerColour;
                    m.scale = config.markerScale;
                    m.opacity = config.markerOpacity;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.marker.default", count));
            }
            case "default_sub" -> {
                String sub = StringArgumentType.getString(ctx, "subproperty").toLowerCase();
                for (MarkerData.TeleportMarker m : targets) {
                    switch (sub) {
                        case "opacity" -> m.opacity = config.markerOpacity;
                        case "scale" -> m.scale = config.markerScale;
                        case "colour" -> m.colour = config.markerColour;
                        default -> {
                            ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_subproperty", sub));
                            return 0;
                        }
                    }
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.marker.default_sub", count, sub));
            }
        }

        return count > 0 ? 1 : 0;
    }

    private static int executeLineCustomise(CommandContext<FabricClientCommandSource> ctx, String property, String targetType, int radiusFlag) {
        ClientPlayerEntity player = ctx.getSource().getPlayer();
        Vec3d playerPos = player != null ? new Vec3d(player.getX(), player.getY(), player.getZ()) : null;
        double radius = radiusFlag == 0 ? DoubleArgumentType.getDouble(ctx, "radius") : -1;

        List<MarkerData.MarkerConnection> targets = resolveLineTargets(targetType, playerPos, radius, ctx);
        if (targets == null) return 0;

        int count = 0;
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

        switch (property) {
            case "opacity" -> {
                float value = FloatArgumentType.getFloat(ctx, "value");
                for (MarkerData.MarkerConnection c : targets) {
                    c.lineOpacity = value;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.line.opacity", count, value));
            }
            case "colour" -> {
                String hex = StringArgumentType.getString(ctx, "hex").trim();
                int colour;
                try {
                    colour = Integer.parseInt(hex.replace("#", ""), 16);
                } catch (NumberFormatException e) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_hex", hex));
                    return 0;
                }
                for (MarkerData.MarkerConnection c : targets) {
                    c.lineColour = colour;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.line.colour", count, hex));
            }
            case "thickness" -> {
                float value = FloatArgumentType.getFloat(ctx, "value");
                for (MarkerData.MarkerConnection c : targets) {
                    c.lineThickness = value;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.line.thickness", count, value));
            }
            case "default" -> {
                for (MarkerData.MarkerConnection c : targets) {
                    c.lineColour = -1;
                    c.lineOpacity = -1;
                    c.lineThickness = -1;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.line.default", count));
            }
            case "default_sub" -> {
                String sub = StringArgumentType.getString(ctx, "subproperty").toLowerCase();
                for (MarkerData.MarkerConnection c : targets) {
                    switch (sub) {
                        case "opacity" -> c.lineOpacity = -1;
                        case "thickness" -> c.lineThickness = -1;
                        case "colour" -> c.lineColour = -1;
                        default -> {
                            ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_subproperty", sub));
                            return 0;
                        }
                    }
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.line.default_sub", count, sub));
            }
        }

        return count > 0 ? 1 : 0;
    }

    private static int executeCircleCustomise(CommandContext<FabricClientCommandSource> ctx, String property, String targetType, int radiusFlag) {
        ClientPlayerEntity player = ctx.getSource().getPlayer();
        Vec3d playerPos = player != null ? new Vec3d(player.getX(), player.getY(), player.getZ()) : null;
        double radius = radiusFlag == 0 ? DoubleArgumentType.getDouble(ctx, "radius") : -1;

        List<MarkerData.TeleportMarker> targets = resolveCircleTargets(targetType, playerPos, radius, ctx);
        if (targets == null) return 0;

        int count = 0;
        BoshysBTEUtilsConfig config = BoshysBTEUtils.getConfig();

        switch (property) {
            case "opacity" -> {
                float value = FloatArgumentType.getFloat(ctx, "value");
                for (MarkerData.TeleportMarker m : targets) {
                    m.circleOpacity = value;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.circle.opacity", count, value));
            }
            case "colour" -> {
                String hex = StringArgumentType.getString(ctx, "hex").trim();
                int colour;
                try {
                    colour = Integer.parseInt(hex.replace("#", ""), 16);
                } catch (NumberFormatException e) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_hex", hex));
                    return 0;
                }
                for (MarkerData.TeleportMarker m : targets) {
                    m.circleColour = colour;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.circle.colour", count, hex));
            }
            case "thickness" -> {
                float value = FloatArgumentType.getFloat(ctx, "value");
                for (MarkerData.TeleportMarker m : targets) {
                    m.circleThickness = value;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.circle.thickness", count, value));
            }
            case "segments" -> {
                float value = FloatArgumentType.getFloat(ctx, "value");
                for (MarkerData.TeleportMarker m : targets) {
                    m.circleSegmentPercent = value;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.circle.segments", count, value));
            }
            case "default" -> {
                for (MarkerData.TeleportMarker m : targets) {
                    m.circleColour = -1;
                    m.circleOpacity = -1;
                    m.circleThickness = -1;
                    m.circleSegmentPercent = -1.0f;
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.circle.default", count));
            }
            case "default_sub" -> {
                String sub = StringArgumentType.getString(ctx, "subproperty").toLowerCase();
                for (MarkerData.TeleportMarker m : targets) {
                    switch (sub) {
                        case "opacity" -> m.circleOpacity = -1;
                        case "thickness" -> m.circleThickness = -1;
                        case "colour" -> m.circleColour = -1;
                        case "segments" -> m.circleSegmentPercent = -1.0f;
                        default -> {
                            ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_subproperty", sub));
                            return 0;
                        }
                    }
                    count++;
                }
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.circle.default_sub", count, sub));
            }
        }

        return count > 0 ? 1 : 0;
    }

    // -----------------------------------------------------------------------
    // Target resolution
    // -----------------------------------------------------------------------

    private static List<MarkerData.TeleportMarker> resolveMarkerTargets(String targetType, Vec3d playerPos, double radius, CommandContext<FabricClientCommandSource> ctx) {
        List<MarkerData.TeleportMarker> result = new ArrayList<>();

        switch (targetType.toLowerCase()) {
            case "selected" -> {
                if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_selected_markers"));
                    return null;
                }
                result.addAll(BoshysBTEUtils.selectedMarkers);
            }
            case "all" -> {
                if (BoshysBTEUtils.markers.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_markers"));
                    return null;
                }
                result.addAll(BoshysBTEUtils.markers);
            }
            case "inrange" -> {
                if (BoshysBTEUtils.markers.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_markers"));
                    return null;
                }
                if (playerPos == null) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_player_pos"));
                    return null;
                }
                for (MarkerData.TeleportMarker m : BoshysBTEUtils.markers) {
                    if (m.position.distanceTo(playerPos) <= radius) {
                        result.add(m);
                    }
                }
                if (result.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_markers_in_range", radius));
                    return null;
                }
            }
            default -> {
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_target", targetType));
                return null;
            }
        }

        return result;
    }

    private static List<MarkerData.MarkerConnection> resolveLineTargets(String targetType, Vec3d playerPos, double radius, CommandContext<FabricClientCommandSource> ctx) {
        List<MarkerData.MarkerConnection> result = new ArrayList<>();

        switch (targetType.toLowerCase()) {
            case "selected" -> {
                if (BoshysBTEUtils.selectedConnections.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_selected_lines"));
                    return null;
                }
                result.addAll(BoshysBTEUtils.selectedConnections);
            }
            case "all" -> {
                if (BoshysBTEUtils.markerConnections.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_lines"));
                    return null;
                }
                result.addAll(BoshysBTEUtils.markerConnections);
            }
            case "inrange" -> {
                if (BoshysBTEUtils.markerConnections.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_lines"));
                    return null;
                }
                if (playerPos == null) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_player_pos"));
                    return null;
                }
                // A line is "in range" if either of its connected markers is in range
                for (MarkerData.MarkerConnection c : BoshysBTEUtils.markerConnections) {
                    if (c.marker1.position.distanceTo(playerPos) <= radius || c.marker2.position.distanceTo(playerPos) <= radius) {
                        result.add(c);
                    }
                }
                if (result.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_lines_in_range", radius));
                    return null;
                }
            }
            default -> {
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_target", targetType));
                return null;
            }
        }

        return result;
    }

    private static List<MarkerData.TeleportMarker> resolveCircleTargets(String targetType, Vec3d playerPos, double radius, CommandContext<FabricClientCommandSource> ctx) {
        List<MarkerData.TeleportMarker> result = new ArrayList<>();

        switch (targetType.toLowerCase()) {
            case "selected" -> {
                if (BoshysBTEUtils.selectedMarkers.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_selected_markers"));
                    return null;
                }
                for (MarkerData.TeleportMarker m : BoshysBTEUtils.selectedMarkers) {
                    if (m.circleRadius > 0) {
                        result.add(m);
                    }
                }
                if (result.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_selected_circles"));
                    return null;
                }
            }
            case "all" -> {
                for (MarkerData.TeleportMarker m : BoshysBTEUtils.markers) {
                    if (m.circleRadius > 0) {
                        result.add(m);
                    }
                }
                if (result.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_circles"));
                    return null;
                }
            }
            case "inrange" -> {
                if (BoshysBTEUtils.markers.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_markers"));
                    return null;
                }
                if (playerPos == null) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_player_pos"));
                    return null;
                }
                for (MarkerData.TeleportMarker m : BoshysBTEUtils.markers) {
                    if (m.circleRadius > 0 && m.position.distanceTo(playerPos) <= radius) {
                        result.add(m);
                    }
                }
                if (result.isEmpty()) {
                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.no_circles_in_range", radius));
                    return null;
                }
            }
            default -> {
                ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.customise.invalid_target", targetType));
                return null;
            }
        }

        return result;
    }
}