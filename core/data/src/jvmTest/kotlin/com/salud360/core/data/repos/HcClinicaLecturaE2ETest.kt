package com.salud360.core.data.repos

import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.idConsultaWeb
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ConsultasClinicaApi
import com.salud360.core.data.network.hc.ConsultasClinicaApiRemota
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcApiSoloLectura
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.Reloj
import com.salud360.core.data.repos.HcClinicaPruebas.filasSucias
import com.salud360.core.data.repos.HcClinicaPruebas.marcasVigentes
import com.salud360.core.data.repos.HcClinicaPruebas.sembrar
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcar
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.especialidad.TipoConsultaHc
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La lectura de la HC general (spec 002, entrega L, E8.1) con el `HcClinicaBackend` de verdad —`HcApiSoloLectura` sobre un
 * `HcApiClient` real— contra una API de hclinica de verdad (la copia local servida por XAMPP) y una base SQLite en un
 * archivo temporal, como la de un dispositivo. Comprueba lo que trae y, con la base real del servidor de por medio, que **solo
 * lee**: ningún pedido es otra cosa que `GET consultas`, `GET consultas/{id}` y `GET pacientes/{id}/historia`, nunca llama a
 * `pacientes/resolver` (la ficha de la web se siembra) y lo que el dispositivo ya tenía no se pisa ni se borra (Q15).
 *
 * Datos inventados: el fixture «Prueba Salud360» de E3.1/E4.4 (pacientes de documento 99000100, en la cartera del médico de
 * prueba, y 99000101, fuera de ella; consultas a-h). **No escribe en el servidor**: no hay nada que limpiar allá (sí se
 * vacía `salud360_token_cache`, que el middleware llena al validar el token). Los ids de la web cambian en cada carga del
 * fixture: se pasan por propiedades (`ids.sql` los da) y nunca se escriben en el repositorio. Se saltea sola sin las seis
 * propiedades. Nunca contra producción.
 *
 * ```
 * ./gradlew :core:data:jvmTest --rerun --tests '*HcClinicaLecturaE2ETest*' \
 *   "-Psalud360.test.hc.clinica=http://localhost/HClinica-salud360/public/index.php" \
 *   "-Psalud360.test.hc.clinica.token=<token de prueba del médico>" \
 *   "-Psalud360.test.hc.clinica.ficha=<id de la ficha del paciente 99000100>" \
 *   "-Psalud360.test.hc.clinica.ficha.ajena=<id de la ficha del paciente 99000101, fuera de la cartera>" \
 *   "-Psalud360.test.hc.clinica.consultas.ocultas=<ids de las consultas d,e,g del paciente 99000100, separados por coma>" \
 *   "-Psalud360.test.hc.clinica.consulta.ajena=<id de la consulta h, del paciente 99000101>"
 * ```
 *
 * Exige el fixture entero tal como lo deja `fixture.sql`: del paciente 99000100 la lista ofrece cuatro consultas (c, b, a,
 * f; se las reconoce por su fecha) con `omitidas {foto 1, otro 1}`, y no ofrece d (historia digitalizada), e (de baja) ni g
 * (tipo 2). Los pedidos van espaciados (la API tiene `throttle:60,1` por IP). Los ids de la ficha y de la consulta que no
 * existen son números inventados que no figuran en ninguna base.
 */
class HcClinicaLecturaE2ETest {

    private val url = propiedad("salud360.test.hc.clinica")
    private val token = propiedad("salud360.test.hc.clinica.token")
    private val fichaPropia = propiedad("salud360.test.hc.clinica.ficha")
    private val fichaAjena = propiedad("salud360.test.hc.clinica.ficha.ajena")
    private val ocultas = propiedad("salud360.test.hc.clinica.consultas.ocultas")
    private val consultaAjena = propiedad("salud360.test.hc.clinica.consulta.ajena")

    private val AJENO: Id = "p-99000101"
    private val INEXISTENTE: Id = "p-sin-ficha-en-la-web"

    /** Un id de ficha y de consulta con la forma de un id (hasta 9 cifras) que no existe en la web. */
    private val NO_EXISTE = 999_999_999L

    private val ficha get() = fichaPropia.toLong()

    /** Un objeto de la base temporal, la API contada y los dos backends (con el token y con uno inventado). */
    private class Ctx(
        val db: Salud360Db,
        val driver: SqlDriver,
        val reloj: Reloj,
        val api: ApiContada,
        val backend: HcClinicaBackend,
        val conTokenInvalido: HcClinicaBackend,
    )

    /**
     * La API de verdad (`ConsultasClinicaApiRemota` sobre `HcApiSoloLectura`) anotando cada pedido que sale, y espaciándolos:
     * hclinica corta con `429` a partir de 60 por minuto y por IP, y el test hace más de uno por segundo si nadie lo frena.
     */
    private class ApiContada(
        private val real: ConsultasClinicaApi,
        /** `lista:<ficha>:<desde>`, `consulta:<id>` o `historia:<ficha>`, en el orden en que salieron (se puede compartir). */
        val pedidos: MutableList<String> = mutableListOf(),
    ) : ConsultasClinicaApi {
        override suspend fun listar(pacienteWebId: Long, limite: Int, desde: Int): RespuestaListaConsultasDto =
            pedir("lista:$pacienteWebId:$desde") { real.listar(pacienteWebId, limite, desde) }

        override suspend fun consulta(id: Long): RespuestaConsultaDto = pedir("consulta:$id") { real.consulta(id) }

        override suspend fun historia(pacienteWebId: Long): RespuestaHistoriaDto = pedir("historia:$pacienteWebId") { real.historia(pacienteWebId) }

        private suspend fun <T> pedir(que: String, bloque: suspend () -> T): T {
            val falta = ultimo + ENTRE_PEDIDOS_MS - System.currentTimeMillis()
            if (falta > 0) delay(falta)
            pedidos += que
            try {
                return bloque()
            } finally {
                ultimo = System.currentTimeMillis()
            }
        }

        private companion object {
            const val ENTRE_PEDIDOS_MS = 1_100L

            /** De todas las instancias: el límite es del servidor, no de cada base. */
            @Volatile var ultimo = 0L
        }
    }

    // ------------------------------------------------------------------
    // (a) y (b): la lista y el historial
    // ------------------------------------------------------------------

    @Test
    fun laListaYElHistorialDejanEnLaBaseLoQueLaWebTiene() = conLaWeb {
        assertTrue(backend.soloLectura)
        val traidas = backend.traerConsultas(PACIENTE, MEDICO, forzar = true)

        // Dos pedidos —la lista y el historial— y ni uno más (RF-57); nunca la cartera ni pacientes/resolver (RF-49).
        assertEquals(listOf("lista:$ficha:0", "historia:$ficha"), api.pedidos)

        // (a) La lista: c, b, a y f; las de d, e y g no (foto, de baja y otro tipo).
        assertEquals(listOf(C, B, A, F).map { idWeb(it) }, traidas.map { it.remotoId }, "de la más nueva a la más vieja")
        assertTrue(traidas.all { it.id == idConsultaWeb(it.remotoId.toLong()) && it.especialidad == CodigosHc.GENERAL })
        val a = idWeb(A)
        val b = idWeb(B)
        val c = idWeb(C)
        val f = idWeb(F)
        val medicoDeB = textos(driver, "SELECT medico_id FROM consulta WHERE id = 'hcc-c$b'").single()
        assertTrue(medicoDeB.startsWith("hcc-m") && medicoDeB != MEDICO, "la de otro médico no es propia: $medicoDeB")
        assertEquals(
            listOf(
                "hcc-c$c|$MEDICO|${TipoConsultaHc.CONSULTA.codigo}|$C|ABIERTA||$c|0",
                "hcc-c$b|$medicoDeB|${TipoConsultaHc.TELEMEDICINA.codigo}|$B|CERRADA||$b|0",
                "hcc-c$a|$MEDICO|${TipoConsultaHc.CONSULTA.codigo}|$A|CERRADA|45 años|$a|0",
                "hcc-c$f|$MEDICO|${TipoConsultaHc.CONSULTA.codigo}|$F|CERRADA||$f|0",
            ),
            textos(
                driver,
                "SELECT id||'|'||medico_id||'|'||tipo||'|'||fecha||'|'||estado||'|'||edad_mostrar||'|'||remoto_id||'|'||dirty " +
                    "FROM consulta WHERE especialidad = 'clinica' ORDER BY fecha DESC",
            ),
        )
        assertEquals(listOf("Prueba Salud360 Otro médico"), textos(driver, "SELECT valor FROM medico_preferencia WHERE medico_id = '$medicoDeB' AND clave = 'nombre'"))
        assertEquals(listOf("1|1|0"), textos(driver, "SELECT valor FROM paciente_extra WHERE paciente_id = '$PACIENTE' AND clave = 'hcc_omitidas'"), "omitidas {foto 1, otro 1}")

        // (b) El historial.
        assertEquals(
            listOf(
                "acv|1|Prueba Salud360 acv detalle (c)|familiares|1|-|0|hcc-a$ficha-acv",
                "nota|1|Prueba Salud360 nota antecedentes (c)|familiares|1|-|0|hcc-a$ficha-nota",
            ),
            textos(
                driver,
                "SELECT clave||'|'||flag||'|'||detalle||'|'||categoria||'|'||activo||'|'||ifnull(consulta_id,'-')||'|'||dirty||'|'||id " +
                    "FROM antecedente WHERE paciente_id = '$PACIENTE' ORDER BY clave",
            ),
            "antecedentes familiares: la última fila activa del paciente (la de c), sin las casillas en 0 y sin la nota del perfil 4",
        )

        val general = cargas("laboratorio_general")
        assertEquals(listOf("2026-03-10", "2026-03-01"), general.map { it.fecha })
        assertTrue(general.all { Regex("hcc-llaboratorio_general-\\d+").matches(it.id) && it.consulta == "hcc-c$a" && it.dirty == "0" })
        assertEquals(
            mapOf(
                "hto" to "41", "hb" to "13.9", "plaquetas" to "210", "glucemia" to "99", "creatinina" to "0.9", "alb_mina" to "4.1",
                "f_sforo" to "3.5", "tgo" to "30", "tgp" to "28", "bt" to "0.8", "tp" to "12", "concentraci_n" to "95",
                "ferritina" to "120", "_cido_rico" to "5.2", "b12_homociste_na_f_lico" to "450", "peso" to "71",
                "otro" to "Prueba Salud360 otro lab 2",
            ),
            general[0].valores,
        )
        assertEquals(
            mapOf(
                "hto" to "40", "hb" to "13.5", "glucemia" to "95", "alb_mina" to "4.0", "f_sforo" to "3.4", "concentraci_n" to "90",
                "_cido_rico" to "5.0", "b12_homociste_na_f_lico" to "420", "otro" to "Prueba Salud360 otro lab 1",
            ),
            general[1].valores,
        )
        val reducido = cargas("laboratorio_reducido").single()
        assertTrue(Regex("hcc-llaboratorio_reducido-\\d+").matches(reducido.id))
        assertEquals("2026-03-10", reducido.fecha)
        assertEquals(
            mapOf(
                "hemograma" to "normal", "ferritina" to "110", "hepatograma" to "normal", "glucemia" to "97", "hba1c" to "5.4",
                "colesterol_total" to "190", "ldl" to "110", "tg" to "140", "urea" to "30", "creatinina" to "0.8", "cpk" to "120",
                "otros" to "Prueba Salud360 otro reducido",
            ),
            reducido.valores,
        )
        val pesos = cargas("peso")
        assertEquals(listOf("2026-03-10" to "70.5", "2026-02-01" to "71.0"), pesos.map { it.fecha to it.valores["peso"] })
        assertTrue(pesos.all { Regex("hcc-lpeso-\\d+").matches(it.id) })
        assertEquals("5", textos(driver, "SELECT COUNT(*) FROM laboratorio").single(), "ni una carga más: la del 2000-01-01 no llega")
        assertEquals("0", textos(driver, "SELECT COUNT(*) FROM laboratorio WHERE fecha LIKE '2000-%'").single())
        assertEquals(listOf("1"), textos(driver, "SELECT valor FROM paciente_extra WHERE paciente_id = '$PACIENTE' AND clave = 'hcc_omitidas_laboratorio'"), "laboratorio_sin_fecha")

        val estudios = registros().filter { it.tipo in ESTUDIOS }
        assertEquals(
            listOf(
                Triple("eco_abdominal", "2026-02-01", "hcc-c$a") to "Prueba Salud360 ecografía abdominal (a)",
                Triple("eco_abdominal", "-", "hcc-c$f") to "Prueba Salud360 ecografía abdominal (f) sin fecha",
                Triple("veda", "2026-02-02", "hcc-c$a") to "Prueba Salud360 videoendoscopia (a)",
                Triple("biopsia_hepatica", "2026-02-03", "hcc-c$a") to "Prueba Salud360 biopsia hepática (a)",
                Triple("estudios_cardiologicos", "2026-02-20", "hcc-c$f") to "Prueba Salud360 " + "x".repeat(175),
                Triple("estudios_cardiologicos", "2026-02-05", "hcc-c$a") to "Prueba Salud360 cardiológico (a)",
                Triple("tc_rmn", "2026-02-04", "hcc-c$a") to "Prueba Salud360 TC/RMN (a)",
            ).sortedBy { (k, _) -> k.first + k.second }.map { (k, v) -> "${k.first}|${k.second}|${k.third}|$v" },
            estudios.map { "${it.tipo}|${it.fecha}|${it.consulta}|${it.campos.getValue("detalle")}" },
            "los cinco estudios del paciente, la cardiológica de 191 caracteres entera",
        )
        assertEquals(191, estudios.first { it.tipo == "estudios_cardiologicos" && it.fecha == "2026-02-20" }.campos.getValue("detalle").length)
        estudios.forEach { assertEquals("hcc-r${it.tipo}-${it.remoto}", it.id, "el id local lleva el tipo") }
        val repetidos = estudios.filter { it.tipo in listOf("veda", "biopsia_hepatica", "tc_rmn") }
        assertEquals(1, repetidos.map { it.remoto }.toSet().size, "el fixture repite el número de la web en las tres tablas (E4.1)")
        assertEquals(3, repetidos.map { it.id }.toSet().size, "…y los ids locales no chocan")
        assertTrue(estudios.all { it.dirty == "0" })

        val examenes = registros().filter { it.tipo == "examen_complementario" }
        assertEquals(
            listOf(
                mapOf("solicito" to "Prueba Salud360 examen solicitado 1", "respuesta" to "Prueba Salud360 examen respondido 1", "numero" to "1"),
                mapOf("solicito" to "Prueba Salud360 examen solicitado 2", "numero" to "2"),
            ),
            examenes.sortedBy { it.campos["numero"] }.map { it.campos },
        )
        assertTrue(examenes.all { it.fecha == "2026-03-10" && it.consulta == "hcc-c$a" && it.id == "hcc-rexamen_complementario-${it.remoto}" })
        val inter = registros().single { it.tipo == "interconsulta" }
        assertEquals(
            mapOf(
                "especialista" to "Prueba Salud360 especialista 1", "solicito" to "Prueba Salud360 interconsulta solicitada 1",
                "respuesta" to "Prueba Salud360 interconsulta respondida 1", "numero" to "1",
            ),
            inter.campos,
        )
        assertEquals("hcc-rinterconsulta-${inter.remoto}", inter.id)
        assertEquals(10, registros().size)

        // Los motivos de las cuatro consultas (las «consultas previas»): lo único que el historial escribe en las secciones.
        assertEquals(
            listOf("hcc-c$a=Prueba Salud360 motivo (a)", "hcc-c$b=Prueba Salud360 motivo (b)", "hcc-c$c=Prueba Salud360 motivo (c)", "hcc-c$f=Prueba Salud360 motivo (f)")
                .sorted(),
            textos(driver, "SELECT consulta_id||'='||valor FROM seccion_valor WHERE seccion = 'motivo_consulta' ORDER BY consulta_id"),
        )
        assertEquals("4", textos(driver, "SELECT COUNT(*) FROM seccion_valor").single())
        assertEquals("0", textos(driver, "SELECT COUNT(*) FROM examen_fisico").single(), "el examen físico viene con cada consulta, no con el historial")

        val pendiente = textos(driver, "SELECT id||'|'||medico_id||'|'||texto||'|'||ifnull(consulta_id,'-')||'|'||resuelto||'|'||dirty FROM pendiente").single()
        assertTrue(Regex("hcc-p\\d+\\|${Regex.escape(MEDICO)}\\|Prueba Salud360 pendiente 2 \\(c\\)\\|-\\|0\\|0").matches(pendiente), pendiente)

        // Solo leer, y nada que enviar.
        assertNadaSucio()
        assertEquals(emptyList(), marcasVigentes(driver), "no desapareció nada")
        val antes = api.pedidos.toList()
        assertFailsWith<UnsupportedOperationException> { backend.enviarSecciones("1", mapOf("nota" to mapOf("texto" to "x"))) }
        assertFailsWith<UnsupportedOperationException> { backend.asegurarConsulta(traidas.first()) }
        assertEquals(antes, api.pedidos, "los envíos ni siquiera salen")
    }

    // ------------------------------------------------------------------
    // (c): una consulta
    // ------------------------------------------------------------------

    @Test
    fun cadaConsultaTraeSusSeccionesYSuExamenFisicoSinNadaPendienteDeEnvio() = conLaWeb {
        backend.traerConsultas(PACIENTE, MEDICO, forzar = true)
        val a = idWeb(A)
        val b = idWeb(B)
        val c = idWeb(C)
        val f = idWeb(F)
        val sinConsultas = api.pedidos.size

        for (id in listOf(a, b, c, f)) backend.traerConsulta("hcc-c$id", id)
        assertEquals(listOf("consulta:$a", "consulta:$b", "consulta:$c", "consulta:$f"), api.pedidos.drop(sinConsultas), "un pedido por consulta (RF-57)")

        // a: todo lo que la web guarda por consulta.
        assertEquals(
            setOf(
                "motivo_consulta/texto=Prueba Salud360 motivo (a)",
                "datos_subjetivos/texto=Prueba Salud360 datos subjetivos (a)",
                "datos_objetivos/texto=Prueba Salud360 datos objetivos (a)",
                "conductas/texto=Prueba Salud360 conductas (a)",
                "nota/texto=Prueba Salud360 nota (a)",
                "habitos/texto=Prueba Salud360 hábitos (a): tabaco, alcohol, ejercicio",
                "diuresis_catarsis/texto=Prueba Salud360 diuresis y catarsis (a)",
                "somnia/texto=Prueba Salud360 somnia (a)",
                "alimentacion/hiposodica=1|Prueba Salud360 hiposódica (a)",
                "alimentacion/celiaco=1|Prueba Salud360 celíaco detalle (a)",
                "serologias/igm_hav=SI", "serologias/ags_hbv=NO", "serologias/ig_anti_hcv=SI", "serologias/anti_lkm=NO",
                "serologias/igg_hav=SI", "serologias/ig_anti_core=NO", "serologias/ig_anti_hdv=SI", "serologias/asma=NO",
                "serologias/hiv=NO", "serologias/ag_e=SI", "serologias/ig_anti_hev=NO", "serologias/ama=SI",
                "child_pugh/ascitis=2", "child_pugh/bilirrubina=1", "child_pugh/albumina=3", "child_pugh/tp=2", "child_pugh/encefalopatia=1",
            ),
            secciones(a),
        )
        assertEquals(listOf("70.5|170|120/80|24.4|Prueba Salud360 examen físico (a)|0"), examenFisico(a))
        // La clase de Child-Pugh (suma 9) se calcula en la app con estos cinco puntos: la web no manda total.
        assertEquals(9, secciones(a).filter { it.startsWith("child_pugh/") }.sumOf { it.substringAfter('=').toInt() })

        // b: la de otro médico, con menos secciones y la alimentación «1|» (marcada, sin detalle).
        assertEquals(
            setOf(
                "motivo_consulta/texto=Prueba Salud360 motivo (b)",
                "datos_subjetivos/texto=Prueba Salud360 datos subjetivos (b)",
                "datos_objetivos/texto=Prueba Salud360 datos objetivos (b)",
                "conductas/texto=Prueba Salud360 conductas (b)",
                "nota/texto=Prueba Salud360 nota (b)",
                "alimentacion/diabetes=1|", "serologias/igm_hav=NO", "serologias/hiv=SI",
            ),
            secciones(b),
        )
        assertEquals(listOf("80||130/85|||0"), examenFisico(b))

        // c: la abierta en la web; sus «0.0» y «0» del examen físico son datos.
        assertEquals(
            setOf(
                "motivo_consulta/texto=Prueba Salud360 motivo (c)", "conductas/texto=Prueba Salud360 conductas (c)",
                "nota/texto=Prueba Salud360 nota (c) en curso",
            ),
            secciones(c),
        )
        assertEquals(listOf("|0.0||0||0"), examenFisico(c))

        // f: solo el motivo; la web avisa que tiene un PDF (no se baja).
        assertEquals(setOf("motivo_consulta/texto=Prueba Salud360 motivo (f)"), secciones(f))
        assertEquals(emptyList(), examenFisico(f), "sin examen físico no se inventa una fila")
        assertEquals(
            listOf("hcc_no_leidas_$f=documentos"),
            textos(driver, "SELECT clave||'='||valor FROM paciente_extra WHERE paciente_id = '$PACIENTE' AND clave LIKE 'hcc!_no!_leidas!_%' ESCAPE '!'"),
            "no_leidas: solo la consulta f",
        )

        assertNadaSucio()
        assertEquals(emptyList(), marcasVigentes(driver))
    }

    // ------------------------------------------------------------------
    // (f): leer dos veces
    // ------------------------------------------------------------------

    @Test
    fun laSegundaLecturaDejaLaMismaBaseYDentroDelMinutoNoPideNada() = conLaWeb {
        backend.traerConsultas(PACIENTE, MEDICO, forzar = true)
        val ids = listOf(C, B, A, F).map { idWeb(it) } // en el orden de la historia: de la más nueva a la más vieja
        ids.forEach { backend.traerConsulta("hcc-c$it", it) }
        val base = volcarTodo(driver)
        val pedidos = api.pedidos.size
        assertEquals(2 + 4, pedidos)

        // Dentro de los 60 s: ni un pedido ni un cambio.
        reloj.avanzar(59_999)
        val otra = backend.traerConsultas(PACIENTE, MEDICO, forzar = false)
        assertEquals(ids, otra.map { it.remotoId })
        assertEquals(pedidos, api.pedidos.size, "0 pedidos dentro de los 60 s (RF-57)")
        assertEquals(base, volcarTodo(driver))

        // A los 60 s sí vuelve a preguntar, y la web dice lo mismo: la base queda idéntica (hasta el `updated_at`).
        reloj.avanzar(1)
        backend.traerConsultas(PACIENTE, MEDICO, forzar = false)
        assertEquals(listOf("lista:$ficha:0", "historia:$ficha"), api.pedidos.drop(pedidos))
        assertEquals(base, volcarTodo(driver), "traer lo mismo dos veces no duplica ni reescribe nada")

        // Y cada consulta, otra vez, también.
        ids.forEach { backend.traerConsulta("hcc-c$it", it) }
        assertEquals(base, volcarTodo(driver))
        // «Actualizar» (forzar) tampoco cambia nada si la web no cambió.
        backend.traerConsultas(PACIENTE, MEDICO, forzar = true)
        assertEquals(base, volcarTodo(driver))
        assertNadaSucio()
    }

    // ------------------------------------------------------------------
    // (d): lo que desaparece de la web
    // ------------------------------------------------------------------

    @Test
    fun loQueLaWebDejaDeMostrarSeMarcaYNoSeBorraNiSeVacia() = conLaWeb {
        val deg = ocultas.split(',').map { it.trim() }
        assertEquals(3, deg.size, "d, e y g")
        // Consultas que el dispositivo ya había traído y la web hoy no ofrece: d (historia digitalizada), e (de baja), g (otro tipo)
        // y una que no existe. Se siembran en la base local; el servidor no se toca.
        val sembradas = deg + NO_EXISTE.toString()
        for (id in sembradas) {
            db.sembrar("hcc-c$id")
            db.historiaClinicaQueries.upsertSeccionValor(SeccionValor("hcc-c$id", "nota", "texto", "texto traído antes de $id").toRow(111, dirty = false))
            db.historiaClinicaQueries.upsertExamenFisico(ExamenFisico("hcc-c$id", peso = "60").toRow(111, dirty = false))
        }
        val propias = {
            sembradas.map { id ->
                volcar(driver, "consulta").filter { it.startsWith("'hcc-c$id'|") } +
                    volcar(driver, "seccion_valor").filter { it.startsWith("'hcc-c$id'|'nota'|") } +
                    volcar(driver, "examen_fisico").filter { it.startsWith("'hcc-c$id'|") }
            }
        }
        val antes = propias()

        val traidas = backend.traerConsultas(PACIENTE, MEDICO, forzar = true)
        assertEquals(
            sembradas.map { "hcc-c$it" }.toSet(),
            marcasVigentes(driver).map { it.substringBefore('=') }.toSet(),
            "las cuatro consultas se marcan «Ya no figura en la web»; las que la web sigue mandando, no",
        )
        assertTrue(marcasVigentes(driver).all { Regex("hcc-c\\d+=\\d{4}-\\d{2}-\\d{2}").matches(it) })
        assertEquals(antes, propias(), "nada se borra ni se vacía (Q15)")
        assertEquals(setOf("0"), textos(driver, "SELECT deleted FROM consulta").toSet())
        // Siguen en la lista que ve el médico, con todo su contenido.
        assertTrue(traidas.map { it.id }.containsAll(sembradas.map { "hcc-c$it" }), "no desaparecen de la historia")
        assertEquals(8, traidas.size)

        // El contenido de una consulta que sigue en la web y dejó de mandar un valor: la marca es del campo.
        val a = idWeb(A)
        db.historiaClinicaQueries.upsertSeccionValor(SeccionValor("hcc-c$a", "alimentacion", "diabetes", "1|valor de antes").toRow(111, dirty = false))
        backend.traerConsulta("hcc-c$a", a)
        assertEquals(
            listOf("alimentacion/diabetes=1|valor de antes"),
            textos(driver, "SELECT seccion||'/'||campo||'='||valor FROM seccion_valor WHERE consulta_id = 'hcc-c$a' AND seccion = 'alimentacion' AND campo = 'diabetes'"),
        )
        assertTrue(marcasVigentes(driver).map { it.substringBefore('=') }.contains("hcc-c$a:alimentacion:diabetes"), "la marca del campo: ${marcasVigentes(driver)}")
        assertFalse(marcasVigentes(driver).map { it.substringBefore('=') }.contains("hcc-c$a"), "la consulta a sigue en la web")

        // Pedirlas una por una: la web las desconoce (404 consulta_no_encontrada), y el dispositivo las conserva igual.
        val marcas = marcasVigentes(driver)
        for (id in sembradas) {
            val error = assertFailsWith<TobbException>("consulta $id") { backend.traerConsulta("hcc-c$id", id) }
            assertEquals(404, error.status)
            assertEquals("consulta_no_encontrada", error.codigo)
        }
        assertEquals(marcas, marcasVigentes(driver))
        assertEquals(antes, propias())
        assertEquals(setOf("0"), textos(driver, "SELECT deleted FROM consulta").toSet())
        assertEquals("8", textos(driver, "SELECT COUNT(*) FROM consulta WHERE especialidad = 'clinica'").single())
        assertNadaSucio()
    }

    // ------------------------------------------------------------------
    // (e): los errores de la web
    // ------------------------------------------------------------------

    @Test
    fun losErroresDeLaWebSalenTalCualSinTocarLaBase() = conLaWeb {
        val d = ocultas.split(',').first().trim()
        // Una consulta ya traída del paciente de la cartera, otra del paciente fuera de ella y la lista de cada uno.
        db.sembrar("hcc-c$d")
        db.sembrar("hcc-c$consultaAjena", paciente = AJENO)
        val antes = volcarTodo(driver)

        // El paciente fuera de la cartera: 403 fuera_de_cartera, por la lista y por su consulta.
        var error = assertFailsWith<TobbException> { backend.traerConsultas(AJENO, MEDICO, forzar = true) }
        assertEquals(403 to "fuera_de_cartera", error.status to error.codigo)
        assertEquals(listOf("lista:$fichaAjena:0"), api.pedidos, "si falla la lista no se pide el historial")
        error = assertFailsWith<TobbException> { backend.traerConsulta("hcc-c$consultaAjena", consultaAjena) }
        assertEquals(403 to "fuera_de_cartera", error.status to error.codigo)
        assertEquals(antes, volcarTodo(driver))

        // Una ficha que no existe: 404 paciente_no_encontrado.
        error = assertFailsWith<TobbException> { backend.traerConsultas(INEXISTENTE, MEDICO, forzar = true) }
        assertEquals(404 to "paciente_no_encontrado", error.status to error.codigo)
        assertEquals(antes, volcarTodo(driver))

        // Un token que no es de nadie: 401, y la base queda como estaba.
        error = assertFailsWith<TobbException> { conTokenInvalido.traerConsultas(PACIENTE, MEDICO, forzar = true) }
        assertEquals(401, error.status)
        error = assertFailsWith<TobbException> { conTokenInvalido.traerConsulta("hcc-c$d", d) }
        assertEquals(401, error.status)
        assertEquals(antes, volcarTodo(driver))

        assertEquals(
            listOf("lista:$fichaAjena:0", "consulta:$consultaAjena", "lista:$NO_EXISTE:0", "lista:$ficha:0", "consulta:$d"),
            api.pedidos,
            "un solo pedido por error, sin reintentos",
        )
        assertNadaSucio()
    }

    // ------------------------------------------------------------------
    // (g): lo del dispositivo gana
    // ------------------------------------------------------------------

    @Test
    fun loLocalSucioYLoCreadoEnElDispositivoNoSeToca() = conLaWeb {
        val cabeceras = api.listar(ficha, 50, 0).consultas
        val porFecha = cabeceras.associate { it.fecha to it.id.toString() }
        val a = porFecha.getValue(A)
        val b = porFecha.getValue(B)
        val c = porFecha.getValue(C)
        val creada = "0b1c6e56-aaaa-4bbb-8ccc-000000000001"
        val sinEnviar = "0b1c6e56-aaaa-4bbb-8ccc-000000000002"
        val q = db.historiaClinicaQueries

        // a: la web la tiene cerrada y la app la editó sin enviar. c: creada en el dispositivo y ya vinculada con la c de la web.
        // Otra creada en el dispositivo que la web no conoce. b: limpia, pero con un motivo y un examen físico sin enviar.
        db.sembrar("hcc-c$a", estado = EstadoConsulta.ABIERTA, edad = "lo que escribió el médico", dirty = true, actualizada = 999)
        db.sembrar(creada, remotoId = c, estado = EstadoConsulta.CERRADA, edad = "propia", actualizada = 400)
        db.sembrar(sinEnviar, remotoId = "", estado = EstadoConsulta.ABIERTA, dirty = true, actualizada = 400)
        db.sembrar("hcc-c$b", estado = EstadoConsulta.CERRADA)
        q.upsertSeccionValor(SeccionValor("hcc-c$b", "motivo_consulta", "texto", "motivo sin enviar").toRow(10, dirty = true))
        q.upsertExamenFisico(ExamenFisico("hcc-c$b", peso = "99").toRow(10, dirty = true))
        q.upsertAntecedente(Antecedente("ant-local-acv", PACIENTE, "clinica", "familiares", "acv", true, "local sin enviar").toRow(10, dirty = true))
        val propias = {
            listOf(
                volcar(driver, "consulta").filter { fila -> listOf("hcc-c$a", creada, sinEnviar).any { fila.startsWith("'$it'|") } },
                volcar(driver, "seccion_valor").filter { it.startsWith("'hcc-c$b'|'motivo_consulta'|") },
                volcar(driver, "examen_fisico"),
                volcar(driver, "antecedente").filter { it.startsWith("'ant-local-acv'|") },
            )
        }
        val antes = propias()
        val sucias = filasSucias(driver)
        assertEquals(5, sucias)
        val pedidos = api.pedidos.size

        backend.traerConsultas(PACIENTE, MEDICO, forzar = true)
        backend.traerConsulta("hcc-c$b", b)
        assertEquals(pedidos + 3, api.pedidos.size, "lista, historial y la consulta b")
        // Las tres que son del dispositivo ni siquiera se piden.
        backend.traerConsulta("hcc-c$a", a)
        backend.traerConsulta(creada, c)
        backend.traerConsulta(sinEnviar, "")
        assertEquals(pedidos + 3, api.pedidos.size, "una consulta sucia o del dispositivo no se pide")

        assertEquals(antes[0], propias()[0], "las consultas sucias y la creada en el dispositivo")
        assertEquals(antes[1], propias()[1], "el motivo sin enviar")
        assertEquals(antes[2], propias()[2], "el examen físico sin enviar (la web mandaba otro peso)")
        assertEquals(antes[3], propias()[3], "el antecedente sin enviar")
        assertEquals("0", textos(driver, "SELECT COUNT(*) FROM consulta WHERE id = 'hcc-c$c'").single(), "la c de la web no se duplica: ya está vinculada con la del dispositivo")
        assertEquals(listOf("ant-local-acv"), textos(driver, "SELECT id FROM antecedente WHERE clave = 'acv'"), "el antecedente de la web no se suma al del dispositivo")
        assertEquals(sucias, filasSucias(driver), "lo traído de la web no queda sucio y lo sucio sigue igual")
        assertEquals("hcc-a$ficha-nota", textos(driver, "SELECT id FROM antecedente WHERE clave = 'nota'").single(), "lo demás sí se trajo")
        assertEquals(
            "Prueba Salud360 datos subjetivos (b)",
            textos(driver, "SELECT valor FROM seccion_valor WHERE consulta_id = 'hcc-c$b' AND seccion = 'datos_subjetivos'").single(),
            "de la consulta b se trajo todo lo que no estaba sucio",
        )
    }

    // ------------------------------------------------------------------
    // Armado
    // ------------------------------------------------------------------

    private class CargaFila(val id: String, val fecha: String, val consulta: String, val valores: Map<String, String>, val dirty: String)

    private class RegistroFila(
        val id: String, val tipo: String, val fecha: String, val consulta: String,
        val campos: Map<String, String>, val remoto: String, val dirty: String,
    )

    private val json = Json

    private fun Ctx.cargas(tipo: String): List<CargaFila> = textos(
        driver,
        "SELECT id||char(9)||fecha||char(9)||ifnull(consulta_id,'-')||char(9)||valores_json||char(9)||dirty FROM laboratorio " +
            "WHERE tipo = '$tipo' ORDER BY fecha DESC",
    ).map { fila ->
        val c = fila.split('\t')
        CargaFila(c[0], c[1], c[2], json.decodeFromString<Map<String, String>>(c[3]), c[4])
    }

    private fun Ctx.registros(): List<RegistroFila> = textos(
        driver,
        "SELECT id||char(9)||tipo||char(9)||ifnull(fecha,'-')||char(9)||ifnull(consulta_id,'-')||char(9)||campos_json||char(9)||remoto_id||char(9)||dirty " +
            "FROM registro_clinico ORDER BY tipo, fecha",
    ).map { fila ->
        val c = fila.split('\t')
        RegistroFila(c[0], c[1], c[2], c[3], json.decodeFromString<Map<String, String>>(c[4]), c[5], c[6])
    }

    private fun Ctx.secciones(idWeb: String): Set<String> = textos(
        driver, "SELECT seccion||'/'||campo||'='||valor FROM seccion_valor WHERE consulta_id = 'hcc-c$idWeb'",
    ).toSet()

    private fun Ctx.examenFisico(idWeb: String): List<String> = textos(
        driver,
        "SELECT peso||'|'||talla||'|'||tension_arterial||'|'||imc||'|'||nota||'|'||dirty FROM examen_fisico WHERE consulta_id = 'hcc-c$idWeb'",
    )

    /** El id de la web de la consulta de [fecha] del paciente de prueba (las cuatro del fixture tienen fechas distintas). */
    private fun Ctx.idWeb(fecha: String): String = textos(
        driver, "SELECT remoto_id FROM consulta WHERE paciente_id = '$PACIENTE' AND especialidad = 'clinica' AND fecha = '$fecha'",
    ).single()

    /** Lo traído de la web no queda con pendiente de envío (RF-53). */
    private fun Ctx.assertNadaSucio() {
        assertEquals(0, filasSucias(driver), "ninguna fila de la historia clínica quedó sucia")
        assertEquals("0", textos(driver, "SELECT COUNT(*) FROM paciente WHERE dirty <> 0").single())
        assertEquals("0", textos(driver, "SELECT COUNT(*) FROM paciente_extra WHERE dirty <> 0").single())
    }

    private fun hayPropiedades(): Boolean {
        val faltan = listOf(
            "salud360.test.hc.clinica" to url, "salud360.test.hc.clinica.token" to token, "salud360.test.hc.clinica.ficha" to fichaPropia,
            "salud360.test.hc.clinica.ficha.ajena" to fichaAjena, "salud360.test.hc.clinica.consultas.ocultas" to ocultas,
            "salud360.test.hc.clinica.consulta.ajena" to consultaAjena,
        ).filter { it.second.isBlank() }.map { it.first }
        if (faltan.isNotEmpty()) println("HcClinicaLecturaE2ETest: sin ${faltan.joinToString()}, no se corre.")
        return faltan.isEmpty()
    }

    /** Una base SQLite nueva en un archivo temporal (un dispositivo) con los tres pacientes sembrados y sus fichas, sin `resolver`. */
    private fun conLaWeb(bloque: suspend Ctx.() -> Unit) {
        if (!hayPropiedades()) return
        val archivo = File.createTempFile("salud360-clinica-lectura", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    val db = Salud360Db(driver)
                    listOf(
                        Triple(PACIENTE, "99000100", ficha),
                        Triple(AJENO, "99000101", fichaAjena.toLong()),
                        Triple(INEXISTENTE, "99000102", NO_EXISTE),
                    ).forEach { (id, dni, fichaWeb) ->
                        db.pacientesQueries.upsertPaciente(Paciente(id, dni, "Salud360", "Prueba").toRow(0, dirty = false))
                        db.pacientesQueries.upsertPacienteExtra(
                            PacienteExtra(id, CodigosHc.GENERAL, PacientesHcRepository.EXTRA_HC_PACIENTE_ID, fichaWeb.toString()).toRow(0, dirty = false),
                        )
                    }
                    val reloj = Reloj()
                    val pacientes = HcClinicaPruebas.pacientes(db)
                    fun remota(token: String) = ConsultasClinicaApiRemota(HcApiSoloLectura(HcApiClient(url, "clinica").also { it.token = token }))
                    val contada = ApiContada(remota(token))
                    val backend = HcClinicaBackend(db, contada, pacientes, { reloj.millis }, HcClinicaBackend.MAX_PAGINAS)
                    // Otro token, la misma cuenta de pedidos: el pedido sale igual y la web contesta 401.
                    val invalida = ApiContada(remota("token-inventado-para-la-prueba"), contada.pedidos)
                    val malo = HcClinicaBackend(db, invalida, pacientes, { reloj.millis }, HcClinicaBackend.MAX_PAGINAS)
                    Ctx(db, driver, reloj, contada, backend, malo).bloque()
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    private companion object {
        /** Las fechas con las que se reconoce cada consulta del fixture. */
        const val A = "2026-03-10"
        const val B = "2026-04-14"
        const val C = "2026-09-01"
        const val F = "2026-02-20"

        val ESTUDIOS = setOf("eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn")

        fun propiedad(clave: String) = System.getProperty(clave).orEmpty()
    }
}
