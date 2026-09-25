// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExactGradeTest {
    @Test fun ignoresCaseAndOuterSpaceOnly() {
        assertEquals(Outcome.CORRECT, gradeExact("  CaFfÈ  ", "caffè"))
        assertEquals(Outcome.WRONG, gradeExact("caffe", "caffè"))
        assertEquals(Outcome.WRONG, gradeExact("caffè!", "caffè"))
        assertEquals(Outcome.WRONG, gradeExact("caf fè", "caffè"))
    }
}
