package io.github.superthom196.matv.visualizer

/** Whatever drives the picture: 16 band levels 0..1 (bass first), a beat pulse, and a palette. */
interface BandSource {
    val levels: FloatArray
    /** Beat energy right now, decaying after each beat; 0 when the source has no beats. */
    val beat: Float
    /**
     * Six RGB colours, 0..1, in the order Music Assistant's palette uses:
     * background_dark, background_light, primary, accent, on_dark, on_light.
     */
    val palette: FloatArray get() = DEFAULT_PALETTE
    /** Called once per rendered frame with the render-loop time in seconds. */
    fun update(t: Double)
}

/** Nothing playing: a flat field in the default palette. */
object IdleBands : BandSource {
    override val levels = FloatArray(16)
    override val beat = 0f
    override fun update(t: Double) = Unit
}

val DEFAULT_PALETTE = floatArrayOf(
    0.06f, 0.07f, 0.10f,   // background_dark
    0.55f, 0.60f, 0.68f,   // background_light
    0.20f, 0.55f, 0.65f,   // primary
    0.95f, 0.60f, 0.25f,   // accent
    0.85f, 0.88f, 0.92f,   // on_dark
    0.12f, 0.14f, 0.20f,   // on_light
)
