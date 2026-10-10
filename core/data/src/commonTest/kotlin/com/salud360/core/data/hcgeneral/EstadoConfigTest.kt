package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import kotlin.test.Test
import kotlin.test.assertEquals

/** Qué configuración usa el médico según lo guardado y lo que contestó la API (RF-19, RF-23, RF-25, RF-26). */
class EstadoConfigTest {
    private val viejo = PerfilHc(1, "Perfil viejo", listOf(PestaniaPerfil("A", listOf(SeccionPerfil("nota", "Nota", setOf(CONSULTA))))))
    private val nuevo = PerfilHc(1, "Perfil nuevo", listOf(PestaniaPerfil("B", listOf(SeccionPerfil("conductas", "Conductas", setOf(CONSULTA))))))

    private val sinRed = ConfigRemota.Fallida("red", "Sin conexión con la historia clínica de clinica")
    private val vencida = ConfigRemota.Fallida("licencia_vencida", "Tu licencia está vencida")

    @Test
    fun remota_buena_sin_cache() {
        assertEquals(EstadoConfigHc.ConPerfil(nuevo), estadoConfig(null, ConfigRemota.Recibida(nuevo)))
    }

    @Test
    fun remota_buena_manda_sobre_el_cache() {
        assertEquals(EstadoConfigHc.ConPerfil(nuevo), estadoConfig(ConfigGuardada(viejo), ConfigRemota.Recibida(nuevo)))
        assertEquals(EstadoConfigHc.ConPerfil(nuevo), estadoConfig(ConfigGuardada(null), ConfigRemota.Recibida(nuevo)))
    }

    @Test
    fun remota_con_perfil_null_es_sin_perfil_aunque_haya_cache() {
        assertEquals(EstadoConfigHc.SinPerfil, estadoConfig(null, ConfigRemota.Recibida(null)))
        assertEquals(EstadoConfigHc.SinPerfil, estadoConfig(ConfigGuardada(viejo), ConfigRemota.Recibida(null)))
    }

    @Test
    fun remota_caida_vale_la_guardada_con_perfil() {
        assertEquals(EstadoConfigHc.ConPerfil(viejo), estadoConfig(ConfigGuardada(viejo), sinRed))
        assertEquals(EstadoConfigHc.ConPerfil(viejo), estadoConfig(ConfigGuardada(viejo), vencida))
    }

    @Test
    fun remota_caida_vale_la_guardada_sin_perfil() {
        assertEquals(EstadoConfigHc.SinPerfil, estadoConfig(ConfigGuardada(null), sinRed))
    }

    @Test
    fun remota_caida_sin_cache_por_red() {
        assertEquals(
            EstadoConfigHc.PrimeraVezSinConexion("red", "Sin conexión con la historia clínica de clinica"),
            estadoConfig(null, sinRed),
        )
    }

    @Test
    fun remota_caida_sin_cache_con_motivo_de_la_api() {
        assertEquals(
            EstadoConfigHc.PrimeraVezSinConexion("licencia_vencida", "Tu licencia está vencida"),
            estadoConfig(null, vencida),
        )
    }
}
