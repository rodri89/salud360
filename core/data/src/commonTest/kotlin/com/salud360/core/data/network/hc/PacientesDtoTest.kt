package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pacientes por especialidad contra la sección "Pacientes" de `md/API_SALUD360_CLINICA.md` de hclinica y
 * `md/API_SALUD360_PEDIATRIA.md` de hc_pediatria: la clave de la ficha elegida de cada API, la ficha con los campos
 * que cada una no manda y las candidatas del `409 paciente_ambiguo` (D1). Pacientes inventados.
 */
class PacientesDtoTest {
    private val cliente = HcApiClient("http://localhost", "clinica")
    private val clinica = PacientesHcApiRemota(cliente, "hc_paciente_id")
    private val pediatria = PacientesHcApiRemota(cliente, "pediatria_paciente_id")

    private val pedido = HcPacienteRequest(
        dni = "30111222", nombre = "PRUEBA", apellido = "SALUD360",
        pacienteIdTobb = 7001, nombreFamiliar = "FAMILIAR INVENTADO",
    )

    private fun ok(cuerpo: String) = cliente.interpretar(200, """{"ok": true, $cuerpo}""")

    private fun cuerpo(api: PacientesHcApiRemota, elegida: Long?) = Json.parseToJsonElement(api.cuerpo(pedido, elegida)).jsonObject

    @Test
    fun la_elegida_va_con_la_clave_de_cada_api() {
        val deClinica = cuerpo(clinica, 501)
        assertEquals(JsonPrimitive(501), deClinica["hc_paciente_id"])
        assertFalse("pediatria_paciente_id" in deClinica)

        val dePediatria = cuerpo(pediatria, 77)
        assertEquals(JsonPrimitive(77), dePediatria["pediatria_paciente_id"])
        assertFalse("hc_paciente_id" in dePediatria)
    }

    @Test
    fun sin_elegida_no_va_ninguna_clave() {
        val c = cuerpo(clinica, null)
        assertFalse("hc_paciente_id" in c)
        assertFalse("pediatria_paciente_id" in c)
        assertEquals(cuerpo(pediatria, null), c)
    }

    @Test
    fun los_nulos_viajan_como_null() {
        val c = cuerpo(clinica, null)
        assertEquals(JsonPrimitive("30111222"), c["dni"])
        assertEquals(JsonPrimitive(7001), c["paciente_id_tobb"])
        assertEquals(JsonPrimitive("FAMILIAR INVENTADO"), c["nombre_familiar"])
        assertEquals(JsonNull, c["telefono_familiar"])
        assertEquals(JsonNull, c["fecha_nacimiento"])
        assertEquals(JsonNull, c["telefono"])
    }

    @Test
    fun ficha_de_clinica_sin_padre_ni_madre() {
        val pagina = clinica.paginaDe(
            ok(
                """"pacientes": [{"id": 501, "paciente_id_tobb": 0, "nombre": "PRUEBA", "apellido": "SALUD360",
                    "dni": "30111222", "sexo": "", "fecha_nacimiento": null, "telefono": "", "mail": "",
                    "domicilio": "", "localidad": "", "obra_social": "", "numero_afiliado": "", "obra_social_plan": "",
                    "nombre_familiar": "FAMILIAR INVENTADO", "telefono_familiar": "2914000000", "activo": 1}],
                   "total": 1, "desde": 0, "hay_mas": 0""",
            ),
        )
        val ficha = pagina.fichas.single()
        assertEquals(501, ficha.id)
        assertEquals(0, ficha.pacienteIdTobb)
        assertNull(ficha.fechaNacimiento)
        assertEquals("FAMILIAR INVENTADO", ficha.nombreFamiliar)
        assertEquals("2914000000", ficha.telefonoFamiliar)
        assertEquals("", ficha.nombrePadre)
        assertEquals("", ficha.nombreMadre)
        assertEquals(0, ficha.cantidadHermanos)
        assertFalse(pagina.hayMas)
    }

    @Test
    fun ficha_de_pediatria_sin_familiar() {
        val pagina = pediatria.paginaDe(
            ok(
                """"pacientes": [{"id": 77, "paciente_id_tobb": 7001, "nombre": "PRUEBA", "apellido": "SALUD360",
                    "dni": "40111222", "sexo": "F", "fecha_nacimiento": "2018-03-01",
                    "nombre_padre": "PADRE INVENTADO", "nombre_madre": "MADRE INVENTADA", "cantidad_hermanos": 2}],
                   "total": 301, "desde": 0, "hay_mas": 1""",
            ),
        )
        val ficha = pagina.fichas.single()
        assertEquals("", ficha.nombreFamiliar)
        assertEquals("", ficha.telefonoFamiliar)
        assertEquals("PADRE INVENTADO", ficha.nombrePadre)
        assertEquals(2, ficha.cantidadHermanos)
        assertTrue(pagina.hayMas)
    }

    @Test
    fun pagina_vacia() {
        val pagina = clinica.paginaDe(ok(""""pacientes": [], "total": 0, "desde": 0, "hay_mas": 0"""))
        assertTrue(pagina.fichas.isEmpty())
        assertFalse(pagina.hayMas)
    }

    @Test
    fun respuesta_de_resolver() {
        val creada = clinica.resolucionDe(
            ok(""""paciente": {"id": 501, "paciente_id_tobb": 7001, "dni": "30111222"}, "creado": true, "vinculado_por": "nuevo""""),
        )
        assertEquals(501, creada.paciente.id)
        assertTrue(creada.creado)
        assertEquals("nuevo", creada.vinculadoPor)

        for (por in listOf("tobb_id", "dni", "elegido")) {
            val r = clinica.resolucionDe(ok(""""paciente": {"id": 502}, "creado": false, "vinculado_por": "$por""""))
            assertFalse(r.creado)
            assertEquals(por, r.vinculadoPor)
        }
    }

    @Test
    fun el_409_ambiguo_trae_las_candidatas() {
        val e = assertFailsWith<TobbException> {
            cliente.interpretar(
                409,
                """{"ok": false, "codigo": "paciente_ambiguo",
                    "mensaje": "Hay más de una ficha con ese documento en la historia clínica. Elegí cuál corresponde.",
                    "candidatos": [
                      {"id": 501, "nombre": "PRUEBA UNO", "apellido": "SALUD360", "dni": "30111222", "fecha_nacimiento": "2015-03-01", "cantidad_consultas": 4},
                      {"id": 502, "nombre": "PRUEBA DOS", "apellido": "SALUD360", "dni": "30111222", "fecha_nacimiento": null, "cantidad_consultas": 0}
                    ]}""",
            )
        }
        assertEquals(409, e.status)
        assertEquals("paciente_ambiguo", e.codigo)
        assertEquals(
            listOf(
                HcPacienteCandidato(501, "PRUEBA UNO", "SALUD360", "30111222", "2015-03-01", 4),
                HcPacienteCandidato(502, "PRUEBA DOS", "SALUD360", "30111222", null, 0),
            ),
            e.candidatos,
        )
    }

    @Test
    fun otros_errores_sin_candidatas() {
        val e = assertFailsWith<TobbException> {
            cliente.interpretar(409, """{"ok": false, "codigo": "paciente_ya_vinculado", "mensaje": "Ya corresponde a otro paciente."}""")
        }
        assertEquals("paciente_ya_vinculado", e.codigo)
        assertTrue(e.candidatos.isEmpty())
    }
}
