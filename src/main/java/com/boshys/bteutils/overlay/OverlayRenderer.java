package com.boshys.bteutils.overlay;

import com.boshys.bteutils.BoshysBTEUtils;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OverlayRenderer {

    private final OverlayStorage storage;
    private final OverlayTextureManager textureManager;

    public OverlayRenderer(OverlayStorage storage, OverlayTextureManager textureManager) {
        this.storage = storage;
        this.textureManager = textureManager;
    }

    /**
     * Gets a VertexConsumerProvider that is safe to use for rendering.
     * In 1.21.10, context.consumers() is always available during AFTER_ENTITIES.
     * In 1.21.11, context.consumers() may be null in certain render passes.
     * This method falls back to the entity vertex consumers from Minecraft's
     * buffer builder storage, which is always available.
     */
    private VertexConsumerProvider getSafeConsumers(WorldRenderContext context) {
        VertexConsumerProvider consumers = context.consumers();
        if (consumers != null) {
            return consumers;
        }
        // Fallback for 1.21.11+ where context.consumers() can be null
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getBufferBuilders() != null) {
            return client.getBufferBuilders().getEntityVertexConsumers();
        }
        return null;
    }

    public void render(WorldRenderContext context) {
        Map<String, OverlayData.ImageOverlay> overlays = storage.getLoadedOverlays();
        if (overlays.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        VertexConsumerProvider consumers = getSafeConsumers(context);
        if (consumers == null) return;

        Camera camera = client.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();
        MatrixStack matrices = context.matrices();

        // Get the configured render distance in blocks (chunks * 16)
        // -1 = use Minecraft's simulation distance (default)
        //  0 = unlimited (always render)
        //  1-64 = custom chunk distance
        int renderDistanceChunks = BoshysBTEUtils.getConfig().overlayRenderDistance;
        double cullDist;
        if (renderDistanceChunks < 0) {
            // Use Minecraft's simulation distance
            cullDist = MinecraftClient.getInstance().options.getSimulationDistance().getValue() * 16.0;
        } else if (renderDistanceChunks == 0) {
            // Unlimited render distance
            cullDist = Double.MAX_VALUE;
        } else {
            cullDist = renderDistanceChunks * 16.0;
        }

        for (OverlayData.ImageOverlay overlay : overlays.values()) {
            if (!overlay.visible) continue;
            if (storage.getTempHiddenOverlays().contains(OverlayData.toSafeFilename(overlay.displayName))) continue;

            Identifier texId = textureManager.getOrLoadTexture(overlay.imageFilename);
            if (texId == null) continue;

            // Render the overlay using chunk subdivision.
            // Each sub-quad is culled individually based on its center point.
            // No early whole-overlay culling — sub-quads near the player will render
            // even if the overlay's anchor is far away.
            renderOverlayChunked(consumers, matrices, camPos, overlay, texId, cullDist);

            if (overlay.markersVisible) {
                renderMarkers(consumers, matrices, camPos, overlay);
            }
        }
    }



    /**
     * Renders the overlay subdivided into chunk-sized pieces to prevent fog/unloading issues.
     * Each sub-quad is rendered independently so the renderer culls individual pieces
     * rather than the whole overlay.
     *
     * Culling is based on the CENTER of each sub-quad (chunk cross-section center),
     * not the corners. This ensures a sub-quad is rendered if its center is in range,
     * which provides smooth fading at the edges of render distance.
     */
    private void renderOverlayChunked(VertexConsumerProvider consumers, MatrixStack matrices,
                                      Vec3d camPos, OverlayData.ImageOverlay overlay, Identifier texId, double cullDist) {

        // Determine subdivision count based on overlay size
        // We subdivide so that no single quad is larger than ~32 blocks
        // This prevents the quad from being affected by chunk culling/fog
        double maxEdgeLength = computeMaxEdgeLength(overlay);
        int subdivisions = Math.max(1, (int) Math.ceil(maxEdgeLength / 32.0));

        if (subdivisions <= 1) {
            // Small overlay - render as single quad
            renderOverlayQuad(consumers, matrices, camPos, overlay, texId, 0, 0, 1, 1, 0, 0, 1, 1);
            return;
        }

        // Large overlay - subdivide into smaller quads
        for (int i = 0; i < subdivisions; i++) {
            for (int j = 0; j < subdivisions; j++) {
                double u0 = (double) i / subdivisions;
                double u1 = (double) (i + 1) / subdivisions;
                double v0 = (double) j / subdivisions;
                double v1 = (double) (j + 1) / subdivisions;

                // Compute the 4 corners of this sub-quad by bilinear interpolation
                Vec3d[] subCorners = new Vec3d[4];
                subCorners[0] = bilinearInterpolate(overlay.corners, u0, v0); // NW
                subCorners[1] = bilinearInterpolate(overlay.corners, u1, v0); // NE
                subCorners[2] = bilinearInterpolate(overlay.corners, u1, v1); // SE
                subCorners[3] = bilinearInterpolate(overlay.corners, u0, v1); // SW

                // Cull based on the CENTER of this sub-quad (cross-section center)
                // This is more accurate than corner-checking for large subdivided overlays
                Vec3d subCenter = subCorners[0].lerp(subCorners[2], 0.5); // diagonal center
                double dx = subCenter.x - camPos.x;
                double dy = subCenter.y - camPos.y;
                double dz = subCenter.z - camPos.z;
                if (dx * dx + dy * dy + dz * dz > cullDist * cullDist) {
                    continue; // Skip this sub-quad - its center is out of range
                }

                // Render this sub-quad with the appropriate UV mapping
                renderSubQuad(consumers, matrices, camPos, subCorners, texId,
                        (float) u0, (float) v0, (float) u1, (float) v1, overlay);
            }
        }
    }

    /**
     * Computes the maximum edge length of the overlay to determine subdivision count.
     */
    private double computeMaxEdgeLength(OverlayData.ImageOverlay overlay) {
        double maxLen = 0;
        // Check the 4 edges
        maxLen = Math.max(maxLen, overlay.corners[0].distanceTo(overlay.corners[1])); // NW-NE
        maxLen = Math.max(maxLen, overlay.corners[1].distanceTo(overlay.corners[2])); // NE-SE
        maxLen = Math.max(maxLen, overlay.corners[2].distanceTo(overlay.corners[3])); // SE-SW
        maxLen = Math.max(maxLen, overlay.corners[3].distanceTo(overlay.corners[0])); // SW-NW
        // Also check diagonals
        maxLen = Math.max(maxLen, overlay.corners[0].distanceTo(overlay.corners[2]));
        maxLen = Math.max(maxLen, overlay.corners[1].distanceTo(overlay.corners[3]));
        return maxLen;
    }

    /**
     * Bilinear interpolation on a quad. Given u,v in [0,1], computes the world position.
     * u goes from west (0) to east (1), v goes from north (0) to south (1).
     */
    private Vec3d bilinearInterpolate(Vec3d[] corners, double u, double v) {
        // corners: 0=NW, 1=NE, 2=SE, 3=SW
        Vec3d top = corners[0].lerp(corners[1], u);      // Lerp along top edge
        Vec3d bottom = corners[3].lerp(corners[2], u);    // Lerp along bottom edge
        return top.lerp(bottom, v);                        // Lerp between top and bottom
    }

    /**
     * Renders a single sub-quad of the overlay.
     */
    private void renderSubQuad(VertexConsumerProvider consumers, MatrixStack matrices, Vec3d camPos,
                               Vec3d[] subCorners, Identifier texId,
                               float u0, float v0, float u1, float v1,
                               OverlayData.ImageOverlay overlay) {

        float[] u = { u0, u1, u1, u0 };
        float[] v = overlay.flipped ? new float[]{ v0, v0, v1, v1 } : new float[]{ v1, v1, v0, v0 };

        RenderLayer layer = RenderLayer.getText(texId);
        VertexConsumer buf = consumers.getBuffer(layer);

        matrices.push();
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f posMatrix = entry.getPositionMatrix();

        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        int overlayUV = OverlayTexture.DEFAULT_UV;
        float opacity = overlay.imageOpacity;

        // Top face
        for (int i = 0; i < 4; i++) {
            double cx = subCorners[i].x - camPos.x;
            double cy = subCorners[i].y - camPos.y;
            double cz = subCorners[i].z - camPos.z;
            buf.vertex(posMatrix, (float) cx, (float) cy + 0.005f, (float) cz)
                    .color(1f, 1f, 1f, opacity)
                    .texture(u[i], v[i])
                    .overlay(overlayUV)
                    .light(light)
                    .normal(entry, 0f, 1f, 0f);
        }

        // Bottom face
        for (int i = 0; i < 4; i++) {
            int idx = 3 - i;
            double cx = subCorners[idx].x - camPos.x;
            double cy = subCorners[idx].y - camPos.y;
            double cz = subCorners[idx].z - camPos.z;
            buf.vertex(posMatrix, (float) cx, (float) cy - 0.005f, (float) cz)
                    .color(1f, 1f, 1f, opacity)
                    .texture(u[i], v[i])
                    .overlay(overlayUV)
                    .light(light)
                    .normal(entry, 0f, -1f, 0f);
        }

        matrices.pop();
    }

    /**
     * Legacy single-quad render for small overlays.
     */
    private void renderOverlayQuad(VertexConsumerProvider consumers, MatrixStack matrices,
                                   Vec3d camPos, OverlayData.ImageOverlay overlay, Identifier texId,
                                   float u0, float v0, float u1, float v1, float uvU0, float uvV0, float uvU1, float uvV1) {

        float[] u = { u0, u1, u1, u0 };
        float[] v = overlay.flipped ? new float[]{ uvV0, uvV0, uvV1, uvV1 } : new float[]{ uvV1, uvV1, uvV0, uvV0 };

        RenderLayer layer = RenderLayer.getText(texId);
        VertexConsumer buf = consumers.getBuffer(layer);

        matrices.push();
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f posMatrix = entry.getPositionMatrix();

        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        int overlayUV = OverlayTexture.DEFAULT_UV;
        float opacity = overlay.imageOpacity;

        // Top face
        for (int i = 0; i < 4; i++) {
            double cx = overlay.corners[i].x - camPos.x;
            double cy = overlay.corners[i].y - camPos.y;
            double cz = overlay.corners[i].z - camPos.z;
            buf.vertex(posMatrix, (float) cx, (float) cy + 0.005f, (float) cz)
                    .color(1f, 1f, 1f, opacity)
                    .texture(u[i], v[i])
                    .overlay(overlayUV)
                    .light(light)
                    .normal(entry, 0f, 1f, 0f);
        }

        // Bottom face
        for (int i = 0; i < 4; i++) {
            int idx = 3 - i;
            double cx = overlay.corners[idx].x - camPos.x;
            double cy = overlay.corners[idx].y - camPos.y;
            double cz = overlay.corners[idx].z - camPos.z;
            buf.vertex(posMatrix, (float) cx, (float) cy - 0.005f, (float) cz)
                    .color(1f, 1f, 1f, opacity)
                    .texture(u[i], v[i])
                    .overlay(overlayUV)
                    .light(light)
                    .normal(entry, 0f, -1f, 0f);
        }

        matrices.pop();
    }

    private void renderMarkers(VertexConsumerProvider consumers, MatrixStack matrices, Vec3d camPos, OverlayData.ImageOverlay overlay) {
        boolean isSelectedOverlay = BoshysBTEUtils.selectedOverlayCorner == overlay;

        for (int i = 0; i < 4; i++) {
            boolean selected = isSelectedOverlay && BoshysBTEUtils.selectedCornerIndex == i;
            renderMarker(consumers, matrices, camPos, overlay.corners[i], selected, false);
        }

        boolean anchorSelected = isSelectedOverlay && BoshysBTEUtils.selectedCornerIndex == 4;
        renderMarker(consumers, matrices, camPos, overlay.anchor, anchorSelected, true);
    }

    private void renderMarker(VertexConsumerProvider consumers, MatrixStack matrices, Vec3d camPos,
                              Vec3d worldPos, boolean selected, boolean isAnchor) {
        double x = worldPos.x - camPos.x;
        double y = worldPos.y - camPos.y;
        double z = worldPos.z - camPos.z;

        float scale = isAnchor ? 0.14f : 0.10f;
        float alpha = selected ? 1.0f : 0.6f;

        matrices.push();
        matrices.translate(x, y, z);

        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
        MatrixStack.Entry entry = matrices.peek();

        buildCube(buffer, entry, scale, 1f, 1f, 1f, alpha);

        if (selected) {
            renderRing(buffer, entry, scale * 1.8f, 1f, 1f, 0.2f, 0.8f);
            renderArrows(consumers, matrices, entry);
        }

        matrices.pop();
    }

    private void renderArrows(VertexConsumerProvider consumers, MatrixStack matrices, MatrixStack.Entry entry) {
        double len = 1.2;
        double startGap = 0.3;

        // +X (red)
        renderArrow(consumers, matrices, entry, new Vec3d(startGap, 0, 0), new Vec3d(len, 0, 0), 1f, 0.2f, 0.2f);
        // -X (red)
        renderArrow(consumers, matrices, entry, new Vec3d(-startGap, 0, 0), new Vec3d(-len, 0, 0), 1f, 0.2f, 0.2f);
        // +Z (blue)
        renderArrow(consumers, matrices, entry, new Vec3d(0, 0, startGap), new Vec3d(0, 0, len), 0.2f, 0.2f, 1f);
        // -Z (blue)
        renderArrow(consumers, matrices, entry, new Vec3d(0, 0, -startGap), new Vec3d(0, 0, -len), 0.2f, 0.2f, 1f);
    }

    private void renderArrow(VertexConsumerProvider consumers, MatrixStack matrices, MatrixStack.Entry entry,
                             Vec3d start, Vec3d dir, float r, float g, float b) {
        // Arrowhead cube only — lines removed
        matrices.push();
        matrices.translate(start.x + dir.x, start.y + dir.y, start.z + dir.z);
        MatrixStack.Entry headEntry = matrices.peek();
        VertexConsumer quadBuf = consumers.getBuffer(RenderLayer.getDebugQuads());
        buildCube(quadBuf, headEntry, 0.06f, r, g, b, 1f);
        matrices.pop();
    }

    private void renderRing(VertexConsumer buffer, MatrixStack.Entry entry, float radius, float r, float g, float b, float a) {
        int segments = 16;
        float y = 0;
        for (int i = 0; i < segments; i++) {
            double a1 = (Math.PI * 2 * i) / segments;
            double a2 = (Math.PI * 2 * (i + 1)) / segments;
            float x1 = (float) (Math.cos(a1) * radius);
            float z1 = (float) (Math.sin(a1) * radius);
            float x2 = (float) (Math.cos(a2) * radius);
            float z2 = (float) (Math.sin(a2) * radius);

            buffer.vertex(entry.getPositionMatrix(), x1, y, z1)
                    .color(r, g, b, a)
                    .light(15728880)
                    .overlay(0)
                    .normal(entry, 0, 1, 0);
            buffer.vertex(entry.getPositionMatrix(), x2, y, z2)
                    .color(r, g, b, a)
                    .light(15728880)
                    .overlay(0)
                    .normal(entry, 0, 1, 0);
        }
    }

    private void buildCube(VertexConsumer buffer, MatrixStack.Entry entry, float scale, float r, float g, float b, float a) {
        // Front
        vertex(buffer, entry, -scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, scale, r, g, b, a);
        // Back
        vertex(buffer, entry, scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, -scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, -scale, r, g, b, a);
        // Top
        vertex(buffer, entry, -scale, scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, -scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, -scale, r, g, b, a);
        // Bottom
        vertex(buffer, entry, -scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, -scale, -scale, scale, r, g, b, a);
        // Right
        vertex(buffer, entry, scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, -scale, r, g, b, a);
        vertex(buffer, entry, scale, scale, scale, r, g, b, a);
        // Left
        vertex(buffer, entry, -scale, -scale, -scale, r, g, b, a);
        vertex(buffer, entry, -scale, -scale, scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, scale, r, g, b, a);
        vertex(buffer, entry, -scale, scale, -scale, r, g, b, a);
    }

    private void vertex(VertexConsumer buffer, MatrixStack.Entry entry, float x, float y, float z, float r, float g, float b, float a) {
        buffer.vertex(entry.getPositionMatrix(), x, y, z).color(r, g, b, a).light(15728880).overlay(0).normal(entry, 0, 1, 0);
    }
}