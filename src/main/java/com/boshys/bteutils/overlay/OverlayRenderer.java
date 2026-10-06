package com.boshys.bteutils.overlay;

import com.boshys.bteutils.BoshysBTEUtils;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
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
import org.joml.*;

import java.lang.Math;
import java.util.*;

public class OverlayRenderer {

    public static final Identifier OVERLAY_PHASE = Identifier.fromNamespaceAndPath("boshysbteutils", "overlay");

    // Default density cap: maximum pixels allowed per 1x1 block
    private static final int DEFAULT_MAX_PIXELS_PER_BLOCK = 10;
    private static final double TILE_SIZE_BLOCKS = 16.0;

    private final OverlayStorage storage;
    private final OverlayTextureManager textureManager;

    private static final RenderPipeline OVERLAY_COLOR_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("boshysbteutils", "pipeline/overlay_color"))
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withColorTargetState(0, new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(Optional.of(new DepthStencilState(
                            DepthStencilState.DEFAULT.depthTest(),
                            false
                    )))
                    .build()
    );

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private final StagedVertexBuffer stagedBuffer;
    private StagedVertexBuffer.Draw activeDraw = null;

    private static class CachedMesh {
        final long meshKey;
        final List<MergedQuad> quads;
        final Vec3 normalOffset;

        CachedMesh(long meshKey, List<MergedQuad> quads, Vec3 normalOffset) {
            this.meshKey = meshKey;
            this.quads = quads;
            this.normalOffset = normalOffset;
        }
    }

    private static class MergedQuad {
        final Vec3 wTL, wTR, wBR, wBL;
        final Vec3 wCenter;
        final float r, g, b, a;

        MergedQuad(Vec3 wTL, Vec3 wTR, Vec3 wBR, Vec3 wBL, Vec3 wCenter, float r, float g, float b, float a) {
            this.wTL = wTL;
            this.wTR = wTR;
            this.wBR = wBR;
            this.wBL = wBL;
            this.wCenter = wCenter;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }
    }

    private final Map<String, CachedMesh> meshCache = new HashMap<>();

    private record PixelMarkerState(
            Vec3 pTL, Vec3 pTR, Vec3 pBR, Vec3 pBL,
            Vec3 normalOffset,
            float r, float g, float b, float a,
            double distSq
    ) {}

    public OverlayRenderer(OverlayStorage storage, OverlayTextureManager textureManager) {
        this.storage = storage;
        this.textureManager = textureManager;
        this.stagedBuffer = new StagedVertexBuffer(() -> "BoshysBTEUtils Overlay Pixel Buffer", RenderType.SMALL_BUFFER_SIZE * 16);
    }

    public static void register(OverlayStorage storage, OverlayTextureManager textureManager) {
        OverlayRenderer renderer = new OverlayRenderer(storage, textureManager);
        LevelExtractionEvents.END_EXTRACTION.register(renderer::extract);
        LevelRenderEvents.END_MAIN.register(OVERLAY_PHASE, renderer::render);
    }

    /**
     * Sequence:
     * - dist <= 10.0 blocks: LOD Level 0 (divisor 1.0)
     * - 10.0 < dist <= 17.5: LOD Level 1 (divisor 1.5)
     * - 17.5 < dist <= 25.0: LOD Level 2 (divisor 1.5^2 = 2.25)
     * - Every 7.5 blocks after 10.0, divides by 1.5 again.
     */
    public static int getLodLevel(double dist) {
        if (dist <= 10.0) {
            return 0;
        }
        return (int) Math.floor((dist - 10.0) / 7.5) + 1;
    }

    public static double getLodDivisor(int lodLevel) {
        if (lodLevel <= 0) {
            return 1.0;
        }
        return Math.pow(1.5, lodLevel);
    }

    public void extract(LevelExtractionContext context) {
        activeDraw = null;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;

        Map<String, OverlayData.ImageOverlay> overlays = storage.getLoadedOverlays();
        if (overlays.isEmpty()) return;

        Vec3 camPos = context.levelState().cameraRenderState.pos;

        // Build active camera view frustum for screen visibility culling
        FrustumIntersection frustum = null;
        if (context.levelState() != null && context.levelState().cameraRenderState != null) {
            Matrix4f projection = context.levelState().cameraRenderState.projectionMatrix;
            Matrix4f modelView = new Matrix4f().rotation(new Quaternionf(context.levelState().cameraRenderState.orientation).conjugate());

            if (projection != null) {
                Matrix4f mvp = new Matrix4f(projection).mul(modelView);
                frustum = new FrustumIntersection(mvp);
            }
        }

        int renderDistanceChunks = BoshysBTEUtils.getConfig().overlayRenderDistance;
        if (renderDistanceChunks < 0) {
            renderDistanceChunks = (client.options != null) ? client.options.getEffectiveRenderDistance() : 16;
        }

        double cullDist = (renderDistanceChunks == 0) ? Double.MAX_VALUE : renderDistanceChunks * 16.0;
        double cullDistSq = cullDist * cullDist;

        List<PixelMarkerState> candidatePixels = new ArrayList<>();

        for (Map.Entry<String, OverlayData.ImageOverlay> entry : overlays.entrySet()) {
            String overlayKey = entry.getKey();
            OverlayData.ImageOverlay overlay = entry.getValue();

            if (!overlay.visible) continue;
            if (storage.getTempHiddenOverlays().contains(OverlayData.toSafeFilename(overlay.displayName))) continue;
            if (overlay.corners == null || overlay.corners.length < 4) continue;

            // --- ACCURATE AABB & DISTANCE CULLING ---
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

            for (Vec3 corner : overlay.corners) {
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
                maxZ = Math.max(maxZ, corner.z);
            }

            double distToX = Math.max(0, Math.max(minX - camPos.x, camPos.x - maxX));
            double distToY = Math.max(0, Math.max(minY - camPos.y, camPos.y - maxY));
            double distToZ = Math.max(0, Math.max(minZ - camPos.z, camPos.z - maxZ));
            double overlayClosestDistSq = distToX * distToX + distToY * distToY + distToZ * distToZ;

            if (overlayClosestDistSq > cullDistSq) continue;

            // --- LEVEL 1: OVERLAY FRUSTUM CULLING (Screen Visibility) ---
            if (!isBoxInFrustum(frustum, minX - camPos.x, minY - camPos.y, minZ - camPos.z,
                    maxX - camPos.x, maxY - camPos.y, maxZ - camPos.z)) {
                continue;
            }

            OverlayTextureManager.PixelData pixelData = textureManager.getPixelData(overlay.imageFilename);
            if (pixelData == null || pixelData.pixels == null || pixelData.width <= 0 || pixelData.height <= 0) continue;

            Vec3 c0 = overlay.corners[0];
            double worldWidth = overlay.corners[1].distanceTo(c0);
            double worldHeight = overlay.corners[3].distanceTo(c0);

            int numTilesX = Math.max(1, (int) Math.ceil(worldWidth / TILE_SIZE_BLOCKS));
            int numTilesY = Math.max(1, (int) Math.ceil(worldHeight / TILE_SIZE_BLOCKS));

            long overlayBaseKey = computeMeshKey(overlay, pixelData);

            // Process overlay grid tiles
            for (int ty = 0; ty < numTilesY; ty++) {
                double v0 = (double) ty / numTilesY;
                double v1 = (double) (ty + 1) / numTilesY;

                for (int tx = 0; tx < numTilesX; tx++) {
                    double u0 = (double) tx / numTilesX;
                    double u1 = (double) (tx + 1) / numTilesX;

                    Vec3 t0 = bilinearInterpolate(overlay.corners[0], overlay.corners[1], overlay.corners[2], overlay.corners[3], u0, v0);
                    Vec3 t1 = bilinearInterpolate(overlay.corners[0], overlay.corners[1], overlay.corners[2], overlay.corners[3], u1, v0);
                    Vec3 t2 = bilinearInterpolate(overlay.corners[0], overlay.corners[1], overlay.corners[2], overlay.corners[3], u1, v1);
                    Vec3 t3 = bilinearInterpolate(overlay.corners[0], overlay.corners[1], overlay.corners[2], overlay.corners[3], u0, v1);

                    double tileMinX = Math.min(Math.min(t0.x, t1.x), Math.min(t2.x, t3.x));
                    double tileMaxX = Math.max(Math.max(t0.x, t1.x), Math.max(t2.x, t3.x));
                    double tileMinY = Math.min(Math.min(t0.y, t1.y), Math.min(t2.y, t3.y));
                    double tileMaxY = Math.max(Math.max(t0.y, t1.y), Math.max(t2.y, t3.y));
                    double tileMinZ = Math.min(Math.min(t0.z, t1.z), Math.min(t2.z, t3.z));
                    double tileMaxZ = Math.max(Math.max(t0.z, t1.z), Math.max(t2.z, t3.z));

                    // --- LEVEL 2: SUB-TILE FRUSTUM CULLING ---
                    if (!isBoxInFrustum(frustum, tileMinX - camPos.x, tileMinY - camPos.y, tileMinZ - camPos.z,
                            tileMaxX - camPos.x, tileMaxY - camPos.y, tileMaxZ - camPos.z)) {
                        continue;
                    }

                    // Dynamic distance calculation for tile
                    double tileCenterX = (tileMinX + tileMaxX) * 0.5;
                    double tileCenterY = (tileMinY + tileMaxY) * 0.5;
                    double tileCenterZ = (tileMinZ + tileMaxZ) * 0.5;

                    double tileDistSq = (tileCenterX - camPos.x) * (tileCenterX - camPos.x)
                            + (tileCenterY - camPos.y) * (tileCenterY - camPos.y)
                            + (tileCenterZ - camPos.z) * (tileCenterZ - camPos.z);

                    if (tileDistSq > cullDistSq) continue;

                    double tileDist = Math.sqrt(tileDistSq);
                    int lodLevel = getLodLevel(tileDist);

                    String cacheTileKey = overlayKey + "_t_" + tx + "_" + ty + "_lod_" + lodLevel;

                    CachedMesh cached = meshCache.get(cacheTileKey);

                    if (cached == null || cached.meshKey != overlayBaseKey) {
                        int srcX0 = (int) Math.floor(u0 * pixelData.width);
                        int srcX1 = (int) Math.min(pixelData.width, Math.ceil(u1 * pixelData.width));
                        int srcY0 = (int) Math.floor(v0 * pixelData.height);
                        int srcY1 = (int) Math.min(pixelData.height, Math.ceil(v1 * pixelData.height));

                        int tileW = Math.max(1, srcX1 - srcX0);
                        int tileH = Math.max(1, srcY1 - srcY0);
                        int[] tilePixels = new int[tileW * tileH];

                        for (int py = 0; py < tileH; py++) {
                            int srcY = Math.min(pixelData.height - 1, srcY0 + py);
                            int srcRow = srcY * pixelData.width;
                            int dstRow = py * tileW;
                            for (int px = 0; px < tileW; px++) {
                                int srcX = Math.min(pixelData.width - 1, srcX0 + px);
                                tilePixels[dstRow + px] = pixelData.pixels[srcRow + srcX];
                            }
                        }

                        Vec3[] tileCorners = new Vec3[]{t0, t1, t2, t3};
                        cached = buildGreedyMesh(tileCorners, tileW, tileH, tilePixels, overlay.imageOpacity, lodLevel, overlayBaseKey);
                        meshCache.put(cacheTileKey, cached);
                    }

                    // --- LEVEL 3: PER-QUAD FRUSTUM & DISTANCE QUEUE ---
                    for (MergedQuad quad : cached.quads) {
                        Vec3 relCenter = quad.wCenter.subtract(camPos);
                        double distSq = relCenter.lengthSqr();

                        if (distSq <= cullDistSq) {
                            Vec3 pTL = quad.wTL.subtract(camPos);
                            Vec3 pTR = quad.wTR.subtract(camPos);
                            Vec3 pBR = quad.wBR.subtract(camPos);
                            Vec3 pBL = quad.wBL.subtract(camPos);

                            double qMinX = Math.min(Math.min(pTL.x, pTR.x), Math.min(pBR.x, pBL.x));
                            double qMaxX = Math.max(Math.max(pTL.x, pTR.x), Math.max(pBR.x, pBL.x));
                            double qMinY = Math.min(Math.min(pTL.y, pTR.y), Math.min(pBR.y, pBL.y));
                            double qMaxY = Math.max(Math.max(pTL.y, pTR.y), Math.max(pBR.y, pBL.y));
                            double qMinZ = Math.min(Math.min(pTL.z, pTR.z), Math.min(pBR.z, pBL.z));
                            double qMaxZ = Math.max(Math.max(pTL.z, pTR.z), Math.max(pBR.z, pBL.z));

                            if (isBoxInFrustum(frustum, qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ)) {
                                candidatePixels.add(new PixelMarkerState(
                                        pTL, pTR, pBR, pBL,
                                        cached.normalOffset,
                                        quad.r, quad.g, quad.b, quad.a,
                                        distSq
                                ));
                            }
                        }
                    }
                }
            }
        }

        if (candidatePixels.isEmpty()) return;

        candidatePixels.sort(Comparator.comparingDouble(PixelMarkerState::distSq));

        VertexFormat formatBinding = OVERLAY_COLOR_PIPELINE.getVertexFormatBinding(0);
        if (formatBinding == null) return;

        PrimitiveTopology primitive = OVERLAY_COLOR_PIPELINE.getPrimitiveTopology();

        StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(formatBinding, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        VertexConsumer builder = stagedBuffer.getVertexBuilder(draw);

        for (PixelMarkerState pixel : candidatePixels) {
            buildPixelQuad(builder, pixel.pTL, pixel.pTR, pixel.pBR, pixel.pBL, pixel.normalOffset, pixel.r, pixel.g, pixel.b, pixel.a);
        }

        stagedBuffer.upload();
        activeDraw = draw;
    }

    private static boolean isBoxInFrustum(FrustumIntersection frustum, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (frustum == null) return true;
        float fMinX = (float) Math.min(minX, maxX);
        float fMaxX = (float) Math.max(minX, maxX);
        float fMinY = (float) Math.min(minY, maxY);
        float fMaxY = (float) Math.max(minY, maxY);
        float fMinZ = (float) Math.min(minZ, maxZ);
        float fMaxZ = (float) Math.max(minZ, maxZ);
        return frustum.testAab(fMinX, fMinY, fMinZ, fMaxX, fMaxY, fMaxZ);
    }

    private static Vec3 bilinearInterpolate(Vec3 c0, Vec3 c1, Vec3 c2, Vec3 c3, double u, double v) {
        double topX = c0.x + u * (c1.x - c0.x);
        double topY = c0.y + u * (c1.y - c0.y);
        double topZ = c0.z + u * (c1.z - c0.z);

        double botX = c3.x + u * (c2.x - c3.x);
        double botY = c3.y + u * (c2.y - c3.y);
        double botZ = c3.z + u * (c2.z - c3.z);

        return new Vec3(
                topX + v * (botX - topX),
                topY + v * (botY - topY),
                topZ + v * (botZ - topZ)
        );
    }

    private static CachedMesh buildGreedyMesh(Vec3[] tileCorners, int tileW, int tileH, int[] tilePixels, float overlayOpacity, int lodLevel, long meshKey) {
        Vec3 c0 = tileCorners[0];

        // 1. Calculate physical world dimensions in blocks (meters)
        double worldWidth = tileCorners[1].distanceTo(c0);
        double worldHeight = tileCorners[3].distanceTo(c0);

        // 2. Compute effective resolution scaling based on Dynamic LOD factor
        double lodDivisor = getLodDivisor(lodLevel);
        double effectivePixelsPerBlock = Math.max(0.25, DEFAULT_MAX_PIXELS_PER_BLOCK / lodDivisor);

        int targetW = Math.max(1, (int) Math.ceil(worldWidth * effectivePixelsPerBlock));
        int targetH = Math.max(1, (int) Math.ceil(worldHeight * effectivePixelsPerBlock));

        int w = tileW;
        int h = tileH;
        int[] pixels = tilePixels;

        // 3. Downsample pixel grid if original resolution exceeds the per-block threshold
        if (w > targetW || h > targetH) {
            int downW = Math.min(w, targetW);
            int downH = Math.min(h, targetH);
            int[] downscaled = new int[downW * downH];

            for (int dy = 0; dy < downH; dy++) {
                int srcY = (dy * h) / downH;
                int dstRow = dy * downW;
                int srcRow = srcY * w;
                for (int dx = 0; dx < downW; dx++) {
                    int srcX = (dx * w) / downW;
                    downscaled[dstRow + dx] = pixels[srcRow + srcX];
                }
            }

            w = downW;
            h = downH;
            pixels = downscaled;
        }

        Vec3 edge1 = tileCorners[1].subtract(c0);
        Vec3 edge2 = tileCorners[3].subtract(c0);
        Vec3 overlayNormal = edge1.cross(edge2);
        Vec3 normalOffset;
        if (overlayNormal.lengthSqr() > 1e-6) {
            normalOffset = overlayNormal.normalize().scale(0.002);
        } else {
            normalOffset = new Vec3(0, 0.002, 0);
        }

        Vec3 vX = edge1.scale(1.0 / w);
        Vec3 vY = edge2.scale(1.0 / h);

        BitSet visited = new BitSet(w * h);
        List<MergedQuad> quads = new ArrayList<>();

        for (int imgY = 0; imgY < h; imgY++) {
            int rowOffset = imgY * w;
            for (int imgX = 0; imgX < w; imgX++) {
                int idx = rowOffset + imgX;
                if (visited.get(idx)) continue;

                int argb = pixels[idx];
                int alpha = (argb >> 24) & 0xFF;

                if (alpha <= 0 || (alpha / 255.0f) * overlayOpacity <= 0.001f) {
                    visited.set(idx);
                    continue;
                }

                // Expand horizontally
                int rectW = 1;
                while (imgX + rectW < w
                        && !visited.get(rowOffset + imgX + rectW)
                        && pixels[rowOffset + imgX + rectW] == argb) {
                    rectW++;
                }

                // Expand vertically
                int rectH = 1;
                while (imgY + rectH < h) {
                    boolean canExtendRow = true;
                    int nextRowOffset = (imgY + rectH) * w;
                    for (int k = 0; k < rectW; k++) {
                        int checkIdx = nextRowOffset + imgX + k;
                        if (visited.get(checkIdx) || pixels[checkIdx] != argb) {
                            canExtendRow = false;
                            break;
                        }
                    }
                    if (!canExtendRow) break;
                    rectH++;
                }

                // Mark merged region as visited
                for (int dy = 0; dy < rectH; dy++) {
                    int markRowOffset = (imgY + dy) * w;
                    for (int dx = 0; dx < rectW; dx++) {
                        visited.set(markRowOffset + imgX + dx);
                    }
                }

                float r = ((argb >> 16) & 0xFF) / 255.0f;
                float g = ((argb >> 8) & 0xFF) / 255.0f;
                float b = (argb & 0xFF) / 255.0f;
                float a = (alpha / 255.0f) * overlayOpacity;

                Vec3 wTL = c0.add(vX.scale(imgX)).add(vY.scale(imgY));
                Vec3 wTR = c0.add(vX.scale(imgX + rectW)).add(vY.scale(imgY));
                Vec3 wBR = c0.add(vX.scale(imgX + rectW)).add(vY.scale(imgY + rectH));
                Vec3 wBL = c0.add(vX.scale(imgX)).add(vY.scale(imgY + rectH));
                Vec3 wCenter = c0.add(vX.scale(imgX + rectW * 0.5)).add(vY.scale(imgY + rectH * 0.5));

                quads.add(new MergedQuad(wTL, wTR, wBR, wBL, wCenter, r, g, b, a));
            }
        }

        return new CachedMesh(meshKey, quads, normalOffset);
    }

    private static long computeMeshKey(OverlayData.ImageOverlay overlay, OverlayTextureManager.PixelData pixelData) {
        long result = 1;
        if (overlay.corners != null) {
            for (Vec3 c : overlay.corners) {
                long x = Double.doubleToLongBits(c.x);
                long y = Double.doubleToLongBits(c.y);
                long z = Double.doubleToLongBits(c.z);
                result = 31 * result + x;
                result = 31 * result + y;
                result = 31 * result + z;
            }
        }
        result = 31 * result + Float.floatToIntBits(overlay.imageOpacity);
        result = 31 * result + (overlay.imageFilename != null ? overlay.imageFilename.hashCode() : 0);
        if (pixelData != null) {
            result = 31 * result + pixelData.width;
            result = 31 * result + pixelData.height;
            result = 31 * result + System.identityHashCode(pixelData.pixels);
        }
        return result;
    }

    public void render(LevelRenderContext context) {
        if (activeDraw == null) return;

        Minecraft client = Minecraft.getInstance();
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        if (colorTexture == null) return;

        StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(activeDraw);
        if (info != null) {
            draw(client, info, OVERLAY_COLOR_PIPELINE);
        }

        stagedBuffer.endFrame();
        activeDraw = null;
    }

    private static void buildPixelQuad(VertexConsumer builder, Vec3 pTL, Vec3 pTR, Vec3 pBR, Vec3 pBL, Vec3 offset, float r, float g, float b, float a) {
        // Top Face
        builder.addVertex((float) (pTL.x + offset.x), (float) (pTL.y + offset.y), (float) (pTL.z + offset.z)).setColor(r, g, b, a);
        builder.addVertex((float) (pTR.x + offset.x), (float) (pTR.y + offset.y), (float) (pTR.z + offset.z)).setColor(r, g, b, a);
        builder.addVertex((float) (pBR.x + offset.x), (float) (pBR.y + offset.y), (float) (pBR.z + offset.z)).setColor(r, g, b, a);
        builder.addVertex((float) (pBL.x + offset.x), (float) (pBL.y + offset.y), (float) (pBL.z + offset.z)).setColor(r, g, b, a);

        // Bottom Face
        builder.addVertex((float) (pBL.x - offset.x), (float) (pBL.y - offset.y), (float) (pBL.z - offset.z)).setColor(r, g, b, a);
        builder.addVertex((float) (pBR.x - offset.x), (float) (pBR.y - offset.y), (float) (pBR.z - offset.z)).setColor(r, g, b, a);
        builder.addVertex((float) (pTR.x - offset.x), (float) (pTR.y - offset.y), (float) (pTR.z - offset.z)).setColor(r, g, b, a);
        builder.addVertex((float) (pTL.x - offset.x), (float) (pTL.y - offset.y), (float) (pTL.z - offset.z)).setColor(r, g, b, a);
    }

    private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();

        if (colorTexture == null) return;

        CompiledRenderPipeline compiledPipeline = RenderSystem.getCompiledPipelineNullable(pipeline);
        if (compiledPipeline == null) return;

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "BTE Overlay Pixels Rendering", colorTexture, Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {

            renderPass.setPipeline(compiledPipeline);

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
            renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());

            renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    public void close() {
        stagedBuffer.close();
        meshCache.clear();
    }
}