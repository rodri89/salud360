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

- [ ] **T3. `AuthRepository` decide con el servidor (sin cablear aún a los flujos).** RF-1, RF-4, RF-7, RF-11, RF-12, RF-14, RF-15 · tamaño M (~30 min)
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

- [ ] **T3b. El error de la API de HC conserva su cuerpo y el backend distingue «sin licencia».** RF-10, RF-21, RF-22 · tamaño M (~25 min) · depende de T2 (y va después de T3)
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

- [ ] **T4. `restaurar` consulta al servidor antes de publicar la sesión.** RF-2, RF-5, RF-6, RF-7, RF-13, RF-15 · tamaño M (~25 min)
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

- [ ] **T5. `login` consulta al servidor antes de guardar la sesión.** RF-1, RF-5, RF-6, RF-13, RF-14, RF-15 · tamaño M (~30 min)
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

- [ ] **T6. `refrescarPerfil` y el armado de Koin.** RF-3, RF-14 · tamaño S (~20 min)
  - Tests primero (mismo archivo): `refrescarPerfil()` justo después de entrar no hace consultas nuevas y no cierra la
    sesión de un médico `Vigente` aunque la fecha local ya haya pasado (reloj del dispositivo corrido); con la memoria vencida
    (>60 s) consulta una vez; `Vencida` → cierra la sesión.
  - Código: `refrescarPerfil` pasa por `decidirLicencia`; `DataModule.kt:79` pasa `get<HcBackends>().porEspecialidad`
    como `backendsHc`.
  - Hecho cuando: `.\gradlew :core:data:jvmTest` completo en verde; `.\gradlew :composeApp:compileKotlinWasmJs` compila (el
    grafo de Koin se arma: `AuthRepository` ← `HcBackends` ← `HcApiClients`, sin ciclo); ninguna ruta de `features/*` ni
    `composeApp` cambió en `git diff --stat`.

## Tanda B — Punta a punta y cierre

- [ ] **T7. E2E contra la copia local de pediatría.** RF-5, RF-6, RF-11, RF-16 · tamaño M (~30 min)
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
