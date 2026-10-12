package com.salud360.core.data.network.hc

import com.salud360.core.data.hcgeneral.ErrorPerfil
import com.salud360.core.data.network.TobbException
import com.salud360.core.model.especialidad.AsignacionPerfil
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.ResumenPerfil
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Los DTO de la configuración de la HC general contra los ejemplos de `md/API_SALUD360_CLINICA.md` de hclinica, y los
 * errores campo por campo del 422 `validacion` (RF-13). Perfiles y médicos inventados.
 */
class PerfilesDtoTest {
    private val cliente = HcApiClient("http://localhost", "clinica")

    private val perfilJson = """
        {"id": 3, "nombre": "Perfil de prueba", "pestanias": [
          {"nombre": "Consulta", "secciones": [
            {"seccion": "motivo_consulta", "nombre": "Motivo", "consulta": true, "telemedicina": false},
            {"seccion": "conductas", "nombre": "Conductas", "consulta": true, "telemedicina": true}
          ]},
          {"nombre": "Remota", "secciones": [
            {"seccion": "datos_subjetivos", "nombre": "Datos subjetivos", "consulta": false, "telemedicina": true}
          ]}
        ]}
    """.trimIndent()

    private val perfil = PerfilHc(
        3, "Perfil de prueba",
        listOf(
            PestaniaPerfil(
                "Consulta",
                listOf(
                    SeccionPerfil("motivo_consulta", "Motivo", setOf(CONSULTA)),
                    SeccionPerfil("conductas", "Conductas", setOf(CONSULTA, TELEMEDICINA)),
                ),
            ),
            PestaniaPerfil("Remota", listOf(SeccionPerfil("datos_subjetivos", "Datos subjetivos", setOf(TELEMEDICINA)))),
        ),
    )

    private fun ok(cuerpo: String) = cliente.interpretar(200, """{"ok": true, $cuerpo}""")

    @Test
    fun perfil_ida_y_vuelta() {
        val leido = cliente.leer(HcPerfilDto.serializer(), ok(""""perfil": $perfilJson"""), "perfil").toModel()
        assertEquals(perfil, leido)
        val enviado = cliente.cuerpo(HcPerfilDto.serializer(), perfil.toDto())
        assertEquals(Json.parseToJsonElement(perfilJson), Json.parseToJsonElement(enviado))
    }

    @Test
    fun perfil_null_es_sin_perfil() {
        val obj = ok(""""perfil": null""")
        assertNull(cliente.leer(HcPerfilDto.serializer().nullable, obj, "perfil"))
    }

    @Test
    fun catalogo_y_lista_de_perfiles() {
        val obj = ok(
            """"secciones": [{"id": "nota", "nombre": "Nota", "descripcion": "Texto libre"}],
               "perfiles": [{"id": 1, "nombre": "Perfil A", "medicos": 2}]""",
        )
        assertEquals(
            listOf(SeccionCatalogoRemota("nota", "Nota", "Texto libre")),
            cliente.leer(ListSerializer(HcSeccionCatalogoDto.serializer()), obj, "secciones").map { it.toModel() },
        )
        assertEquals(
            listOf(ResumenPerfil(1, "Perfil A", 2)),
            cliente.leer(ListSerializer(HcResumenPerfilDto.serializer()), obj, "perfiles").map { it.toModel() },
        )
    }

    @Test
    fun asignaciones_con_nulos() {
        val obj = ok(
            """"asignaciones": [
                {"medico_id_tobb": 101, "nombre": "Médica Inventada", "vinculado": true, "activo": true, "perfil_id": 1, "perfil_web": 2},
                {"medico_id_tobb": 500, "nombre": null, "vinculado": false, "activo": null, "perfil_id": 1, "perfil_web": null},
                {"medico_id_tobb": 102, "nombre": "Médico Inventado", "vinculado": true, "activo": false, "perfil_id": null, "perfil_web": 3}
            ]""",
        )
        assertEquals(
            listOf(
                AsignacionPerfil(101, "Médica Inventada", vinculado = true, activo = true, perfilId = 1, perfilWeb = 2),
                AsignacionPerfil(500, null, vinculado = false, activo = null, perfilId = 1, perfilWeb = null),
                AsignacionPerfil(102, "Médico Inventado", vinculado = true, activo = false, perfilId = null, perfilWeb = 3),
            ),
            cliente.leer(ListSerializer(HcAsignacionDto.serializer()), obj, "asignaciones").map { it.toModel() },
        )
    }

    @Test
    fun asignar_manda_perfil_id_aunque_sea_null() {
        assertEquals(
            Json.parseToJsonElement("""{"perfil_id": null}"""),
            Json.parseToJsonElement(cliente.cuerpo(HcAsignarRequest.serializer(), HcAsignarRequest(null))),
        )
        assertEquals(
            Json.parseToJsonElement("""{"perfil_id": 4}"""),
            Json.parseToJsonElement(cliente.cuerpo(HcAsignarRequest.serializer(), HcAsignarRequest(4))),
        )
    }

    @Test
    fun el_422_trae_los_errores_campo_por_campo() {
        // Respuesta de `PerfilController::respuestaDeGuardar` de hclinica.
        val e = assertFailsWith<TobbException> {
            cliente.interpretar(
                422,
                """{"ok": false, "codigo": "validacion", "mensaje": "El perfil tiene datos para corregir.",
                    "errores": [
                      {"campo": "nombre", "mensaje": "Ya hay otro perfil con ese nombre."},
                      {"campo": "pestanias.0.secciones.1.seccion", "mensaje": "La sección no está en el catálogo: inventada"}
                    ]}""",
            )
        }
        assertEquals(422, e.status)
        assertEquals("validacion", e.codigo)
        assertEquals("El perfil tiene datos para corregir.", e.message)
        assertEquals(
            listOf(
                ErrorPerfil("nombre", "Ya hay otro perfil con ese nombre."),
                ErrorPerfil("pestanias.0.secciones.1.seccion", "La sección no está en el catálogo: inventada"),
            ),
            e.errores,
        )
    }

    @Test
    fun otros_errores_sin_lista_de_errores() {
        val e = assertFailsWith<TobbException> {
            cliente.interpretar(409, """{"ok": false, "codigo": "perfil_con_medicos", "mensaje": "El perfil tiene médicos asignados.", "medicos": 2}""")
        }
        assertEquals(409, e.status)
        assertEquals("perfil_con_medicos", e.codigo)
        assertTrue(e.errores.isEmpty())
    }
}
