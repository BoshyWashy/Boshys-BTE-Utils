package com.boshys.bteutils.overlay;

import com.boshys.bteutils.BoshysBTEUtils;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Map;

public class OverlayRenderer {

    private final OverlayStorage storage;
    private final OverlayTextureManager textureManager;

    public OverlayRenderer(OverlayStorage storage, OverlayTextureManager textureManager) {
        this.storage = storage;
        this.textureManager = textureManager;
    }

    public void render(WorldRenderContext context) {
        Map<String, OverlayData.ImageOverlay> overlays = storage.getLoadedOverlays();
        if (overlays.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        Camera camera = client.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();
        MatrixStack matrices = context.matrices();

        for (OverlayData.ImageOverlay overlay : overlays.values()) {
            if (!overlay.visible) continue;

            Identifier texId = textureManager.getOrLoadTexture(overlay.imageFilename);
            if (texId == null) continue;

            renderOverlay(consumers, matrices, camPos, overlay, texId);

            if (overlay.markersVisible) {
                renderMarkers(consumers, matrices, camPos, overlay);
            }
        }
    }

    private void renderOverlay(VertexConsumerProvider consumers, MatrixStack matrices,
                               Vec3d camPos, OverlayData.ImageOverlay overlay, Identifier texId) {

        double acx = overlay.anchor.x - camPos.x;
        double acy = overlay.anchor.y - camPos.y;
        double acz = overlay.anchor.z - camPos.z;
        double cullDist = 512.0;
        if (acx * acx + acy * acy + acz * acz > cullDist * cullDist) return;

        float[] u = { 0f, 1f, 1f, 0f };
        float[] v = overlay.flipped ? new float[]{ 0f, 0f, 1f, 1f } : new float[]{ 1f, 1f, 0f, 0f };

        RenderLayer layer = RenderLayer.getText(texId);
        VertexConsumer buf = consumers.getBuffer(layer);

        matrices.push();
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f posMatrix = entry.getPositionMatrix();

        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        int overlayUV = OverlayTexture.DEFAULT_UV;

        // Top face
        for (int i = 0; i < 4; i++) {
            double cx = overlay.corners[i].x - camPos.x;
            double cy = overlay.corners[i].y - camPos.y;
            double cz = overlay.corners[i].z - camPos.z;
            buf.vertex(posMatrix, (float) cx, (float) cy + 0.005f, (float) cz)
                    .color(1f, 1f, 1f, 1f)
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
                    .color(1f, 1f, 1f, 1f)
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
        VertexConsumer lineBuf = consumers.getBuffer(RenderLayer.getLines());

        float x1 = (float) start.x;
        float y1 = (float) start.y;
        float z1 = (float) start.z;
        float x2 = (float) (start.x + dir.x);
        float y2 = (float) (start.y + dir.y);
        float z2 = (float) (start.z + dir.z);

        lineBuf.vertex(entry.getPositionMatrix(), x1, y1, z1)
                .color(r, g, b, 1f)
                .normal(entry, (float) dir.x, (float) dir.y, (float) dir.z)
                .overlay(0)
                .light(15728880);
        lineBuf.vertex(entry.getPositionMatrix(), x2, y2, z2)
                .color(r, g, b, 1f)
                .normal(entry, (float) dir.x, (float) dir.y, (float) dir.z)
                .overlay(0)
                .light(15728880);

        // Arrowhead cube
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