package org.nocturne.emulator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Drawn Car Thing bezel (160x480): four preset buttons across the top, a
 * rotary dial in the middle (drag around it to turn, tap center to press),
 * and back + settings buttons at the bottom. Events are forwarded as the
 * exact hardware codes the web UI listens for.
 */
@SuppressLint("ClickableViewAccessibility")
class BezelView(
    context: Context,
    private val onKey: (String) -> Unit,  // receives "key|code"
    private val onDial: (Int) -> Unit,
) : View(context) {

    // key is what the main UI reads (e.key); code is what Mockingbird reads (e.code)
    private data class Button(val rect: RectF, val label: String, val key: String, val code: String, var pressed: Boolean = false)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF141414.toInt() }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E2E2E.toInt() }
    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF535353.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 14f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E2E2E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D3D3D.toInt() }

    private val presetButtons = mutableListOf<Button>()
    private lateinit var backButton: Button
    private lateinit var settingsButton: Button
    private var dialCenterX = 0f
    private var dialCenterY = 0f
    private var dialRadius = 0f
    private var dialPressed = false
    private var lastDialAngle = Float.NaN
    private var dialAccumulator = 0f

    init {
        setBackgroundColor(0xFF141414.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        presetButtons.clear()
        val presetH = h * 0.10f
        val presetW = w / 4f
        for (i in 0 until 4) {
            presetButtons += Button(
                RectF(i * presetW + 4f, 4f, (i + 1) * presetW - 4f, presetH - 4f),
                "${i + 1}",
                "${i + 1}",
                "Digit${i + 1}",
            )
        }

        val dialBottom = h * 0.72f
        val dialTop = presetH + h * 0.04f
        dialCenterX = w / 2f
        dialCenterY = (dialTop + dialBottom) / 2f
        dialRadius = minOf(w * 0.42f, (dialBottom - dialTop) / 2f)

        val navTop = dialBottom + h * 0.02f
        val navH = h - navTop - 8f
        backButton = Button(RectF(4f, navTop, w / 2f - 2f, navTop + navH), "BCK", "Escape", "Escape")
        settingsButton = Button(RectF(w / 2f + 2f, navTop, w - 4f, navTop + navH), "SET", "m", "KeyM")
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (button in presetButtons + listOf(backButton, settingsButton)) {
            canvas.drawRoundRect(
                button.rect, 8f, 8f,
                if (button.pressed) buttonPressedPaint else buttonPaint,
            )
            canvas.drawText(
                button.label,
                button.rect.centerX(),
                button.rect.centerY() + labelPaint.textSize / 3f,
                labelPaint,
            )
        }

        canvas.drawCircle(dialCenterX, dialCenterY, dialRadius, ringPaint)
        canvas.drawCircle(
            dialCenterX, dialCenterY, dialRadius * 0.55f,
            if (dialPressed) buttonPressedPaint else centerPaint,
        )
        canvas.drawText("O", dialCenterX, dialCenterY + labelPaint.textSize / 3f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hitButton(presetButtons, x, y)?.let { it.pressed = true }
                hitButton(listOf(backButton, settingsButton), x, y)?.let { it.pressed = true }
                val dx = x - dialCenterX
                val dy = y - dialCenterY
                val dist = kotlin.math.hypot(dx, dy)
                if (dist <= dialRadius * 0.55f) {
                    dialPressed = true
                } else if (dist <= dialRadius * 1.2f) {
                    lastDialAngle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    dialAccumulator = 0f
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!lastDialAngle.isNaN()) {
                    val dx = x - dialCenterX
                    val dy = y - dialCenterY
                    val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    var delta = angle - lastDialAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastDialAngle = angle
                    dialAccumulator += delta
                    while (abs(dialAccumulator) >= STEP_DEGREES) {
                        onDial(if (dialAccumulator > 0) 1 else -1)
                        dialAccumulator -= STEP_DEGREES * Math.signum(dialAccumulator)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val released = presetButtons + listOf(backButton, settingsButton)
                released.filter { it.pressed }.forEach {
                    it.pressed = false
                    if (it.rect.contains(x, y)) onKey(it.key + "|" + it.code)
                }
                if (dialPressed) {
                    dialPressed = false
                    val dx = x - dialCenterX
                    val dy = y - dialCenterY
                    if (kotlin.math.hypot(dx, dy) <= dialRadius * 0.55f && event.actionMasked == MotionEvent.ACTION_UP) {
                        onKey("Enter|Enter")
                    }
                }
                lastDialAngle = Float.NaN
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitButton(buttons: List<Button>, x: Float, y: Float): Button? =
        buttons.firstOrNull { it.rect.contains(x, y) }

    companion object {
        private const val STEP_DEGREES = 15f
    }
}
