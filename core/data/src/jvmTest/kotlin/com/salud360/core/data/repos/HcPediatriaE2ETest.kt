package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcConsulta
import com.salud360.core.data.network.hc.HcRegistroRemoto
import com.salud360.core.data.sync.HcApiSync
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.createDatabase
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.newId
import com.salud360.core.model.pacientes.Paciente
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La prueba que importa: lo que el médico carga en el dispositivo tiene que aparecer en la base de
 * pediatría, la misma que lee la web.
 *
 * Recorre el camino completo del lado de la app —base local con filas sucias, [HcApiSync],
 * [HcPediatriaBackend] y el cliente HTTP— contra una API de pediatría de verdad. Lo único que queda
 * afuera es la pantalla.
 *
 * Necesita una API levantada y un token de turnosonlinebb, así que **se saltea sola** si no se los
 * pasan. Para correrla contra el MAMP:
 *
 * ```
 * ./gradlew :core:data:jvmTest \
 *   -Psalud360.test.hc.pediatria=http://localhost:8888/HCPediatria/public_html/pediatria/HCDPediatria/public/index.php \
 *   -Psalud360.test.hc.token=<token en claro de tobb.salud360_tokens> \
 *   -Psalud360.test.hc.paciente=tobb-p24726
 * ```
 *
 * El paciente tiene que ser uno de la cartera del médico dueño del token. La consulta que crea la
 * prueba se borra al final (baja lógica, como en la web), salvo que haya caído sobre una que ya
 * existía: `consultas/abrir` reutiliza la consulta abierta del médico y nunca se borra una ajena.
 */
class HcPediatriaE2ETest {

    private val url = System.getProperty("salud360.test.hc.pediatria").orEmpty()
    private val token = System.getProperty("salud360.test.hc.token").orEmpty()

    @Test
    fun loQueSeEscribeEnElDispositivoLlegaAPediatria() {
        if (url.isBlank() || token.isBlank()) {
            println("HcPediatriaE2ETest: sin -Psalud360.test.hc.pediatria / .token, no se corre.")
            return
        }
        val archivo = File.createTempFile("salud360-e2e", ".db").also { it.delete() }
        runBlocking {
            val db = createDatabase(DriverFactory(archivo.absolutePath))
            val api = HcApiClient(url, "pediatria").also { it.token = token }
            val backend = HcPediatriaBackend(db, api)
            val sync = HcApiSync(db, mapOf("pediatria" to backend))
            val hq = db.historiaClinicaQueries
            val ahora = ahoraMillis()
            val consultaId = "e2e-$ahora"
            // Las listas y los antecedentes son del paciente y sobreviven a la consulta, así que cada
            // corrida marca lo suyo: si no, dos corridas dejan filas idénticas imposibles de distinguir.
            val registros = registros(consultaId, pacienteId)
            var remotoId = ""
            // `consultas/abrir` reutiliza la consulta abierta que el médico ya tenga de ese tipo (evita
            // duplicados cuando la app reintenta sin señal). Por eso se anota qué consultas existían
            // antes: si la prueba cayó sobre una de ellas, no se borra nada que no haya creado.
            var previas = emptySet<Long>()

            try {
                // --- lo que deja la pantalla: todo en el dispositivo y marcado como pendiente ---
                db.pacientesQueries.upsertPaciente(paciente().toRow(ahora))
                hq.upsertConsulta(
                    Consulta(
                        id = consultaId, pacienteId = pacienteId, medicoId = "tobb-m1", especialidad = "pediatria",
                        tipo = "control", fecha = hoy(), estado = EstadoConsulta.ABIERTA, edadMostrar = EDAD,
                    ).toRow(ahora),
                )
                TEXTOS.forEach { (seccion, texto) ->
                    hq.upsertSeccionValor(SeccionValor(consultaId, seccion, "texto", texto).toRow(ahora))
                }
                FORMULARIOS.forEach { (seccion, campos) ->
                    campos.forEach { (campo, valor) ->
                        hq.upsertSeccionValor(SeccionValor(consultaId, seccion, campo, valor).toRow(ahora))
                    }
                }
                // Los antecedentes son del paciente y viven en su propia tabla, no en las secciones.
                ANTECEDENTES.forEach { (categoria, items) ->
                    items.forEach { (clave, valor) ->
                        val partes = valor.split("|", limit = 2)
                        hq.upsertAntecedente(
                            Antecedente(
                                newId(), pacienteId, "pediatria", categoria, clave,
                                flag = partes[0] == "1", detalle = partes.getOrNull(1).orEmpty(),
                                consultaId = consultaId,
                            ).toRow(ahora),
                        )
                    }
                }
                // Las listas: varias filas por sección, cada una con su id local.
                registros.forEach { r -> hq.upsertRegistro(r.toRow(ahora)) }
                hq.upsertExamenFisico(ExamenFisico(consultaId, peso = PESO, talla = TALLA, nota = NOTA_EXAMEN).toRow(ahora))

                val pacienteRemoto = backend.resolverPaciente(pacienteId)
                assertTrue(pacienteRemoto != null, "no se pudo resolver el paciente en pediatría")
                previas = idsDeConsultas(api, pacienteRemoto)

                // --- lo que hace el motor de envío ---
                sync.empujarTodo()
                var faltan = pendientes(hq, consultaId, pacienteId)
                val hasta = ahoraMillis() + 60_000
                while (faltan.isNotEmpty() && ahoraMillis() < hasta) {
                    delay(200)
                    faltan = pendientes(hq, consultaId, pacienteId)
                }
                assertTrue(faltan.isEmpty(), "quedó sin enviar: ${faltan.joinToString()}")
                remotoId = hq.consultaPorId(consultaId).uno { it.remoto_id }.orEmpty()
                assertTrue(remotoId.isNotBlank(), "la consulta no se creó en pediatría")

                // --- lo que ve la web: se relee de la API, que lee las tablas de pediatría ---
                val r = api.get("consultas/$remotoId")
                val secciones = api.leerOpcional(SECCIONES, r, "secciones").orEmpty()
                TEXTOS.forEach { (seccion, texto) ->
                    assertEquals(texto, secciones[seccion]?.get("texto"), "la sección '$seccion' no llegó a pediatría")
                }
                FORMULARIOS.forEach { (seccion, campos) ->
                    campos.forEach { (campo, valor) ->
                        assertEquals(valor, secciones[seccion]?.get(campo), "el campo '$seccion.$campo' no llegó a pediatría")
                    }
                }
                ANTECEDENTES.forEach { (categoria, items) ->
                    items.forEach { (clave, valor) ->
                        assertEquals(valor, secciones["antecedentes_$categoria"]?.get(clave), "el antecedente '$categoria.$clave' no llegó a pediatría")
                    }
                }
                val remotos = api.leerOpcional(ListSerializer(HcRegistroRemoto.serializer()), r, "registros").orEmpty()
                registros.forEach { esperado ->
                    val remoto = remotos.firstOrNull { it.tipo == esperado.tipo && it.campos == esperado.campos }
                    assertTrue(remoto != null, "el registro '${esperado.tipo}' no llegó a pediatría")
                    assertEquals(esperado.fecha?.toString(), remoto.fecha, "la fecha del registro '${esperado.tipo}' no coincide")
                    // El id que devolvió la API quedó anotado: sin eso, el próximo envío lo duplicaría.
                    val local = hq.registroPorId(esperado.id).uno { it.remoto_id }
                    assertEquals(remoto.id, local, "no se guardó el id de pediatría del registro '${esperado.tipo}'")
                }

                val examen = api.leerOpcional(CAMPOS, r, "examen_fisico").orEmpty()
                assertEquals(PESO, examen["peso"], "el peso no llegó a pediatría")
                assertEquals(TALLA, examen["talla"], "la talla no llegó a pediatría")
                assertEquals(NOTA_EXAMEN, examen["nota"], "la nota del examen no llegó a pediatría")
            } finally {
                // Se relee de la base y no de la variable: si la prueba se cortó antes de llegar a
                // asignarla, la consulta ya existe del otro lado y hay que borrarla igual.
                remotoId = hq.consultaPorId(consultaId).uno { it.remoto_id }.orEmpty()
                // Las listas no cuelgan de la consulta: hay que darlas de baja aparte. De paso, es la
                // única prueba del camino de borrado.
                val aBorrar = registros.mapNotNull { r ->
                    hq.registroPorId(r.id).uno { it.remoto_id }?.takeIf { it.isNotBlank() }?.let { r.copy(remotoId = it, activo = false) }
                }
                if (aBorrar.isNotEmpty() && remotoId.isNotBlank()) {
                    runCatching { backend.enviarRegistros(remotoId, aBorrar) }
                }
                if (remotoId.isNotBlank() && remotoId.toLongOrNull() !in previas) {
                    runCatching { api.pedir(HttpMethod.Delete, "consultas/$remotoId") }
                } else if (remotoId.isNotBlank()) {
                    println("HcPediatriaE2ETest: la consulta $remotoId ya existía (reutilizada); no se borra.")
                }
                archivo.delete()
            }
        }
    }

    /**
     * Una fila de cada lista, marcada con el id de la consulta de esta corrida. `internacion` no lleva
     * fecha porque del otro lado la tabla no tiene columna; `screening` es de las que se guardan sin
     * pasar por "de la consulta abierta".
     */
    private fun registros(consultaId: String, pacienteId: String): List<RegistroClinico> {
        fun registro(tipo: String, fecha: LocalDate?, campos: Map<String, String>) = RegistroClinico(
            "$consultaId-$tipo", pacienteId, consultaId, "pediatria", tipo, fecha, campos,
        )
        return listOf(
            registro("examen_complementario", LocalDate(2026, 9, 10), mapOf("solicito" to "Hemograma $consultaId", "respuesta" to "Normal")),
            registro("interconsulta", LocalDate(2026, 9, 12), mapOf("especialista" to "Oftalmología $consultaId", "solicito" to "Control de visión", "respuesta" to "")),
            registro("screening", LocalDate(2026, 9, 5), mapOf("evaluacion" to "M-CHAT $consultaId", "respuesta" to "Sin riesgo")),
            registro("internacion", null, mapOf("motivo" to "Bronquiolitis $consultaId", "lugar" to "Hospital Penna", "duracion" to "3 días", "indicacion_alta" to "Control en 48 h")),
        )
    }

    /** Qué le queda por enviar al motor, con nombre, para que un fallo diga qué se trabó. */
    private suspend fun pendientes(hq: com.salud360.core.database.HistoriaClinicaQueries, consultaId: String, pacienteId: String): List<String> {
        val out = mutableListOf<String>()
        if (hq.consultaPorId(consultaId).uno { it.remoto_id }.isNullOrBlank()) out += "la consulta no se creó"
        hq.seccionValoresDirty().lista { it }.filter { it.consulta_id == consultaId }
            .forEach { out += "sección ${it.seccion}.${it.campo}" }
        if (hq.examenesFisicosDirty().lista { it.consulta_id }.contains(consultaId)) out += "examen físico"
        hq.antecedentesDirty().lista { it }.filter { it.paciente_id == pacienteId }
            .forEach { out += "antecedente ${it.categoria}.${it.clave}" }
        hq.registrosDirty().lista { it }.filter { it.paciente_id == pacienteId }
            .forEach { out += "registro ${it.tipo} (${it.id})" }
        if (hq.consultasDirty().lista { it.id }.contains(consultaId)) out += "estado de la consulta"
        return out
    }

    /** Consultas que el paciente ya tiene en pediatría, para no borrar ninguna que no haya creado la prueba. */
    private suspend fun idsDeConsultas(api: HcApiClient, pacienteRemoto: Long?): Set<Long> {
        val r = api.get("consultas", mapOf("paciente_id" to pacienteRemoto))
        return api.leerOpcional(ListSerializer(HcConsulta.serializer()), r, "consultas").orEmpty().map { it.id }.toSet()
    }

    /**
     * El paciente de prueba, como lo tendría la app después de importarlo de turnos. Pediatría lo
     * ubica por su vínculo con turnos (`tobb-p<n>`) o por documento; nunca pisa la ficha con vacíos.
     */
    private fun paciente() = Paciente(
        id = pacienteId,
        dni = propiedad("salud360.test.hc.dni", "42555111"),
        nombre = propiedad("salud360.test.hc.nombre", "MATEO"),
        apellido = propiedad("salud360.test.hc.apellido", "PRUEBA HC"),
    )

    private val pacienteId: String get() = propiedad("salud360.test.hc.paciente", "tobb-p24726")

    private fun propiedad(clave: String, porDefecto: String) =
        System.getProperty(clave).orEmpty().ifBlank { porDefecto }

    companion object {
        /** Una sección de texto de cada tabla que ya traduce la API: motivo, observaciones y conductas. */
        private val TEXTOS = mapOf(
            "motivo_consulta" to "Prueba e2e: control de salud",
            "observaciones" to "Prueba e2e: sin particularidades",
            "conductas" to "Prueba e2e: control en 3 meses",
        )
        /**
         * Un campo de cada forma de las secciones con tabla propia: casilla con detalle, texto,
         * número, opción de dos valores propios ("+" / "Normal") y sí/no con detalle.
         */
        private val FORMULARIOS = mapOf(
            "alimentacion" to mapOf(
                "pecho" to "1|a libre demanda",
                "leche_vaca" to "0|",
                "dieta_tipo" to "Variada, cuatro comidas",
                "dieta_comidas" to "4",
                "hierro" to "1|1 ml por día",
            ),
            "neonatales" to mapOf("nota" to "Prueba e2e: sin patología neonatal"),
            // Desarrollo madurativo: un campo por hito, con el mismo id que usa la web, más la
            // observación, que del otro lado es un hito más. Los ids son del tramo de 24 meses.
            "desarrollo" to mapOf(
                "dm_156" to "1",
                "dm_157" to "0",
                "observacion" to "Prueba e2e: acorde a la edad",
            ),
            // Las tres de la consulta prenatal, que del otro lado numeran al revés (1/2, el 0 es vacío).
            "prenatal_familia" to mapOf(
                "mama" to "Ana Prueba",
                "mama_edad" to "31",
                "hermanos" to "SI",
            ),
            "prenatal_embarazo" to mapOf(
                "obstetra" to "Dra. Prueba",
                "controles" to "6",
                "serologia1" to "+",
                "serologia1_detalle" to "toxoplasmosis",
                "ptog" to "P",
                "ptog_detalle" to "derivada a nutrición",
                "parto" to "Cesárea",
                "cesarea_detalle" to "programada",
            ),
            "prenatal_obstetricos" to mapOf(
                // Sin el 0: la columna nace en 0 y la web también escribe 0 cuando está vacío, así que
                // un cero cargado a mano no se distingue de "sin cargar" y vuelve vacío.
                "g" to "2",
                "p" to "1",
                "detalle" to "Prueba e2e: sin complicaciones",
            ),
            "perinatales" to mapOf(
                "embarazo" to "CONTROLADO",
                "controles" to "8",
                "patologias" to "SI|hipotiroidismo materno",
                "serologia1" to "-",
                "parto" to "CESÁREA",
                "peso" to "3.250",
                "fei" to "Normal",
                "oea" to "Presentes",
            ),
        )
        /**
         * Antecedentes del paciente, por categoría. En el dispositivo son filas de `antecedente` y
         * viajan como si fueran una sección, con el valor `"<tilde>|<detalle>"`. `enfermedad_actual`
         * es de las que solo tienen detalle: del otro lado hay una columna de texto y nada más.
         */
        private val ANTECEDENTES = mapOf(
            "personales" to mapOf(
                "enfermedad_actual" to "1|Prueba e2e: bronquiolitis a los 8 meses",
                "alergias" to "1|penicilina",
                "qx" to "0|",
            ),
            "familiares" to mapOf(
                "asma" to "1|madre",
                "dbt" to "0|",
            ),
        )
        /**
         * Una fila de cada lista. `internacion` no lleva fecha porque del otro lado la tabla no tiene
         * columna; `screening` es de las que se guardan sin pasar por "de la consulta abierta".
         */
        private const val PESO = "15.4"
        private const val TALLA = "0.99"
        private const val NOTA_EXAMEN = "Prueba e2e: examen normal"
        private const val EDAD = "3 años, 5 meses y 8 días"

        private val CAMPOS = MapSerializer(String.serializer(), String.serializer())
        private val SECCIONES = MapSerializer(String.serializer(), CAMPOS)
    }
}
