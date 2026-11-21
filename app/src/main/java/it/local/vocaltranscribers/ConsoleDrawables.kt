package it.local.vocaltranscribers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Draws the hard-cornered frame used by the app's console panels and controls.
 *
 * @param fillColor interior color.
 * @param edgeColor primary frame color.
 * @param signalColor highlighted navigation marks.
 * @param density screen density used to scale the drawing details.
 */
internal class ConsoleFrameDrawable(
    private val fillColor: Int,
    private val edgeColor: Int,
    private val signalColor: Int,
    private val density: Float,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val inset = density * 1.5f
        val cut = density * 8f
        val frame = RectF(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
        )
        val outline = chamferedPath(frame, cut)

        fillPaint.color = withAlpha(fillColor)
        edgePaint.color = withAlpha(edgeColor)
        detailPaint.color = withAlpha(edgeColor, 88)
        accentPaint.color = withAlpha(signalColor)
        canvas.drawPath(outline, fillPaint)
        canvas.drawPath(outline, edgePaint)

        val inner = RectF(frame).apply { inset(density * 4f, density * 4f) }
        if (inner.width() > cut * 2f && inner.height() > cut * 2f) {
            canvas.drawPath(chamferedPath(inner, cut * 0.65f), detailPaint)
        }

        val railX = frame.left + density * 4f
        canvas.drawRect(railX, frame.top + density * 19f, railX + density * 2f, frame.top + density * 39f, accentPaint)
        canvas.drawRect(frame.right - density * 34f, frame.top + density * 3f, frame.right - density * 12f, frame.top + density * 4f, accentPaint)

        // Segmented data marks at the lower right give each surface a console bezel.
        val markY = frame.bottom - density * 5f
        repeat(3) { index ->
            val markWidth = density * if (index == 2) 5f else 9f
            val markLeft = frame.right - density * (15f + index * 13f)
            canvas.drawRect(markLeft, markY, markLeft + markWidth, markY + density, accentPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        edgePaint.colorFilter = colorFilter
        detailPaint.colorFilter = colorFilter
        accentPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun chamferedPath(rect: RectF, cut: Float) = Path().apply {
        moveTo(rect.left + cut, rect.top)
        lineTo(rect.right - cut, rect.top)
        lineTo(rect.right, rect.top + cut)
        lineTo(rect.right, rect.bottom - cut)
        lineTo(rect.right - cut, rect.bottom)
        lineTo(rect.left + cut, rect.bottom)
        lineTo(rect.left, rect.bottom - cut)
        lineTo(rect.left, rect.top + cut)
        close()
    }

    private fun withAlpha(color: Int, alpha: Int = Color.alpha(color)): Int =
        Color.argb(alpha * drawableAlpha / 255, Color.red(color), Color.green(color), Color.blue(color))
}

/** Draws a restrained star grid behind all screens. */
internal class ConsoleNightDrawable(
    private val backgroundColor: Int,
    private val gridColor: Int,
    private val density: Float,
) : Drawable() {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var drawableAlpha = 255
    private val stars = arrayOf(
        0.08f to 0.18f, 0.22f to 0.39f, 0.87f to 0.23f, 0.74f to 0.58f,
        0.12f to 0.76f, 0.92f to 0.82f, 0.54f to 0.91f, 0.39f to 0.11f,
        0.64f to 0.34f, 0.29f to 0.68f, 0.96f to 0.48f, 0.48f to 0.52f,
    )

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        backgroundPaint.color = withAlpha(backgroundColor)
        canvas.drawRect(bounds, backgroundPaint)

        gridPaint.color = withAlpha(gridColor, 19)
        val gridSpacing = density * 72f
        var x = bounds.left + gridSpacing
        while (x < bounds.right) {
            canvas.drawLine(x, bounds.top.toFloat(), x, bounds.bottom.toFloat(), gridPaint)
            x += gridSpacing
        }
        var y = bounds.top + gridSpacing
        while (y < bounds.bottom) {
            canvas.drawLine(bounds.left.toFloat(), y.toFloat(), bounds.right.toFloat(), y.toFloat(), gridPaint)
            y += gridSpacing
        }

        starPaint.color = withAlpha(gridColor, 105)
        stars.forEachIndexed { index, (xRatio, yRatio) ->
            val radius = density * if (index % 4 == 0) 1.3f else 0.7f
            canvas.drawCircle(
                bounds.left + bounds.width() * xRatio,
                bounds.top + bounds.height() * yRatio,
                radius,
                starPaint,
            )
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        backgroundPaint.colorFilter = colorFilter
        gridPaint.colorFilter = colorFilter
        starPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private fun withAlpha(color: Int, alpha: Int = Color.alpha(color)): Int =
        Color.argb(alpha * drawableAlpha / 255, Color.red(color), Color.green(color), Color.blue(color))
}
