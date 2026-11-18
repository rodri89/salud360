# Trabajar una historia clínica en Salud 360 (y varias en paralelo)

Guía para una persona o una IA que arranca o retoma **una** historia clínica (HC: odontología, clínica, pediatría…) en una PC, mientras otra PC trabaja otra HC. Complementa a `AGENTS.md` (reglas del repo), `docs/constitution.md` (principios) y la skill `sdd` (`.agents/skills/sdd/SKILL.md`). No las reemplaza: si algo choca, mandan ellas.

## 1. La idea en una página

- **Una HC = una rama = un rango de specs = una memoria.** Rama `hc-<código>` (clínica sigue en `hc-general`), specs en su centena, memoria en `memoria/<código>.md`.
- **Todas siguen el mismo camino**: descubrir → spec → plan → tareas → una tarea por vez con tests primero → validación del revisor → prueba manual del usuario → producción (aprobación aparte). Es el flujo SDD de siempre; esta guía solo agrega cómo no pisarse entre HC.
- **El dueño del dato manda.** Identidad y agenda son de turnosonlinebb; la HC y las licencias son de la base de cada especialidad. La base del dispositivo es caché y modo sin señal.
- **Lo que decide el usuario no lo decide la IA**: push a `main` (publica la web), producción, dependencias nuevas, migraciones de esquema, cambios en las APIs Laravel, borrar historia. Se pregunta.

## 2. Qué leer antes de tocar (en este orden)

1. `AGENTS.md` y `docs/constitution.md`.
2. `docs/ARQUITECTURA.md` (especialidades declarativas, tablas genéricas, tipos de sección, identidad).
3. `docs/hc-pediatria-estado-y-plan.md`, sobre todo "Cosas que hay que saber antes de tocar": es el caso de una HC con API propia, lectura y escritura.
4. Esta guía y la memoria de la HC que vas a trabajar: `memoria/<código>.md` (si no existe, se crea con la plantilla del punto 8). `MEMORY.md` es lo transversal.
5. La spec activa de la HC (`specs/<centena>…/`): `spec.md`, `plan.md` y `tasks.md`.
6. Como modelos de código: `especialidades/endocrinologia` (declarativa simple), `especialidades/pediatria` (secciones a medida, con API) y, cuando esté publicada, `especialidades/clinica` (HC general con perfiles y, en su primera entrega, solo lectura).

## 3. Convenciones para trabajar en paralelo

### Ramas y worktrees
- Cada HC trabaja en su rama `hc-<código>` creada desde `origin/main`. Si una misma PC lleva dos HC, un worktree por HC (`git worktree add ..\salud360-<código> -b hc-<código> origin/main`), nunca alternar ramas en la misma carpeta con una base web a medias.
- Integrar `main` seguido (`git fetch` y `merge` o `rebase`) para no divergir. Lo que otra HC pueda necesitar (cambios en `core/*`, `features/hc`, componentes de `core/ui`) va en un **PR chico aparte a `main` antes**, no escondido dentro de la rama de la HC.
- Commits: `<área>: qué cambia, en lenguaje llano` (por ejemplo `odontologia: …`, `hc: …`, `doc: …`), con el porqué en el cuerpo. Sin commit ni push a `main` sin pedido. Pushear a `main` publica la web.
- Hoy existe `origin/hc_odontologia` (con guion bajo): un solo commit de `.gitignore`, redundante con `main`, sobre un `main` viejo (de 2026-09-22) y sin trabajo de odontología. Antes de empezar, recrearla desde `origin/main` actual (o usar `hc-odontologia`); la convención de nombre es `hc-<código>`.

### Specs: una centena por HC
| Rango | Para qué |
|---|---|
| `0xx` | Transversal, clínica y pediatría (001 a 004 existentes; siguiente libre: 006) |
| `1xx` | Odontología (101 en adelante) |
| `2xx` | La próxima HC que se sume |

Cada HC numera solo dentro de su centena. Una spec que cruza varias HC se numera en `0xx` y se avisa a las demás. `specs/README.md` lleva una fila por spec: al integrar, ante un conflicto de esa tabla se conservan las dos filas.

### Archivos compartidos (donde dos PCs chocan)
Tocalos con el cambio **mínimo y aditivo** (una línea nueva, no reordenar ni reformatear), y avisá en el PR:
- `settings.gradle.kts`: un `include(":especialidades:<nombre>")`.
- `composeApp/.../AppDi.kt`: un `import` y un elemento en la lista `especialidades`.
- `gradle.properties`: dos líneas `salud360.entorno.dev.hc.<código>=…` y `salud360.entorno.release.hc.<código>=…` (no cambiar las de otras HC ni las de turnos).
- `core/data/build.gradle.kts`: las propiedades `salud360.test.hc.…` de sus E2E.
- `DataModule` (registro del backend y del cliente de la HC) y `HcBackends`/`HcApiClients`: una entrada por HC.
- `specs/README.md` y `MEMORY.md`.

### Migraciones de la base local: la numeración es global
La base local es una sola (SQLDelight, `core/database/.../migrations/N.sqm`). Dos ramas que crean el mismo `N.sqm` rompen los datos de los dispositivos, y en la web el driver descarta la base si la versión guardada es más nueva que la de la app. Por eso:
- Una HC declarativa **no debería necesitar migración**: usa las tablas genéricas (`seccion_valor`, `registro_clinico`, `paciente_extra`, `laboratorio`, `archivo`…).
- Si hace falta una, **se pregunta al usuario antes** y se reserva el número mirando `origin/main` y todas las ramas `hc-*` (`git branch -a`, `git ls-tree`). Hoy `main` llega a `5.sqm` (esquema v6) y `hc-general` agrega `6.sqm` a `8.sqm` (v9). Actualizá este dato cuando cambie.
- Columnas nuevas siempre al final de la tabla (`upsertX` es posicional) y el `.sq` actualizado junto con la migración.

### Entorno de cada PC
- Cada PC tiene su XAMPP/MAMP y sus bases de prueba, que son copias con datos reales: **nunca al repo** (`bd_test/` está ignorado). Los tokens y contraseñas de prueba no se escriben en ningún archivo del repo.
- Las URLs locales van por `-P` (en `main`); cuando se publique `hc-general`, también por `local.properties`.
- Una base web de esquema nuevo y una app vieja en el **mismo puerto del navegador** se pisan (el almacenamiento es por origen). Si dos ramas tienen esquemas distintos, servir cada una en un puerto distinto o usar otro perfil del navegador.
- Después de tocar `.env` o `config/` en una copia servida de Laravel: `php artisan config:cache`. Las copias servidas comparten el entorno de Apache.

## 4. Empezar una HC nueva (por ejemplo odontología)

Antes de escribir la primera spec hay que contestar, con el usuario (de a una pregunta):
1. ¿Existe un Laravel y una base de esa especialidad? ¿Repo, rama (`salud360-api`), URL de producción, tablas que la web usa hoy?
2. ¿Va **con API propia** (como pediatría y clínica) o primero **solo declarativa y local** (como el resto de las especialidades)? Recomendado: declarativa primero, API después.
3. Código de la HC (`odontologia`): lo habilita turnos en `salud360_medico_hc` (Administración → Médico → "Historias clínicas Salud 360"); la app no la deduce de la especialidad (el fallback se eliminó), solo la ve el médico que la tiene habilitada.
4. Roles: la secretaria no ve ninguna historia clínica (decisión del 2026-11-09, solo ve turnos). ¿El médico con y sin agenda?
5. ¿Cobra licencia como pediatría (`medico_licencias`)? ¿Cartera de pacientes y vínculo con turnos (`paciente_id_tobb`)?
6. ¿Qué pasa sin señal y qué se sincroniza?
7. Alcance de la primera entrega: **solo lectura primero** (patrón de clínica: mostrar lo de la web sin escribir, con candados) o lectura y escritura (pediatría). Recomendado: lectura primero, escritura en una entrega aparte con su spec.

Fases sugeridas, cada una con su puerta (no se pasa a la siguiente sin aprobación explícita):
| Fase | Qué | Puerta |
|---|---|---|
| 0 | Descubrimiento y spec (sin código) | usuario aprueba spec y plan |
| 1 | Definición declarativa de la especialidad: módulo `especialidades/<nombre>`, `EspecialidadContribution`, alta en `AppDi.kt` y `settings.gradle.kts`; se prueba en `.\gradlew devWeb` | tests en verde y prueba visual |
| 2 | API en el Laravel de la HC (**repo aparte**, no se implementa acá; se lista como dependencia con endpoint y orden de despliegue; documentar en `md/API_SALUD360_<HC>.md` del repo hermano) | pruebas del servidor y `GET` sin token |
| 3 | Backend en la app (`HcBackend`, cliente `HcApiClient`, registro en `DataModule`) | tests con servidor falso (`HttpServer` local) |
| 4 | E2E contra la copia local de la API (se saltea solo sin propiedades) | corrida verde y base limpiada por id con conteos antes/después |
| 5 | Producción | **aprobación aparte**, a mano, nunca con tests automáticos |

## 5. Reglas que costaron caro (resumen; el detalle está en `docs/hc-pediatria-estado-y-plan.md`)

- **Nunca pisar un dato con vacíos ni inventar una respuesta** que el médico no dio. Si la web deja de mandar algo, se conserva y se marca; no se borra.
- **No escribir `medico_infos` desde la API**: guarda lo que el médico mira en la web.
- **El autoguardado dispara cada 600 ms**: nunca llamar a la API desde ahí; para eso está el envío diferido (`HcApiSync`, ~4 s).
- **Los datos cuyo dueño es remoto no quedan `dirty`** en el dispositivo.
- **Koin borra los genéricos**: envolver `Map<String, …>` en una clase propia (`HcBackends`, `HcApiClients`).
- **Un error que no se ve es un error**: si la carga falla, la pantalla tiene que decirlo; no mostrarlo igual que "sin datos".
- **Opciones de tres estados**: en varias tablas legacy `2` = "sin cargar"; escribir `0` inventa un "no". Mirar cada tabla.
- **Probar contra la copia local, no contra producción.** Los E2E hacen `skip` sin propiedades; los datos de prueba se limpian por id, y se comparan conteos y `CHECKSUM TABLE` antes y después.
- **Servidores**: no correr `php artisan migrate` en producción sin revisar; `config:cache` en las copias servidas; pushear el repo hermano antes de subir por FTP; `APP_DEBUG=false` en producción.
- **`SyncEngine`** pega a rutas de turnos que no existen (404 ignorado); no asumir que el sync contra `:server` funciona.
- Gradle necesita `JAVA_HOME` (JBR de Android Studio) y se queda sin memoria con el navegador abierto.

## 6. Cómo trabaja la IA (OpenCode)

- Agentes: `coordinator` (dirige el flujo y habla con el usuario), `planner` (spec, plan, tareas), `implementer` (una tarea, tests primero), `reviewer` (QA de la spec y validación RF por RF). Comandos: `/sdd-spec`, `/sdd-clarify`, `/sdd-plan`, `/sdd-tasks`, `/sdd-implement NNN Tn`, `/sdd-validate`, `/sdd-change`, `/sdd-status`; cambios chicos: `/feature`.
- Una tarea por vez; tests en verde antes de darla por hecha; se marca en `tasks.md` y se anota el resultado ahí.
- Los subagentes no ven la conversación: pasarles el pedido, las decisiones y las rutas de los archivos.
- Al terminar cada tarea: actualizar `memoria/<código>.md` (breve, sin datos sensibles). Lo permanente se propone para `AGENTS.md` o esta guía en un PR chico.

## 7. Cierre de cada sesión

1. Tests del módulo tocado en verde (o decir cuáles no corrieron y por qué).
2. Commit local en la rama de la HC (sin push a `main`).
3. `memoria/<código>.md` al día: estado, siguiente paso exacto, pendientes y trampas nuevas.
4. Si cambiaste un archivo compartido o la numeración de migraciones, anotalo en el PR y en la memoria.
5. Resumen al usuario: qué se hizo, qué hay que probar a mano, qué sigue.

## 8. Plantilla de `memoria/<código>.md`

```
# Memoria — <HC> (<código>)
Rama: hc-<código> · Specs: <centena> · Última actualización: AAAA-MM-DD

## Estado
- Spec activa y fase (0 a 5 de la guía).
- Última tarea hecha y qué falta.

## Siguiente paso
- Una frase concreta que alcance para retomar sin la conversación.

## Pendientes
- …

## Decisiones (y por qué)
- …

## Entorno de esta PC
- Cómo se levanta (comando, puertos, URLs), sin credenciales ni tokens.

## Trampas encontradas
- …
```

## 9. Prompt de arranque para la IA en la otra PC

Pegar tal cual en la sesión nueva, completando lo que está entre `<>`:

```
Vas a trabajar la historia clínica de <especialidad> (código <código>) en Salud 360, en la rama hc-<código>.
Hay otra PC trabajando otra HC en paralelo, así que seguí las convenciones de docs/trabajo-por-historia-clinica.md.
Antes de tocar nada leé, en orden: AGENTS.md, docs/constitution.md, docs/ARQUITECTURA.md,
docs/trabajo-por-historia-clinica.md y memoria/<código>.md (si no existe, crealo con la plantilla de la guía).
Trabajá con el flujo SDD (skill sdd): no pases de fase sin mi aprobación, una tarea por vez, tests primero.
No hagas push ni commit a main, no toques archivos de otra HC, no crees migraciones .sqm sin preguntarme,
y nunca guardes datos reales, tokens ni contraseñas en el repo. Empezá diciéndome en qué fase estamos
y cuál es el siguiente paso según la memoria.
```

## 10. Historial de esta guía

- 2026-11-03: primera versión (clínica y otra HC en paralelo, sin código de odontología todavía).
