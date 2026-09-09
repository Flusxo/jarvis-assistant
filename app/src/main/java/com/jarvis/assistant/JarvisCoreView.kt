package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class JarvisCoreView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, MAP }
    private var state = State.IDLE
    private var rotation = 0f
    private var phase = 0f
    private var hue = 195f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particles = ArrayList<Particle>()
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 9000L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            rotation = it.animatedValue as Float
            phase += 0.07f
            if (state == State.IDLE) {
                hue += 0.22f
                if (hue >= 360f) hue -= 360f
            }
            invalidate()
        }
    }
    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        repeat(90) { particles += Particle(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 2.2f + 0.5f, Random.nextFloat() * 6.28318f, Random.nextFloat() * 0.7f + 0.3f) }
        animator.start()
    }
    fun setState(newState: State) { state = newState; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.38f
        val color = stateColor()
        val c1 = Color.argb(125, Color.red(color), Color.green(color), Color.blue(color))
        val c2 = Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))
        glowPaint.shader = RadialGradient(cx, cy, maxOf(width, height) * 0.75f, intArrayOf(c1, c2, Color.TRANSPARENT), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        glowPaint.shader = null
        drawGrid(canvas, color)
        drawParticles(canvas, color)
        drawCore(canvas, cx, cy, color)
    }
    private fun stateColor(): Int = when (state) {
        State.IDLE -> Color.HSVToColor(floatArrayOf(hue, 0.72f, 1f))
        State.LISTENING -> Color.rgb(0, 224, 255)
        State.THINKING -> Color.rgb(154, 95, 255)
        State.SPEAKING -> Color.rgb(0, 255, 202)
        State.MAP -> Color.rgb(60, 155, 255)
    }
    private fun drawGrid(canvas: Canvas, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(20, Color.red(color), Color.green(color), Color.blue(color))
        var x = 0f
        while (x <= width) { canvas.drawLine(x, 0f, x, height.toFloat(), paint); x += 75f }
        var y = 0f
        while (y <= height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += 75f }
    }
    private fun drawParticles(canvas: Canvas, color: Int) {
        paint.style = Paint.Style.FILL
        particles.forEach { p ->
            p.angle += 0.002f * p.speed
            val driftX = sin(p.angle.toDouble()).toFloat() * 20f
            val driftY = cos((p.angle * 0.8f).toDouble()).toFloat() * 14f
            val pulse = (sin((phase * p.speed + p.angle).toDouble()).toFloat() + 1f) / 2f
            paint.color = Color.argb((25f + pulse * 105f).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(p.x * width + driftX, p.y * height + driftY, p.size, paint)
        }
    }
    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val base = minOf(width, height) * 0.105f
        val scale = when (state) { State.IDLE -> 1f; State.LISTENING -> 1.12f; State.THINKING -> 1.05f; State.SPEAKING -> 1.18f; State.MAP -> 1.04f }
        val radius = base * scale * (1f + sin(phase.toDouble()).toFloat() * 0.055f)
        glowPaint.shader = RadialGradient(cx, cy, radius * 5f, intArrayOf(Color.argb(180, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(65, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT), floatArrayOf(0f, 0.34f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 5f, glowPaint)
        glowPaint.shader = null
        paint.style = Paint.Style.STROKE
        for (i in 0..4) {
            val rr = radius * (1.55f + i * 0.42f)
            paint.strokeWidth = if (i == 0) 4f else 1.5f
            paint.color = Color.argb(155 - i * 22, Color.red(color), Color.green(color), Color.blue(color))
            val rect = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.save(); canvas.rotate(rotation * if (i % 2 == 0) 1f else -0.65f, cx, cy)
            canvas.drawArc(rect, 15f + i * 33f, 205f - i * 18f, false, paint)
            canvas.restore()
        }
        paint.style = Paint.Style.FILL
        for (i in 0 until 8) {
            val angle = Math.toRadians((rotation * 0.75f + i * 45f).toDouble())
            val rr = radius * 2.45f
            paint.color = Color.argb(190, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(cx + cos(angle).toFloat() * rr, cy + sin(angle).toFloat() * rr, 2.7f, paint)
        }
        paint.shader = RadialGradient(cx - radius * 0.28f, cy - radius * 0.28f, radius * 1.15f, intArrayOf(Color.WHITE, Color.rgb(minOf(255, Color.red(color) + 75), minOf(255, Color.green(color) + 75), minOf(255, Color.blue(color) + 75)), color), floatArrayOf(0f, 0.24f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint); paint.shader = null
        paint.color = Color.WHITE; canvas.drawCircle(cx, cy, radius * 0.16f, paint)
        if (state == State.SPEAKING || state == State.LISTENING) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
            for (i in 0 until 18) {
                val angle = i * 20f + rotation
                val wave = 0.2f * sin((phase * 2.5f + i).toDouble()).toFloat()
                val r1 = radius * 1.5f; val r2 = radius * (2f + wave); val a = Math.toRadians(angle.toDouble())
                paint.color = Color.argb(180, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawLine(cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, paint)
            }
        }
        if (state == State.MAP) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            paint.color = Color.argb(125, Color.red(color), Color.green(color), Color.blue(color))
            val r = radius * 3.25f
            canvas.drawCircle(cx, cy, r, paint); canvas.drawLine(cx - r, cy, cx + r, cy, paint); canvas.drawLine(cx, cy - r, cx, cy + r, paint)
        }
    }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    private data class Particle(val x: Float, val y: Float, val size: Float, var angle: Float, val speed: Float)
}
