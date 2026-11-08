# Plan 005 — La HC general escribe sus consultas en la base de la web

Estado: **borrador del planificador (2026-11-04)**, para revisar con el usuario antes de generar la tanda siguiente. Spec
aprobada el 2026-11-04 (`spec.md`, RF-1 a RF-70, decisiones D1 a D27, sin dudas abiertas). Apoyo técnico (rutas provisorias,
verificación en el código con archivo y línea, enmienda a la 004): `notas-para-el-plan.md`. Rama `hc-general` de este repo; servidor
en `C:\Users\flor\rebonline\hclinica` (rama `salud360-api`); producción **fuera** de este plan (cada etapa de producción, «E9 de la
escritura», se aprueba aparte).

Convención de este documento: «**Toca API Laravel: sí**» = autorizado en D3 **solo para la copia local** (producción, aparte y con
respaldo probado, D4). «**Migración: pregunta antes — ya aprobada en D26/D8**» = el implementador no pregunta si crear la migración,
pero sí **vuelve a preguntar** si el número ya lo tomó otra rama.

## Resumen
1. **Dónde vive el envío.** Un orquestador nuevo para la HC general, `HcClinicaEnvio`, con su cola, su espera de 4 s, su reintento
   y sus estados. `HcApiSync` (pediatría) **no se toca** salvo para ignorar los backends que declaran `envioPropio` (RF-5).
2. **El backend de clínica pasa de solo lectura a lectura y escritura**: `HcClinicaBackend` recibe el cliente completo, declara
   `soloLectura = false` y `envioPropio = true`. La API de lectura queda como está; se suma una API de escritura aparte.
3. **Histórico vs propio (D26):** una columna al final de `consulta` (`propia`, 0 por omisión: todo lo existente al migrar es
   histórico). Los datos por paciente se clasifican **por la consulta a la que están asociados** (RF-68); no hace falta otra marca.
4. **Servidor:** controlador de escritura **nuevo y separado** del de lectura, servicio de escritura, reglas puras con phpunit y un
   registro propio de lo recibido (`salud360_envios`, creado por la API) que da la repetibilidad (RF-15) y el «esta consulta la
   creó la app» (RF-52). Ninguna tabla de la web recibe columnas.
5. **Orden:** E0 (limpieza, respaldo, scripts) → T9 de la 004 (**precondición, pedido aparte**) → servidor local de E1 → app de E1 →
   cierre de E1 (reviewer, prueba manual, limpieza). **E2, E3 y E4 quedaron detalladas y fueron aprobadas por el usuario el 2026-11-07 (Q-A sí; orden alternativo Q-B; las demás preguntas siguen abiertas)**
   (T48 a T113 en `tasks.md`; contrato y decisiones al final de este documento); E5 se detalla al llegar, con aprobación aparte.
6. **Licencia en el ingreso (D22/D25):** `HcClinicaBackend.licenciaDelServidor()` + la enmienda a `decidirLicencia` («se bloquea
   solo si todas las HC con licencia contestan vencida con fecha»). Depende de la T9 y de la enmienda de texto a la 004.
7. **Panel de licencias sin regresión desde E1 (RF-66):** `HcBackend.administraLicencia` (clínica `false` hasta E5).
8. **Riesgo del motor viejo cerrado (decisión de plan):** `SyncEngine` deja de empujar las tablas clínicas; no se apaga entero.
9. **Disparadores de reintento (RF-57):** abrir la app e iniciar sesión en código común; recuperar la conexión con un observador por
   plataforma (web, Android, iOS). Todos entran por la espera de 4 s; además, un reintento periódico acotado (RF-29).
10. **Dos migraciones, cada una con su número:** la marca de E1 (probablemente `9.sqm`, esquema v10) y el identificador de la web de
    E3 (probablemente `10.sqm`, v11). Procedimiento de reserva más abajo.
11. **Pruebas:** phpunit del repo hermano para las reglas puras (cuidado con el printer roto de XAMPP: ver `MEMORY.md`), scripts
    contra una base descartable para el servicio con base, `jvmTest` con servidor falso para la app, E2E contra la copia local que se
    saltea sin propiedades, y lista manual en `devWeb` para lo de pantalla.
12. **Sin conexión:** todo se guarda primero en el dispositivo; el envío es diferido, repetible y reconcilia por el identificador de la
    web (consulta y filas hijas) sin duplicar.
13. **Tests de la L que cambian por diseño:** los que fijan «clínica no escribe» (solo lectura, candado, base migrada, módulo Koin) se
    reemplazan por los de escritura; la lista está en «Tests de la L que se reescriben».
14. **Nada de esto se publica** hasta cerrar E1 a E3 (D23) y con el servidor ya en producción (aprobación aparte).
15. **Riesgos principales:** RAM de esta máquina, `throttle` de 60 por minuto por IP, `SyncEngine`, la T9 de la 004, el registro de
    repetibilidad (si se pierde, duplica), PHP 7.3 de 32 bits y base local sin modo estricto.

## Qué se verificó contra el código (2026-11-04) y contradicciones con la spec
Leído, sin ejecutar nada contra bases: `HcApiSync`, `HcBackend`, `HcClinicaBackend`, `HcPediatriaBackend`, `HcRepository`,
`AdminRepository`, `DataModule`, `SyncEngine`/`SyncAdapters`, `App.kt`, `ConsultaViewModel`, `HistoriaClinica.sq`, migraciones 1 a 8,
y en hclinica `routes/api.php`, `Salud360Controller`, `ConsultaController`, `ConsultasService`, `ColumnasLegacy`, `TobbAuthService`,
`Salud360Cors`, `Kernel`, `tests/Unit`. Confirmado lo de `notas-para-el-plan.md`. Hallazgos nuevos del plan:

| # | Hallazgo | Consecuencia |
|---|---|---|
| 1 | La CORS de la API solo permite los encabezados `Authorization, Content-Type, Accept, X-Requested-With, X-Salud360-Token` | La clave de repetibilidad va **en el cuerpo**, no en un encabezado nuevo (evita tocar CORS) |
| 2 | `AdminRepository.destinosDeLicencia` = backends con `soloLectura = false`: al pasar clínica a `false` entraría con el `guardarLicencia` por defecto (`false`) | Hace falta `administraLicencia` (RF-66) **en la misma tarea** que abre el candado |
| 3 | `SyncEngine.iniciarPeriodico` arranca en `App.kt` con cada sesión y sus adaptadores incluyen las ocho tablas clínicas | Se cierra el riesgo excluyéndolas (decisión P2) |
| 4 | No existe ninguna abstracción de conectividad en el repo | «Recuperar la conexión» es trabajo nuevo por plataforma (RF-57, tal como dice la spec) |
| 5 | `drenar` manda registros y antecedentes **por paciente y especialidad**, sin mirar a qué consulta pertenecen | El envío de clínica filtra por consulta propia (RF-68); por eso es un orquestador aparte |
| 6 | El controlador de lectura declara «no hay rutas que escriban» y el grep de la L lo comprueba | La escritura va en un controlador y un servicio **nuevos**, para no invalidar esa garantía |
| 7 | Nada de lo que escribe la app puede ser atómico con la respuesta del servidor | RF-61 se cumple con repetibilidad (D21) + una reconciliación local en una sola transacción (decisión P8) |

**Contradicciones con la spec:** ninguna que impida cumplirla. Tres matices (la spec no se cambia):
- RF-61 pide «registrar la confirmación y el vínculo de modo que un corte no deje lo enviado como pendiente ni duplicado». Entre «el
  servidor confirmó» y «lo anoté» siempre hay una ventana; se cierra porque el reenvío es repetible (RF-15): el peor caso es un envío
  repetido que el servidor contesta igual, sin duplicar.
- RF-57 pide los tres disparadores «en cada plataforma». iOS solo compila en macOS: su observador se escribe pero **solo se verifica en
  la Mac** (tarea marcada «a mano, en macOS»).
- La verificación de RF-17 (la web abre la consulta creada con los perfiles 1, 2, 3, 4 y 9) necesita médicos locales con cada perfil;
  si la copia local no los tiene, se arman con datos «PRUEBA SALUD360» (pregunta al usuario, abajo).

## Módulos y archivos (app, este repo)
Todo lo de lógica va en `commonMain` como funciones puras, sin Compose ni red, con el reloj como parámetro (constitución, punto 4).

### `core/model`
| Archivo | Cambio | RF |
|---|---|---|
| `hc/Consulta.kt` | Campo `propia: Boolean = false` **al final** del `data class` | RF-8, RF-62, RF-68 |
| `hc/EstadoEnvio.kt` (nuevo) | `sealed interface EstadoEnvio`: `Pendiente`, `EnEspera(motivo)`, `NoSePudoEnviar(motivo)`, `Descartado`; `CausaEnEspera` | RF-6, RF-30 |

### `core/database`
| Archivo | Cambio | RF |
|---|---|---|
| `migrations/9.sqm` (número a reservar) | `ALTER TABLE consulta ADD COLUMN propia INTEGER NOT NULL DEFAULT 0;` | RF-8 |
| `HistoriaClinica.sq` | `propia` **al final** de `consulta`; `consultaAbiertaPropia` (paciente, médico, especialidad, tipo, `propia = 1`, `ABIERTA`); `consultasPropiasConPendiente`; `datosPorPacienteDeConsulta…` (registros, antecedentes, laboratorio, pendientes cuyo `consulta_id` es de una propia); `marcarPropia(id)` | RF-8, RF-11, RF-68 |
| `migrations/10.sqm` (E3, número a reservar) | `ALTER TABLE laboratorio ADD COLUMN remoto_id TEXT NOT NULL DEFAULT '';` y lo mismo en `pendiente`; `.sq` con la columna **al final** | RF-41, RF-43 |

### `core/data` (la mayor parte del trabajo)
| Archivo | Cambio | RF |
|---|---|---|
| `repos/HcBackend.kt` | `val envioPropio: Boolean get() = false` y `val administraLicencia: Boolean get() = true` | RF-5, RF-66 |
| `sync/HcApiSync.kt` | Ignora (no envía, no cuenta) los backends con `envioPropio`, igual que ya ignora los de solo lectura; **ningún otro cambio** | RF-5 |
| `sync/SyncAdapters.kt` / `SyncEngine.kt` | `todos(db)` deja afuera las tablas clínicas de contenido (`CONSULTAS`, `SECCION_VALORES`, `EXAMEN_FISICO`, `ANTECEDENTES`, `REGISTROS`, `LABORATORIOS`, `ARCHIVOS`, `PENDIENTES`) | NFR riesgo |
| `repos/AdminRepository.kt` | `destinosDeLicencia` = `!soloLectura && administraLicencia` | RF-66 |
| `repos/HcClinicaBackend.kt` | `soloLectura = false`, `envioPropio = true`, `administraLicencia = false`; recibe además `ClinicaEscrituraApi`; `licenciaDelServidor()`; la lectura no cambia salvo `aplicarLista`/`traerConsulta` para las propias (T26) | RF-1, RF-33, RF-58 |
| `network/hc/ClinicaEscrituraApi.kt` + `HcClinicaEscrituraDtos.kt` (nuevos) | Interfaz y remota sobre `HcApiClient` completo: `crear`, `secciones`, `examen`, `cerrar`, `baja` (E1); `formularios`, `antecedentes`, `registros` (E2); `laboratorio`, `peso`, `pendientes` (E3); `adjuntos` (E4) | RF-12 a RF-26 |
| `network/hc/HcApiSoloLectura.kt` | **Sigue** para la API de lectura (no se borra) | — |
| `hcgeneral/OrigenDeConsulta.kt` (nuevo, puro) | `propia` / `histórico` / `web`; agrupación de la lista (propia abierta → «Consulta en curso») | RF-8, RF-62, RF-64 |
| `hcgeneral/SeccionesEnviables.kt` (nuevo, puro) | Qué secciones y campos se envían en esta versión (por subentrega), cuáles la web no guarda (D9) y qué se ofrece | RF-3, RF-4 |
| `hcgeneral/ClasificacionDeEnvio.kt` (nuevo, puro) | De un error o de un estado del médico a `EnEspera`/`NoSePudoEnviar`/reintentable; precedencia de RF-19; texto con el nombre de la HC | RF-6, RF-19, RF-30 |
| `hcgeneral/PlanDeEnvio.kt` (nuevo, puro) | Qué viaja, en qué orden y qué se queda: solo lo tocado, vaciado a propósito, antecedentes desactivados, datos por paciente de la consulta propia, cierre al final | RF-9, RF-56, RF-60, RF-68, RF-69 |
| `hcgeneral/CambioReal.kt` (nuevo, puro) | `cambioReal(anterior, nuevo)`: un vacío sobre «nada» no es un cambio (no marca sucio) | RF-69 |
| `hcgeneral/HistoricoDelDispositivo.kt` (nuevo, puro) | `puedeEnviarse`, `seccionesFaltantes`, `contenidoQueNoViaja`, `esDelDueño` | RF-10, RF-63, RF-67 |
| `hcgeneral/AvisoDeAbierta.kt` (nuevo, puro) | «Dato fresco» (< 1 min, reloj inyectado), nombre de quien abrió, solo abiertas de la web (no propias de otro tipo) | RF-18, RF-59 |
| `hcgeneral/PendientesTexto.kt` (nuevo, puro) | Unir pendientes de la consulta en un texto (uno por línea) y reconocer si el texto de la web es el enviado (huella) | RF-43 |
| `hcgeneral/PrecedenciaDeBaja.kt` (parte de `ClasificacionDeEnvio`) | paciente de baja → consulta de baja → otro rechazo | RF-19, RF-33 |
| `sync/HcClinicaEnvio.kt` (nuevo) | Orquestador: `Mutex` por consulta (RF-54), espera de 4 s con reloj inyectado (RF-28), orden y parcial (RF-32, RF-56), cambios en vuelo (RF-55), reintentos 15/60/300 s y periódico acotado (RF-29), disparadores (RF-57), sesión/médico (RF-50), estados, contador (RF-31) | RF-27 a RF-32, RF-50 a RF-57, RF-61 |
| `sync/ObservadorDeConexion.kt` + `expect/actual` por plataforma (nuevo) | Flujo «hay red» (web: evento `online`; Android: `ConnectivityManager`; iOS: `NWPathMonitor`) | RF-57 |
| `repos/HcRepository.kt` | `abrirConsulta` crea `propia = 1` si el backend envía y reutiliza `consultaAbiertaPropia`; guardas por «de la web» siguen por prefijo `hcc-`; cerrar/reabrir/anular con estado de envío; `cambioReal` en `guardarValor`/`guardarSeccion` para clínica; reasignar la consulta al editar un dato por paciente del histórico; `enviarHistorico(id)` | RF-11, RF-22 a RF-26, RF-68, RF-69 |
| `repos/AuthRepository.kt` | (precondición T9 de la 004 + enmienda D22/D25): `decidirLicencia` por HC, regla «todas vencidas» | RF-58 |
| `repos/MotivoHc` / `hcgeneral/MotivoHc.kt` | Motivos «En espera» que nombran la HC (licencia vencida de <HC>, etc.) | RF-6 |
| `DataModule.kt` | `HcClinicaBackend(get(), cliente, pacientes, escritura)` con el `HcApiClient` completo; `HcClinicaEnvio` como `single`; `ObservadorDeConexion` | RF-1 |

### `features/hc`, `features/auth`, `especialidades/clinica`, `composeApp`
| Archivo | Cambio | RF |
|---|---|---|
| `ConsultaViewModel.kt` | `soloLecturaWeb` solo para `hcc-…`/de la web; en la propia: `HcClinicaEnvio.marcarSucia` en vez de `HcApiSync`; estado de envío en `ConsultaUi`; nada llama a la API desde el autoguardado | RF-2, RF-27, RF-62 |
| `ConsultaScreen.kt` / `ConsultaDeLaWeb.kt` | Marcas «Falta enviar», «En espera», «No se pudo enviar» con «Reintentar»/«Descartar», «Descartado…», «No se envía a la web», «Esta sección todavía no se envía a la web», «Enviando el cierre…» | RF-3, RF-4, RF-24, RF-30, RF-55 |
| `HistoriaClinicaScreen.kt` / `HistoriaClinicaViewModel.kt` | Grupos «Consulta en curso» (propias abiertas), «Solo en este dispositivo» (histórico) con «Enviar a la web» y el diálogo de confirmación, «Abierta en la web», contador de pendientes, aviso previo de abierta | RF-8, RF-10, RF-18, RF-31, RF-62, RF-64, RF-65 |
| `secciones/ElegirFichaPaciente.kt` | Se reutiliza desde la consulta «En espera» (sin cambios de contenido) | RF-13 |
| `features/auth` | Texto «licencia vencida de <HC>» (enmienda 004 RF-18) | RF-58 |
| `especialidades/clinica/ClinicaMedica.kt` y `LecturaClinicaCatalogoTest.kt` | Sacar del catálogo de lo que se envía: casilla `tabaquismo`, FC y CA del examen, diagnósticos, audio y «antecedentes personales» (D9); es un **cambio de requisitos sobre la 001** | RF-3 |
| `composeApp/.../App.kt`, `MainShell.kt`, `AppDi.kt` | Disparar `alAbrirOIniciar()` con la sesión; contador de pendientes de clínica; registrar el observador | RF-57, RF-31 |
| `composeApp` wasm/Android/iOS | Los `actual` del observador de conexión | RF-57 |

## Migraciones locales (dos, cada una con su número)
**Pregunta antes: ya aprobada en D26 (E1) y D8 (E3).** Para cada una:
1. **Reserva del número, justo antes de crearla** (no antes): `git fetch`; mirar `core/database/.../migrations/` en `origin/main`
   (hoy llega a `5.sqm`) y en **todas** las ramas (`git branch -a`; `git ls-tree -r origin/<rama> --name-only` por cada `hc-*`;
   `hc-general` llega a `8.sqm`). El número es el siguiente al mayor de todos. Con el estado de hoy serían `9.sqm` (E1, esquema
   v10) y `10.sqm` (E3, v11), **pero no se asumen**: si otra rama (odontología, por ejemplo) ya tomó el número, el implementador
   **para y vuelve a preguntar**. Avisar en el commit y en `MEMORY.md` el número reservado; la guía `docs/trabajo-por-historia-clinica.md`
   (ya en `origin/main`) pide coordinarlo con las demás PC.
2. **Forma:** una sola sentencia `ALTER TABLE … ADD COLUMN … DEFAULT …` por columna (corre igual en SQLite y en sql.js de la web),
   **columna al final** de la tabla y el `.sq` actualizado en la misma tarea (`upsertX` es posicional).
3. **Prueba:** una base de la versión anterior con datos (como `MigracionHepatologiaTest`), migrada por el driver: las filas
   existentes quedan con el valor por omisión (histórico) y nada más cambia; las nuevas escriben el valor explícito.
4. **Sin vuelta atrás:** una app más vieja sobre una base más nueva descarta los datos del navegador. Para probar, servir esta rama en
   **otro puerto** que el `devWeb` publicado/de otra rama (el almacenamiento es por origen). 001 y 002 se publican juntas.

**Migración de E1 (D26):** `consulta.propia`. Valores: `0` histórico o de la web (todo lo existente y todo lo que traiga la
lectura), `1` propia. Reglas: la app escribe `1` al crear una consulta de la HC general con el envío habilitado y al confirmar
«Enviar a la web» del histórico (RF-63); **ninguna ruta que reescriba una `Consulta` puede perder el valor** (`copy` lo conserva;
`fusionarConsulta` de la lectura lo respeta). Un dato por paciente es **propio** si su `consulta_id` apunta a una consulta con
`propia = 1`; si apunta a una histórica, a una de la web o a ninguna, es histórico y no viaja (RF-68). Al editar una casilla o fila
del histórico dentro de una consulta propia, el repositorio reasigna su `consulta_id` a la propia y la marca sucia: viaja solo eso.
*Descartado por el usuario (D26):* regla por fecha. *Descartado por el plan:* una marca en cada tabla de datos por paciente
(cuatro migraciones en vez de una; la consulta ya las agrupa).

**Migración de E3 (D8):** `laboratorio.remoto_id` y `pendiente.remoto_id`. En `pendiente`, el identificador es el **del texto único
de la consulta** y lo lleva la **fila portadora**: la primera fila (por `updated_at` de alta, estable) de pendientes de esa
consulta propia; si el médico la borra y quedan otras, el identificador pasa a la siguiente **en la misma transacción**. La
**huella** (hash) del último texto enviado se guarda en `paciente_extra` (clave `hce_pend_<consulta>`, `dirty = 0`) para reconocer, al
volver a leer, que el texto de la web es el enviado y no crear ningún pendiente `hcc-p…` nuevo (RF-43). *Descartado:* guardar solo
en `paciente_extra` sin usar la columna aprobada (D8 pide el identificador en `pendiente`).

## Sin conexión y reconciliación
- **Guardar primero local**, siempre; `HcClinicaEnvio` toma lo `dirty` de las consultas `propia = 1` (y lo por paciente asociado).
  Nada del histórico entra en la cola (RF-7).
- **Crear:** el envío manda `clave` (= `c:` + id local de la consulta, estable entre reintentos). El servidor responde el mismo
  resultado si ya la recibió (RF-15). El id de la web se guarda en `consulta.remoto_id` y se limpia el pendiente **en una sola
  transacción local** (RF-61). Si la app cae entre «el servidor respondió» y «lo anoté», el próximo envío repite la misma clave y
  recibe lo mismo.
- **Filas hijas:** estudios/exámenes/interconsultas (ya tienen `remoto_id`) y, desde E3, laboratorio/peso/pendientes: clave `r:`/
  `l:`/`p:` + id local; el id de la web vuelve y se guarda con el mismo criterio.
- **Orden:** creación → contenido (secciones, examen, formularios, listas, cargas, pendientes, adjuntos) → cierre al final; el
  cierre no sale mientras haya algo pendiente, «En espera» o rechazado (RF-56). Un cambio hecho mientras viaja un envío no se da por
  enviado: se limpia solo lo que viajó, comparando `updated_at` de cada fila con el momento en que se armó el envío (RF-55).
- **Una consulta, un envío a la vez** (RF-54): `Mutex` por consulta dentro de `HcClinicaEnvio`; un segundo disparo espera y vuelve a
  leer lo pendiente al terminar.
- **Errores:** `ClasificacionDeEnvio` separa (a) *causa ajena* (`401`, `403 hc_no_habilitada|medico_no_vinculado|licencia_vencida|
  fuera_de_cartera`, sin red, `5xx`, `429`, `503 tobb_caido`) → «En espera» con el motivo de `MotivoHc` nombrando la HC; (b) *causa
  del dato* (`422`, `404 consulta_no_encontrada`, `409` por consulta cerrada o dada de baja) → «No se pudo enviar», sin reintento
  automático, con «Reintentar»/«Descartar»; (c) reintentable con espera (`429`, `5xx`, red). Precedencia de RF-19: primero paciente
  de baja o inexistente (`404 paciente_no_encontrado`, «En espera»), después consulta de baja (`404 consulta_no_encontrada`: marca
  «Ya no figura en la web» si ya estaba enviada y lo pendiente pasa a «No se pudo enviar»), después cualquier otro rechazo.
- **Reintentos (RF-29):** 15 s, 60 s y 300 s (las esperas que ya tiene pediatría) y después **cada 300 s mientras la app esté abierta
  y haya algo pendiente o «En espera»** (nunca más rápido: con 60 pedidos por minuto por IP un consultorio entero comparte el
  límite). Cada intento pasa por la espera de 4 s. Los disparadores de RF-57 adelantan el siguiente intento, no lo multiplican.
- **Disparadores (RF-57):** `alAbrirOIniciar()` desde `App.kt` cuando hay sesión (código común), `alRecuperarConexion()` desde el
  `ObservadorDeConexion` de cada plataforma, y el pedido manual «Reintentar». Los tres llaman a `programar()` que reinicia la espera
  de 4 s de esa consulta; ninguno corre en paralelo con un envío en curso.
- **Sesión y médico (RF-50, RF-51):** cada fila pendiente de la cola lleva al médico dueño (`consulta.medico_id`); el orquestador solo
  toma las de la sesión actual. `logout` con pendientes avisa cuántas consultas (el aviso lo da la UI con `contarPendientes()`) y no
  borra nada. Una sesión que vence a mitad de un envío (`401`) deja el resto «En espera: sesión vencida».
- **Estados persistentes** (sin columnas nuevas): `paciente_extra` de la HC general, claves `hce_estado_<consulta>:<parte>`, valor
  `espera|rechazado|descartado` + motivo + fecha, `dirty = 0`; se reconstruyen al abrir la app.
- **Lectura de lo propio (RF-33):** `aplicarLista`/`traerConsulta` dejan de tratar como «intocable» a una propia ya enviada: la
  refrescan campo por campo con las reglas de la L (lo sucio no se pisa, lo vacío no pisa), conservando `propia`; empareja por
  `remoto_id` (consulta, estudios, exámenes, interconsultas; laboratorio y pendiente desde E3) y no crea filas `hcc-…` duplicadas.

## Interfaz (pantalla y rol)
Todo para el **médico** de la HC general (con o sin agenda). Administrador: solo el panel de E5.
- **Historia del paciente** (`HistoriaClinicaScreen`): «Consulta en curso» (propias abiertas, con «Falta enviar»/«En espera»/«No se
  pudo enviar»), «Abiertas en la web» (solo lectura), «Solo en este dispositivo» (histórico) con «Enviar a la web» (o la lista de
  secciones que faltan y el motivo si RF-67 lo impide), «Consultas anteriores». El aviso «<Nombre> tiene una consulta abierta de este
  paciente en la web» antes de crear (RF-18) y después de enviar (RF-59).
- **Consulta** (`ConsultaScreen`): editable si es propia o histórico local; solo lectura si es de la web (`hcc-…`). Banner de estado
  de envío con acciones; secciones sin envío con «Esta sección todavía no se envía a la web»; lo que la web no guarda,
  «No se envía a la web»; «Enviando el cierre…» y sin «Reabrir» una vez enviado el cierre.
- **Elegir ficha:** desde la consulta «En espera: elegí la ficha», con la pantalla de la 003 (D15).
- **Cerrar sesión:** diálogo con la cantidad de consultas sin enviar (RF-51).
- **Licencia:** el ingreso muestra el texto de vencida nombrando la HC (enmienda a la 004); el panel de licencias no cambia hasta E5.

## Servidor (fuera de este repo): hclinica, rama `salud360-api`
**Toca API Laravel: sí; autorizado en D3 solo para la copia local.** Clon `C:\Users\flor\rebonline\hclinica`, copia servida
`C:\xampp\htdocs\HClinica-salud360` (los archivos se copian a mano; `php -l` de cada uno; `php artisan route:clear`; `config:cache` si
se toca `config/`). PHP 7.3 de 32 bits: sin `fn`, sin propiedades tipadas, ids como texto antes de `(int)`. Commits como `rodri89`.

### Archivos nuevos (el de lectura no se toca)
| Archivo | Responsabilidad |
|---|---|
| `app/Services/Salud360/EscrituraReglas.php` | Reglas puras (sin base): validar cuerpos, normalizar fecha/edad/tipo, qué campos se escriben (solo los presentes), vaciado a propósito, cierre (qué filas se confirman), traducción inversa con `MapaClinica::columnaWeb`, `0.00` = vacío |
| `app/Services/Salud360/EnviosRegistro.php` | Registro de lo recibido: crea `salud360_envios` con `Schema::create` (como `TobbAuthService::asegurarTablas`); `buscar(user, clave)`, `registrar(user, dispositivo, clave, tipo, recurso, respuesta)`, `consultaCreadaPorLaApp(user, consultaId)`. Único `(user_id, clave)`; un duplicado simultáneo cae en la violación de unicidad y devuelve lo registrado |
| `app/Services/Salud360/EscrituraService.php` | Con base: crear, textos, examen, cerrar, baja (E1); formularios, antecedentes, registros (E2); laboratorio, peso, pendientes (E3); adjuntos (E4). Cada operación en `DB::transaction`. Relleno con `ColumnasLegacy::vacias` |
| `app/Http/Controllers/Api/Salud360/ConsultaEscrituraController.php` | Valida, autoriza (RF-52), delega, responde; `\Throwable` → `500` sin detalle |
| `routes/api.php` | Rutas nuevas dentro del grupo `salud360` ya protegido por el middleware |
| `tests/Unit/EscrituraReglasTest.php`, `EnviosRegistroTest.php` (parte pura) | phpunit sin Laravel ni base |
| `md/API_SALUD360_CLINICA.md` | Sección «Escritura» por subentrega |

### Contrato (nombres provisorios del plan; cuerpo siempre JSON; `clave` en el cuerpo, no en un encabezado: la CORS no lo permite)
| Sub | Ruta | Cuerpo → respuesta | RF | Se despliega a producción cuando |
|---|---|---|---|---|
| E1 | `POST consultas` | `{clave, dispositivo, paciente_id, tipo, fecha, edad_mostrar}` → `201/200 {consulta, creada, otras_abiertas:[{id, medico_nombre}]}` | RF-12 a RF-15, RF-59 | E1 cerrada |
| E1 | `PUT consultas/{id}/secciones` | `{secciones:{<seccion>:{texto}}}` (`""` = vaciado a propósito) → `{guardadas, desconocidas}` | RF-20, RF-69 | E1 cerrada |
| E1 | `PUT consultas/{id}/examen` | `{peso?, talla?, tension_arterial?, imc?, nota?}` solo lo presente | RF-21 | E1 cerrada |
| E1 | `POST consultas/{id}/cerrar` | `{fecha, edad_mostrar}` → consulta cerrada; confirma solo exámenes e interconsultas `activo = 2` | RF-22, RF-23 | E1 cerrada |
| E1 | `DELETE consultas/{id}` | baja lógica (`activo = 0`) solo si está abierta y la creó la app | RF-26 | E1 cerrada |
| E2 | `PUT consultas/{id}/formularios` | alimentación, serologías, Child-Pugh | RF-35, RF-37, RF-38 | E2 cerrada |
| E2 | `PUT pacientes/{id}/antecedentes-familiares` | `{consulta_id, casillas, nota}` solo lo tocado, mezclado con la última fila activa | RF-36 | E2 cerrada |
| E2 | `PUT consultas/{id}/registros` | `[{ref, tipo, id?, fecha, campos, borrado}]` → `{ids:{ref:id}}`; `borrado` = baja | RF-39, RF-40, RF-70 | E2 cerrada |
| E3 | `PUT/DELETE pacientes/{id}/laboratorios` y `/pesos` | `{clave, consulta_id, id?, fecha, valores}` → `{id}` (visibles al instante) | RF-41, RF-42 | E3 cerrada |
| E3 | `PUT consultas/{id}/pendientes` | `{texto}` → `{id}` (un texto por consulta) | RF-43 | E3 cerrada |
| E4 | `POST consultas/{id}/adjuntos` (multipart), `DELETE`, `GET …/archivo`, `GET` de límites | subida repetible por `clave` | RF-44 a RF-46 | E4 cerrada |
| E5 | `GET licencias`, `PUT licencias/{medicoIdTobb}` | panel; el aviso ya viaja en `auth/perfil` | RF-47 a RF-49 | E5 cerrada |

**Reglas del servidor (RF-52, RF-53):** todas las rutas de escritura exigen médico (`soloMedico`: administrador y secretaria →
`403 sin_permiso`); paciente en su cartera (`pacienteVisible`); la consulta debe existir, estar **`activo = 2`**, ser de `medico_id =
token` y figurar en `salud360_envios` como creada por la app (si no, `403 consulta_no_propia`); sobre una cerrada o de baja, `409
consulta_cerrada` salvo repetir una escritura ya registrada (misma respuesta). Todo en modo estricto (`SET SESSION sql_mode` en las
pruebas). La API **nunca** escribe `medico_infos` ni replica los `es_nueva_consulta` de la web; ningún `DELETE` físico.
**`inicializarCampos`:** solo se replica si la verificación de RF-17 muestra que la web la necesita (decisión P7).
**Tabla `salud360_envios`:** se crea sola la primera vez (privilegio `CREATE`); sin vencimiento ni limpieza; entra en el respaldo; el
E2E la limpia **por clave** (RF-15). **No se tocan columnas de tablas de la web.** En producción es una tabla nueva: aprobación
aparte junto con el respaldo (D4, D21).

### Orden de despliegue (cada paso con su aprobación; producción fuera de este plan)
1. Servidor en el clon y en la copia local (todas las capacidades se escriben y prueban acá, D3), pero **cada ruta se declara
   «lista para producción» recién cuando su subentrega esté cerrada**.
2. App contra la copia local (tests, E2E, prueba manual del usuario).
3. **E9 de la escritura (aprobación aparte):** respaldo restaurable verificado de producción, subir solo las capacidades de las
   subentregas cerradas, comprobar sin token (`401`, no `404`), `APP_DEBUG=false`, privilegio `CREATE`, tabla de repetibilidad aprobada.
4. Recién entonces **publicar la app** (E1 a E3 cerradas, D23), con la comprobación de solo lectura de médicos de clínica con licencia
   vencida o sin fila hecha antes (tarea de cierre de E1 en `tasks.md`).

## Integración de la T9 de la spec 004 y la licencia (precondición de E1)
- **T9 (cherry-pick de T1 a T7 de la 004 desde `origin/main` a `hc-general`) es PRECONDICIÓN de E1 por D22. La T9 requiere pedido
  aparte del usuario según la 004**: el plan la deja como tarea con esa advertencia y **no se ejecuta sin que lo pida**. El detalle
  de conflictos está en `specs/004-licencia-del-servidor/tasks.md` («Integración en `hc-general`»): orden de los 9 commits, `HcBackend`
  sin reintroducir `traerPacientes`, constructor de `AuthRepository` con los parámetros nuevos **después** de los de `hc-general`,
  `TobbException.cuerpo` al final, `cerrarPorLicencia` que llama a `logout(token)`.
- **Enmienda a la 004 (D22/D25):** el texto se aplica **donde se integre la T9** y se avisa en ambas ramas (la lista completa está
  en `notas-para-el-plan.md`, §7). El código: `HcClinicaBackend.licenciaDelServidor()` (lee `auth/perfil`; `403 licencia_vencida` con
  `licencia: null` → `SinLicencia`, con fecha → `Vencida`), y `decidirLicencia` por HC con la regla D25 (bloquea solo si **todas** las
  HC con licencia contestan «vencida» con fecha; sin copia local de clínica).
- **Panel (RF-66):** `administraLicencia` en `HcBackend`; el panel no cambia hasta E5.

## Decisiones del plan (con la alternativa descartada)
- **P1. Orquestador propio `HcClinicaEnvio`.** Pediatría y clínica difieren en orden, estados, rechazos, reintento periódico, datos
  por paciente y cierre. *Descartado:* extender `HcApiSync.drenar` con ramas por backend (cualquier cambio arriesga pediatría, RF-5, y
  `drenar` manda por paciente sin mirar la consulta).
- **P2. Cierre del riesgo de `SyncEngine`: excluir del motor viejo las tablas clínicas de contenido.** Es lo mínimo que lo cierra
  (con el histórico en `dirty = 1`, RF-7) sin tocar turnos, agenda ni administración. *Descartado:* apagarlo entero (`iniciarPeriodico`
  lo usan el login, la agenda y el admin; cambiaría comportamiento ajeno a esta spec, RF-5). Efecto: el contador de `EstadoSync` deja de
  contar esas filas (el indicador está oculto hoy) y el test que documenta lo que empuja hoy cambia a «no empuja las clínicas».
- **P3. Marca en `consulta`** y datos por paciente por `consulta_id` (arriba). *Descartado:* regla por fecha (D26); marca en cada tabla.
- **P4. Identificador del pendiente en la fila portadora + huella en `paciente_extra`** (arriba).
- **P5. Clave de repetibilidad = id local con prefijo, en el cuerpo.** Estable sin guardar nada extra. *Descartado:* un encabezado
  (obligaría a tocar la CORS y probar el preflight) y una clave nueva por intento (no repite).
- **P6. Controlador y servicio de escritura nuevos.** Mantiene la garantía «la lectura no escribe». *Descartado:* ampliar
  `ConsultaController`.
- **P7. `inicializarCampos` solo si hace falta.** Se decide con la verificación de RF-17 (tarea manual); si no hace falta, se evita
  replicar filas vacías. *Descartado:* replicarlo siempre «por las dudas» (escribe más de lo necesario).
- **P8. RF-61 por repetibilidad + una transacción local.** *Descartado:* intentar atomicidad con el servidor (imposible).
- **P9. Disparadores con `expect/actual` por plataforma y un reintento periódico común.** *Descartado:* solo el periódico (RF-57
  pide «al recuperar la conexión»); solo los eventos (se pierden si la app estaba cerrada).
- **P10. Estados de envío en `paciente_extra` (sin columnas).** La única migración aprobada para E1 es la marca. *Descartado:* una
  columna de estado por tabla.
- **P11. Reintento periódico cada 300 s tras los tres primeros.** Respeta el límite de pedidos por IP. *Descartado:* 60 s (varios
  médicos tras la misma red agotarían los 60 pedidos por minuto).
- **P12. Propia enviada = sigue «Consulta en curso» mientras esté abierta** (RF-62): `agruparConsultas` mira `propia` antes que
  `remoto_id`. *Descartado:* tratarla «de la web» y bloquearla (contradice D1).
- **P13. El cambio al catálogo de la 001 (D9) se declara en esta spec y se prueba con `LecturaClinicaCatalogoTest` reescrito.**

## Tests de la L que se reescriben (por diseño de la spec, no por accidente)
Fijan «clínica no escribe» y dejan de valer cuando se abre el candado: `HcClinicaBackendSoloLecturaTest` (reflexión: solo `GET`),
`HcApiSyncSoloLecturaTest` y `HcApiSyncBaseMigradaTest` (candado para clínica; el de pediatría queda), `HcBackendsSoloLecturaTest`
(`clinica` pasa a `false`/`envioPropio = true`), `DataModuleClinicaTest` («solo `GET`»), `HcRepositoryGuardasDeLaWebTest` y
`HcRepositoryTest` (las guardas por `remoto_id` de un backend de solo lectura), `AdminRepositoryLicenciasTest`,
`LecturaClinicaCatalogoTest` (tabaquismo) y el test que documenta lo que empuja `SyncEngine`. Cada tarea que los toca lo dice y los
reemplaza por el equivalente de escritura (misma cobertura, regla nueva). Los E2E de lectura (`HcClinicaLecturaE2ETest`) siguen.

## Estrategia de tests
Convención: **PHP** = phpunit puro del repo hermano (`tests/Unit`, en la copia servida: `C:\xampp\php\php.exe vendor\bin\phpunit
tests\Unit\<Test>.php`; si un test falla el *printer* se cae por `phpDocumentor`/sintaxis 7.4 y no muestra el mensaje — correr la clase
con un `TestSuite`/`TestResult` propio, ver `%TEMP%\opencode\lectura\corre.php`, o llamar el código con `php -r`); **PHP-base** = script
con el bootstrap de Laravel contra una base descartable (como los de E3/E4 de la L), con `sql_mode` estricto y sin; **curl** = contra
la copia servida; **jvm** = `jvmTest` de `core:data` (`.\gradlew :core:data:jvmTest --tests '*Nombre*'`); **common** = `commonTest`;
**E2E** = `jvmTest` que se saltea sin las propiedades `-Psalud360.test.hc.clinica…`; **manual** = `.\gradlew devWeb` (lista en
`tasks.md`).

| RF | Cobertura |
|---|---|
| RF-1, RF-2 | jvm `HcClinicaEnvioTest` (no envía nada sobre `hcc-…`/de la web ni sus filas); jvm `HcRepositoryGuardasDeLaWebTest` (reescrito); curl `403` en consulta de la web |
| RF-3 | common `SeccionesEnviablesTest`; `especialidades/clinica` `LecturaClinicaCatalogoTest` (reescrito) |
| RF-4 | common `SeccionesEnviablesTest` (qué ofrece cada versión/subentrega); manual |
| RF-5 | `.\gradlew :core:data:jvmTest` completo; `HcPediatriaE2ETest` y `HcCarteraE2ETest` con `--rerun`; jvm `HcBackendsSoloLecturaTest` (reescrito) |
| RF-6 | common `ClasificacionDeEnvioTest` (cada causa → «En espera» con la HC nombrada); jvm `HcClinicaEnvioEsperaTest` (servidor falso: 401, 403×4, 503, 429, red) |
| RF-7 | jvm `EnviarHistoricoTest` (T33) sobre la base de `HcApiSyncBaseMigradaTest` migrada (cero pedidos al abrir, iniciar sesión, volver la conexión, reintentar, abrir o cerrar una consulta nueva); jvm `HcApiSyncBaseMigradaTest` reescrito para `SyncEngine` (T30) |
| RF-8 | jvm `MigracionMarcaTest` (existente = histórico, nueva = propia); common `OrigenDeConsultaTest` (persistente, edición no la cambia) |
| RF-9, RF-60 | common `PlanDeEnvioTest` (desactivados, nunca figuran enviados ni pendientes) |
| RF-10, RF-63, RF-67 | common `HistoricoDelDispositivoTest` (`puedeEnviarse`, secciones que faltan, qué no viaja, dueño, abierta sigue abierta); jvm `EnviarHistoricoTest`; manual (diálogo) |
| RF-11 | jvm `HcRepositoryTest` ampliado (`consultaAbiertaPropia`, nunca una de la web ni histórica) |
| RF-12 | jvm `HcClinicaEnvioRitmoTest` (abrir no hace pedidos) |
| RF-13, RF-19 | common `ClasificacionDeEnvioTest` (precedencia); jvm `VinculoAlEnviarTest` (resolver antes de enviar, ficha ambigua → «En espera»); manual (elegir ficha) |
| RF-14 | PHP `EscrituraReglasTest` (fecha clínica, edad, tipo, a nombre del médico); PHP-base crear; jvm `PayloadsClinicaTest` |
| RF-15 | PHP `EnviosRegistroTest` + PHP-base (repetir la misma clave: misma respuesta, una sola fila); jvm `HcClinicaEnvioRepetibleTest` (se pierde la respuesta → mismo envío, una consulta); E2E |
| RF-16 | E2E **puerta bloqueante** (`medico_infos` idéntico, incluido `es_nueva_consulta`, tras crear, cargar y cerrar); grep sin `medico_infos` en los archivos nuevos del servidor |
| RF-17 | manual con el usuario (T de verificación, perfiles 1, 2, 3, 4, 9) |
| RF-18, RF-59 | common `AvisoDeAbiertaTest` (fresco/no fresco, otro tipo propio); PHP-base (`otras_abiertas`); manual |
| RF-20, RF-21 | PHP `EscrituraReglasTest` (textos, `0.00`, `0`, `0.0`); PHP-base; jvm `PayloadsClinicaTest`; E2E |
| RF-22, RF-23 | PHP `EscrituraReglasTest` (qué filas confirma el cierre) + PHP-base (borrada no reaparece); E2E |
| RF-24, RF-25, RF-26 | jvm `HcRepositoryCierreTest` (falta enviar, reabrir solo antes de enviar, anular: nada / baja / cerrada enviada no); PHP-base (`DELETE`); manual |
| RF-27, RF-28 | jvm `HcClinicaEnvioRitmoTest` con reloj falso (cambios cada 600 ms durante 30 s: cero envíos y uno a los 4 s) |
| RF-29 | jvm `HcClinicaEnvioReintentoTest` (15/60/300 s y después cada 300 s con la app abierta; sin ráfaga) |
| RF-30, RF-32 | jvm `HcClinicaEnvioRechazoTest` (422, 404, 409; parcial; descartar; no frena a las demás) |
| RF-31 | common `ContadorDeEnvioTest` (cuenta todo tipo, excluye histórico, separa «En espera»/«No se pudo enviar») |
| RF-33 | jvm `HcClinicaBackendPropiasTest` (refresca la propia, no duplica, empareja por `remoto_id`, baja → «Ya no figura en la web»); E2E |
| RF-34 | PHP-base (web 10:00, dispositivo 9:55, llega 10:05: queda el del dispositivo; campo no tocado nunca se envía); jvm `PayloadsClinicaTest` |
| RF-35 a RF-40, RF-70 | PHP `EscrituraReglasTest` + PHP-base (E2); jvm `PayloadsClinicaTest`, `HcRepositoryRegistrosTest` (borrar = baja); E2E (E2) |
| RF-41, RF-42 | PHP + PHP-base (visibles al instante, fecha de la consulta, baja); jvm `MigracionLaboratorioTest`, `HcClinicaBackendPropiasTest` (empareja, no duplica); E2E (E3) |
| RF-43 | common `PendientesTextoTest` (uno por línea, huella, coincide/cambió); jvm test del emparejamiento; E2E (E3) |
| RF-44 a RF-46 | PHP + PHP-base (límite, subida repetible, baja); jvm `AdjuntosClinicaTest` (solo de la consulta, nunca de otra HC ni sin consulta); E2E (E4) |
| RF-47, RF-48 | jvm `AvisoLicenciaClinicaTest`; PHP-base (`licencias`); manual (E5) |
| RF-49 | curl con token de administrador (ya cubierto) |
| RF-50, RF-51 | jvm `SesionYEnvioTest` (otro médico no envía lo del primero; cerrar sesión no borra); manual |
| RF-52, RF-53 | PHP `EscrituraReglasTest` (autorización pura) + curl: administrador, secretaria, otro médico, consulta de la web, cerrada → códigos esperados; PHP-base (repetir sobre cerrada) |
| RF-54, RF-55 | jvm `HcClinicaEnvioConcurrenciaTest` (dos disparos = un envío a la vez; cambio en vuelo no se limpia; cerrar durante el envío espera) |
| RF-56 | jvm `HcClinicaEnvioOrdenTest` (creación → contenido → cierre; una parte rechazada o en espera frena el cierre) |
| RF-57 | jvm `HcClinicaEnvioDisparadoresTest` (cada disparador pasa por los 4 s y no corre en paralelo); manual por plataforma (iOS en macOS) |
| RF-58 | jvm `AuthRepositoryLicenciaClinicaTest` (todas vencidas → no entra; una vigente → entra; sin fila → entra; solo clínica); E2E contra la copia local con la licencia de prueba movida y restaurada |
| RF-61 | jvm `HcClinicaEnvioAtomicoTest` (corte entre confirmar y anotar: el reenvío repite la clave y no duplica) |
| RF-62, RF-64, RF-65 | common `OrigenDeConsultaTest` (agrupación); manual |
| RF-66 | jvm `AdminRepositoryLicenciasTest` ampliado (médico solo de clínica: `true`, cero llamadas) |
| RF-68 | jvm `DatosPorPacienteTest` (solo lo asociado a una propia; reasignar al editar; histórico no viaja) |
| RF-69 | common `CambioRealTest`; PHP `EscrituraReglasTest` (vaciado a propósito); E2E |

## Qué RF cubre cada parte (resumen)
- **E0:** ninguno (precondiciones D4).
- **E1:** RF-1 a RF-34, RF-50 a RF-69 (todo menos formularios/listas, laboratorio, adjuntos y aviso/panel de licencia de E5).
- **E2:** RF-35 a RF-40 y RF-70. **E3:** RF-41 a RF-43. **E4:** RF-44 a RF-46. **E5:** RF-47 a RF-49.

## Riesgos
| Riesgo | Mitigación |
|---|---|
| **RAM de esta máquina** (7,5 GB; Gradle muere con el navegador abierto; `compileKotlinJvm` de `core:crecimiento` se colgó una vez) | Cerrar el navegador al compilar; correr `--tests` acotados y el completo solo al cerrar cada tarea; `-Pentorno=dev`; no usar `build` (compilaría contra producción) |
| **Throttle de 60 pedidos por minuto por IP** (todo el consultorio comparte la IP) | Envío agrupado por consulta; reintento periódico cada 300 s; disparadores que adelantan sin multiplicar; E2E espaciado 1,1 s; `429` = reintentable, no rechazo |
| **`SyncEngine`** (rutas inexistentes en turnos) | P2: excluir las tablas clínicas; test que lo fija |
| **T9 de la 004** (conflictos con `AuthRepository`, `HcBackend`, `TobbException`) | Cuadro de conflictos de la 004; pedido aparte; correr `:core:data:jvmTest` completo antes y después |
| **Registro de repetibilidad perdido → duplica** | Sin vencimiento, en el respaldo, el E2E no lo vacía; la app no reenvía lo confirmado |
| **PHP 7.3 de 32 bits / printer roto / base local no estricta** | `php -l`, runner propio, `SET SESSION sql_mode` estricto en cada prueba, ids como texto |
| **El cierre de la web reactiva lo borrado** si alguien cierra en la web una consulta de la app | Documentado; fuera del control de la app |
| **Migración sin vuelta atrás** (web: la base se descarta si es más nueva) | Servir esta rama en otro puerto al probar; 001 y 002 se publican juntas |
| **iOS no se compila en Windows** | Observador de conexión de iOS marcado «a mano, en macOS» |
| **Producción estricta/esquema distinto** | `ColumnasLegacy`, pruebas estrictas, E9 de la escritura con respaldo y paciente de prueba |

## Qué hay que preguntarle al usuario antes de empezar
1. **E0:** ¿se pueden borrar, por id, las filas de la deuda de limpieza de la L y de la 003 (la lista está en `MEMORY.md`, «Limpieza
   pendiente»)? ¿y restaurar `medico_infos` del médico de prueba como indica la memoria? (D4 lo manda; se confirma la lista antes de ejecutar).
2. **E0:** ¿nombre de la base descartable donde restaurar el respaldo (propuesta: `hc_clinica_respaldo`) y confirmar que el MySQL
   local tiene espacio? ¿Puedo correr `mysqldump` sobre la base local?
3. **T9 de la 004:** ¿la pedís ahora? Sin ella no empieza E1 (precondición de D22). También: ¿aplico la enmienda de texto a la 004
   en el mismo pedido?
4. **E1:** que otra PC/rama de HC no haya tomado `9.sqm` (el implementador lo mira, pero conviene saber si hay una rama de
   odontología con migraciones).
5. **E1:** el movimiento de la licencia del médico de prueba (vencida/sin fila/restaurada) para probar RF-58: ¿se permite con los
   scripts de la 004 (`%TEMP%\opencode\t9m`) y restauración verificada?
6. **E1 (RF-17):** ¿hay en la copia local médicos con los perfiles 1, 2, 3, 4 y 9, o se arman con datos «PRUEBA SALUD360»? Las
   credenciales las pone el usuario en la sesión, nunca en el repo.
7. **Antes de publicar:** la comprobación de solo lectura de médicos de clínica con licencia vencida o sin fila (en local y, con
   aprobación de producción, allá) — sin nombres ni datos de pacientes.

---

# E2, E3 y E4: contrato y decisiones (2026-11-07) — APROBADAS por el usuario el 2026-11-07 (salvo lo que dependa de una pregunta abierta)
Borrador del planificador a pedido del usuario (llegar a «todo editable como en pediatría»). Las tareas están en `tasks.md` (T48 a T113); lo que la
web hace hoy, con archivo y línea, en `notas-para-el-plan.md` §11 a §13. **No cambia la spec**; los huecos están como preguntas Q-… en `tasks.md` y
no se resolvieron acá. Todo es «Toca API Laravel: sí» solo para la copia local (D3); producción, aparte (D4, D18).

## Contrato provisorio (nombres a confirmar en T48, T73 y T95)
Mismo grupo `salud360`, mismo middleware; cuerpo JSON salvo la subida; mismos errores que E1 (`401`, `403 sin_permiso|fuera_de_cartera|consulta_no_propia`,
`404 consulta_no_encontrada|paciente_no_encontrado`, `409 consulta_cerrada`, `422 datos_invalidos` con `campo` y `motivo`, `500 escritura` sin detalle) más los
que dice cada fila. **La lectura no se modifica.**

| Sub | Ruta | Cuerpo → respuesta | Reglas del servidor | RF |
|---|---|---|---|---|
| E2 | `PUT consultas/{id}/formularios` | `{formularios:{alimentacion:{hiposodica,diabetes,celiaco,otra: "<0\|1>\|<detalle>"}, serologias:{<16 claves>: "SI"\|"NO"\|""}, child_pugh:{ascitis,bilirrubina,albumina,tp,encefalopatia: "1".."3"\|""}}}` → `{ok, guardadas, desconocidas}` | Solo lo presente; `celiaco`→`seliaco`; `SI`=1, `NO`=2, `""`=0 (nunca `NO` por un vacío); total de Child-Pugh = suma de la fila resultante; fila `activo = 1` de mayor id de la consulta o alta; upsert sin registro | RF-35, 37, 38 |
| E2 | `PUT pacientes/{id}/antecedentes-familiares` | `{consulta_id, casillas:{<clave>:{flag,detalle}}, nota}` → `{ok, id, guardadas, desconocidas}` | Consulta propia/abierta/de ese paciente; `tabaquismo` y claves desconocidas → `desconocidas`; fila de (consulta, paciente) o **fila nueva = última activa del paciente mezclada con lo tocado**; nunca toca la fila de otra consulta | RF-36, RF-3, RF-9 |
| E2 | `PUT consultas/{id}/registros` | `{items:[{ref:"r:<id local>", tipo, id?, fecha?, campos, borrado?}]}` → `{ok, ids:{ref:id}, guardadas, desconocidas}` | Atómico; cinco estudios (upsert por consulta/paciente; fecha obligatoria); exámenes/interconsultas: alta `activo = 2` con `numero`, `numero_consulta`, `fechaSolicitud` asignados por el servidor, edición, **completar** (`respuesta` de una fila `activo = 1` de otra consulta), **baja** (`activo = 0`, solo de esta consulta); `ref` registrado = actualizar esa fila; `422 borrado_no_permitido` (estudios), `422 no_se_quita_desde_otra_consulta` | RF-39, 40, 70 |
| E3 | `PUT pacientes/{id}/laboratorios` / `PUT pacientes/{id}/pesos` | `{clave:"l:<id local>", dispositivo, consulta_id, tipo?, id?, fecha, valores}` → `{ok, id, guardadas, desconocidas}` | `activo = 1` desde el alta (D20); fecha obligatoria y nunca el marcador de «sin fecha» (`422 fecha_invalida`); alta repetible por clave; actualizar (`id`) solo una carga de esa consulta creada por la app (`403 carga_no_propia`); `422 carga_vacia`; no escribe `medico_infos` | RF-41, 42 |
| E3 | `DELETE pacientes/{id}/laboratorios/{cargaId}` / `…/pesos/{cargaId}` | `{clave, dispositivo, consulta_id}` → `{ok, baja}` | `activo = 0`, nunca físico; solo cargas propias de esa consulta; repetir = ok | RF-41, 42 |
| E3 | `PUT consultas/{id}/pendientes` | `{texto}` → `{ok, id}` | Un texto por consulta en `aux_pendientes` (`activo = 1`), el último manda; `""` vacía; sin `resuelto` (D10); upsert | RF-43 |
| E4 | `POST consultas/{id}/adjuntos` (multipart) | `archivo`, `clave:"a:<id local>"`, `dispositivo`, `tipo: pdf\|foto_examen`, `registro_id?` → `201/200 {ok, adjunto:{id,nombre,tamanio,created_at}}` | Solo PDF (cabecera `%PDF-`) o, si D24, JPEG/PNG de un examen confirmado; tope = menor entre 15 MB y el hosting; archivo temporal → fila + registro en una transacción → renombrar; `422 archivo_grande\|archivo_invalido`; `500 carpeta_no_escribible` con la ruta | RF-44, 46 |
| E4 | `GET consultas/{id}/adjuntos`, `GET adjuntos/{id}/archivo` | lista / bytes | Con sesión, paciente visible, nunca enlace público; la lista no trae rutas del disco | RF-45 |
| E4 | `DELETE adjuntos/{id}` | `{clave, dispositivo, consulta_id}` → `{ok, baja}` | `activo = 0`; no borra el archivo; solo adjuntos propios | RF-46 |
| E4 | `GET adjuntos/limites` | → `{maximo_bytes, maximo_web_bytes, upload_max_filesize, post_max_size}` | Solo lectura (`ini_get`); en producción solo con la aprobación de producción (D18) | RF-44 |

Registro de lo recibido (`salud360_envios`): se agregan el tipo `registro`, `carga` y `adjunto` y el prefijo `a` (hoy `c r l p`, `EnviosRegistro::PREFIJOS`). El
`remoto_id` guardado permite que **un reenvío con la misma clave actualice la misma fila** en vez de duplicarla.

### Contrato de E2 (completado en T48 el 2026-11-07) - borrador, se copia a `md/API_SALUD360_CLINICA.md` del clon en T56
Verificado contra el esquema **real** de `hc_clinica` y `hc_clinica_respaldo` (solo lectura; tabla de columnas, diferencias con las migraciones y cómo escribe hoy la web, con archivo y línea, en `notas-para-el-plan.md` §11.0 y §11).
Los nombres de los campos son **provisorios hasta T54-T58**. Lo que depende de una pregunta abierta va marcado `[Q-E2-n]`: es la **recomendación del planificador, no está aprobada** y la tarea que lo implementa está bloqueada
hasta que el usuario conteste. `md/API_SALUD360_CLINICA.md` del clon **no se tocó** en T48 (todavía no tiene sección de E2: termina en «Lo que viene»).

**Reglas comunes** (las de «Reglas de toda la escritura de E1» de ese `.md`, que valen igual): solo médico y solo pacientes de su cartera; solo consultas **propias, creadas por la app y abiertas** (`403 consulta_no_propia`, `409 consulta_cerrada`);
orden de los controles rol → `{id}` → cuerpo → consulta y cartera → con la consulta bloqueada (`FOR UPDATE`) propiedad y estado; una transacción por pedido (o se escribe todo o nada); cuerpo JSON **leído crudo** (`""` no se vuelve `null`);
`500 escritura` sin detalle. Además, para E2:
- **Solo lo presente se escribe** (RF-34); una clave ausente no se toca; `""` es el vaciado a propósito y **sí** se escribe (RF-69); `null` o un valor que no es del tipo esperado es `422 datos_invalidos` con `campo` y `motivo`.
- **Qué fila:** la `activo = 1` de **mayor `id`** de (consulta, paciente) (la que lee la lectura, `ConsultasService.php:360-367`; en estas tablas hoy no hay repetidas ni `activo = 2`); si no hay, **alta con `activo = 1`** y el relleno de
  `ColumnasLegacy::vacias` **sin `$opciones`** (así `0` es «sin cargar» o «no marcada») y **con `fecha` / `fechaSolicitud` siempre puestas por el servicio** (nunca el «hoy» en UTC del relleno).
- **Límite de los textos:** columna `text` = 65535 **bytes** (`strlen`); `varchar(191)` = 191 **caracteres** (`mb_strlen`); si se pasa, `422 texto_demasiado_largo` con el `campo` y el tope, **sin escribir nada del pedido y sin cortar** (RF-39).
- **No hace:** escribir `medico_infos`; crear las filas vacías de `inicializarCampos` (lo decide T11); ningún `DELETE` físico; tocar otra tabla que no esté en `EscrituraService::tablasEscribibles()` (E2 suma las once de abajo, con su test de «no escribe ninguna otra»); crear columnas;
  tocar la lectura. Las rutas van en el grupo `salud360`, con el mismo middleware, y la CORS no cambia.

**`PUT consultas/{id}/formularios`** - tablas `alimentacions`, `serologias_hepatitis`, `c_p_s` (una fila por consulta y paciente). Cuerpo:
```json
{"formularios": {
  "alimentacion": {"hiposodica": "1|sin sal", "celiaco": "0|", "otra": ""},
  "serologias":   {"hiv": "NO", "fan": "SI", "ama": ""},
  "child_pugh":   {"ascitis": "2", "tp": ""}
}}
```
- **Alimentación**: claves `hiposodica`, `diabetes`, `celiaco`, `otra`; valor `"<0|1>|<detalle>"` (se parte en el **primer** `|`; el detalle puede llevar más `|`, saltos de línea, tildes, emoji). `celiaco` → columnas `seliaco` / `seliaco_detalle`; `otra` → `otra` / `otra_detalle`
  (`MapaClinica::ALIMENTACION`). `""` y `"0|"` = casilla en 0 con detalle vacío (vaciado a propósito). Flag distinto de 0 y 1 → `422 flag_invalido`. Detalle: `text`, 65535 bytes.
- **Serologías**: las 16 claves = las 16 columnas (`igm_hav` … `ama`); `"SI"` → 1, `"NO"` → 2, `""` → **0** («sin cargar»: nunca `NO` por un vacío, RF-37; la lectura solo muestra 1 y 2, `ConsultasReglas.php:139-147`); otro valor → `422 valor_invalido`.
- **Child-Pugh**: claves `ascitis`, `bilirrubina`, `albumina`, `tp`, `encefalopatia`; `"1"`..`"3"` → ese puntaje, `""` → 0; otro valor → `422 valor_invalido`. **`total_puntos` lo calcula el servidor** como la suma de las cinco columnas de la fila **resultante**
  (lo que hace la web en el navegador, `resources/views/medico/seccion_new/cps.blade.php:124-131`; en las 1670 filas locales el total es exactamente la suma); la app no lo manda.
- Respuesta: `200 {ok, guardadas, desconocidas}` con `guardadas` = `"<seccion>.<clave>"` de lo escrito (también lo vaciado), en el orden del pedido; una sección o clave que el mapa no conoce va en `desconocidas` y no falla. Repetible (upsert, sin registro [Q-E2-6]).

**`PUT pacientes/{id}/antecedentes-familiares`** - tabla `antecedentes_familiares`. Cuerpo: `{consulta_id, casillas: {<clave>: {flag, detalle}}, nota}`.
- `consulta_id` tiene que ser una consulta propia, creada por la app, abierta y **de ese paciente** (si no: `403 consulta_no_propia` / `409 consulta_cerrada` / `422 consulta_de_otro_paciente`).
- Las 15 claves: `hta`, `dbt_nir`, `dbt_ir`, `acv`, `dsl`, `fa`, `mp`, `analfabeto`, `postrado`, `af_ca_mama`, `ca_mama`, `institucionalizado`, `psicofarmacos`, `dependiente_terceros` (columna `dependientes_terceros`), `otro`. `flag` 0 o 1 (`422 flag_invalido`);
  `detalle` y `nota` son `text` (65535 bytes). **`tabaquismo`** (la web no lo guarda, RF-3) y cualquier otra clave van en `desconocidas`.
- **Fila:** la de (consulta, paciente) con `activo = 1`; si no existe, **fila nueva de esa consulta = copia de la última fila activa del paciente** (la que muestra la lectura, `ConsultasService.php:108-112`) con solo lo tocado encima, y si el
  paciente no tiene ninguna, el relleno de `ColumnasLegacy` (todo en 0 / `''`). **Nunca se modifica la fila de otra consulta** ni se reactiva una `activo = 0` (hay una en la base local). Solo viajan antecedentes activos (RF-9: la app no manda los desactivados).
- **La nota del perfil 4** (`antecedentes_familiares_perfil4.nota`) **no está en el contrato**: la tabla no existe en la copia local [Q-E2-1]. Si el usuario decide escribirla, se agrega una clave aparte y la migración se corre en la copia local.
- Respuesta: `200 {ok, id, guardadas, desconocidas}` (`id` = la fila escrita; `guardadas` = las claves, y `nota` si se escribió). Repetible (upsert, sin registro [Q-E2-6]).

**`PUT consultas/{id}/registros`** - los cinco estudios, los exámenes complementarios y las interconsultas. Cuerpo: `{items: [{ref, tipo, id?, fecha?, campos, borrado?}]}`:
```json
{"items": [
  {"ref": "r:3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b", "tipo": "eco_abdominal", "fecha": "2026-11-06", "campos": {"detalle": "hígado de aspecto normal"}},
  {"ref": "r:8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f", "tipo": "examen_complementario", "campos": {"solicito": "laboratorio", "respuesta": ""}},
  {"ref": "r:5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b", "tipo": "interconsulta", "campos": {"especialista": "cardiología", "solicito": "control"}, "borrado": true}
]}
```
- `ref` = `r:<id local>` (prefijo `r`, ya reservado en `EnviosRegistro::PREFIJOS`); `tipo` ∈ `eco_abdominal` (`ecografia_abdominals`), `veda` (`videoendoscopia_digestiva_altas`), `biopsia_hepatica` (`biopsia_hepaticas`), `tc_rmn` (`tc_rmn_colangiormns`),
  `estudios_cardiologicos` (`estudios_cardiologicos`), `examen_complementario` (`examenes_complementarios`), `interconsulta` (`interconsultas`). Un `tipo` que no es ninguno va en `desconocidas` (el resto del pedido sigue); **un ítem inválido
  rechaza todo el pedido** con `422` y no se escribe nada (atómico, P16).
- **Estudios** (`campos: {detalle}` → `descripcion`): `fecha` **obligatoria**, `YYYY-MM-DD`, una fecha de calendario real (`422 fecha_invalida`; la web no la valida, `MedicoController.php:2212-2216`, y cardiología la tiene NOT NULL); `detalle: ""` = **vaciado a propósito**
  (la fila se conserva y la lectura la oculta, `ConsultasReglas.php:569-572`) y `borrado: true` → `422 borrado_no_permitido` `[Q-E2-2]`. Es un **upsert** por (consulta, paciente) sobre la fila `activo = 1` de mayor id (alta `activo = 1`) y no usa el registro de claves `[Q-E2-6]`.
  Tope de `detalle`: **191 caracteres solo en `estudios_cardiologicos`**; en los otros cuatro la columna es `longtext` y rige el tope general `[Q-E2-3]`.
- **Exámenes e interconsultas** (`campos: {solicito?, respuesta?, especialista?}`, este último solo en interconsulta, todos `text`): **alta** (sin `id` y con `ref` nuevo) con `activo = 2` (pendiente, se confirma al cerrar, RF-22); `numero` y `numero_consulta` los asigna el servidor
  (`numero` = el mayor del paciente + 1; `numero_consulta` = las pendientes de esa consulta + 1) y `fechaSolicitud` es la fecha clínica de la consulta (`consultas.created_at`) `[Q-E2-5]`; `consulta_respuesta` = esta consulta si `respuesta` no está vacía, `0` si lo está.
  **Edición** (el `ref` ya registrado, o `id`): solo filas **de esta consulta**, del mismo paciente y `activo = 2` (las pendientes; otra cosa es `422 registro_no_editable`), campo por campo; **`""` se escribe** (RF-69). **Completar** una fila de **otra** consulta del mismo paciente
  (`activo = 1`): solo `respuesta`, y `consulta_respuesta` = esta consulta; `consulta_id`, `solicito`, `especialista` y `fechaSolicitud` no cambian `[Q-E2-4]`. **Baja** (`borrado: true`): `activo = 0` de una fila **de esta consulta**, nunca borrado físico (RF-70, D27);
  de otra consulta → `422 no_se_quita_desde_otra_consulta`; repetir la baja es `ok`. La web **no** tiene esta baja (capacidad nueva) y **sí** reactiva lo dado de baja al cerrar una consulta suya (`:2225-2278`); la API al cerrar confirma solo las `activo = 2`.
- **Repetible:** `ref` ya registrado en `salud360_envios` (tipo `registro`) ⇒ se **actualiza esa misma fila** y no se crea otra (el `remoto_id` guardado es `bigint unsigned` y entra el `int unsigned` de exámenes).
- Respuesta: `200 {ok, ids, guardadas, desconocidas}`; `ids` = `{ref: id de la web}` de todos los aceptados (también los estudios, aunque no se registren), `guardadas` = los `ref` escritos, `desconocidas` = los `ref` con `tipo` desconocido.

**Errores nuevos de E2** (se suman a los de E1; `422 datos_invalidos` con `campo` y `motivo`): `flag_invalido`, `valor_invalido`, `fecha_invalida`, `texto_demasiado_largo` (con el tope), `consulta_de_otro_paciente`, `borrado_no_permitido` `[Q-E2-2]`,
`no_se_quita_desde_otra_consulta` `[Q-E2-4]`, `registro_de_otro_paciente` (un `id` que no es del paciente), `registro_no_editable`, `ref_en_uso` (el mismo `ref` con otro `tipo` o de otra consulta).

#### Cómo quedó en la app (T61, 2026-11-08) - lo que el servidor de antecedentes y de registros tiene que aceptar
La app arma estos cuerpos (`PayloadsClinica`, probados contra un servidor falso). El servidor de formularios ya existe (T54) y coincide; **antecedentes y registros todavía no existen** y se escriben contra esto:
- **Todo escalar es texto**, salvo `borrado` (booleano): `consulta_id` (`"7011"`) y `flag` (`"0"`/`"1"`) incluidos, como `paciente_id` en `crear`. `ids` de la respuesta de registros puede venir como mapa o como `[]` si no hay ninguno.
- **Antecedentes:** `PUT pacientes/{id}/antecedentes-familiares` con `{consulta_id, casillas:{<clave>:{flag, detalle}}, nota?}`: solo las casillas que cambiaron (una vaciada a propósito va `flag "0"`, `detalle ""`) y `nota` solo si cambió (`""` la vacía). Respuesta `200 {ok, id, guardadas, desconocidas}`; `guardadas` es obligatoria.
- **Registros:** `PUT consultas/{id}/registros` con `{items:[{ref:"r:<id local>", tipo, id?, fecha?, campos, borrado?}]}`. Estudios: `fecha` (`YYYY-MM-DD`) y `campos:{detalle}` (`""` = vaciado, nunca `borrado`). Exámenes e interconsultas: `campos` con solo los textos que cambiaron (`solicito`, `respuesta`, `especialista`), **sin fecha ni número**; la baja va `campos:{}`, `borrado:true` y solo de una fila que la web ya tiene. `id` es el `remoto_id` si la app lo conoce. Respuesta `200 {ok, ids:{<ref>:<id>}, guardadas:[<ref>], desconocidas:[<ref>]}`; `ids` y `guardadas` son obligatorios.
- **No viaja todavía (preguntas abiertas):** la nota del perfil 4 (Q-E2-1), la baja de un estudio (Q-E2-2), `consulta_respuesta` al completar de otra consulta (Q-E2-4), `fecha`/`numero` de exámenes e interconsultas (Q-E2-5). El tope de largo (Q-E2-3) está expuesto en `TopesDeEscritura` pero la app no lo aplica.
- `dependiente_terceros` (clave de la app) viaja como una de las 15 casillas; la columna es `dependientes_terceros`.
#### Qué cambia respecto del contrato provisorio, por lo que mostró el esquema real
1. **Tope de largo:** solo `estudios_cardiologicos.descripcion` (191 caracteres); el resto es `text` o `longtext`. El borrador decía 191 para los cinco estudios y para los textos de exámenes e interconsultas.
2. **`fecha` de estudio:** obligatoria en el contrato aunque cuatro columnas admitan NULL (la lectura ya tolera una fecha NULL, pero RF-39 habla de «fecha y descripción» y cardiología la exige).
3. **Totales y fechas del relleno:** `total_puntos` y todas las fechas las pone el servicio; `ColumnasLegacy::vacias` pone el día en UTC y no sirve para fechas clínicas.
4. **Fila a escribir:** la que lee la lectura (`activo = 1`, mayor id), no la que edita la web (`first()` de menor id con `activo != 0`).
5. **Perfil 4:** la tabla no existe en la copia local, así que no está en el contrato `[Q-E2-1]`.

#### Cada pregunta abierta Q-E2-*, con lo que la motiva (T48 verificó que lo cite)
| Pregunta | Columna / archivo que la motiva | Qué aclara T48 (sin resolverla) |
|---|---|---|
| Q-E2-1 nota del perfil 4 | `antecedentes_familiares_perfil4.nota` (tabla opcional); `MedicoController.php:1390-1408`; `ConsultasService.php:168-176`; `LecturaClinica.kt:73` (`CLAVE_NOTA_PERFIL4`) | La tabla **no existe** en las dos bases locales; la migración del clon (`2026_08_18_000000_…`) no se corrió. «No probable de verificar en local» se vuelve «probable si se corre la migración en la copia local» |
| Q-E2-2 estudio en blanco | `<estudio>.descripcion` NOT NULL (acepta `''`); `MedicoController.php:6075-6078`; `ConsultasReglas.php:569-572`; `EstudioConHistorial.kt:41-42` (`cambioEstudio` → `Borrar`) | Vaciar un estudio (`descripcion = ''`) lo oculta en la lectura: se ve igual que una baja. La web no tiene baja de estudios |
| Q-E2-3 tope de largo | `estudios_cardiologicos.descripcion varchar(191)` (única); `AppServiceProvider.php:31`; `config/database.php:59` (conexión estricta) | **La premisa no se cumple en la base real**: solo cardiología tiene 191; el resto es `text` (65535 bytes) o `longtext`. En estricto, pasarse da error de base, no corta |
| Q-E2-4 completar de otra consulta | `examenes_complementarios.consulta_respuesta` / `interconsultas.consulta_respuesta` (int NOT NULL); `MedicoController.php:2054-2064`, `:2330-2336`; `HcRepository.kt:526-527`, `:558`; `PlanDeEnvio.kt:405-421`; `docs/hc-pediatria-estado-y-plan.md:621` | Pasa en los datos: 3 exámenes y 1 interconsulta completados desde otra consulta. La web siempre pisa `consulta_respuesta` con la consulta actual; editar por id no comprueba paciente |
| Q-E2-5 `fechaSolicitud` y `numero` | `fechaSolicitud date NOT NULL`, `numero`, `numero_consulta` int NOT NULL; `MedicoController.php:2285-2295`, `:2315-2317`; `ColumnasLegacy.php:38-57` + `config/app.php:70`; `ConsultasReglas.php:585-616` | Si nadie la manda, el relleno pone el día **en UTC**; la web usa Buenos Aires. La fecha clínica de la consulta está en `consultas.created_at` |
| Q-E2-6 idempotencia | Ninguna de las tablas tiene `UNIQUE (consulta_id, paciente_id)`; `EnviosRegistro.php:55` (`PREFIJOS = c, r, l, p`); `salud360_envios` (`remoto_id bigint unsigned`, `clave varchar(191)`) | La unicidad depende de la transacción con la consulta bloqueada; no hay filas repetidas hoy. Registrar con clave solo lo que se crea cabe en la tabla actual |

## Decisiones del plan para E2 a E4 (con la alternativa descartada)
- **P14. Contrato primero, servidor y app en paralelo.** La primera tarea de cada subentrega deja el contrato escrito; la app se construye contra un servidor falso.
  *Descartado:* que la app espere al servidor (alarga el camino sin bajar el riesgo: el E2E igual prueba los dos juntos).
- **P15. Clave natural (upsert) para lo que ya tiene una fila por (consulta, paciente) y registro con clave para lo que se crea** (Q-E2-6). *Descartado:*
  registrar todo con clave (un reenvío con otros datos no se aplicaría).
- **P16. `registros` es atómico y se identifica por `ref`.** *Descartado:* una ruta por tipo (más pedidos con un límite de 60 por minuto por IP) y aplicar los ítems
  buenos y rechazar los malos (rompe «entra completo o no entra»).
- **P17. El total de Child-Pugh lo calcula el servidor.** *Descartado:* confiar en el total del cliente (la web lo hace con JavaScript; un total inconsistente quedaría guardado).
- **P18. Antecedentes: fila nueva de la consulta, mezclada con la última activa.** *Descartado:* actualizar la última fila del paciente (pisaría el historial de otra consulta y de
  otro médico) y reescribir las quince casillas (pisa lo que el médico no tocó, RF-36).
- **P19. Cargas: `PUT` con `id` para actualizar y `DELETE` con id.** La web solo inserta y da de baja (`MedicoController.php:6195-6731`, `:3682-3734`); actualizar una carga es capacidad
  nueva que pide RF-41. *Descartado:* borrar y recrear al editar (cambiaría el id de la web en cada autoguardado y rompería el emparejamiento).
- **P20. Pendientes: fila portadora + huella (P4) y upsert en el servidor.**
- **P21. Adjuntos: controlador y servicio aparte, archivo temporal + transacción + renombrar, nunca se borra un archivo del disco.** *Descartado:* ampliar el controlador de escritura
  (mezclaría subidas de archivos con la garantía «la escritura de datos es JSON»), y borrar el archivo al dar de baja (la web no lo hace y el historial puede apuntar a él).
- **P22. Una constante de subentrega por tarea de habilitación** (T67, T88, T108), la última antes del E2E de cada subentrega. Si el usuario acepta el orden alternativo (Q-B), una tarea chica
  anterior la reemplaza por un conjunto de secciones habilitadas. *Descartado:* habilitar de a poco sin tarea propia (se publicaría una versión intermedia por accidente).
- **P23. Pedidos por envío.** RF-28 pide fijar y justificar el número. Una consulta completa con cola real manda, en una ráfaga y en este orden: crear (1), secciones (1), examen (1), formularios
  (1), antecedentes (1), registros (1), cargas (una por carga nueva o tocada), pendientes (1), adjuntos (uno por archivo) y cierre (1); lo ya enviado no se reenvía, así que una ráfaga normal de
  cambios cuesta, **según una estimación del planificador (a medir en T68, T89 y T109)**, de 2 a 5 pedidos. Con 60 por minuto por IP y la espera de 4 s, un consultorio con varios médicos no se acerca al límite; si un envío grande se acerca, `429` frena a **todas**
  las consultas (T27). *Descartado:* juntar todo en un solo pedido (perdería el envío parcial y la confirmación por parte, RF-32).

## Cobertura de E2 a E4 (se suma a «Estrategia de tests»)
| RF | Cobertura |
|---|---|
| RF-35, RF-37, RF-38 | PHP `FormulariosReglasTest`, PHP-base (T54); jvm `PayloadsClinicaE2Test`, `PlanDeEnvioE2Test`, `HcClinicaEnvioFormulariosTest`; E2E `HcClinicaFormulariosListasE2ETest` |
| RF-36, RF-3, RF-9 | PHP `AntecedentesFamiliaresReglasTest`, PHP-base (T55); jvm `HcClinicaEnvioAntecedentesTest`, `HcClinicaBackendPropiasE2Test`; E2E |
| RF-39 | PHP `RegistrosReglasTest`, PHP-base (T56); common `LimitesDeCampoTest`; jvm `HcClinicaEnvioRegistrosTest`; E2E; manual (T71) |
| RF-40, RF-70 | PHP `RegistrosReglasTest`, `RegistrosBajaTest`, PHP-base (T57, T58, T59); common `ReglasDeRegistroTest`; jvm `HcRepositoryRegistrosTest`, `HcClinicaBackendPropiasE2Test` (las `activo = 2` no vuelven hasta cerrar); E2E |
| RF-41, RF-42 | PHP `CargasReglasTest`, PHP-base (T76, T77); jvm `MigracionLaboratorioTest`, `HcRepositoryCargasTest`, `HcClinicaEnvioCargasTest`, `HcClinicaBackendPropiasE3Test`; E2E `HcClinicaLaboratorioE2ETest` |
| RF-43 | PHP `PendientesReglasTest`, PHP-base (T78); common `PendientesTextoTest`; jvm `HcRepositoryPendientesTest`, `HcClinicaEnvioPendientesTest`, `HcClinicaBackendPropiasE3Test`; E2E |
| RF-44 | PHP `AdjuntosReglasTest`, PHP-base (T97, T99); common `AdjuntosClinicaTest`; jvm `ClinicaAdjuntosApiTest`, `HcClinicaEnvioAdjuntosTest`; E2E `HcClinicaAdjuntosE2ETest` |
| RF-45 | PHP-base (T98); jvm `HcClinicaBackendAdjuntosTest`; manual (T112) |
| RF-46 | PHP-base (T98); common `AdjuntosClinicaTest`, jvm `AdjuntosPropiosTest` (nunca sin consulta ni de otra HC); E2E |
| RF-15, RF-61 en E2 a E4 | PHP `EnviosRegistroTest` (T53) + PHP-base; jvm `HcClinicaEnvioRepetibleTest` y `…AtomicoTest` ampliados en T63, T85 y T104 |
| RF-16 en E2 a E4 | E2E de cada subentrega: `medico_infos` idéntico (también tras laboratorio y peso) |

## Riesgos de E2 a E4 (se suman a «Riesgos»)
| Riesgo | Mitigación |
|---|---|
| **La app de E1 nunca habló con el servidor real** (todo con servidores falsos) | T40 antes de T61 (ver «Qué de E1 bloquea a E2» en `tasks.md`); el primer E2E real incluye E1 |
| **Completar un examen de una consulta anterior** no cabe en el modelo «cada consulta envía lo suyo» (RF-68) | Q-E2-4; la tarea que lo toca (T62, T64) queda bloqueada hasta la respuesta |
| **Las filas `activo = 2` no vuelven en `historia` hasta cerrar** y podrían parecer «ya no figura en la web» | Test explícito en T65; la marca solo se pone a lo que la app ya había traído |
| **La web reactiva lo dado de baja si alguien cierra en la web una consulta de la app** (`setActivoExamenesComplementarios`/`Interconsulta`, `MedicoController.php:2225-2278`) | Ya declarado; documentado en T60 y en el manual |
| **Migración v11 sin vuelta atrás y un solo puerto de `devWeb` (8080)** | Aviso al usuario en T80; 001 y 002 se publican juntas |
| **Tope de largo de las columnas `string` (191)** distinto en producción | T48 mide la local; producción solo con su aprobación; el servidor devuelve `texto_demasiado_largo` en vez de truncar |
| **Archivos en disco: carpeta, permisos, límite y espacio en el hosting** | T95 mide lo local; el paso de hosting se lista en T113 y se hace en la etapa de producción con aprobación; el servidor nombra la ruta si no puede escribir |
| **Una subida cortada deja fila sin archivo o archivo sin fila** | Temporal + transacción + renombrar (T97); la lectura de la web ya responde 404 si falta el archivo |
| **Más rondas de reviewer y prueba manual si se adopta el orden alternativo** | Es la contrapartida de Q-B; el usuario decide |
