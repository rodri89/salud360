package com.salud360.core.data.network.hc

import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.EstadoAdministrar
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.Marcable
import com.salud360.core.data.hcgeneral.ResultadoCrear
import com.salud360.core.data.network.TobbException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Enfermedades contra la sección "Enfermedades" de `md/API_SALUD360_CLINICA.md` de hclinica (RF-50 a RF-54): rutas,
 * cuerpos con booleanos de JSON y `paciente_id` opcional, las respuestas de ejemplo del md convertidas a los modelos de
 * `hcgeneral` y los errores que la sección distingue. Enfermedades "Prueba Salud360 …" inventadas.
 */
class EnfermedadesDtoTest {
    private val cliente = HcApiClient("http://localhost", "clinica")
    private val api = EnfermedadesApiRemota(cliente)

    private fun ok(cuerpo: String) = cliente.interpretar(200, """{"ok": true, $cuerpo}""")

    private fun json(texto: String): JsonObject = Json.parseToJsonElement(texto).jsonObject

    private val seccionDelMd = """"seccion": {
        "marcables": [{"id": 101, "nombre": "Prueba Salud360 A", "tiene": true},
                      {"id": 102, "nombre": "Prueba Salud360 B", "tiene": false}],
        "otras": [{"id": 103, "nombre": "Prueba Salud360 C"}]}"""

    private val estadoDelMd = EstadoSeccionEnfermedades(
        marcables = listOf(Marcable(101, "Prueba Salud360 A", true), Marcable(102, "Prueba Salud360 B", false)),
        otras = listOf(Enfermedad(103, "Prueba Salud360 C")),
    )

    private val administrarDelMd = """"administrar": {
        "lista": [{"id": 101, "nombre": "Prueba Salud360 A"}, {"id": 102, "nombre": "Prueba Salud360 B"}],
        "catalogo": [{"id": 104, "nombre": "Prueba Salud360 D"}]}"""

    private val listaDelMd = EstadoAdministrar(
        lista = listOf(Enfermedad(101, "Prueba Salud360 A"), Enfermedad(102, "Prueba Salud360 B")),
        catalogo = listOf(Enfermedad(104, "Prueba Salud360 D")),
    )

    /** El ejemplo de crear del md, con lo que cambia en cada caso. */
    private fun crear(
        creada: Boolean = true,
        reutilizada: Boolean = false,
        reactivada: Boolean = false,
        yaEnLista: Boolean = false,
        marcada: Boolean = true,
        sinMarcarPor: String? = null,
        nombre: String = "Prueba Salud360 E",
        conSeccion: Boolean = true,
    ): String {
        val por = sinMarcarPor?.let { "\"$it\"" } ?: "null"
        val seccion = if (conSeccion) {
            """, "seccion": {"marcables": [{"id": 105, "nombre": "$nombre", "tiene": true}], "otras": []}"""
        } else {
            ""
        }
        return """{"ok": true,
            "administrar": {"lista": [{"id": 105, "nombre": "$nombre"}], "catalogo": []},
            "enfermedad": {"id": 105, "nombre": "$nombre"},
            "creada": $creada, "reutilizada": $reutilizada, "reactivada": $reactivada, "ya_en_lista": $yaEnLista,
            "marcada": $marcada, "sin_marcar_por": $por$seccion}"""
    }

    // --- Rutas ---

    @Test
    fun rutas_de_las_cinco_operaciones() {
        assertEquals("enfermedades/pacientes/501", api.rutaSeccion(501))
        assertEquals("enfermedades/pacientes/501/101", api.rutaMarcar(501, 101))
        assertEquals("enfermedades/lista", EnfermedadesApiRemota.RUTA_LISTA)
        assertEquals("enfermedades/lista/101", api.rutaLista(101))
        assertEquals("enfermedades", EnfermedadesApiRemota.RUTA_CREAR)
    }

    // --- Cuerpos (booleanos de JSON, `paciente_id` número y opcional) ---

    @Test
    fun cuerpo_de_marcar() {
        assertEquals(json("""{"tiene": true}"""), json(api.cuerpoMarcar(true)))
        assertEquals(json("""{"tiene": false}"""), json(api.cuerpoMarcar(false)))
    }

    @Test
    fun cuerpo_de_la_lista_con_y_sin_paciente() {
        assertEquals(json("""{"en_lista": true, "paciente_id": 501}"""), json(api.cuerpoLista(true, 501)))
        assertEquals(json("""{"en_lista": false}"""), json(api.cuerpoLista(false, null)))
        // Booleano de JSON, no "true" ni 1 (el servidor responde 422 datos_invalidos).
        assertEquals(JsonPrimitive(true), json(api.cuerpoLista(true, null))["en_lista"])
    }

    @Test
    fun cuerpo_de_crear_con_y_sin_paciente() {
        assertEquals(
            json("""{"nombre": "Prueba Salud360 E", "paciente_id": 501}"""),
            json(api.cuerpoCrear("Prueba Salud360 E", 501)),
        )
        assertEquals(json("""{"nombre": "Prueba Salud360 E"}"""), json(api.cuerpoCrear("Prueba Salud360 E", null)))
    }

    @Test
    fun el_nombre_viaja_como_se_escribio() {
        // La web normaliza; la app no recorta antes de mandar.
        assertEquals(JsonPrimitive(" prueba  SALUD360 1 "), json(api.cuerpoCrear(" prueba  SALUD360 1 ", null))["nombre"])
    }

    // --- Respuestas ---

    @Test
    fun seccion_del_md() {
        assertEquals(estadoDelMd, api.seccionDe(ok(seccionDelMd)))
    }

    @Test
    fun seccion_vacia() {
        assertEquals(
            EstadoSeccionEnfermedades(emptyList(), emptyList()),
            api.seccionDe(ok(""""seccion": {"marcables": [], "otras": []}""")),
        )
    }

    @Test
    fun administrar_del_md_y_vacio() {
        assertEquals(listaDelMd, api.administrarDe(ok(administrarDelMd)))
        assertEquals(
            EstadoAdministrar(emptyList(), emptyList()),
            api.administrarDe(ok(""""administrar": {"lista": [], "catalogo": []}""")),
        )
    }

    @Test
    fun la_lista_con_y_sin_seccion() {
        assertEquals(RespuestaLista(listaDelMd, estadoDelMd), api.listaDe(ok("$administrarDelMd, $seccionDelMd")))
        // Ficha fuera de la cartera o dada de baja: la web omite `seccion` sin error.
        assertEquals(RespuestaLista(listaDelMd, null), api.listaDe(ok(administrarDelMd)))
    }

    @Test
    fun sin_la_parte_esperada_es_respuesta_inesperada() {
        val e = assertFailsWith<TobbException> { api.seccionDe(ok(administrarDelMd)) }
        assertEquals("respuesta", e.codigo)
        assertEquals("respuesta", assertFailsWith<TobbException> { api.administrarDe(ok(seccionDelMd)) }.codigo)
        assertEquals("respuesta", assertFailsWith<TobbException> { api.listaDe(ok(seccionDelMd)) }.codigo)
    }

    @Test
    fun crear_nueva_y_marcada_con_201() {
        // Reviewer F1, punto 4: 201 y 200 son éxito; se distinguen por `creada` (E5).
        val obj = cliente.interpretar(201, crear())
        assertTrue(cliente.leerTodo(RespuestaCrearDto.serializer(), obj).creada)

        val r = api.crearDe(obj)
        assertEquals(
            ResultadoCrear(
                Enfermedad(105, "Prueba Salud360 E"),
                reutilizada = false, reactivada = false, yaEnLista = false, marcada = true, sinMarcarPor = null,
            ),
            r.resultado,
        )
        assertEquals(EstadoAdministrar(listOf(Enfermedad(105, "Prueba Salud360 E")), emptyList()), r.administrar)
        assertEquals(
            EstadoSeccionEnfermedades(listOf(Marcable(105, "Prueba Salud360 E", true)), emptyList()),
            r.seccion,
        )
    }

    @Test
    fun crear_reutilizada_con_200_trae_el_nombre_del_catalogo() {
        val obj = cliente.interpretar(
            200,
            crear(creada = false, reutilizada = true, reactivada = true, yaEnLista = true, nombre = "Prueba Salud360 Diabétes"),
        )
        assertFalse(cliente.leerTodo(RespuestaCrearDto.serializer(), obj).creada)

        val r = api.crearDe(obj).resultado
        assertEquals(Enfermedad(105, "Prueba Salud360 Diabétes"), r.enfermedad)
        assertTrue(r.reutilizada)
        assertTrue(r.reactivada)
        assertTrue(r.yaEnLista)
        assertTrue(r.marcada)
    }

    @Test
    fun crear_sin_paciente_no_trae_seccion() {
        val r = api.crearDe(cliente.interpretar(201, crear(marcada = false, conSeccion = false)))
        assertNull(r.seccion)
        assertFalse(r.resultado.marcada)
        assertNull(r.resultado.sinMarcarPor)
    }

    @Test
    fun sin_marcar_por_queda_crudo() {
        for (por in listOf("fuera_de_cartera", "paciente_no_encontrado", "un_motivo_nuevo")) {
            val r = api.crearDe(cliente.interpretar(200, crear(creada = false, reutilizada = true, marcada = false, sinMarcarPor = por, conSeccion = false)))
            assertEquals(por, r.resultado.sinMarcarPor)
            assertFalse(r.resultado.marcada)
            assertNull(r.seccion)
        }
    }

    @Test
    fun crear_sin_sin_marcar_por_es_null() {
        // Si una versión de la web no lo manda, es lo mismo que null.
        val texto = crear().replace(""", "sin_marcar_por": null""", "")
        assertFalse("sin_marcar_por" in texto)
        assertNull(api.crearDe(cliente.interpretar(201, texto)).resultado.sinMarcarPor)
    }

    // --- Errores ---

    @Test
    fun el_429_de_laravel_queda_con_su_status_y_sin_codigo() {
        val e = assertFailsWith<TobbException> { cliente.interpretar(429, """{"message": "Too Many Attempts."}""") }
        assertEquals(429, e.status)
        assertNull(e.codigo)
        assertEquals("Too Many Attempts.", e.message)
    }

    @Test
    fun el_422_nombre_invalido_trae_el_mensaje() {
        val e = assertFailsWith<TobbException> {
            cliente.interpretar(
                422,
                """{"ok": false, "codigo": "nombre_invalido", "mensaje": "El nombre tiene caracteres que no se ven: escribilo de nuevo"}""",
            )
        }
        assertEquals(422, e.status)
        assertEquals("nombre_invalido", e.codigo)
        assertEquals("El nombre tiene caracteres que no se ven: escribilo de nuevo", e.message)
    }

    @Test
    fun los_codigos_de_la_seccion_llegan_tal_cual() {
        for ((status, codigo) in listOf(
            403 to "fuera_de_cartera", 404 to "paciente_no_encontrado", 422 to "no_en_tu_lista",
            422 to "enfermedad_dada_de_baja", 422 to "datos_invalidos", 403 to "sin_permiso", 500 to "enfermedades",
        )) {
            val e = assertFailsWith<TobbException> {
                cliente.interpretar(status, """{"ok": false, "codigo": "$codigo", "mensaje": "Texto de la web."}""")
            }
            assertEquals(status, e.status)
            assertEquals(codigo, e.codigo)
        }
    }
}
