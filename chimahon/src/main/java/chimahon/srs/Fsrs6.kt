package chimahon.srs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS-6 (kumapie_plan.md "kumapie's own Anki"), the same formulas as py-fsrs 6.x with no learning steps and no fuzz:
 * kumapie rates only while watching, so every rating is one plain FSRS step and the interval is in days.
 * Ratings: 1 Again, 2 Hard, 3 Good, 4 Easy. Checked against py-fsrs by Fsrs6Test (tools\srs\fsrs_ref.py).
 */
class Fsrs6(val w: DoubleArray = DEFAULT, val retention: Double = 0.9, val maxInterval: Int = 36500) {
    init { require(w.size == 21) { "FSRS-6 needs 21 parameters, got ${w.size}" } }

    data class State(val stability: Double, val difficulty: Double)

    private val decay = -w[20]
    private val factor = 0.9.pow(1 / decay) - 1

    /** Chance of recall [elapsedDays] after the last rating. */
    fun retrievability(elapsedDays: Double, stability: Double) = (1 + factor * elapsedDays / stability).pow(decay)

    /** Days until R falls to [retention]; py-fsrs rounds half to even. */
    fun interval(stability: Double): Int =
        Math.rint(stability / factor * (retention.pow(1 / decay) - 1)).toInt().coerceIn(1, maxInterval)

    /**
     * One rating. [prev] = null for a card never rated; [elapsedDays] = whole days since the previous rating
     * (py-fsrs: timedelta.days), 0 = same day, which takes the short-term formula.
     */
    fun next(prev: State?, elapsedDays: Int, rating: Int): State {
        require(rating in 1..4)
        if (prev == null) return State(stab(w[rating - 1]), diff(initDifficulty(rating)))
        val s = prev.stability
        val d = prev.difficulty
        val newS = if (elapsedDays < 1) {
            val inc = exp(w[17] * (rating - 3 + w[18])) * s.pow(-w[19])
            s * if (rating >= 2) max(inc, 1.0) else inc // py-fsrs 6.3: only Again lowers S on the same day
        } else {
            val r = retrievability(elapsedDays.toDouble(), s)
            if (rating == 1) {
                val long = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp((1 - r) * w[14])
                min(long, s / exp(w[17] * w[18]))
            } else {
                val hard = if (rating == 2) w[15] else 1.0
                val easy = if (rating == 4) w[16] else 1.0
                s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp((1 - r) * w[10]) - 1) * hard * easy)
            }
        }
        val damped = d + (10 - d) * (-w[6] * (rating - 3)) / 9
        return State(stab(newS), diff(w[7] * initDifficulty(4) + (1 - w[7]) * damped))
    }

    private fun initDifficulty(rating: Int) = w[4] - exp(w[5] * (rating - 1)) + 1
    private fun stab(s: Double) = max(s, 0.001)
    private fun diff(d: Double) = d.coerceIn(1.0, 10.0)

    companion object {
        /** py-fsrs 6.x defaults, until step 2 fits Pedro's own from his review history. */
        val DEFAULT = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )
    }
}
