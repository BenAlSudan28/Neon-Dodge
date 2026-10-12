package com.neondodge.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context), Choreographer.FrameCallback {

    private enum class State { READY, PLAYING, PAUSED, OVER }

    private class Block(
        var x: Float, var y: Float, val size: Float,
        val speed: Float, var angle: Float, val spin: Float
    )

    private class Orb(var x: Float, var y: Float, val speed: Float, val phase: Float)
    private class Star(val x: Float, var y: Float, val r: Float, val speed: Float)
    private class Spark(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val color: Int
    )

    private val cyan = Color.parseColor("#00E5FF")
    private val pink = Color.parseColor("#FF2E93")
    private val violet = Color.parseColor("#7C4DFF")
    private val white = Color.WHITE

    private val prefs = context.getSharedPreferences("neon_dodge", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)

    private var state = State.READY
    private var running = false
    private var lastNanos = 0L

    private val blocks = ArrayList<Block>()
    private val orbs = ArrayList<Orb>()
    private val stars = ArrayList<Star>()
    private val sparks = ArrayList<Spark>()

    private var w = 0f
    private var h = 0f
    private var u = 1f

    private var playerX = 0f
    private var targetX = 0f
    private var scoreF = 0f
    private var elapsed = 0f
    private var spawnTimer = 0f
    private var orbTimer = 0f
    private var pulse = 0f
    private var shake = 0f
    private var lives = 3
    private var invincible = 0f
    private var overTimer = 0f
    private var newRecord = false

    private val bgPaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val normalFace = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val boldFace = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val path = Path()
    private val rect = RectF()

    private val playerY: Float get() = h * 0.82f

    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    private fun difficulty(): Float = min(1f + elapsed / 25f, 3f)

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        w = nw.toFloat()
        h = nh.toFloat()
        u = w / 360f
        playerX = w / 2f
        targetX = playerX
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#0B0220"),
                Color.parseColor("#1A0B3D"),
                Color.parseColor("#06101F")
            ),
            null, Shader.TileMode.CLAMP
        )
        stars.clear()
        repeat(70) {
            stars.add(
                Star(
                    Random.nextFloat() * w,
                    Random.nextFloat() * h,
                    (0.6f + Random.nextFloat() * 1.8f) * u,
                    (20f + Random.nextFloat() * 80f) * u
                )
            )
        }
    }

    // ---------- دورة الحياة ----------

    fun resume() {
        if (!running) {
            running = true
            lastNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (state == State.PLAYING) state = State.PAUSED
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 0.016f
        else ((frameTimeNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
        lastNanos = frameTimeNanos
        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------- منطق اللعبة ----------

    private fun startGame() {
        blocks.clear()
        orbs.clear()
        sparks.clear()
        scoreF = 0f
        elapsed = 0f
        spawnTimer = 0.6f
        orbTimer = 2f
        lives = 3
        invincible = 0f
        newRecord = false
        playerX = w / 2f
        targetX = playerX
        state = State.PLAYING
    }

    private fun gameOver() {
        state = State.OVER
        overTimer = 0.8f
        val s = scoreF.toInt()
        if (s > best) {
            best = s
            newRecord = true
            prefs.edit().putInt("best", best).apply()
        }
        burst(playerX, playerY, pink, 40)
    }

    private fun burst(x: Float, y: Float, color: Int, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * (2f * PI.toFloat())
            val sp = (60f + Random.nextFloat() * 220f) * u
            sparks.add(
                Spark(x, y, cos(a) * sp, sin(a) * sp, 0.5f + Random.nextFloat() * 0.5f, color)
            )
        }
    }

    private fun spawnBlock() {
        val size = (28f + Random.nextFloat() * 34f) * u
        val x = size / 2f + Random.nextFloat() * (w - size)
        val speed = (150f + Random.nextFloat() * 110f) * u * difficulty()
        blocks.add(
            Block(x, -size, size, speed, Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 240f)
        )
    }

    private fun spawnOrb() {
        val x = 20f * u + Random.nextFloat() * (w - 40f * u)
        orbs.add(Orb(x, -20f * u, 170f * u, Random.nextFloat() * 6f))
    }

    private fun update(dt: Float) {
        pulse += dt
        if (overTimer > 0f) overTimer -= dt
        if (shake > 0f) shake -= dt

        val boost = if (state == State.PLAYING) 1f + difficulty() * 0.5f else 1f
        for (s in stars) {
            s.y += s.speed * dt * boost
            if (s.y > h) s.y = -s.r
        }

        var i = sparks.size - 1
        while (i >= 0) {
            val p = sparks[i]
            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.life <= 0f) sparks.removeAt(i)
            i--
        }

        if (state != State.PLAYING) return

        elapsed += dt
        scoreF += dt * 10f * difficulty()
        if (invincible > 0f) invincible -= dt
        playerX += (targetX - playerX) * min(1f, dt * 14f)

        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnBlock()
            spawnTimer = maxOf(0.28f, 0.85f / difficulty())
        }
        orbTimer -= dt
        if (orbTimer <= 0f) {
            spawnOrb()
            orbTimer = 1.8f + Random.nextFloat() * 1.5f
        }

        val py = playerY
        val pr = 16f * u

        i = blocks.size - 1
        while (i >= 0) {
            val b = blocks[i]
            b.y += b.speed * dt
            b.angle += b.spin * dt
            if (b.y - b.size > h) {
                blocks.removeAt(i)
                scoreF += 3f
            } else if (invincible <= 0f &&
                hypot(b.x - playerX, b.y - py) < pr * 0.7f + b.size * 0.45f
            ) {
                blocks.removeAt(i)
                burst(b.x, b.y, pink, 18)
                lives--
                shake = 0.35f
                invincible = 1.5f
                if (lives <= 0) {
                    gameOver()
                    return
                }
            }
            i--
        }

        i = orbs.size - 1
        while (i >= 0) {
            val o = orbs[i]
            o.y += o.speed * dt
            if (o.y > h + 20f * u) {
                orbs.removeAt(i)
            } else if (hypot(o.x - playerX, o.y - py) < pr + 12f * u) {
                orbs.removeAt(i)
                scoreF += 50f
                burst(o.x, o.y, cyan, 14)
            }
            i--
        }
    }

    // ---------- اللمس ----------

    private fun setTarget(x: Float) {
        val m = 18f * u
        targetX = x.coerceIn(m, w - m)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when (state) {
                    State.READY -> startGame()
                    State.PAUSED -> state = State.PLAYING
                    State.OVER -> if (overTimer <= 0f) startGame()
                    State.PLAYING -> {}
                }
                setTarget(e.x)
            }
            MotionEvent.ACTION_MOVE -> setTarget(e.x)
        }
        return true
    }

    // ---------- الرسم ----------

    private fun drawCenter(c: Canvas, s: String, y: Float, size: Float, color: Int, bold: Boolean = false) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size
        text.color = color
        text.typeface = if (bold) boldFace else normalFace
        c.drawText(s, w / 2f, y, text)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        canvas.save()
        if (shake > 0f) {
            canvas.translate(
                (Random.nextFloat() - 0.5f) * 2f * shake * 20f * u,
                (Random.nextFloat() - 0.5f) * 2f * shake * 20f * u
            )
        }

        // نجوم الخلفية
        for (s in stars) {
            fill.color = withAlpha(white, (60 + s.r / u * 50f).toInt())
            canvas.drawCircle(s.x, s.y, s.r, fill)
        }

        // الكرات
        for (o in orbs) {
            val r = (9f + 2f * sin(pulse * 6f + o.phase)) * u
            fill.color = withAlpha(cyan, 45)
            canvas.drawCircle(o.x, o.y, r * 2.2f, fill)
            fill.color = withAlpha(cyan, 110)
            canvas.drawCircle(o.x, o.y, r * 1.4f, fill)
            fill.color = cyan
            canvas.drawCircle(o.x, o.y, r, fill)
        }

        // الكتل
        for (b in blocks) {
            canvas.save()
            canvas.translate(b.x, b.y)
            canvas.rotate(b.angle)
            val hs = b.size / 2f
            rect.set(-hs * 1.3f, -hs * 1.3f, hs * 1.3f, hs * 1.3f)
            fill.color = withAlpha(pink, 40)
            canvas.drawRoundRect(rect, 8f * u, 8f * u, fill)
            rect.set(-hs, -hs, hs, hs)
            fill.color = withAlpha(pink, 70)
            canvas.drawRoundRect(rect, 6f * u, 6f * u, fill)
            stroke.color = pink
            stroke.strokeWidth = 3f * u
            canvas.drawRoundRect(rect, 6f * u, 6f * u, stroke)
            canvas.restore()
        }

        // السفينة
        if (state != State.OVER) {
            val blink = invincible > 0f && ((invincible * 12f).toInt() % 2 == 0)
            if (!blink) drawShip(canvas)
        }

        // الشرارات
        for (p in sparks) {
            fill.color = withAlpha(p.color, (p.life.coerceIn(0f, 1f) * 255f).toInt())
            canvas.drawCircle(p.x, p.y, 2.5f * u, fill)
        }

        canvas.restore()

        drawHud(canvas)

        when (state) {
            State.READY -> {
                fill.color = withAlpha(Color.BLACK, 120)
                canvas.drawRect(0f, 0f, w, h, fill)
                drawCenter(canvas, "NEON DODGE", h * 0.30f, 44f * u, cyan, true)
                drawCenter(canvas, "تفادَ الكتل الوردية", h * 0.40f, 20f * u, white)
                drawCenter(canvas, "واجمع الكرات المضيئة", h * 0.40f + 30f * u, 20f * u, white)
                val a = (150 + 105 * sin(pulse * 4f)).toInt()
                drawCenter(canvas, "المس للبدء", h * 0.58f, 26f * u, withAlpha(violet, 255).let { withAlpha(white, a) }, true)
                if (best > 0) {
                    drawCenter(canvas, "الأفضل", h * 0.68f, 16f * u, withAlpha(white, 180))
                    drawCenter(canvas, best.toString(), h * 0.68f + 34f * u, 30f * u, cyan, true)
                }
            }
            State.PAUSED -> {
                fill.color = withAlpha(Color.BLACK, 150)
                canvas.drawRect(0f, 0f, w, h, fill)
                drawCenter(canvas, "متوقف مؤقتاً", h * 0.42f, 32f * u, white, true)
                drawCenter(canvas, "المس للمتابعة", h * 0.50f, 20f * u, withAlpha(white, 200))
            }
            State.OVER -> {
                fill.color = withAlpha(Color.BLACK, 150)
                canvas.drawRect(0f, 0f, w, h, fill)
                drawCenter(canvas, "انتهت اللعبة", h * 0.30f, 36f * u, pink, true)
                drawCenter(canvas, "النقاط", h * 0.40f, 18f * u, withAlpha(white, 180))
                drawCenter(canvas, scoreF.toInt().toString(), h * 0.40f + 52f * u, 52f * u, white, true)
                if (newRecord) {
                    drawCenter(canvas, "رقم قياسي جديد!", h * 0.40f + 92f * u, 22f * u, cyan, true)
                } else {
                    drawCenter(canvas, "الأفضل", h * 0.40f + 90f * u, 16f * u, withAlpha(white, 180))
                    drawCenter(canvas, best.toString(), h * 0.40f + 120f * u, 26f * u, cyan, true)
                }
                if (overTimer <= 0f) {
                    val a = (150 + 105 * sin(pulse * 4f)).toInt()
                    drawCenter(canvas, "المس للعب مجدداً", h * 0.72f, 24f * u, withAlpha(white, a), true)
                }
            }
            State.PLAYING -> {}
        }
    }

    private fun drawShip(canvas: Canvas) {
        val x = playerX
        val y = playerY
        val pr = 16f * u

        fill.color = withAlpha(cyan, 35)
        canvas.drawCircle(x, y, pr * 2.2f, fill)

        // اللهب
        val flame = pr * (1.2f + 0.5f * sin(pulse * 30f))
        path.reset()
        path.moveTo(x - pr * 0.4f, y + pr * 0.7f)
        path.lineTo(x + pr * 0.4f, y + pr * 0.7f)
        path.lineTo(x, y + pr * 0.7f + flame)
        path.close()
        fill.color = withAlpha(pink, 200)
        canvas.drawPath(path, fill)

        // الجسم
        path.reset()
        path.moveTo(x, y - pr * 1.3f)
        path.lineTo(x + pr, y + pr)
        path.lineTo(x, y + pr * 0.5f)
        path.lineTo(x - pr, y + pr)
        path.close()
        fill.color = withAlpha(cyan, 70)
        canvas.drawPath(path, fill)
        stroke.color = cyan
        stroke.strokeWidth = 3f * u
        canvas.drawPath(path, stroke)
    }

    private fun drawHud(canvas: Canvas) {
        val top = 60f * u
        text.typeface = boldFace
        text.textAlign = Paint.Align.LEFT
        text.textSize = 30f * u
        text.color = white
        canvas.drawText(scoreF.toInt().toString(), 18f * u, top, text)

        text.textAlign = Paint.Align.RIGHT
        text.textSize = 13f * u
        text.color = withAlpha(white, 160)
        canvas.drawText("الأفضل", w - 18f * u, top - 22f * u, text)
        text.typeface = boldFace
        text.textSize = 22f * u
        text.color = cyan
        canvas.drawText(best.toString(), w - 18f * u, top, text)

        for (k in 0 until 3) {
            val cx = 24f * u + k * 22f * u
            val cy = top + 24f * u
            if (k < lives) {
                fill.color = pink
                canvas.drawCircle(cx, cy, 7f * u, fill)
            } else {
                stroke.color = withAlpha(pink, 120)
                stroke.strokeWidth = 2f * u
                canvas.drawCircle(cx, cy, 7f * u, stroke)
            }
        }
    }
}
