package eu.pb4.softwaregl.blaze3d;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import eu.pb4.softwaregl.RGBA;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import org.joml.*;
import org.jspecify.annotations.Nullable;

import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

public class SoftRenderPass implements RenderPassBackend {
    private final SoftCommandEncoder encoder;
    private final SoftTextureView colorTexture;
    private final @Nullable SoftTextureView depthTexture;
    private final Map<String, SampledTexture> binds = new HashMap<>();
    private final Map<String, Std140Reader> uniforms = new HashMap<>();
    private final ByteBuffer[] vertexBuffers = new ByteBuffer[1];
    private final Scissor defaultScissor;
    private boolean closed;
    private RenderPipeline pipeline;
    private ByteBuffer indexBuffer;
    private VertexFormat.IndexType indexType = VertexFormat.IndexType.INT;
    private Scissor scissor;
    private DepthTestPredicate depthTest;
    private ColorBlender blender;
    private boolean writeDepth;
    private boolean limitDepth;

    public SoftRenderPass(SoftCommandEncoder encoder, String label, SoftTextureView colorTexture, @Nullable SoftTextureView depthTexture) {
        this.encoder = encoder;
        this.colorTexture = colorTexture;
        this.defaultScissor = new Scissor(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0));
        this.scissor = defaultScissor;
        this.depthTexture = depthTexture;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {

    }

    @Override
    public void popDebugGroup() {

    }

    @Override
    public void setPipeline(RenderPipeline pipeline) {
        this.pipeline = pipeline;

        var depthStencilState = pipeline.getDepthStencilState();
        this.writeDepth = depthStencilState != null && depthStencilState.writeDepth();
        this.limitDepth = depthStencilState != null && depthStencilState.depthTest() != CompareOp.ALWAYS_PASS;
        this.depthTest = depthStencilState != null ? switch (depthStencilState.depthTest()) {
            case ALWAYS_PASS -> (image, drawn) -> true;
            case EQUAL -> (image, drawn) -> image == drawn;
            case NOT_EQUAL -> (image, drawn) -> image != drawn;
            case LESS_THAN_OR_EQUAL -> (image, drawn) -> image >= drawn;
            case LESS_THAN ->(image, drawn) -> image > drawn;
            case GREATER_THAN -> (image, drawn) -> image < drawn;
            case GREATER_THAN_OR_EQUAL -> (image, drawn) -> image <= drawn;
            case NEVER_PASS -> (image, drawn) -> false;
        } : (a, b) -> true;

        var colorState = pipeline.getColorTargetState();
        if (pipeline == RenderPipelines.SOLID_TERRAIN) {
            this.blender = ColorBlender.SOLID;
        } else if (colorState.blendFunction().isEmpty()) {
            this.blender = ColorBlender.CUTOUT;
        } else if (colorState.blendFunction().get() == BlendFunction.INVERT) {
            this.blender = ColorBlender.INVERT;
        } else if (colorState.blendFunction().get() == BlendFunction.ADDITIVE) {
            this.blender = ColorBlender.ADDITIVE;
        } else if (colorState.blendFunction().get() == BlendFunction.OVERLAY) {
            this.blender = ColorBlender.OVERLAY;
        } else if (colorState.blendFunction().get() == BlendFunction.GLINT) {
            this.blender = ColorBlender.MULTIPLY;
        } else {
            this.blender = ColorBlender.ALPHA_BLEND;
        }
    }

    @Override
    public void bindTexture(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
        this.binds.put(name, new SampledTexture((SoftTextureView) textureView, (SoftSampler) sampler));
    }

    @Override
    public void setUniform(String name, GpuBuffer value) {
        this.uniforms.put(name, Std140Reader.wrap(((SoftBuffer) value).data()));
    }

    @Override
    public void setUniform(String name, GpuBufferSlice value) {
        var buf = ((SoftBuffer) value.buffer()).data().slice((int) value.offset(), (int) value.length());
        buf.order(ByteOrder.LITTLE_ENDIAN);
        this.uniforms.put(name, Std140Reader.wrap(buf));
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        this.scissor = new Scissor(x, y, x + width, y + height);
    }

    @Override
    public void disableScissor() {
        this.scissor = this.defaultScissor;
    }

    @Override
    public void setVertexBuffer(int slot, GpuBuffer vertexBuffer) {
        this.vertexBuffers[slot] = ((SoftBuffer) vertexBuffer).data();
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
        this.indexBuffer = ((SoftBuffer) indexBuffer).data();
        this.indexType = indexType;
    }

    @Override
    public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        try {
            executeDraw(this.vertexBuffers[0], this.indexBuffer, baseVertex, firstIndex, indexCount, this.indexType, instanceCount, this.uniforms);
        } catch (Throwable e) {
            System.currentTimeMillis();
            // Suffering
        }
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, VertexFormat.@Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T uniformArgument) {
        try {
            for (var draw : draws) {
                var indexBuf = draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer;
                var indexType = draw.indexType() != null ? draw.indexType() : defaultIndexType;
                var x = new HashMap<>(this.uniforms);

                draw.uniformUploaderConsumer().accept(uniformArgument, (name, buffer) -> x.put(name, Std140Reader.wrap(((SoftBuffer) buffer.buffer()).data().slice((int) buffer.offset(), (int) buffer.length()))));
                executeDraw(((SoftBuffer) draw.vertexBuffer()).data(), indexBuf != null ? ((SoftBuffer) indexBuf).data() : null, draw.baseVertex(), draw.firstIndex(), draw.indexCount(), indexType, 1, x);
            }
        } catch (Throwable e) {
            System.currentTimeMillis();
            // Suffering
        }
    }

    @Override
    public void draw(int firstVertex, int vertexCount) {
        try {
            executeDraw(this.vertexBuffers[0], null, firstVertex, 0, vertexCount, null, 1, this.uniforms);
        } catch (Throwable e) {
            System.currentTimeMillis();
            // Pain
        }
    }


    private void executeDraw(ByteBuffer vertexBuffer, @Nullable ByteBuffer indexBuffer, int baseVertex, int firstIndex, int drawCount, VertexFormat.@Nullable IndexType indexType, int instanceCount, Map<String, Std140Reader> uniforms) {
        if (this.pipeline == RenderPipelines.ANIMATE_SPRITE_BLIT && this.uniforms.get("SpriteAnimationInfo") instanceof Std140Reader reader) {
            var output = (SoftTexture) (RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride.texture() : this.colorTexture.texture());
            var halfWidth = output.getWidth(0) / 2;
            var halfHeight = output.getHeight(0) / 2;
            SampledTexture sprite = this.binds.get("Sprite");
            var projMat = reader.getMat4f(new Matrix4f()).mul(reader.getMat4f(new Matrix4f()));
            var xPadding = reader.putFloat();
            var yPadding = reader.putFloat();
            var mipmapLevels = reader.putInt();
            var min = projMat.transformProject(new Vector3f(0)).mul(halfWidth, halfHeight, 1).add(halfWidth, halfHeight, 0);
            var max = projMat.transformProject(new Vector3f(1)).mul(halfWidth, halfHeight, 1).add(halfWidth, halfHeight, 0);


            int minX = (int) Mth.clamp(min.x, 0, halfWidth * 2 - 1);
            int minY = (int) Mth.clamp(min.y, 0, halfHeight * 2 - 1);

            int maxX = (int) Mth.clamp(max.x, 0, halfWidth * 2 - 1);
            int maxY = (int) Mth.clamp(max.y, 0, halfHeight * 2 - 1);

            var lengthX = (maxX - minX);
            var lengthY = (maxY - minY);

            var spriteWidth = sprite.texture.getWidth(0);
            var spriteHeight = sprite.texture.getHeight(0);

            var margin = (lengthX - spriteWidth) / 2;

            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    output.setRGBA(0, x, y, sprite.sample(
                            (float) (x - minX - margin) / spriteWidth,
                            (float) (y - minY - margin) / spriteHeight,
                            0
                    ));
                }
            }

            return;
        } else if (this.pipeline == RenderPipelines.ANIMATE_SPRITE_INTERPOLATE) {
            return;
        } else if (this.pipeline == RenderPipelines.LIGHTMAP && this.uniforms.get("LightmapInfo") instanceof Std140Reader reader) {
            var output = (SoftTexture) (RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride.texture() : this.colorTexture.texture());
            float skyFactor = reader.putFloat();
            float blockFactor = reader.putFloat();
            float NightVisionFactor = reader.putFloat();
            float DarknessScale = reader.putFloat();
            float BossOverlayWorldDarkeningFactor = reader.putFloat();
            float BrightnessFactor = reader.putFloat();
            var blockLightTint = reader.getVec3();
            var skyLightColor = reader.getVec3();
            var AmbientColor = reader.getVec3();
            var NightVisionColor = reader.getVec3();
            reader.reset();

            var color = new Vector3f();
            var tmp = new Vector3f();

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    tmp.set(0);
                    color.set(0);
                    float blockLevel = x / 15f;
                    float skyLevel = y / 15f;

                    var blockBrightness = blockFactor * blockLevel / (4.0f - 3.0f * blockLevel);
                    var skyBrightness = skyFactor * skyLevel / (4.0f - 3.0f * skyLevel);

                    // Add sky light
                    color.add(tmp.set(skyLightColor).mul(skyBrightness));

                    // Add block light
                    var parabolicFactor = 0.9f * (2.0f * blockLevel - 1.0f) * (2.0f * blockLevel - 1.0f);
                    tmp.set(blockLightTint).lerp(new Vector3f(1), parabolicFactor);
                    color.add(tmp.mul(blockBrightness));

                    output.setRGBA(0, x, y, RGBA.colorFromFloatRGBA(
                            Mth.clamp(color.x, 0, 1),
                            Mth.clamp(color.y, 0, 1),
                            Mth.clamp(color.z, 0, 1),
                            1
                    ));
                }
            }
            return;
        } else if (this.pipeline == RenderPipelines.VIGNETTE) {
            return;
        }

        var projMat = new Matrix4f();
        var modelViewMat = new Matrix4f();
        var colorModulator = new Vector4f(1, 1, 1, 1);
        var modelOffset = new Vector3f();
        var textureMat = new Matrix4f();
        var cameraBlockPos = new Vector3i();
        var cameraOffset = new Vector3f();

        var position = new Vector3f();
        SampledTexture sampler0 = this.pipeline.getSamplers().contains("Sampler0") ? this.binds.get("Sampler0") : null;
        SampledTexture sampler2 = this.pipeline.getSamplers().contains("Sampler2") ? this.binds.get("Sampler2") : null;

        if (uniforms.get("Globals") instanceof Std140Reader reader) {
            reader.getIVec3(cameraBlockPos);
            reader.getVec3(cameraOffset);
            reader.reset();
        }

        if (uniforms.get("Projection") instanceof Std140Reader reader) {
            reader.getMat4f(projMat);
            reader.reset();
        }

        if (uniforms.get("DynamicTransforms") instanceof Std140Reader reader) {
            reader.getMat4f(modelViewMat);
            reader.getVec4(colorModulator);
            reader.getVec3(modelOffset);
            reader.getMat4f(textureMat);
            reader.reset();
        }

        if (uniforms.get("ChunkSection") instanceof Std140Reader reader) {
            reader.getMat4f(modelViewMat);
            reader.putFloat(); // ChunkVisibility
            reader.getIVec2(new Vector2i()); // Texture Size
            var chunkPos = reader.getIVec3();

            position.set(
                    chunkPos.x - cameraBlockPos.x + cameraOffset.x,
                    chunkPos.y - cameraBlockPos.y + cameraOffset.y,
                    chunkPos.z - cameraBlockPos.z + cameraOffset.z
            );
            reader.reset();
        }

        var colorOutput = (SoftTexture) (RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride.texture() : this.colorTexture.texture());
        var depthOutput = (SoftTexture) (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride.texture() : this.depthTexture != null ? this.depthTexture.texture() : null);

        int width = colorOutput.getWidth(0);
        int height = colorOutput.getHeight(0);

        var halfWidth = width / 2f;
        var halfHeight = height / 2f;

        if (vertexBuffer == null) return;
        var indexStart = indexBuffer != null ? indexBuffer.position() + indexType.bytes * firstIndex : 0;
        var vertInit = vertexBuffer.position();

        var vec = new Vector4f[]{
                new Vector4f(), new Vector4f(), new Vector4f(),
                new Vector4f(), new Vector4f(), new Vector4f(),
                new Vector4f(), new Vector4f(), new Vector4f(),
                new Vector4f(), new Vector4f(), new Vector4f(),
        };

        var uvs = new Vector2f[]{
                new Vector2f(1), new Vector2f(1), new Vector2f(1),
                new Vector2f(1), new Vector2f(1), new Vector2f(1),
                new Vector2f(1), new Vector2f(1), new Vector2f(1),
                new Vector2f(1), new Vector2f(1), new Vector2f(1),
        };

        var colors = new Vector4f[]{
                new Vector4f(colorModulator), new Vector4f(colorModulator), new Vector4f(colorModulator),
                new Vector4f(colorModulator), new Vector4f(colorModulator), new Vector4f(colorModulator),
                new Vector4f(colorModulator), new Vector4f(colorModulator), new Vector4f(colorModulator),
                new Vector4f(colorModulator), new Vector4f(colorModulator), new Vector4f(colorModulator),
        };

        var vertexFormat = this.pipeline.getVertexFormat();

        var vertexLength = vertexFormat.getVertexSize();
        var positionOffset = vertexFormat.getOffset(VertexFormatElement.POSITION);
        var colorOffset = vertexFormat.getOffset(VertexFormatElement.COLOR);
        var uvOffset = vertexFormat.getOffset(VertexFormatElement.UV0);
        var uv2Offset = sampler2 == null ? -1 : vertexFormat.getOffset(VertexFormatElement.UV2);

        var vertexMode = this.pipeline.getVertexFormatMode();

        if (vertexMode != VertexFormat.Mode.QUADS && vertexMode != VertexFormat.Mode.TRIANGLES)
            return;

        var workMat = new Matrix4f();
        workMat.set(projMat).mul(modelViewMat);

        int layer = 0;
        int triangle = 0;
        for (int i = 0; i < drawCount; i += 3) {
            for (int a = 0; a < 3; a++) {
                var id = indexType == null
                        ? i + a + baseVertex
                        : indexType == VertexFormat.IndexType.INT
                        ? indexBuffer.getInt(indexStart + (i + a) * 4) + baseVertex
                        : indexBuffer.getShort(indexStart + (i + a) * 2) + baseVertex;

                var pos = vertInit + id * vertexLength;

                vec[a].set(
                        vertexBuffer.getFloat(pos + positionOffset) + position.x,
                        vertexBuffer.getFloat(pos + positionOffset + 4) + position.y,
                        vertexBuffer.getFloat(pos + positionOffset + 8) + position.z,
                        1
                );

                workMat.transform(vec[a]);
                if (colorOffset != -1) {
                    var color = Integer.reverseBytes(vertexBuffer.getInt(pos + colorOffset));

                    colors[a].set(
                            RGBA.redFloat(color),
                            RGBA.greenFloat(color),
                            RGBA.blueFloat(color),
                            RGBA.alphaFloat(color)
                    ).mul(colorModulator);
                } else {
                    colors[a].set(colorModulator);
                }

                if (uvOffset != -1) {
                    uvs[a].set(vertexBuffer.getFloat(pos + uvOffset), vertexBuffer.getFloat(pos + uvOffset + 4));
                } else if (vertexMode == VertexFormat.Mode.TRIANGLES || (triangle & 1) == 0) {
                    uvs[a].set(a == 2 ? 1 : 0, a == 0 ? 1 : 0);
                } else if ((triangle & 1) == 1) {
                    uvs[a].set(a != 2 ? 1 : 0, a != 0 ? 1 : 0);
                }

                if (uv2Offset != -1) {
                    var u = Mth.clamp(vertexBuffer.getShort(pos + uv2Offset) / 256f + 0.5f / 16f, 0.5f / 16f, 15.5f / 16f);
                    var v = Mth.clamp(vertexBuffer.getShort(pos + uv2Offset + 2)/ 256f + 0.5f / 16f, 0.5f / 16f, 15.5f / 16f);

                    var color = sampler2.sample(u, v, 0);
                    colors[a].mul(RGBA.redFloat(color), RGBA.greenFloat(color), RGBA.blueFloat(color), RGBA.alphaFloat(color));
                }
            }

            int vertStart = 0;
            int actualVertCount = 3;

            var clip0 = vec[0].z < -vec[0].w || vec[0].z > vec[0].w ? 1 : 0;
            var clip1 = vec[1].z < -vec[1].w || vec[1].z > vec[1].w ? 1 : 0;
            var clip2 = vec[2].z < -vec[2].w || vec[2].z > vec[2].w ? 1 : 0;

            var outOfBounds = clip0 + clip1 + clip2;

            // This is broken
            if (outOfBounds == 1) {
                var clipIndex = clip0 == 1 ? 0 : clip1 == 1 ? 1 : 2;
                var clipSpace = vec[clipIndex].z < -vec[clipIndex].w ? -vec[clipIndex].w : vec[clipIndex].w;

                var nextIndex = (clipIndex + 1) % 3;
                var previousIndex = (clipIndex - 1 + 3) % 3;

                var fracNext = (clipSpace - vec[clipIndex].z) / (vec[nextIndex].z - vec[clipIndex].z);
                var fracPrevious = (clipSpace - vec[clipIndex].z) / (vec[previousIndex].z - vec[clipIndex].z);

                lerp(clipIndex, previousIndex, 3, vec, uvs, colors, fracPrevious);
                lerp(clipIndex, nextIndex, 3 + 1, vec, uvs, colors, fracNext);
                copy(previousIndex, 3 + 2, vec, uvs, colors);

                lerp(clipIndex, nextIndex, 6, vec, uvs, colors, fracNext);
                copy(nextIndex, 6 + 1, vec, uvs, colors);
                copy(previousIndex, 6 + 2, vec, uvs, colors);

                colors[3].set(colors[4].set(colors[5].set(1, 0, 0, 1)));
                colors[6].set(colors[7].set(colors[8].set(1, 1, 0, 1)));

                vertStart = 3;
                actualVertCount += 6;

            } else if (outOfBounds == 2) {
                var notClippedIndex = clip0 == 0 ? 0 : clip1 == 0 ? 1 : 2;
                var nextIndex = (notClippedIndex + 1) % 3;
                var previousIndex = (notClippedIndex - 1 + 3) % 3;

                var clipSpaceNext = vec[nextIndex].z < -vec[nextIndex].w ? -vec[nextIndex].w : vec[nextIndex].w;
                var clipSpacePrevious = vec[previousIndex].z < -vec[previousIndex].w ? -vec[previousIndex].w : vec[previousIndex].w;

                var fracNext = (clipSpaceNext - vec[notClippedIndex].z) / (vec[nextIndex].z - vec[notClippedIndex].z);
                var fracPrevious = (clipSpacePrevious - vec[notClippedIndex].z) / (vec[previousIndex].z - vec[notClippedIndex].z);

                lerp(notClippedIndex, previousIndex, 3 + previousIndex, vec, uvs, colors, fracPrevious);
                copy(notClippedIndex, 3 + notClippedIndex, vec, uvs, colors);
                lerp(notClippedIndex, nextIndex, 3 + nextIndex, vec, uvs, colors, fracNext);

                colors[3].set(colors[4].set(colors[5].set(1, 0, 1, 1)));

                vertStart = 3;
                actualVertCount += 3;

            } else if (outOfBounds == 3) {
                /*var indexA = 0;
                var indexB = 1;
                var indexC = 2;

                var clipSpaceA = vec[indexA].z < -vec[indexA].w ? -vec[indexA].w : vec[indexA].w;
                var clipSpaceB = vec[indexB].z < -vec[indexA].w ? -vec[indexA].w : vec[indexA].w;

                var fracNext = (clipSpaceA - vec[notClippedIndex].z) / (vec[indexA].z - vec[notClippedIndex].z);
                var fracPrevious = (clipSpaceB - vec[notClippedIndex].z) / (vec[previousIndex].z - vec[notClippedIndex].z);

                lerp(notClippedIndex, previousIndex, 3 + previousIndex, vec, uvs, colors, fracPrevious);
                copy(notClippedIndex, 3 + notClippedIndex, vec, uvs, colors);
                lerp(notClippedIndex, indexA, 3 + indexA, vec, uvs, colors, fracNext);

                colors[3].set(colors[4].set(colors[5].set(1, 0, 1, 1)));

                vertStart = 3;
                actualVertCount += 3;*/

                vertStart = 9999;
            }

            for (var t = vertStart; t < actualVertCount; t += 3) {
                int scale = 4;

                vec[t].w = 1 / vec[t].w;
                vec[t].mul(halfWidth * vec[t].w / scale, halfHeight * vec[t].w / scale, vec[t].w, 1).add(halfWidth, halfHeight, 0, 0);

                vec[t + 1].w = 1 / vec[t + 1].w;
                vec[t + 1].mul(halfWidth * vec[t + 1].w / scale, halfHeight * vec[t + 1].w / scale, vec[t + 1].w, 1).add(halfWidth, halfHeight, 0, 0);

                vec[t + 2].w = 1 / vec[t + 2].w;
                vec[t + 2].mul(halfWidth * vec[t + 2].w / scale, halfHeight * vec[t + 2].w / scale, vec[t + 2].w, 1).add(halfWidth, halfHeight, 0, 0);
                drawTriangle(colorOutput, depthOutput, t, vec, uvs, colors, sampler0, layer / 2);
            }

            if (vec[0].z > 1 || vec[1].z > 1 || vec[2].z > 1) {
                if (this.pipeline == RenderPipelines.SOLID_TERRAIN) {
                    System.currentTimeMillis();
                }
            } else

            if (this.pipeline == RenderPipelines.PANORAMA) {
                layer++;
            }

            triangle++;
        }
    }

    private static void lerp(int a, int b, int out, Vector4f[] vec, Vector2f[] uvs, Vector4f[] colors, float frec) {
        vec[a].lerp(vec[b], frec, vec[out]);
        uvs[a].lerp(uvs[b], frec, uvs[out]);
        colors[a].lerp(colors[b], frec, colors[out]);

        /*var w = 1 / vec[out].w;
        var wa = vec[a].w * (1 - frec);
        var wb = vec[b].w * frec;

        /*vec[out].set(
                (vec[a].x * wa + vec[b].x * wb) * w,
                (vec[a].y * wa + vec[b].y * wb) * w,
                (vec[a].z * wa + vec[b].z * wb) * w,
                (vec[a].w * wa + vec[b].w * wb) * w
        );*/
        /*uvs[out].set((uvs[a].x * wa + uvs[b].x * wb) * w, (uvs[a].y * wa + uvs[b].y * wb) * w);
        colors[out].set(
                (colors[a].x * wa + colors[b].x * wb) * w,
                (colors[a].y * wa + colors[b].y * wb) * w,
                (colors[a].z * wa + colors[b].z * wb) * w,
                (colors[a].w * wa + colors[b].w * wb) * w
        );*/
    }

    private static void copy(int a, int out, Vector4f[] vec, Vector2f[] uvs, Vector4f[] colors) {
        vec[out].set(vec[a]);
        uvs[out].set(uvs[a]);
        colors[out].set(colors[a]);
    }

    private float signedTriangleArea(float ax, float ay, float bx, float by, float cx, float cy) {
        return 0.5f * ((by - ay) * (bx + ax) + (cy - by) * (cx + bx) + (ay - cy) * (ax + cx));
    }


    private void drawTriangle(SoftTexture color, @Nullable SoftTexture depth, int offset, Vector4f[] vec, Vector2f[] uvs, Vector4f[] colors, @Nullable SampledTexture texture, int layer) {
        var vec0 = vec[offset];
        var vec1 = vec[offset + 1];
        var vec2 = vec[offset + 2];

        var minX = (int) Math.min(vec0.x, Math.min(vec1.x, vec2.x));
        var maxX = (int) Math.max(vec0.x, Math.max(vec1.x, vec2.x));
        var minY = (int) Math.min(vec0.y, Math.min(vec1.y, vec2.y));
        var maxY = (int) Math.max(vec0.y, Math.max(vec1.y, vec2.y));

        var scissor = this.scissor;

        if (minX > scissor.x2() || maxX < scissor.x1() || minY > scissor.y2() || maxY < scissor.y1()) {
            return;
        }

        minX = Math.max(minX, scissor.x1);
        maxX = Math.min(maxX, scissor.x2 - 1);
        minY = Math.max(minY, scissor.y1);
        maxY = Math.min(maxY, scissor.y2 - 1);

        var drawnColor = new Vector4f();
        var originalColor = new Vector4f();

        var uv0 = uvs[offset];
        var uv1 = uvs[offset + 1];
        var uv2 = uvs[offset + 2];

        var color0 = colors[offset];
        var color1 = colors[offset + 1];
        var color2 = colors[offset + 2];

        var totalArea = signedTriangleArea(vec0.x, vec0.y, vec1.x, vec1.y, vec2.x, vec2.y);
        if (totalArea < 1 && (this.pipeline.isCull() || totalArea > -1)) return;

        totalArea = 1 / totalArea;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                //color.setRGBA(0, x, y, 0xFFFFFFFF);

                var alpha = signedTriangleArea(x + 0.5f, y + 0.5f, vec1.x, vec1.y, vec2.x, vec2.y);
                if (alpha < 0) continue;

                var beta = signedTriangleArea(x + 0.5f, y + 0.5f, vec2.x, vec2.y, vec0.x, vec0.y);
                if (beta < 0) continue;

                var gamma = signedTriangleArea(x + 0.5f, y + 0.5f, vec0.x, vec0.y, vec1.x, vec1.y);
                if (gamma < 0) continue;

                alpha *= totalArea;
                beta *= totalArea;
                gamma *= totalArea;

                var z = (alpha * vec0.z + beta * vec1.z + gamma * vec2.z);

                alpha *= vec0.w;
                beta *= vec1.w;
                gamma *= vec2.w;
                var invW = 1 / (alpha + beta + gamma);


                //if (this.limitDepth && (z < -1 || z > 1)) {
                //    continue;
                //}

                if (depth == null || this.depthTest.test(depth.getDepth(0, x, y), z)) {
                    var u = (alpha * uv0.x + beta * uv1.x + gamma * uv2.x) * invW;
                    var v = (alpha * uv0.y + beta * uv1.y + gamma * uv2.y) * invW;

                    var clr = RGBA.toVector4f(texture != null ? texture.sample(u, v, layer) : -1, drawnColor).mul(
                            (color0.x * alpha + color1.x * beta + color2.x * gamma) * invW,
                            (color0.y * alpha + color1.y * beta + color2.y * gamma) * invW,
                            (color0.z * alpha + color1.z * beta + color2.z * gamma) * invW,
                            (color0.w * alpha + color1.w * beta + color2.w * gamma) * invW
                    );

                    if (this.blender == ColorBlender.SOLID) {
                        clr.w = 1;
                    } else if (clr.w < 0.05) {
                        continue;
                    }

                    if (this.writeDepth) {
                        depth.setDepth(0, x, y, z);
                    }

                    var out = this.blender.blend.applyAsInt(RGBA.toVector4f(color.getRGBA(0, x, y), originalColor), drawnColor);
                    color.setRGBA(0, x, y, out);
                }
            }

        }
    }


    @Override
    public void close() {
        this.closed = true;
        this.encoder.isInRenderPass = false;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }


    private record SampledTexture(SoftTextureView texture, SoftSampler sampler) {
        public int sample(float u, float v, int layer) {
            if (sampler.getAddressModeU() == AddressMode.REPEAT) {
                u = u % 1;
                if (u < 0) {
                    u = 1 - u;
                }
            }

            if (sampler.getAddressModeV() == AddressMode.REPEAT) {
                v = v % 1;
                if (v < 0) {
                    v = 1 - v;
                }
            }

            var width = texture.texture().getWidth(texture.baseMipLevel());
            var height = texture.texture().getHeight(texture.baseMipLevel());

            var x = (int) Mth.clamp(u * width, 0, width - 1);
            var y = (int) Mth.clamp(v * height, 0, height - 1);

            return ((SoftTexture) texture.texture()).getRGBA(layer, texture.baseMipLevel(), x, y);
        }
    }

    private record Scissor(int x1, int y1, int x2, int y2) {
        public boolean inBounds(int x, int y) {
            return x >= x1 - 1 && x < x2 + 1 && y >= y1 - 1 && y < y2 + 1;
        }

        public boolean inBounds(float x, float y) {
            return x >= x1 - 1 && x < x2 + 1 && y >= y1 - 1 && y < y2 + 1;
        }

        public int inBoundsInt(float x, float y) {
            return x >= x1 - 1 && x < x2 + 1 && y >= y1 - 1 && y < y2 + 1 ? 1 : 0;
        }
    }

    private interface DepthTestPredicate {
        boolean test(float image, float drawn);
    }

    private record ColorBlender(ToIntBiFunction<Vector4f, Vector4f> blend) {
        public static final ColorBlender SOLID = new ColorBlender((image, drawn) -> RGBA.fromVector4f(drawn) | 0xFF);
        public static final ColorBlender CUTOUT = new ColorBlender((image, drawn) -> RGBA.fromVector4f(drawn.w > 0.1f ? drawn : image));
        public static final ColorBlender ALPHA_BLEND = new ColorBlender((image, drawn) -> RGBA.alphaBlend(RGBA.fromVector4f(image), RGBA.fromVector4f(drawn)));
        public static final ColorBlender INVERT = new ColorBlender((image, drawn) -> RGBA.fromVector4f(image.mul(-1, -1, -1, 1).add(1, 1, 1, 0)));
        public static final ColorBlender ADDITIVE = new ColorBlender((image, drawn) -> RGBA.fromVector4f(image.add(drawn)));
        public static final ColorBlender MULTIPLY = new ColorBlender((image, drawn) -> RGBA.fromVector4f(image.mul(drawn.x, drawn.y, drawn.z, 1)));
        public static final ColorBlender OVERLAY = new ColorBlender((image, drawn) -> RGBA.fromVector4f(drawn.max(image)));


        int blend(Vector4f image, Vector4f drawn) {
            return this.blend.applyAsInt(image, drawn);
        }
    }
}
