package io.github.santtua.stillreader

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader

internal data class ReadingComfort(
    val theme: ReadingTheme = ReadingTheme.SYSTEM,
    val recolor: Boolean = false,
    val fitPage: Boolean = false,
    val keepAwake: Boolean = false,
    val crop: Int = 0
) {
    fun bounded(): ReadingComfort = copy(crop = crop.takeIf { it == 5 || it == 10 } ?: 0)
}

internal enum class ReadingTheme(val label: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    SEPIA(R.string.theme_sepia),
    HIGH_CONTRAST(R.string.theme_high_contrast);

    fun resolve(configuration: Configuration): ReadingTheme = if (this == SYSTEM) {
        if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
    } else this

    companion object {
        fun stored(value: String?): ReadingTheme = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

internal data class ReaderPalette(val background: Int, val control: Int, val text: Int, val muted: Int) {
    companion object {
        fun from(theme: ReadingTheme): ReaderPalette = when (theme) {
            ReadingTheme.DARK -> ReaderPalette(0xff16191f.toInt(), 0xff282e37.toInt(), 0xfff4f6fa.toInt(), 0xffb7bfcc.toInt())
            ReadingTheme.SEPIA -> ReaderPalette(0xffefe2c7.toInt(), 0xfffaefd8.toInt(), 0xff382b20.toInt(), 0xff665443.toInt())
            ReadingTheme.HIGH_CONTRAST -> ReaderPalette(Color.BLACK, 0xff242424.toInt(), Color.WHITE, 0xffdddddd.toInt())
            else -> ReaderPalette(0xffeef0f3.toInt(), Color.WHITE, 0xff20252d.toInt(), 0xff535c69.toInt())
        }
    }
}

internal fun pageColorEffect(theme: ReadingTheme): RenderEffect? {
    if (theme == ReadingTheme.LIGHT || theme == ReadingTheme.SYSTEM) return null
    val shader = RuntimeShader(NEUTRAL_TONES)
    when (theme) {
        ReadingTheme.DARK -> {
            shader.setFloatUniform("ink", 0.93f, 0.94f, 0.96f)
            shader.setFloatUniform("paper", 0.10f, 0.11f, 0.13f)
        }
        ReadingTheme.SEPIA -> {
            shader.setFloatUniform("ink", 0.17f, 0.12f, 0.08f)
            shader.setFloatUniform("paper", 0.98f, 0.93f, 0.80f)
        }
        ReadingTheme.HIGH_CONTRAST -> {
            shader.setFloatUniform("ink", 1f, 1f, 1f)
            shader.setFloatUniform("paper", 0f, 0f, 0f)
        }
        else -> return null
    }
    shader.setFloatUniform("contrast", if (theme == ReadingTheme.HIGH_CONTRAST) 1.7f else 1f)
    return RenderEffect.createRuntimeShaderEffect(shader, "page")
}

private const val NEUTRAL_TONES = """
    uniform shader page;
    uniform half3 ink;
    uniform half3 paper;
    uniform half contrast;

    half4 main(float2 position) {
        half4 original = page.eval(position);
        if (original.a < 0.001) return original;
        half3 rgb = original.rgb / original.a;
        half low = min(rgb.r, min(rgb.g, rgb.b));
        half high = max(rgb.r, max(rgb.g, rgb.b));
        half chroma = high - low;
        half light = dot(rgb, half3(0.2126, 0.7152, 0.0722));
        light = clamp((light - 0.5) * contrast + 0.5, 0.0, 1.0);
        half3 neutral = mix(ink, paper, light);
        half3 recolored = mix(neutral, rgb, smoothstep(0.025, 0.20, chroma));
        return half4(recolored * original.a, original.a);
    }
"""
