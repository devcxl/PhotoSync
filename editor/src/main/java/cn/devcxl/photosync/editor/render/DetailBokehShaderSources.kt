package cn.devcxl.photosync.editor.render

/**
 * Stage 5 of the `SpatialDetailChain` fragment shader: simulated background bokeh.
 *
 * Split out of [DetailShaderSources] so each GLSL source stays below the repository's file-size
 * budget while both halves still compile into one program. The disc uses the same golden-angle
 * offsets as [cn.devcxl.photosync.editor.domain.SubjectBokehMath].
 */
internal object DetailBokehShaderSources {

    /** `subjectWeightAt`, the relight helper and the terminal bokeh stage. */
    val FUNCTIONS = """
        // Stage 5: simulated background bokeh, weighted by the refined subject alpha.
        ${ColorShaderGlsl.SUBJECT_RELIGHT_FUNCTIONS}

        vec3 subjectBokeh(vec3 sharp) {
            if (uHasSubjectMask == 0 || uSubjectBokehRadius <= 0.0) return sharp;
            float centerAlpha = subjectWeightAt(vTexCoord);
            float backgroundWeight = 1.0 - centerAlpha;
            if (backgroundWeight <= 0.0) return sharp;
            vec3 accum = sharp * backgroundWeight;
            float weightSum = backgroundWeight;
            for (int tap = 0; tap < 12; tap++) {
                float index = float(tap);
                float angle = index * GOLDEN_ANGLE_RAD;
                float distance = sqrt((index + 0.5) / 12.0) * uSubjectBokehRadius;
                vec2 offset = vec2(cos(angle), sin(angle)) * distance * uTexelSize;
                vec2 tapUv = vTexCoord + offset;
                float tapWeight = 1.0 - subjectWeightAt(tapUv);
                if (tapWeight <= 0.0) continue;
                accum += sampleAt(tapUv) * tapWeight;
                weightSum += tapWeight;
            }
            vec3 blurred = accum / max(weightSum, 1e-4);
            return mix(sharp, blurred, backgroundWeight);
        }
    """.trimIndent()
}
