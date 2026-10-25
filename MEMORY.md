# MEMORY.md — Salud 360

Memoria del proyecto entre sesiones. Resumir o borrar lo que ya no aporte; el detalle por tarea vive en
`specs/*/tasks.md` y en `git log`. Nunca datos sensibles (tokens, contraseñas, datos de pacientes, fechas de
registros de personas): el repo es público.

## Traspaso (2026-10-22)
- Spec 001 (perfiles configurables) y parte 1 de la 002 (pacientes y cartera): implementadas y validadas. Faltan las
  pruebas manuales del usuario de varias tandas (001: C, C2 a C7; 002: E2, RF-10 y RF-14) y el resto de la 002
  (envío de consultas).
- Spec 003 (enfermedades): plan aprobado. Tanda F1 (servidor hclinica) completa y validada. Tanda F2 completa
  (F2.1 a F2.10 hechas y commiteadas; F2.7 = migración `7.sqm` / `hc_enfermedades`, `EnfermedadesRepository` y borrado en
  `AuthRepository`; F2.8 = `EnfermedadesControlador`, `DataModule` y E12, la guardia contra respuestas lentas tras el
  `logout`; F2.9 = "Enfermedades" CUSTOM en `catalogoHcGeneral`, sin renderer hasta F3.4; F2.10 = E2E
  `HcClinicaEnfermedadesE2ETest` contra la copia local; F2.11 = ajustes de la validación del reviewer; F2.12 = tests de enfermedades estables bajo carga; el último
  commit de código está en `git log`). La tanda F2 se validó (aprobada con observaciones, 2026-10-23) y las observaciones
  están cerradas. Tanda F3 (pantallas, 10 tareas, decisiones P1 a P9 en `tasks.md`) aprobada el 2026-10-23;
  F3.1 hecha (`CasillaElegible` en `core/ui/components`, sin tests nuevos: reutiliza `ColoresSiNo`); F3.2 hecha
  (`lineaDeCandidata` en `core/data/hcgeneral/FichaCandidata.kt` y `ElegirFichaPaciente` en `features/hc/secciones`);
  F3.3 hecha (`hcgeneral/AccesoEnfermedades.kt`: `medicoParaEnfermedades(sesión)`, `consultaEditable`, `visibleParaSesion`;
  `EnfermedadesViewModel` en `features/hc`; `ConsultaViewModel` recibe `AuthRepository` y filtra la pestaña en `pestanias`;
  su `medicoId` sigue privado); F3.4a hecha (`repos/PanelEnfermedades.kt`: `panelDeSeccion(ui, editable)` con
  `Cargando`/`ElegirFicha`/`Motivo`/`SinCopia`/`Contenido` y los `TEXTO_*`; "Reintentar" con motivos 5, 7, 8 y 9, en sin
  copia y en solo lectura; las casillas no tocables salen de `ui.marcado.puedeTocar`); F3.4b hecha
  (`features/hc/secciones/EnfermedadesSeccion.kt`, sin registrar todavía: el perfil con la sección sigue mostrando "Sección
  no disponible" hasta F3.4c; "Administrar" es una lambda vacía hasta F3.5); F3.4c hecha (renderer `enfermedades`
  registrado en `hcGeneralContribution`, ícono propio `HealthAndSafety` en `Iconos.kt`; el cartel "Sección no disponible" ya no
  sale para esa sección); F3.5 hecha (`repos/PanelAdministrar.kt`: `panelDeAdministrar(ui)` con `Cargando`/`SoloAviso`/`Contenido`;
  `AdministrarEnfermedadesDialog` modal en `features/hc/secciones`, abierto por "Administrar enfermedades"); ajuste de F3.5
  (2026-10-23, decisión del usuario): la ventana se cierra sola si la sección deja de poder administrar por conexión o por un
  motivo 1 a 5 (`debeCerrarAdministrar` en `PanelAdministrar.kt`; con la sección cargando o solo `editable` falso no cierra, RF-58).
  F3.6 hecha ("Datos" en `ConsultaScreen`: `enfermedadesEnDatosDeConsulta` en `AccesoEnfermedades.kt` limita a HC general
  (`porPerfil`) y al médico propio, pediatría no cambia; `ConsultaViewModel.enfermedadesEnDatos(ui)` y
  `secciones/EnfermedadesEnDatos.kt`, dibujado en los dos lugares de "Datos"; solo lectura, comparte el controlador con la
  sección y su `abrir()` puede crear la ficha en la web, P7-A). F3.8 hecha (RF-59: `RevocaSesion` en `repos/`,
  `TurnosOnlineClient.cerrarSesion(token)`, `HcApiClient.salir(token)`, `AuthRepository.logout(tokenARevocar)` revoca en
  segundo plano con `limite` de 3 s y sin demorar el cierre; `DataModule` cablea turnos + cada HC). El reviewer validó la
  tanda F3 (aprobada con observaciones, 2026-10-24) y F3.9 cerró los cuatro puntos que el usuario aprobó (ventana de administrar
  con `remember` y `cerrarAdministrar()` al salir de la composición, tarjeta de `ElegirFichaPaciente` fusionada para el lector,
  `FlowRow` en "Reintentar"/"Administrar", DNI de `FichaCandidataTest` en `99000xxx`). El código de la tanda F3 está completo y
  validado: solo falta F3.7 (prueba manual del usuario, incluida la tanda M8: cierre real contra la copia local; M3 (9), M5 (5, 6)
  son pasos nuevos de F3.9). Quedan los otros menores del reviewer, sin tarea.
- F3.7 preparada (2026-10-25); estado en `%TEMP%\opencode\f37` (fuera del repo). El `devWeb` no estaba levantado: arrancarlo.
- Cómo seguir: leer `AGENTS.md`, esta memoria y `specs/003-hc-enfermedades/tasks.md`; una tarea por vez con el
  implementador, tests primero, validación del revisor al cerrar cada tanda; commits locales como rodri89, sin push.
- Repos hermanos (todo local, sin push): hclinica rama `salud360-api` (último `39d13cf`), hc_pediatria rama
  `salud360-api` (`72196ce`), turnosonlinebb rama `salud360-hc-general` (`0077ed4`).
- Entorno: `.\gradlew devWeb` a secas apunta al XAMPP (`local.properties`). Al 2026-10-22 el devWeb de antes sigue
  vivo (node en :8080, desde el 13/10), Apache y MySQL del XAMPP también (login de turnos da 422 como se espera).
  Tokens de prueba de pediatría: archivo local fuera del repo (`%TEMP%\opencode\respaldos\tokens-prueba-pediatria-local.txt`).
- Las contraseñas de prueba locales no están en ningún archivo: la sesión nueva se las pide al usuario.
- Menores: ofrecer actualizar el plugin `@xtruder/opencode-claude-max-plugin` (hay versión nueva); revisar si
  `DibujoCanvas.kt` lee valores con `ctx.valor` (el bug de Child-Pugh); `ColumnasEnfermedadesTest` repite
  `SiNoVacioTest` (aceptado, E9).

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
- Spec 003: F1 (hclinica: `EnfermedadesReglas`, `EnfermedadesService`, `EnfermedadController`, 5 rutas, `auth/salir`
  en hclinica y pediatría) y F2.1 a F2.10 (`hcgeneral/NombreEnfermedad`, `EnfermedadesSeccion`, `MotivosEnfermedades`,
  `MarcadoEnfermedades`, `AdministrarEnfermedades`; `network/hc/EnfermedadesDtos` y `EnfermedadesApi`; base v8 con
  `hc_enfermedades` y `repos/EnfermedadesRepository`; `repos/EnfermedadesControlador` con dos `StateFlow`: sección y
  administrar). Decisiones C1-C5 y E1-E12 en `tasks.md`. El ViewModel de F3 arma el controlador con
  `EnfermedadesRepository` y `PacientesHcRepository` (de Koin) y su `viewModelScope`.
- hclinica: MySQL compara nombres sin tildes (`utf8mb4_unicode_ci`) y `validar` no; el `unique` lo frena y la API
  contesta 422 igual. En esa MySQL `ß` = `ss` y `ａ` = `a` (sirven para probar el choque). La app (`claveDeNombre`)
  ignora tildes y ñ = n para avisar antes.
- `HcClinicaEnfermedadesE2ETest` deja en `hc_clinica` dos enfermedades "Prueba Salud360 <n>", sus filas en
  `medico_enfermedades`/`paciente_enfermedades`, la ficha del DNI de prueba (desde 99000090) y su `medico_pacientes`:
  limpiar con el SQL del KDoc del test (`DELETE` puntuales en ese orden; verificar antes que el DNI esté libre) y vaciar
  `salud360_token_cache`. Una corrida con datos viejos sin limpiar falla a propósito.
- Los tokens de prueba de pediatría se sacaron del repo (`1c7ad61`, placeholders) pero siguen en el historial (ya en
  `origin/main`). Rotados el 2026-10-20 (solo local).
- El E2E de pediatría deja en `hc_pediatrica` una consulta de baja (y sus filas) y dos imágenes en
  `public/img/salud360` por corrida: borrarlas por id. Orden de las FK al borrar: fotos → padres, `internaciones`
  antes que `antecedentes_personales`, `consultas` al final. Ya hay 5 consultas de baja de corridas viejas.
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
- Gradle necesita `JAVA_HOME` (JBR de Android Studio) y se queda sin memoria si el navegador está abierto.
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
- OpenCode no habla con Anthropic por API key: el plugin `@xtruder/opencode-claude-max-plugin` (provider
  `anthropic-sdk`) usa el OAuth de Claude Code de `~/.claude/.credentials.json`. Si ese archivo se vacía (la sesión
  OAuth venció, ~8 h de vida del refresh), el error es "Could not resolve authentication method" y no es de red:
  se arregla con `claude auth login` (abre el navegador y la página muestra un código que hay que pegar).
  Diagnóstico rápido: `claude auth status` → `loggedIn: false`.
