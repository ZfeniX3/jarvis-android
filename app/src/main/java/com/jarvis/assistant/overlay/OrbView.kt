package com.jarvis.assistant.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.assistant.Phase
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bolinha estilo Jarvis: núcleo pulsante, anéis e brilho. A cor e a animação mudam com a fase.
 * Toque = falar; arrastar = mover; segurar = abrir o app.
 */
class OrbView(context: Context) : View(context) {

    var onTap: () -> Unit = {}
    var onLongPress: () -> Unit = {}
    var onDrag: (dx: Int, dy: Int) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}

    var phase: Phase = Phase.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Nível do microfone (0..1), usado para "respirar" enquanto ouve. */
    var level: Float = 0f
        set(value) {
            field = value
            if (phase == Phase.LISTENING) invalidate()
        }

    private val sizePx = (SIZE_DP * resources.displayMetrics.density).toInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val cyan = ContextCompat.getColor(context, R.color.jarvis_cyan)
    private val amber = ContextCompat.getColor(context, R.color.jarvis_amber)
    private val red = ContextCompat.getColor(context, R.color.jarvis_red)
    private val green = ContextCompat.getColor(context, R.color.jarvis_green)
    private val dark = ContextCompat.getColor(context, R.color.jarvis_bg)

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val arcBounds = RectF()

    private var t = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedFraction
            invalidate()
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressed = true
            onLongPress()
        }
    }

    init {
        contentDescription = context.getString(R.string.orb_content_description)
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    private fun phaseColor(): Int = when (phase) {
        Phase.THINKING, Phase.CONFIRMING -> amber
        Phase.ACTING -> green
        Phase.ERROR -> red
        else -> cyan
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = width / 2f
        val color = phaseColor()
        val wave = sin(t * 2f * Math.PI.toFloat())

        val active = phase != Phase.IDLE
        val pulse = when (phase) {
            Phase.LISTENING -> 0.08f + 0.22f * level
            Phase.SPEAKING -> 0.10f * (wave * 0.5f + 0.5f)
            Phase.IDLE -> 0.03f * (wave * 0.5f + 0.5f)
            else -> 0.06f * (wave * 0.5f + 0.5f)
        }

        // brilho em camadas (sem shaders, para não alocar a cada quadro)
        val glowAlpha = if (active) 1f else 0.6f
        glow.color = color
        glow.alpha = (26 * glowAlpha).toInt()
        canvas.drawCircle(cx, cy, base * (0.98f + pulse * 0.4f), glow)
        glow.alpha = (40 * glowAlpha).toInt()
        canvas.drawCircle(cx, cy, base * (0.82f + pulse * 0.5f), glow)

        // fundo escuro do disco
        core.color = dark
        core.alpha = 235
        canvas.drawCircle(cx, cy, base * 0.66f, core)

        // anel externo: gira quando pensando/agindo; fixo nas outras fases
        ring.color = color
        ring.alpha = 255
        arcBounds.set(cx - base * 0.66f, cy - base * 0.66f, cx + base * 0.66f, cy + base * 0.66f)
        if (phase == Phase.THINKING || phase == Phase.ACTING) {
            canvas.drawArc(arcBounds, t * 360f, 110f, false, ring)
            canvas.drawArc(arcBounds, t * 360f + 180f, 110f, false, ring)
        } else {
            ring.alpha = if (active) 255 else 170
            canvas.drawCircle(cx, cy, base * 0.66f, ring)
        }

        // anel interno, em contra-rotação
        ring.alpha = 120
        arcBounds.set(cx - base * 0.48f, cy - base * 0.48f, cx + base * 0.48f, cy + base * 0.48f)
        canvas.drawArc(arcBounds, -t * 360f, 250f, false, ring)

        // núcleo
        core.color = color
        core.alpha = if (active) 255 else 200
        canvas.drawCircle(cx, cy, base * (0.22f + pulse), core)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                dragging = false
                longPressed = false
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    onDrag((event.rawX - lastX).toInt(), (event.rawY - lastY).toInt())
                    lastX = event.rawX
                    lastY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                when {
                    dragging -> onDragEnd()
                    !longPressed && abs(event.rawX - downX) <= slop && abs(event.rawY - downY) <= slop -> performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (dragging) onDragEnd()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    companion object {
        const val SIZE_DP = 72
    }
}
