package com.mobilestreaming.studio.streaming.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import com.mobilestreaming.studio.core.model.SourceKind
import com.mobilestreaming.studio.core.model.VisualSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class BitmapSourceRenderer(private val context: Context) {
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

    fun render(source: VisualSource, width: Int, height: Int, nowMs: Long): Bitmap {
        val safeWidth = width.coerceIn(16, 2048)
        val safeHeight = height.coerceIn(16, 1024)
        val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = source.backgroundColor.toInt()
        if (Color.alpha(background) > 0 && source.kind != SourceKind.IMAGE && source.kind != SourceKind.LOGO) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
            val radius = if (source.kind == SourceKind.TEXT) 0f else min(safeWidth, safeHeight) * 0.08f
            canvas.drawRoundRect(RectF(0f, 0f, safeWidth.toFloat(), safeHeight.toFloat()), radius, radius, paint)
        }
        val rawText = when (source.kind) {
            SourceKind.CLOCK -> clockFormat.format(Date(nowMs))
            SourceKind.DATE -> dateFormat.format(Date(nowMs))
            else -> source.text
        }
        if (rawText.isNotBlank()) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = source.foregroundColor.toInt()
                textAlign = Paint.Align.LEFT
                isFakeBoldText = source.kind == SourceKind.BREAKING_NEWS || source.kind == SourceKind.TEXT
            }
            val horizontalPadding = safeWidth * 0.06f
            val availableWidth = (safeWidth - horizontalPadding * 2).coerceAtLeast(1f)
            paint.textSize = safeHeight * 0.46f * source.fontScale
            while (paint.textSize > 8f && source.kind != SourceKind.TICKER &&
                paint.measureText(rawText) > availableWidth
            ) paint.textSize *= 0.92f
            val baseline = safeHeight / 2f - (paint.descent() + paint.ascent()) / 2f
            if (source.kind == SourceKind.TICKER) {
                val text = "$rawText     •     $rawText"
                val textWidth = paint.measureText(text)
                val travel = textWidth + safeWidth
                val x = safeWidth - ((nowMs / 25f) % travel)
                canvas.save()
                canvas.clipRect(0f, 0f, safeWidth.toFloat(), safeHeight.toFloat())
                canvas.drawText(text, x, baseline, paint)
                canvas.restore()
            } else {
                val lines = rawText.lines().take(3)
                val lineHeight = paint.fontSpacing
                val firstBaseline = safeHeight / 2f - (lines.size - 1) * lineHeight / 2f -
                    (paint.descent() + paint.ascent()) / 2f
                lines.forEachIndexed { index, line ->
                    val ellipsized = TextUtils.ellipsize(line, paint, availableWidth, TextUtils.TruncateAt.END).toString()
                    val textWidth = paint.measureText(ellipsized)
                    canvas.drawText(ellipsized, (safeWidth - textWidth) / 2f, firstBaseline + index * lineHeight, paint)
                }
            }
        }
        return bitmap
    }

    fun decodeImage(uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= 1 && bounds.outHeight / (sampleSize * 2) >= 1 &&
            (bounds.outWidth / (sampleSize * 2) > 2048 || bounds.outHeight / (sampleSize * 2) > 2048 ||
                bounds.outWidth.toLong() * bounds.outHeight / ((sampleSize * 2L) * (sampleSize * 2L)) > 8_000_000L)
        ) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    fun bitmapSize(source: VisualSource, canvasWidth: Int, canvasHeight: Int): Pair<Int, Int> {
        val width = (canvasWidth * source.transform.width).roundToInt().coerceIn(64, 2048)
        val height = (canvasHeight * source.transform.height).roundToInt().coerceIn(32, 1024)
        return max(16, width) to max(16, height)
    }
}
