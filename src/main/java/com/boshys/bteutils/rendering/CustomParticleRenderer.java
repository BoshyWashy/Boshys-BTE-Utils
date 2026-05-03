package com.boshys.bteutils.rendering;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.data.MarkerData;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;

public class CustomParticleRenderer {

    private static final int CIRCLE_SEGMENTS = 64;

    public static void render(WorldRenderContext context) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        MatrixStack matrices = context.matrices();
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getPos();

        matrices.push();

        // Render connections first (so they appear behind markers)
        if (!BoshysBTEUtils.markerConnections.isEmpty()) {
            renderConnections(context, matrices, cameraPos);
        }

        // Render markers and their optional circles
        if (!BoshysBTEUtils.markers.isEmpty()) {
            renderMarkersAndCircles(context, matrices, cameraPos);
        }

        matrices.pop();
    }

    private static void renderConnections(WorldRenderContext context, MatrixStack matrices, Vec3d cameraPos) {
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());

        Color lineColour = new Color(BoshysBTEUtils.getConfig().lineColour);
        float r = lineColour.getRed() / 255f;
        float g = lineColour.getGreen() / 255f;
        float b = lineColour.getBlue() / 255f;
        float a = BoshysBTEUtils.getConfig().lineOpacity;
        float thickness = BoshysBTEUtils.getConfig().lineThickness;

        for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
            Vec3d pos1 = conn.marker1.position;
            Vec3d pos2 = conn.marker2.position;

            double x1 = pos1.x - cameraPos.x;
            double y1 = pos1.y - cameraPos.y;
            double z1 = pos1.z - cameraPos.z;

            double x2 = pos2.x - cameraPos.x;
            double y2 = pos2.y - cameraPos.y;
            double z2 = pos2.z - cameraPos.z;

            drawThickLine(buffer, matrices, x1, y1, z1, x2, y2, z2, r, g, b, a, thickness);
        }
    }

    private static void drawThickLine(VertexConsumer buffer, MatrixStack matrices,
                                      double x1, double y1, double z1,
                                      double x2, double y2, double z2,
                                      float r, float g, float b, float a, float thickness) {

        MatrixStack.Entry entry = matrices.peek();

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length == 0) return;

        dx /= length;
        dy /= length;
        dz /= length;

        double perpX, perpY, perpZ;
        if (Math.abs(dx) < Math.abs(dy) && Math.abs(dx) < Math.abs(dz)) {
            perpX = 0; perpY = -dz; perpZ = dy;
        } else if (Math.abs(dy) < Math.abs(dz)) {
            perpX = -dz; perpY = 0; perpZ = dx;
        } else {
            perpX = -dy; perpY = dx; perpZ = 0;
        }

        double perpLength = Math.sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ);
        perpX /= perpLength;
        perpY /= perpLength;
        perpZ /= perpLength;

        double perp2X = dy * perpZ - dz * perpY;
        double perp2Y = dz * perpX - dx * perpZ;
        double perp2Z = dx * perpY - dy * perpX;

        float halfThickness = thickness * 0.05f;

        buffer.vertex(entry.getPositionMatrix(), (float)x1, (float)y1, (float)z1)
                .color(r, g, b, a)
                .normal(entry, (float)dx, (float)dy, (float)dz)
                .overlay(0)
                .light(15728880);

        buffer.vertex(entry.getPositionMatrix(), (float)x2, (float)y2, (float)z2)
                .color(r, g, b, a)
                .normal(entry, (float)dx, (float)dy, (float)dz)
                .overlay(0)
                .light(15728880);

        for (int i = 0; i < 4; i++) {
            double angle = (Math.PI * 2 * i) / 4;
            double offsetX = (perpX * Math.cos(angle) + perp2X * Math.sin(angle)) * halfThickness;
            double offsetY = (perpY * Math.cos(angle) + perp2Y * Math.sin(angle)) * halfThickness;
            double offsetZ = (perpZ * Math.cos(angle) + perp2Z * Math.sin(angle)) * halfThickness;

            buffer.vertex(entry.getPositionMatrix(), (float)(x1 + offsetX), (float)(y1 + offsetY), (float)(z1 + offsetZ))
                    .color(r, g, b, a)
                    .normal(entry, (float)dx, (float)dy, (float)dz)
                    .overlay(0)
                    .light(15728880);

            buffer.vertex(entry.getPositionMatrix(), (float)(x2 + offsetX), (float)(y2 + offsetY), (float)(z2 + offsetZ))
                    .color(r, g, b, a)
                    .normal(entry, (float)dx, (float)dy, (float)dz)
                    .overlay(0)
                    .light(15728880);
        }
    }

    private static void renderMarkersAndCircles(WorldRenderContext context, MatrixStack matrices, Vec3d cameraPos) {
        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            double distSq = marker.position.squaredDistanceTo(cameraPos);
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

            matrices.push();
            matrices.translate(x, y, z);

            VertexConsumerProvider consumers = context.consumers();
            if (consumers == null) {
                matrices.pop();
                continue;
            }

            VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());

            buildCube(buffer, matrices, scale, r, g, b, a);

            if (isSelected && BoshysBTEUtils.selectedMarkers.size() > 1) {
                renderSelectionRing(buffer, matrices, scale * 1.5f, r, g, b, 0.5f);
            }

            matrices.pop();

            // Render the optional circle (drawn in world-space to follow the marker position)
            if (marker.circleRadius > 0) {
                Color circleColor = new Color(marker.colour);
                float cr = circleColor.getRed() / 255f;
                float cg = circleColor.getGreen() / 255f;
                float cb = circleColor.getBlue() / 255f;
                float ca = Math.min(1.0f, marker.opacity + 0.2f); // slightly brighter than marker
                renderCircle(consumers, matrices, x, y, z, marker.circleRadius, cr, cg, cb, ca);
            }
        }
    }

    /**
     * Renders a horizontal circle (on the XZ plane) centred at (cx, cy, cz) relative to camera.
     */
    private static void renderCircle(VertexConsumerProvider consumers, MatrixStack matrices,
                                     double cx, double cy, double cz,
                                     double radius, float r, float g, float b, float a) {
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = matrices.peek();

        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle1 = (Math.PI * 2 * i) / CIRCLE_SEGMENTS;
            double angle2 = (Math.PI * 2 * (i + 1)) / CIRCLE_SEGMENTS;

            float x1 = (float)(cx + Math.cos(angle1) * radius);
            float z1 = (float)(cz + Math.sin(angle1) * radius);
            float x2 = (float)(cx + Math.cos(angle2) * radius);
            float z2 = (float)(cz + Math.sin(angle2) * radius);
            float fy = (float) cy;

            buffer.vertex(entry.getPositionMatrix(), x1, fy, z1)
                    .color(r, g, b, a)
                    .normal(entry, 0f, 1f, 0f)
                    .overlay(0)
                    .light(15728880);

            buffer.vertex(entry.getPositionMatrix(), x2, fy, z2)
                    .color(r, g, b, a)
                    .normal(entry, 0f, 1f, 0f)
                    .overlay(0)
                    .light(15728880);
        }
    }

    private static void renderSelectionRing(VertexConsumer buffer, MatrixStack matrices, float scale, float r, float g, float b, float a) {
        MatrixStack.Entry entry = matrices.peek();

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

            buffer.vertex(entry.getPositionMatrix(), x1, ringY, z1)
                    .color(r, g, b, a)
                    .light(15728880)
                    .overlay(0)
                    .normal(entry, 0, 1, 0);

            buffer.vertex(entry.getPositionMatrix(), x2, ringY, z2)
                    .color(r, g, b, a)
                    .light(15728880)
                    .overlay(0)
                    .normal(entry, 0, 1, 0);
        }
    }

    private static void buildCube(VertexConsumer buffer, MatrixStack matrices, float scale, float r, float g, float b, float a) {
        MatrixStack.Entry entry = matrices.peek();

        // Front face (z+)
        vertex(buffer, entry, -scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, scale, r, g, b, a);

        // Back face (z-)
        vertex(buffer, entry, scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, -scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, -scale, r, g, b, a);

        // Top face (y+)
        vertex(buffer, entry, -scale, scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, -scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, -scale, r, g, b, a);

        // Bottom face (y-)
        vertex(buffer, entry, -scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, -scale, -scale, scale, r, g, b, a);

        // Right face (x+)
        vertex(buffer, entry, scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, scale, r, g, b, a);

        // Left face (x-)
        vertex(buffer, entry, -scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, -scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, -scale, r, g, b, a);
    }

    private static void vertex(VertexConsumer buffer, MatrixStack.Entry entry, float x, float y, float z, float r, float g, float b, float a) {
        buffer.vertex(entry.getPositionMatrix(), x, y, z).color(r, g, b, a).light(15728880).overlay(0).normal(entry, 0, 1, 0);
    }
}