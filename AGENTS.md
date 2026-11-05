# AGENTS.md — Salud 360

App Kotlin Multiplatform (Android, iOS, Web/Wasm) de historia clínica + turnos. Todo el código, la doc y los
commits están en **español**: seguir igual. Contexto de fondo en `docs/ARQUITECTURA.md`, `docs/DESPLIEGUE.md` y,
sobre todo, `docs/hc-pediatria-estado-y-plan.md` (decisiones cerradas y trampas de la integración con pediatría:
leer "Cosas que hay que saber antes de tocar" antes de tocar HC/sync/API).

> **Nota sobre ramas (2026-11-03):** este archivo, `MEMORY.md` y `specs/` vienen de la rama `hc-general` (aún sin publicar). Lo que mencionan de la HC general de clínica (tests `HcClinica*`, carpeta `hcgeneral/`, migraciones `6.sqm` a `8.sqm`, lectura de `local.properties` por Gradle) **todavía no existe en `main`**: en `main` las URLs locales se pasan con `-P`. Para trabajar una HC nueva (o varias en paralelo) leé primero [`docs/trabajo-por-historia-clinica.md`](docs/trabajo-por-historia-clinica.md).

## Repo público: cuidado con datos
- `bd_test/` tiene volcados de producción (pacientes, contraseñas, credenciales de cobro). Está en `.gitignore`;
  nunca agregarlo, citarlo ni copiar datos de ahí a código, tests o docs.
- No escribir tokens en claro, contraseñas ni secrets en el repo (tampoco en docs).

## Forma de trabajar (SDD)
- Leé `MEMORY.md` al empezar y actualizalo al terminar cada tarea (breve, sin datos sensibles; lo permanente se
  propone para este archivo).
- Antes de tocar código, leé `docs/constitution.md` y la spec activa (`specs/NNN-*/`). Flujo y plantillas en la
  skill `sdd` (`.agents/skills/sdd/SKILL.md`).
- Funcionalidades: `/sdd-spec` → `/sdd-clarify` → `/sdd-plan` → `/sdd-tasks` → `/sdd-implement NNN Tn` →
  `/sdd-validate`; cambios con `/sdd-change`, estado con `/sdd-status`. O el agente `coordinator`, que delega en
  `planner`, `implementer` y `reviewer`. Cambios chicos: `/feature`.
- Varias historias clínicas en paralelo (otra PC, otra IA): seguí docs/trabajo-por-historia-clinica.md (ramas, rangos de specs, memoria por HC, archivos compartidos y migraciones).
- ✅ Siempre: tests en verde antes de dar algo por hecho; una tarea por vez.
- ⚠ Preguntá antes: dependencias nuevas, migraciones de esquema, cambios que exijan tocar las APIs Laravel.
- 🚫 Nunca: commit/push sin pedido, E2E contra producción, datos reales o tokens en el repo.

## Comandos (Windows: `.\gradlew`)
- Si Gradle dice que falta `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Web: `.\gradlew devWeb` → http://localhost:8080. Build prod: `.\gradlew releaseWeb`
  (sale en `composeApp/build/dist/wasmJs/productionExecutable`).
- URLs locales por máquina: van en `local.properties` (no se versiona), nunca en `gradle.properties`.
  Precedencia: `-P` > `local.properties` > `gradle.properties`. En esta compu (Windows + XAMPP) ya están
  cargadas (turnos y HC clínica a `localhost`), así que `.\gradlew devWeb` a secas ya funciona contra el
  XAMPP; en la Mac siguen mandando las de `gradle.properties` (MAMP `:8888`).
- Para probar contra otra URL sin tocar nada: `.\gradlew devWeb "-Psalud360.entorno.dev.turnos=<url>"`
  (comillas obligatorias en PowerShell).
- Android: `.\gradlew devAndroid` / `releaseAndroid`. iOS solo en macOS (`iosApp`, XcodeGen); en Windows los
  targets iOS se saltean solos.
- Tests: `.\gradlew :core:data:jvmTest`, `:core:crecimiento:jvmTest`, `:core:ui:jvmTest`, `:especialidades:clinica:jvmTest`
  (no hay tests en `features/*`, `composeApp` ni `:server`). Uno solo: `.\gradlew :core:data:jvmTest --tests '*LicenciaTest*'`.
- Compilación rápida de un módulo: `.\gradlew :features:hc:compileKotlinJvm` (todos los módulos KMP tienen target `jvm`).
- No hay linter/formatter configurado; estilo `kotlin.code.style=official`.
- `:server` (Ktor) existe pero **la app no habla con él**; no asumir que el sync contra él funciona. Ojo: `SyncEngine`
  igual se inicia tras cada login (`App.kt`) y cada minuto pega a `<turnos>/api/salud360/sync/push` (con filas `dirty`) o
  `sync/pull`, rutas que no existen en turnos: da 404, se ignora (queda en el log y en `EstadoSync`). Si alguien crea
  esas rutas en turnos, saldrían de golpe todas las filas `dirty` del dispositivo.

## Entorno dev/release (generado)
- `composeApp` genera `Entornos.kt` desde `gradle.properties` (`salud360.entorno.{dev,release}.*`) y los
  overrides de `local.properties` (`salud360.entorno.<env>.turnos`, `salud360.entorno.<env>.hc.<código>`).
  No editarlo a mano; para agregar una URL de HC: `salud360.entorno.<env>.hc.<especialidad>=...` en ambos entornos.
- Sin `-Pentorno`, Gradle elige `dev` si las tareas pedidas contienen `dev`/`debug`/`DevelopmentRun`, si no `release`.
  Ojo: cualquier otra tarea que compile `composeApp` (p. ej. `build`) queda contra **producción**.
- `dev` apunta a un MAMP local en `:8888` en la Mac; en esta compu lo apunta el XAMPP (`local.properties`).
  `-PdevHost=<ip>` para teléfono físico. No cambiar las URLs de `gradle.properties` para probar: usar
  `-Pentorno=release` o pasar la URL al test.
- Si cambia el entorno en un mismo dispositivo/navegador, cerrar sesión (la sesión queda guardada).

## Tests E2E contra la API real de pediatría y de la HC general
`HcPediatriaE2ETest`, `HcCarteraE2ETest`, `HcLicenciasE2ETest` (`core/data/src/jvmTest`) **se saltean solos**
si no reciben propiedades; con ellas escriben en una base real. Se corren con `--rerun` y
`-Psalud360.test.hc.pediatria=<url> -Psalud360.test.hc.token=<token médico>` (+ `.token.admin` para licencias).
Los de la HC general (`HcClinicaPacientesE2ETest`, `HcClinicaEnfermedadesE2ETest`) se saltean igual y usan
`-Psalud360.test.hc.clinica=<url> -Psalud360.test.hc.clinica.token=<token médico> -Psalud360.test.hc.dni=<documento libre>`
(+ `.nombre` y `.apellido`); dejan filas de prueba que hay que limpiar (SQL en su KDoc).
Ver el KDoc de cada test y `core/data/build.gradle.kts`. No correrlos contra producción sin que lo pidan.

## Arquitectura que no se ve en los nombres
- Especialidades declarativas: cada `especialidades/*` exporta un `EspecialidadDefinition`/`EspecialidadContribution`
  que se registra en `composeApp/src/commonMain/kotlin/com/salud360/app/AppDi.kt`; `features/hc` renderiza
  cualquier definición. Una especialidad nueva no toca base ni servidor.
- Hepatología NO es una HC: es un perfil de la HC general (`clinica`). La app normaliza el código `hepatologia` que
  informa turnos a `clinica` (`hcgeneral/HistoriasClinicas.kt`) y `8.sqm` pasó lo guardado en el dispositivo. El
  `internal val hepatologia` de `ClinicaMedica.kt` sigue porque `catalogoHcGeneral` copia de ahí: no borrarlo.
- Identidad: turnosonlinebb es el único proveedor (login `/api/salud360/auth/login`). Las APIs de HC (Laravel de
  cada especialidad, repo aparte) no emiten tokens: aceptan el de turnos. El dueño de cada dato manda (agenda y
  usuarios → turnos; HC y licencias → base de la especialidad); la base local es caché/offline.
- Agenda del médico (spec 005): se decide con `tiene_agenda` de turnos, que escribe el administrador con
  `PUT medicos/{id}/agenda` (`AgendaTurnosOnline.guardarAgenda`; la copia local cambia recién con la respuesta). `Medico.tieneTurnos`
  guarda ese dato (no «tiene horarios»); `tiene_horarios` es solo respaldo si el servidor no informa `tiene_agenda`. Las reglas de
  quién ve qué están en `AgendaDelMedico` (`core/data`). Las historias clínicas habilitadas también se guardan en turnos, con
  `PUT medicos/{id}/historias-clinicas` (lista exacta; `AgendaTurnosOnline.guardarHistoriasClinicas`, misma regla: la copia local
  cambia recién con la respuesta). El resto de los campos de la tarjeta del admin (consultorio, especialidad, teléfono, mail)
  siguen siendo solo locales.
- HC con API (hoy pediatría): `HcPediatriaBackend` + `HcApiSync` (envío diferido, ~4 s). El autoguardado dispara
  cada 600 ms: nunca llamar a la API desde ahí.
- Los datos cuyo dueño es remoto no deben quedar `dirty` en el dispositivo (ese fue un bug real en licencias).

## Base local (SQLDelight, `core/database`)
- Cambios de esquema = nueva migración `migrations/N.sqm` (siguiente número) **y** actualizar el `.sq`
  (salvo que la migración solo mueva datos, como `8.sqm`). Una base ya migrada no vuelve a una app vieja: en la web
  el driver (`DriverFactory.wasmJs.kt`) descarta las tablas si la versión guardada es más nueva que la de la app o si la
  migración falla; si es más vieja, migra. `verifyMigrations` está apagado: nadie lo controla por vos.
- Columnas nuevas **siempre al final** de la tabla: `upsertX` es `INSERT OR REPLACE INTO x VALUES ?` posicional.
- `generateAsync = true` (driver web en Web Worker); el `.wasm`/worker persistente está en
  `composeApp/src/wasmJsMain/resources/sqljs-persistente.worker.js`.

## Deploy
Push a `main` que toque `composeApp/ core/ features/ build-logic/ gradle/` → GitHub Actions compila la web
(`wasmJsBrowserDistribution`, release) y la sube por rsync al hosting. Pushear a `main` **es publicar**: confirmar antes.

## Commits
Formato del historial: `área: qué cambia, en lenguaje llano` (ej. `pacientes: ...`, `hc: ...`, `doc: ...`),
cuerpo explicando el porqué.
