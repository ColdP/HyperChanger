// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutMaterialBackground(modifier: Modifier = Modifier) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val shader = remember { RuntimeShader(ABOUT_BACKGROUND_SHADER) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader } }
    var animationTime by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastFrame = withFrameNanos { it }
        var direction = 1f
        while (isActive) {
            val frame = withFrameNanos { it }
            animationTime += ((frame - lastFrame) * 1e-9f).coerceIn(0f, .05f) * direction
            if (animationTime >= 120f) direction = -1f else if (animationTime <= 0f) direction = 1f
            lastFrame = frame
        }
    }
    Canvas(modifier) {
        shader.setFloatUniform("uResolution", size.width, size.height)
        shader.setFloatUniform("uAnimTime", animationTime)
        shader.setFloatUniform("uPoints", ABOUT_POINTS)
        shader.setFloatUniform("uColors", if (dark) ABOUT_DARK_COLORS else ABOUT_LIGHT_COLORS)
        shader.setFloatUniform("uNoiseScale", 1.5f)
        shader.setFloatUniform("uPointOffset", if (dark) .4f else .2f)
        shader.setFloatUniform("uSaturateOffset", if (dark) .17f else .2f)
        shader.setFloatUniform("uLightOffset", if (dark) 0f else .1f)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
        }
    }
}

private val ABOUT_POINTS = floatArrayOf(.8f, .2f, 1f, .8f, .9f, 1f, .2f, .9f, 1f, .2f, .2f, 1f)
private val ABOUT_LIGHT_COLORS = floatArrayOf(
    .58f, .74f, 1f, 1f, 1f, .9f, .93f, 1f,
    .74f, .76f, 1f, 1f, .97f, .77f, .84f, 1f,
)
private val ABOUT_DARK_COLORS = floatArrayOf(
    .07f, .15f, .79f, .5f, .62f, .21f, .67f, .5f,
    .06f, .25f, .84f, .5f, 0f, .2f, .78f, .5f,
)

private const val ABOUT_BACKGROUND_SHADER = """
uniform float2 uResolution;
uniform float uAnimTime;
uniform float3 uPoints[4];
uniform half4 uColors[4];
uniform float uNoiseScale;
uniform float uPointOffset;
uniform float uSaturateOffset;
uniform float uLightOffset;

float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.13);
    p3 += dot(p3, p3.yzx + 3.333);
    return fract((p3.x + p3.y) * p3.z);
}
float perlin(float2 x) {
    float2 i = floor(x); float2 f = fract(x);
    float a = hash(i); float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0)); float d = hash(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
float gradientNoise(float2 uv) {
    return fract(52.9829189 * fract(dot(uv, float2(0.06711056, 0.00583715))));
}
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution; uv.y = 1.0 - uv.y;
    float noiseValue = perlin(uv * uNoiseScale + float2(-uAnimTime));
    half4 color = half4(0.0);
    for (int i = 0; i < 4; i++) {
        half4 pointColor = uColors[i]; pointColor.rgb *= pointColor.a;
        float2 point = uPoints[i].xy;
        point.x += sin(uAnimTime + point.y) * uPointOffset;
        point.y += cos(uAnimTime + point.x) * uPointOffset;
        float pct = smoothstep(uPoints[i].z, 0.0, distance(uv, point));
        color.rgb = mix(color.rgb, pointColor.rgb, pct);
        color.a = mix(color.a, pointColor.a, pct);
    }
    color.rgb /= max(color.a, 0.001);
    float gray = dot(color.rgb, half3(.299, .587, .114));
    color.rgb = mix(color.rgb, half3(gray), noiseValue * uSaturateOffset);
    color.rgb += noiseValue * uLightOffset;
    color.rgb += (10.0 / 255.0) * gradientNoise(fragCoord) - (5.0 / 255.0);
    return half4(color.rgb, 1.0);
}
"""
