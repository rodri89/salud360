# Plan 004 — La licencia la decide el servidor

Estado: **aprobado por el usuario el 2026-11-02** junto con la spec (P1 a P6 decididas; **P3 = solo la HC de pediatría**,
que agrega la tarea T3b). Alcance: T1 a T8 en el worktree, sin push ni publicación. Spec: `spec.md`.

Todas las rutas y líneas son del **worktree `C:\Users\flor\rebonline\salud360-pediatria`** (rama
`pediatria-licencia-servidor`, creada desde `origin/main` = `d3b6e7d`), que es el código de la app publicada y
**no** el de `hc-general` (allí los números de línea son otros). Las del servidor son del clon
`C:\Users\flor\rebonline\hc_pediatria` (rama `salud360-api`, ya en producción desde el 2026-11-02), solo lectura.

## Cómo está hoy (lo que hay que cambiar)
- `core/data/src/commonMain/kotlin/com/salud360/core/data/repos/AuthRepository.kt`
  - `resolverPerfil` (`:185-206`) arma `PerfilMedico` con la fila `licencia` de la base local (`:190`, `:195`):
    sin fila → `licenciaActiva = true` y sin fecha, o sea no bloquea.
  - `PerfilMedico.licenciaVencida()` (`:213-217`) y `Sesion.licenciaBloqueada()` (`:220`): inactiva o `vence < hoy()` con
    el reloj del dispositivo.
  - Se llama a `licenciaBloqueada()` en cuatro lugares, todos con la copia local: `restaurar` (`:84`),
    `refrescarPerfilRemoto` (`:95`), `login` camino online (`:109`) y camino sin conexión (`:122`), y `refrescarPerfil` (`:157`).
  - `login` pone el bloqueo **antes** de `guardarSesion` (`:107-110`) porque esa función publica la sesión (`:165`) y la
    app navega apenas deja de ser null (`App.kt:55-58`). En ese punto los clientes de HC todavía **no tienen el token
    nuevo** (lo asigna `guardarSesion`, `:164`).
  - `restaurar` (`:73-89`) publica la sesión en `:85` y recién después llama a `refrescarPerfilRemoto`. Mientras dura,
    la pantalla muestra «Abriendo Salud 360...» (`App.kt:47-56`, `restaurando`); pero `LaunchedEffect(sesion?.token)`
    (`App.kt:51-53`) arranca `sync.iniciarPeriodico` apenas la sesión deja de ser null.
- `HcPediatriaBackend.avisoDeLicencia` (`:511-520`) ya hace `GET auth/perfil` y lee `licencia` con `HcPerfilRemoto`
  (`HcDtos.kt:172-188`: `vence`, `aviso_desde`, `por_vencer`), pero descarta todo error (`runCatching`, `:512-516`) y
  devuelve solo el aviso. `HcBackend.avisoDeLicencia` (`HcBackend.kt:77-81`) tiene default `null`.
- `HcApiClient.pedir` (`:87-103`) convierte la red en `TobbException(0, "red", …)`; `interpretar` (`:105-116`) convierte
  cualquier rechazo en `TobbException(status, codigo, mensaje)` y **descarta el resto del cuerpo** (la clase está en
  `ApiClient.kt:40`). El 403 `licencia_vencida` trae `licencia {vence, vencida}` (o `licencia: null`) pero eso no
  llega a la app. Tiempos del cliente: 30 s de pedido y 10 s de conexión (`HcApiClient.kt:68`).
- `MainShell.kt:191-197` pregunta el aviso por vencer una vez al entrar (`HcRepository.avisosDeLicencia`, `:66-67`).
  `LoginViewModel.kt:72` lanza `sync.sincronizar(); auth.refrescarPerfil()` tras un login online.
- La copia local se escribe en `AdminRepository.sincronizarLicencias` (`:66-71`: `upsertLicencia(it.toRow(ahora,
  dirty = false))`), en `guardarLicencia` (`:80-87`: primero con `dirty = true`, después limpia si el servidor aceptó) y en
  `SyncAdapters.kt:81-82` (tira de `sync/pull`, hoy 404). `TurnosImportador` no la toca (`:28`).
  Tabla `licencia` en `core/database/.../Auth.sq:94-109`; mappers en `Mappers.kt:125-127`.
- Servidor (referencia, no se cambia): `TobbAuthService.php` — `conLicencia` (`:178-204`) corre tanto al validar un
  token nuevo (`:161`) como con la sesión en caché (`:111`, 10 min); en la gracia por turnos caído (`:216-229`) **no**
  se evalúa y `licencia` queda en `null`. `Salud360Api.php:53-55` traduce a `403 licencia_vencida`. `AuthController::perfil`
  (`:30-58`) devuelve `licencia` (`:50`): `{vence, aviso_desde, por_vencer}` o `null` (administrador, o sin evaluar).

## Decisiones de diseño (con su alternativa descartada)
- **D1. La consulta vive en `HcBackend`, no en `AuthRepository`.** Se agrega
  `suspend fun licenciaDelServidor(): EstadoLicenciaRemoto = EstadoLicenciaRemoto.SinDato` con default que no
  molesta a las HC sin licencia (clínica, y cualquier backend de solo lectura). `AuthRepository` recibe los backends
  y pregunta solo a los de las HC habilitadas del médico. *Descartado:* que `AuthRepository` use `HcApiClient` directo
  (ya tiene los clientes, `:61`) — duplicaría el parseo y la lectura de `auth/perfil` que ya hace el backend, y no
  habría dónde probarlo con un backend falso.
- **D2. `avisoDeLicencia` se queda y se apoya en la misma lectura.** Una función privada `leerPerfilDeLicencia()` en
  `HcPediatriaBackend` hace el `GET auth/perfil` y la traduce; `licenciaDelServidor` y `avisoDeLicencia` la usan. El
  comportamiento de `avisoDeLicencia` no cambia (null ante cualquier fallo; `HcLicenciasE2ETest` lo cubre).
  *Descartado:* reemplazar `avisoDeLicencia`: toca `MainShell`/`HcRepository` (sin tests) por nada.
- **D3. El resultado es un tipo cerrado, no un booleano.** `EstadoLicenciaRemoto`: `Vigente(vence, avisoDesde,
  porVencer)`, `Vencida`, `SinLicencia`, `SinDato`. (`SinLicencia` nace por P3: el servidor dijo que el médico no tiene
  ninguna fila; se declara junto con el tipo, en T1, para que las reglas puras y `decidirLicencia` ya lo contemplen.) La
  traducción, completa desde T3b (en T2 todo `licencia_vencida` se traduce a `Vencida`; T3b separa `SinLicencia`):

  | Respuesta de `auth/perfil` | Estado |
  |---|---|
  | 200 con `perfil.licencia` y `vence` no vacío | `Vigente` |
  | 403 con `codigo = licencia_vencida` y `licencia` **no nula** en el cuerpo (con fecha) | `Vencida` |
  | 403 con `codigo = licencia_vencida` y `licencia` **explícitamente nula** (sin fila) | `SinLicencia` |
  | 403 con `codigo = licencia_vencida` cuyo cuerpo JSON no trae la clave `licencia` o la trae de otro tipo (o no se conservó el cuerpo) | `Vencida` (ante la duda se bloquea, RF-21) |
  | 200 sin `licencia` / `vence` vacío, 401, 403 otro código o sin `codigo`, 403 con cuerpo que no es JSON (sin `codigo` no se sabe que era `licencia_vencida`), 429, 5xx, 503 `tobb_caido`, red (`codigo = red`), respuesta ilegible, tiempo agotado | `SinDato` (decide la copia local, RF-9) |

  (aclaración 2026-11-02, T3b: la fila «cuerpo no se pudo leer → `Vencida`» no se puede cumplir tal cual cuando el cuerpo no
  es JSON, porque `HcApiClient` no tiene `codigo` y el 403 no se reconoce como `licencia_vencida`; queda `SinDato`. Es lo
  que hace el código de T3b.)

  Se loguea el `codigo`, nunca el token ni las fechas (RF-20). Hay que relanzar la `CancellationException` (el
  tope de espera usa `withTimeoutOrNull`): no tragarla con `runCatching` pelado; patrón del cierre de sesión de
  `hc-general` (`currentCoroutineContext().ensureActive()`).
- **D4. La decisión es una función pura** (constitución #4), en `core/data` `commonMain`, sin red ni Compose:
  `licenciaBloqueadaSegun(estado, sesionLocal): Boolean` → `Vigente` = false, `Vencida` = true, `SinLicencia` = false
  (RF-10: el ingreso no se bloquea, aunque la copia diga vencida; el servidor frena la HC), `SinDato` =
  `sesionLocal.licenciaBloqueada()` (la regla actual, sin tocarla; `LicenciaTest` queda igual). Y
  `copiaDeLicencia(estado, previa, medicoId): Licencia?` calcula la fila local o `null` si no hay nada que escribir.
- **D5. La copia se escribe con `upsertLicencia(fila.toRow(ahoraMillis(), dirty = false))`**, la misma forma que
  `AdminRepository.sincronizarLicencias:69`, sin `dirty` y sin esquema nuevo. Reglas de `copiaDeLicencia`:
  - `Vigente`: `fechaExpiracion = vence`, `fechaAviso = avisoDesde` (si viene vacía, la previa), `activo = true`,
    **`importe` de la previa** (o 0.0 si no hay): el perfil no trae el importe y no se pisa con un vacío (RF-12).
  - `Vencida`: solo si hay previa y estaba activa → `previa.copy(activo = false)`. Sin previa no se crea fila (el
    cliente no sabe la fecha: ver D8). Una fila nueva con fechas vacías confundiría al panel.
  - `SinLicencia` y `SinDato`: `null` (la copia no se toca: no hay fecha que informar ni nada que inventar). Igual que
    la previa → `null` (no se reescribe). `Vigente` con `vence` en blanco: `null` (no es concluyente, RF-12).
  La fila se identifica con `Sesion.medico.medicoId` (el mismo `tobb-m<n>` que usa `resolverPerfil:190`).
- **D6. Un solo punto de decisión en `AuthRepository`:** una función privada
  `decidirLicencia(sesion: Sesion, tope: Duration): Pair<Sesion, Boolean>` que (1) si no es un médico (administrador,
  secretaria) devuelve la sesión y `false`; y si es un médico **sin** HC con backend que lleve licencia, no pide nada
  (RF-4) y decide por la copia local con la regla de hoy, `licenciaBloqueadaSegun(SinDato, sesion)`, sin escribirla
  (RF-7); no queda en `false` fijo, porque eso cambiaría el comportamiento actual en dos casos: el médico de pediatría
  cuyo backend no está registrado (p. ej. sin URL de HC en el entorno) y el médico con una fila de licencia vencida pero
  sin ninguna HC que lleve licencia, que hoy quedan bloqueados por la copia y dejarían de estarlo (sin copia da lo mismo:
  `false`); (2) obtiene el estado, de la
  **memoria** si tiene menos de 60 s y es del mismo token, o consultando con `withTimeoutOrNull(tope)`; (3) escribe la
  copia; (4) rearma la sesión con `resolverPerfil` (para que `PerfilMedico` muestre lo que quedó en la copia) y (5)
  devuelve la sesión y la decisión de `licenciaBloqueadaSegun`. La memoria (`token`, estado, instante) se guarda
  también para `SinDato` (si no, `refrescarPerfil` repetiría un pedido ya fallido) y se borra en `logout`. Con
  varias HC con licencia (hoy solo pediatría) alguna `Vencida` bloquea; la copia es una sola por médico, así que esa
  combinación se decide cuando exista la segunda (fuera de alcance, documentado).
  Los cuatro lugares que hoy llaman a `licenciaBloqueada()` pasan por acá, **incluido `refrescarPerfilRemoto`**
  (`:95`): si no, después de que el servidor dijo `Vigente` la regla local con el reloj del dispositivo podría cerrar
  la sesión el día del vencimiento.
- **D7. Constructor:** tres parámetros nuevos, **al final y con default**, para no romper ningún armado existente:
  `backendsHc: Map<String, HcBackend> = emptyMap()`, `ahora: () -> Long = ::ahoraMillis` (reloj para la memoria de
  60 s) y las esperas (`esperaLogin = 10.seconds`, `esperaRestaurar = 5.seconds`). `DataModule.kt:79` pasa
  `get<HcBackends>().porEspecialidad`. No hay ciclo en Koin: los backends dependen de `HcApiClients` y de la base, no
  de `AuthRepository`.
- **D8. El error conserva el cuerpo (T3b, por P3 = solo la HC).** El 403 del servidor es
  `{ok:false, mensaje, codigo:"licencia_vencida", licencia: {vence, vencida:true} | null}` (verificado en
  `TobbAuthService::conLicencia`, `:184-200`, y `Salud360Api::handle`, `:53-55`: `licencia` va en `extra`, dentro del
  cuerpo). `TobbException` (`ApiClient.kt:40`) gana un **cuarto parámetro opcional con default**
  (`val cuerpo: JsonObject? = null`), de modo que ningún `TobbException(status, codigo, mensaje)` existente cambia de
  firma ni de comportamiento; `HcApiClient.interpretar` (`:105-116`) lo pasa con el objeto ya parseado. El mensaje, el
  código y el estado quedan idénticos (RF-22). Es lo único que cambia fuera de la lógica de licencias, y solo agrega un
  campo. `HcPediatriaBackend.leerPerfilDeLicencia` mira, ante `licencia_vencida`, si el cuerpo trae la clave `licencia`
  con `JsonNull`: `SinLicencia`; en cualquier otro caso de un `licencia_vencida` ya reconocido (con fecha, sin la clave, de otro
  tipo, cuerpo `null`): `Vencida` (RF-21; aclaración 2026-11-02, T3b: un cuerpo que no es JSON nunca llega acá, es `SinDato`).
  `decidirLicencia` no cambia: ya trata `SinLicencia` como «no bloquea, no escribe la copia» por las reglas de T1.
  *Descartado:* un indicador booleano suelto en la excepción (`licenciaNula`): el cuerpo completo sirve a otros casos
  sin costo y deja la decisión en el backend, que es quien conoce el contrato.
- **D9. Orden de las llamadas** (RF-13). El bloqueo va antes de cualquier publicación de la sesión:

  `login` (rama online, `:103-111`):
  1. `importador.importar(perfil)` y `resolverPerfil(...)?.copy(token)` — igual que hoy (`:104-106`).
  2. **Nuevo:** `hcClients.values.forEach { it.token = r.token }` — solo los clientes de HC (la consulta necesita el
     token; `guardarSesion` aún no corrió y `_sesion` sigue en null). Se limpian en `logout` si bloquea.
  3. **Nuevo:** `val (sesion, bloqueada) = decidirLicencia(sesionLocal, esperaLogin)`.
  4. `if (bloqueada) { cerrarPorLicencia(); return ResultadoLogin.LicenciaVencida(sesion) }` — **antes** de
     `guardarSesion`, como hoy (`:109`).
  5. `guardarSesion(sesion)` y `ResultadoLogin.Ok(sesion)`.

  `restaurar` (`:73-89`): tokens a los clientes (`:78-80`, ya está) → `resolverPerfilLocal` (`:83`) → **nuevo**
  `decidirLicencia(s, esperaRestaurar)` → `if (bloqueada) { cerrarPorLicencia(); return null }` → recién entonces
  `_sesion.value = s` (`:85`), `guardarSesion`, `refrescarPerfilRemoto(s)`. La consulta queda **antes** de publicar, así la
  sincronización no arranca para alguien que queda afuera.
  `refrescarPerfil` (`:154-159`): `decidirLicencia(nueva, esperaLogin)`; después de un login cae en la memoria de 60 s
  (sin pedido). `refrescarPerfilRemoto`: igual, también cae en la memoria.
  El camino «sin conexión a turnos» de `login` (`:114-131`) **no se toca**: sigue con la regla local (RF-7, caso 11).
  `cerrarPorLicencia()` es un helper privado de una línea que hoy llama `logout()`; es el único punto que cambia en la
  integración con `hc-general` (ver abajo).
- **D10. Topes y qué pasa cuando se agotan.** `withTimeoutOrNull(tope)` envuelve la consulta; `null` →
  `SinDato`. Si pediatría tarda, la pantalla de «Abriendo Salud 360...» no espera más que 5 s por esto. (Alternativa
  «mostrar con la copia y corregir después», descartada en P5(b).) Además de lo que ya espera `refrescarPerfilRemoto` (turnos,
  hasta 30 s: es previo y no cambia).
- **D11. Sin cambios en `features/auth` ni en `composeApp`:** `LoginViewModel` ya muestra el aviso con
  `ResultadoLogin.LicenciaVencida` (`:75`, `LoginScreen.kt:121-126`); `App.kt` ya espera `restaurar()`.

## Qué se crea y qué se modifica (todo en `core/data`)
| Archivo | Cambio | RF |
|---|---|---|
| `repos/LicenciaDelServidor.kt` (nuevo) | `EstadoLicenciaRemoto` (con `SinLicencia`), `licenciaBloqueadaSegun`, `copiaDeLicencia` (puras) | 5, 6, 7, 8, 10, 11, 12 |
| `network/ApiClient.kt` (`:40`) y `network/hc/HcApiClient.kt` (`:105-116`) | `TobbException.cuerpo: JsonObject? = null` y `interpretar` lo pasa (T3b) | 10, 21, 22 |
| `repos/HcBackend.kt` (`:77-81`) | `licenciaDelServidor()` con default `SinDato` | 1, 2, 3, 4, 19 |
| `repos/HcPediatriaBackend.kt` (`:511-520`) | `leerPerfilDeLicencia()` privado; `licenciaDelServidor()`; `avisoDeLicencia()` sobre lo anterior; en T3b distingue `SinLicencia` por el cuerpo | 1, 5, 6, 9, 10, 19, 20, 21 |
| `repos/AuthRepository.kt` | `decidirLicencia`, memoria de 60 s, parámetros nuevos, `cerrarPorLicencia`, token a los clientes en `login`, cuatro llamadas | 1, 2, 3, 4, 7, 8, 13, 14, 15 |
| `DataModule.kt` (`:79`) | pasar `HcBackends.porEspecialidad` al `AuthRepository` | 1, 2 |
| `jvmTest/.../HcLicenciasE2ETest.kt` | tests nuevos contra la copia local de pediatría | 5, 6, 11, 16 |
| `commonTest` / `jvmTest` (nuevos) | ver «Estrategia de tests» | todos |

No se tocan: `AdminRepository`, `HcRepository`, `SyncAdapters`, `TurnosImportador`, `features/*`, `composeApp`, base de
datos, servidores. (El texto que ve el médico sin fila en la HC es el que ya sale hoy con el mensaje del servidor; no
se agrega ninguno.) `build.gradle.kts` de `core:data` ya reenvía las propiedades `salud360.test.hc.*` a los tests
(`:25-31`); no hace falta ninguna dependencia nueva (`runBlocking` y `com.sun.net.httpserver.HttpServer` vienen con el
JDK / lo que ya usan los E2E).

## Dependencias fuera de este repo
Ninguna nueva. Pediatría ya sirve `licencia` en `auth/perfil` y `403 licencia_vencida` (`Salud360Api.php:53-55`,
`AuthController.php:50`). Un servidor sin esa mejora contesta correcto sin `licencia`: se vuelve `SinDato` (RF-19).

## Sin conexión y reconciliación
- Sin señal, o sin respuesta concluyente: la copia local decide con la regla de hoy y no se modifica (RF-7).
- Con señal, la copia local se pone al día con cada respuesta concluyente (RF-11); al volver la señal, el próximo
  inicio, recarga o refresco la corrige. Si el panel del administrador la escribe después, sigue igual que hoy.
- La copia nunca queda `dirty`: ninguna ruta de `SyncEngine` la empujaría (`SyncAdapters.kt:81-82` solo lee filas `dirty = 1`).
- Riesgo conocido de `SyncEngine`: si algún día existe `sync/pull` en turnos y trae `licencias`, pisaría la copia con
  lo que haya allá (hoy da 404). No se cambia acá.

## Interfaz
Ninguna pantalla cambia. El médico bloqueado ve el mismo texto de la pantalla de ingreso (`LoginScreen.kt:121-126`); el aviso
por vencer sigue saliendo de `MainShell.kt:191-197` (dos pedidos a `auth/perfil` por inicio de sesión: consulta + aviso, ver P6).

## Estrategia de tests
Todos en `core:data`. Backend falso = una clase de test que implementa `HcBackend` con un `licenciaDelServidor()`
programable (con compuerta `CompletableDeferred` para «no contesta a tiempo», no con `delay`, patrón de `hc-general`).
Base: SQLite temporal como `HcLicenciasE2ETest` (`createDatabase(DriverFactory(archivo))`).

| RF | Test | Dónde |
|---|---|---|
| 5, 6, 7, 8, 10 | `licenciaBloqueadaSegun`: tabla `Vigente`/`Vencida`/`SinLicencia`/`SinDato` × copia vigente / vencida / inactiva / ausente / fecha corrupta | `commonTest` `LicenciaDelServidorTest` |
| 10, 11, 12 | `copiaDeLicencia`: vigente sobre vencida, sobre ausente, conserva importe, no reescribe si es igual, `Vencida` sin previa no crea, `SinLicencia` y `SinDato` no tocan | ídem |
| 9, 10, 19, 20 | `HcPediatriaBackend.licenciaDelServidor` contra `HttpServer` falso en `127.0.0.1`: 200 con licencia, 200 sin licencia, 200 con `vence` vacío, 403 `licencia_vencida` con `licencia` con fecha (T2: `Vencida`), 403 `hc_no_habilitada`/`medico_no_vinculado`/`sin_permiso`, 401, 429, 500, 503, cuerpo no JSON, puerto cerrado; y `avisoDeLicencia` sin regresión | `jvmTest` `HcPediatriaLicenciaServidorTest` |
| 10, 21, 22 | T3b: `TobbException` conserva el cuerpo (y sin cuerpo `cuerpo == null`); un `HttpServer` falso con 403 `licencia_vencida` y `licencia: null` → `SinLicencia`; con `licencia: {vence, vencida}` → `Vencida`; sin la clave `licencia` o de otro tipo → `Vencida`; 403 con cuerpo que no es JSON → `SinDato`; los errores existentes no cambian (código, estado, mensaje) | `jvmTest` `HcPediatriaLicenciaServidorTest` y `HcApiClientErrorTest` (nuevo) |
| 10 | `AuthRepository` con backend falso `SinLicencia`: `restaurar`/`login`/`refrescarPerfil` no bloquean aunque la copia diga vencida, y no tocan la copia | `jvmTest` `AuthRepositoryLicenciaServidorTest` |
| 1, 4, 14 | `AuthRepository` con backend falso: pregunta solo a las HC del médico con licencia; no pregunta al administrador, a la secretaria ni al médico sin HC con licencia; una consulta por entrada | `jvmTest` `AuthRepositoryLicenciaServidorTest` |
| 2, 5, 6, 7, 13, 15 | `restaurar`: copia vencida + servidor vigente → sesión; copia vigente + servidor vencida → null, sesión borrada, nunca publicada (se observa `sesion` desde un colector); servidor sin respuesta → decide la copia; servidor colgado → vuelve a los 5 s con la copia | ídem |
| 1, 5, 6, 13, 15 | `login` (con la parte posterior a turnos expuesta como función `internal`, como `borrarCopiaEnfermedadesAlEntrar` de `hc-general`, o con un turnos falso en `HttpServer` si armar el `TobbPerfil` cuesta más): mismos casos, y que `ResultadoLogin.LicenciaVencida` salga **sin** haber publicado la sesión | ídem |
| 3, 14 | `refrescarPerfil` justo tras entrar reusa la respuesta (reloj falso a 59 s / 61 s) | ídem |
| 11 | tras cada consulta concluyente, `licenciasDirty()` vacío | ídem |
| 17 | `AdminRepository` sin cambios: `AdminRepositoryLicenciasTest` no existe en este árbol; el E2E existente (`:48-101`) cubre `sincronizarLicencias` / `guardarLicencia` | `HcLicenciasE2ETest` |
| 5, 6, 11, 16 | E2E contra la **copia local de pediatría**: ver T7 | `HcLicenciasE2ETest` |
| 10 (en la HC), 13, 15, 18 | manual del usuario (M1 a M9 de `tasks.md`), con `.\gradlew devWeb` desde el worktree | — |

`LicenciaTest` (existente, `commonTest`) no se modifica y tiene que seguir en verde: es la regla del camino sin conexión.

## Integración posterior en `hc-general` (no es parte de esta spec; requiere pedido)
- Se hace por **cherry-pick** de los commits de `pediatria-licencia-servidor` sobre `hc-general`, no por merge (el
  merge arrastraría `d3b6e7d` y todo el historial de la rama publicada; además `hc-general` tiene commits con un nombre
  real en el historial y no se publica tal cual, ver `MEMORY.md`).
- Conflictos esperables, por archivo:
  - `AuthRepository.kt`: `hc-general` agregó `alCerrarSesion`, `revocar`, `scopeRevocacion` y `limite` al constructor, y
    `logout(tokenARevocar)` con la revocación del token en segundo plano (`:64-88`, `:175-209`). Los tres parámetros
    de D7 van **después** de esos: conflicto de firma, trivial. `cerrarPorLicencia()` (D9) es la costura: allí hay que llamar a
    `logout(token)` con el **token recién emitido** en `login` (hoy lo hace `:136` y `:149` de `hc-general`) y a `logout()` en
    `restaurar`/`refrescarPerfil`; las demás líneas de D9 no chocan. Un detalle bueno: la consulta de `login` hace que
    el servidor guarde ese token 10 min; con la revocación de `hc-general` el bloqueo lo borra de pediatría (`auth/salir`)
    en lugar de dejarlo aceptado hasta que venza su fila.
  - `DataModule.kt`: el armado de `AuthRepository` en `hc-general` ya lleva `alCerrarSesion` y `revocar`; se suma el
    parámetro nuevo por nombre.
  - `HcBackend.kt` y `HcPediatriaBackend.kt`: en `hc-general` los números de línea cambian (`avisoDeLicencia` en `:89` y `:430`),
    y `HcPediatriaBackend` delega pacientes en otro archivo; la zona de licencias no cambió, así que debería aplicar con
    desplazamiento. `HcClinicaBackend` (solo lectura) no sobreescribe nada: queda con el default `SinDato`, que es lo
    correcto (clínica no lleva licencia en la app; `AdminRepository` de `hc-general` ya ignora los backends de solo lectura).
  - `ApiClient.kt` (`TobbException`) y `HcApiClient.kt` (`interpretar`), por T3b: agregan solo un parámetro opcional y
    pasarlo; en `hc-general` revisar que otras tareas no hayan cambiado esas dos líneas (conflicto trivial).
  - Tests: los tests de `AuthRepository` de `hc-general` (E12: `revocar` vacío por defecto, nunca el cliente de turnos real
    de producción) no deben armarse con backends reales; los de esta spec arman backends falsos.
- Al integrar hay que reejecutar `.\gradlew :core:data:jvmTest` completo en `hc-general` y actualizar `docs/hc-pediatria-estado-y-plan.md`
  («Las licencias») con la regla nueva: el dispositivo ya no decide si hay conexión.

## Publicación (aparte, con aprobación)
Un push de la rama a `main` **publica la web** (GitHub Actions → rsync) y requiere aprobación aparte. Antes: (1) prueba
manual M1 a M9 del usuario; (2) la rama parte de `origin/main` (`d3b6e7d`), así que no arrastra nada de `hc-general`.
(Ya no hace falta confirmar que todos los médicos con pediatría habilitada tengan su licencia cargada: con P3 = solo la
HC, el médico sin fila entra a la app y solo se le frena la HC de pediatría, que el servidor ya frena hoy.)

## Riesgos
1. **Bloqueo masivo (P3): descartado por la decisión del usuario.** El médico con pediatría habilitada y sin fila de
   licencia entra a la app y a la agenda; solo la HC de pediatría queda frenada (por el servidor). Riesgo que queda: la
   distinción depende de que el servidor mande `licencia: null` (explícito) en el 403; si algún día cambia la forma del
   cuerpo, la app bloquea (RF-21), que es el lado seguro. Lo cubren los tests de T3b y el E2E contra la copia local.
2. **Recarga más lenta con mala señal:** hasta 5 s de pantalla de carga por la licencia (más lo que ya espera por turnos).
3. **Dos `auth/perfil` por inicio de sesión** (consulta + aviso) contra el límite de 60/min por IP del consultorio; la
   consulta del refresco reutiliza la respuesta.
4. **Servidor en gracia** (turnos caído): contesta correcto sin `licencia` → la app cae a la copia, que puede estar vieja.
5. **Copia vieja sin señal:** una baja o renovación posterior no se ve hasta tener señal (P2).
6. **Los E2E de licencias provocan una escritura del propio servidor:** al evaluar una licencia vencida, `conLicencia`
   la marca `activo = 0` (`TobbAuthService.php:195-197`). Solo se corren contra la copia local y siempre con el `finally`
   que restaura por `PUT licencias/{n}`.
7. **Integración:** un solo punto delicado (`cerrarPorLicencia` y el token de `login`); si se olvida, el bloqueo no revoca el token.
8. **Diferencias de reloj/fecha** del servidor (UTC, otra zona) afectan el último día de la licencia: se evalúan aparte, a propósito.
