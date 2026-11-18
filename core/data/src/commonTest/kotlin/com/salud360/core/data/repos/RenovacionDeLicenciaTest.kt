package com.salud360.core.data.repos

import com.salud360.core.model.auth.Licencia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * El botón «Renovar» y los avisos de la pestaña Licencias del panel. Todo puro: ni red, ni base, ni Compose.
 */
class RenovacionDeLicenciaTest {

    private fun licencia(vence: String, aviso: String, importe: Double = 0.0, activo: Boolean = true) =
        Licencia(medicoId = "m1", fechaExpiracion = vence, fechaAviso = aviso, importe = importe, activo = activo)

    @Test
    fun renovarSumaUnMesAlVenceYAlAviso() {
        // (vence, aviso) -> (vence, aviso) renovados
        val tabla = listOf(
            // el ejemplo del pedido
            ("2026-12-10" to "2026-12-01") to ("2027-01-10" to "2027-01-01"),
            // un mes calendario recorta el día al último del mes: año común y bisiesto
            ("2027-01-31" to "2027-01-15") to ("2027-02-28" to "2027-02-15"),
            ("2028-01-31" to "2028-01-30") to ("2028-02-29" to "2028-02-29"),
            // mes de 30 días a mes de 31
            ("2026-11-30" to "2026-10-30") to ("2026-12-30" to "2026-11-30"),
            // cambio de año
            ("2026-12-31" to "2026-12-01") to ("2027-01-31" to "2027-01-01"),
            // 31 de marzo → 30 de abril
            ("2026-03-31" to "2026-03-31") to ("2026-04-30" to "2026-04-30"),
        )
        for ((antes, despues) in tabla) {
            val renovada = renovarLicencia(licencia(antes.first, antes.second))
            assertEquals(despues.first, renovada?.fechaExpiracion, "vence de $antes")
            assertEquals(despues.second, renovada?.fechaAviso, "aviso de $antes")
        }
    }

    @Test
    fun renovarConservaMedicoImporteYActivo() {
        val renovada = renovarLicencia(licencia("2026-12-10", "2026-12-01", importe = 1500.5, activo = false))
        assertEquals(licencia("2027-01-10", "2027-01-01", importe = 1500.5, activo = false), renovada)
    }

    @Test
    fun renovarSumaUnMesAUnaLicenciaYaVencida() {
        // Renovar suma a lo guardado, no a hoy: una licencia vencida puede seguir vencida y se renueva de nuevo.
        val renovada = renovarLicencia(licencia("2020-03-15", "2020-03-01"))
        assertEquals("2020-04-15", renovada?.fechaExpiracion)
        assertEquals("2020-04-01", renovada?.fechaAviso)
    }

    @Test
    fun conUnaFechaInvalidaNoSeRenuevaAMedias() {
        assertNull(renovarLicencia(licencia("no es una fecha", "2026-12-01")))
        assertNull(renovarLicencia(licencia("2026-12-10", "no es una fecha")))
        assertNull(renovarLicencia(licencia("", "")))
        assertNull(renovarLicencia(licencia("2026-02-30", "2026-12-01")))
    }

    @Test
    fun losAvisosDeLicenciaDicenQuePaso() {
        assertEquals("Licencia guardada", avisoDeLicenciaGuardada())
        assertEquals("Licencia renovada", avisoDeLicenciaRenovada())
        assertEquals(
            "La licencia quedó guardada en este dispositivo, pero la historia clínica no la aceptó.",
            avisoDeLicenciaNoAceptada(),
        )
        assertEquals("La licencia tiene una fecha inválida; corregila antes de renovar", avisoDeLicenciaConFechaInvalida())
    }

    @Test
    fun elAvisoDeErrorLlevaElMotivoRealYSinMotivoDiceSinConexion() {
        assertEquals("No se pudo guardar la licencia: timeout", avisoDeLicenciaNoGuardada("timeout"))
        assertEquals("No se pudo guardar la licencia: sin conexión", avisoDeLicenciaNoGuardada(null))
        assertEquals("No se pudo guardar la licencia: sin conexión", avisoDeLicenciaNoGuardada("  "))
    }

    @Test
    fun elImporteEscritoAceptaComaOPuntoYRechazaLoQueNoEsUnNumero() {
        assertEquals(1500.0, importeEscrito("1500"))
        assertEquals(1500.5, importeEscrito("1500.5"))
        assertEquals(1500.5, importeEscrito(" 1500,5 "))
        assertNull(importeEscrito(""))
        assertNull(importeEscrito("12-14"))
        assertNull(importeEscrito("abc"))
    }

    @Test
    fun elTextoDelImporteNoPisaLoQueSeEstaEscribiendo() {
        // Importe cero: el campo queda vacío.
        assertEquals("", textoDelImporte(0.0, escrito = ""))
        // Lo escrito vale lo mismo que lo guardado: se deja como está (no pasa de «1500» a «1500.0» mientras se tipea).
        assertEquals("1500", textoDelImporte(1500.0, escrito = "1500"))
        assertEquals("1500,5", textoDelImporte(1500.5, escrito = "1500,5"))
        // Cambió desde afuera (sincronización): se muestra el valor guardado.
        assertEquals("2000.0", textoDelImporte(2000.0, escrito = "1500"))
        assertEquals("1500.0", textoDelImporte(1500.0, escrito = ""))
    }
}
