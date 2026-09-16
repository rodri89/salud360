package com.salud360.core.ui.components

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DateFieldTest {
    @Test
    fun idaYVueltaDeMilisegundos() {
        val f = LocalDate(2026, 10, 18)
        assertEquals(f, f.toEpochMillis().toLocalDate())
        assertEquals("18/10/2026", f.toDisplay())
    }
}
