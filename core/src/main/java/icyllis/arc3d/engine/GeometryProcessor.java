/*
 * Modern UI.
 * Copyright (C) 2019-2023 BloCamLimb. All rights reserved.
 *
 * Modern UI is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Modern UI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Modern UI. If not, see <https://www.gnu.org/licenses/>.
 */

package icyllis.arc3d.engine;

import icyllis.arc3d.engine.shading.*;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.graphics.MathUtil;
import icyllis.modernui.graphics.Matrix3;

import javax.annotation.concurrent.Immutable;
import java.util.*;

/**
 * The {@link GeometryProcessor} represents some kind of geometric primitive. This includes
 * the shape of the primitive and the inherent color of the primitive.<br>
 * The {@link GeometryProcessor} is responsible for providing a color and coverage input
 * into the rendering pipeline. Through optimization, engine may decide a different color,
 * no color, and / or no coverage are required from the {@link GeometryProcessor}, so the
 * {@link GeometryProcessor} must be able to support this functionality.
 * <p>
 * There are two feedback loops between the {@link FragmentProcessor FragmentProcessors},
 * the {@link TransferProcessor}, and the {@link GeometryProcessor}. These loops run on the
 * CPU and to determine known properties of the final color and coverage inputs to the
 * {@link TransferProcessor} in order to perform optimizations that preserve correctness.
 * The DrawOp seeds these loops with initial color and coverage, in its
 * getProcessorAnalysisInputs implementation. These seed values are processed by the
 * subsequent stages of the rendering pipeline and the output is then fed back into the DrawOp
 * in the applyPipelineOptimizations call, where the op can use the information to inform
 * decisions about {@link GeometryProcessor} creation.
 */
@Immutable
public abstract class GeometryProcessor extends Processor {

    /**
     * Describes a vertex or instance attribute.
     */
    @Immutable
    public static class Attribute {

        public static final int IMPLICIT_OFFSET = -1;

        /**
         * It must be N-aligned for all types, where N is sizeof(float).
         */
        public static int alignOffset(int offset) {
            return MathUtil.align4(offset);
        }

        private final String mName;
        private final byte mSrcType;
        private final byte mDstType;
        private final int mOffset;

        /**
         * Makes an attribute whose offset will be implicitly determined by the types and ordering
         * of an array attributes.
         *
         * @param name    the attrib name, cannot be null or empty
         * @param srcType the data type in vertex buffer, see {@link Engine.VertexAttribType}
         * @param dstType the data type in vertex shader, see {@link ShaderDataType}
         */
        public Attribute(@NonNull String name, byte srcType, byte dstType) {
            assert (dstType != ShaderDataType.kVoid);
            assert (srcType >= 0 && srcType <= Engine.VertexAttribType.kLast);
            assert (ShaderDataType.checkSLType(dstType));
            assert (!name.isEmpty() && !name.startsWith("_"));
            assert (ShaderDataType.locationCount(dstType) > 0);
            mName = name;
            mSrcType = srcType;
            mDstType = dstType;
            mOffset = IMPLICIT_OFFSET;
        }

        /**
         * Makes an attribute with an explicit offset.
         *
         * @param name    the attrib name, UpperCamelCase, cannot be null or empty
         * @param srcType the data type in vertex buffer, see {@link Engine.VertexAttribType}
         * @param dstType the data type in vertex shader, see {@link ShaderDataType}
         * @param offset  N-aligned offset
         */
        public Attribute(@NonNull String name, byte srcType, byte dstType, int offset) {
            assert (dstType != ShaderDataType.kVoid);
            assert (srcType >= 0 && srcType <= Engine.VertexAttribType.kLast);
            assert (ShaderDataType.checkSLType(dstType));
            assert (!name.isEmpty() && !name.startsWith("_"));
            assert (ShaderDataType.locationCount(dstType) > 0);
            assert (offset != IMPLICIT_OFFSET && alignOffset(offset) == offset);
            mName = name;
            mSrcType = srcType;
            mDstType = dstType;
            mOffset = offset;
        }

        @NonNull
        public final String name() {
            return mName;
        }

        /**
         * @return the data type in vertex buffer, see {@link Engine.VertexAttribType}
         */
        public final byte srcType() {
            return mSrcType;
        }

        /**
         * @return the data type in vertex shader, see {@link ShaderDataType}
         */
        public final byte dstType() {
            return mDstType;
        }

        /**
         * Returns the offset if attributes were specified with explicit offsets. Otherwise,
         * offsets (and total vertex stride) are implicitly determined from attribute order and
         * types. See {@link #IMPLICIT_OFFSET}.
         */
        public final int offset() {
            return mOffset;
        }

        /**
         * @return the size of the source data in bytes
         */
        public final int size() {
            return Engine.VertexAttribType.size(mSrcType);
        }

        /**
         * @return the number of locations
         */
        public final int locations() {
            return ShaderDataType.locationCount(mDstType);
        }

        /**
         * @return the total size for this attribute in bytes
         */
        public final int stride() {
            int size = size();
            int count = locations();
            assert (size > 0 && count > 0);
            return size * count;
        }

        @NonNull
        public final ShaderVar asShaderVar() {
            return new ShaderVar(mName, mDstType, ShaderVar.kIn_TypeModifier);
        }
    }

    /**
     * A set of attributes that can iterated.
     */
    @Immutable
    public static class AttributeSet implements Iterable<Attribute> {

        private final Attribute[] mAttributes;
        private final int mStride;

        private AttributeSet(Attribute[] attributes, int stride) {
            mAttributes = attributes;
            mStride = stride;
        }

        /**
         * Create an attribute set with implicit offsets and stride. No attributes can have a
         * predetermined stride.
         */
        @NonNull
        public static AttributeSet makeImplicit(@NonNull Attribute... attrs) {
            assert (attrs.length > 0 && attrs.length <= Integer.SIZE);
            int stride = 0;
            for (Attribute attr : attrs) {
                assert (attr != null);
                assert (attr.offset() == Attribute.IMPLICIT_OFFSET);
                stride += Attribute.alignOffset(attr.stride());
            }
            assert (stride <= 0xFFFF);
            return new AttributeSet(attrs, Attribute.IMPLICIT_OFFSET);
        }

        /**
         * Create an attribute set with explicit offsets and stride. All attributes must be
         * initialized and have an explicit offset aligned to 4 bytes and with no attribute
         * crossing stride boundaries.
         */
        @NonNull
        public static AttributeSet makeExplicit(int stride, @NonNull Attribute... attrs) {
            assert (stride > 0 && stride <= 0xFFFF);
            assert (Attribute.alignOffset(stride) == stride);
            assert (attrs.length > 0 && attrs.length <= Integer.SIZE);
            for (Attribute attr : attrs) {
                assert (attr != null);
                assert (attr.offset() != Attribute.IMPLICIT_OFFSET);
                assert (Attribute.alignOffset(attr.offset()) == attr.offset());
                assert (attr.offset() + attr.stride() <= stride);
            }
            return new AttributeSet(attrs, stride);
        }

        final int stride(int mask) {
            // implicit stride changes with mask
            if (mStride == Attribute.IMPLICIT_OFFSET) {
                final int count = mAttributes.length;
                int stride = 0;
                for (int i = 0, bit = 1; i < count; i++, bit <<= 1) {
                    final Attribute attr = mAttributes[i];
                    if ((mask & bit) != 0) {
                        assert (attr.offset() == Attribute.IMPLICIT_OFFSET);
                        stride += Attribute.alignOffset(attr.stride());
                    }
                }
                return stride;
            }
            return mStride;
        }

        final int numLocations(int mask) {
            final int rawCount = mAttributes.length;
            int locations = 0;
            for (int i = 0, bit = 1; i < rawCount; i++, bit <<= 1) {
                final Attribute attr = mAttributes[i];
                if ((mask & bit) != 0) {
                    locations += attr.locations();
                }
            }
            return locations;
        }

        final void addToKey(@NonNull Key.Builder b, int mask) {
            final int rawCount = mAttributes.length;
            // max attribs is no less than 16, we assume the minimum
            b.addBits(6, rawCount, "attribute count");
            int implicitOffset = 0;
            for (int i = 0, bit = 1; i < rawCount; i++, bit <<= 1) {
                final Attribute attr = mAttributes[i];
                if ((mask & bit) != 0) {
                    b.appendComment(attr.name());
                    b.addBits(8, attr.srcType() & 0xFF, "attrType");
                    b.addBits(8, attr.dstType() & 0xFF, "attrGpuType");
                    int offset;
                    if (attr.offset() != Attribute.IMPLICIT_OFFSET) {
                        offset = attr.offset();
                    } else {
                        offset = implicitOffset;
                        implicitOffset += Attribute.alignOffset(attr.stride());
                    }
                    assert (offset >= 0 && offset <= 0xFFFF);
                    b.addBits(16, offset, "attrOffset");
                } else {
                    b.appendComment("unusedAttr");
                    b.addBits(8, 0xFF, "attrType");
                    b.addBits(8, 0xFF, "attrGpuType");
                    b.addBits(16, 0xFFFF, "attrOffset");
                }
            }
            final int stride;
            if (mStride == Attribute.IMPLICIT_OFFSET) {
                stride = implicitOffset;
            } else {
                stride = mStride;
            }
            // max stride is no less than 2048, we assume the minimum
            assert (stride > 0 && stride <= 0xFFFF);
            assert (Attribute.alignOffset(stride) == stride);
            b.addBits(16, stride, "stride");
        }

        @NonNull
        @Override
        public Iterator<Attribute> iterator() {
            return new Iter((1 << mAttributes.length) - 1);
        }

        private class Iter implements Iterator<Attribute> {

            private final int mask;
            private final int count;

            private int index;
            private int implicitOffset;

            private Iter(int mask) {
                this.mask = mask;
                count = Integer.bitCount(mask);
            }

            @Override
            public boolean hasNext() {
                return index < count;
            }

            @NonNull
            @Override
            public Attribute next() {
                try {
                    while ((mask & (1 << index)) == 0) {
                        index++; // skip unused
                    }
                    final Attribute ret, curr = mAttributes[index++];
                    if (curr.offset() == Attribute.IMPLICIT_OFFSET) {
                        ret = new Attribute(curr.name(), curr.srcType(), curr.dstType(), implicitOffset);
                    } else {
                        ret = curr;
                    }
                    implicitOffset += Attribute.alignOffset(curr.stride());
                    return ret;
                } catch (Exception e) {
                    throw new NoSuchElementException(e);
                }
            }
        }
    }

    /**
     * GPs that need to use either float or ubyte colors can just call this to get a correctly
     * configured {@link Attribute} struct.
     */
    @NonNull
    protected static Attribute makeColorAttribute(String name, boolean wideColor) {
        return new Attribute(name, wideColor ? Engine.VertexAttribType.kFloat4 : Engine.VertexAttribType.kUByte4_norm,
                ShaderDataType.kFloat4);
    }

    private AttributeSet mVertexAttributes;     // binding = 0, divisor = 0, VK_VERTEX_INPUT_RATE_VERTEX
    private AttributeSet mInstanceAttributes;   // binding = 1, divisor = 1, VK_VERTEX_INPUT_RATE_INSTANCE
    private int mVertexAttributesMask;          // bit mask controlling which attributes are enabled
    private int mInstanceAttributesMask;        // bit mask controlling which attributes are enabled

    protected GeometryProcessor(int classID) {
        super(classID);
    }

    /**
     * @see Engine.PrimitiveType
     */
    public abstract byte primitiveType();

    /**
     * Currently, GP is limited to one texture sampler at most.
     */
    public int numTextureSamplers() {
        return 0;
    }

    /**
     * Used to capture the properties of the TextureProxies required/expected by a GeometryProcessor
     * along with an associated SamplerState. The actual proxies used are stored in either the
     * fixed or dynamic state arrays. TextureSamplers don't perform any coord manipulation to account
     * for texture origin.
     *
     * @see SamplerState
     */
    public int textureSamplerState(int i) {
        assert false;
        return SamplerState.DEFAULT;
    }

    /**
     * @see Swizzle
     */
    public short textureSamplerSwizzle(int i) {
        assert false;
        return Swizzle.RGBA;
    }

    /**
     * Returns the number of used per-vertex attributes.
     * Note: attribute of a matrix type counts as just one.
     *
     * @see #numVertexLocations()
     */
    public final int numVertexAttributes() {
        return Integer.bitCount(mVertexAttributesMask);
    }

    /**
     * Returns the number of used per-vertex attribute locations.
     * Note: attribute of a matrix type counts more than one.
     *
     * @see ShaderDataType#locationCount(byte)
     * @see #numVertexAttributes()
     */
    public final int numVertexLocations() {
        assert (mVertexAttributesMask == 0 || mVertexAttributes != null);
        return mVertexAttributesMask == 0 ? 0 : mVertexAttributes.numLocations(mVertexAttributesMask);
    }

    /**
     * Returns an iterator of used per-vertex attributes. It's safe to call even if there's no attribute.
     * The iterator handles hides two pieces of complexity:
     * <ol>
     * <li>It skips uninitialized attributes.</li>
     * <li>It always returns an attribute with a known offset.</li>
     * </ol>
     */
    @NonNull
    public final Iterable<Attribute> getVertexAttributes() {
        assert (mVertexAttributesMask == 0 || mVertexAttributes != null);
        return mVertexAttributesMask == 0 ? Collections.emptyList() :
                () -> mVertexAttributes.new Iter(mVertexAttributesMask);
    }

    /**
     * Returns the number of used per-instance attributes.
     * Note: attribute of a matrix type counts as just one.
     *
     * @see #numInstanceLocations()
     */
    public final int numInstanceAttributes() {
        return Integer.bitCount(mInstanceAttributesMask);
    }

    /**
     * Returns the number of used per-instance attribute locations.
     * Note: attribute of a matrix type counts more than one.
     *
     * @see ShaderDataType#locationCount(byte)
     * @see #numInstanceAttributes()
     */
    public final int numInstanceLocations() {
        assert (mInstanceAttributesMask == 0 || mInstanceAttributes != null);
        return mInstanceAttributesMask == 0 ? 0 : mInstanceAttributes.numLocations(mInstanceAttributesMask);
    }

    /**
     * Returns an iterator of used per-instance attributes. It's safe to call even if there's no attribute.
     * The iterator handles hides two pieces of complexity:
     * <ol>
     * <li>It skips uninitialized attributes.</li>
     * <li>It always returns an attribute with a known offset.</li>
     * </ol>
     */
    @NonNull
    public final Iterable<Attribute> getInstanceAttributes() {
        assert (mInstanceAttributesMask == 0 || mInstanceAttributes != null);
        return mInstanceAttributesMask == 0 ? Collections.emptyList() :
                () -> mInstanceAttributes.new Iter(mInstanceAttributesMask);
    }

    public final boolean hasVertexAttributes() {
        assert (mVertexAttributesMask == 0 || mVertexAttributes != null);
        return mVertexAttributesMask != 0;
    }

    public final boolean hasInstanceAttributes() {
        assert (mInstanceAttributesMask == 0 || mInstanceAttributes != null);
        return mInstanceAttributesMask != 0;
    }

    /**
     * A common practice is to populate the vertex/instance's memory using an implicit array of
     * structs. In this case, it is best to assert that: stride == sizeof(struct).
     */
    public final int vertexStride() {
        return mVertexAttributes == null ? 0 : mVertexAttributes.stride(mVertexAttributesMask);
    }

    /**
     * @see #vertexStride()
     */
    public final int instanceStride() {
        return mInstanceAttributes == null ? 0 : mInstanceAttributes.stride(mInstanceAttributesMask);
    }

    /**
     * Adds a key on the KeyBuilder that reflects any variety in the code that the
     * geometry processor subclass can emit.
     */
    public abstract void addToKey(Key.Builder b);

    public final void getAttributeKey(Key.Builder b) {
        b.appendComment("vertex attributes");
        mVertexAttributes.addToKey(b, mVertexAttributesMask);
        b.appendComment("instance attributes");
        mInstanceAttributes.addToKey(b, mInstanceAttributesMask);
    }

    /**
     * Returns a new instance of the appropriate implementation class for the given
     * GeometryProcessor. This method is called only when the specified key does not
     * exist in the program cache.
     *
     * @see #addToKey(Key.Builder)
     */
    @NonNull
    public abstract ProgramImpl makeProgramImpl(ShaderCaps caps);

    /**
     * Sets per-vertex attributes. Given a shared {@link AttributeSet} containing all attributes,
     * and then use mask to control which of them are used by this GeometryProcessor instance.
     * For example, a mask of 0b1011 means to use attrs[0], attrs[1] and attrs[3], where 0, 1 and 3
     * is the layout location of the vertex shader input. Note: Call this in subclasses constructor.
     *
     * @param attrs all per-vertex attributes
     * @param mask  a mask determining which attributes to use, can be zero
     */
    protected final void setVertexAttributes(AttributeSet attrs, int mask) {
        assert (mVertexAttributes == null && attrs != null);
        mVertexAttributes = attrs;
        mVertexAttributesMask = mask & ((1 << attrs.mAttributes.length) - 1);
    }

    /**
     * Sets per-instance attributes. Given a shared {@link AttributeSet} containing all attributes,
     * and then use mask to control which of them are used by this GeometryProcessor instance.
     * For example, a mask of 0b1011 means to use attrs[0], attrs[1] and attrs[3], where 0, 1 and 3
     * is the layout location of the vertex shader input. Note: Call this in subclasses constructor.
     *
     * @param attrs all per-instance attributes
     * @param mask  a mask determining which attributes to use, can be zero
     */
    protected final void setInstanceAttributes(AttributeSet attrs, int mask) {
        assert (mInstanceAttributes == null && attrs != null);
        mInstanceAttributes = attrs;
        mInstanceAttributesMask = mask & ((1 << attrs.mAttributes.length) - 1);
    }

    /**
     * Every {@link GeometryProcessor} must be capable of creating a subclass of {@link ProgramImpl}.
     * The {@link ProgramImpl} emits the shader code that implements the {@link GeometryProcessor},
     * is attached to the generated backend API pipeline/program and used to extract uniform data
     * from {@link GeometryProcessor} instances.
     */
    public static abstract class ProgramImpl {

        /**
         * A helper for setting the matrix on a uniform handle initialized through
         * writeOutputPosition or writeLocalCoord. Automatically handles elided uniforms,
         * scale+translate matrices, and state tracking (if provided state pointer is non-null).
         *
         * @param matrix the matrix to set, must be immutable
         * @param state  the current state
         * @return new state, eiter matrix or state
         */
        //TODO move to other places
        protected static Matrix3 setTransform(@NonNull UniformDataManager pdm,
                                              @UniformHandler.UniformHandle int uniform,
                                              @NonNull Matrix3 matrix,
                                              @Nullable Matrix3 state) {
            if (uniform == Engine.INVALID_RESOURCE_HANDLE ||
                    (state != null && state.equals(matrix))) {
                // No update needed
                return state;
            }
            if (matrix.isScaleTranslate()) {
                // ComputeMatrixKey and writeX() assume the uniform is a float4 (can't assert since nothing
                // is exposed on a handle, but should be caught lower down).
                pdm.set4f(uniform, matrix.getScaleX(), matrix.getTranslateX(),
                        matrix.getScaleY(), matrix.getTranslateY());
            } else {
                pdm.setMatrix3f(uniform, matrix);
            }
            return matrix;
        }

        /**
         * Helpers for adding code to write the transformed vertex position. The first simple version
         * just writes a variable named by 'posName' into the position output variable with the
         * assumption that the position is 2D. The second version transforms the input position by a
         * view matrix and the output variable is 2D or 3D depending on whether the view matrix is
         * perspective.
         *
         * @param inPos the local variable or the attribute, type must be either vec2 or vec3
         */
        protected static void writeWorldPosition(VertexGeomBuilder vertBuilder,
                                                 ShaderVar inPos,
                                                 String matrixName,
                                                 ShaderVar outPos) {
            assert (inPos.getType() == ShaderDataType.kFloat2 || inPos.getType() == ShaderDataType.kFloat3);

            if (inPos.getType() == ShaderDataType.kFloat3) {
                // A float3 stays a float3 whether the matrix adds perspective
                vertBuilder.codeAppendf("vec3 _worldPos = %s * %s;\n",
                        matrixName,
                        inPos.getName());
                outPos.set("_worldPos", ShaderDataType.kFloat3);
            } else {
                // A float2 is promoted to a float3 if we add perspective via the matrix
                vertBuilder.codeAppendf("vec3 _worldPos = %s * vec3(%s, 1.0);\n",
                        matrixName,
                        inPos.getName());
                outPos.set("_worldPos", ShaderDataType.kFloat3);
            }
        }

        /**
         * Emits the code from this geometry processor into the shaders. For any FP in the pipeline that
         * has its input coords implemented by the GP as a varying, the varying will be accessible in
         * the returned map and should be used when the FP code is emitted. The FS variable containing
         * the GP's output local coords is also returned.
         */
        public final void emitCode(VertexGeomBuilder vertBuilder,
                                   FPFragmentBuilder fragBuilder,
                                   VaryingHandler varyingHandler,
                                   UniformHandler uniformHandler,
                                   ShaderCaps shaderCaps,
                                   GeometryProcessor geomProc,
                                   String outputColor,
                                   String outputCoverage,
                                   @UniformHandler.SamplerHandle int[] texSamplers) {
            final var localPos = new ShaderVar();
            final var worldPos = new ShaderVar();
            onEmitCode(vertBuilder,
                    fragBuilder,
                    varyingHandler,
                    uniformHandler,
                    shaderCaps,
                    geomProc,
                    outputColor,
                    outputCoverage,
                    texSamplers,
                    localPos,
                    worldPos);

            // Emit the vertex position to the hardware in the normalized device coordinates it expects.
            assert (worldPos.getType() == ShaderDataType.kFloat2 ||
                    worldPos.getType() == ShaderDataType.kFloat3);
            vertBuilder.emitNormalizedPosition(worldPos);
            if (worldPos.getType() == ShaderDataType.kFloat2) {
                varyingHandler.setNoPerspective();
            }
        }

        /**
         * A ProgramImpl instance can be reused with any GeometryProcessor that produces the same key.
         * This function reads data from a GeometryProcessor and updates any uniform variables
         * required by the shaders created in emitCode(). The GeometryProcessor parameter is
         * guaranteed to be of the same type and to have an identical processor key as the
         * GeometryProcessor that created this ProgramImpl.
         */
        public abstract void setData(UniformDataManager manager,
                                     GeometryProcessor geomProc);

        /**
         * The local pos is used to specify the output variable storing draw's local position. It can
         * be either a vec2 or a vec3, or void. It can only be void when no FP needs local coordinates.
         * This variable can be an attribute or local variable, but should not itself be a varying.
         * PipelineBuilder automatically determines if this must be passed to the FP.
         * <p>
         * The world pos is used to specify the output variable storing its world (device) position.
         * It can either be a vec2 or a vec3 (in order to handle perspective).
         */
        protected abstract void onEmitCode(VertexGeomBuilder vertBuilder,
                                           FPFragmentBuilder fragBuilder,
                                           VaryingHandler varyingHandler,
                                           UniformHandler uniformHandler,
                                           ShaderCaps shaderCaps,
                                           GeometryProcessor geomProc,
                                           String outputColor,
                                           String outputCoverage,
                                           @UniformHandler.SamplerHandle int[] texSamplers,
                                           ShaderVar localPos,
                                           ShaderVar worldPos);
    }
}
