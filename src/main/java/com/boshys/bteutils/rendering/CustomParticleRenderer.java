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

    public static void render(WorldRenderContext context) {
        if (!BoshysBTEUtils.getConfig().enableMarkers) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        MatrixStack matrices = context.matrices();
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getPos();

        matrices.push();

        // Render connections first (so they appear behind markers)
        if (!BoshysBTEUtils.markerConnections.isEmpty()) {
            renderConnections(consumers, matrices, cameraPos);
        }

        // Render markers and their optional circles
        if (!BoshysBTEUtils.markers.isEmpty()) {
            renderMarkersAndCircles(consumers, matrices, cameraPos);
        }

        matrices.pop();
    }

    private static void renderConnections(VertexConsumerProvider consumers, MatrixStack matrices, Vec3d cameraPos) {
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());

        Color defaultLineColour = new Color(BoshysBTEUtils.getConfig().lineColour);
        float defaultR = defaultLineColour.getRed() / 255f;
        float defaultG = defaultLineColour.getGreen() / 255f;
        float defaultB = defaultLineColour.getBlue() / 255f;
        float defaultA = BoshysBTEUtils.getConfig().lineOpacity;
        float defaultThickness = BoshysBTEUtils.getConfig().lineThickness;

        for (MarkerData.MarkerConnection conn : BoshysBTEUtils.markerConnections) {
            Vec3d pos1 = conn.marker1.position;
            Vec3d pos2 = conn.marker2.position;

            double x1 = pos1.x - cameraPos.x;
            double y1 = pos1.y - cameraPos.y;
            double z1 = pos1.z - cameraPos.z;

            double x2 = pos2.x - cameraPos.x;
            double y2 = pos2.y - cameraPos.y;
            double z2 = pos2.z - cameraPos.z;

            // Use per-connection properties if set (-1 means use default)
            Color connColour = conn.lineColour >= 0 ? new Color(conn.lineColour) : defaultLineColour;
            float r = conn.lineColour >= 0 ? connColour.getRed() / 255f : defaultR;
            float g = conn.lineColour >= 0 ? connColour.getGreen() / 255f : defaultG;
            float b = conn.lineColour >= 0 ? connColour.getBlue() / 255f : defaultB;
            float a = conn.lineOpacity >= 0 ? conn.lineOpacity : defaultA;
            float thickness = conn.lineThickness >= 0 ? conn.lineThickness : defaultThickness;

            boolean isSelected = BoshysBTEUtils.selectedConnections.contains(conn);
            float renderR = r;
            float renderG = g;
            float renderB = b;
            float renderA = a;
            float renderThickness = thickness * 0.03f; // Scale to reasonable size

            if (isSelected) {
                // Highlight selected connections
                renderR = Math.min(1.0f, r + 0.3f);
                renderG = Math.min(1.0f, g + 0.3f);
                renderB = Math.min(1.0f, b + 0.3f);
                renderThickness *= 1.5f;
            }

            renderLineAsRect(buffer, matrices, x1, y1, z1, x2, y2, z2,
                    renderR, renderG, renderB, renderA, renderThickness);
        }
    }

    /**
     * Renders a line as a long, thin rectangular prism (like a stretched marker).
     * The rectangle is oriented along the line direction and has a square cross-section.
     * This ensures the line always looks correct regardless of viewing angle or length.
     */
    private static void renderLineAsRect(VertexConsumer buffer, MatrixStack matrices,
                                         double x1, double y1, double z1,
                                         double x2, double y2, double z2,
                                         float r, float g, float b, float a, float thickness) {

        MatrixStack.Entry entry = matrices.peek();

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length == 0) return;

        // Normalize direction
        double nx = dx / length;
        double ny = dy / length;
        double nz = dz / length;

        // Find perpendicular vectors for the cross-section
        // Pick an arbitrary vector not parallel to the line direction
        double arbitraryX = 0, arbitraryY = 1, arbitraryZ = 0;
        if (Math.abs(ny) > 0.99) {
            arbitraryY = 0;
            arbitraryZ = 1;
        }

        // First perpendicular (cross product with arbitrary)
        double perp1X = ny * arbitraryZ - nz * arbitraryY;
        double perp1Y = nz * arbitraryX - nx * arbitraryZ;
        double perp1Z = nx * arbitraryY - ny * arbitraryX;
        double perp1Len = Math.sqrt(perp1X * perp1X + perp1Y * perp1Y + perp1Z * perp1Z);
        perp1X /= perp1Len;
        perp1Y /= perp1Len;
        perp1Z /= perp1Len;

        // Second perpendicular (cross product of direction and first perpendicular)
        double perp2X = ny * perp1Z - nz * perp1Y;
        double perp2Y = nz * perp1X - nx * perp1Z;
        double perp2Z = nx * perp1Y - ny * perp1X;

        // Half-thickness offsets
        double hx = perp1X * thickness;
        double hy = perp1Y * thickness;
        double hz = perp1Z * thickness;

        double jx = perp2X * thickness;
        double jy = perp2Y * thickness;
        double jz = perp2Z * thickness;

        // 8 corners of the rectangular prism
        // Start face (4 corners)
        double s0x = x1 + hx + jx, s0y = y1 + hy + jy, s0z = z1 + hz + jz;
        double s1x = x1 + hx - jx, s1y = y1 + hy - jy, s1z = z1 + hz - jz;
        double s2x = x1 - hx - jx, s2y = y1 - hy - jy, s2z = z1 - hz - jz;
        double s3x = x1 - hx + jx, s3y = y1 - hy + jy, s3z = z1 - hz + jz;

        // End face (4 corners)
        double e0x = x2 + hx + jx, e0y = y2 + hy + jy, e0z = z2 + hz + jz;
        double e1x = x2 + hx - jx, e1y = y2 + hy - jy, e1z = z2 + hz - jz;
        double e2x = x2 - hx - jx, e2y = y2 - hy - jy, e2z = z2 - hz - jz;
        double e3x = x2 - hx + jx, e3y = y2 - hy + jy, e3z = z2 - hz + jz;

        // Render 6 faces of the prism
        // Top face (s0-s1-e1-e0)
        quad(buffer, entry, s0x, s0y, s0z, s1x, s1y, s1z, e1x, e1y, e1z, e0x, e0y, e0z, r, g, b, a);
        // Bottom face (s2-s3-e3-e2)
        quad(buffer, entry, s2x, s2y, s2z, s3x, s3y, s3z, e3x, e3y, e3z, e2x, e2y, e2z, r, g, b, a);
        // Side face 1 (s1-s2-e2-e1)
        quad(buffer, entry, s1x, s1y, s1z, s2x, s2y, s2z, e2x, e2y, e2z, e1x, e1y, e1z, r, g, b, a);
        // Side face 2 (s3-s0-e0-e3)
        quad(buffer, entry, s3x, s3y, s3z, s0x, s0y, s0z, e0x, e0y, e0z, e3x, e3y, e3z, r, g, b, a);
        // Start cap (s0-s3-s2-s1)
        quad(buffer, entry, s0x, s0y, s0z, s3x, s3y, s3z, s2x, s2y, s2z, s1x, s1y, s1z, r, g, b, a);
        // End cap (e0-e1-e2-e3)
        quad(buffer, entry, e0x, e0y, e0z, e1x, e1y, e1z, e2x, e2y, e2z, e3x, e3y, e3z, r, g, b, a);
    }

    private static void quad(VertexConsumer buffer, MatrixStack.Entry entry,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4,
                             float r, float g, float b, float a) {
        vertex(buffer, entry, (float)x1, (float)y1, (float)z1, r, g, b, a);
        vertex(buffer, entry, (float)x2, (float)y2, (float)z2, r, g, b, a);
        vertex(buffer, entry, (float)x3, (float)y3, (float)z3, r, g, b, a);
        vertex(buffer, entry, (float)x4, (float)y4, (float)z4, r, g, b, a);
    }

    private static void renderMarkersAndCircles(VertexConsumerProvider consumers, MatrixStack matrices, Vec3d cameraPos) {
        for (MarkerData.TeleportMarker marker : BoshysBTEUtils.markers) {
            double distSq = marker.position.squaredDistanceTo(cameraPos);
            if (distSq > 1024 * 1024) continue;

            // Use per-marker colour if set, otherwise use the marker's own stored colour
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

            VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());

            buildCube(buffer, matrices, scale, r, g, b, a);

            if (isSelected && BoshysBTEUtils.selectedMarkers.size() > 1) {
                renderSelectionRing(buffer, matrices, scale * 1.5f, r, g, b, 0.5f);
            }

            matrices.pop();

            // Render the optional circle using thick line segments
            if (marker.circleRadius > 0) {
                VertexConsumer lineBuffer = consumers.getBuffer(RenderLayer.getDebugQuads());
                // Use per-marker circle properties if set (-1 means use default)
                Color defaultCircleColor = new Color(BoshysBTEUtils.getConfig().circleColour);
                float cr = marker.circleColour >= 0
                        ? new Color(marker.circleColour).getRed() / 255f
                        : defaultCircleColor.getRed() / 255f;
                float cg = marker.circleColour >= 0
                        ? new Color(marker.circleColour).getGreen() / 255f
                        : defaultCircleColor.getGreen() / 255f;
                float cb = marker.circleColour >= 0
                        ? new Color(marker.circleColour).getBlue() / 255f
                        : defaultCircleColor.getBlue() / 255f;
                float ca = marker.circleOpacity >= 0 ? marker.circleOpacity : BoshysBTEUtils.getConfig().circleOpacity;
                float circleThickness = marker.circleThickness >= 0 ? marker.circleThickness : BoshysBTEUtils.getConfig().circleThickness;
                float segmentPercent = marker.circleSegmentPercent >= 0 ? marker.circleSegmentPercent : BoshysBTEUtils.getConfig().circleSegmentPercent;
                renderCircleAsRects(lineBuffer, matrices, x, y, z, marker.circleRadius,
                        cr, cg, cb, ca, circleThickness * 0.03f, segmentPercent);
            }
        }
    }

    /**
     * Renders a horizontal circle as a chain of rectangular segments.
     * Each segment is a small rectangular prism for consistent rendering.
     */
    private static void renderCircleAsRects(VertexConsumer buffer, MatrixStack matrices,
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

            renderLineAsRect(buffer, matrices, x1, cy, z1, x2, cy, z2, r, g, b, a, thickness);
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