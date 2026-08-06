package com.boshys.bteutils.overlay;

import com.boshys.bteutils.BoshysBTEUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.IndexType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * OverlayRenderer for Minecraft 26.2 (Mojang unobfuscated mappings).
 *
 * FIXED VERSION v9:
 * - Deferred fullbright lightmap creation to first render() call
 *   (static DynamicTexture crashed because GPU isn't ready during class loading)
 * - Binds a 1x1 WHITE texture as the lightmap to fix dark tint
 * - Opacity works correctly via vertex color alpha only
 * - Fixed near-plane clipping
 */
public class OverlayRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("BoshysBTEUtils/OverlayRenderer");

    private final OverlayStorage storage;
    private final OverlayTextureManager textureManager;

    private static final RenderPipeline OVERLAY_TEX_PIPELINE;
    private static final RenderPipeline OVERLAY_COLOR_PIPELINE;

    // CRITICAL FIX: Don't create DynamicTexture in static block — GPU isn't ready yet!
    // Instead, create the NativeImage now and register the DynamicTexture lazily.
    private static final NativeImage FULLBRIGHT_LIGHTMAP_IMAGE;
    private static final Identifier FULLBRIGHT_LIGHTMAP_ID;
    private static GpuTextureView fullbrightLightmapView = null;
    private static boolean lightmapRegistered = false;

    static {
        OVERLAY_TEX_PIPELINE = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("boshysbteutils", "pipeline/overlay_tex"))
                        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP)
                        .withPrimitiveTopology(PrimitiveTopology.QUADS)
                        .withDepthStencilState(Optional.empty())
                        .build()
        );

        OVERLAY_COLOR_PIPELINE = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("boshysbteutils", "pipeline/overlay_color"))
                        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                        .withPrimitiveTopology(PrimitiveTopology.QUADS)
                        .withDepthStencilState(Optional.empty())
                        .build()
        );

        // Create the image now, but NOT the DynamicTexture (needs GPU)
        FULLBRIGHT_LIGHTMAP_IMAGE = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        FULLBRIGHT_LIGHTMAP_IMAGE.setPixel(0, 0, 0xFFFFFFFF); // Full white, full alpha
        FULLBRIGHT_LIGHTMAP_ID = Identifier.fromNamespaceAndPath("boshysbteutils", "fullbright_lightmap");
    }

    // ColorModulator alpha is ALWAYS 1.0 — opacity comes from vertex color only
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private final StagedVertexBuffer stagedBuffer;

    private static class OverlayDrawJob {
        final StagedVertexBuffer.Draw draw;
        final Identifier texId;
        final boolean isTextured;

        OverlayDrawJob(StagedVertexBuffer.Draw draw, Identifier texId, boolean isTextured) {
            this.draw = draw;
            this.texId = texId;
            this.isTextured = isTextured;
        }
    }

    private final List<OverlayDrawJob> pendingJobs = new ArrayList<>();

    public OverlayRenderer(OverlayStorage storage, OverlayTextureManager textureManager) {
        this.storage = storage;
        this.textureManager = textureManager;
        this.stagedBuffer = new StagedVertexBuffer(() -> "BoshysBTEUtils Overlay Buffer", RenderType.SMALL_BUFFER_SIZE * 8);
    }

    public void endFrame() {
        stagedBuffer.endFrame();
        pendingJobs.clear();
    }

    public void close() {
        stagedBuffer.close();
    }

    public void render(PoseStack poseStack) {
        Map<String, OverlayData.ImageOverlay> overlays = storage.getLoadedOverlays();
        if (overlays.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;

        // CRITICAL FIX: Register the fullbright lightmap texture lazily on first render.
        // The GPU device is guaranteed to be initialized by now.
        if (!lightmapRegistered) {
            DynamicTexture whiteTexture = new DynamicTexture(() -> "boshysbteutils/fullbright_lightmap", FULLBRIGHT_LIGHTMAP_IMAGE);
            client.getTextureManager().register(FULLBRIGHT_LIGHTMAP_ID, whiteTexture);
            lightmapRegistered = true;
        }

        // Cache the fullbright lightmap view on first use
        if (fullbrightLightmapView == null) {
            fullbrightLightmapView = getTextureView(FULLBRIGHT_LIGHTMAP_ID);
        }

        Camera camera = client.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();

        int renderDistanceChunks = BoshysBTEUtils.getConfig().overlayRenderDistance;
        double cullDist;
        if (renderDistanceChunks < 0) {
            cullDist = client.options.simulationDistance().get() * 16.0;
        } else if (renderDistanceChunks == 0) {
            cullDist = Double.MAX_VALUE;
        } else {
            cullDist = renderDistanceChunks * 16.0;
        }

        // Phase 1: Append ALL draws with camera-relative coordinates
        for (OverlayData.ImageOverlay overlay : overlays.values()) {
            if (!overlay.visible) continue;
            if (storage.getTempHiddenOverlays().contains(OverlayData.toSafeFilename(overlay.displayName))) continue;

            Identifier texId = textureManager.getOrLoadTexture(overlay.imageFilename);
            if (texId == null) continue;

            appendOverlayChunked(poseStack, camPos, overlay, texId, cullDist);

            if (overlay.markersVisible) {
                appendMarkers(poseStack, camPos, overlay);
            }
        }

        if (pendingJobs.isEmpty()) return;

        // Phase 2: Upload ONCE
        stagedBuffer.upload();

        // Phase 3: Execute all draws
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        for (OverlayDrawJob job : pendingJobs) {
            executeDraw(job, client, dynamicTransforms);
        }
    }

    private void appendOverlayChunked(PoseStack poseStack,
                                      Vec3 camPos, OverlayData.ImageOverlay overlay, Identifier texId, double cullDist) {

        double maxEdgeLength = computeMaxEdgeLength(overlay);
        int subdivisions = Math.max(1, (int) Math.ceil(maxEdgeLength / 32.0));

        if (subdivisions <= 1) {
            appendOverlayQuad(poseStack, camPos, overlay, texId, 0, 0, 1, 1, overlay.corners);
            return;
        }

        for (int i = 0; i < subdivisions; i++) {
            for (int j = 0; j < subdivisions; j++) {
                double u0 = (double) i / subdivisions;
                double u1 = (double) (i + 1) / subdivisions;
                double v0 = (double) j / subdivisions;
                double v1 = (double) (j + 1) / subdivisions;

                Vec3[] subCorners = new Vec3[4];
                subCorners[0] = bilinearInterpolate(overlay.corners, u0, v0);
                subCorners[1] = bilinearInterpolate(overlay.corners, u1, v0);
                subCorners[2] = bilinearInterpolate(overlay.corners, u1, v1);
                subCorners[3] = bilinearInterpolate(overlay.corners, u0, v1);

                Vec3 subCenter = subCorners[0].lerp(subCorners[2], 0.5);
                double dx = subCenter.x - camPos.x;
                double dy = subCenter.y - camPos.y;
                double dz = subCenter.z - camPos.z;
                if (dx * dx + dy * dy + dz * dz > cullDist * cullDist) {
                    continue;
                }

                appendOverlayQuad(poseStack, camPos, overlay, texId, (float) u0, (float) v0, (float) u1, (float) v1, subCorners);
            }
        }
    }

    private double computeMaxEdgeLength(OverlayData.ImageOverlay overlay) {
        double maxLen = 0;
        maxLen = Math.max(maxLen, overlay.corners[0].distanceTo(overlay.corners[1]));
        maxLen = Math.max(maxLen, overlay.corners[1].distanceTo(overlay.corners[2]));
        maxLen = Math.max(maxLen, overlay.corners[2].distanceTo(overlay.corners[3]));
        maxLen = Math.max(maxLen, overlay.corners[3].distanceTo(overlay.corners[0]));
        maxLen = Math.max(maxLen, overlay.corners[0].distanceTo(overlay.corners[2]));
        maxLen = Math.max(maxLen, overlay.corners[1].distanceTo(overlay.corners[3]));
        return maxLen;
    }

    private Vec3 bilinearInterpolate(Vec3[] corners, double u, double v) {
        Vec3 top = corners[0].lerp(corners[1], u);
        Vec3 bottom = corners[3].lerp(corners[2], u);
        return top.lerp(bottom, v);
    }

    private void appendOverlayQuad(PoseStack poseStack, Vec3 camPos,
                                   OverlayData.ImageOverlay overlay, Identifier texId,
                                   float u0, float v0, float u1, float v1, Vec3[] corners) {

        RenderPipeline pipeline = OVERLAY_TEX_PIPELINE;
        VertexFormat formatBinding = pipeline.getVertexFormatBinding(0);
        if (formatBinding == null) return;

        PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(formatBinding, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        poseStack.pushPose();
        PoseStack.Pose pose = poseStack.last();
        Matrix4fc posMatrix = pose.pose();

        VertexConsumer builder = stagedBuffer.getVertexBuilder(draw);

        int light = 0xF000F0;
        int overlayUV = OverlayTexture.NO_OVERLAY;
        float opacity = overlay.imageOpacity;

        float[] u = { u0, u1, u1, u0 };
        float[] v = overlay.flipped ? new float[]{ v0, v0, v1, v1 } : new float[]{ v1, v1, v0, v0 };

        float yOffsetTop = 0.1f;
        float yOffsetBottom = -0.1f;

        // Top face (y + offset) - CAMERA-RELATIVE coordinates
        for (int i = 0; i < 4; i++) {
            float cx = (float) (corners[i].x - camPos.x);
            float cy = (float) (corners[i].y - camPos.y);
            float cz = (float) (corners[i].z - camPos.z);
            builder.addVertex(posMatrix, cx, cy + yOffsetTop, cz)
                    .setColor(1f, 1f, 1f, opacity)
                    .setUv(u[i], v[i])
                    .setOverlay(overlayUV)
                    .setLight(light)
                    .setNormal(pose, 0f, 1f, 0f);
        }

        // Bottom face (y - offset) - CAMERA-RELATIVE coordinates
        for (int i = 3; i >= 0; i--) {
            float cx = (float) (corners[i].x - camPos.x);
            float cy = (float) (corners[i].y - camPos.y);
            float cz = (float) (corners[i].z - camPos.z);
            builder.addVertex(posMatrix, cx, cy + yOffsetBottom, cz)
                    .setColor(1f, 1f, 1f, opacity)
                    .setUv(u[3 - i], v[3 - i])
                    .setOverlay(overlayUV)
                    .setLight(light)
                    .setNormal(pose, 0f, -1f, 0f);
        }

        poseStack.popPose();
        pendingJobs.add(new OverlayDrawJob(draw, texId, true));
    }

    private void appendMarkers(PoseStack poseStack, Vec3 camPos, OverlayData.ImageOverlay overlay) {
        boolean isSelectedOverlay = BoshysBTEUtils.selectedOverlayCorner == overlay;

        for (int i = 0; i < 4; i++) {
            boolean selected = isSelectedOverlay && BoshysBTEUtils.selectedCornerIndex == i;
            appendMarker(poseStack, camPos, overlay.corners[i], selected, false);
        }

        boolean anchorSelected = isSelectedOverlay && BoshysBTEUtils.selectedCornerIndex == 4;
        appendMarker(poseStack, camPos, overlay.anchor, anchorSelected, true);
    }

    private void appendMarker(PoseStack poseStack, Vec3 camPos,
                              Vec3 worldPos, boolean selected, boolean isAnchor) {
        float baseScale = isAnchor ? 0.14f : 0.10f;
        float scale = Math.max(baseScale, 0.05f);
        float alpha = selected ? 1.0f : 0.6f;

        poseStack.pushPose();
        poseStack.translate(worldPos.x - camPos.x, worldPos.y - camPos.y, worldPos.z - camPos.z);
        PoseStack.Pose pose = poseStack.last();

        RenderPipeline pipeline = OVERLAY_COLOR_PIPELINE;
        VertexFormat formatBinding = pipeline.getVertexFormatBinding(0);
        if (formatBinding == null) {
            poseStack.popPose();
            return;
        }

        PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(formatBinding, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        VertexConsumer builder = stagedBuffer.getVertexBuilder(draw);
        buildCube(builder, pose, scale, 1f, 1f, 1f, alpha);

        if (selected) {
            renderRing(builder, pose, scale * 1.8f, 1f, 1f, 0.2f, 0.8f);
        }

        poseStack.popPose();
        pendingJobs.add(new OverlayDrawJob(draw, null, false));
    }

    private void executeDraw(OverlayDrawJob job, Minecraft client, GpuBufferSlice dynamicTransforms) {
        StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(job.draw);
        if (info == null) return;

        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        if (colorTexture == null) return;

        RenderPipeline pipeline = job.isTextured ? OVERLAY_TEX_PIPELINE : OVERLAY_COLOR_PIPELINE;

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "BoshysBTEUtils overlay render",
                        colorTexture, Optional.empty(),
                        mainTarget.useDepth ? mainTarget.getDepthTextureView() : null,
                        OptionalDouble.empty())) {

            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            if (job.isTextured && job.texId != null) {
                // Bind overlay texture
                GpuTextureView textureView = getTextureView(job.texId);
                if (textureView != null) {
                    SamplerCache samplerCache = RenderSystem.getSamplerCache();
                    GpuSampler sampler = samplerCache.getSampler(
                            AddressMode.CLAMP_TO_EDGE,
                            AddressMode.CLAMP_TO_EDGE,
                            FilterMode.LINEAR,
                            FilterMode.LINEAR,
                            true);
                    renderPass.bindTexture("Sampler0", textureView, sampler);
                }

                // Bind fullbright white texture as lightmap to prevent dark tint
                if (fullbrightLightmapView != null) {
                    SamplerCache samplerCache = RenderSystem.getSamplerCache();
                    GpuSampler lightmapSampler = samplerCache.getSampler(
                            AddressMode.CLAMP_TO_EDGE,
                            AddressMode.CLAMP_TO_EDGE,
                            FilterMode.NEAREST,
                            FilterMode.NEAREST,
                            true);
                    try {
                        renderPass.bindTexture("Sampler1", fullbrightLightmapView, lightmapSampler);
                    } catch (Exception e1) {
                        try {
                            renderPass.bindTexture("Sampler2", fullbrightLightmapView, lightmapSampler);
                        } catch (Exception e2) {
                            LOGGER.debug("Could not bind fullbright lightmap: {} / {}", e1.getMessage(), e2.getMessage());
                        }
                    }
                }
            }

            renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
            renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());
            renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private void renderRing(VertexConsumer buffer, PoseStack.Pose pose, float radius, float r, float g, float b, float a) {
        int segments = 16;
        float y = 0;
        Matrix4fc matrix = pose.pose();
        for (int i = 0; i < segments; i++) {
            double a1 = (Math.PI * 2 * i) / segments;
            double a2 = (Math.PI * 2 * (i + 1)) / segments;
            float x1 = (float) (Math.cos(a1) * radius);
            float z1 = (float) (Math.sin(a1) * radius);
            float x2 = (float) (Math.cos(a2) * radius);
            float z2 = (float) (Math.sin(a2) * radius);

            buffer.addVertex(matrix, x1, y, z1)
                    .setColor(r, g, b, a)
                    .setLight(15728880)
                    .setOverlay(0)
                    .setNormal(pose, 0, 1, 0);
            buffer.addVertex(matrix, x2, y, z2)
                    .setColor(r, g, b, a)
                    .setLight(15728880)
                    .setOverlay(0)
                    .setNormal(pose, 0, 1, 0);
        }
    }

    private void buildCube(VertexConsumer buffer, PoseStack.Pose pose, float scale, float r, float g, float b, float a) {
        Matrix4fc matrix = pose.pose();

        emitFace(buffer, pose, matrix, r, g, b, a,
                new float[]{-scale, -scale, scale}, new float[]{scale, -scale, scale},
                new float[]{scale, scale, scale}, new float[]{-scale, scale, scale});
        emitFace(buffer, pose, matrix, r, g, b, a,
                new float[]{scale, -scale, -scale}, new float[]{-scale, -scale, -scale},
                new float[]{-scale, scale, -scale}, new float[]{scale, scale, -scale});
        emitFace(buffer, pose, matrix, r, g, b, a,
                new float[]{-scale, scale, scale}, new float[]{scale, scale, scale},
                new float[]{scale, scale, -scale}, new float[]{-scale, scale, -scale});
        emitFace(buffer, pose, matrix, r, g, b, a,
                new float[]{-scale, -scale, -scale}, new float[]{scale, -scale, -scale},
                new float[]{scale, -scale, scale}, new float[]{-scale, -scale, scale});
        emitFace(buffer, pose, matrix, r, g, b, a,
                new float[]{scale, -scale, scale}, new float[]{scale, -scale, -scale},
                new float[]{scale, scale, -scale}, new float[]{scale, scale, scale});
        emitFace(buffer, pose, matrix, r, g, b, a,
                new float[]{-scale, -scale, -scale}, new float[]{-scale, -scale, scale},
                new float[]{-scale, scale, scale}, new float[]{-scale, scale, -scale});
    }

    private void emitFace(VertexConsumer buffer, PoseStack.Pose pose, Matrix4fc matrix,
                          float r, float g, float b, float a,
                          float[] p0, float[] p1, float[] p2, float[] p3) {
        float[][] pts = {p0, p1, p2, p3};
        for (float[] p : pts) {
            buffer.addVertex(matrix, p[0], p[1], p[2])
                    .setColor(r, g, b, a)
                    .setLight(15728880)
                    .setOverlay(0)
                    .setNormal(pose, 0, 1, 0);
        }
    }

    private GpuTextureView getTextureView(Identifier textureId) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        try {
            AbstractTexture texture = textureManager.getTexture(textureId);
            if (texture == null) return null;

            try {
                java.lang.reflect.Method m = AbstractTexture.class.getMethod("getTextureView");
                return (GpuTextureView) m.invoke(texture);
            } catch (NoSuchMethodException e) {
                for (java.lang.reflect.Method m : texture.getClass().getMethods()) {
                    if (GpuTextureView.class.isAssignableFrom(m.getReturnType())
                            && m.getParameterCount() == 0) {
                        return (GpuTextureView) m.invoke(texture);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get texture view for {}", textureId, e);
        }
        return null;
    }
}