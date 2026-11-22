package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * One sensor frame on the terrain with the sensor's own image in it: the image (a photo or a
 * video frame) is projected through the frame transform the way a slide projector would throw it,
 * so each terrain fragment inside the frame shows the image pixel the sensor saw there. With
 * occlusion the image lands only on ground the sensor actually sees; ground a ridge hides gets
 * the occluded colour, never a smear of the ridge's pixels. The outline is drawn on top.
 *
 * Same frame math as [SensorFrameProgram] (early clip-space rejection, single-derivative outline
 * width, depth-atlas lookup) for one frame per draw, since each frame binds its own image.
 * Image coordinates are the frame's `x/w`, `y/w` mapped to 0..1 (bottom left origin), then the
 * image texture's own coordinate transform (a loaded image's vertical flip, a video surface's
 * transform). [OES] samples Android external video textures (`samplerExternalOES`).
 */
open class SensorFrameImageProgram(private val externalOes: Boolean = false) : AbstractShaderProgram() {
    /** For `GL_TEXTURE_EXTERNAL_OES` images (Android MediaPlayer / Camera → SurfaceTexture). */
    class OES : SensorFrameImageProgram(true)

    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform mat4 frameMatrix;

            attribute vec4 vertexPoint;

            varying vec4 frameClip;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                frameClip = frameMatrix * vertexPoint;
            }
        """.trimIndent(),
        """
            #if defined(GL_ES) && !defined(GL_FRAGMENT_PRECISION_HIGH)
            #define HP mediump
            #else
            #define HP highp
            #endif
            #ifdef GL_ES
            precision HP float;
            #endif

            uniform float outlineWidth;
            uniform vec4 outlineColor;
            uniform vec4 occludedColor;
            uniform float imageOpacity;
            uniform mat3 texCoordMatrix;
            /* x = tan(horizontal FOV / 2), y = tan(vertical FOV / 2), z = range, w = depth near plane, metres */
            uniform HP vec4 frameParams;
            /* Depth atlas cell: xy = origin, z = size (texture coordinates), w = 1 when occluded */
            uniform HP vec4 depthCell;
            uniform ${if (externalOes) "samplerExternalOES" else "sampler2D"} imageSampler;
            #ifdef WW_SHADOW_SAMPLERS
            uniform highp sampler2DShadow frameDepth;
            #else
            uniform highp sampler2D frameDepth;
            #endif

            varying HP vec4 frameClip;

            float frameVisibility(HP vec2 ndc) {
                if (depthCell.w < 0.5) return 1.0;
                HP vec2 uv = depthCell.xy + clamp(ndc * 0.5 + 0.5, 0.0, 1.0) * depthCell.z;
                HP float depth = 0.5 * frameClip.z / frameClip.w + 0.5;
                HP float texel = 2.0 * frameClip.w * max(frameParams.x, frameParams.y) / ${SensorFrameProgram.CELL_PIXELS_FLOAT};
                HP float bias = frameParams.w * (0.5 + 2.0 * texel) / (frameClip.w * frameClip.w);
                HP float reference = clamp(depth - bias, 0.0, 1.0);
            #ifdef WW_SHADOW_SAMPLERS
                return textureLod(frameDepth, vec3(uv, reference), 0.0);
            #else
                return step(reference, texture2D(frameDepth, uv).r);
            #endif
            }

            void main() {
                HP vec4 clip = frameClip;
                /* Outside the frame (5% margin for the outline) or behind the sensor */
                if (clip.w <= 0.0 || abs(clip.x) > clip.w * 1.05 || abs(clip.y) > clip.w * 1.05) discard;
                HP vec2 ndc = clip.xy / clip.w;
                HP float e = 1.0 - max(abs(ndc.x), abs(ndc.y));
            #ifdef WW_HAS_DERIVATIVES
                HP float d = e / max(fwidth(e), 1e-6);
            #else
                HP float d = e / 2e-3;
            #endif
                if (d < -0.5) discard;
                HP vec2 lateral = clip.xy * frameParams.xy;
                if (dot(lateral, lateral) + clip.w * clip.w > frameParams.z * frameParams.z) discard;
                float inside = clamp(d + 0.5, 0.0, 1.0);
                float line = inside * clamp(outlineWidth + 0.5 - d, 0.0, 1.0);
                /* The image pixel the sensor saw here: frame 0..1, then the image's own transform */
                vec2 imageUv = (texCoordMatrix * vec3(clamp(ndc * 0.5 + 0.5, 0.0, 1.0), 1.0)).xy;
                vec4 image = texture2D(imageSampler, imageUv) * imageOpacity;
                float visible = frameVisibility(ndc);
                gl_FragColor = outlineColor * line + mix(occludedColor, image, visible) * inside * (1.0 - line);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    /**
     * As [SensorFrameProgram] (modern GLSL with depth-compare samplers where available). An
     * external image needs `GL_OES_EGL_image_external_essl3` under GLSL ES 3.00, the ES 2
     * extension under the legacy dialect.
     */
    override fun glslVersion(dc: DrawContext): String {
        val prefix = ShadowReceiverGlsl.glslPrefix(dc, true)
        if (!externalOes) return prefix
        val essl3 = prefix.contains("#version 3")
        return prefix + if (essl3) "#extension GL_OES_EGL_image_external_essl3 : require\n"
        else "#extension GL_OES_EGL_image_external : require\n"
    }

    private var mvpMatrixId = KglUniformLocation.NONE
    private var frameMatrixId = KglUniformLocation.NONE
    private var outlineWidthId = KglUniformLocation.NONE
    private var outlineColorId = KglUniformLocation.NONE
    private var occludedColorId = KglUniformLocation.NONE
    private var imageOpacityId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var frameParamsId = KglUniformLocation.NONE
    private var depthCellId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        frameMatrixId = gl.getUniformLocation(program, "frameMatrix")
        outlineWidthId = gl.getUniformLocation(program, "outlineWidth")
        outlineColorId = gl.getUniformLocation(program, "outlineColor")
        occludedColorId = gl.getUniformLocation(program, "occludedColor")
        imageOpacityId = gl.getUniformLocation(program, "imageOpacity")
        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        frameParamsId = gl.getUniformLocation(program, "frameParams")
        depthCellId = gl.getUniformLocation(program, "depthCell")
        gl.uniform1i(gl.getUniformLocation(program, "imageSampler"), 0)
        gl.uniform1i(gl.getUniformLocation(program, "frameDepth"), SensorFrameProgram.DEPTH_UNIT)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /** [matrix] column-major, 16 floats from [offset]. */
    fun loadFrameMatrix(matrix: FloatArray, offset: Int) = gl.uniformMatrix4fv(frameMatrixId, 1, false, matrix, offset)

    /** Colours premultiplied; params and depth cell as in [SensorFrameProgram]; 4 floats each from [offset]. */
    fun loadFrame(
        outlineWidth: Float, outline: FloatArray, occluded: FloatArray, params: FloatArray, cell: FloatArray, offset: Int,
        imageOpacity: Float, texCoordMatrix: Matrix3,
    ) {
        gl.uniform1f(outlineWidthId, outlineWidth)
        gl.uniform4f(outlineColorId, outline[offset], outline[offset + 1], outline[offset + 2], outline[offset + 3])
        gl.uniform4f(occludedColorId, occluded[offset], occluded[offset + 1], occluded[offset + 2], occluded[offset + 3])
        gl.uniform4f(frameParamsId, params[offset], params[offset + 1], params[offset + 2], params[offset + 3])
        gl.uniform4f(depthCellId, cell[offset], cell[offset + 1], cell[offset + 2], cell[offset + 3])
        gl.uniform1f(imageOpacityId, imageOpacity)
        texCoordMatrix.transposeToArray(array, 0)
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
    }
}
