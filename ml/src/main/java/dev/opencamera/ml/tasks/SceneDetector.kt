/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import android.content.Context
import android.graphics.Bitmap
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlRuntime

enum class Scene { NONE, FOOD, SKY, NIGHT, DOCUMENT, PORTRAIT, PLANT, TEXT }

/** Frame statistics the rules use next to (or instead of) the classifier. */
data class FrameStats(val meanLuma: Float, val skyFraction: Float, val largestFaceArea: Float)

/**
 * Maps classifier labels plus cheap frame statistics to a scene preset. Night, sky and portrait
 * work without the classifier, so the chip still does something when the model is missing.
 */
object SceneRules {
    private val FOOD = setOf(
        "pizza", "cheeseburger", "hotdog", "plate", "carbonara", "burrito", "ice cream", "bagel", "pretzel",
        "guacamole", "consomme", "trifle", "meat loaf", "potpie", "espresso", "eggnog", "French loaf",
        "mashed potato", "head cabbage", "broccoli", "cauliflower", "zucchini", "bell pepper", "cucumber",
        "Granny Smith", "strawberry", "orange", "lemon", "fig", "pineapple", "banana", "pomegranate",
        "chocolate sauce", "dough", "wok", "frying pan", "hot pot", "soup bowl", "spaghetti squash",
        "butternut squash", "acorn squash", "artichoke", "cardoon", "corn", "red wine", "cup", "coffee mug",
    )
    private val PLANT = setOf(
        "daisy", "yellow lady's slipper", "pot", "rapeseed", "hip", "buckeye", "acorn", "coral fungus", "agaric",
        "greenhouse", "vase", "earthstar", "hen-of-the-woods", "bolete", "custard apple",
    )
    private val DOCUMENT = setOf("envelope", "book jacket", "binder", "notebook", "packet", "paper towel", "file")
    private val TEXT = setOf("menu", "street sign", "scoreboard", "web site", "crossword puzzle", "monitor", "screen")
    private val SKY = setOf(
        "alp", "volcano", "cliff", "seashore", "lakeside", "promontory", "valley", "sandbar", "airliner",
        "balloon", "parachute", "kite", "airship", "breakwater",
    )

    const val NIGHT_LUMA = 45f
    const val MIN_LABEL_SCORE = 0.2f

    fun decide(labels: List<Pair<String, Float>>, stats: FrameStats): Pair<Scene, Float> {
        if (stats.meanLuma < NIGHT_LUMA) return Scene.NIGHT to (1f - stats.meanLuma / NIGHT_LUMA).coerceIn(0.5f, 1f)
        if (stats.largestFaceArea >= 0.03f) return Scene.PORTRAIT to (stats.largestFaceArea * 10f).coerceIn(0.5f, 1f)
        val scores = HashMap<Scene, Float>()
        for ((label, p) in labels) {
            val scene = when (label) {
                in FOOD -> Scene.FOOD
                in PLANT -> Scene.PLANT
                in DOCUMENT -> Scene.DOCUMENT
                in TEXT -> Scene.TEXT
                in SKY -> Scene.SKY
                else -> continue
            }
            scores[scene] = (scores[scene] ?: 0f) + p
        }
        if (stats.skyFraction > 0.35f) scores[Scene.SKY] = (scores[Scene.SKY] ?: 0f) + stats.skyFraction
        val best = scores.maxByOrNull { it.value } ?: return Scene.NONE to 0f
        return if (best.value >= MIN_LABEL_SCORE) best.key to best.value.coerceAtMost(1f) else Scene.NONE to 0f
    }

    /** Mean luma (0..255) and the share of sky-like (bright, blue-dominant) pixels in the top third. */
    fun stats(px: IntArray, w: Int, h: Int, faceArea: Float): FrameStats {
        var luma = 0L
        var sky = 0
        val topRows = h / 3
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val r = c shr 16 and 0xff
            val g = c shr 8 and 0xff
            val b = c and 0xff
            luma += (r * 77 + g * 150 + b * 29) shr 8
            if (y < topRows && b > 120 && b > r + 15 && b >= g) sky++
        }
        val n = (w * h).coerceAtLeast(1)
        return FrameStats(luma.toFloat() / n, if (topRows == 0) 0f else sky.toFloat() / (topRows * w), faceArea)
    }
}

/** Classifier + rules; runs on a small preview frame, well under the 30 ms budget on CPU. */
class SceneDetector(context: Context, private val runtime: MlRuntime, private val faces: FaceMesh) {
    data class Detection(val scene: Scene, val confidence: Float, val usedClassifier: Boolean)

    private val labels: List<String> by lazy {
        runCatching {
            context.applicationContext.assets.open("models/$CLASSIFIER/labels.txt").bufferedReader().readLines()
        }.getOrDefault(emptyList())
    }

    fun detect(frame: Bitmap): Detection {
        val small = ImageTensors.scaled(frame, SIZE, SIZE)
        val px = ImageTensors.pixels(small)
        if (small !== frame) small.recycle()
        val faceArea = faces.detectBoxes(frame, 1)?.firstOrNull()?.first
            ?.let { it.width() * it.height() / (frame.width.toFloat() * frame.height) } ?: 0f
        val stats = SceneRules.stats(px, SIZE, SIZE, faceArea)
        var top = emptyList<Pair<String, Float>>()
        if (runtime.isAvailable(CLASSIFIER) && labels.isNotEmpty()) {
            // The model rescales internally: RGB in 0..255.
            val t = ImageTensors.argbToNhwc(px, SIZE, SIZE, mul = 255f)
            runtime.runOrNull(CLASSIFIER, listOf(t), CLASSIFIER_TIMEOUT_MS)?.first()?.data?.let { p ->
                top = p.indices.sortedByDescending { p[it] }.take(5).mapNotNull { i -> labels.getOrNull(i)?.let { it to p[i] } }
            }
        }
        val (scene, conf) = SceneRules.decide(top, stats)
        return Detection(scene, conf, top.isNotEmpty())
    }

    companion object {
        const val CLASSIFIER = "scene_classifier"
        private const val SIZE = 224
        private const val CLASSIFIER_TIMEOUT_MS = 500L
    }
}
