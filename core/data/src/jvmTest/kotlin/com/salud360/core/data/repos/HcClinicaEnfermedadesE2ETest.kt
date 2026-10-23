package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.Marcable
import com.salud360.core.data.network.hc.EnfermedadesApiRemota
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.PacientesHcApiRemota
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Enfermedades de la HC general (spec 003 RF-15, RF-41, RF-44, RF-51, RF-53, RF-54) contra una API de hclinica de
 * verdad: administrar, crear con la ficha de un paciente nuevo, reutilizar por clave, marcar de manera idempotente,
 * quitar y agregar de la lista, y crear sin paciente.
 *
 * **Escribe** en la base de hclinica: crea la ficha del paciente de prueba, su `medico_pacientes` y dos enfermedades
 * "Prueba Salud360 <n>" con sus filas en `medico_enfermedades` y `paciente_enfermedades`. Por eso pide el documento
 * explícito (uno libre, verificado antes con `SELECT COUNT(*) FROM pacientes WHERE dni = <dni>`) y se saltea sola sin
 * las propiedades. Nunca contra producción. La base tiene datos reales: el test no muestra listas ni nombres de la
 * base en sus mensajes, y no toca más que lo suyo.
 *
 * ```
 * ./gradlew :core:data:jvmTest --rerun --tests '*HcClinicaEnfermedadesE2ETest*' \
 *   "-Psalud360.test.hc.clinica=http://localhost/HClinica-salud360/public/index.php" \
 *   "-Psalud360.test.hc.clinica.token=<token de prueba del médico>" \
 *   "-Psalud360.test.hc.dni=<documento libre>" "-Psalud360.test.hc.nombre=PRUEBA" \
 *   "-Psalud360.test.hc.apellido=SALUD360"
 * ```
 *
 * Antes de correrlo, guardar los conteos por `activo` de `enfermedades`, `medico_enfermedades` y
 * `paciente_enfermedades`. Al terminar, en `hc_clinica` (aunque haya fallado), en este orden por las claves foráneas:
 *
 * ```
 * DELETE FROM paciente_enfermedades WHERE enfermedad_id IN (SELECT id FROM enfermedades WHERE nombre LIKE 'Prueba Salud360%');
 * DELETE FROM medico_enfermedades WHERE enfermedad_id IN (SELECT id FROM enfermedades WHERE nombre LIKE 'Prueba Salud360%');
 * DELETE FROM enfermedades WHERE nombre LIKE 'Prueba Salud360%';
 * DELETE FROM medico_pacientes WHERE paciente_id IN (SELECT id FROM pacientes WHERE dni = <dni>);
 * DELETE FROM pacientes WHERE dni = <dni>;
 * DELETE FROM salud360_token_cache;
 * ```
 *
 * Después, los conteos por `activo` tienen que ser los de antes y los de `nombre LIKE 'Prueba Salud360%'` y del
 * documento de prueba, 0.
 */
class HcClinicaEnfermedadesE2ETest {

    private val url = System.getProperty("salud360.test.hc.clinica").orEmpty()
    private val token = System.getProperty("salud360.test.hc.clinica.token").orEmpty()
    private val dni = System.getProperty("salud360.test.hc.dni").orEmpty()

    @Test
    fun administrarCrearMarcarQuitarYAgregarContraLaWeb() = runBlocking {
        if (url.isBlank() || token.isBlank() || dni.isBlank()) {
            println("HcClinicaEnfermedadesE2ETest: sin -Psalud360.test.hc.clinica / .clinica.token / .dni, no se corre.")
            return@runBlocking
        }
        val cliente = HcApiClient(url, "clinica").also { it.token = token }
        val api = EnfermedadesApiRemota(cliente)
        val pacientes = PacientesHcApiRemota(cliente, "hc_paciente_id")

        val n = System.currentTimeMillis() % 1_000_000
        val nombre = "Prueba Salud360 $n"
        val otroEspaciado = "  prueba   SALÚD360   $n  "
        val sinPaciente = "Prueba Salud360 ${n}b"

        // --- administrar: el prefijo de prueba tiene que estar libre ---
        val antes = api.administrar()
        assertTrue(
            (antes.lista + antes.catalogo).none { it.nombre.startsWith(PREFIJO) },
            "no tiene que haber enfermedades de prueba de una corrida anterior (limpiar con el SQL del KDoc)",
        )

        // --- el paciente de prueba: la ficha se crea con pacientes/resolver (002) ---
        val resuelto = pacientes.resolver(
            HcPacienteRequest(
                dni = dni,
                nombre = propiedad("salud360.test.hc.nombre", "PRUEBA"),
                apellido = propiedad("salud360.test.hc.apellido", "SALUD360"),
                pacienteIdTobb = "9$dni".toLong(),
            ),
            elegida = null,
        )
        assertTrue(resuelto.creado, "el documento de prueba tiene que estar libre")
        val ficha = resuelto.paciente.id

        // Paciente nuevo: toda la lista del médico sin marcar y nada en "Otras".
        val inicial = api.seccion(ficha)
        assertTrue(inicial.marcables.none { it.tiene }, "el paciente nuevo no tiene nada marcado")
        assertTrue(inicial.otras.isEmpty(), "el paciente nuevo no tiene enfermedades fuera de la lista")

        // --- crear con la ficha: 201, en la lista y marcada (RF-44, RF-54) ---
        val (statusCrear, cuerpoCrear) = postCrear(api.cuerpoCrear(nombre, ficha))
        assertEquals(201, statusCrear)
        val creada = api.crearDe(cuerpoCrear)
        assertEquals(Enfermedad(creada.resultado.enfermedad.id, nombre), creada.resultado.enfermedad)
        assertFalse(creada.resultado.reutilizada)
        assertFalse(creada.resultado.reactivada)
        assertFalse(creada.resultado.yaEnLista)
        assertTrue(creada.resultado.marcada)
        assertNull(creada.resultado.sinMarcarPor)
        val id = creada.resultado.enfermedad.id
        val marcada = Marcable(id, nombre, true)
        val seccionCreada = assertNotNull(creada.seccion, "crear con la ficha trae la sección")
        assertTrue(marcada in seccionCreada.marcables, "la nueva sale marcada en la grilla")
        assertTrue(seccionCreada.otras.none { it.id == id })
        assertTrue(Enfermedad(id, nombre) in creada.administrar.lista, "la nueva queda en la lista del médico")
        assertTrue(creada.administrar.catalogo.none { it.id == id })
        assertTrue(seccionCreada == api.seccion(ficha), "la sección de la respuesta es la de la web (RF-15)")
        assertTrue(Enfermedad(id, nombre) in api.administrar().lista)

        // --- el mismo nombre con otro espaciado, mayúsculas y tildes: 200, la del catálogo (RF-54) ---
        val (statusRepetida, cuerpoRepetida) = postCrear(api.cuerpoCrear(otroEspaciado, ficha))
        assertEquals(200, statusRepetida)
        val repetida = api.crearDe(cuerpoRepetida)
        assertEquals(id, repetida.resultado.enfermedad.id)
        assertEquals(nombre, repetida.resultado.enfermedad.nombre, "el nombre es el del catálogo, no el escrito")
        assertNotEquals(otroEspaciado, repetida.resultado.enfermedad.nombre)
        assertTrue(repetida.resultado.reutilizada)
        assertFalse(repetida.resultado.reactivada)
        assertTrue(repetida.resultado.yaEnLista)
        assertTrue(repetida.resultado.marcada, "la que ya estaba marcada sigue marcada")
        assertEquals(1, repetida.administrar.lista.count { it.id == id }, "no se duplica en la lista")
        assertTrue(seccionCreada == repetida.seccion, "la grilla no cambia")

        // --- desmarcar y marcar, dos veces cada una: el estado elegido, no "invertir" (RF-51) ---
        val desmarcada = api.marcar(ficha, id, false)
        assertEquals(Marcable(id, nombre, false), desmarcada.marcables.single { it.id == id })
        assertTrue(desmarcada.otras.none { it.id == id }, "desmarcada y en la lista no pasa a Otras")
        assertTrue(api.marcar(ficha, id, false) == desmarcada, "desmarcar dos veces da el mismo estado")
        assertEquals(marcada, api.marcar(ficha, id, true).marcables.single { it.id == id })
        assertTrue(seccionCreada == api.marcar(ficha, id, true), "marcar dos veces da el mismo estado")

        // --- quitar de la lista: la marcada sigue en "Otras" (RF-41, RF-53) ---
        val quitada = api.enLista(id, false, ficha)
        assertTrue(quitada.administrar.lista.none { it.id == id }, "quitada de la lista")
        assertTrue(Enfermedad(id, nombre) in quitada.administrar.catalogo, "y de vuelta en el catálogo")
        val seccionQuitada = assertNotNull(quitada.seccion, "con la ficha de la cartera trae la sección")
        assertTrue(seccionQuitada.marcables.none { it.id == id }, "ya no es marcable")
        assertTrue(Enfermedad(id, nombre) in seccionQuitada.otras, "pero sigue en Otras enfermedades del paciente")
        assertTrue(seccionQuitada == api.seccion(ficha), "la sección de la respuesta es la de la web")

        // --- agregar otra vez: vuelve marcada ---
        val agregada = api.enLista(id, true, ficha)
        assertTrue(Enfermedad(id, nombre) in agregada.administrar.lista)
        assertTrue(agregada.administrar.catalogo.none { it.id == id })
        val seccionAgregada = assertNotNull(agregada.seccion)
        assertTrue(marcada in seccionAgregada.marcables, "vuelve a la grilla marcada")
        assertTrue(seccionAgregada.otras.none { it.id == id })
        assertTrue(seccionCreada == seccionAgregada, "agregar deja la grilla como estaba")

        // --- crear sin paciente: entra a la lista y no se marca a nadie (RF-58) ---
        val sola = api.crear(sinPaciente, null)
        assertEquals(sinPaciente, sola.resultado.enfermedad.nombre)
        assertFalse(sola.resultado.reutilizada)
        assertFalse(sola.resultado.marcada)
        assertNull(sola.seccion, "sin paciente no hay sección")
        val idSola = sola.resultado.enfermedad.id
        assertNotEquals(id, idSola)
        assertTrue(Enfermedad(idSola, sinPaciente) in sola.administrar.lista)
        val despues: EstadoSeccionEnfermedades = api.seccion(ficha)
        assertTrue(Marcable(idSola, sinPaciente, false) in despues.marcables, "aparece en la grilla sin marcar")
        assertTrue(marcada in despues.marcables, "y la otra sigue marcada")
        assertTrue(despues.otras.isEmpty())
    }

    /**
     * `POST enfermedades` con el estado HTTP a la vista: `HcApiClient` trata 200 y 201 como éxito y no lo devuelve, y
     * esto prueba justamente que crear contesta 201 y reutilizar 200. El cuerpo se lee con el mismo código de la app.
     */
    private fun postCrear(cuerpo: String): Pair<Int, JsonObject> {
        val pedido = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + "/api/salud360/enfermedades"))
            .header("Authorization", "Bearer $token")
            .header("X-Salud360-Token", token)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
            .build()
        val r = HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return r.statusCode() to Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun propiedad(clave: String, porDefecto: String) =
        System.getProperty(clave).orEmpty().ifBlank { porDefecto }

    private companion object {
        const val PREFIJO = "Prueba Salud360"
    }
}
