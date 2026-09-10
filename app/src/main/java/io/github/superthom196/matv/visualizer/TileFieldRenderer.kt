package io.github.superthom196.matv.visualizer

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Abstract evolving art from a field of 3D tiles, coloured from the album artwork.
 *
 * A large grid of square tiles is drawn with one instanced call; the vertex shader gives each
 * tile its height, rock and colour from slow noise fields that drift across the grid. The
 * overall relief follows the smoothed energy of the music, so it breathes rather than jiggles;
 * bass hits and server beats launch a ripple across the tiles, the only fast movement. A high
 * camera orbits slowly. Colours are the six of Music Assistant's artwork palette, picked per tile
 * by a second drifting field; the background is the artwork's dark tone.
 */
class TileFieldRenderer : GLSurfaceView.Renderer {
    @Volatile var source: BandSource = IdleBands
    /** Camera pitch above the field, degrees. */
    private val tilt = 50f

    private var program = 0
    private var uMvp = 0
    private var uEye = 0
    private var uTime = 0
    private var uPalette = 0
    private var uFog = 0
    private var uScale = 0
    private var vbo = 0
    private var ibo = 0
    private var instVbo = 0
    // bloom
    private var sceneFbo = 0; private var sceneTex = 0; private var sceneDepth = 0
    private var blurFboA = 0; private var blurTexA = 0
    private var blurFboB = 0; private var blurTexB = 0
    private var blurW = 1; private var blurH = 1
    private var sceneW = 1; private var sceneH = 1
    private val sceneScale = 0.65f
    private var brightProgram = 0; private var blurProgram = 0; private var compositeProgram = 0
    private var uBrightTex = 0; private var uBlurTex = 0; private var uBlurDir = 0
    private var uCompScene = 0; private var uCompBloom = 0; private var uCompStrength = 0
    private val field = FloatArray((COLS + 2) * (ROWS + 2))
    private val coarseW = (COLS + 2) / 2 + 2
    private val coarseH = (ROWS + 2) / 2 + 2
    private val coarse = FloatArray(coarseW * coarseH)
    private val colourField = FloatArray(COLS * ROWS)
    private var frameNo = 0
    private var fieldNs = 0L
    private var fieldFrames = 0
    // The field is computed on a worker one frame ahead; the GL thread only uploads.
    private val instFront = FloatArray(COLS * ROWS * 4)
    private var instBack = FloatArray(COLS * ROWS * 4)
    private val workLock = Object()
    private var workT = 0f; private var workAmp = 0f
    private val workRipples = FloatArray(RIPPLES * 4)
    private var workPending = false
    private var resultReady = false
    private var workerStarted = false
    private var frameDt = 0.04f
    private var lastFrameT = 0.0
    private val inst = ByteBuffer.allocateDirect(COLS * ROWS * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var indexCount = 0
    private var width = 1
    private var height = 1
    private val start = System.nanoTime()
    private var frames = 0
    private var lastReport = 0L
    private val mvp = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)

    // music -> motion
    private var energy = 0f
    private var bassAvg = 0f
    private var lastOnset = -10.0
    private val ripples = FloatArray(RIPPLES * 4)   // cx, cz, t0, strength
    private var nextRipple = 0
    private val rnd = Random(3)
    private val smoothPalette = FloatArray(18).also { System.arraycopy(DEFAULT_PALETTE, 0, it, 0, 18) }

    private fun startWorker() {
        if (workerStarted) return
        workerStarted = true
        Thread({
            val local = FloatArray(RIPPLES * 4)
            while (true) {
                val t: Float; val amp: Float
                synchronized(workLock) {
                    while (!workPending) (workLock as Object).wait()
                    workPending = false
                    t = workT; amp = workAmp
                    System.arraycopy(workRipples, 0, local, 0, local.size)
                }
                val t0 = System.nanoTime()
                computeField(t, amp, local, instBack)
                fieldNs += System.nanoTime() - t0; fieldFrames++
                synchronized(workLock) {
                    System.arraycopy(instBack, 0, instFront, 0, instFront.size)
                    resultReady = true
                }
            }
        }, "field").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }.start()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        startWorker()
        program = link(VERTEX, FRAGMENT)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uEye = GLES30.glGetUniformLocation(program, "uEye")
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uPalette = GLES30.glGetUniformLocation(program, "uPalette")
        uFog = GLES30.glGetUniformLocation(program, "uFog")
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        brightProgram = link(FULLSCREEN_VERTEX, BRIGHT)
        uBrightTex = GLES30.glGetUniformLocation(brightProgram, "uTex")
        blurProgram = link(FULLSCREEN_VERTEX, BLUR)
        uBlurTex = GLES30.glGetUniformLocation(blurProgram, "uTex")
        uBlurDir = GLES30.glGetUniformLocation(blurProgram, "uDir")
        compositeProgram = link(FULLSCREEN_VERTEX, COMPOSITE)
        uCompScene = GLES30.glGetUniformLocation(compositeProgram, "uScene")
        uCompBloom = GLES30.glGetUniformLocation(compositeProgram, "uBloom")
        uCompStrength = GLES30.glGetUniformLocation(compositeProgram, "uStrength")
        buildTile()
        for (i in 0 until RIPPLES) ripples[i * 4 + 2] = -100f
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        Log.i(TAG, "renderer: " + GLES30.glGetString(GLES30.GL_RENDERER) + " " + GLES30.glGetString(GLES30.GL_VERSION))
    }

    /** One tile: a box from y=0 to y=1 with a top and four sides, position + normal per vertex. */
    private fun buildTile() {
        val g = 0.42f   // half width, leaving a gap between tiles of cell size 1
        val faces = listOf(
            // normal, then four corners counter-clockwise seen from outside
            floatArrayOf(0f, 1f, 0f, -g, 1f, g, g, 1f, g, g, 1f, -g, -g, 1f, -g),
            floatArrayOf(0f, 0f, 1f, -g, 0f, g, g, 0f, g, g, 1f, g, -g, 1f, g),
            floatArrayOf(0f, 0f, -1f, g, 0f, -g, -g, 0f, -g, -g, 1f, -g, g, 1f, -g),
            floatArrayOf(1f, 0f, 0f, g, 0f, g, g, 0f, -g, g, 1f, -g, g, 1f, g),
            floatArrayOf(-1f, 0f, 0f, -g, 0f, -g, -g, 0f, g, -g, 1f, g, -g, 1f, -g),
        )
        val verts = ByteBuffer.allocateDirect(faces.size * 4 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val idx = ByteBuffer.allocateDirect(faces.size * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        var base = 0
        for (f in faces) {
            for (c in 0 until 4) {
                verts.put(f[3 + c * 3]); verts.put(f[4 + c * 3]); verts.put(f[5 + c * 3])
                verts.put(f[0]); verts.put(f[1]); verts.put(f[2])
            }
            idx.put(base.toShort()); idx.put((base + 1).toShort()); idx.put((base + 2).toShort())
            idx.put(base.toShort()); idx.put((base + 2).toShort()); idx.put((base + 3).toShort())
            base += 4
        }
        verts.flip(); idx.flip()
        indexCount = idx.limit()
        val ids = IntArray(3)
        GLES30.glGenBuffers(3, ids, 0)
        vbo = ids[0]; ibo = ids[1]; instVbo = ids[2]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.limit() * 4, verts, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexCount * 2, idx, GLES30.GL_STATIC_DRAW)
    }

    /** Ripple contribution at grid coords (fx, fz) from the given ripple list. */
    private fun rippleHeight(fx: Float, fz: Float, t: Float, rp: FloatArray, active: Int): Float {
        var h = 0f
        for (i in 0 until active) {
            val age = t - rp[i * 4 + 2]
            val ddx = fx - rp[i * 4]; val ddz = fz - rp[i * 4 + 1]
            val d = kotlin.math.sqrt(ddx * ddx + ddz * ddz)
            val front = d - age * 9f
            if (front * front > 40f) continue
            val far = 1f - ((d - 30f) / 10f).coerceIn(0f, 1f)
            h += rp[i * 4 + 3] * 2.2f * kotlin.math.exp(-front * front / 6f) * kotlin.math.exp(-age * 0.9f) * far
        }
        return h
    }

    /**
     * Compute the per-tile data (height, rock about x, rock about z, colour-field value) into
     * `out`. The noise is smooth, so it is sampled on every second tile and interpolated; the
     * colour field drifts slowly and is refreshed every third call. Runs on the worker thread.
     */
    private fun computeField(t: Float, amp: Float, rp: FloatArray, out: FloatArray) {
        val w = COLS + 2; val hgt = ROWS + 2
        val cx0 = -1f - 0.5f * (COLS - 1); val cz0 = -1f - 0.5f * (ROWS - 1) - ROW_SHIFT
        for (j in 0 until coarseH) for (i in 0 until coarseW) {
            val fx = cx0 + i * 2f + t * 0.9f; val fz = cz0 + j * 2f - t * 0.5f
            var h = 0.5f + 0.5f * Noise.noise(fx * 0.07f, fz * 0.07f, t * 0.04f)
            h += 0.25f * Noise.noise(fx * 0.16f + 40f, fz * 0.16f, t * 0.06f)
            coarse[j * coarseW + i] = h * amp
        }
        // compact the live ripples so the inner loop only sees active ones
        val live = FloatArray(RIPPLES * 4); var active = 0
        for (i in 0 until RIPPLES) {
            val age = t - rp[i * 4 + 2]
            if (age < 0f || age > 6f) continue
            System.arraycopy(rp, i * 4, live, active * 4, 4); active++
        }
        for (j in 0 until hgt) {
            val cj = j / 2; val fj = (j and 1) * 0.5f
            for (i in 0 until w) {
                val ci = i / 2; val fi = (i and 1) * 0.5f
                val a = coarse[cj * coarseW + ci]; val b = coarse[cj * coarseW + ci + 1]
                val c = coarse[(cj + 1) * coarseW + ci]; val d = coarse[(cj + 1) * coarseW + ci + 1]
                var h = (a * (1 - fi) + b * fi) * (1 - fj) + (c * (1 - fi) + d * fi) * fj
                if (active > 0) h += rippleHeight(cx0 + i, cz0 + j, t, live, active)
                field[j * w + i] = h
            }
        }
        if (frameNo % 3 == 0) {
            for (j in 0 until ROWS) for (i in 0 until COLS) {
                val gx = i - 0.5f * (COLS - 1) + t * 0.9f; val gz = j - 0.5f * (ROWS - 1) - t * 0.5f
                colourField[j * COLS + i] = 0.5f + 0.5f * Noise.noise(gx * 0.05f + 200f, gz * 0.05f, t * 0.025f)
            }
        }
        frameNo++
        var o = 0
        for (j in 0 until ROWS) for (i in 0 until COLS) {
            val c = (j + 1) * w + (i + 1)
            out[o] = kotlin.math.max(field[c], 0.12f)
            out[o + 1] = ((field[c + w] - field[c - w]) * 0.18f).coerceIn(-0.45f, 0.45f)
            out[o + 2] = (-(field[c + 1] - field[c - 1]) * 0.18f).coerceIn(-0.45f, 0.45f)
            out[o + 3] = colourField[j * COLS + i]
            o += 4
        }
    }

    /** Upload the latest computed field and ask the worker for the next one. */
    private fun updateField(t: Float, amp: Float) {
        synchronized(workLock) {
            if (resultReady) {
                inst.clear(); inst.put(instFront); inst.flip()
                resultReady = false
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instVbo)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, inst.limit() * 4, inst, GLES30.GL_STREAM_DRAW)
            }
            workT = t + frameDt; workAmp = amp
            System.arraycopy(ripples, 0, workRipples, 0, workRipples.size)
            workPending = true
            (workLock as Object).notify()
        }
    }

    private fun makeTarget(w: Int, h: Int, depth: Boolean): IntArray {
        val fbo = IntArray(1); val tex = IntArray(1); val rb = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        if (depth) {
            GLES30.glGenRenderbuffers(1, rb, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, w, h)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, rb[0])
        }
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) Log.w(TAG, "fbo incomplete: $status")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return intArrayOf(fbo[0], tex[0], rb[0])
    }

    private fun deleteTargets() {
        if (sceneFbo != 0) {
            GLES30.glDeleteFramebuffers(3, intArrayOf(sceneFbo, blurFboA, blurFboB), 0)
            GLES30.glDeleteTextures(3, intArrayOf(sceneTex, blurTexA, blurTexB), 0)
            GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        deleteTargets()
        sceneW = (w * sceneScale).toInt(); sceneH = (h * sceneScale).toInt()
        val scene = makeTarget(sceneW, sceneH, true); sceneFbo = scene[0]; sceneTex = scene[1]; sceneDepth = scene[2]
        blurW = w / 4; blurH = h / 4
        val a = makeTarget(blurW, blurH, false); blurFboA = a[0]; blurTexA = a[1]
        val b = makeTarget(blurW, blurH, false); blurFboB = b[0]; blurTexB = b[1]
        GLES30.glViewport(0, 0, w, h)
        Log.i(TAG, "surface ${w}x$h")
        frames = 0; lastReport = System.nanoTime()
    }

    private fun drive(t: Double, bands: BandSource) {
        val lv = bands.levels
        var mean = 0f
        for (v in lv) mean += v
        mean /= 16f
        energy += (mean - energy) * (if (mean > energy) 0.06f else 0.02f)
        val bass = (lv[0] + lv[1] + lv[2]) / 3f
        bassAvg += (bass - bassAvg) * 0.04f
        val jump = bass - bassAvg
        val serverBeat = bands.beat > 0.9f
        if ((jump > 0.22f || serverBeat) && t - lastOnset > 0.3) {
            lastOnset = t
            val i = nextRipple * 4
            nextRipple = (nextRipple + 1) % RIPPLES
            ripples[i] = (rnd.nextFloat() - 0.5f) * COLS * 0.5f
            ripples[i + 1] = (rnd.nextFloat() - 0.5f) * ROWS * 0.5f - ROW_SHIFT
            ripples[i + 2] = t.toFloat()
            ripples[i + 3] = (0.5f + jump * 2f).coerceIn(0.5f, 1.4f)
        }
        // palette changes only per track; ease into it so a track change is a slow wash
        val p = bands.palette
        for (k in 0 until 18) smoothPalette[k] += (p[k] - smoothPalette[k]) * 0.02f
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val t = (now - start) / 1e9
        val bands = source
        bands.update(t)
        drive(t, bands)

        // fixed camera high above the field; the field itself drifts under it
        val dist = 60f
        val rad = Math.toRadians(tilt.toDouble())
        val eyeY = (dist * sin(rad)).toFloat()
        val ex = 0f
        val ez = (dist * cos(rad)).toFloat()
        Matrix.setLookAtM(view, 0, ex, eyeY, ez, 0f, 0f, -4f, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 36f, sceneW.toFloat() / sceneH, 1f, 300f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, sceneW, sceneH)
        GLES30.glClearColor(smoothPalette[0], smoothPalette[1], smoothPalette[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform3f(uEye, ex, eyeY, ez)
        GLES30.glUniform1f(uTime, t.toFloat())
        frameDt = ((t - lastFrameT).toFloat()).coerceIn(0.02f, 0.1f); lastFrameT = t
        updateField(t.toFloat(), 0.6f + 3.5f * energy)
        GLES30.glUniform3fv(uPalette, 6, smoothPalette, 0)
        GLES30.glUniform3f(uFog, smoothPalette[0], smoothPalette[1], smoothPalette[2])
        GLES30.glUniform1f(uScale, 1f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instVbo)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0, COLS * ROWS)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        bloomAndPresent()
        frames++
        if (now - lastReport >= 10_000_000_000L) {
            val fps = frames / ((now - lastReport) / 1e9f)
            Log.i(TAG, "%dx%d %.1f fps, field %.1f ms".format(width, height, fps, if (fieldFrames > 0) fieldNs / 1e6f / fieldFrames else 0f))
            fieldNs = 0; fieldFrames = 0
            frames = 0; lastReport = now
        }
    }

    /** Bright-pass at quarter size, two blur passes, then scene + bloom to the screen. */
    private fun bloomAndPresent() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboA)
        GLES30.glViewport(0, 0, blurW, blurH)
        GLES30.glUseProgram(brightProgram)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glUniform1i(uBrightTex, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        GLES30.glUseProgram(blurProgram)
        GLES30.glUniform1i(uBlurTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboB)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexA)
        GLES30.glUniform2f(uBlurDir, 1.6f / blurW, 0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboA)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexB)
        GLES30.glUniform2f(uBlurDir, 0f, 1.6f / blurH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(compositeProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glUniform1i(uCompScene, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexA)
        GLES30.glUniform1i(uCompBloom, 1)
        GLES30.glUniform1f(uCompStrength, 0.9f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glBindAttribLocation(p, 1, "aNormal")
        GLES30.glBindAttribLocation(p, 2, "aInst")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link failed: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("compile failed: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        const val TAG = "MATV/visualizer"
        const val COLS = 108
        const val ROWS = 60
        /** Rows are shifted so the field runs from far (-) to just below the camera (+). */
        const val ROW_SHIFT = 9f
        const val RIPPLES = 6

        val VERTEX = """
            #version 300 es
            precision highp float;
            in vec3 aPos;
            in vec3 aNormal;
            in vec4 aInst;   // height, rock about x, rock about z, colour field 0..1
            uniform mat4 uMvp;
            uniform vec3 uPalette[6];
            out vec3 vNormal;
            out vec3 vColor;
            out vec3 vPos;
            out float vGlow;

            const float COLS = ${COLS}.0;
            const float ROWS = ${ROWS}.0;

            void main() {
                int id = gl_InstanceID;
                vec2 g = vec2(float(id % ${COLS}), float(id / ${COLS})) - 0.5 * vec2(COLS - 1.0, ROWS - 1.0) - vec2(0.0, $ROW_SHIFT);
                float h = aInst.x;
                float ax = aInst.y;
                float az = aInst.z;
                mat3 rx = mat3(1.0, 0.0, 0.0, 0.0, cos(ax), sin(ax), 0.0, -sin(ax), cos(ax));
                mat3 rz = mat3(cos(az), sin(az), 0.0, -sin(az), cos(az), 0.0, 0.0, 0.0, 1.0);
                mat3 rot = rz * rx;
                vec3 local = vec3(aPos.x, aPos.y * h, aPos.z);
                vec3 world = rot * local + vec3(g.x, 0.0, g.y);
                vNormal = rot * aNormal;
                vPos = world;

                // colour: the slow field picks among the palette, height lifts towards the light tone
                float s = aInst.w * 5.0;
                int k = int(floor(s));
                float f = smoothstep(0.35, 0.65, fract(s));
                vec3 ordered[6];
                ordered[0] = uPalette[0]; ordered[1] = uPalette[5]; ordered[2] = uPalette[2];
                ordered[3] = uPalette[3]; ordered[4] = uPalette[4]; ordered[5] = uPalette[1];
                vec3 col = mix(ordered[k], ordered[min(k + 1, 5)], f);
                float lift = clamp((h - 0.5) / 4.0, 0.0, 1.0);
                col = mix(col, uPalette[1], lift * 0.35);
                vColor = col;
                vGlow = clamp((h - 2.5) / 3.0, 0.0, 1.0) * 0.6;
                gl_Position = uMvp * vec4(world, 1.0);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 300 es
            precision mediump float;
            in vec3 vNormal;
            in vec3 vColor;
            in vec3 vPos;
            in float vGlow;
            uniform vec3 uEye;
            uniform vec3 uFog;
            uniform float uScale;
            out vec4 fragColor;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(vec3(-0.5, 1.0, 0.35));
                vec3 v = normalize(uEye - vPos);
                vec3 h = normalize(l + v);
                float diff = max(dot(n, l), 0.0);
                float spec = pow(max(dot(n, h), 0.0), 48.0) * 0.35;
                vec3 col = vColor * (0.38 + 0.85 * diff) + spec * vec3(1.0);
                // distance fog into the background tone
                float fog = smoothstep(58.0, 84.0, length(uEye - vPos));
                col += vColor * vGlow;
                col = mix(col, uFog, fog);
                fragColor = vec4(col * uScale, 1.0);
            }
        """.trimIndent()

        val FULLSCREEN_VERTEX = """
            #version 300 es
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
                vUv = p * 0.5 + 0.5;
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """.trimIndent()

        val BRIGHT = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTex;
            out vec4 fragColor;
            void main() {
                vec3 c = texture(uTex, vUv).rgb;
                float l = dot(c, vec3(0.299, 0.587, 0.114));
                float k = smoothstep(0.35, 0.75, l);
                fragColor = vec4(c * k, 1.0);
            }
        """.trimIndent()

        val BLUR = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            out vec4 fragColor;
            void main() {
                vec3 c = texture(uTex, vUv).rgb * 0.227;
                c += (texture(uTex, vUv + uDir * 1.385).rgb + texture(uTex, vUv - uDir * 1.385).rgb) * 0.316;
                c += (texture(uTex, vUv + uDir * 3.231).rgb + texture(uTex, vUv - uDir * 3.231).rgb) * 0.070;
                c += (texture(uTex, vUv + uDir * 5.2).rgb + texture(uTex, vUv - uDir * 5.2).rgb) * 0.02;
                fragColor = vec4(c, 1.0);
            }
        """.trimIndent()

        val COMPOSITE = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uScene;
            uniform sampler2D uBloom;
            uniform float uStrength;
            out vec4 fragColor;
            void main() {
                vec3 s = texture(uScene, vUv).rgb;
                vec3 b = texture(uBloom, vUv).rgb;
                vec3 c = s + b * uStrength;
                fragColor = vec4(c / (1.0 + c * 0.15), 1.0);
            }
        """.trimIndent()

    }
}
