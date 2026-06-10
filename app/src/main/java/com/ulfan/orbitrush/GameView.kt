package com.ulfan.orbitrush

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    // ---------- state machine ----------
    private enum class State { MENU, PLAYING, PAUSED, DYING, GAME_OVER, SHOP }

    private var state = State.MENU

    // ---------- engine ----------
    private var thread: Thread? = null
    @Volatile private var running = false
    private var surfaceReady = false

    private val prefs = Prefs(context)
    private val sounds = SoundManager(context)
    private val haptics = Haptics(context)

    // ---------- world ----------
    private var w = 0f
    private var h = 0f
    private var cx = 0f
    private var cy = 0f
    private val ringR = FloatArray(2)        // inner, outer radius
    private var playerR = 0f                 // ball radius
    private var spikeR = 0f                  // spike collision radius

    private var playerAngle = 0.0            // grows forever (radians)
    private var omega = OMEGA_BASE           // angular speed rad/s
    private var ringIndex = 0                // target ring (0 inner, 1 outer)
    private var playerRadius = 0f            // current radius (lerps to target)

    private var score = 0
    private var runGems = 0                  // gems collected this run
    private var newBest = false
    private var deathTimer = 0f
    private var shake = 0f

    private class Spike(val angle: Double, val ring: Int) { var scored = false }
    private class Gem(val angle: Double, val ring: Int)
    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val color: Int
    )

    private val spikes = ArrayList<Spike>()
    private val gems = ArrayList<Gem>()
    private val particles = ArrayList<Particle>()
    private val trail = ArrayList<FloatArray>()   // [x, y, age]
    private val stars = ArrayList<FloatArray>()   // [x, y, phase, size]
    private var nextSpawnAngle = 0.0
    private var lastSpikeAngle = -100.0
    private var lastSpikeRing = -1

    // feedback timers
    private var scorePulse = 0f
    private var bestFlash = 0f
    private var bestAnnounced = false
    private var tutorialTimer = 0f

    // daily streak banner (computed once per launch)
    private val dailyReward = prefs.claimDailyReward()
    private var bannerTimer = if (dailyReward > 0) 5f else 0f

    // ---------- paints ----------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var bgShader: Shader? = null
    private val path = Path()

    // tap targets, rebuilt every frame they're drawn
    private val shopBtn = RectF()
    private val backBtn = RectF()
    private val retryBtn = RectF()
    private val doubleBtn = RectF()
    private val skinCells = ArrayList<Pair<RectF, Skin>>()
    private var rewardedUsed = false

    init {
        holder.addCallback(this)
        isFocusable = true
        AdsManager.init(context as Activity)
    }

    // ---------- lifecycle ----------
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        cx = w / 2f
        cy = h * 0.46f
        val base = min(w, h)
        ringR[0] = base * 0.26f
        ringR[1] = base * 0.40f
        playerR = base * 0.030f
        spikeR = base * 0.034f
        playerRadius = ringR[ringIndex]
        bgShader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#0D0B1E"), Color.parseColor("#1B1040"),
            Shader.TileMode.CLAMP
        )
        if (stars.isEmpty()) {
            repeat(42) {
                stars.add(
                    floatArrayOf(
                        Random.nextFloat() * w,
                        Random.nextFloat() * h,
                        Random.nextFloat() * 6.28f,
                        1.5f + Random.nextFloat() * 2.5f
                    )
                )
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    fun resumeGame() {
        if (surfaceReady) startLoop()
    }

    fun pauseGame() {
        if (state == State.PLAYING) state = State.PAUSED
        stopLoop()
    }

    fun destroyGame() = sounds.release()

    private fun startLoop() {
        if (running) return
        running = true
        thread = Thread(this, "GameLoop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        thread?.join(500)
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - last) / 1_000_000_000f
            last = now
            if (dt > 0.05f) dt = 0.05f
            if (w == 0f) {  // surfaceChanged not delivered yet
                Thread.sleep(16)
                continue
            }
            update(dt)
            val canvas = holder.lockCanvas() ?: continue
            try {
                renderFrame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    // ---------- game logic ----------
    private fun startRun() {
        spikes.clear(); gems.clear(); particles.clear(); trail.clear()
        playerAngle = 0.0
        nextSpawnAngle = 3.2          // ~2.4 s of breathing room before first spike
        lastSpikeAngle = -100.0
        lastSpikeRing = -1
        omega = OMEGA_BASE
        ringIndex = 0
        playerRadius = ringR[0]
        score = 0
        runGems = 0
        newBest = false
        bestAnnounced = false
        rewardedUsed = false
        shake = 0f
        scorePulse = 0f
        bestFlash = 0f
        tutorialTimer = if (prefs.gamesPlayed < 3) 7f else 0f
        state = State.PLAYING
    }

    private fun update(dt: Float) {
        if (shake > 0f) shake = (shake - dt * 60f).coerceAtLeast(0f)
        if (bannerTimer > 0f) bannerTimer -= dt
        if (scorePulse > 0f) scorePulse -= dt
        if (bestFlash > 0f) bestFlash -= dt
        if (tutorialTimer > 0f && state == State.PLAYING) tutorialTimer -= dt
        updateParticles(dt)

        when (state) {
            State.PLAYING -> updatePlaying(dt)
            State.DYING -> {
                deathTimer -= dt
                if (deathTimer <= 0f) finishDeath()
            }
            else -> Unit
        }
    }

    private fun updatePlaying(dt: Float) {
        omega = min(OMEGA_BASE + score * 0.022, OMEGA_MAX)
        playerAngle += omega * dt

        // radius transition between rings
        val target = ringR[ringIndex]
        playerRadius += (target - playerRadius) * min(1f, dt * 14f)

        // trail
        val px = px(); val py = py()
        trail.add(floatArrayOf(px, py, 0f))
        for (t in trail) t[2] += dt
        trail.removeAll { it[2] > 0.35f }

        spawnAhead()

        // spikes: score + collision
        val it = spikes.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (!s.scored && s.angle < playerAngle - 0.25) {
                s.scored = true
                score++
                scorePulse = 0.3f
                if (score > prefs.highScore) {
                    newBest = true
                    if (!bestAnnounced && prefs.highScore > 0) {
                        bestAnnounced = true
                        bestFlash = 1.6f
                        sounds.reward()
                        haptics.gem()
                    }
                }
            }
            if (s.angle < playerAngle - PI) { it.remove(); continue }
            // Only spikes angularly near the player can hit. Without this,
            // a spike spawned ~2π ahead sits at the same screen position as
            // the player (but is culled from rendering) — invisible death.
            if (abs(s.angle - playerAngle) > 1.0) continue
            val sx = cx + cos(s.angle).toFloat() * ringR[s.ring]
            val sy = cy + sin(s.angle).toFloat() * ringR[s.ring]
            if (hypot(px - sx, py - sy) < playerR + spikeR * 0.62f) {
                die()
                return
            }
        }

        // gems
        val gi = gems.iterator()
        while (gi.hasNext()) {
            val g = gi.next()
            if (g.angle < playerAngle - PI) { gi.remove(); continue }
            if (abs(g.angle - playerAngle) > 1.0) continue
            val gx = cx + cos(g.angle).toFloat() * ringR[g.ring]
            val gy = cy + sin(g.angle).toFloat() * ringR[g.ring]
            if (hypot(px - gx, py - gy) < playerR + spikeR * 1.6f) {
                gi.remove()
                runGems++
                sounds.gem()
                haptics.gem()
                burst(gx, gy, Color.parseColor("#4DFFC4"), 10)
            }
        }
    }

    private fun spawnAhead() {
        val horizon = playerAngle + 2.0 * PI
        while (nextSpawnAngle < horizon) {
            val a = nextSpawnAngle
            val ring = Random.nextInt(2)
            val other = 1 - ring
            val roll = Random.nextFloat()
            var end = a
            when {
                roll < 0.50f -> end = addSpike(a, ring)
                roll < 0.70f && score >= 12 -> {       // forced double-switch
                    end = addSpike(a, ring)
                    end = addSpike(end + 1.1, other)
                }
                else -> {                              // risk/reward: gem beside spike
                    end = addSpike(a, ring)
                    gems.add(Gem(end, other))
                }
            }
            if (Random.nextFloat() < 0.20f) {
                gems.add(Gem(end + gap() * 0.5, Random.nextInt(2)))
            }
            nextSpawnAngle = end + gap()
        }
    }

    /**
     * Adds a spike, enforcing fairness: if it sits on the opposite ring from
     * the previous spike, the player needs time to switch — guarantee at
     * least MIN_SWITCH_SECONDS of travel between them at current speed.
     */
    private fun addSpike(angle: Double, ring: Int): Double {
        var a = angle
        if (lastSpikeRing != -1 && ring != lastSpikeRing) {
            val minGap = maxOf(0.85, omega * MIN_SWITCH_SECONDS)
            if (a - lastSpikeAngle < minGap) a = lastSpikeAngle + minGap
        }
        spikes.add(Spike(a, ring))
        lastSpikeAngle = a
        lastSpikeRing = ring
        return a
    }

    private fun gap(): Double {
        val g = 1.55 - score * 0.005
        return g.coerceAtLeast(0.95) * (0.9 + Random.nextDouble() * 0.3)
    }

    private fun die() {
        sounds.crash()
        haptics.crash()
        shake = 26f
        burst(px(), py(), Skins.byId(prefs.selectedSkin).color, 26)
        deathTimer = 0.9f
        state = State.DYING
    }

    private fun finishDeath() {
        prefs.gems += runGems
        if (score > prefs.highScore) prefs.highScore = score
        prefs.gamesPlayed += 1
        state = State.GAME_OVER
        if (prefs.gamesPlayed % AdsManager.INTERSTITIAL_EVERY == 0) {
            (context as? Activity)?.let { AdsManager.showInterstitial(it) {} }
        }
    }

    private fun burst(x: Float, y: Float, color: Int, n: Int) {
        repeat(n) {
            val ang = Random.nextFloat() * 2f * PI.toFloat()
            val sp = Random.nextFloat() * w * 0.45f + w * 0.05f
            particles.add(
                Particle(x, y, cos(ang) * sp, sin(ang) * sp, 0.6f + Random.nextFloat() * 0.3f, color)
            )
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.96f
            p.vy *= 0.96f
        }
    }

    private fun px() = cx + cos(playerAngle).toFloat() * playerRadius
    private fun py() = cy + sin(playerAngle).toFloat() * playerRadius

    // ---------- input ----------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        when (state) {
            State.PLAYING -> {
                ringIndex = 1 - ringIndex
                sounds.tap()
                haptics.tick()
            }
            State.MENU -> {
                if (shopBtn.contains(x, y)) { state = State.SHOP; sounds.tap() }
                else startRun()
            }
            State.PAUSED -> state = State.PLAYING
            State.GAME_OVER -> {
                when {
                    doubleBtn.contains(x, y) && !rewardedUsed && runGems > 0 -> {
                        (context as? Activity)?.let {
                            AdsManager.showRewarded(it) {
                                prefs.gems += runGems
                                runGems *= 2
                                rewardedUsed = true
                                sounds.reward()
                            }
                        }
                    }
                    shopBtn.contains(x, y) -> { state = State.SHOP; sounds.tap() }
                    retryBtn.contains(x, y) || y < h * 0.55f -> startRun()
                }
            }
            State.SHOP -> {
                if (backBtn.contains(x, y)) { state = State.MENU; sounds.tap(); return true }
                for ((rect, skin) in skinCells) {
                    if (rect.contains(x, y)) {
                        if (prefs.isOwned(skin.id)) {
                            prefs.selectedSkin = skin.id
                            sounds.tap()
                        } else if (prefs.gems >= skin.cost) {
                            prefs.gems -= skin.cost
                            prefs.setOwned(skin.id)
                            prefs.selectedSkin = skin.id
                            sounds.reward()
                            haptics.gem()
                        } else {
                            haptics.tick()
                        }
                        break
                    }
                }
            }
            State.DYING -> Unit
        }
        return true
    }

    // ---------- rendering ----------
    private fun renderFrame(canvas: Canvas) {
        // background
        paint.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        canvas.save()
        if (shake > 0f) {
            canvas.translate(
                (Random.nextFloat() - 0.5f) * shake,
                (Random.nextFloat() - 0.5f) * shake
            )
        }

        when (state) {
            State.SHOP -> drawShop(canvas)
            else -> {
                drawWorld(canvas)
                when (state) {
                    State.MENU -> drawMenu(canvas)
                    State.PLAYING, State.DYING -> drawHud(canvas)
                    State.PAUSED -> drawPaused(canvas)
                    State.GAME_OVER -> drawGameOver(canvas)
                    else -> Unit
                }
            }
        }
        canvas.restore()
    }

    private fun drawWorld(canvas: Canvas) {
        val skin = Skins.byId(prefs.selectedSkin)
        val now = System.currentTimeMillis() % 100000L / 1000f

        // twinkling stars
        for (st in stars) {
            paint.color = Color.WHITE
            paint.alpha = (45 + 45 * sin(now * 1.8f + st[2])).toInt().coerceIn(10, 90)
            canvas.drawCircle(st[0], st[1], st[3], paint)
        }
        paint.alpha = 255

        // rings — the player's target ring glows in skin color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = playerR * 0.35f
        for (i in 0..1) {
            if (i == ringIndex && (state == State.PLAYING || state == State.MENU)) {
                paint.color = skin.color
                paint.alpha = 110
            } else {
                paint.color = Color.WHITE
                paint.alpha = 36
            }
            canvas.drawCircle(cx, cy, ringR[i], paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL

        // spikes (subtle pulse so they read as threats)
        paint.color = Color.parseColor("#FF4060")
        for (s in spikes) {
            if (s.angle < playerAngle - PI || s.angle > playerAngle + PI) continue
            drawSpike(canvas, s, 1f + 0.08f * sin(now * 6f + s.angle.toFloat()))
        }

        // gems
        paint.color = Color.parseColor("#4DFFC4")
        for (g in gems) {
            if (g.angle < playerAngle - PI || g.angle > playerAngle + PI) continue
            val gx = cx + cos(g.angle).toFloat() * ringR[g.ring]
            val gy = cy + sin(g.angle).toFloat() * ringR[g.ring]
            drawDiamond(canvas, gx, gy, spikeR * 0.8f)
        }

        // particles
        for (p in particles) {
            paint.color = p.color
            paint.alpha = (255 * (p.life / 0.9f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, playerR * 0.4f, paint)
        }
        paint.alpha = 255

        if (state == State.DYING) return  // ball exploded

        // trail
        for (t in trail) {
            val a = 1f - t[2] / 0.35f
            paint.color = skin.color
            paint.alpha = (90 * a).toInt().coerceIn(0, 90)
            canvas.drawCircle(t[0], t[1], playerR * (0.4f + 0.6f * a), paint)
        }
        paint.alpha = 255

        // player with glow
        paint.color = skin.color
        paint.alpha = 50
        canvas.drawCircle(px(), py(), playerR * 2.0f, paint)
        paint.alpha = 255
        canvas.drawCircle(px(), py(), playerR, paint)
    }

    private fun drawSpike(canvas: Canvas, s: Spike, scale: Float = 1f) {
        val r = ringR[s.ring]
        val sr = spikeR * scale
        val ax = cos(s.angle).toFloat()
        val ay = sin(s.angle).toFloat()
        val bx = cx + ax * r
        val by = cy + ay * r
        // triangle pointing outward from ring line, sized by spikeR
        val tipX = bx + ax * sr
        val tipY = by + ay * sr
        val baseX = bx - ax * sr * 0.6f
        val baseY = by - ay * sr * 0.6f
        // perpendicular
        val pxn = -ay; val pyn = ax
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(baseX + pxn * sr * 0.8f, baseY + pyn * sr * 0.8f)
        path.lineTo(baseX - pxn * sr * 0.8f, baseY - pyn * sr * 0.8f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawDiamond(canvas: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        path.moveTo(x, y - r)
        path.lineTo(x + r * 0.7f, y)
        path.lineTo(x, y + r)
        path.lineTo(x - r * 0.7f, y)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawHud(canvas: Canvas) {
        val pulse = (scorePulse / 0.3f).coerceIn(0f, 1f)
        textPaint.textSize = h * 0.07f * (1f + 0.25f * pulse)
        textPaint.alpha = 255
        canvas.drawText("$score", cx, h * 0.10f, textPaint)
        textPaint.textSize = h * 0.022f
        textPaint.alpha = 170
        canvas.drawText("BEST ${maxOf(prefs.highScore, score)}", cx, h * 0.135f, textPaint)
        canvas.drawText("◆ ${prefs.gems + runGems}", w * 0.88f, h * 0.05f, textPaint)
        textPaint.alpha = 255

        // live "NEW BEST!" celebration when the record is broken mid-run
        if (bestFlash > 0f) {
            textPaint.textSize = h * 0.034f * (1f + 0.15f * sin(bestFlash * 18f))
            textPaint.color = Color.parseColor("#FFD24D")
            canvas.drawText("NEW BEST!", cx, h * 0.175f, textPaint)
            textPaint.color = Color.WHITE
        }

        // first-runs tutorial hint
        if (tutorialTimer > 0f && score < 3 && state == State.PLAYING) {
            val blink = (System.currentTimeMillis() / 500) % 2 == 0L
            if (blink) {
                textPaint.textSize = h * 0.028f
                canvas.drawText("TAP = SWITCH RING", cx, h * 0.86f, textPaint)
            }
        }
    }

    private fun drawMenu(canvas: Canvas) {
        textPaint.textSize = h * 0.062f
        canvas.drawText("ORBIT RUSH", cx, h * 0.14f, textPaint)
        textPaint.textSize = h * 0.024f
        textPaint.alpha = 190
        canvas.drawText("Tap to switch rings. Dodge spikes. Grab gems.", cx, h * 0.185f, textPaint)
        canvas.drawText("BEST ${prefs.highScore}    ◆ ${prefs.gems}", cx, h * 0.225f, textPaint)
        if (prefs.streak > 1) {
            canvas.drawText("🔥 ${prefs.streak}-day streak", cx, h * 0.26f, textPaint)
        }
        textPaint.alpha = 255

        if (bannerTimer > 0f) {
            textPaint.textSize = h * 0.026f
            textPaint.color = Color.parseColor("#4DFFC4")
            canvas.drawText("Daily reward: +$dailyReward ◆", cx, h * 0.30f, textPaint)
            textPaint.color = Color.WHITE
        }

        val blink = (System.currentTimeMillis() / 600) % 2 == 0L
        if (blink) {
            textPaint.textSize = h * 0.034f
            canvas.drawText("TAP TO PLAY", cx, h * 0.78f, textPaint)
        }
        drawButton(canvas, shopBtn, cx - w * 0.18f, h * 0.84f, cx + w * 0.18f, h * 0.895f, "SKINS")
    }

    private fun drawPaused(canvas: Canvas) {
        dim(canvas)
        textPaint.textSize = h * 0.05f
        canvas.drawText("PAUSED", cx, h * 0.45f, textPaint)
        textPaint.textSize = h * 0.026f
        canvas.drawText("Tap to resume", cx, h * 0.51f, textPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        dim(canvas)
        textPaint.textSize = h * 0.05f
        canvas.drawText(if (newBest) "NEW BEST!" else "GAME OVER", cx, h * 0.30f, textPaint)
        textPaint.textSize = h * 0.08f
        canvas.drawText("$score", cx, h * 0.40f, textPaint)
        textPaint.textSize = h * 0.026f
        textPaint.alpha = 190
        canvas.drawText("BEST ${prefs.highScore}     +$runGems ◆", cx, h * 0.45f, textPaint)
        textPaint.alpha = 255

        drawButton(canvas, retryBtn, cx - w * 0.25f, h * 0.58f, cx + w * 0.25f, h * 0.65f, "PLAY AGAIN")
        if (!rewardedUsed && runGems > 0) {
            drawButton(canvas, doubleBtn, cx - w * 0.25f, h * 0.68f, cx + w * 0.25f, h * 0.75f, "▶ 2x GEMS")
        } else {
            doubleBtn.setEmpty()
        }
        drawButton(canvas, shopBtn, cx - w * 0.25f, h * 0.78f, cx + w * 0.25f, h * 0.85f, "SKINS")
    }

    private fun drawShop(canvas: Canvas) {
        textPaint.textSize = h * 0.045f
        canvas.drawText("SKINS", cx, h * 0.10f, textPaint)
        textPaint.textSize = h * 0.026f
        textPaint.alpha = 190
        canvas.drawText("◆ ${prefs.gems}", cx, h * 0.145f, textPaint)
        textPaint.alpha = 255

        skinCells.clear()
        val cols = 3
        val cellW = w / (cols + 1f)
        val startY = h * 0.24f
        Skins.ALL.forEachIndexed { i, skin ->
            val col = i % cols
            val row = i / cols
            val x = cellW * (col + 1f)
            val y = startY + row * h * 0.20f
            val rect = RectF(x - cellW * 0.42f, y - h * 0.075f, x + cellW * 0.42f, y + h * 0.095f)
            skinCells.add(rect to skin)

            val owned = prefs.isOwned(skin.id)
            val selected = prefs.selectedSkin == skin.id

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = if (selected) skin.color else Color.parseColor("#44FFFFFF")
            canvas.drawRoundRect(rect, 24f, 24f, paint)
            paint.style = Paint.Style.FILL

            paint.color = skin.color
            canvas.drawCircle(x, y - h * 0.012f, playerR * 1.4f, paint)

            textPaint.textSize = h * 0.020f
            canvas.drawText(skin.name, x, y + h * 0.045f, textPaint)
            textPaint.textSize = h * 0.018f
            textPaint.alpha = 190
            val label = when {
                selected -> "EQUIPPED"
                owned -> "TAP TO USE"
                else -> "◆ ${skin.cost}"
            }
            canvas.drawText(label, x, y + h * 0.072f, textPaint)
            textPaint.alpha = 255
        }

        drawButton(canvas, backBtn, cx - w * 0.18f, h * 0.86f, cx + w * 0.18f, h * 0.92f, "BACK")
    }

    private fun drawButton(
        canvas: Canvas, rect: RectF,
        l: Float, t: Float, r: Float, b: Float, label: String
    ) {
        rect.set(l, t, r, b)
        paint.color = Color.parseColor("#2A2150")
        canvas.drawRoundRect(rect, 28f, 28f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#66FFFFFF")
        canvas.drawRoundRect(rect, 28f, 28f, paint)
        paint.style = Paint.Style.FILL
        textPaint.textSize = (b - t) * 0.42f
        canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize * 0.35f, textPaint)
    }

    private fun dim(canvas: Canvas) {
        paint.color = Color.parseColor("#B3000000")
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    companion object {
        private const val OMEGA_BASE = 1.35
        private const val OMEGA_MAX = 3.0
        private const val MIN_SWITCH_SECONDS = 0.45   // guaranteed reaction window
    }
}
