# MEMORY.md — Salud 360

Memoria del proyecto entre sesiones. Resumir o borrar lo que ya no aporte; el detalle por tarea vive en
`specs/*/tasks.md` y en `git log`. Nunca datos sensibles (tokens, contraseñas, datos de pacientes, fechas de
registros de personas): el repo es público.

## Traspaso (2026-10-29)
- **Siguiente paso: tanda A (app) de la spec 002, entrega L, aprobada por el usuario y encadenada tarea por tarea. E5.1 hecha
  (2026-10-29): `HcBackend.soloLectura` (default `false`) y candado en `HcApiSync` (`marcarSucia`, `empujarYa`, `drenar`,
  `consultasDeAntecedentesPendientes`). **E5.2 hecha (2026-10-29, `0e3c461`, solo un test):** `HcApiSyncBaseMigradaTest` prueba el candado
  con una base v8 con datos de hepatología migrada por `8.sqm` (cero llamadas, base idéntica, `pendientes` igual, pediatría sigue); sigue
  E5.3. Hallazgo para la entrega E: `archivosPendientesDeConsulta` recoge los adjuntos por paciente (`consulta_id` NULL) de cualquier HC
  (fijado como límite conocido en ese test). `HcBackendsSoloLecturaTest` obliga a decidir `soloLectura` de cada backend que se
  registre (E6.5 agrega `clinica` = `true`). Ojo: `SyncEngine` no pasa por el candado (otro canal, hacia turnos, 404 hoy) y
  `AdminRepository.guardarLicencia` llama al backend sin candado hasta E5.3. La tanda S (servidor `hclinica`, rama `salud360-api`,
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
  registrar el `HcBackend` de solo lectura de clínica (E6.5) tienen que estar los candados de E5; y no pushear `main`
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
- **DEUDA, no un olvido: limpieza de datos de prueba de F3.7 sin hacer.** La base local `hc_clinica` no volvió al
  estado inicial: enfermedades "Prueba Salud360 1/2/3", la lista del médico de prueba vaciada por `UPDATE` (6 filas
  inactivas), marcas en el paciente de prueba A y en otros, pacientes "PRUEBA SALUD360 A" a "F", un perfil de prueba
  (4), el médico de prueba con el perfil 2 (el original es el 1) y totales distintos de los iniciales. Archivos fuera
  del repo en `%TEMP%\opencode\f37` (`estado-inicial.txt`, `ids-y-limpieza.txt`, `lista-medico9-antes.txt`,
  `limpieza.sql`). Se hace al final y **antes** hay que mirar en el log de Apache a qué pacientes y enfermedades
  apuntan los 9 `PUT enfermedades/pacientes/{n}/{n}` del 26 y 27 de octubre, para restaurar esas marcas. La tanda S
  usa la misma base: no mezclar su fixture con estos datos.
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
- Repos hermanos (todo local, sin push): hclinica rama `salud360-api` (último `64849c9`), hc_pediatria rama
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
  `network/hc/EnfermedadesApi`, `7.sqm` con `hc_enfermedades` (hoy la base es v9 por `8.sqm`), `repos/EnfermedadesRepository`
  y `repos/EnfermedadesControlador` (dos `StateFlow`: sección y administrar), que arma el ViewModel con
  `EnfermedadesRepository` y `PacientesHcRepository` (de Koin). Decisiones C1-C5 y E1-E12 en `tasks.md`.
- Spec 002 entrega L: servidor (tanda S, E3 y E4) implementado en hclinica y documentado en `md/API_SALUD360_CLINICA.md`, con los ajustes de E4.4;
  falta la validación final del reviewer solo si cambió algo mayor. La app (tanda A) aún sin código: `HcClinicaBackend` será de solo lectura.
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
