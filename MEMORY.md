# MEMORY.md — Salud 360

Memoria del proyecto entre sesiones. Resumir o borrar lo que ya no aporte; el detalle por tarea vive en
`specs/*/tasks.md` y en `git log`. Nunca datos sensibles (tokens, contraseñas, datos de pacientes, fechas de
registros de personas): el repo es público.

## Traspaso 2026-11-02 (clínica retomada el 2026-11-03, pediatría primero)
- **Clínica retomada el 2026-11-03:** E8.2 hecha y E8.3 cerrada por decisión del usuario con excepciones (ver tasks.md de la spec 002).
  E8.4 (revisor) quedó aprobada con observaciones (ver abajo); la limpieza sigue pendiente.
- **Spec 005 (escritura de la HC general):** spec APROBADA el 2026-11-04 (D1 a D27); plan y tareas aprobados el 2026-11-04 (E0 y E1
  detalladas, 47 tareas; E2 a E5 en lista); **E0 cerrada por el usuario el 2026-11-04** (médico de prueba y documento libre confirmados);
  línea base en %TEMP%\opencode\e0\linea-base.txt. **E0 cerrada; T6 hecha (2026-11-04):** la licencia del servidor T1–T7 de la 004 está en
  `hc-general` (9 cherry-picks `ba679f5` a `6d2cf4d`, más 3 tests de la 004 adaptados); `core:data` 1305 tests en verde y composeApp/features:auth wasm
  compilan. **T1 a T7 hechas; T8 hecha (2026-11-04): reglas puras de escritura en hclinica `e35d8e7` (`EscrituraReglas`, 215 tests; `tests/Unit` 684
  en verde; copiado a la servida). T9 hecha (2026-11-04): `EnviosRegistro` en hclinica `9819292` (tabla propia `salud360_envios`, único
  `(user_id, clave)`, `conTransaccion`; 66 tests, `tests/Unit` 750 en verde; probado con base en la descartable `hc_clinica_t9`, que sigue creada: borrar con
  `DROP DATABASE hc_clinica_t9;` cuando haga falta). T10 hecha (2026-11-04): `POST consultas` en hclinica `6b3306f`
  (`ConsultaEscrituraController` + `EscrituraService`; `tests/Unit` 766 en verde; probado en la descartable `hc_clinica_t10` y por HTTP contra la copia local, limpiado por id
  y por clave con las sumas iguales a la línea base; `salud360_envios` ya existe y está vacía en `hc_clinica` local; la ficha de prueba 1440 se limpió: T11 pide cargar otra);
  T1 a T10 hechas; T11 diferida a la prueba general del usuario (datos de prueba P1–P5 siguen cargados; limpiar con `t11\limpiar-t11.cmd`); T12 hecha (hclinica `37a0485`: `PUT consultas/{id}/secciones` y `/examen`; `tests/Unit` 789 en verde; `medico_infos` y P1–P5 intactos). T13 hecha (2026-11-04): `POST consultas/{id}/cerrar` y `DELETE consultas/{id}` en hclinica `ed0bb61` (`tests/Unit` 805 en verde; probado en la descartable y por HTTP, limpiado por id y por clave con la base igual a `t11\snap-base-antes`; baja de cerrada = 409 resuelto; MySQL y Apache del XAMPP se levantan a mano si la máquina se reinicia: `mysqld --defaults-file=C:\xampp\mysql\bin\my.ini --standalone` y `C:\xampp\apache\bin\httpd.exe`); el detalle va en tasks.md. T14 hecha (2026-11-04): hclinica `5ebb410` (solo la documentación «Escritura (E1)»); pasada por HTTP 111 controles OK, scripts con base 209 por modo, greps limpios, `tests/Unit` 805 en verde, servida idéntica al clon, `salud360_token_cache` vaciada y la base igual a `base-antes`; **servidor de E1 (T8 a T14) hecho; T11 diferida; sigue T15** (la app).** T7 (2026-11-04): la 004 quedó enmendada en texto en `hc-general` (la
  HC general también lleva licencia, regla D25; sin código); falta avisar en `origin/main`. Datos de la L y de la 003 saldados con excepciones (ver «Limpieza pendiente»), salvo el fixture de la
  tanda S, que se limpia en T45 (respaldo en la base descartable hc_clinica_respaldo, volcado en %TEMP%\opencode\e0). T9 de la 004 ya integrada en
  `hc-general` (2026-11-04). RF-50 de la 002 ya enumera lo que escribe el middleware (D17).
  Quedan 4 observaciones de E8.4 (1 a 4 de su nota: RF-57 con más de 50 consultas, RF-50 y lo que escribe el middleware, prueba manual
  y baja real contra la copia local, filas `dirty` del dispositivo sin medir) a resolver o aceptar por escrito antes de pedir E9.
- **T15 de la 005 hecha (2026-11-04, `89e62d8`): migración `9.sqm` RESERVADA (esquema v10)**, `consulta.propia INTEGER NOT NULL DEFAULT 0`
  (0 = histórico o de la web, 1 = propia). Autorizada por el usuario; se miraron todas las ramas locales y remotas (hc-general llega al 8,
  main al 5, hc_odontologia al 2). **No hay vuelta atrás en un dispositivo migrado** (una app v9 sobre una base v10 descarta los datos del
  navegador en la web). `core:data` 1314 tests en verde. La próxima migración (E3, `remoto_id`) sería la `10.sqm`: volver a mirar las ramas
  antes de crearla. **T16 hecha (2026-11-06, `d0b51c8`); sigue T17.** `OrigenDeConsulta` (propia / histórico / web; la propia enviada con `remoto_id` NO es de la web),
  `abrirConsulta` de la HC general crea con `propia = 1` y solo reutiliza una propia abierta; `fusionarConsulta` conserva la marca; `core:data` 1342 tests en
  verde. `marcarPropia` sigue sin llamarse (lo usa «Enviar a la web» del histórico). Ojo: una abierta del histórico ya no se retoma desde `abrirConsulta`.
  **T17 hecha (2026-11-06, `4936364`); sigue T18. CAMBIO DE REQUISITOS SOBRE LA 001 (D9):** lo que la web no guarda (tabaquismo, FC, CA del examen, diagnósticos, audio, antecedentes personales) no se
  ofrece ni se envía nunca; lo ya cargado se conserva y se marca «No se envía a la web». `hcgeneral/SeccionesEnviables.kt`: `SUBENTREGA_HABILITADA = E1` (10 de las 25 secciones: 8 textos, examen físico y
  `enfermedades`; el resto «todavía no»); **al cerrar E2, E3 y E4 se sube esa constante**. `ItemAntecedente.sinEnvio` marca el tabaquismo en `ClinicaMedica.kt`. Las pantallas NO se tocaron: T36 a T39
  tienen que usar `sinEnvio` y `seccionSeOfrece`. `core:data` 1357 tests, `especialidades:clinica` 38.
  **T18 hecha (2026-11-06, `493ca64`); sigue T19.** Hallazgo: hasta T18 cada autoguardado (600 ms) marcaba `dirty` y movía `updated_at` aunque no cambiara nada. Ahora, solo en la HC general, `guardarValor`/
  `guardarSeccion`/`guardarExamenFisico`/`guardarAntecedente`/`guardarRegistro` escriben solo con cambio real (`hcgeneral/CambioReal.kt`; `0.00` = vacío en las medidas; vaciar a propósito se escribe vacío);
  pediatría y las demás igual que antes. Datos por paciente: `antecedentesDeConsultasPropias`/`registrosDeConsultasPropias` (solo los de una consulta propia pueden viajar). **Pendiente de cablear:**
  `ConsultaViewModel.guardarRegistro` y `flushEstudios` deben pasar `enConsulta = consultaId` para reasignar filas del histórico. `core:data` 1394 tests.
  **T19 hecha (2026-11-06, `e7da239`); sigue T20.** `network/hc/ClinicaEscrituraApi.kt` + `HcClinicaEscrituraDtos.kt` (`PayloadsClinica`, puro): crear, secciones, examen, cerrar y baja contra el contrato de E1,
  probados con un servidor falso en 127.0.0.1 (nada cableado al backend ni a la UI; ningún pedido real). `HcApiClient.delete(ruta, cuerpo)`: el DELETE con cuerpo anda en JVM; **sin probar en Android, iOS ni web** (prueba manual).
  Ojo para T20: `clave_en_uso` es el `motivo` de un `422 datos_invalidos`, no un `codigo` (está en `TobbException.cuerpo`). `core:data` 1432 tests.
  **T20 hecha (2026-11-06, `19d9a95`); sigue T21.** `hcgeneral/ClasificacionDeEnvio.kt` (puro): causa ajena → «En espera: <motivo con el nombre de la HC>» (401, 403 hc/médico/licencia/cartera/permiso, 404 paciente, 429, 5xx, 503, sin red, respuesta ilegible);
  causa del dato → «No se pudo enviar» (422, 404 consulta, 409, 403 consulta_no_propia); precedencia de RF-19 en `masPrioritaria`. `core/model/hc/EstadoEnvio.kt`. Estados en `paciente_extra` (`hce_estado_<consulta>:<parte>`, `dirty = 0`, sin migración) con
  `repos/EstadosDeEnvio.kt`; sobreviven a reabrir la app. Nada cableado todavía. `core:data` 1460 tests.
  **T21 hecha (2026-11-06, `1bd70cf`); sigue T22.** `hcgeneral/PlanDeEnvio.kt` (puro): `planDeEnvio(EntradaDelPlan)` da pasos ordenados (crear → secciones → examen → … → cierre al final; `siguiente` corta en la primera parte frenada),
  `sinViaje` (se limpia sin figurar enviado: lo que la web no guarda, desactivados, lo ya igual), `enReserva` (lo que la versión todavía no envía: queda sucio y no cuenta) y retenciones por partes rechazadas. Solo `propia`; solo lo tocado y con cambio real.
  **Pendiente para T25:** registrar `LoEnviado` (lo último que la web confirmó, `conLoConfirmado`) y dejar la fila de la consulta `dirty` mientras falte cierre o baja. Ojo: `toSortedMap` y similares son solo JVM; compilar wasm antes de dar por hecho. `core:data` 1503 tests.
  **T22 hecha (2026-11-06, `5668069`); sigue T23.** `HcBackend.envioPropio` (por omisión `false`) y `administraLicencia` (por omisión `true`); `HcClinicaBackend` declara `true`/`false` (su `soloLectura` sigue `true` hasta T23). `HcApiSync` ignora y no cuenta a los de
  `envioPropio` (los de solo lectura se siguen contando, Q16-A) y `AdminRepository.destinosDeLicencia` = `!soloLectura && administraLicencia` (el panel no cambia para clínica, RF-66). Ojo: el contador `HcApiSync.pendientes` de clínica es 0 desde acá hasta que T24 cuente lo suyo.
  `core:data` 1514 tests.
  **T30 hecha (2026-11-07, `85aa616`); sigue T31.** El riesgo del motor viejo quedó cerrado: `SyncAdapters.todos(db)` ya no incluye las ocho tablas clínicas de contenido y `SyncEngine` ya no sube adjuntos (`:server` conserva `completos`); sigue empujando `paciente_extras`, `medico_preferencias` y diagnósticos/interconsultores/vacunas (ver nota de T30). **T29 hecha (2026-11-07, `49bbbce`).** La lectura de la HC general refresca lo propio y no lo duplica (RF-33): empareja por `remoto_id`, así que la lista no crea otra `hcc-c<id>` para una propia ya enviada (si ya había una copia, la retira con baja lógica) y refresca su cabecera salvo si tiene pendiente, es de otro médico o está anulada (una cerrada no se reabre por una lista vieja); `traerConsulta` refresca su contenido salvo lo pendiente, sin que un vacío pise y sin pisar lo que el médico descartó (se compara con `LoEnviado`); los registros ya enviados se emparejan por tipo + `remoto_id`. «Ya no figura en la web» ya se aplica a las propias (lista, `404` al abrir y `404` al enviar; la pantalla pregunta `AusenciasEnLaWeb.consultaPropia(remotoId)`). Pendiente: laboratorios y pendiente no se emparejan por `remoto_id` (sin columna; migración `10.sqm` de E3) y hay una carrera lectura/envío chica sin cerrar (ver nota de T29). **T28 hecha (2026-11-07, `1cbb337`).** `HcRepository` (con la cola por la interfaz `EnvioDeConsultas`, que le pasa `DataModule`) aplica las reglas a las consultas **propias** de la HC general: `cerrarConsulta` deja el cierre pendiente («Falta enviar») y pide `empujarYa` a la cola; `reabrirConsulta` solo si el cierre **no** se envió y **no** viaja (D13), sin tocar la web y borrando el estado del cierre; `anularConsulta`: nunca creada → cero pedidos y limpia, creada → baja `c:<id>-baja` y descarta lo pendiente, **no** si el cierre ya se envió ni mientras viaja; el envío en curso no sigue con el resto de su plan si se anuló. «Cierre enviado» = `CERRADA` + creada + fila sin pendiente + cierre no «Descartado» (`cierreEnviado`, puro). Modelo de la tarjeta sin UI: `EstadoDeLaConsulta` (marca «Falta enviar» / «En espera: …» / «No se pudo enviar: …» / «Enviando el cierre.», `puedeReabrir`, `puedeAnular`) vía `HcClinicaEnvio.estadoDeLaConsulta` y `consultasEnviandoElCierre`. Los datos por paciente pendientes de una anulada siguen **en reserva** (la spec no lo dice: confirmar). **Ojo:** sin tocar la UI, «Cerrar» y «Anular» de la pantalla actual ya mandan a la web (usan `hc.cerrarConsulta/anularConsulta`); y no hay barrera de escritura para una cerrada con el cierre enviado (T36 a T39 + segunda barrera). `core:data` 1772 tests.
  **T27 hecha (2026-11-07, `d7ef1d7`).** Reintentos: una causa ajena al dato deja **toda** la consulta «En espera: <motivo con la HC>» (ya no solo el 401) y programa el próximo intento con `esperaDeReintento` (pasajeras 15/60/300 s y después cada 300 s; de cuenta o de alguien, cada 300 s), en el scope inyectado y con el reloj inyectado: solo con la app abierta, nada en segundo plano. El reintento es la misma espera que el autoguardado (una por consulta; un cambio la adelanta, no la multiplica); un `429` frena a **todas** las consultas hasta cumplir su espera; mismas claves, sin duplicar. Rechazo (`422`/`404 consulta_no_encontrada`/`409`/`403 consulta_no_propia`): «No se pudo enviar», sin reintento solo ni frenar a las demás. `reintentar(id)` (a mano, vuelve a mandar lo rechazado) y `descartar(id, parte?)` («Descartado: no se envió a la web»: deja `dirty = 0` lo que la parte cubría, **conserva el dato**, no toca la web ni `LoEnviado`, y libera el cierre; no descarta esperas ni la creación). Ojo: la cadena de reintentos es en memoria, **no se retoma al reabrir la app** (lo hace el disparador `alAbrirOIniciar` de T31, que debe recorrer lo pendiente/«En espera» del médico). `core:data` 1708 tests.
  **Pregunta abierta (decide el usuario antes de la prueba general; por ahora rige la spec):** el vínculo antes de enviar la primera consulta de un paciente sin ficha (T26) usa `pacientes/resolver`, y la 002 (RF-10) dice que la web **crea la ficha** si no hay ninguna con ese documento; el pedido del usuario fue «nunca crear fichas desde el envío». El envío no crea fichas por su cuenta (no resuelve ni crea para un paciente de baja ni tras un 404, ni tras elegir ficha / ficha de otro paciente, ni con un documento inválido), pero la primera consulta de un paciente sin ficha puede disparar que la web la cree. Si se quiere prohibirlo hay que cambiar la regla de la 002 y el contrato de `resolver`.
  **T26 hecha (2026-11-06, `4705951`).** `crear` resuelve el vínculo con `PacientesHcRepository.vinculo` antes del `POST` (solo la creación lo necesita); si no queda vinculado, la parte `crear` queda «En espera: <motivo>» (`estadoDeVinculo`, causas nuevas `ELEGIR_FICHA`/`DOCUMENTO_INVALIDO`/`FICHA_DE_OTRO_PACIENTE`, sin migración) y no sale nada; un paciente dado de baja (`404`) deja «En espera» sin volver a resolver. La cola recibe `medicoDeLaSesion` (por omisión `null` = no envía) y envía **solo lo del médico de la sesión**, mirado al programar, al empezar y antes de cada paso (enviar con el token de otro crearía la consulta a su nombre); lo de otro ni se envía ni se programa ni se marca. `contarPendientes` cuenta solo lo del médico de la sesión. Un `401` deja lo que faltaba de esa consulta «En espera: sesión vencida». Ojo: resolver por documento puede **crear la ficha en la web** (002 RF-10); el envío no la crea por su cuenta. Test de Koin cambiado (ahora hace falta sesión). `core:data` 1669 tests.
  **T25 hecha (2026-11-06, `fda5791`).** `HcClinicaEnvio.enviar` ya manda: `planDeEnvio` → crear (`c:<id>`) → textos → examen → cierre (`-cierre`) / baja (`-baja`), cortando en el primer paso que no llega (el cierre nunca sale con algo sin confirmar). Cada paso se anota en **una** `db.transaction` (`remoto_id` + `dirty = 0` solo si no cambió en vuelo, comparando `updated_at` + `LoEnviado` + estado); la fila de una cerrada/anulada sigue sucia hasta el cierre/la baja. `LoEnviado` vive en `paciente_extra` (`hce_enviado_<consulta>`, JSON, `dirty = 0`, sin migración). Parcial = solo lo de `guardadas`; lo demás, `NoSePudoEnviar(OTRO)`. Errores con `clasificarErrorDeEnvio` guardados por parte. Sin ficha conocida **no envía ni marca estado** (T26 resuelve el vínculo y la sesión); reintentos/descarte son T27. `consultasCreadas` (SharedFlow) expone `otras_abiertas` (RF-59); no persiste. `DataModule` arma la cola con el `escritura` del backend y el `dispositivo_id`. Test de Koin de T24 reemplazado (ahora la cola envía). `core:data` 1635 tests.
  **T24 hecha (2026-11-06, `fbb4a5a`).** `sync/HcClinicaEnvio.kt` (`open class`): `marcarSucia` (solo marca y programa: espera de 4 s por consulta, cada cambio la reinicia), `empujarYa` (en el acto), `contarPendientes` (RF-31; cuenta pura en `hcgeneral/ContadorDeEnvio.kt`), `Mutex` por consulta; solo entran consultas propias de la HC general (D2). Su envío es el gancho `protected open suspend fun enviar(consultaId)`: **T25 lo llena** (hoy no hace nada). Reloj inyectado (`esperar`); el falso (`RelojFalso`) vive en `jvmTest` porque `core:data` no tiene `kotlinx-coroutines-test`. Registrada en `DataModule`, **sin cablear** a la UI ni a `App.kt`. Ojo: una consulta nueva vacía ya cuenta como «Falta enviar» (la creación es un paso del plan) y sin `LoEnviado` (T25) un texto vaciado a propósito no se cuenta. `core:data` 1576 tests.
  **T23 hecha (2026-11-06, `b189850`).** `HcClinicaBackend`: `soloLectura = false`, recibe `escritura: ClinicaEscrituraApi` (no la usa; el motor es `HcClinicaEnvio`, T24); constructor público con el `HcApiClient` completo. Trampa evitada: `esSoloLectura` ya no dice «viene de la web» → `HcRepository.leeDeLaWeb`/`esConsultaDeLaWeb` (la consulta de la web sigue siempre solo lectura, D1) y `ConsultaViewModel` los usa. `core:data` 1529 tests.
- **PR #1 y servidores (2026-11-03):** el PR #1 llevó el SDD y la guía `docs/trabajo-por-historia-clinica.md` a `main`. El `devWeb` de
  clínica corre en :8080 (el de pediatría se cerró; quedó un cliente de Gradle de pediatría con `-Psalud360.web.puerto=8090` colgado,
  sin cerrar). `origin/main` contiene la licencia del servidor T1 a T7 desde el 2026-11-03 y su deploy salió bien (la prueba manual M1
  a M10 sigue pendiente).
- **Clínica (rama `hc-general`, más de 150 commits locales sin push, ver `git log origin/main..hc-general`; `origin/main` ya no está en el
  publicado de septiembre: contiene el PR #1, SDD y guía, y la licencia del servidor T1 a T7):** la entrega L de la
  spec 002 (lectura) llega hasta E8.1 y E7.4. La lectura local quedó verificada: la app no escribió en clínica (se miró el log de
  Apache). **Pendiente de clínica:** limpieza de los datos de prueba, E9 (producción, con
  aprobación aparte) y la entrega E (escritura), que se especifica en la spec 005. Ojo: esta rama NO debe publicarse tal cual, porque dos
  commits de su historia llevan un nombre real (uno lo puso y otro lo sacó del archivo, pero queda en el historial): hay que
  reescribir o rehacer la historia antes de cualquier push, y solo con pedido expreso.
- **Limpieza pendiente en la base local de clínica (datos de prueba):** lo de la L (consulta de la web de E8.2) quedó saldado en la T2
  y lo de la 003 (enfermedades «Prueba Salud360», lista del médico de prueba, fichas de prueba con sus carteras y marcas) en la T3 de la
  spec 005 (2026-11-04; detalle en `%TEMP%\opencode\e0\t3-resultado.txt`). **NO saldado, por decisión del usuario:** (1) el perfil 2
  (Hepatología) está alterado: se editó el 26/10 y tiene 5 secciones en vez de las 17 de la carga inicial (reconstruible desde la carga
  inicial de perfiles de hclinica); (2) el perfil de prueba 4 se conserva; (3) el médico de prueba sigue asignado al perfil 2 (el original
  era el 1); (4) los pacientes E y F de la prueba siguen en `turnosonlinebb`; (5) el fixture de la tanda S sigue cargado y se limpia en T45.
  Por eso los totales de perfiles (3, 6 y 29) no son los iniciales (2, 4 y 29).
- **Pediatría primero:** el servidor de pediatría lo subió el usuario a mano (8 archivos, verificado por GET sin token). Pendientes:
  licencias (revisar las fechas reales), `APP_DEBUG=false` en pediatría y en hclinica, 10 médicos sin alta, y el cambio de licencia
  (la app debe leer la licencia del servidor al entrar, no la copia local), que va en una rama aparte (`pediatria-licencia-servidor`,
  worktree `..\salud360-pediatria`, sobre `origin/main`).
- **Spec 004 (licencia del servidor) aprobada el 2026-11-02** (`specs/004-licencia-del-servidor/`; P1 a P6 decididas; **P3 = solo se
  bloquea la HC de pediatría del médico sin fila**, por eso hay una T3b y el estado `SinLicencia`). Se implementa T1 a T8 en el worktree
  (rama `pediatria-licencia-servidor`, sin push; publicar a `main` y T9 solo con pedido). **T1 hecha** (`920d206`: reglas puras en
  `core/data/.../repos/LicenciaDelServidor.kt`). **T2 hecha** (`3fc1a24`: `HcBackend.licenciaDelServidor()` con default `SinDato`;
  `HcPediatriaBackend` lee `auth/perfil` y el `403 licencia_vencida` → `Vencida`; `avisoDeLicencia` sobre la misma lectura; 25 tests
  con un `HttpServer` falso; `core:data` 68 tests en verde). **T3 hecha** (`98ef156`: `AuthRepository.decidirLicencia(sesion, tope)`
  `internal`, memoria de 60 s por token, copia sin `dirty`; 27 tests nuevos, `core:data` 95 en verde; sin cablear a `login`/`restaurar`/
  `refrescarPerfil`; sin backend con licencia decide la copia como hoy, no `false`: el plan D6 ya está alineado). **T3b hecha** (`4096bf7`:
  `TobbException.cuerpo: JsonObject? = null` opcional, `HcApiClient.interpretar` lo pasa; el `403 licencia_vencida` con `licencia: null`
  explícito → `SinLicencia`, todo otro `licencia_vencida` → `Vencida`; `HcApiClientErrorTest` 17 + 8 tests nuevos en
  `HcPediatriaLicenciaServidorTest`; `core:data` 120 en verde; un test de T2 se ajustó a `SinLicencia`). Ojo: un 403 con cuerpo no JSON es
  `SinDato` (sin JSON no hay `codigo`), no `Vencida` como decía el texto de T3b: RF-21 y la tabla D3 se aclararon el 2026-11-02 (`doc:`
  `d1a5176`). **T4 hecha** (`3de1538`: `restaurar` y `refrescarPerfilRemoto` pasan por `decidirLicencia(…, 5 s)` antes de publicar `sesion`;
  12 tests nuevos, `core:data` 132 en verde, wasm de `core:data` compila; ningún test existente cambió). **T5 hecha** (`e3817c6`: `login`
  online da el token a los clientes de HC, consulta con `decidirLicencia(…, 10 s)` y bloquea antes de `guardarSesion`; el ingreso sin
  conexión con turnos sigue con la copia, sin consulta; 15 tests nuevos, `core:data` 147 en verde, wasm de `core:data` compila; ningún test
  existente cambió). **T6 hecha** (`0e4e266`: `refrescarPerfil` pasa por `decidirLicencia(…, 10 s)` con guarda por si la sesión se cerró
  mientras esperaba, y `DataModule` pasa `backendsHc = HcBackends.porEspecialidad` (hoy solo pediatría) al `AuthRepository`; 11 tests de
  refrescarPerfil + `DataModuleLicenciaTest` (7, Koin real con servidores falsos); `core:data` 165 en verde; `features:auth` y `composeApp`
  wasm compilan; ningún test existente cambió). Con T6 deja de haber un refresco que contradiga a `login` (el aviso de «no publicar T5 sin T6»).
  **T6b hecha** (`a8b6f93`: `refrescarPerfilRemoto` también comprueba que la sesión siga siendo la misma antes de guardarla; test con
  compuerta). **T7 hecha** (`4a26625`: `HcLicenciasE2ETest` +3 tests contra la copia local de pediatría: `licenciaDelServidor()` vigente/vencida/
  baja/restaurada, el caso reportado y el inverso con `restaurar()`; 5/5 en verde con las tres propiedades `-P`, `core:data` 169 en verde sin
  ellas; la tabla `medico_licencias` y los conteos/checksums de las 55 tablas quedaron idénticos; el servidor escribe `updated_at` en cada
  `PUT` de licencia: se restauró con un `UPDATE` puntual; respaldos en `%TEMP%\opencode\t7`; «sin fila» (`SinLicencia`) quedó sin E2E, solo
  servidor falso y M9). **T8 hecha, parte técnica (2026-11-03, sin commit de código):** `core:data` 169 en verde, `features:auth` y `composeApp`
  wasm (klib, `-Pentorno=dev`) compilan, diff acotado a `core/data` (13 archivos, sin esquema, build ni servidores); `core:crecimiento:jvmTest`
  no corrió (Gradle colgado en su `compileKotlinJvm`, sin relación con el diff). Falta el reviewer (corrida aparte), la prueba manual M1 a M10 y
  aprobación aparte para publicar. **T9 hecha en hc-general el 2026-11-04** (cherry-picks, 3 tests adaptados, `core:data` 1305 en verde; nota en `tasks.md`). **Ojo (probar):** el worktree es esquema v6 y el navegador en `localhost:8080` guarda una v9: servirlo allá
  borra esa base; usar otro puerto (script de Gradle en `%TEMP%\opencode\t8`, comando y M1 a M10 en `tasks.md`). En el árbol publicado nada lee
  `local.properties`: las URLs locales van con `-P`. Médico de prueba con pediatría y licencia: el 1 de turnos. El árbol publicado tenía 43 tests en `core:data`.
  **Checklist reescrita (2026-11-03, solo doc):** el revisor aprobó con observaciones (H1 a H5, M10 y H6 [documentar `licencia`/`licencia_vencida` en
  `API_SALUD360_PEDIATRIA.md`, con la integración]); `tasks.md` tiene M1 a M10 y el cuadro de conflictos de T9. Scripts fuera del repo en `%TEMP%\opencode\t9m`
  (`licencia.ps1` mueve solo la fila 2 de `medico_licencias` con respaldo y `RESTAURAR` verificado, `levantar-8090.ps1`, `verificar-entornos.ps1`,
  `comparar-licencia.ps1` + línea base; el ensayo `-m` del puerto 8090 anda; ciclo de estados probado, las 55 tablas de pediatría quedaron idénticas por `CHECKSUM TABLE`). Con la base local aprobada por el usuario para estas pruebas: al terminar,
  `RESTAURAR`. Ojo: la pantalla de ingreso solo se ve sin sesión guardada, así que la M10 (ingreso sin señal con sesión previa) no se arma desde la interfaz; la cubren tests.

## Traspaso (2026-10-29)
- **Siguiente paso: E8.2 (con la app real, necesita al usuario y el navegador), dentro de la entrega L de la spec 002, aprobada por el
  usuario. E7.4 hecha (2026-10-30, solo infraestructura):** `wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` en 4 min 30 s (exit 0,
  URLs de `Entornos.kt` a `localhost/...-salud360`); el `devWeb` del usuario en :8080 ya sirve el wasm nuevo (43 528 858 bytes, idéntico al
  compilado) con el backend de clínica registrado y las pantallas de E7; no se repitieron `core:data:jvmTest` ni `compileKotlinJvm`. Sin
  migraciones (v9). Con poca RAM (~0,4 GB libres al compilar) alcanzó igual. **E8.1 hecha (2026-10-30):** `HcClinicaLecturaE2ETest` (6 tests, `core/data/src/jvmTest`)
  con el backend real y la copia local de hclinica; se saltea sin las 6 propiedades `-Psalud360.test.hc.clinica[.token|.ficha|.ficha.ajena|
  .consultas.ocultas|.consulta.ajena]` (ids del fixture por `ids.sql`; el comando está en su KDoc). Verde, y el servidor solo vio `GET`
  de `consultas`, `consultas/{n}` y `pacientes/{n}/historia`; sumas y conteos de las 68 tablas iguales; el fixture sigue cargado para E8.2
  y `salud360_token_cache` vacía. Pedidos espaciados 1,1 s por el `throttle:60,1`; un `request timeout` de 30 s de Apache local en una
  corrida (se repitió, verde). 1161 tests de `core:data` en verde. **E6.5 hecha (2026-10-30):** `DataModule` registra `clinica` =
  `HcClinicaBackend(…, HcApiSoloLectura(cliente de CodigosHc.GENERAL), …)` en `HcBackends` (`soloLectura = true`; sin URL de la HC general
  no se registra); `HcRepository.tieneApi("clinica")` ya es verdadero. Test nuevo `DataModuleClinicaTest` (8: Koin real + `HttpServer`
  local; solo `GET` de `consultas`, `consultas/{id}`, `pacientes/{id}/historia`; el motor de envío no manda nada; licencias como antes);
  `HcBackendsSoloLecturaTest` ahora exige `clinica` registrada. 1155 tests de `core:data` en verde. `motivoHc` NO adopta el `429` (queda en
  `motivoDeLaLectura`, decidido). Ojo: `PacienteDetalleViewModel` ya pide lista + historial de clínica al abrir la ficha de un
  paciente vinculado; no pushear `main` con esto antes de que producción tenga las rutas (E9). **E7.3 hecha (2026-10-30):** la consulta de la web
  se abre siempre en solo lectura (`hcgeneral/SoloLecturaDeLaWeb.kt`, puro: `esSoloLecturaWeb`, `edicionDeConsulta`, avisos, `marcaDeSeccion`,
  `mensajeDeLaLecturaDeConsulta`; `ConsultaViewModel` no escribe ni llama a `HcApiSync` si `soloLecturaWeb`; `ConsultaDeLaWeb.kt` y las
  secciones con las marcas «Ya no figura en la web»). **Guardas en `HcRepository`** (decisión del usuario): `cerrar/reabrir/anular/guardar*/
  eliminar*` sobre lo `hcc-…` (o `remoto_id` con backend de solo lectura) devuelven `false` y no tocan la base; ahora esas operaciones
  devuelven `Boolean`. Efectos a revisar: antecedentes de la web solo para leer en consultas locales, «Elegir filas» fuera de la consulta de
  la web; ningún texto nuevo; detalle en la nota de E7.3. 1147 tests de `core:data` en verde. **E7.2 hecha (2026-10-30):** `hcgeneral/VistaDeLaHistoria.kt` (`vistaDeLaHistoria`: grupos de E7.1 +
  50 más recientes y «Ver más» sobre lo ya traído + avisos), `repos/Refrescable.kt` (capacidad opcional: «Actualizar» = `traerConsultas(forzar =
  true)` vía `HcRepository.actualizarConsultasRemotas`; `HcBackend` intacto), `motivoDeLaLectura` (el `429` con texto propio, sin tocar `motivoHc`),
  `HistoriaDeLaWeb.kt` y `HistoriaClinicaScreen` (solo con backend de solo lectura: hoy inalcanzable hasta E6.5) y la nota del perfil 4 en
  `AntecedentesSeccion`; 1109 tests de `core:data` en verde. Textos nuevos a revisar: el del `429` y el rótulo «Nota» (detalle en la nota de
  E7.2). **E7.1 hecha (2026-10-30):** `hcgeneral/ConsultaWeb.kt` (`agruparConsultas` → abiertas en la web / en curso /
  anteriores, con origen, autor y marca), `AusenciasEnLaWeb.kt` (qué ítem lleva «Ya no figura en la web») y `AvisosLectura.kt` (textos de la
  spec/plan como constantes, `extrasDeLectura`, `avisosDeLaHistoria`, `avisosDeConsulta`, `notaPerfil4`); 1078 tests de `core:data` en verde. Dos
  avisos distintos (consultas omitidas y cargas sin fecha: no se suman); el aviso de D1 y los singulares no están en la spec, hay que revisarlos
  (lista en la nota de E7.1). **E6.6 hecha (2026-10-30):** `CargasLaboratorio.kt` (`puedeBorrarCarga`: sin tacho para `hcc-l…`) y
  `PendientesWeb.kt` (`esDeLaWeb`, `puedeResolverse`, `cambioDePendientes`: nunca resuelve `hcc-p…`; el texto del cierre es solo el de los
  locales) usados por `ConsultaViewModel`; 1027 tests de `core:data` en verde. Sin guardas en `HcRepository` (E7.3); E7.3 debe ocultar
  «Resuelto» con `puedeResolverse()` y ajustar el diálogo de cierre. **E6.4b hecha (2026-10-30):** `repos/MarcasDeLaWeb.kt` aplica `marcarAusentes` desde `traerConsultas`
  (lista completa), `aplicarHistoria` y `traerConsulta` (también ante `404 consulta_no_encontrada`, que relanza el error): marcas
  `hcc_ausente_<clave>` en `paciente_extra` (`dirty = 0`, valor = fecha), primero y en la misma transacción que lo que trae la respuesta;
  `traido`/`marcadas` solo del paciente y de filas limpias `hcc-…`; quitar una marca = dar de baja su fila (`deleted = 1`, sin `DELETE`);
  `HcClinicaBackend.marcasAusentes(pacienteId)` las lee para E7. Un `TRIGGER` por tabla prueba que nada se borra ni se reescribe salvo marcas.
  1014 tests de `core:data` en verde, wasm compila; 8 tests de E6.3/E6.4 que decían «sin marcas hasta E6.4b» se ajustaron. Sigue sin
  registrarse el backend (E6.5). **E6.4 hecha (2026-10-30):** `HcClinicaBackend.traerConsulta` (un `GET
  consultas/{id}`; escribe `seccion_valor`, `examen_fisico` y el aviso `hcc_no_leidas_<id web>`; no pide nada si la consulta no es `hcc-c<remoto>`,
  no está, está sucia o no hay ficha) y `traerConsultas` ahora hace lista + historial (`aplicarHistoria`: antecedentes con `nota_perfil4`,
  cargas, estudios, exámenes, interconsultas, motivos de las consultas que están, pendiente; todo `dirty = 0`, lo sucio no se toca, nada se
  borra ni se vacía). Lista e historial son pedidos, transacciones y esperas de 60 s independientes: si falla la lista no se pide el historial;
  si falla el historial la lista queda y el próximo pedido repite solo el historial. Las cargas sin fecha (D1) van en `hcc_omitidas_laboratorio`
  (fila aparte de `hcc_omitidas`). `pendientePorId` en `HistoriaClinica.sq` (sin migración). 979 tests de `core:data` en verde y wasm compila.
  El backend sigue sin registrarse (E6.5, D6). **E6.3 hecha (2026-10-30):** `repos/HcClinicaBackend.kt` (`soloLectura = true`; envíos lanzan
  `UnsupportedOperationException`; sin `resolver`) trae la lista (50 por pedido, hasta 20 páginas, todo en una transacción, de la más
  vieja a la más nueva), lo `dirty`/del dispositivo no se toca, nada se borra; `traerConsultas(…, forzar)` y espera de 60 s por
  (paciente, médico). `hcc_omitidas` = `foto|otro|sin_fecha`
  (`OmitidasLectura.kt`). **E6.2 hecha (2026-10-30):** `hcgeneral/LecturaClinica.kt` (puro): ids `hcc-c<id>`, `hcc-m`, `hcc-l<tipo>-<id>`, `hcc-r<tipo>-<id>`, `hcc-p`,
  `hcc-a<ficha>-<clave>`; conversión de las respuestas al modelo (nunca un vacío que pise, textos intactos) y `fusionar…` (lo que viene
  pisa, lo que no se conserva, `null` si no cambia nada); `filaDeLaWeb` (siempre `dirty = 0`); `desconocidos(catalogo, …)`;
  `marcarAusentes(traido, marcadas, LecturaWeb)` solo devuelve `marcar`/`desmarcar` (claves `hcc_ausente_<clave>`; la lista solo cubre con
  todas las páginas de un «Actualizar»; la nota del perfil 4 nunca se marca). 825 tests de `core:data`, 32 de `clinica` y wasm en verde; el
  test contra `catalogoHcGeneral` vive en `especialidades/clinica`. E6.3 debe escribir las consultas de la más vieja a la más nueva
  (no se guarda `created_at`). Detalle y decisiones en la nota de E6.2.
  **E6.1 hecha (2026-10-30):** `HcApiSoloLectura` (solo `get`/`leer`/`leerOpcional`/`leerTodo`), `ConsultasClinicaApi` + `...Remota` (solo
  `listar`/`consulta`/`historia`, agregado a lo que pedía el plan) y `HcClinicaDtos.kt`; tests con reflexión y servidor falso (solo `GET`, sin
  cuerpo, un pedido por error); 729 tests en verde y wasm compila. Los DTOs exigen los contenedores de lo que E6.4b podría marcar como
  ausente (si faltan, `TobbException` `respuesta`); pueden faltar `created_at`, `antecedentes_familiares_perfil4`, `omitidas` y `no_leidas`.
  E5.1 hecha
  (2026-10-29): `HcBackend.soloLectura` (default `false`) y candado en `HcApiSync` (`marcarSucia`, `empujarYa`, `drenar`,
  `consultasDeAntecedentesPendientes`). **E5.2 hecha (2026-10-29, `0e3c461`, solo un test):** `HcApiSyncBaseMigradaTest` prueba el candado
  con una base v8 con datos de hepatología migrada por `8.sqm` (cero llamadas, base idéntica, `pendientes` igual, pediatría sigue); sigue
  **E5.3 hecha (2026-10-29):** `AdminRepository` ignora los backends de solo lectura para licencias (médico solo de clínica sigue en
  `true`); `HcRepository.esSoloLectura` y `abrirConsulta` con `consultaAbiertaLocal` (no retoma una abierta con `remoto_id` o id `hcc-`;
  `hcgeneral/LecturaClinica.kt` tiene `PREFIJO_ID_WEB` y `Consulta.esDeLaWeb()`, E6.2 lo completa); 671 tests en verde.
  **E5.4 hecha (2026-10-30, solo documentación):** `HcPediatriaE2ETest` y `HcCarteraE2ETest` en verde contra la copia local de pediatría con el
  motor ya con candado; limpiado por id (conteos de las 55 tablas idénticos); `salud360_token_cache` de pediatría NO se vacía (guarda los tokens
  de prueba sembrados); `HcLicenciasE2ETest` no se corrió. La tanda E5 está completa.
  **D6 (2026-10-29, usuario):** E6.5 (registrar el backend en `DataModule`) va DESPUÉS de E7.3 y antes de E7.4 y de la tanda V (orden: E5, E6.1 a
  E6.4b, E6.6, E7.1 a E7.3, E6.5, E7.4, E8): `cerrarConsulta`, `reabrirConsulta`, `anularConsulta` y `guardar*` de `HcRepository` no distinguen
  consultas de la web hasta E7.3. Mientras tanto no se registra nada y los tests usan backends falsos. Hallazgo para la entrega E: `archivosPendientesDeConsulta` recoge los adjuntos por paciente (`consulta_id` NULL) de cualquier HC
  (fijado como límite conocido en ese test). `HcBackendsSoloLecturaTest` obliga a decidir `soloLectura` de cada backend que se
  registre (E6.5 agrega `clinica` = `true`). Ojo: `SyncEngine` no pasa por el candado (otro canal, hacia turnos, 404 hoy) y
  `AdminRepository` ya no manda licencias a un backend de solo lectura (E5.3). La tanda S (servidor `hclinica`, rama `salud360-api`,
  E3 y E4: todas las tareas hechas; `specs/002-hc-clinica/tasks.md`) quedó «implementada y con ajustes; falta
  validación final del reviewer solo si cambió algo mayor».** Paso 0 (2026-10-29, hclinica `64849c9`): `pacienteVisible` y
  `consultaVisible` ahora dentro del `try` del `ConsultaController` (el 500 propio también cubre la comprobación de cartera).
  **E4.4 hecha (2026-10-29, hclinica `0d0d394`, ajustes de la validación y decisiones D1 a D5):** examen físico `"0.00"` = vacío
  (`"0"` y `"0.0"` son datos), `historia.antecedentes_familiares_perfil4` (`{nota}` o `null`; tabla opcional, no existe en la copia
  local), `created_at` completo en la cabecera, `\Throwable` en `ConsultaController`, `ultimaActiva` con `paciente_id`, y el `.md`
  corregido (`limite`/`desde`, qué escribe el middleware, 403/404 de `consultas/{id}`, tamaños). Fixture ampliado (fuera del repo) y
  4 mutaciones que antes sobrevivían, ahora detectadas. **El fixture sigue cargado** (lo usa E8.1); `salud360_token_cache` vacía.
  Avisos para el usuario en la nota de E4.4: RF-50 dice «lo único que se registra es la sesión» y el middleware escribe también la
  licencia vencida y los vínculos pendientes (no se editó la spec); `pacienteVisible`/`consultaVisible` corren fuera del `try`;
  el índice de `medico_pacientes` y el perfil 4 de producción quedan para E9.1; el DTO de la app debe tolerar la ausencia de los
  campos nuevos. **E4.3 hecha (2026-10-29, hclinica `3948d3c`, solo el `.md`):** grep de escrituras con `\b` sin coincidencias injustificadas; con el
  general log de MySQL las 3 rutas solo hacen `SELECT` (más el `INSERT` del middleware en `salud360_token_cache`; turnos actualiza
  `ultimo_uso_en` de la sesión); `CHECKSUM` y conteos de las 68 tablas y `suma.sql` iguales; 0 métodos no `GET` en Apache (los 4 `405` de
  E4.2 descontados); `tests/Unit` 435 en verde; general log restaurado. **El fixture sigue cargado** (lo usa E8.1; limpiar con `limpiar.cmd`).
  Los avisos abiertos para el usuario están en la nota de E4.3 (fecha `2000-01-01`, sin `baja` en `omitidas`, sin `edad_meses`,
  `antecedentes_familiares_perfil4` sin mapear, ids de estudios repetidos, `APP_DEBUG=true` en el 405, 403 de admin sin probar).
  Las dos copias de la API de clínica y pediatría comparten el nombre de ruta `consultas/...`: el log de Apache mezcla las dos.
  **E4.2 hecha (2026-10-29, hclinica `2d60bf9`):** `ConsultaController` + 3 rutas `GET` (`consultas`, `consultas/{id}`,
  `pacientes/{id}/historia`). **E4.1 hecha (2026-10-29, hclinica `3905b0d`):**
  `ConsultasService::listar` (2 queries; `$medicoId` como 4.º parámetro para `propia`) e `historia` (13 queries, una por tabla), con las
  reglas puras nuevas en `ConsultasReglas` (`lista`, `cabecera`, `cargas`, `antecedentesFamiliares`…); contrastado con SQL directo en el
  fixture y en una base descartable ya borrada. Hallazgos: la web guarda `2000-01-01` como fecha vacía de laboratorio y peso (`fechaDeCarga`;
  esas cargas no se mandan y se cuentan en `omitidas.laboratorio_sin_fecha`); `edad_meses` no se manda (en clínica es 0 siempre); el grep
  de escrituras usa `\b(insert|update|delete)\b` porque la cabecera lleva la columna `updated_at`; los ids de estudios se
  repiten entre tablas (la app arma el id local con el tipo). Las decisiones chicas están en la nota de E4.1 para que el usuario las revise.
  **E3.3 hecha (2026-10-29, hclinica `92e5977`):** `ConsultasService::leer` (solo `SELECT`: secciones por consulta, examen físico y
  `no_leidas`); devuelve `null` para lo que la lista no ofrece (el controlador lo hará `404`); `secciones` y `examen_fisico` salen como
  objeto aunque estén vacíos; las tablas ausentes se detectan en `information_schema` (sin `Schema::`); `diagnosticos` no se informa
  nunca. **E3.2 hecha (2026-10-29, hclinica `a5ec2cc`):** `MapaClinica` y `ConsultasReglas`
  (puras) y el mapa contrastado con la estructura real de `hc_clinica`. **E3.1 hecha (2026-10-28):** el fixture de la L (pacientes 99000100 y 99000101, un
  médico extra de `users`, consultas a-h) está CARGADO en `hc_clinica` local, aparte de los datos de la 003; scripts en
  `%TEMP%\opencode\lectura\` (`cargar.cmd`, `limpiar.cmd`, `ids.sql`, `contar.sql`, `suma.sql`, `esquema.txt`) y conteos/sumas
  de "antes" en `%TEMP%\opencode\e31\`. Cargar y limpiar siempre por stdin (con `mysql -e source` un error no corta). Los
  ids cambian en cada carga. Hallazgos: no hay tabla de diagnósticos y `grabaciones_audio` no existe en la base local
  (E3.3 debe tolerarlo); la base local no tiene `consultas.activo = 0`, tipo 2 ni `es_foto = 1`. El usuario aprobó el 2026-10-28 la spec, el plan y las tareas de la L y, con
  "arranquemos con la tanda S", la tanda S entera (E3 y E4, sin preguntar tarea por tarea). **E9 (producción) requiere
  aprobación aparte**; la entrega E (escritura) es borrador sin aprobar y sin tareas. Q1 a Q14 y Q16 a Q18 como
  recomendó el planificador; **Q15 = nunca borrar**: la lectura no elimina ni vacía nada de lo traído de la web, lo
  marca "Ya no figura en la web" (RF-63, tarea E6.4b). Las decisiones están en `spec.md` ("Decididas"). Antes de
  registrar el `HcBackend` de solo lectura de clínica (E6.5) tienen que estar los candados de E5 y la pantalla E7.3 (D6); y no pushear `main`
  con ese backend antes de que producción tenga las rutas.
- **Lo que el planificador no pudo verificar** (no ejecutó nada contra bases ni servidores): el esquema real de
  producción (columnas, `activo`, modo estricto), si hay un respaldo restaurable de producción, qué versión de hclinica
  corre allá (la ruta `pacientes` contesta `401` aunque esta memoria diga que los repos hermanos no se subieron), qué
  significa `consultas.activo = 0`, los nombres de las tablas de audio y fotos, el tamaño real del historial de un
  paciente, cómo muestra la web las filas `activo = 2` y el `429` en el cliente wasm. E3.1 y E4.1 miran lo que se puede
  en la copia local; el resto es de E9.1.
- **Spec 003 (enfermedades): cerrada el 2026-10-28 por decisión del usuario, con excepciones.** F1 a F3 completas y
  validadas por el reviewer. F3.7 (prueba manual) está en `[x]` pero quedó sin verificar en buena parte: secretaria (M1),
  crear enfermedades (M3: el log de Apache no tiene ningún `POST enfermedades`), M4 puntos 1, 3 y 4, M5, M6, M7 salvo la
  consulta cerrada y «Datos», y M8 (cierre de sesión con conteo de tokens). Confirmado por el usuario: M2 puntos 1 y 2 y
  M3 agregar, quitar y marcar. Detalle en la nota de F3.7.
- **Limpieza de datos de prueba de F3.7: hecha en la T3 de la spec 005 (2026-11-04), con excepciones.** Enfermedades, lista del
  médico de prueba, fichas, carteras y marcas están en los totales iniciales (los 9 `PUT` apuntaban solo a fichas de prueba). Quedan
  sin saldar el perfil 2 alterado, el perfil de prueba conservado, la asignación del médico al perfil 2 y los pacientes E y F en turnos
  (ver «Limpieza pendiente» arriba). Archivos de apoyo fuera del repo en `%TEMP%\opencode\f37` y `e0`. La tanda S usa la misma base:
  no mezclar su fixture con estos datos.
- **Spec 001 (perfiles configurables): completa.** T52 (prueba manual de la tanda D) cerrada con excepciones el
  2026-10-28: confirmado que no hay error de migración, que hay una sola HC «Clínica» con el perfil Hepatología y que una
  consulta creada con un perfil se ve al cambiar de perfil; sin comparar antes/después (la base ya estaba migrada al
  respaldarla; el respaldo quedó en `%TEMP%\opencode\respaldos`) ni los pasos 5 a 10. Falta la prueba manual del usuario
  de las tandas C y C2 a C7.
- **Tanda D de la 001 (hepatología deja de ser una HC aparte):** `normalizarHistoriasClinicas` lee `hepatologia` de turnos
  como `clinica` (en `MedicoRow.toModel`, `TurnosImportador` y `AgendaTurnosOnline`); `8.sqm` (esquema local **v9**) solo
  mueve datos de `hepatologia` a `clinica` (los choques se resuelven sin pérdida: antecedente repetido = gana la más
  reciente y la otra `activo = 0`; consultas abiertas en las dos = quedan las dos; secciones ocultas = unión). **No hay
  vuelta atrás en un dispositivo migrado:** una app vieja (v8) sobre una base v9 destruye los datos del navegador en la
  web. `hepatologiaContribution` se borró; `internal val hepatologia` queda porque `catalogoHcGeneral` copia de ahí.
  `HcPerfilesE2ETest` sigue sin escribirse (D7, fuera).
- **Spec 002 parte 1 (pacientes y cartera): implementada y validada;** falta la prueba manual del usuario (RF-10, RF-14 y la
  lista de E2.7).
- Pendientes conocidos (sin tarea):
  - Límite conocido de `8.sqm`: un `paciente_extra` de hepatología frente a una fila `deleted = 1` de `clinica` queda en
    hepatología (no se pierde, pero la HC general no lo ve).
  - Test intermitente de F2.12 (enfermedades) nunca explicado; la causa más probable son los frenones de esta máquina.
  - La web de turnos sigue mostrando la casilla Hepatología (inofensivo: la app la lee como `clinica`).
  - `composeApp` no tiene tests: lo que vive ahí se verifica compilando y a mano.
  - Subida a producción pendiente: pediatría y turnos con sus rutas nuevas (ver "Pendiente"). Los repos hermanos siguen sin push.
  - Para el envío de consultas (entrega E): filtrar los antecedentes `activo = 0` que deja `8.sqm` y decidir qué hacer con las
    consultas ex-hepatología sin enviar (saldrían en bloque). En la L lo cubren los candados de E5.
  - `SyncEngine` no está apagado: corre tras cada login y pega a `<turnos>/api/salud360/sync/push|pull`, que no existen (404 ignorado).
    Si alguien crea esas rutas en turnos saldrían todas las filas `dirty`.
- **ANTES DE PUSHEAR esta rama hay que limpiar de la historia un nombre de persona real** que entró en los commits `a26d676` y
  `dc94dc4` (`MEMORY.md`), por ejemplo con rebase interactivo (`edit`) sobre `a26d676` y los commits siguientes, o publicando con
  squash-merge. Hay más de 100 commits locales sin push y ninguno está en `origin`. Decisión del usuario (2026-10-27): no
  reescribir historia ahora, resolverlo justo antes del push. Si se reescribe, los hashes de esta memoria y de los `tasks.md` cambian.
- Cómo seguir: leer `AGENTS.md`, esta memoria y el `tasks.md` de la spec activa; una tarea por vez con el
  implementador, tests primero, validación del revisor al cerrar cada tanda; commits locales como rodri89, sin push.
- Repos hermanos (todo local, sin push): hclinica rama `salud360-api` (último `5ebb410`, 005 T14), hc_pediatria rama
  `salud360-api` (`72196ce`), turnosonlinebb rama `salud360-hc-general` (`0077ed4`).
- Entorno: `.\gradlew devWeb` a secas apunta al XAMPP (`local.properties`). Apache y MySQL del XAMPP pueden estar vivos de
  sesiones anteriores (login de turnos con credenciales malas da 422 como se espera).
  Tokens de prueba de pediatría: archivo local fuera del repo (`%TEMP%\opencode\respaldos\tokens-prueba-pediatria-local.txt`).
- Las contraseñas de prueba locales no están en ningún archivo: la sesión nueva se las pide al usuario.
- Menores: ofrecer actualizar el plugin `@xtruder/opencode-claude-max-plugin` (hay versión nueva); revisar si
  `DibujoCanvas.kt` lee valores con `ctx.valor` (el bug de Child-Pugh); `ColumnasEnfermedadesTest` repite
  `SiNoVacioTest` (aceptado, decisión E9 de la 003).

## Estado actual
- App desplegada en web (push a `main` → GitHub Actions → Hostinger). SDD adoptado (`docs/constitution.md`, `specs/`).
- HC de pediatría lee y escribe su base real (detalle y pendientes en `docs/hc-pediatria-estado-y-plan.md`).
  Licencias y alta de usuarios desde el admin, leyendo de su dueño. El médico que solo usa HC ve su cartera.
- Rama `hc-general` de este repo (sin push). Spec 001, tandas A1/A2 (servidor hclinica + turnos), B (modelo y repos),
  C a C7 (admin de perfiles, HC del médico, estudios con historial, filas de laboratorio, varias cargas, serologías
  SI/NO, Child-Pugh, motivo con consultas previas): todas hechas, ver `specs/001-hc-configurable/tasks.md`.
  La base local de hclinica tiene la carga inicial de perfiles aplicada (1 Clínica general, 2 Hepatología): no vaciarla.
- Spec 002 parte 1: E1 (servidor, hclinica y hc_pediatria) y E2 (app: `PacientesHcApi`, `VinculoPacientes`,
  `MotivoHc`, `PacientesHcRepository`, `HcPediatriaBackend` delegando, lista con motivos por HC, E2E
  `HcClinicaPacientesE2ETest` con `-Psalud360.test.hc.dni` explícito); ver `specs/002-hc-clinica/tasks.md`.
- Spec 003 (cerrada con excepciones, ver el traspaso): servidor en hclinica (`EnfermedadesReglas`, `EnfermedadesService`,
  `EnfermedadController`, 5 rutas, `auth/salir` en hclinica y pediatría); en la app `hcgeneral/*Enfermedad*`,
  `network/hc/EnfermedadesApi`, `7.sqm` con `hc_enfermedades` (hoy la base es v10 por `8.sqm` y `9.sqm`), `repos/EnfermedadesRepository`
  y `repos/EnfermedadesControlador` (dos `StateFlow`: sección y administrar), que arma el ViewModel con
  `EnfermedadesRepository` y `PacientesHcRepository` (de Koin). Decisiones C1-C5 y E1-E12 en `tasks.md`.
- Spec 002 entrega L: servidor (tanda S, E3 y E4) implementado en hclinica y documentado en `md/API_SALUD360_CLINICA.md`, con los ajustes de E4.4;
  falta la validación final del reviewer solo si cambió algo mayor. La app (tanda A): E5, E6.1 a E6.6 y E7.1 a E7.3 hechas; `HcClinicaBackend` es de solo lectura y está registrado en `DataModule` (E6.5); E7.4 hecha (el `devWeb` sirve el build nuevo). E8 cerrada (E8.1 y E8.2 hechas, E8.3 cerrada con excepciones, E8.4 aprobada con observaciones); falta E9, con aprobación aparte.
- hclinica: MySQL compara nombres sin tildes (`utf8mb4_unicode_ci`) y `validar` no; el `unique` lo frena y la API
  contesta 422 igual. En esa MySQL `ß` = `ss` y `ａ` = `a` (sirven para probar el choque). La app (`claveDeNombre`)
  ignora tildes y ñ = n para avisar antes.
- (No confundir con la deuda de limpieza de F3.7, arriba.) `HcClinicaEnfermedadesE2ETest` deja en `hc_clinica` dos enfermedades "Prueba Salud360 <n>", sus filas en
  `medico_enfermedades`/`paciente_enfermedades`, la ficha del DNI de prueba (desde 99000090) y su `medico_pacientes`:
  limpiar con el SQL del KDoc del test (`DELETE` puntuales en ese orden; verificar antes que el DNI esté libre) y vaciar
  `salud360_token_cache`. Una corrida con datos viejos sin limpiar falla a propósito.
- Los tokens de prueba de pediatría se sacaron del repo (`1c7ad61`, placeholders) pero siguen en el historial (ya en
  `origin/main`). Rotados el 2026-10-20 (solo local).
- El E2E de pediatría deja en `hc_pediatrica` una consulta de baja (y sus filas) y dos imágenes en
  `public/img/salud360` por corrida: borrarlas por id. Orden de las FK al borrar: fotos → padres, `internaciones`
  antes que `antecedentes_personales`, `consultas` al final. Ya hay 5 consultas de baja de corridas viejas. Limpieza de E5.4: conteos de `COUNT(*)` de las 55 tablas antes y después (`%TEMP%\opencode\e54`); no vaciar
  `salud360_token_cache` de pediatría (tiene los tokens sembrados).
- Tras el logout, hclinica aceptaba el token por `salud360_token_cache`: vaciarla en las pruebas (desde F1.7
  `POST auth/salir` también la vacía). Su throttle es por IP y cuenta todo el grupo `api` (~1,1 s entre pedidos).

## Entorno local (Windows + XAMPP)
- Clones de trabajo: `rebonline\hclinica` (no `C:\Users\flor\hclinica`, que tiene cambios sin commitear del
  transcriptor) y turnos. Copias servidas: `HClinica-salud360` y `TurnosOnlineBB-salud360` (sin git; los commits se
  copian a mano; la de turnos ya no es igual a `main`). Comando de `devWeb` contra ellas en `AGENTS.md`.
- `devWeb` no recompila solo (no es `-t`): para refrescarlo, `:composeApp:wasmJsDevelopmentExecutableCompileSync
  -Pentorno=dev` con las mismas `-P` y webpack lo toma.
- Bases `hc_clinica`, `turnosonlinebb` y `tobb` con datos reales: solo locales, nunca al repo.
- Usuarios de prueba en `turnosonlinebb` local: un médico (vinculado en `hc_clinica`, con HC `clinica`) y un admin;
  las credenciales no van en el repo.
- La licencia del médico de prueba en `hc_clinica` está extendida a propósito: no restaurarla. Respaldo en
  `C:\Users\flor\AppData\Local\Temp\opencode\respaldos\licencia6-antes-prueba-tandaC.sql`.
- Las dos copias servidas comparten el entorno del proceso de Apache (una leía el `.env` de la otra): por eso tienen
  `config:cache`. Después de tocar `.env` o `config/`: `php artisan config:cache` (no `config:clear`).
- El `.env` de la copia de hclinica tiene `SALUD360_TOBB_URL` a turnos local. Los `-P` de Gradle sí llegan a HC_URLS.
- Nunca restaurar tablas con `DROP TABLE`.
- `auth/login` de turnos devuelve el token en `access_token` (no `token`); `POST auth/logout` existe.

## Pendiente (fuera de este repo, en los Laravel de turnos y pediatría)
- Subir a producción: aviso de licencia (pediatría), alta de usuarios (turnos), cartera y agenda (ambos).
- `APP_DEBUG=false` en producción de pediatría; límite de conexiones del hosting.
- Probar la app sin señal de punta a punta (nunca se probó).

## Decisiones (y por qué)
- turnosonlinebb es el único login: las HC aceptan su token y no emiten el propio.
- Sin servidor propio en uso: `:server` existe pero la app habla directo con las APIs Laravel.
- Spec 001: hclinica es la "HC general" y se habilita en turnos como una sola HC. Hepatología NO es otra HC: es un
  perfil. Perfil = pestañas + secciones del catálogo con nombre propio, armado desde el admin de la app y guardado
  en hclinica; un perfil por médico; sección quitada con datos = solo lectura. La web sigue con sus perfiles; la app
  muestra lo que tiene la web, ni más ni menos (para la app manda la config de la app). Médico sin agenda igual que
  pediatría; secretarias, audio y HC digitalizada afuera; PDFs adentro. Perfiles 3 y 4 los arma el usuario.
- 001 y 002 se entregan juntas (rama `hc-general`; push a main publica).
- Endpoint nuevo en turnos para editar HC habilitadas (`PUT medicos/{id}/historias-clinicas`, solo admin): mismo
  servicio que `admin_hc_medico` (desactiva, no borra); el HC del médico queda sin `dirty`.

## Aprendizajes y errores a evitar
- Gradle necesita `JAVA_HOME` (JBR de Android Studio) y se queda sin memoria si el navegador está abierto. Si se cuelga en un
  `compileKotlinJvm` sin CPU (daemon de Kotlin viejo), cortar solo el cliente y el daemon propios (no el del `devWeb`) y reintentar con
  `-Pkotlin.compiler.execution.strategy=in-process`.
- Las URLs del entorno se pisan por máquina desde `local.properties` (no versionado), con las mismas claves que
  `gradle.properties`: precedencia `-P` > `local.properties` > `gradle.properties`. En esta compu apunta al XAMPP
  (turnos y HC clínica); en la Mac sigue mandando el MAMP `:8888` de `gradle.properties`. `release` no se altera.
- La app guarda para siempre a qué ficha de pediatría corresponde cada paciente (`hc_paciente_id`); no lo revalida.
- En la web el perfil 1 NO tiene motivo de consulta; telemedicina es igual para todos; perfiles 5 y 9 = 1.
- En PowerShell no escribir markdown con here-strings: los backticks se toman como escapes. Usar el editor.
- hclinica: Laravel 5.8 / PHP 7.3; la HC cambia según `users.perfil` (2 = hepatología). Producción:
  `https://clinica.hclinicadigital.com`. Su API va con `throttle:60,1` por IP. Commits como rodri89.
- PowerShell no distingue mayúsculas en variables (`$t` pisa `$T`): un logout falló así; tokens de turnos en
  `salud360_tokens` (revocar = borrar la fila). Quedan filas viejas de pruebas anteriores sin revocar.
- Migración que agrega una columna: los tests que simulan una base vieja (`PRAGMA user_version = N` sobre una base creada con el esquema
  actual) tienen que quitar antes esa columna con `ALTER TABLE … DROP COLUMN` (el driver reaplica las migraciones y `ADD COLUMN` choca con
  la existente), y las aserciones de `Schema.version`/`user_version` suben. El `CREATE TABLE` del `.sq` no lleva comentarios pegados a las
  últimas columnas: SQLite no reescribe bien la tabla en `DROP COLUMN`. Hoy son cuatro: `ConfigHcRepositoryTest`, `CopiaEnfermedadesTest`,
  `MigracionHepatologiaTest`, `HcApiSyncBaseMigradaTest`.
- SQLDelight con `generateAsync`: los `INSERT`/`DELETE` generados son `suspend` (los helpers que escriben también). Un
  test que simula una base de versión anterior tiene que borrar todas las tablas que crean las migraciones siguientes
  (si no, `CREATE TABLE` falla con "already exists").
- Un pedido que sale antes del `logout` y llega después no debe tocar la copia local: `AuthRepository.logout` llama a
  `alCerrarSesion` (= `EnfermedadesRepository.cerrarSesion`), que borra `hc_enfermedades` y sube la generación dentro del
  mismo `Mutex` (primero borra, después sube) (E12); `logout` ya no borra esa tabla por su cuenta: un `AuthRepository`
  armado sin ese parámetro no la borra. Algo parecido habría que hacer si otra copia local se llena desde pedidos en
  vuelo.
- `EnfermedadesControlador`: administrar y los cambios de la lista salen sin hacer nada si no se puede administrar
  (RF-37); solo `abrirAdministrar` mira `editable` (RF-58 deja crear con la consulta ya cerrada). Un test que abra
  administrar tiene que esperar antes a que la sección cargue.
- `ofrecerCrear` devuelve `null` con el nombre vacío: el error "El nombre está vacío" (solo espacios) lo arma `panelDeAdministrar`.
  Con la ventana de administrar abierta, un toque anterior sin respuesta puede pasar la sección a solo lectura y los cambios de
  la lista salen sin hacer nada (F3.5-g): resuelto cerrando la ventana sola. Un cambio de la lista que falla sin conexión NO
  cambia el estado de la sección (solo el aviso de la ventana), así que ahí la ventana no se cierra.
- `AuthRepository` se arma con `revocar` vacío por defecto: el `TurnosOnlineClient()` por defecto apunta a producción, así
  que un test nunca debe armarlo con revocadores reales (los de verdad van en `DataModule`). La revocación recibe el token por
  parámetro (los clientes ya lo olvidaron) y `logout(token)` es para `login`, donde el token nuevo no está en ningún lado.
  Tests de red sin dependencias: `com.sun.net.httpserver.HttpServer` en `127.0.0.1` (`RevocarSesionClientesTest`).
- Tests que prueban "no contesta a tiempo": retener la respuesta con una compuerta que el test abre (`CompletableDeferred`),
  no con `delay` contra una `espera` corta.
- Tests de enfermedades (F2.12): `espera` del repositorio y tope de `hasta` de 30 s; la `espera` corta (100 ms) solo en el
  test que prueba "a la espera es sin conexión", y ahí la copia de partida va con `pedirSeccion(...).await()` y la apertura
  con `abiertaConConexion()`. Esta máquina tiene frenones de varios segundos (tests de base 5-14 s en vez de ~1 s) sin
  tocarla: no poner topes de reloj de 5 s. Un cambio de la lista actualiza la ventana y un paso después la sección: un test
  que espere una también espera la otra (`marcado.guardado`). Las intermitencias (1) y (3) nunca se reprodujeron: la causa
  más probable es ese frenón, no demostrada.
- RF-4: la secretaria abre las HC del médico que eligió y `ConsultaScreen` recibe el `medicoId` de ese médico; el de
  Enfermedades sale de la sesión (`medicoParaEnfermedades`), nunca de la pantalla. `EnfermedadesViewModel.editable` no es
  observable: la pantalla calcula `puedeAdministrar` en la misma composición que el estado del controlador.
- Renderers CUSTOM de la HC: leer con `ctx.vm.valores.collectAsState()`, no con `ctx.valor` (no se observa y Compose
  saltea la sección: el bug de Child-Pugh que no se pintaba).
- Cargas de laboratorio: `ORDER BY fecha DESC, updated_at DESC`; sin el desempate, a igual fecha SQLite devuelve el
  orden del índice/rowid.
- El PHP del XAMPP es de 32 bits (`PHP_INT_SIZE` 4): `(int)` de números grandes satura en 2147483647.
- phpunit en la copia servida: si un test falla, el printer se cae (`phpDocumentor` del `vendor` usa sintaxis PHP
  7.4 y XAMPP tiene 7.3); se ve `E`/`F` pero no el mensaje. Para verlo, llamar el código con `php -r`.
- `Set-Content -Encoding utf8` de PowerShell 5 agrega BOM: en un `.php` da "Namespace declaration statement has to be the very first
  statement". Para editar o mutar PHP, usar el editor o `[IO.File]::WriteAllText(..., UTF8Encoding($false))`.
- PowerShell 5: `comando > archivo` escribe UTF-16 (los `diff` y `git diff` ven basura); para volcar salidas usar `cmd /c "... > archivo"`.
  Con phpunit en el XAMPP un test que falla rompe el printer: correr la clase con un `TestSuite`/`TestResult` propio (ver
  `%TEMP%\opencode\lectura\corre.php`) para ver los mensajes.
- OpenCode no habla con Anthropic por API key: el plugin `@xtruder/opencode-claude-max-plugin` (provider
  `anthropic-sdk`) usa el OAuth de Claude Code de `~/.claude/.credentials.json`. Si ese archivo se vacía (la sesión
  OAuth venció, ~8 h de vida del refresh), el error es "Could not resolve authentication method" y no es de red:
  se arregla con `claude auth login` (abre el navegador y la página muestra un código que hay que pegar).
  Diagnóstico rápido: `claude auth status` → `loggedIn: false`.
## Propuestas para AGENTS.md
(Se proponen, no se imponen: el usuario las pasa a `AGENTS.md` si está de acuerdo.)

- **Riesgo de `SyncEngine` cerrado (spec 005, T30, `85aa616`).** Reemplazar en «Comandos» la frase del riesgo («`SyncEngine` igual se inicia tras cada login... Si alguien crea esas rutas en turnos, saldrían de golpe todas las filas `dirty` del dispositivo») por: «`SyncEngine` igual se inicia tras cada login y pega cada minuto a `<turnos>/api/salud360/sync/push|pull` (404, se ignora). Desde la spec 005 ya **no** maneja las tablas clínicas de contenido (`TABLAS_CLINICAS_DE_CONTENIDO`: consultas, secciones, examen, antecedentes, registros, laboratorios, archivos, pendientes) ni sube adjuntos; sigue empujando las demás (p. ej. `paciente_extra`, `medico_preferencia`, diagnósticos, vacunas): si crean esas rutas en turnos, saldrían esas filas `dirty`. `:server` usa `SyncAdapters.completos`.» Además, que una tabla clínica nueva no se agregue a `SyncAdapters` sin pensar su envío propio.
