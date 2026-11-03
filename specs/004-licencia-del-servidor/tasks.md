# Tareas 004 — La licencia la decide el servidor

Estado: **aprobado por el usuario el 2026-11-02** (P1 a P6 decididas, ver al final; P3 = solo la HC de pediatría, que suma la
tarea T3b). Se implementan T1 a T8 en el worktree, encadenadas, sin push ni publicación; T9 y la publicación a `main` solo con
pedido aparte. Orden: T1, T2, T3, T3b, T4, T5, T6, T7, T8.

Plan: `plan.md`. Spec: `spec.md`. Una tarea por vez, **tests primero (en rojo), después el código, tests en verde,
marcar la tarea y parar**. Máximo 20-30 minutos cada una.

Reglas para todas:
- **El código va en el worktree `C:\Users\flor\rebonline\salud360-pediatria`, rama `pediatria-licencia-servidor`**
  (desde `origin/main` = `d3b6e7d`). Las specs se quedan en `salud360` (rama `hc-general`). Antes de empezar:
  `git -C ..\salud360-pediatria status` limpio y en esa rama; no se trabaja en `hc-general` para esto.
- Commits **locales** como `rodri89` (verificar con `git log -1 --format=%an`), uno por tarea, mensaje `área: qué
  cambia, en lenguaje llano` (por ejemplo `auth: la licencia se decide con lo que contesta el servidor`). **Sin push** en
  ningún repo. Publicar a `main` publica la web y requiere aprobación aparte.
- Nada contra producción. Ningún dato real, token, mail ni nombre en el repo (tampoco en tests, `.md` ni mensajes de
  commit): los fixtures usan ids inventados y fechas relativas a `hoy()`. Los tokens de prueba de pediatría están en un
  archivo fuera de los repos (`MEMORY.md`); no copiarlos.
- No tocar servidores (pediatría, turnos, `hclinica`), ni `hc-general`/clínica, ni el esquema local (sin migraciones), ni
  `features/*`/`composeApp` salvo lo que diga la tarea.
- `LicenciaTest` y todo lo que ya pasa tiene que seguir en verde; **prohibido avanzar con tests en rojo**.
- Gradle: si falla por `JAVA_HOME`, `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`. El worktree **no
  tiene `local.properties`** (no se versiona): para `devWeb` o para cualquier tarea que compile `composeApp`, copiar el de
  `salud360` o pasar las URLs con `-P`; sin eso el build queda contra producción (`AGENTS.md`, «Entorno dev/release»).
  No matar el `devWeb` que tenga abierto el usuario en el repo principal.
- E2E solo contra la **copia local** de pediatría (`http://localhost/HCDPediatria-salud360/public/index.php`), con
  tokens del archivo de pruebas; al terminar, restaurar la licencia de prueba. **No vaciar `salud360_token_cache` de
  pediatría**: guarda los tokens de prueba sembrados (`MEMORY.md`, E5.4).
- Actualizar `MEMORY.md` al cerrar cada tarea (breve, sin datos sensibles).

## Tanda A — Lógica y datos (`core:data`, worktree)

- [x] **T1. Estado de la licencia del servidor y reglas puras.** RF-5, RF-6, RF-7, RF-8, RF-10, RF-11, RF-12 · tamaño S (~20 min)
  - Tests primero: `core/data/src/commonTest/.../repos/LicenciaDelServidorTest.kt`. `licenciaBloqueadaSegun`: tabla
    `Vigente` / `Vencida` / `SinLicencia` / `SinDato` × copia vigente, vencida por fecha, inactiva, ausente, con fecha corrupta
    (`SinDato` + ausente = no bloquea; `Vigente` + copia vencida = no bloquea; `Vencida` + copia vigente = bloquea;
    `SinLicencia` nunca bloquea, ni con la copia vencida o inactiva).
    `copiaDeLicencia`: vigente sobre ausente (importe 0.0), vigente sobre vencida (conserva el importe), `aviso_desde`
    vacío (conserva el previo), `vence` vacío (`null`), igual a la previa (`null`), `Vencida` sobre activa (`activo = false` con
    las fechas), `Vencida` sin previa (`null`), `SinLicencia` (`null`, con y sin previa), `SinDato` (`null`).
  - Código: `repos/LicenciaDelServidor.kt` con `EstadoLicenciaRemoto` (`Vigente(vence, avisoDesde, porVencer)`, `Vencida`,
    `SinLicencia`, `SinDato`), `licenciaBloqueadaSegun(estado, sesion)` y `copiaDeLicencia(estado, previa, medicoId)`. Puras: sin
    red, sin base, sin reloj propio (`licenciaBloqueada()` ya usa `hoy()`).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*LicenciaDelServidorTest*' --tests '*LicenciaTest*'` en verde;
    `LicenciaTest.kt` sin cambios en `git diff`.
  - **Hecho (2026-11-02, worktree, commit `920d206`).** Rojo previo: `LicenciaDelServidorTest` sin el código fallaba al compilar solo
    por los tres símbolos que faltan (67 errores, todos «Unresolved reference» de `EstadoLicenciaRemoto`, `licenciaBloqueadaSegun`
    y `copiaDeLicencia`). Verde: `LicenciaDelServidorTest` 18/18 y `LicenciaTest` 8/8; `:core:data:jvmTest --rerun` completo 43 tests, 0
    fallas (el árbol publicado tiene 9 clases de test; los E2E se saltean solos). `LicenciaTest.kt` sin cambios. No se compiló
    wasm (T1 no lo pide; es `commonMain` puro).
    Decisiones que el texto no fijaba: (1) `SinLicencia` nace en T1 (la spec ya la pedía por P3) y **nunca bloquea, ni siquiera con
    la copia vencida o inactiva**; no toca la copia. (2) `Vigente` con `vence` en blanco: `copiaDeLicencia` devuelve `null`
    (no se pisa la copia con un vacío; el backend, en T2, ya lo traduce a `SinDato`). (3) `Vencida` solo desactiva una copia que
    estaba activa; si ya estaba inactiva devuelve `null`. (4) El aviso vacío conserva el de la previa o queda `""` sin previa.
    (5) `licenciaBloqueadaSegun` no mira el rol: filtrar al administrador, la secretaria y al médico sin HC con licencia es de
    `decidirLicencia` (T3, RF-4).

- [x] **T2. El backend de pediatría informa la licencia.** RF-1, RF-5, RF-6, RF-9, RF-10, RF-19, RF-20 · tamaño M (~30 min)
  - Tests primero: `core/data/src/jvmTest/.../repos/HcPediatriaLicenciaServidorTest.kt` con un `HttpServer`
    (`com.sun.net.httpserver`) en `127.0.0.1` que contesta `auth/perfil` según el caso, y `HcPediatriaBackend(db,
    HcApiClient(url, "pediatria"))` con SQLite temporal. Casos: 200 con `licencia` → `Vigente` (fechas y `por_vencer` bien
    leídos); 200 sin `licencia` → `SinDato`; 200 con `vence` vacío → `SinDato`; 403 `licencia_vencida` con `licencia` y sin ella → `Vencida` (en esta tarea todo
    `licencia_vencida` es `Vencida`; T3b separa el caso `licencia: null`);
    403 `hc_no_habilitada`, `medico_no_vinculado` y `sin_permiso` → `SinDato`; 401 `token`, 429, 500, 503 `tobb_caido`,
    cuerpo que no es JSON y puerto cerrado → `SinDato`; la cancelación no se traga (una corrutina cancelada mientras espera
    termina cancelada); y `avisoDeLicencia` sin regresión: `por_vencer = true` → aviso con la fecha, `false` → null, cualquier
    fallo → null.
  - Código: `HcBackend.licenciaDelServidor()` con default `SinDato`; `HcPediatriaBackend.leerPerfilDeLicencia()` privado +
    `licenciaDelServidor()`; `avisoDeLicencia()` apoyado en la misma lectura, mismo resultado que hoy. Loguear solo el
    `codigo` del error, nunca token ni fechas.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*HcPediatriaLicenciaServidorTest*'` en verde; el test del aviso
    pasa igual antes y después de tocar `avisoDeLicencia`; `.\gradlew :core:data:compileKotlinJvm` sin errores.
  - **Hecho (2026-11-02, worktree, commit `3fc1a24`).** Rojo previo en dos pasos: sin nada, el test no compilaba (5 «Unresolved
    reference» de `licenciaDelServidor`); con solo el default en `HcBackend`, 7 de 25 fallaban (`Vigente` x2, `Vencida` x2, el pedido
    único/solo lectura, y las dos cancelaciones) y los 5 del aviso pasaban con el `avisoDeLicencia` viejo. Verde: 25/25, y
    `:core:data:jvmTest --rerun` completo 68 tests (43 + 25), 0 fallas; `compileKotlinJvm` y `:core:data:compileKotlinWasmJs` compilan
    (no se compiló `composeApp`: T2 no lo pide y es `commonMain`). Ningún test existente cambió (`LicenciaTest` y el E2E sin tocar).
    Mapeo (`leerPerfilDeLicencia`): 200 con `perfil.licencia` y `vence` no vacío → `Vigente(vence, aviso_desde, por_vencer)`; 200 sin
    `licencia`/`perfil`, con `vence` vacío o ilegible → `SinDato`; **403 + `licencia_vencida` → `Vencida`** (con o sin `licencia` en el
    cuerpo: hoy `TobbException` no conserva el cuerpo); cualquier otro 401/403, 429, 5xx, 503 `tobb_caido`, cuerpo no JSON, 200 con
    `ok: false` y red (`codigo = red`, puerto cerrado) → `SinDato`. La `CancellationException` se relanza. Solo `GET auth/perfil`, uno por lectura.
    **Punto de extensión para T3b:** el `catch (e: TobbException)` de `leerPerfilDeLicencia` (hoy devuelve `Vencida` para todo
    `licencia_vencida`); con `e.cuerpo` ahí se mira `JsonNull` → `SinLicencia`. El test
    `en_esta_tarea_licencia_vencida_sin_licencia_en_el_cuerpo_tambien_es_vencida` pide hoy `Vencida` para `licencia: null` y T3b tiene
    que cambiarlo a `SinLicencia` (el texto de T3b ya lo prevé).
    Decisiones que el texto no fijaba: (1) **un cambio de comportamiento a propósito en `avisoDeLicencia`:** antes tragaba la
    cancelación (`runCatching`) y devolvía `null`; ahora la relanza. Resultado observable igual en todo lo demás; el único llamador
    (`HcRepository.avisosDeLicencia`) ya la traga con su propio `runCatching`, así que no cambia nada visible, y hay un test que lo fija.
    (2) El `403 licencia_vencida` exige status 403 **y** código (otro status con ese código → `SinDato`). (3) Cualquier `Throwable` que no
    sea `TobbException` ni cancelación también da `SinDato`, y se loguea solo el nombre de la clase. (4) El log pasó de `log.w(it)`
    (con excepción y mensaje) a una línea con el `codigo` o el status (RF-20). (5) Se agregó un fake `HcBackend` mínimo en el test
    para probar el default `SinDato`; T3 tendrá que armar el suyo, programable.

- [x] **T3. `AuthRepository` decide con el servidor (sin cablear aún a los flujos).** RF-1, RF-4, RF-7, RF-11, RF-12, RF-14, RF-15 · tamaño M (~30 min)
  - Tests primero: `core/data/src/jvmTest/.../repos/AuthRepositoryLicenciaServidorTest.kt`, con SQLite temporal,
    `TurnosOnlineClient` apuntado a `http://127.0.0.1:1` (cae rápido; nunca el cliente por defecto, que apunta a
    producción) y un backend falso programable. Llamando a `decidirLicencia` (`internal`): médico con HC con licencia →
    consulta una vez; administrador, secretaria y médico sin HC con licencia → ninguna; `Vigente` escribe la copia con
    `licenciasDirty()` vacío y conserva el importe; `Vencida` pone la copia en inactiva; `SinDato` no toca la copia y
    decide la copia; la segunda llamada a los 59 s con el mismo token reutiliza (cero consultas más), a los 61 s o con otro
    token consulta de nuevo (reloj inyectado); la consulta que no contesta (compuerta `CompletableDeferred`) vuelve a los
    topes con `SinDato` (tope corto inyectado); `logout` borra la memoria; `SinLicencia` no bloquea aunque la copia diga
    vencida o inactiva, no toca la copia y también queda en la memoria de 60 s.
  - Código: `decidirLicencia(sesion, tope)`, la memoria de 60 s, `cerrarPorLicencia()` (llama a `logout()`) y los tres
    parámetros del constructor (al final, con default: `backendsHc`, `ahora`, `esperaLogin`/`esperaRestaurar`). Todavía
    sin llamarla desde `login`/`restaurar`/`refrescarPerfil`.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*AuthRepositoryLicenciaServidorTest*'` en verde; ningún
    `AuthRepository(...)` existente deja de compilar (`.\gradlew :core:data:compileKotlinJvm`).
  - **Hecho (2026-11-02, worktree, commit `98ef156`).** Rojo previo: `AuthRepositoryLicenciaServidorTest` no compilaba (65 errores, todos
    consecuencia de lo que falta: `decidirLicencia`, y los parámetros `backendsHc` y `ahora`). Verde: 27/27 y `:core:data:jvmTest --rerun`
    completo 95 tests (68 + 27), 0 fallas, 0 salteados; `:core:data:compileKotlinWasmJs` compila (`composeApp` no: T3 no lo pide).
    Ningún test existente cambió; los métodos públicos de `AuthRepository` (`login`, `restaurar`, `refrescarPerfil`) no tienen ni una línea
    cambiada (el `git diff` solo agrega), y `DataModule` sigue igual (T6 le pasa `backendsHc`).
    Firma: `internal suspend fun decidirLicencia(sesion: Sesion, tope: Duration): Pair<Sesion, Boolean>` (sesión rearmada desde la copia,
    «bloqueada»). Constructor: `backendsHc: Map<String, HcBackend> = emptyMap()`, `ahora: () -> Long = ::ahoraMillis`,
    `esperaLogin = 10.seconds`, `esperaRestaurar = 5.seconds`, al final y con default. `logout` borra la memoria; `cerrarPorLicencia()`
    (privada, llama a `logout()`) todavía sin usar.
    Árbol de decisión: (1) sin `sesion.medico` (administrador, secretaria) → `(sesion, false)`, sin pedido ni escritura. (2) Backends = los de
    `backendsHc` cuyo código está en las especialidades del médico; si no hay ninguno → `(sesion, licenciaBloqueadaSegun(SinDato, sesion))`,
    o sea la regla de hoy sobre la copia, sin pedido ni escritura. (3) Con memoria del mismo token y de menos de 60 s (`0 <= ahora - en <
    60 000`, a los 60 s justos ya consulta) → se reusa el estado y **no se escribe nada**. (4) Si no, un pedido por backend con
    `withTimeoutOrNull(tope)` (tope → `SinDato`; cualquier `Throwable` que no sea cancelación → `SinDato`, se loguea solo la clase); varios
    backends se juntan con `combinarEstadosDeLicencia` (`Vencida` > primera `Vigente` > `SinLicencia` > `SinDato`); se escribe la copia con
    `copiaDeLicencia` (`upsertLicencia(fila.toRow(ahoraMillis(), dirty = false))`, solo si devuelve algo) y se guarda la memoria (también
    `SinDato` y `SinLicencia`). (5) Se rearma la sesión con `resolverPerfil` y se devuelve `licenciaBloqueadaSegun(estado, rearmada)`.
    | Estado | Bloquea | Copia local |
    |---|---|---|
    | `Vigente` | no, aunque la copia diga vencida/inactiva/ausente | fecha, aviso (el previo si viene vacío), `activo = true`, importe previo (0.0 sin previa); no se reescribe si es igual |
    | `Vencida` | sí, aunque la copia diga vigente | solo si había copia activa → `activo = false`; sin copia no se crea fila |
    | `SinLicencia` | no, aunque la copia diga vencida/inactiva | no se toca |
    | `SinDato` (sin respuesta, tope, falla del backend) | decide la copia (hoy); sin copia entra | no se toca |
    Decisiones que el texto no fijaba: (1) **médico sin backend con licencia → decide la copia, no `false`:** el plan D6 decía «devuelve
    la sesión y `false`», pero eso cambiaría el comportamiento de hoy para un médico de pediatría sin backend registrado (p. ej. sin URL) o
    para quien tenga una fila de licencia vencida sin HC con licencia; RF-7/«los tests existentes» piden la regla de hoy, y en la práctica
    coincide (sin copia = `false`). (2) «HC con licencia» = HC del médico que tiene backend en `backendsHc` (no hay un indicador en
    `HcBackend`); un backend sin licencia (clínica) contesta `SinDato` al instante, sin red. (3) Con varias HC se consulta a todas, una
    tras otra, cada una con su tope (peor caso n × tope; hoy n = 1) y la combinación de arriba, que la spec dejaba para cuando exista la
    segunda; está en `LicenciaDelServidor.kt` (pura) y la cubren dos tests de `AuthRepository`. (4) La memoria hit no reescribe la copia
    (ya se aplicó). (5) La memoria guarda `ahora()` al terminar el pedido, no al empezarlo. (6) Sin `Mutex`: dos `decidirLicencia`
    simultáneos pueden hacer dos pedidos (en los flujos de la app son secuenciales). (7) No hay guarda de «generación» contra un pedido que
    termina después de `logout`: la memoria está atada al token, y el siguiente ingreso trae otro token.

- [x] **T3b. El error de la API de HC conserva su cuerpo y el backend distingue «sin licencia».** RF-10, RF-21, RF-22 · tamaño M (~25 min) · depende de T2 (y va después de T3)
  - Contexto: por P3 (solo la HC) el servidor contesta `403 licencia_vencida` con `licencia: null` (sin fila) o con
    `licencia: {vence, vencida: true}` (con fila vencida o baja), dentro del cuerpo JSON (verificado en `TobbAuthService.php` y
    `Salud360Api.php`, solo lectura). Hoy `HcApiClient.interpretar` descarta el cuerpo.
  - Tests primero: (1) `core/data/src/jvmTest/.../network/hc/HcApiClientErrorTest.kt` (nuevo, `HttpServer` en `127.0.0.1`): un
    rechazo conserva `status`, `codigo` y `mensaje` **idénticos a hoy** (casos: 403 `licencia_vencida`, 401 `token`, 500 sin JSON,
    200 con `ok: false`, cuerpo vacío) y `cuerpo` trae el objeto cuando el cuerpo era JSON (y `null` cuando no); un
    `TobbException(status, codigo, mensaje)` armado con tres argumentos sigue compilando y tiene `cuerpo == null`.
    (2) en `HcPediatriaLicenciaServidorTest`: 403 `licencia_vencida` con `licencia: null` → `SinLicencia`; con
    `licencia: {vence, vencida: true}` → `Vencida`; sin la clave `licencia` → `Vencida`; cuerpo que no es JSON → `Vencida`;
    ni el 401, ni otros 403, ni el 200 sin licencia cambian de resultado.
  - Código: `TobbException` con un cuarto parámetro **opcional con default** `val cuerpo: JsonObject? = null` (nadie más lo
    pasa); `HcApiClient.interpretar` lo pasa con el objeto ya parseado; `HcPediatriaBackend.leerPerfilDeLicencia` mira, ante
    `licencia_vencida`, si `cuerpo["licencia"]` es `JsonNull` (explícito) → `SinLicencia`, y si no → `Vencida`. No se loguea el
    cuerpo (RF-20).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*HcApiClientErrorTest*' --tests '*HcPediatriaLicenciaServidorTest*'` en
    verde; `.\gradlew :core:data:jvmTest` completo en verde (nada existente cambió: ningún test previo se tocó); `git diff` de
    `ApiClient.kt` y `HcApiClient.kt` muestra solo el parámetro nuevo y su uso.
  - **Hecho (2026-11-02, worktree, commit `4096bf7`).** Rojo previo en dos pasos: sin nada, los tests no compilaban (`cuerpo` no existía);
    con solo el parámetro en `TobbException` (sin pasarlo ni leerlo), 5 de 50 fallaban (3 de `HcApiClientErrorTest`: el cuerpo trae el objeto,
    `licencia` nula frente a ausente, el cuerpo en el 200 con `ok: false` y en el 401; y 2 de `SinLicencia`) y el resto, que fija lo que
    no tiene que cambiar, ya pasaba. Verde: `HcApiClientErrorTest` 17/17, `HcPediatriaLicenciaServidorTest` 33/33 (eran 25) y
    `:core:data:jvmTest --rerun` completo **120 tests, 0 fallas, 0 errores, 0 salteados** (95 + 17 + 8); `:core:data:compileKotlinWasmJs`
    compila (`composeApp` no: T3b no lo pide, y el parámetro nuevo es opcional). Cuerpo real verificado en el servidor (solo lectura):
    `Salud360Api::rechazo` hace `array_merge(['ok' => false, 'mensaje', 'codigo'], $extra)`; `TobbAuthService::conLicencia` pone
    `extra = ['licencia' => null]` sin fila y `['licencia' => ['vence', 'vencida' => true]]` con fila vencida o de baja (en JSON, `null`
    explícito). Cambios: `ApiClient.kt` (`TobbException(…, val cuerpo: JsonObject? = null)`, el import y una línea de KDoc), `HcApiClient.kt`
    (`interpretar` pasa `obj`, una línea), `HcPediatriaBackend.kt` (el `catch (TobbException)` de `leerPerfilDeLicencia`); ningún
    `TobbException(…)` existente cambió. Mapeo del 403 de `auth/perfil` (cuerpo → estado):
    | Respuesta | Estado |
    |---|---|
    | 403 `licencia_vencida` y `licencia` **nula** (clave presente con `null`) | `SinLicencia` |
    | 403 `licencia_vencida` y `licencia` con objeto `{vence, vencida}` (vencida o de baja) | `Vencida` |
    | 403 `licencia_vencida` sin la clave `licencia`, o con otro tipo (`{}`, texto, `"null"`, `false`, `0`, lista) | `Vencida` (RF-21) |
    | 403 `licencia_vencida` armado a mano sin cuerpo (`cuerpo == null`) | `Vencida` |
    | 403 con otro código (`hc_no_habilitada`, `medico_no_vinculado`, `sin_permiso`) o sin `codigo`, aunque traiga `licencia: null` | `SinDato` |
    | 401 `token`, 429, 5xx, 503 `tobb_caido`, red, cualquier otro status, aunque diga `licencia_vencida` con `licencia: null` | `SinDato` |
    | 403 cuyo cuerpo **no es JSON** (HTML, texto) | `SinDato` (sin JSON no hay `codigo`) |
    **Único test existente que se ajustó:** `HcPediatriaLicenciaServidorTest.en_esta_tarea_licencia_vencida_sin_licencia_en_el_cuerpo_tambien_es_vencida`
    (T2) pedía `Vencida` para `licencia: null` y para «sin la clave»; su propio texto decía que T3b lo cambiaba. Pasó a
    `licencia_vencida_con_licencia_nula_en_el_cuerpo_es_sin_licencia` (`SinLicencia`) y la mitad «sin la clave → `Vencida`» salió a su
    propio test nuevo. Más el KDoc de la clase de test. Ningún otro test existente se tocó.
    Decisiones que el texto no fijaba: (1) **«cuerpo que no es JSON → `Vencida`» (T3b, tests (2)) no se puede cumplir tal cual:** sin JSON
    `HcApiClient` no tiene `codigo`, así que no hay forma de saber que era `licencia_vencida`, y `un_403_sin_codigo_es_sin_dato` (T2, sin
    tocar) pide `SinDato`, que es además lo que manda RF-9 para cualquier 403 que no sea `licencia_vencida`. Quedó `SinDato` y un test que lo
    fija; lo que sí es `Vencida` es el cuerpo JSON con `licencia_vencida` y `licencia` ausente o de otro tipo. El «cuerpo ilegible → `Vencida`»
    de RF-21/D3 solo se alcanza armando la excepción a mano (`cuerpo == null`). (2) `cuerpo` es el objeto de cualquier respuesta con JSON
    de objeto (también el 200 con `ok: false` y el 401), no solo del 403; un JSON que no es objeto (`null`, lista) da `cuerpo == null`.
    (3) El 403 `licencia_vencida` sigue exigiendo status 403 **y** código (como en T2). (4) El cuerpo no entra en el mensaje ni en
    `toString()` (hay un test con una fecha inventada), y el log sigue siendo solo `codigo ?: status`. (5) El diff de `ApiClient.kt` suma,
    además del parámetro, el `import` de `JsonObject` y la línea `@param cuerpo` del KDoc.

- [x] **T4. `restaurar` consulta al servidor antes de publicar la sesión.** RF-2, RF-5, RF-6, RF-7, RF-13, RF-15 · tamaño M (~25 min)
  - Tests primero (en `AuthRepositoryLicenciaServidorTest`): sesión guardada de un médico +
    - copia vencida e inactiva + servidor `Vigente` → `restaurar()` devuelve la sesión y la copia queda vigente (**el caso reportado**);
    - copia vigente + servidor `Vencida` → devuelve `null`, la sesión guardada queda borrada y `sesion` **nunca pasó por un valor
      no nulo** (colector de `StateFlow` que registra los valores);
    - servidor `SinDato` → decide la copia con la regla de hoy (vigente entra, vencida no);
    - servidor `SinLicencia` (médico sin fila) → devuelve la sesión **aunque la copia diga vencida o inactiva** y la copia no
      cambia (P3: no se bloquea el ingreso);
    - servidor que no contesta → vuelve al tope (5 s inyectados cortos) con la copia y sin colgarse;
    - administrador y secretaria → no se consulta nada.
  - Código: en `restaurar`, `decidirLicencia(s, esperaRestaurar)` entre `resolverPerfilLocal` y `_sesion.value = s` (orden de
    D9); `if (bloqueada) { cerrarPorLicencia(); return null }`. `refrescarPerfilRemoto` usa también `decidirLicencia`
    (cae en la memoria: cero pedidos más).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*AuthRepositoryLicenciaServidorTest*'` en verde y el colector de
    `sesion` del caso «servidor vencida» solo vio `null`.
  - **Hecho (2026-11-02, worktree, commit `3de1538`).** Rojo previo: con los tests y el código viejo, 8 de 39 fallaban (el caso
    reportado, servidor vencida con copia vigente [el colector vio una sesión], el token y el orden, sin licencia, el tope de 5 s, el
    pedido único y el refresco por la decisión del servidor ×2); los 27 de T3 y los 4 nuevos que fijan «lo que no cambia» (sin dato,
    administrador/secretaria, médico sin HC con licencia, sin sesión guardada) ya pasaban. Verde: `AuthRepositoryLicenciaServidorTest`
    39/39 (27 + 12) y `:core:data:jvmTest --rerun` completo **132 tests, 0 fallas, 0 errores, 0 salteados** (120 + 12);
    `:core:data:compileKotlinWasmJs` compila (`composeApp` no: T4 no lo pide y no se tocó). Mutación: con el
    refresco de perfil vuelto a la regla local, fallan los dos tests del refresco (la verifican).
    Cambios: `AuthRepository.kt` solo en `restaurar` y `refrescarPerfilRemoto` (`git diff`: 15 líneas); el helper `escena(...)` del test
    ganó parámetros con default, y la clase de test, un turnos falso en `HttpServer` (`auth/perfil`) y un gancho `alConsultar` en el
    backend falso. Ningún test existente cambió.
    Nuevo flujo de `restaurar`:
    | Entrada (sesión guardada + servidor) | Resultado | `sesion` | Copia local |
    |---|---|---|---|
    | médico, `Vigente`, copia vencida/inactiva/ausente | devuelve la sesión | publicada una vez, después de decidir | pasa a vigente (importe previo), sin `dirty` |
    | médico, `Vencida`, copia vigente | `null` | nunca no nula; sesión guardada (base y `Settings`) y tokens de los clientes borrados | si estaba activa, `activo = false`; sin copia no se crea |
    | médico, `SinLicencia` (P3), copia vencida/inactiva/ausente | devuelve la sesión | publicada | no se toca |
    | médico, `SinDato` (red, tope de 5 s, 5xx, 429, otro 401/403, backend que lanza) | decide la copia con la regla de hoy (vigente entra, vencida/inactiva `null`); sin copia entra (P1) | según la copia | no se toca |
    | médico sin HC con backend de licencia | regla de hoy sobre la copia, sin pedido | según la copia | no se toca |
    | administrador, secretaria | devuelve la sesión, sin pedido | publicada | — |
    | sin sesión guardada | `null`, sin pedido | nunca | — |
    Después de publicar, `refrescarPerfilRemoto` (turnos) también pasa por `decidirLicencia(…, esperaRestaurar)`: dentro de los 60 s
    cae en la memoria (cero pedidos más); pasado el minuto consulta una vez más y, si ahora está `Vencida`, cierra la sesión
    (única forma de que `sesion` pase por no nulo y vuelva a `null` en una restauración: turnos tardó más de un minuto).
    Decisiones que el texto no fijaba: (1) **`refrescarPerfilRemoto` va en T4** (el texto de T4 lo dice en «Código»); T6 queda con
    `refrescarPerfil` (el público, tras sincronizar) y `DataModule`. (2) `refrescarPerfilRemoto` usa el tope de restaurar (5 s), no
    el de iniciar sesión. (3) El bloqueo usa `cerrarPorLicencia()` (hoy `logout()`), que ya borra la memoria, la sesión guardada y los
    tokens. (4) `restaurar` publica la sesión rearmada por `decidirLicencia` (con la copia ya escrita), no la de antes de consultar.
    (5) Los tests no usan `Settings` (no hay `MapSettings` en las dependencias de prueba): la sesión guardada se mira en la base.
    (6) Se esperan hasta 5 s por la licencia más lo que ya esperaba el refresco de turnos; no se tocó nada de eso.

- [x] **T5. `login` consulta al servidor antes de guardar la sesión.** RF-1, RF-5, RF-6, RF-13, RF-14, RF-15 · tamaño M (~30 min)
  - Tests primero (mismo archivo): la parte de `login` posterior a turnos expuesta como función `internal` (como
    `borrarCopiaEnfermedadesAlEntrar` de `hc-general`) con un `TobbPerfil` mínimo copiado del estilo de
    `core/data/src/commonTest/.../network/tobb/TobbPerfilTest.kt`; si armarlo cuesta más de lo razonable, un turnos falso con
    `HttpServer` que contesta `auth/login`. Casos: servidor `Vigente` con copia vencida → `ResultadoLogin.Ok`; `Vencida` con copia
    vigente → `ResultadoLogin.LicenciaVencida`, `sesion` nunca no nula, ni sesión guardada en la base ni en `Settings`;
    `SinLicencia` con copia vencida → `ResultadoLogin.Ok` (no bloquea) y copia intacta; el cliente
    de HC **ya tiene el token** cuando se consulta (el backend falso lo verifica); `SinDato` → regla local; una sola consulta por
    login; tras bloquear los clientes quedan sin token.
  - Código: en la rama online de `login`, asignar el token a `hcClients` (D9 paso 2), `decidirLicencia(sesion, esperaLogin)`,
    `if (bloqueada) { cerrarPorLicencia(); return LicenciaVencida(sesion) }` **antes** de `guardarSesion`. El camino sin conexión
    a turnos no se toca.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*AuthRepositoryLicenciaServidorTest*'` en verde;
    `git diff` muestra que el bloqueo sigue antes de `guardarSesion` y que el camino «Error» de turnos no cambió.
  - **Hecho (2026-11-02, worktree, commit `e3817c6`).** Rojo previo: con los tests y el código viejo, 9 de 54 fallaban (el caso reportado, el
    token en el cliente de HC al consultar, servidor vencida con copia vigente [y sin copia], sin licencia con copia vencida/inactiva, sin
    copia + vigente, un solo pedido, el tope de 10 s y la cancelación); los 39 anteriores y 6 nuevos que fijan «lo que no cambia» (sin dato,
    administrador/secretaria, médico sin HC con licencia, credenciales inválidas, turnos caído, ingreso sin conexión con sesión previa) ya
    pasaban. Verde: `AuthRepositoryLicenciaServidorTest` 54/54 y `:core:data:jvmTest --rerun` completo **147 tests, 0 fallas, 0 errores, 0
    salteados** (132 + 15); `:core:data:compileKotlinWasmJs` y `:features:auth:compileKotlinJvm` compilan (`composeApp` no: T5 no lo pide y no se
    tocó). Un primer intento del rojo se colgó 15 min (el test de cancelación esperaba una consulta que el código viejo nunca hace): ahora esa
    espera lleva `withTimeout(20 s)` para que falle en vez de colgarse.
    Cambios: `AuthRepository.kt` solo en la rama online de `login` (+ un comentario en la rama sin conexión; `git diff`: 15 líneas). Los
    tests: 15 nuevos y el turnos falso (`TurnosFalso`) ahora también contesta `auth/login` (`tok-login`) y se le programa `rol`, `historias` y
    el rechazo del login. Ningún test existente cambió; `LoginViewModel` y `features/*` sin tocar.
    Nuevo flujo de `login` (rama online, orden de las llamadas): `turnos.login` → `importador.importar` → `resolverPerfil(...).copy(token)` →
    **token a `hcClients`** → `decidirLicencia(sesion, esperaLogin = 10 s)` → si bloquea: `cerrarPorLicencia()` (= `logout()`) y
    `LicenciaVencida(sesión rearmada)`, **sin** `guardarSesion`; si no: `guardarSesion(sesión rearmada)` y `Ok`.
    | Entrada (login online a turnos + servidor de la HC) | Resultado | `sesion` / base | Copia local |
    |---|---|---|---|
    | médico, `Vigente`, copia vencida/inactiva/ausente | `Ok` | publicada y guardada una vez, después de decidir | pasa a vigente (importe previo, 0.0 sin copia), sin `dirty` |
    | médico, `Vencida`, copia vigente o ausente | `LicenciaVencida` | nunca no nula; sin sesión guardada; clientes de HC sin token | si estaba activa, `activo = false`; sin copia no se crea |
    | médico, `SinLicencia` (P3), copia vencida/inactiva/ausente | `Ok` | publicada y guardada | no se toca |
    | médico, `SinDato` (red, tope de 10 s, 5xx, backend que lanza) | la copia decide con la regla de hoy (vigente/sin copia `Ok`; vencida/inactiva `LicenciaVencida`) | según la copia | no se toca |
    | administrador, secretaria; médico sin HC con backend de licencia | `Ok` (el médico sin HC: regla de hoy sobre la copia); sin pedido | publicada | — |
    | credenciales inválidas / turnos caído sin sesión previa | `CredencialesInvalidas` / `SinConexionYSinSesionPrevia`, sin pedido | nunca; clientes sin token | — |
    | turnos caído con sesión previa del mismo usuario (**no cambió**) | la copia decide (`Ok(offline = true)` / `LicenciaVencida`), **0 pedidos** al servidor de la HC | según la copia | no se toca |
    Un solo pedido por ingreso: la memoria de 60 s queda armada con el token nuevo, y el `refrescarPerfil` que lanza `LoginViewModel` (T6) la
    reutilizará.
    Decisiones que el texto no fijaba: (1) **ingreso sin conexión con sesión previa: sigue con la copia, sin consulta** (D9 lo deja sin tocar, la
    spec lo fija en el caso límite 11 y RF-7; «turnos caído» no dice que pediatría también lo esté, pero la spec elige no mezclar): dos tests
    lo fijan, con el backend falso diciendo lo contrario de la copia. (2) **Si `decidirLicencia` se cancela o lanza, los clientes de HC vuelven a
    quedar sin token** (un `try/catch` que relanza): el texto de D9 solo limpia al bloquear, pero un `login` cancelado dejaría el token suelto sin
    sesión; hay un test. (3) `LicenciaVencida` lleva la sesión **rearmada** por `decidirLicencia` (con la copia ya al día), no la de antes de
    consultar; su forma no cambia y `LoginViewModel` solo usa el email. (4) `LoginViewModel`: verificado, no hace falta tocarlo
    (`guardarEmailSiCorresponde` y `licenciaVencida = true`). **Ojo con T6 (hasta que se haga):** `LoginViewModel` lanza `auth.refrescarPerfil()`
    tras un login online y hoy esa función aún decide con la regla local; un médico `SinLicencia` con copia vencida/inactiva (P3), o con
    reloj corrido, **entra al `login` y ese refresco le cierra la sesión**. T6 lo corrige; no publicar T5 sin T6.

- [x] **T6. `refrescarPerfil` y el armado de Koin.** RF-3, RF-14 · tamaño S (~20 min)
  - Tests primero (mismo archivo): `refrescarPerfil()` justo después de entrar no hace consultas nuevas y no cierra la
    sesión de un médico `Vigente` aunque la fecha local ya haya pasado (reloj del dispositivo corrido); con la memoria vencida
    (>60 s) consulta una vez; `Vencida` → cierra la sesión.
  - Código: `refrescarPerfil` pasa por `decidirLicencia`; `DataModule.kt:79` pasa `get<HcBackends>().porEspecialidad`
    como `backendsHc`.
  - Hecho cuando: `.\gradlew :core:data:jvmTest` completo en verde; `.\gradlew :composeApp:compileKotlinWasmJs` compila (el
    grafo de Koin se arma: `AuthRepository` ← `HcBackends` ← `HcApiClients`, sin ciclo); ninguna ruta de `features/*` ni
    `composeApp` cambió en `git diff --stat`.
  - **Hecho (2026-11-02, worktree, commit `0e4e266`).** Rojo previo: con los tests y el código viejo, 13 de 72 fallaban (8 de los 11 de
    `refrescarPerfil` y 5 de los 7 de Koin); pasaban de entrada los que fijan «lo que no cambia» (administrador/secretaria, médico sin HC con
    licencia, sin sesión) y el de armado de Koin (que `HcBackends` solo trae pediatría y que armar el grafo no pide nada). El refresco sin
    cablear: no consultaba (`0` en vez de `1`), cerraba al médico `SinLicencia`/`Vigente` con copia vencida y al `Vencida` con copia vigente
    no le cerraba; en Koin, `login`/`restaurar` nunca llegaban al servidor (`backendsHc` vacío). Verde: `AuthRepositoryLicenciaServidorTest` 65/65
    (54 + 11), `DataModuleLicenciaTest` 7/7 y `:core:data:jvmTest --rerun` completo **165 tests, 0 fallas, 0 errores** (147 + 18);
    `:features:auth:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` (klib) compilan. Mutación: sin la guarda de «la sesión
    ya no es la misma» falla solo el test del `logout` en vuelo. `git diff --stat` de `features/` y `composeApp/`: vacío. Ningún test
    existente cambió (solo se agregaron tests y se actualizó el KDoc de la clase de test).
    Cambios: `AuthRepository.refrescarPerfil` (+ guarda), `DataModule` (`backendsHc = get<HcBackends>().porEspecialidad`, por nombre), tests.
    Flujo de `refrescarPerfil` (tope = `esperaLogin`, 10 s; el médico sigue siendo el de la sesión abierta):
    | Entrada | Resultado | Copia local |
    |---|---|---|
    | sin sesión | no hace nada, sin pedido | — |
    | administrador, secretaria; médico sin HC con backend de licencia | no consulta; el médico sin HC decide la copia con la regla de hoy (cierra si está vencida) | no se toca |
    | memoria de 60 s del mismo token (recién entró) | usa el estado guardado, **0 pedidos**; `Vigente`/`SinLicencia` no cierran aunque la copia diga vencida/inactiva; `SinDato` decide la copia | no se reescribe |
    | memoria vencida (≥ 60 s) y `Vigente` | 1 pedido; no cierra | pasa a vigente (importe previo), sin `dirty` |
    | memoria vencida y `SinLicencia` (P3) | 1 pedido; no cierra aunque la copia diga vencida/inactiva | no se toca |
    | memoria vencida y `Vencida` | 1 pedido; `cerrarPorLicencia()` (= `logout()`): sin sesión, ni guardada, ni token | si estaba activa, `activo = false` |
    | memoria vencida y `SinDato` (red, tope de 10 s, 5xx, backend que lanza) | la copia decide con la regla de hoy | no se toca |
    | la sesión se cerró (o entró otra) mientras esperaba | vuelve sin publicar ni cerrar nada | — |
    Koin: `AuthRepository(db, api, turnos, settings, hcClients, backendsHc = get<HcBackends>().porEspecialidad)`; `ahora` y los topes con sus
    defaults (10 s / 5 s). `HcBackends` ← `HcApiClients` y la base, sin ciclo; resolver el grafo no hace pedidos (test). Hoy `HcBackends` solo
    trae `pediatria` (en este árbol publicado no hay backend de clínica); una URL de HC sin backend (p. ej. `clinica`) no consulta nada.
    `DataModuleLicenciaTest` (nuevo, `core/data/src/jvmTest/.../DataModuleLicenciaTest.kt`): Koin real con `koinApplication`, servidores falsos
    `HttpServer` (turnos, pediatría y una HC «clinica» que cuenta pedidos) y las preferencias en un nodo propio de `java.util.prefs` que se
    borra al terminar; resuelve `AuthRepository` y comprueba `login` (token nuevo en el pedido, 1 solo, clínica 0), `login` vencida, médico solo
    de clínica (0 pedidos), `refrescarPerfil` sin pedido nuevo, `restaurar` vigente con copia vencida (el caso reportado) y `restaurar` vencida.
    Lista del `git grep` de decisiones de licencia (código no-test): `AuthRepository.restaurar` (`:108`), `refrescarPerfilRemoto` (`:122`),
    `login` online (`:139`) y `refrescarPerfil` (`:244`) pasan por `decidirLicencia`; el ingreso sin conexión con sesión previa (`:161`) usa
    `licenciaBloqueada()` con la copia (RF-7, a propósito); `licenciaBloqueadaSegun` (`LicenciaDelServidor.kt:36`, `SinDato`) y el propio
    `decidirLicencia` (`:202`, médico sin HC con backend) usan la regla local como respaldo (es la regla de hoy). Fuera de la app: `:server`
    (`Application.kt:149`, Ktor, la app no habla con él) sigue con `licenciaBloqueada()`; no se tocó. `features/*` y `composeApp` no deciden nada
    (solo muestran `ResultadoLogin.LicenciaVencida`, `licenciaVence` y el aviso).
    Decisiones que el texto no fijaba: (1) **guarda de sesión en `refrescarPerfil`**: con la consulta ahora puede esperar hasta 10 s, y si el
    médico cierra la sesión en ese lapso `guardarSesion` la habría vuelto a publicar y guardar; se compara el token de la sesión abierta
    antes y después y, si cambió, no hace nada (test con compuerta y mutación). (2) Tope = `esperaLogin` (10 s, como pedía D9), no el de
    restaurar. (3) `refrescarPerfilRemoto` (T4) tenía el mismo riesgo (espera a turnos, hasta 30 s, más la licencia) y T6 no lo tocó (es
    anterior y T6 no lo pide); **se resolvió aparte en T6b (`a8b6f93`, ver abajo)**. (4) El test de Koin pisa `Settings` con un nodo de `java.util.prefs` aislado porque no hay
    `MapSettings` en las dependencias de prueba (sin dependencias nuevas). (5) `restaurar` por Koin se prueba con un segundo grafo sobre la
    misma base (equivale a recargar la página).
  - **T6b (2026-11-02, worktree, commit `a8b6f93`, una línea más su test; no es una tarea de la lista, cierra la duda (3) de T6).**
    `refrescarPerfilRemoto` (el refresco que sigue a `restaurar`) espera a turnos (hasta 30 s) y a la licencia y después llamaba a
    `guardarSesion` sin mirar si la sesión seguía siendo la misma: si el médico cerraba sesión en ese lapso, se volvía a publicar y a
    guardar. Ahora, después de `decidirLicencia`, `if (_sesion.value?.token != actual.token) return` (la misma guarda que
    `refrescarPerfil`; `AuthRepository.kt`: +2 líneas, una de comentario). Test nuevo con compuerta
    (`el_refresco_del_perfil_de_restaurar_no_revive_una_sesion_que_se_cerro_mientras_esperaba`): el turnos falso retiene el perfil, el
    médico hace `logout()` y al soltarlo no queda `sesion` publicada, ni guardada en la base, ni token en los clientes de HC. Rojo previo: sin
    la línea falla esa aserción («la sesión cerrada no se vuelve a publicar»; la mutación es el propio rojo, porque la línea es lo único que
    cambió); verde: `AuthRepositoryLicenciaServidorTest` 66/66 (65 + 1). Ojo (decisión del texto, no se amplió): la guarda va después de la
    consulta de licencia, así que en ese caso la consulta igual se hace una vez más con el token ya cerrado (el servidor la rechaza o no
    contesta: `SinDato`); no publica ni guarda nada. Si se quiere evitar ese pedido, una guarda más justo después de `turnos.perfil()`.

## Tanda B — Punta a punta y cierre

- [x] **T7. E2E contra la copia local de pediatría.** RF-5, RF-6, RF-11, RF-16 · tamaño M (~30 min)
  - Ampliar `HcLicenciasE2ETest.kt` (se saltea solo sin las propiedades, igual que hoy). Tres tests nuevos con el token de
    administrador para mover la licencia del médico y el token del médico para leerla; todos con `try/finally` que restaura la
    licencia original por `guardarLicencia(original)`:
    1. `licenciaDelServidor()` del médico: licencia lejana → `Vigente` con la fecha; vencida (ayer) → `Vencida`; dada de baja
       (`activo = false`) → `Vencida`; restaurada → `Vigente`.
    2. **El caso reportado de punta a punta:** base temporal con la copia del médico **inactiva y vencida**, sesión guardada
       del médico, `AuthRepository` con `HcPediatriaBackend` real y turnos apuntado a `http://127.0.0.1:1`; con la licencia
       vigente en el servidor, `restaurar()` devuelve la sesión y la copia pasa a vigente, sin `dirty`.
    3. El inverso: copia vigente, licencia vencida en el servidor → `restaurar()` devuelve `null`.
  - Ojo: evaluar una licencia vencida hace que el **servidor** la marque `activo = 0` (`TobbAuthService.php:195-197`); el
    `finally` la restaura. Pedidos separados ~1,1 s (límite de 60/min por IP).
  - Comando (en el worktree): `.\gradlew :core:data:jvmTest --rerun --tests '*HcLicenciasE2ETest*'
    -Psalud360.test.hc.pediatria=http://localhost/HCDPediatria-salud360/public/index.php
    -Psalud360.test.hc.token=<token médico de prueba> -Psalud360.test.hc.token.admin=<token admin de prueba>`
  - Hecho cuando: el comando anterior en verde (los tres nuevos y los dos de antes); `SELECT` de la fila de licencia del médico de prueba
    en la copia local de pediatría idéntico al de antes de correr (guardado fuera del repo); conteos de tablas sin cambios.
  - **Hecho (2026-11-02, worktree, commit `4a26625`).** Solo contra `http://localhost/HCDPediatria-salud360/public/index.php` (base
    `hc_pediatrica`); Apache y MySQL vivos, la copia contestó 401 sin token y los dos tokens de prueba (médico y administrador) servían.
    Cambios: solo `HcLicenciasE2ETest.kt` (+184 líneas, 1 borrada: el KDoc de la clase). Los dos tests de antes no se tocaron; los tres
    nuevos se saltean solos sin `.pediatria`, `.token.admin` y `.token` (el de «aviso» ya pedía las tres). Sin dependencias, sin
    propiedades nuevas (`build.gradle.kts` sin tocar).
    Tests (los tres con `try/finally` que restaura la licencia con `guardarLicencia(original)`, y pedidos espaciados 1,1 s):
    1. `elServidorDiceSiLaLicenciaDelMedicoEstaVigenteOVencida`: licencia lejana → `Vigente(vence, aviso, porVencer = false)`; vencida
       (ayer) → `Vencida`, y el servidor la dejó `activo = false` (se lee con `traerLicencias`); dada de baja con fecha lejana → `Vencida`;
       restaurada → `Vigente` con las fechas originales.
    2. `elMedicoConLaCopiaVencidaEntraSiElServidorDiceQueLaLicenciaEstaVigente` (**el caso reportado**): base temporal con la copia
       inactiva y vencida (importe 12.5), sesión guardada del médico con su token, `AuthRepository` con `HcPediatriaBackend` real y turnos a
       `127.0.0.1:1`; servidor vigente → `restaurar()` devuelve la sesión, queda publicada y guardada, la copia pasa a vigente conservando
       el importe y sin `dirty`.
    3. `elMedicoConLaCopiaVigenteNoEntraSiElServidorDiceQueLaLicenciaEstaVencida` (el inverso): copia vigente, servidor vencido →
       `restaurar()` devuelve `null`, `sesion` nunca pasó por un valor no nulo (colector), sin sesión guardada, cliente de HC sin token,
       copia inactiva y sin `dirty`.
    Resultado: sin propiedades, `:core:data:jvmTest --rerun` completo **169 tests, 0 fallas, 0 errores** (165 + 1 de T6b + 3 de T7; los E2E
    se saltean solos). Con las propiedades locales `--tests '*HcLicenciasE2ETest*' --rerun`: **5/5 en verde**, dos corridas seguidas, sin
    intermitencias (unos 35 s la clase). Mutación temporal (revertida, `git diff` contra el respaldo vacío): «dada de baja → `Vigente`» falla
    el test 1 («expected Vigente… but was Vencida») y `listOf(false, true)` en los valores vistos de `sesion` falla el test 3.
    **Datos de la base local (`hc_pediatrica`, solo números):** `COUNT(*)` de las 55 tablas idéntico antes y después (`medico_licencias` 26,
    `users` 26, `salud360_token_cache` 5, sin vaciarla) y `CHECKSUM TABLE` de las 55 idéntico. El médico de prueba es el usuario 2 de
    pediatría (médico 1 de turnos), con **una** fila en `medico_licencias` (id 2: activo 1, `fecha_expiracion` y `fecha_aviso` = 2030-12-01,
    importe 0, `created_at` NULL). `guardarLicencia(original)` la devuelve a los mismos valores pero el servidor escribe `updated_at` en
    cada PUT: tras las corridas se restauró con un `UPDATE ... WHERE id = 2` puntual (más las columnas de la fila como guarda,
    `ROW_COUNT = 1`) y el volcado completo de la tabla quedó byte a byte igual al de antes (hash). Ningún `DROP`, `DELETE` ni `INSERT`.
    Respaldos fuera del repo: `%TEMP%\opencode\t7\` (`medico_licencias-antes.tsv`, `conteos-*.txt`, `checksum-*.txt`, `contar.sql`,
    `checksum.sql`). Ningún token en el repo, el diff ni el commit (se comprobó que el texto de ninguno aparece en el diff); los mensajes de
    aserción de los tests no imprimen la sesión (su `toString` lleva el token).
    **Corrida (los tokens se leen del archivo local sin imprimirlos):** `.\gradlew :core:data:jvmTest --rerun --tests '*HcLicenciasE2ETest*'
    -Psalud360.test.hc.pediatria=<url> -Psalud360.test.hc.token=<token médico> -Psalud360.test.hc.token.admin=<token admin>` (comando en el KDoc).
    **Lo que quedó manual:** el caso «sin fila» (`403 licencia_vencida` con `licencia: null` → `SinLicencia`). T7 no lo pide y probarlo
    contra el servidor real exige sacar la fila del médico de prueba (o crear un usuario ad hoc con su token sembrado); no se hizo. Lo cubren
    los tests con servidor falso (T3b a T5, `DataModuleLicenciaTest`) y la prueba manual M9; la forma del cuerpo (`licencia: null`) se verificó
    leyendo `TobbAuthService::conLicencia` (T3b), pero sigue sin verse en vivo.
    Decisiones que el texto no fijaba: (1) las tres propiedades hacen falta para los tres tests nuevos (el administrador mueve y el
    médico lee); los dos de antes siguen como estaban. (2) Se exige como precondición que la licencia de prueba esté vigente al empezar
    (si una corrida anterior murió a medias el test falla pidiendo restaurarla a mano en vez de «restaurar» un estado malo). (3) «Vencida»
    se prueba con la fecha de ayer y se comprueba además que el servidor la dejó inactiva (`TobbAuthService.php:195-197`), que es un
    contrato del servidor y no de la app: si algún día cambia, esa aserción es la que avisa. (4) Los dos tests viejos tocan la fila 2
    igual (el primero mueve «la primera licencia»): por eso se comparó la tabla entera y no solo la fila del médico.

- [ ] **T8. Cierre: suite completa, compilación web, revisión y memoria.** RF-todos · tamaño S (~20 min)
  - `.\gradlew :core:data:jvmTest` completo, `:core:crecimiento:jvmTest`, `:core:ui:jvmTest`, `:especialidades:clinica:jvmTest` (como
    pide `AGENTS.md`; en el árbol publicado correr los que existan), `.\gradlew :features:auth:compileKotlinJvm`, `.\gradlew
    :composeApp:compileKotlinWasmJs`.
  - Agente `reviewer`: RF por RF contra `spec.md` (qué test cubre cada uno, resultado) y el chequeo de D9 (bloqueo antes de publicar).
  - Actualizar `MEMORY.md` (qué quedó hecho, la lista de commits, pendientes) y proponer el texto para `AGENTS.md` si hay regla
    permanente. **No** tocar `docs/hc-pediatria-estado-y-plan.md` en el worktree: va con la integración.
  - Hecho cuando: todo lo anterior en verde, `git log --oneline origin/main..HEAD` lista un commit por tarea, `git status` limpio, y el
    reviewer sin hallazgos mayores abiertos.

## Prueba manual del usuario (M1 a M9) — después de T8, antes de pensar en publicar
Con `.\gradlew devWeb` **desde el worktree** (necesita su `local.properties`, ver arriba) contra el XAMPP local y la copia local de
pediatría (que debe tener el `conLicencia` actual). Hace falta un navegador con una copia local de la licencia desactualizada **a
propósito**, y el panel de DevTools → Network abierto. Se usa un médico de prueba con pediatría habilitada y un administrador de prueba.

Preparar la copia vieja en el navegador B (el del médico): entrar ahí como administrador, abrir Administración → Licencias (sincroniza la
copia), y con el interruptor **dar de baja** la licencia del médico de prueba (queda baja en el servidor *y* en la copia de B). Después, en
otro navegador o ventana privada A, entrar como administrador y **reactivar** la licencia en el servidor. B queda con la copia «baja» y el
servidor «vigente».

- **M1 (el error reportado).** En B, cerrar sesión, entrar como el médico. *Esperado:* entra (antes: «licencia vencida»). Cerrar sesión,
  volver a mirar Administración → Licencias como administrador en B: la licencia aparece vigente.
- **M2 (recarga).** Repetir la preparación, entrar al sitio ya con la sesión del médico abierta y apretar F5. *Esperado:* vuelve a la
  app (no a la pantalla de ingreso), con «Abriendo Salud 360…» no más de unos segundos.
- **M3 (copia vigente, servidor vencido).** Preparar B con la copia «vigente»; en A, **dar de baja** la licencia en el servidor. En B
  entrar como el médico. *Esperado:* no entra, aparece «Tu licencia está vencida. Comunicate con el administrador para renovarla.»;
  ni un parpadeo de la app. Repetir con F5 estando con la sesión abierta (previamente abierta antes de la baja): vuelve a la
  pantalla de ingreso.
- **M4 (sin señal).** DevTools → Network → «Offline». Con copia «vigente» y F5: entra con la sesión guardada. Con copia «baja»: no entra
  (como hoy). Al volver la señal el comportamiento es el de M1 / M3.
- **M5 (pediatría caída, turnos arriba).** Bloquear solo el dominio de pediatría (DevTools → bloquear URL) y entrar: decide la copia, sin
  quedarse pensando más de 10 s al entrar ni más de 5 s al recargar.
- **M6 (aviso por vencer).** En el panel poner el vencimiento a unos días y el «aviso desde» en ayer: entrar como el médico. *Esperado:* el
  cartel ámbar «Tu licencia está por vencer el <fecha>» sigue apareciendo.
- **M7 (cantidad de pedidos).** En DevTools → Network, filtrar `auth/perfil` contra pediatría al entrar: se esperan **dos** (la consulta
  de esta spec y el aviso de la pantalla principal; P6) y ninguno más tras el refresco del perfil. Al recargar con sesión: uno más el aviso.
- **M8 (los demás no cambian).** El administrador y la secretaria de prueba entran como siempre, sin pedidos de licencia a pediatría, y el
  panel de Licencias sigue leyendo y guardando.
- **M9 (médico sin fila de licencia, P3).** Con un médico de prueba con pediatría habilitada **sin ninguna fila** en la tabla de licencias de
  la copia local de pediatría (respaldar la fila antes y restaurarla después), entrar. *Esperado:* **entra** a la app y a su agenda (sin
  cartel de licencia vencida); al abrir la HC de pediatría de un paciente aparece el rechazo del servidor («No se pudo traer la historia
  clínica…» con el texto de licencia vencida) y no se escribe nada; si tiene la HC general, esa se abre. Con F5 vuelve a la app. Después,
  cargar su licencia vencida (fila con fecha pasada): ahora **no entra** (texto de licencia vencida).

## Integración en `hc-general` — NO hacer sin pedido del usuario
- [ ] **T9. Cherry-pick a `hc-general`.** Tamaño M (~30 min), aparte. Pasos y conflictos esperables en `plan.md`
  («Integración posterior»). Hecho cuando: `.\gradlew :core:data:jvmTest` completo y `:composeApp:compileKotlinWasmJs` en verde en
  `hc-general`; `cerrarPorLicencia` llama a `logout(token)` en `login`; `docs/hc-pediatria-estado-y-plan.md` actualizado; `MEMORY.md` al día.

## Publicación a `main` — NO es una tarea de esta spec
Requiere aprobación aparte (publica la web) y la prueba manual M1 a M9 del usuario. No hay tarea ni comando acá.

## Preguntas decididas por el usuario el 2026-11-02 (detalle completo en `spec.md`)
| # | Pregunta | Decisión |
|---|---|---|
| P1 | Sin respuesta y sin copia local | **(a) entra**, como hoy |
| P2 | Antigüedad máxima de la copia sin señal | **(a) sin límite** |
| P3 | Médico sin ninguna licencia en pediatría (403 con `licencia: null`) | **(b) solo la HC de pediatría** (distinta de la recomendación (a)): el ingreso no se bloquea; con fecha vencida/baja sigue el bloqueo de toda la app. Suma **T3b** |
| P4 | Otros 401/403 (no habilitada, no vinculado, sin permiso) | **(a) decide la copia local** |
| P5 | Orden y topes en la recarga | **(a) esperar** antes de mostrar la app, 5 s al restaurar y 10 s al iniciar sesión |
| P6 | El aviso por vencer hace su propio pedido | **(a) dejarlo** |
