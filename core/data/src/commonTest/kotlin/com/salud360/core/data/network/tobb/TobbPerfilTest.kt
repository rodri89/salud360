package com.salud360.core.data.network.tobb

import com.salud360.core.model.tobb.TobbPerfil
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TobbPerfilTest {
    private fun perfil(medico: String): TobbPerfil = tobbJson.decodeFromJsonElement(
        TobbPerfil.serializer(),
        tobbJson.parseToJsonElement("""{"usuario":{"id":12,"nombre":"Dra","email":"d@m.com","tipo":2},"rol":"medico","medico":$medico}""").jsonObject,
    )

    @Test
    fun elPerfilTraeLasHistoriasClinicasHabilitadas() {
        val p = perfil("""{"id":3,"nombre":"Ana","apellido":"Perez","especialidad_id":5,"especialidad":"Pediatría","consultorio_id":1,"historias_clinicas":["pediatria","clinica"]}""")
        assertEquals(listOf("pediatria", "clinica"), p.medico?.historiasClinicas)
    }

    @Test
    fun sinElCampoLaListaQuedaVacia() {
        val p = perfil("""{"id":3,"nombre":"Ana","apellido":"Perez","consultorio_id":0}""")
        assertTrue(p.medico?.historiasClinicas?.isEmpty() == true)
        assertEquals(0L, p.medico?.consultorioId)
        assertNull(p.secretaria)
    }
}
