// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.study

import io.github.giovannipivatoo.memoro.data.Rating
import io.github.giovannipivatoo.memoro.data.Scheduling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fsrs6Test {
    // Golden values from open-spaced-repetition/py-fsrs 6.3.2, fuzz disabled,
    // UTC 2026-01-01. See THIRD_PARTY_FSRS.md for the oracle invocation.
    @Test fun initialAndLongTermReviewMatchReference() {
        val start = 1_767_225_600_000L
        val first = Fsrs6.review(Scheduling(), Rating.GOOD, start)
        assertEquals(2.3065, first.stability, 1e-12)
        assertEquals(2.118103970459016, first.difficulty, 1e-12)
        assertEquals(start + 600_000, first.dueAtMillis)
        val second = Fsrs6.review(first, Rating.GOOD, start + 86_400_000)
        assertEquals(7.31530074407728, second.stability, 1e-9)
        assertEquals(2.111214235785395, second.difficulty, 1e-12)
        assertEquals(start + 8 * 86_400_000, second.dueAtMillis)
    }

    @Test fun sameDayLearningAndAnkiBootstrap() {
        val start = 1_767_225_600_000L
        val first = Fsrs6.review(Scheduling(), Rating.GOOD, start)
        val second = Fsrs6.review(first, Rating.GOOD, start + 600_000)
        assertEquals(2.3065, second.stability, 1e-12)
        assertEquals(2.111214235785395, second.difficulty, 1e-12)
        assertEquals(start + 600_000 + 2 * 86_400_000, second.dueAtMillis)
        val imported = Scheduling(dueAtMillis = start - 1, reps = 5, importedIntervalDays = 7)
        val reviewed = Fsrs6.review(imported, Rating.GOOD, start)
        assertTrue(reviewed.stability > 7)
        assertEquals(6, reviewed.reps)
    }

    @Test fun allRatingsMatchIndependentReferenceAcrossPhases() {
        val start = 1_767_225_600_000L
        val initial = listOf(
            Vector(Rating.AGAIN, 0.212, 6.4133, 60_000L),
            Vector(Rating.HARD, 1.2931, 5.112170705601056, 330_000L),
            Vector(Rating.GOOD, 2.3065, 2.118103970459016, 600_000L),
            Vector(Rating.EASY, 8.2956, 1.0, 8 * 86_400_000L),
        )
        for (vector in initial) checkVector(Fsrs6.review(Scheduling(), vector.rating, start), vector, start)
        val first = Fsrs6.review(Scheduling(), Rating.GOOD, start)
        val sameDay = listOf(
            Vector(Rating.AGAIN, 0.7750839828558984, 7.394502741279718, 60_000L),
            Vector(Rating.HARD, 2.3065, 4.752858488532557, 600_000L),
            Vector(Rating.GOOD, 2.3065, 2.111214235785395, 2 * 86_400_000L),
            Vector(Rating.EASY, 3.946054067969477, 1.0, 4 * 86_400_000L),
        )
        for (vector in sameDay) checkVector(Fsrs6.review(first, vector.rating, start + 600_000L), vector, start + 600_000L)
        val overdue = listOf(
            Vector(Rating.AGAIN, 0.6368506992409603, 7.394502741279718, 60_000L),
            Vector(Rating.HARD, 9.234870781784839, 4.752858488532557, 600_000L),
            Vector(Rating.GOOD, 13.826903694354568, 2.111214235785395, 14 * 86_400_000L),
            Vector(Rating.EASY, 23.88306407915667, 1.0, 24 * 86_400_000L),
        )
        for (vector in overdue) checkVector(Fsrs6.review(first, vector.rating, start + 3 * 86_400_000L), vector, start + 3 * 86_400_000L)
    }

    private data class Vector(val rating: Rating, val stability: Double, val difficulty: Double, val delay: Long)
    private fun checkVector(actual: Scheduling, expected: Vector, time: Long) {
        assertEquals(expected.stability, actual.stability, 1e-9)
        assertEquals(expected.difficulty, actual.difficulty, 1e-12)
        assertEquals(time + expected.delay, actual.dueAtMillis)
    }
}
