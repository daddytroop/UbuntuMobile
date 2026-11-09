package com.ubuntumobile.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import java.io.File

/**
 * The desktop's wallpapers.
 *
 * They are drawn here, with Android's own canvas, rather than shipped as
 * pictures: the same code draws onboarding's previews and the files the
 * desktop shows, and the APK stays small. [ensure] writes them into the
 * container, where linx-look.sh sets the one setup.conf names.
 */
enum class Wallpaper(val id: String, val label: String) {
    AUBERGINE("aubergine", "Aubergine"),
    MIDNIGHT("midnight", "Aurora"),
    DUNES("dunes", "Dunes"),
    FOREST("forest", "Ridges"),
    GRAPHITE("graphite", "Graphite"),
    DAWN("dawn", "Dawn"),
    ;

    /** Draw it into [w] x [h] pixels of [c]. */
    fun draw(c: Canvas, w: Float, h: Float) {
        when (this) {
            AUBERGINE -> aubergine(c, w, h)
            MIDNIGHT -> aurora(c, w, h)
            DUNES -> dunes(c, w, h)
            FOREST -> ridges(c, w, h)
            GRAPHITE -> graphite(c, w, h)
            DAWN -> dawn(c, w, h)
        }
    }

    companion object {
        private const val TAG = "Wallpapers"

        /** Bump when a design changes, so the files are drawn again. */
        private const val VERSION = "1"

        /** Square, and big enough to cover a 1440p monitor or a tall phone. */
        private const val SIZE = 2048

        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: AUBERGINE

        /**
         * Draw any that are missing into the container; true if it drew
         * some. Slow (a few seconds): call it off the main thread.
         */
        fun ensure(root: File): Boolean {
            val dir = File(root, "usr/local/share/linx/wallpapers")
            val stamp = File(dir, ".version")
            val fresh = stamp.isFile && stamp.readText() == VERSION
            if (fresh && entries.all { File(dir, "${it.id}.jpg").isFile }) return false
            try {
                dir.mkdirs()
                for (w in entries) {
                    val file = File(dir, "${w.id}.jpg")
                    if (fresh && file.isFile) continue
                    val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                    w.draw(Canvas(bmp), SIZE.toFloat(), SIZE.toFloat())
                    val tmp = File(dir, ".${w.id}.jpg")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()
                    tmp.renameTo(file)
                }
                stamp.writeText(VERSION)
            } catch (e: Exception) {
                Log.w(TAG, "could not draw the wallpapers", e)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "could not draw the wallpapers", e)
            }
            return true
        }
    }
}

private fun linear(x0: Float, y0: Float, x1: Float, y1: Float, vararg stops: Pair<Float, Int>) =
    LinearGradient(
        x0, y0, x1, y1,
        stops.map { it.second }.toIntArray(),
        stops.map { it.first }.toFloatArray(),
        Shader.TileMode.CLAMP,
    )

private fun fill(shader: Shader) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }

private fun solid(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

/** A soft round glow of [color], fading out at [r]. */
private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
    val clear = color and 0x00FFFFFF
    c.drawCircle(x, y, r, fill(RadialGradient(x, y, r, color, clear, Shader.TileMode.CLAMP)))
}

/** A band across the picture whose top edge follows a sine-like curve. */
private fun hills(w: Float, h: Float, base: Float, amp: Float, phase: Float, waves: Float): Path {
    val path = Path()
    path.moveTo(0f, h)
    val steps = 64
    for (i in 0..steps) {
        val x = w * i / steps
        val t = i.toFloat() / steps
        val y = base + amp * (
            0.65f * kotlin.math.sin((t * waves + phase) * 2f * Math.PI.toFloat()) +
                0.35f * kotlin.math.sin((t * waves * 2.3f + phase * 1.7f) * 2f * Math.PI.toFloat())
            )
        path.lineTo(x, y)
    }
    path.lineTo(w, h)
    path.close()
    return path
}

private fun aubergine(c: Canvas, w: Float, h: Float) {
    c.drawRect(0f, 0f, w, h, fill(linear(0f, 0f, w, h, 0f to 0xFF2C001E.toInt(), 0.55f to 0xFF5E2750.toInt(), 1f to 0xFF77216F.toInt())))
    glow(c, w * 0.88f, h * 0.12f, w * 0.75f, 0xB0E95420.toInt())
    glow(c, w * 0.05f, h * 0.95f, w * 0.6f, 0x802C001E.toInt())
    // Two soft ribbons, Ubuntu's curve.
    val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = w * 0.05f
        maskFilter = BlurMaskFilter(w * 0.03f, BlurMaskFilter.Blur.NORMAL)
    }
    for ((i, color) in listOf(0x40FFFFFF, 0x30E95420).withIndex()) {
        ribbon.color = color
        val p = Path()
        p.moveTo(-w * 0.1f, h * (0.78f + i * 0.08f))
        p.cubicTo(w * 0.3f, h * (0.5f + i * 0.1f), w * 0.6f, h * (1.0f + i * 0.05f), w * 1.1f, h * (0.55f + i * 0.1f))
        c.drawPath(p, ribbon)
    }
}

private fun aurora(c: Canvas, w: Float, h: Float) {
    c.drawRect(0f, 0f, w, h, fill(linear(0f, 0f, 0f, h, 0f to 0xFF050816.toInt(), 0.6f to 0xFF0B1A33.toInt(), 1f to 0xFF10284A.toInt())))
    val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(w * 0.06f, BlurMaskFilter.Blur.NORMAL)
    }
    val colors = listOf(0x9028E0B0.toInt(), 0x7030A0F0, 0x60A050F0)
    for ((i, color) in colors.withIndex()) {
        band.color = color
        band.strokeWidth = w * (0.12f - i * 0.025f)
        val p = Path()
        val y = h * (0.32f + i * 0.1f)
        p.moveTo(-w * 0.1f, y + h * 0.15f)
        p.cubicTo(w * 0.25f, y - h * 0.2f, w * 0.6f, y + h * 0.25f, w * 1.1f, y - h * 0.1f)
        c.drawPath(p, band)
    }
    // A few stars.
    val rnd = java.util.Random(7)
    val star = solid(0xFFFFFFFF.toInt())
    repeat(140) {
        star.alpha = 60 + rnd.nextInt(160)
        c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.7f, w * (0.0007f + rnd.nextFloat() * 0.0016f), star)
    }
    c.drawPath(hills(w, h, h * 0.86f, h * 0.03f, 0.2f, 1.4f), solid(0xFF040A14.toInt()))
}

private fun dunes(c: Canvas, w: Float, h: Float) {
    c.drawRect(0f, 0f, w, h, fill(linear(0f, 0f, 0f, h, 0f to 0xFFFFE3B3.toInt(), 0.5f to 0xFFF9B384.toInt(), 1f to 0xFFE88C5D.toInt())))
    glow(c, w * 0.7f, h * 0.3f, w * 0.35f, 0xA0FFF4D6.toInt())
    c.drawCircle(w * 0.7f, h * 0.3f, w * 0.08f, solid(0xFFFFF1D0.toInt()))
    val layers = listOf(0xFFE7905F.toInt(), 0xFFD0704A.toInt(), 0xFFB0533A.toInt(), 0xFF7E3A2E.toInt())
    for ((i, color) in layers.withIndex()) {
        c.drawPath(hills(w, h, h * (0.5f + i * 0.12f), h * 0.05f, i * 0.37f, 1.1f + i * 0.2f), solid(color))
    }
}

private fun ridges(c: Canvas, w: Float, h: Float) {
    c.drawRect(0f, 0f, w, h, fill(linear(0f, 0f, 0f, h, 0f to 0xFFE6F2E1.toInt(), 0.6f to 0xFFB7D9C3.toInt(), 1f to 0xFF8DBFA4.toInt())))
    glow(c, w * 0.25f, h * 0.22f, w * 0.3f, 0x90FFFFFF.toInt())
    val layers = listOf(0xFF8FB89A.toInt(), 0xFF5E9270.toInt(), 0xFF3A6B4E.toInt(), 0xFF1F4532.toInt(), 0xFF0F2A1E.toInt())
    for ((i, color) in layers.withIndex()) {
        c.drawPath(hills(w, h, h * (0.42f + i * 0.11f), h * (0.07f - i * 0.008f), i * 0.53f + 0.1f, 1.6f + i * 0.3f), solid(color))
    }
}

private fun graphite(c: Canvas, w: Float, h: Float) {
    c.drawRect(0f, 0f, w, h, fill(linear(0f, 0f, w, h, 0f to 0xFF2B2B2E.toInt(), 1f to 0xFF161618.toInt())))
    glow(c, w * 0.3f, h * 0.25f, w * 0.7f, 0x30FFFFFF)
    // Fine rings, barely there.
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = w * 0.0015f
        color = 0x18FFFFFF
    }
    for (i in 1..14) c.drawCircle(w * 0.78f, h * 0.82f, w * 0.06f * i, ring)
}

private fun dawn(c: Canvas, w: Float, h: Float) {
    c.drawRect(0f, 0f, w, h, fill(linear(0f, 0f, w, h, 0f to 0xFFFBC2EB.toInt(), 0.5f to 0xFFC9C6F0.toInt(), 1f to 0xFFA6C1EE.toInt())))
    glow(c, w * 0.2f, h * 0.75f, w * 0.5f, 0x90FFD6E8.toInt())
    glow(c, w * 0.85f, h * 0.2f, w * 0.45f, 0x90FFFFFF.toInt())
    glow(c, w * 0.6f, h * 0.6f, w * 0.3f, 0x60B8D8FF)
}
