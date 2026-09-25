// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.study

import io.github.giovannipivatoo.memoro.data.Rating
import io.github.giovannipivatoo.memoro.data.Scheduling
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** Deterministic FSRS-6 scheduler, default parameters and 90% desired retention. */
object Fsrs6 {
    private const val DAY = 86_400_000L
    private val w = doubleArrayOf(0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
        0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542)
    private val factor = 0.9.pow(-1.0 / w[20]) - 1.0

    fun review(previous: Scheduling, rating: Rating, nowMillis: Long, retention: Double = 0.9): Scheduling {
        require(retention > 0 && retention < 1)
        require(nowMillis >= 0)
        val grade = rating.ordinal + 1
        val first = previous.reps == 0
        // Legacy Anki cards may lack FSRS memory state. Preserve their imported due date
        // until review, then bootstrap from the imported interval rather than treat as new.
        val seed = if (!first && (previous.stability <= 0 || previous.difficulty <= 0 || previous.lastReviewAtMillis == null)) {
            val interval = max(1, previous.importedIntervalDays ?: 1)
            previous.copy(stability = max(0.1, previous.stability.takeIf { it > 0 } ?: interval.toDouble()),
                difficulty = previous.difficulty.takeIf { it > 0 } ?: initialDifficulty(3),
                lastReviewAtMillis = previous.lastReviewAtMillis ?: nowMillis - interval * DAY)
        } else previous
        val difficulty = if (first) initialDifficulty(grade) else nextDifficulty(seed.difficulty, grade)
        val stability = if (first) w[grade - 1] else {
            val elapsed = max(0.0, (nowMillis - seed.lastReviewAtMillis!!).toDouble() / DAY)
            val s = seed.stability
            if (elapsed < 1.0) {
                val multiplier = exp(w[17] * (grade - 3 + w[18])) * s.pow(-w[19])
                s * if (grade >= 2) max(1.0, multiplier) else multiplier
            } else {
                val r = (1.0 + factor * elapsed / s).pow(-w[20])
                if (rating == Rating.AGAIN) {
                    val forgotten = w[11] * seed.difficulty.pow(-w[12]) *
                        ((s + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r))
                    min(forgotten, s / exp(w[17] * w[18]))
                } else {
                    val penalty = if (rating == Rating.HARD) w[15] else 1.0
                    val bonus = if (rating == Rating.EASY) w[16] else 1.0
                    s * (1 + exp(w[8]) * (11 - seed.difficulty) * s.pow(-w[9]) *
                        (exp(w[10] * (1 - r)) - 1) * penalty * bonus)
                }
            }
        }.coerceIn(0.01, 36500.0)
        val intervalDays = (stability / factor * (retention.pow(-1.0 / w[20]) - 1)).roundToLong().coerceIn(1, 36500)
        val learning = first || previous.learningStep != null
        val wasRelearning = previous.relearning
        val step = previous.learningStep ?: 0
        val nextStep: Int?
        val nextRelearning: Boolean
        val dueAtMillis: Long
        if (!learning && rating == Rating.AGAIN) {
            nextStep = 0; nextRelearning = true; dueAtMillis = nowMillis + 10 * 60_000L
        } else if (learning && rating == Rating.AGAIN) {
            nextStep = 0; nextRelearning = wasRelearning
            dueAtMillis = nowMillis + if (wasRelearning) 10 * 60_000L else 60_000L
        } else if (learning && rating == Rating.HARD) {
            nextStep = step; nextRelearning = wasRelearning
            dueAtMillis = nowMillis + if (wasRelearning || step > 0) 10 * 60_000L else 330_000L
        } else if (learning && rating == Rating.GOOD && !wasRelearning && step == 0) {
            nextStep = 1; nextRelearning = false; dueAtMillis = nowMillis + 10 * 60_000L
        } else {
            nextStep = null; nextRelearning = false; dueAtMillis = nowMillis + intervalDays * DAY
        }
        return previous.copy(dueAtMillis = dueAtMillis, stability = stability, difficulty = difficulty,
            lastReviewAtMillis = nowMillis, reps = previous.reps + 1,
            lapses = previous.lapses + if (!learning && rating == Rating.AGAIN) 1 else 0,
            learningStep = nextStep, relearning = nextRelearning)
    }

    private fun initialDifficulty(grade: Int): Double = rawInitialDifficulty(grade).coerceIn(1.0, 10.0)
    private fun rawInitialDifficulty(grade: Int): Double = w[4] - exp(w[5] * (grade - 1)) + 1
    private fun nextDifficulty(previous: Double, grade: Int): Double {
        val damped = previous - w[6] * (grade - 3) * (10 - previous) / 9
        return (w[7] * rawInitialDifficulty(4) + (1 - w[7]) * damped).coerceIn(1.0, 10.0)
    }
}
