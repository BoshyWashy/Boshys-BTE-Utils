package com.boshys.bteutils.rendering;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.data.MarkerData;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public class CustomParticleRenderer {

    private static final RenderPipeline MARKER_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("boshysbteutils", "marker_pipeline"))
                    .build()
    );

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer stagedBuffer = new StagedVertexBuffer(() -> "BTE Markers", RenderType.SMALL_BUFFER_SIZE);

    // Render states - extracted during extraction phase, used during drawing phase
    private static List<ConnectionRenderState> connectionStates = new ArrayList<>();
    private static List<MarkerRenderState> markerStates = new ArrayList<>();
    private static Vec3 cameraPos = Vec3.ZERO;

    private record ConnectionRenderState(
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            float r, float g, float b, float a,
            float thickness,
            boolean isSelected
    ) {}

    private record MarkerRenderState(
            double x, double y, double z,
            float r, float g, float b, float a,
            float scale,
            boolean isSelected,
            double circleRadius,
            float cr, float cg, float cb, float ca,
            float circleThickness,
            float circleSegmentPercent
    ) {}

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(CustomParticleRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(CustomParticleRenderer::render);
    }

    public static void extract(LevelExtractionContext context) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) {
            connectionStates = List.of();
            markerStates = List.of();
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            connectionStates = List.of();
            markerStates = List.of();
            return;
        }

        cameraPos = context.levelState().cameraRenderState.pos;

        // Extract connections
        List<ConnectionRenderState> connList = new ArrayList<>();
        if (!BoshysBTEUtils.markerConnections.isEmpty()) {
            Color defaultLineColour = new Color(BoshysBTEUtils.getConfig().lineColour);
            float defaultR = defaultLineColour.getRed() / 255f;
            float defaultG = defaultLineColour.getGreen() / 255f;
            float defaultB = defaultLineColour.getBlue() / 255f;
            float defaultA = BoshysBTEUtils.getConfig().lineOpacity;
            float defaultThickness = BoshysBTEUtils.getConfig().lineThickness;

            for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
                Vec3 pos1 = conn.marker1.position;
                Vec3 pos2 = conn.marker2.position;

                double x1 = pos1.x - cameraPos.x;
                double y1 = pos1.y - cameraPos.y;
                double z1 = pos1.z - cameraPos.z;
                double x2 = pos2.x - cameraPos.x;
                double y2 = pos2.y - cameraPos.y;
                double z2 = pos2.z - cameraPos.z;

                Color connColour = conn.lineColour >= 0 ? new Color(conn.lineColour) : defaultLineColour;
                float r = conn.lineColour >= 0 ? connColour.getRed() / 255f : defaultR;
                float g = conn.lineColour >= 0 ? connColour.getGreen() / 255f : defaultG;
                float b = conn.lineColour >= 0 ? connColour.getBlue() / 255f : defaultB;
                float a = conn.lineOpacity >= 0 ? conn.lineOpacity : defaultA;
                float thickness = conn.lineThickness >= 0 ? conn.lineThickness : defaultThickness;

                boolean isSelected = BoshysBTEUtils.selectedConnections.contains(conn);
                float renderR = r, renderG = g, renderB = b, renderA = a;
                float renderThickness = thickness * 0.03f;

                if (isSelected) {
                    renderR = Math.min(1.0f, r + 0.3f);
                    renderG = Math.min(1.0f, g + 0.3f);
                    renderB = Math.min(1.0f, b + 0.3f);
                    renderThickness *= 1.5f;
                }

                connList.add(new ConnectionRenderState(x1, y1, z1, x2, y2, z2,
                        renderR, renderG, renderB, renderA, renderThickness, isSelected));
            }
        }
        connectionStates = connList;

        // Extract markers
        List<MarkerRenderState> markerList = new ArrayList<>();
        if (!BoshysBTEUtils.markers.isEmpty()) {
            for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
                double distSq = marker.position.distanceToSqr(cameraPos);
                if (distSq > 1024 * 1024) continue;

                Color markerColour = new Color(marker.colour);
                float r = markerColour.getRed() / 255f;
                float g = markerColour.getGreen() / 255f;
                float b = markerColour.getBlue() / 255f;
                float a = marker.opacity;

                double x = marker.position.x - cameraPos.x;
                double y = marker.position.y - cameraPos.y;
                double z = marker.position.z - cameraPos.z;

                float baseScale = marker.scale;
                float scale = baseScale;
                boolean isSelected = BoshysBTEUtils.selectedMarkers.contains(marker);

                if (isSelected) {
                    scale = baseScale * 1.2f;
                    r = Math.min(1.0f, r + 0.3f);
                    g = Math.min(1.0f, g + 0.3f);
                    b = Math.min(1.0f, b + 0.3f);
                }

                float cr = 0, cg = 0, cb = 0, ca = 0, circleThickness = 0, circleSegmentPercent = 0;
                double circleRadius = 0;
                if (marker.circleRadius > 0) {
                    Color defaultCircleColor = new Color(BoshysBTEUtils.getConfig().circleColour);
                    cr = marker.circleColour >= 0
                            ? new Color(marker.circleColour).getRed() / 255f
                            : defaultCircleColor.getRed() / 255f;
                    cg = marker.circleColour >= 0
                            ? new Color(marker.circleColour).getGreen() / 255f
                            : defaultCircleColor.getGreen() / 255f;
                    cb = marker.circleColour >= 0
                            ? new Color(marker.circleColour).getBlue() / 255f
                            : defaultCircleColor.getBlue() / 255f;
                    ca = marker.circleOpacity >= 0 ? marker.circleOpacity : BoshysBTEUtils.getConfig().circleOpacity;
                    circleThickness = marker.circleThickness >= 0 ? marker.circleThickness : BoshysBTEUtils.getConfig().circleThickness;
                    circleSegmentPercent = marker.circleSegmentPercent >= 0 ? marker.circleSegmentPercent : BoshysBTEUtils.getConfig().circleSegmentPercent;
                    circleRadius = marker.circleRadius;
                }

                markerList.add(new MarkerRenderState(x, y, z, r, g, b, a, scale, isSelected,
                        circleRadius, cr, cg, cb, ca, circleThickness * 0.03f, circleSegmentPercent));
            }
        }
        markerStates = markerList;
    }

    public static void render(LevelRenderContext context) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) return;
        if (connectionStates.isEmpty() && markerStates.isEmpty()) return;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();

        VertexFormat formatBinding = MARKER_PIPELINE.getVertexFormatBinding(0);
        assert formatBinding != null;

        PrimitiveTopology primitive = MARKER_PIPELINE.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(formatBinding, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        VertexConsumer builder = stagedBuffer.getVertexBuilder(draw);

        if (!connectionStates.isEmpty()) {
            renderConnections(poseStack, builder);
        }

        if (!markerStates.isEmpty()) {
            renderMarkersAndCircles(poseStack, builder);
        }

        stagedBuffer.upload();

        StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
        if (info != null) {
            draw(Minecraft.getInstance(), info, MARKER_PIPELINE);
        }

        stagedBuffer.endFrame();
        poseStack.popPose();
    }

    private static void renderConnections(PoseStack poseStack, VertexConsumer builder) {
        for (ConnectionRenderState conn : connectionStates) {
            renderLineAsRect(builder, poseStack,
                    conn.x1, conn.y1, conn.z1, conn.x2, conn.y2, conn.z2,
                    conn.r, conn.g, conn.b, conn.a, conn.thickness);
        }
    }

    private static void renderLineAsRect(VertexConsumer builder, PoseStack poseStack,
                                         double x1, double y1, double z1,
                                         double x2, double y2, double z2,
                                         float r, float g, float b, float a, float thickness) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) return;

        double nx = dx / length;
        double ny = dy / length;
        double nz = dz / length;

        double arbitraryX = 0, arbitraryY = 1, arbitraryZ = 0;
        if (Math.abs(ny) > 0.99) {
            arbitraryY = 0;
            arbitraryZ = 1;
        }

        double perp1X = ny * arbitraryZ - nz * arbitraryY;
        double perp1Y = nz * arbitraryX - nx * arbitraryZ;
        double perp1Z = nx * arbitraryY - ny * arbitraryX;
        double perp1Len = Math.sqrt(perp1X * perp1X + perp1Y * perp1Y + perp1Z * perp1Z);
        perp1X /= perp1Len; perp1Y /= perp1Len; perp1Z /= perp1Len;

        double perp2X = ny * perp1Z - nz * perp1Y;
        double perp2Y = nz * perp1X - nx * perp1Z;
        double perp2Z = nx * perp1Y - ny * perp1X;

        double hx = perp1X * thickness, hy = perp1Y * thickness, hz = perp1Z * thickness;
        double jx = perp2X * thickness, jy = perp2Y * thickness, jz = perp2Z * thickness;

        double s0x = x1 + hx + jx, s0y = y1 + hy + jy, s0z = z1 + hz + jz;
        double s1x = x1 + hx - jx, s1y = y1 + hy - jy, s1z = z1 + hz - jz;
        double s2x = x1 - hx - jx, s2y = y1 - hy - jy, s2z = z1 - hz - jz;
        double s3x = x1 - hx + jx, s3y = y1 - hy + jy, s3z = z1 - hz + jz;

        double e0x = x2 + hx + jx, e0y = y2 + hy + jy, e0z = z2 + hz + jz;
        double e1x = x2 + hx - jx, e1y = y2 + hy - jy, e1z = z2 + hz - jz;
        double e2x = x2 - hx - jx, e2y = y2 - hy - jy, e2z = z2 - hz - jz;
        double e3x = x2 - hx + jx, e3y = y2 - hy + jy, e3z = z2 - hz + jz;

        quad(builder, matrix, s0x, s0y, s0z, s1x, s1y, s1z, e1x, e1y, e1z, e0x, e0y, e0z, r, g, b, a);
        quad(builder, matrix, s2x, s2y, s2z, s3x, s3y, s3z, e3x, e3y, e3z, e2x, e2y, e2z, r, g, b, a);
        quad(builder, matrix, s1x, s1y, s1z, s2x, s2y, s2z, e2x, e2y, e2z, e1x, e1y, e1z, r, g, b, a);
        quad(builder, matrix, s3x, s3y, s3z, s0x, s0y, s0z, e0x, e0y, e0z, e3x, e3y, e3z, r, g, b, a);
        quad(builder, matrix, s0x, s0y, s0z, s3x, s3y, s3z, s2x, s2y, s2z, s1x, s1y, s1z, r, g, b, a);
        quad(builder, matrix, e0x, e0y, e0z, e1x, e1y, e1z, e2x, e2y, e2z, e3x, e3y, e3z, r, g, b, a);
    }

    private static void quad(VertexConsumer builder, Matrix4f matrix,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4,
                             float r, float g, float b, float a) {
        vertex(builder, matrix, (float)x1, (float)y1, (float)z1, r, g, b, a);
        vertex(builder, matrix, (float)x2, (float)y2, (float)z2, r, g, b, a);
        vertex(builder, matrix, (float)x3, (float)y3, (float)z3, r, g, b, a);
        vertex(builder, matrix, (float)x4, (float)y4, (float)z4, r, g, b, a);
    }

    private static void renderMarkersAndCircles(PoseStack poseStack, VertexConsumer builder) {
        for (MarkerRenderState marker : markerStates) {
            poseStack.pushPose();
            poseStack.translate(marker.x, marker.y, marker.z);

            buildCube(builder, poseStack, marker.scale, marker.r, marker.g, marker.b, marker.a);

            if (marker.isSelected && BoshysBTEUtils.selectedMarkers.size() > 1) {
                renderSelectionRing(builder, poseStack, marker.scale * 1.5f, marker.r, marker.g, marker.b, 0.5f);
            }

            poseStack.popPose();

            if (marker.circleRadius > 0) {
                renderCircleAsRects(builder, poseStack, marker.x, marker.y, marker.z, marker.circleRadius,
                        marker.cr, marker.cg, marker.cb, marker.ca, marker.circleThickness, marker.circleSegmentPercent);
            }
        }
    }

    private static void renderCircleAsRects(VertexConsumer builder, PoseStack poseStack,
                                            double cx, double cy, double cz,
                                            double radius, float r, float g, float b, float a,
                                            float thickness, float segmentPercent) {
        int segments = Math.max(3, Math.min(1000, (int) Math.round(100.0 / segmentPercent)));

        for (int i = 0; i < segments; i++) {
            double angle1 = (Math.PI * 2 * i) / segments;
            double angle2 = (Math.PI * 2 * (i + 1)) / segments;

            double x1 = cx + Math.cos(angle1) * radius;
            double z1 = cz + Math.sin(angle1) * radius;
            double x2 = cx + Math.cos(angle2) * radius;
            double z2 = cz + Math.sin(angle2) * radius;

            renderLineAsRect(builder, poseStack, x1, cy, z1, x2, cy, z2, r, g, b, a, thickness);
        }
    }

    private static void renderSelectionRing(VertexConsumer builder, PoseStack poseStack, float scale, float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        int segments = 16;
        float ringRadius = scale;
        float ringY = 0;

        for (int i = 0; i < segments; i++) {
            double angle1 = (Math.PI * 2 * i) / segments;
            double angle2 = (Math.PI * 2 * (i + 1)) / segments;

            float x1 = (float)(Math.cos(angle1) * ringRadius);
            float z1 = (float)(Math.sin(angle1) * ringRadius);
            float x2 = (float)(Math.cos(angle2) * ringRadius);
            float z2 = (float)(Math.sin(angle2) * ringRadius);

            builder.addVertex(matrix, x1, ringY, z1).setColor(r, g, b, a);
            builder.addVertex(matrix, x2, ringY, z2).setColor(r, g, b, a);
        }
    }

    private static void buildCube(VertexConsumer builder, PoseStack poseStack, float scale, float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        vertex(builder, matrix, -scale, -scale, scale, r, g, b, a);
        vertex(builder, matrix, scale, -scale, scale, r, g, b, a);
        vertex(builder, matrix, scale, scale, scale, r, g, b, a);
        vertex(builder, matrix, -scale, scale, scale, r, g, b, a);

        vertex(builder, matrix, scale, -scale, -scale, r, g, b, a);
        vertex(builder, matrix, -scale, -scale, -scale, r, g, b, a);
        vertex(builder, matrix, -scale, scale, -scale, r, g, b, a);
        vertex(builder, matrix, scale, scale, -scale, r, g, b, a);

        vertex(builder, matrix, -scale, scale, scale, r, g, b, a);
        vertex(builder, matrix, scale, scale, scale, r, g, b, a);
        vertex(builder, matrix, scale, scale, -scale, r, g, b, a);
        vertex(builder, matrix, -scale, scale, -scale, r, g, b, a);

        vertex(builder, matrix, -scale, -scale, -scale, r, g, b, a);
        vertex(builder, matrix, scale, -scale, -scale, r, g, b, a);
        vertex(builder, matrix, scale, -scale, scale, r, g, b, a);
        vertex(builder, matrix, -scale, -scale, scale, r, g, b, a);

        vertex(builder, matrix, scale, -scale, scale, r, g, b, a);
        vertex(builder, matrix, scale, -scale, -scale, r, g, b, a);
        vertex(builder, matrix, scale, scale, -scale, r, g, b, a);
        vertex(builder, matrix, scale, scale, scale, r, g, b, a);

        vertex(builder, matrix, -scale, -scale, -scale, r, g, b, a);
        vertex(builder, matrix, -scale, -scale, scale, r, g, b, a);
        vertex(builder, matrix, -scale, scale, scale, r, g, b, a);
        vertex(builder, matrix, -scale, scale, -scale, r, g, b, a);
    }

    private static void vertex(VertexConsumer builder, Matrix4f matrix, float x, float y, float z, float r, float g, float b, float a) {
        builder.addVertex(matrix, x, y, z).setColor(r, g, b, a);
    }

    private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();

        assert colorTexture != null;

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "BTE Markers Rendering", colorTexture, Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
            renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());

            renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    public static void close() {
        stagedBuffer.close();
    }
}