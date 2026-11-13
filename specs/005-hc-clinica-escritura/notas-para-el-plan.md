# Notas para el plan — spec 005 (escritura de la HC general)

Estado: **apoyo del planificador, no es una spec**. No fija requisitos: junta lo técnico que la spec 005 no debe llevar (rutas,
nombres de archivos, columnas, números de línea, límites del hosting) para que quien escriba el `plan.md` lo tenga. Si algo de acá
choca con la spec, manda la spec. Creado el 2026-11-04; actualizado el mismo día tras la segunda clarificación (D16, D18, D25 a
D27 y observaciones del QA).
Rutas relativas a este repo; `hclinica` = `C:\Users\flor\rebonline\hclinica` (solo se leyó). No se ejecutó nada contra bases.
Repo público: nada de datos reales, tokens ni nombres de personas.

## 1. Lo que la autorización D3 permite y cómo se despliega
El usuario autorizó (D3) tocar el Laravel de hclinica (rama `salud360-api`) para **todas** las rutas de escritura juntas, para
implementarlas y probarlas en el clon y en la copia **local** servida. Producción, aparte, con respaldo probado (D4). Es una
decisión del usuario contra la recomendación de ir por subentregas, así que cada ruta dice de qué subentrega es y que **no se
despliega a producción hasta que su subentrega esté implementada y validada en la app**. (El planificador lee «no se despliega»
como «a producción»; el clon y la copia local las tienen todas desde el principio: pendiente de validar, D19(c) de la spec.)

### Rutas provisorias (los nombres los fija el plan)
Todas dentro del grupo de la API de la app (middleware que valida el token de turnos, la licencia y el médico). Hoy **no existe
ninguna ruta de escritura de consultas** [VERIFICADO: `hclinica/routes/api.php:34-64`, solo `GET` de consultas e historia, pacientes
y enfermedades].

| Sub | Ruta provisoria | Para qué | Se despliega a producción cuando |
|---|---|---|---|
| E1 | `POST consultas` | Crear la consulta, idempotente por una clave del dispositivo (D21), a nombre del médico del token; informa si el paciente tenía otra abierta (RF-59) | E1 cerrada |
| E1 | `PUT consultas/{id}/secciones` | Textos (incluye el vaciado a propósito, RF-69) | E1 cerrada |
| E1 | `PUT consultas/{id}/examen` | Examen físico | E1 cerrada |
| E1 | `POST consultas/{id}/cerrar` | Cerrar con fecha y edad; confirma solo exámenes complementarios e interconsultas pendientes de confirmar (D20) | E1 cerrada |
| E1 | `DELETE consultas/{id}` (baja lógica) | Anular una consulta ya enviada y todavía abierta (D14); **no** hay `reabrir` (D13) | E1 cerrada |
| E2 | `PUT consultas/{id}/formularios` (o dentro de `secciones`) | Alimentación, serologías, Child-Pugh | E2 cerrada |
| E2 | `PUT pacientes/{id}/antecedentes-familiares` | Antecedentes familiares y nota (sin tabaquismo) | E2 cerrada |
| E2 | `PUT consultas/{id}/registros` | Estudios, exámenes complementarios e interconsultas (devuelve los ids de la web) | E2 cerrada |
| E2 | baja de examen complementario e interconsulta (en `registros` con marca de baja, o ruta propia) | **Capacidad nueva del servidor** (D27, RF-70): la web no la tiene | E2 cerrada |
| E3 | `PUT/DELETE pacientes/{id}/laboratorios` y `/pesos` | Cargas con fecha, visibles al instante (D20); devuelve los ids de la web | E3 cerrada |
| E3 | `PUT consultas/{id}/pendientes` | Texto único de pendientes (D10) | E3 cerrada |
| E4 | `POST consultas/{id}/adjuntos`, `DELETE`, `GET …/archivo` | Subir, dar de baja y bajar; y los adjuntos dentro de `consultas/{id}` | E4 cerrada |
| E4 | `GET` de límites del hosting | Leer el límite de subida sin escribir; en producción solo tras aprobarla (D18, parte de lo autorizado por D3) | E4 cerrada |
| E5 | `GET licencias`, `PUT licencias/{medicoIdTobb}` | Panel del administrador (el estado de licencia ya viaja en `auth/perfil`) | E5 cerrada |

### Tabla propia de idempotencia (D21)
- Tabla chica propia de la API, creada por la API como las otras tablas `salud360_*` (el middleware crea `salud360_token_cache` y
  `salud360_vinculos_pendientes` con `Schema::create` la primera vez; necesita privilegio `CREATE`). Guarda, por médico y
  dispositivo, la clave de cada envío y su resultado (id de la web) para responder lo mismo ante un reenvío.
- Alcance: creación de la consulta y filas hijas (laboratorio, peso, registros, pendientes, adjuntos).
- **No se agrega ninguna columna a las tablas de la web.** En producción es una tabla nueva en la base de producción: se
  aprueba aparte junto al respaldo (D4). Hoy el consumo es solo local.
- La clave sirve también para saber que una consulta **la creó la app** (RF-52, D1 del lado del servidor).
- **Retención permanente (RF-15):** sin vencimiento ni limpieza automática; entra en el respaldo de la base. Si se perdiera, el
  servidor trata como nuevo lo que no reconoce y un reenvío puede duplicar: mitigaciones — respaldo, no vaciar la tabla en las
  pruebas (el E2E limpia sus filas **por identificador**), y que la app no reenvíe lo que ya tiene confirmado (RF-61).

## 2. Cómo escribe hoy la web de hclinica (verificado)
Archivo: `hclinica/app/Http/Controllers/MedicoController.php`.
- **Crear (camino activo)** `crearNuevaConsulta` (`:3008-3050`): consulta nueva con `activo = 2`, `medico_id` del usuario, **sin mirar
  si ya hay una abierta**; para el perfil 2 llama a `inicializarCampos` (`:2954-3006`), que crea filas vacías en motivo (con fecha) y en
  los cinco estudios; además **escribe `medico_infos`** (la pantalla del médico): la API nunca lo hace (RF-16). El camino que
  reutilizaba la abierta (`nuevaConsulta`, `:282-320`, y `consultaGet`, `:252-275`) está marcado DEPRECATED.
- **También `guardarLaboratorioGeneral`, `guardarLaboratorioGeneral2` y `guardarPeso` escriben `medico_infos.es_nueva_consulta = 8`**
  (`:6531`, `:6651`, `:6727`), y otras acciones de la web lo ponen en 0, 1, 3 o 4 (`:909-944`, `:2503-2505`, `:3036-3072`, `:3136`).
  **[Corregido el 2026-11-07, ver §12; reverificado en T73 el 2026-11-12 contra el clon]** el de `guardarPeso` (`:6722-6728`) está **comentado** (`/* ... */`, línea por línea igual a los otros dos): lo escriben el laboratorio general
  (`:6526-6532`) y el reducido (`:6646-6652`) y las fotos de exámenes (`:2497-2506`). La puerta RF-16 no cambia (la API no escribe `medico_infos`).
  Importa para la **puerta bloqueante de RF-16**: la API **no replica** esas escrituras (cargar laboratorio o peso por la API no
  cambia ese estado), y la comparación antes/después de la prueba debe incluir esa tabla **después de cargar laboratorio y peso**.
- **Cerrar** `establecerActivo` (`:3442-3461`): `activo = 1`, cambia `created_at` por la fecha enviada y la edad, y llama a
  `setActivoLaboratorioGeneral/2`, `setActivoPeso`, `setActivoExamenesComplementarios`, `setActivoInterconsulta` (`:2225-2280`), que
  ponen `activo = 1` en **todas** las filas de la consulta, **también las dadas de baja**. La app no copia ese defecto (D14, D27):
  el cierre de la API confirma solo exámenes e interconsultas con `activo = 2` y **no toca laboratorio ni peso** (D20).
- **Qué nace pendiente de confirmar:** solo exámenes complementarios e interconsultas nacen con `activo = 2` (`:2095`, `:2321`).
  **Laboratorio general, reducido y peso nacen con `activo = 1`** (`:6523`, `:6643`, `:6719`): son visibles al instante. Una versión
  anterior de la spec (y de la anotación en la 002) decía lo contrario; era falso y se corrigió con D20. Como el cierre de la web
  reactiva todas las filas, un médico que **cierre en la web** una consulta de la app puede resucitar filas que la app dio de baja:
  riesgo conocido, fuera de lo que la app controla.
- **Dar de baja:** la web tiene funciones para dar de baja **laboratorio general, reducido y peso** (`activo = 0`, solo si estaban en
  `activo = 1`: `:3682-3730`), **PDF** (`:3657`), **audio** (`:4033`), un **interconsultor** de la lista del médico (`:4699`; no es una
  interconsulta clínica) y la enfermedad del paciente (`:784`). **No se encontró ninguna función para dar de baja exámenes
  complementarios ni interconsultas.** → RF-70 es una **capacidad nueva del servidor**: dar de baja esas filas (`activo = 0`, nunca
  `DELETE`), y que el cierre no las reactive.
- **No existe reabrir**: ningún camino pone `activo = 2` a una consulta ya cerrada (solo los de creación).
- **Pendientes**: `aux_pendientes` guarda una fila de texto por (paciente, consulta) (`:4808-4842`).
- **PDF**: `consulta_pdfs`, hasta 15 MB, ruta `img/<usuario>/consulta_pdf` creada con `mkdir` (`:3592-3655`); puede colgar de una
  consulta o del paciente (`consulta_id` 0). En el hosting de pediatría `mkdir` no estaba permitido y las fotos van a una carpeta
  creada a mano (`docs/hc-pediatria-estado-y-plan.md`).
- **Fotos**: hay `consulta_fotos` y `examenes_complementarios_fotos` (esta última sí se usa en la pantalla de exámenes, `:2367-2390`);
  las fotos de consulta tipo 3 son la HC digitalizada (`crearNuevaConsultaFoto`, fuera de alcance). **No se verificó** que la web
  actual ofrezca una foto «común» de consulta: duda D24.
- **Mapa inverso**: `MapaClinica::columnaWeb` ya existe (`hclinica/app/Services/Salud360/MapaClinica.php:253-258`); Q12 = A se cumple
  al revés sin mover nada.
- **Modo estricto**: la web tiene casi todas las columnas `NOT NULL` sin valor por defecto (E3.1 de la 002) y la base de producción es
  probablemente estricta; la local no. `ColumnasLegacy` (ya en hclinica) rellena columnas como en pediatría.
- **Límites del web/hosting**: la API de la app va con `throttle:60,1` por IP (60 pedidos por minuto) y el hosting tiene pocas
  conexiones: el plan fija y justifica el tope de pedidos por consulta enviada (RF-28 no lleva el número).
- **Licencia**: el middleware exige licencia vigente en cada pedido y corta con `403 licencia_vencida` también sin fila (`licencia`
  nula en el cuerpo); `auth/perfil` ya devuelve `licencia` (vence, aviso, por vencer) y el administrador no tiene
  (`hclinica/md/API_SALUD360_CLINICA.md:63-103, 146-168`; `AuthController.php:50-53`). Durante la gracia (turnos caído) el servidor de
  clínica **sí** mira la licencia (a diferencia del de pediatría): ver el caso 5 de la 004 en la enmienda. **No hay rutas `licencias`**.
- **Lo que el middleware escribe** (insumo del cambio propuesto al RF-50 de la 002): la caché de tokens (`salud360_token_cache`), la baja
  de la licencia vencida (`medico_licencias.activo = 0`), la lista de médicos sin vincular (`salud360_vinculos_pendientes`) y la
  creación de esas dos tablas la primera vez; turnos además marca `ultimo_uso_en` en su sesión. Con D21 se suma la tabla de
  idempotencia (solo en las escrituras).

## 3. Lo que la L ya construyó y la E tiene que abrir (app)
- `HcBackend.soloLectura` y el candado del motor de envío: `core/data/.../repos/HcBackend.kt:37`; `core/data/.../sync/HcApiSync.kt:68-96`
  (`esDeSoloLectura`, `marcarSucia`, `empujarYa`), `:120` (`drenar` sale si el backend es de solo lectura), `:230`
  (`consultasDeAntecedentesPendientes`). `HcClinicaBackend.soloLectura = true` (`repos/HcClinicaBackend.kt:85`) y todo método de
  envío lanza `UnsupportedOperationException` (`:377-395`). El cliente de clínica es `HcApiSoloLectura` (no tiene `post`).
- Guardas por identificador en `HcRepository` (`repos/HcRepository.kt:129-164, 183-197, 221-225, 251-257, 290-359`): lo que empieza con
  `hcc-` o es una consulta con `remoto_id` de un backend de solo lectura no se escribe. `esSoloLecturaWeb`
  (`hcgeneral/SoloLecturaDeLaWeb.kt:20-27`) y `Consulta.esDeLaWeb()` = `remoto_id` no vacío **o** prefijo `hcc-`
  (`hcgeneral/LecturaClinica.kt:93`). Efecto: una consulta propia, una vez enviada (con `remoto_id`), pasa a contarse «de la web»
  en la lista (`hcgeneral/ConsultaWeb.kt:16`): RF-62 pide que siga editable y en «Consulta en curso» mientras esté abierta.
- `abrirConsulta` con backend de solo lectura usa `consultaAbiertaLocal` (ignora las de la web): `repos/HcRepository.kt:117-127`.
- La lectura **no refresca** una consulta propia ya enviada: `aplicarLista` la trata como `intocable` si el dispositivo ya tiene otra
  fila con su `remoto_id` (`repos/HcClinicaBackend.kt:187-189`) y `traerConsulta` solo acepta ids `hcc-c<id>` (`:304-309`). → RF-33
  es trabajo nuevo.
- La lectura empareja las filas del historial **por id**, no por identificador de la web: `registroPorId(web.id)`
  (`repos/HcClinicaBackend.kt:264-267`), `laboratorioPorId` (`:257-260`), `pendientePorId` (`:277-281`). Una fila enviada por la app
  (id propio, id de la web aparte) reaparecería como fila `hcc-…` duplicada. → RF-33 (emparejar) es trabajo nuevo.
- `AdminRepository` ignora los backends de solo lectura para licencias; cuando clínica deje de serlo (E1), `guardarLicencia` de un
  médico solo de clínica llamaría al `guardarLicencia` por defecto de `HcBackend` (devuelve `false`) → RF-66 (E1) pide que no cambie
  nada hasta E5 (test: médico solo de clínica sigue guardándose sin error y sin llamar al servidor).

## 4. El motor de envío y lo que hoy no cubre
- Espera de 4 s y reintentos de 15 s, 60 s y 300 s, **y después se rinde** hasta el próximo disparo: `sync/HcApiSync.kt:189-205,
  238-244`. RF-29 pide que **no se rinda**: reintento periódico con espera acotada mientras la app esté abierta (el plan fija el
  intervalo sin superar el límite de pedidos de la web).
  **Los disparadores «conexión recuperada», «abrir la app» e «iniciar sesión» no existen**: `empujarTodo` existe pero nadie lo dispara
  en esos momentos para clínica (RF-57 lo declara trabajo nuevo por plataforma: web, Android, iOS).
- **Solo registra el error en el log**: no hay motivo visible ni distinción entre error que se arregla reintentando y rechazo
  (`:189-205`). → RF-6, RF-30 y RF-31 son trabajo nuevo. `HcBackend` no tiene laboratorio ni pendientes ni estado de rechazo
  (`repos/HcBackend.kt:27-102`).
- `empujarTodo` y `recontar` miran consultas, secciones, examen, adjuntos y antecedentes/listas, **no** laboratorio ni pendientes
  (`sync/HcApiSync.kt:100-111, 208-216`); `laboratoriosDirty`/`pendientesDirty` solo los usa el motor viejo hacia turnos
  (`sync/SyncAdapters.kt:104, 114`, 404 conocido). → RF-31 los incluye.
- Los antecedentes se mandan sin mirar `activo` (`sync/HcApiSync.kt:133-134`) → RF-9.
- El examen físico se arma con `HcPediatriaBackend.aCampos` (de pediatría) (`sync/HcApiSync.kt:180`) → clínica necesita el suyo.
- `archivosPendientesDeConsulta` toma por paciente los adjuntos sin consulta de cualquier HC (`core/database/.../HistoriaClinica.sq:220-224`;
  límite ya fijado en un test de la L) → RF-46 y RF-43.
- **El envío de una misma consulta hoy puede correr en paralelo consigo mismo**: `drenar` se lanza desde `marcarSucia`, `empujarYa`,
  `empujarTodo` y los reintentos sin un candado por consulta (hay un `Mutex` solo para el mapa de trabajos) → RF-54.
- `drenar` limpia lo enviado en pasos separados (`limpiarSeccionValor`, `limpiarRegistro`, `guardarRemotoIdRegistro`, …): un corte en
  el medio deja lo enviado como pendiente → RF-61 pide que un corte no deje lo enviado como pendiente ni duplicado (en la misma
  transacción, o con una reconciliación al reintentar).
- `SyncEngine` (motor viejo hacia turnos) **no pasa por el candado** y sus adaptadores incluyen las tablas clínicas, también las de
  clínica (`sync/SyncAdapters.kt`); cada minuto pega a rutas de turnos que no existen (404 ignorado). **Riesgo declarado:** con
  el histórico en `dirty = 1` (RF-7) y las tablas de HC con envío propio, si alguien crea esas rutas en turnos saldría todo (ya
  documentado en `AGENTS.md`). **El plan debe cerrarlo**: excluir del motor viejo las tablas de HC que tienen envío propio, o
  apagarlo.
- Pediatría es el modelo y no se toca: `repos/HcPediatriaBackend.kt:76-140`. Pediatría **no** envía laboratorio ni pendientes.

## 5. Esquema local: dos migraciones (D26 y D8)
- Hoy: migraciones `1.sqm` a `8.sqm` en `core/database/src/commonMain/sqldelight/com/salud360/core/database/migrations/` (esquema v9)
  en `hc-general`; `origin/main` llega a `5.sqm` (v6). Sin vuelta atrás en un dispositivo migrado: la web descarta la base si la
  guardada es más nueva que la de la app (`DriverFactory.wasmJs.kt`). 001 y 002 se publican juntas, así que main (v6) pasa a la
  versión nueva de una vez.
- **Migración de E1 (D26), chica:** una **columna al final de la tabla de consultas** (`upsertX` es posicional) que marca cada
  consulta de la HC general como histórica o propia. Lo que existe al migrar queda como histórica (el valor por omisión de la
  columna para las filas existentes); lo creado después, como propia (la app la escribe al crear). Regla de datos por paciente
  (RF-68) **derivada de la marca de la consulta**: un dato por paciente viaja solo si su `consulta_id` apunta a una consulta propia;
  los de consulta histórica o sin consulta no viajan; al editar una casilla o fila del histórico dentro de una consulta propia, el
  plan reasigna su consulta de origen a la propia (el repositorio ya guarda `consulta_id` al guardar). Descartado por el usuario:
  una regla por fecha (frágil, depende del reloj; y editar mueve `updated_at`).
- **Migración de E3 (D8), distinta:** agrega **`remoto_id` al final** de `laboratorio` y de `pendiente` (`upsertX` es posicional) y
  actualiza su `.sq`. En `pendiente`, el identificador corresponde al **texto único de la consulta** (RF-43): candidatos para el
  plan sobre qué fila lo lleva: (a) la primera fila de pendientes de la consulta como «portadora» del identificador del conjunto,
  o (b) una fila `paciente_extra` por consulta (sin esquema nuevo) con el id de la web del texto. Al volver a leer, si el texto de la
  web coincide con el último enviado (guardar su huella), no se crea ningún `hcc-p…` nuevo.
- **Numeración:** cada migración lleva **su número**, reservado **mirando todas las ramas** (`git branch -a`, `git ls-tree` de
  `origin/main` y de cada `hc-*`) **justo antes de crearla**, y coordinado con las demás PC (la guía
  `docs/trabajo-por-historia-clinica.md` ya está en `origin/main`). Hoy serían `9.sqm` (E1) y `10.sqm` (E3). Si otra rama ya tomó el
  número, **se vuelve a preguntar**. Cada una con su `.sq` actualizado y columnas siempre al final.
- `laboratorio` y `pendiente` **no tienen `remoto_id`** (`HistoriaClinica.sq:158-171, 227-237`); `registro_clinico` y `archivo` sí
  (`:141-143, 199-203`). `pendiente` y `archivo` **no tienen especialidad**: el plan decide a qué HC pertenecen por su consulta
  (RF-43, RF-46).
- La tabla local de licencias tiene como clave solo el médico (`Auth.sq:94-109`; `core/model/.../auth/Medico.kt:47-54`): no
  distingue la HC. Con D11/D22 no se toca; la licencia de clínica no se copia localmente.
- Reglas de limpieza al enviar (RF-60/RF-61): lo que nunca viaja queda con `dirty = 0` sin figurar como enviado; el id de la web se
  persiste en la misma transacción que limpia el pendiente.

## 6. El catálogo viejo todavía ofrece lo que la web no tiene (D9)
- `tabaquismo` está entre las casillas de los **antecedentes familiares** de clínica (`especialidades/clinica/.../ClinicaMedica.kt:58`);
  frecuencia cardíaca y circunferencia abdominal, en el **examen físico** (`:63`, y `:110` para hepatología); diagnósticos y audio en
  las secciones por defecto (`:42, :48-49, :78`). La decisión del 2026-10-09 no se aplicó. La L lo dejó (Q13) y un test lo fija
  (`especialidades/clinica/.../LecturaClinicaCatalogoTest.kt:136-138`). Con D9 hay que cambiar el catálogo y ese test: es un cambio
  de requisitos sobre la 001, que el plan declara.
- «Antecedentes personales» (RF-3) es la **sección legada** del catálogo viejo; no confundir con la nota de antecedentes que algún
  perfil rotula «Antecedentes personales» (002, decisiones del 2026-10-09: un mismo dato con distinto nombre por perfil), que viaja con
  los antecedentes familiares (RF-36). El plan lo contrasta con los perfiles reales.

## 7. Enmienda propuesta a la spec 004 (D22, D25) — NO aplicada
La 004 se escribió solo para pediatría. D22 extiende «HC que lleva licencia» a la HC general desde E1 y D25 fija la regla con dos
HC. **Rama donde se aplica:** la spec 004 existe en `origin/main` y en `hc-general`; el **código** de la 004 solo está en
`origin/main` (T1 a T7). La enmienda **de texto** se aplica donde se integre la T9 (cherry-pick del código a `hc-general`) y se avisa
en **ambas** ramas para que no diverjan. No se edita la 004 ni la 001 desde la spec 005. Cambios, punto por punto:

- **Contexto y Usuarios:** «Hoy es la única HC con licencia» y «Médico sin ninguna HC que lleve licencia (solo agenda, o solo la HC
  general)» → pasan a «pediatría y HC general»; sin licencia queda «solo agenda».
- **HU-2** («un médico al que di de baja la licencia quede afuera en su próximo ingreso o recarga»): vale solo cuando **todas** las
  HC con licencia del médico están vencidas; si queda una vigente, entra y la vencida queda frenada (D25).
- **Definiciones:** «Consulta de licencia», «Respuesta concluyente» y «Sin respuesta concluyente» se evalúan **por HC** (pediatría y
  clínica); «Copia local» sigue siendo solo de pediatría (D11: sin copia local por HC).
- **RF-1, RF-2, RF-3:** la consulta al servidor es a cada HC con licencia que el médico tenga; la memoria de 60 s es por HC y por token.
- **RF-4:** cambia de alcance. La premisa «médico solo de la HC general = sin licencia» **deja de valer**: el médico solo de clínica
  ahora se consulta; el administrador, la secretaria y el médico sin ninguna HC con licencia siguen sin consulta.
- **RF-5, RF-6, RF-10:** se aplican por HC; con dos HC rige la regla de combinación (abajo). RF-5 («no dejar entrar») pasa a «no
  dejar entrar si todas las HC con licencia están vencidas».
- **RF-7, RF-8, RF-9, RF-11, RF-12:** sin respuesta concluyente decide la copia local **solo para pediatría**; para clínica no hay
  copia (D11) y la regla de RF-8 (sin copia, entra) es la que vale; RF-11/RF-12 (actualizar la copia) solo para pediatría.
- **RF-14:** «a lo sumo un pedido adicional por inicio o restauración» pasa a «a lo sumo uno por HC con licencia» (dos para pediatría
  más clínica); revisar el límite por IP.
- **RF-15:** los topes de 10 s y 5 s se aplican al conjunto de las consultas (en paralelo), no a cada una en serie.
- **RF-17:** el panel de licencias sigue como hoy para pediatría; el de clínica es E5 de la 005.
- **RF-18** («esta spec no agrega ningún texto»): la 005 exige un motivo «En espera» que **nombra la HC**; la enmienda permite un
  texto parametrizado con el nombre de la HC (o reutilizar el existente con el nombre agregado) y deja de decir «no agrega ninguno».
- **RF-19:** el servidor de clínica ya informa la licencia en el perfil.
- **RF-21/RF-22:** la distinción «sin licencia» (`licencia` nula) vs «vencida» ya vale para el servidor de clínica (mismo formato del
  cuerpo).
- **Casos límite:**
  - El **7** («Médico que además es de otra HC sin licencia (p. ej. la HC general): esa HC no cuenta») **deja de valer**.
  - El **2** («licencia vencida o dada de baja en el servidor: no entra / se le cierra la sesión») pasa a «no entra solo si todas las
    HC con licencia están vencidas».
  - El **14** («si además tiene HC general, esa funciona») pasa a «esa funciona si su licencia está vigente»; con clínica sin fila o
    vencida queda frenada.
  - El **5** (gracia de 24 h del servidor) no aplica igual a clínica, cuyo servidor sí mira la licencia durante la gracia.
  - Los casos 1, 3, 4, 6, 8, 9, 11 a 13, 15 y 16 se leen por HC.
- **Caso 10 y Fuera de alcance («reaccionar a un `licencia_vencida` que llegue desde cualquier otra pantalla de HC»):** la 004 lo deja
  fuera. La 005 (RF-6) exige que un `403 licencia_vencida` **durante un envío** pase a «En espera» con el motivo; la enmienda lo
  acota **solo al camino de envío de la HC general**, no a las demás pantallas de HC.
- **Fuera de alcance:** «Cambios en los servidores (…hclinica) y en la rama hc-general/clínica» deja de valer para lo de clínica (no
  hay cambios de servidor: ya informa la licencia). La integración a `hc-general` (T9) pasa a ser requisito de esta enmienda.
- **Sin cambios en la base local** (sigue valiendo: no hay copia de clínica).
- **Regla de combinación (D25, decidida; defensiva porque el médico paga por el total y el caso no se espera):**
  - Se consulta cada HC con licencia del médico, en paralelo.
  - Se bloquea el ingreso a toda la app **solo si todas** las HC con licencia contestan «vencida» (con fecha).
  - Si al menos una está vigente, sin fila o sin respuesta concluyente, el médico entra y la HC vencida queda sin poder usarse: el
    servidor sigue rechazando cada lectura y cada envío, la pantalla de esa HC muestra el texto de licencia vencida y lo pendiente
    de esa HC queda «En espera» (RF-6 de la 005).
  - Médico **solo de clínica**: vencida con fecha → no entra; sin fila → entra y la HC queda frenada (P3 de la 004); vigente →
    entra; sin respuesta concluyente → entra (no hay copia local de clínica).
  - Médico de **pediatría y clínica con una vencida y la otra vigente o sin fila**: entra; la vencida queda frenada.

## 8. Lo que pasa al plan (resumen)
Ver la sección «Hallazgos de la clarificación» de la spec. Pasan al plan: reserva de los números de las dos migraciones (17); qué fila
local lleva el identificador del texto único de pendientes (RF-43); disparadores por plataforma (21); detalle del guardado de licencias
sin regresión (29); detalle del E2E (31); cómo se persiste la HC de un pendiente (35); cierre del riesgo de `SyncEngine` (43); cómo
trata la web una consulta dada de baja con datos por paciente ya enviados (23); lectura de límites y descarga con sesión (26); qué ve
el segundo médico de lo pendiente del primero en el mismo dispositivo (RF-50).

## 9. Tareas del plan que NO son decisiones
- **Comprobación de solo lectura antes de publicar (D25):** cuántos médicos de clínica tienen la licencia vencida o sin fila de
  licencia (con D22 quedarían afuera o con la HC frenada). Solo `SELECT` sobre la copia local primero; en producción solo con la
  aprobación de producción. Resultado sin nombres de personas ni datos de pacientes.
- **Registro de lo recibido (D21):** no vaciarlo en el E2E; limpiar sus filas de prueba por identificador; incluirlo en el respaldo.
- **Puerta RF-16:** comparar `medico_infos` (incluido `es_nueva_consulta`) antes y después de crear consulta, cargar laboratorio y
  peso, y cerrar.
- **Cambio del catálogo de la 001 por D9** y de su test (ver §6), declarado como cambio de requisitos.

## 10. Corrección de una afirmación falsa
El 2026-11-03 la spec 005 y las anotaciones puestas en la 002 afirmaron que el laboratorio y el peso «nacen pendientes de confirmar
hasta el cierre». Es falso (ver el punto 2). Se corrigió en la 005 con D20 y se retiró de las anotaciones de la 002 (RF-20 y RF-22).

---

# Anexo del 2026-11-07: cómo escribe hoy la web lo de E2, E3 y E4 (para las tareas T48 a T113)
Solo lectura del clon `C:\Users\flor\rebonline\hclinica` (rama `salud360-api`). Todos los números de línea son de `app/Http/Controllers/MedicoController.php`
salvo que se diga otra cosa. Nada de esto cambia la spec; las preguntas que salen están en `tasks.md` («Preguntas abiertas»).

## 11. E2 — formularios y listas
- **Alimentación** `guardarAlimentacion` `:1917-1959`: una fila por (consulta, paciente) con `activo` 1 o 2 (`existeAlimentacion` `:1961-1971`); reescribe las cuatro casillas y sus
  detalles cada vez (null → `""`); la casilla «celíaco» se guarda en `seliaco` (`:1943`) y «otra» en `otra` (`:1949`, el parámetro se llama `otro`). Siempre `activo = 1`.
- **Antecedentes familiares** `guardarAntecedentesFamiliares` `:1198-1315`: una fila por (consulta, paciente) con `activo` 1 o 2 (`existeAntecedentesFamiliares` `:1375-1385`); reescribe las 15
  casillas y la nota; `dependientes_terceros` recibe el parámetro `dependiente_terceros` (`:1294`). **Perfiles 3 y 4 de la web** (`users.perfil`): solo la nota (`:1202-1204`,
  `:1321-1338`, con las demás casillas en 0 por `antecedentesFamiliaresSetCamposPorDefecto` `:1340-1373`); el perfil 4 además tiene una **pestaña propia** que guarda en
  `antecedentes_familiares_perfil4` (`guardarAntecedentesFamiliaresPerfil4` `:1390-1408`; tabla opcional, la copia local no la tiene: ver Q-E2-1). La lectura toma la **última fila activa del
  paciente** (`ConsultasService.php:108-112`, `delPaciente` `:299-306`: `activo = 1` de consultas con `activo` 1 o 2).
- **Serologías** `guardarSerologiasHepatitis` `:5964-5999` y **Child-Pugh** `guardarCps` `:6017-6042`: una fila por (consulta, paciente) con `activo != 0` (`existeTablaConsultaPaciente`
  `:5955-5962`), alta con `activo = 1`; los valores van tal cual del navegador (serologías 0/1/2, puntos 0 a 3); `total_puntos` lo manda el navegador (`:6037`, calculado en `cps.blade.php:124-131`
  como la suma de los cinco). La lectura: `ConsultasReglas::serologia` (`:139-147`: solo 1 y 2, nunca `NO` por un 0) y `childPugh` (`:192-203`: solo 1 a 3, sin total).
- **Los cinco estudios** `:6060-6193` (videoendoscopia, ecografía, TC/RMN, biopsia hepática, estudios cardiológicos): una fila por (consulta, paciente) con `activo != 0`, alta con `activo = 1`;
  `descripcion` (null → `""`) y `fecha` en formato `d/m/Y` convertida por `convertirFechaGuardar` (`:2212-2216`). Columnas: `descripcion` `string` (191) y `fecha` `date NOT NULL` en las migraciones
  (`2024_08_25_001739_create_ecografia_abdominals_table.php:22-23`, `2025_12_10_130248_create_estudios_cardiologicos_table.php:22-23`). La web **no tiene baja de estudios**. Con perfil 2,
  `inicializarCampos` (`:2954-3006`) crea al abrir una consulta una fila vacía de motivo y de cada estudio (T11 decide si la API la replica).
- **Exámenes complementarios** `guardarExamenesComplementarios` `:2280-2350`: el alta es `activo = 2` (`:2321`), `numero_consulta` = pendientes de la consulta + 1 (`:2290-2295`), `numero` del navegador
  (`:2285-2288`), `fechaSolicitud` = hoy en el servidor (`:2315-2317`), `consulta_respuesta` = la consulta si trae respuesta (`:2307-2313`); editar una fila por id cambia `solicito` y
  `respuesta`/`consulta_respuesta` (`:2323-2337`) y **escribe `""` si el parámetro viene vacío** (la web no distingue «no tocado»). Columnas `solicito`, `respuesta` `string` (191)
  (`2020_05_15_140913_create_examenes_complementarios_table.php:22-28`).
- **Interconsultas** `guardarInterconsulta` `:2040-2110`: si ya existe una fila **confirmada** (`activo = 1`) con ese `numero` para el paciente (`existeInterconsulta` `:2030-2038`), solo se actualiza la
  `respuesta` y `consulta_respuesta` (`:2054-2064`); si no, alta `activo = 2` (`:2095`). `especialista`, `solicito`, `respuesta` `string` (191)
  (`2020_05_15_131141_create_interconsulta_table.php:22-29`).
- **Confirmación al cerrar** `setActivoExamenesComplementarios`, `…Interconsulta`, `…LaboratorioGeneral`, `…General2`, `…Peso` `:2225-2278`: ponen `activo = 1` a **todas** las filas de la consulta, también
  las dadas de baja (defecto de la web que la API no copia: `EscrituraService::confirmarPendientes` toma solo `activo = 2`).
- **Lectura de exámenes e interconsultas** (`ConsultasService::historia` `:150-161`): solo `activo = 1`; una fila `activo = 2` (pendiente de confirmar) **no se ve** hasta que se cierra la consulta.
- **Baja de exámenes e interconsultas:** no existe (D27); sí para laboratorio, peso, PDF, audio (`:4033`), interconsultor (`:4699`) y enfermedad (`:784`).

### 11.0 T48 - El esquema real de E2 (verificado el 2026-11-07, solo lectura)
**Cómo se midió.** Solo `SELECT` sobre `information_schema` y sobre las tablas de `hc_clinica` (la copia local) y `hc_clinica_respaldo` (MariaDB 10.1.37 del XAMPP). La estructura de las once tablas que existen es **idéntica** en las dos bases (columnas, tipos, nulidad, defectos e índices). Antes y después se sacó la foto de `T11` (`snapshot.cmd`): ninguna tabla cambió (ver el resultado en `tasks.md`, T48).
El servidor del XAMPP corre **sin** `STRICT` (`sql_mode = NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION`), pero la conexión de Laravel sí es **estricta** (`config/database.php:59`, `'strict' => true`): por eso un alta
tiene que llenar todas las columnas obligatorias (`ColumnasLegacy`), y un texto más largo que la columna da error (`Data too long`) en vez de cortarse.

**Columnas comunes a las once tablas que existen.** `id` `bigint(20) unsigned` PK `auto_increment` (**salvo `examenes_complementarios.id`: `int(10) unsigned`**); `consulta_id` y `paciente_id` `int(10) unsigned` NOT NULL con
clave foránea (`RESTRICT`, sin cascada) a `consultas(id)` y `pacientes(id)`; `activo` `int(11)` NOT NULL **sin defecto**; `created_at` y `updated_at` `timestamp` NULL, defecto NULL. Índices: `PRIMARY(id)`,
`KEY(consulta_id)` y `KEY(paciente_id)`; **ninguna tiene `UNIQUE`** (nada impide dos filas por consulta y paciente: lo evita el código). InnoDB, `utf8mb4_unicode_ci`. **Ninguna columna tiene valor por defecto**
(salvo los `timestamp`): todas las demás son NOT NULL a secas.

| Tabla (sección de la app) | Columnas propias, tal como están en la base real |
|---|---|
| `alimentacions` (`alimentacion`) | `hiposodica`, `diabetes`, `seliaco`, `otra` `int(11)`; `hiposodica_detalle`, `diabetes_detalle`, `seliaco_detalle`, `otra_detalle` **`text`** |
| `antecedentes_familiares` | 15 casillas `int(11)`: `hta`, `dbt_nir`, `dbt_ir`, `acv`, `dsl`, `fa`, `mp`, `analfabeto`, `postrado`, `af_ca_mama`, `ca_mama`, `institucionalizado`, `psicofarmacos`, `dependientes_terceros`, `otro`; cada una con `<casilla>_detalle` **`text`**; `nota` **`text`** (37 columnas en total) |
| `serologias_hepatitis` (`serologias`) | 16 `int(11)`: `igm_hav`, `ags_hbv`, `ig_anti_hcv`, `fan`, `anti_lkm`, `igg_hav`, `ig_anti_core`, `ig_anti_s_hbv`, `ig_anti_hdv`, `asma`, `fibrotest`, `hiv`, `ag_e`, `anti_ag_e`, `ig_anti_hev`, `ama` |
| `c_p_s` (`child_pugh`) | `ascitis`, `bilirrubina`, `albumina`, `tp`, `encefalopatia`, `total_puntos` `int(11)` |
| `ecografia_abdominals`, `videoendoscopia_digestiva_altas`, `biopsia_hepaticas`, `tc_rmn_colangiormns` | `descripcion` **`longtext` NOT NULL**; `fecha` **`date` NULL (admite NULL)** |
| `estudios_cardiologicos` | `descripcion` **`varchar(191)` NOT NULL**; `fecha` **`date` NOT NULL** |
| `examenes_complementarios` | `numero_consulta`, `numero` `int(11)`; `solicito` **`text`**; `fechaSolicitud` `date` NOT NULL; `respuesta` **`text`**; `consulta_respuesta` `int(11)` |
| `interconsultas` | `numero_consulta`, `numero` `int(11)`; `especialista`, `solicito`, `respuesta` **`text`**; `fechaSolicitud` `date` NOT NULL; `consulta_respuesta` `int(11)` |
| `antecedentes_familiares_perfil4` | **No existe** ni en `hc_clinica` ni en `hc_clinica_respaldo`. Hay una migración en el clon (`2026_08_18_000000_create_antecedentes_familiares_perfil4_table.php`: `nota` `string` con defecto `''` y `activo`) que **no se corrió** en la copia local |

**Contra lo que dicen las migraciones y T48 (diferencias, todas verificadas).** Ninguna migración posterior altera estas tablas (la única `Schema::table` del clon es de `laboratorio_generals`), así que la base real **no sale de las migraciones**
(la copia local es una restauración de otra base, la de producción o su respaldo; producción no se leyó, D18).

| Dato | Migraciones / T48 | Base real | Consecuencia |
|---|---|---|---|
| `descripcion` de ecografía, videoendoscopia, biopsia y TC/RMN | `string` (191) | **`longtext`** (el máximo medido es de 974 caracteres; 5 ecografías y 1 TC/RMN superan 191) | No hay tope de 191 en esas cuatro |
| `fecha` de esos mismos cuatro | `date` NOT NULL | **`date` NULL** (hoy ninguna fila la tiene en NULL ni en `0000-00-00`) | La lectura ya admite estudio con texto y sin fecha (`ConsultasReglas.php:564-567`) |
| `descripcion` y `fecha` de `estudios_cardiologicos` | `string` (191) y `date` NOT NULL | **igual a la migración**: `varchar(191)` y `date` NOT NULL (el texto más largo que hay mide justo 191) | **Es la única columna de E2 con tope de 191 caracteres** |
| `solicito`, `respuesta` (exámenes) y `especialista`, `solicito`, `respuesta` (interconsultas) | `string` (191) | **`text`** (65535 bytes) | Sin tope de 191 |
| `*_detalle` y `nota` de alimentación y de antecedentes familiares | `string` (191) | **`text`** (65535 bytes) | Sin tope de 191 |
| `fechaSolicitud` | `date` NOT NULL | igual | El servidor la tiene que poner siempre (ver `ColumnasLegacy`) |
| `activo`, `consulta_respuesta` | `integer` NOT NULL | igual (sin defecto) | Igual |
| `c_p_s` | `create_cps_table` crea `c_p_s` | igual; columna `ascitis` (la web la escribe como `$cps->ascitis`) | Sin diferencias |

**Qué columnas cubre `ColumnasLegacy::vacias` (`app/Services/Salud360/ColumnasLegacy.php:38-57`).** Rellena toda columna NOT NULL, sin defecto y fuera de `[id, consulta_id, paciente_id, activo, created_at, updated_at]` (o las que
pase quien llama en `$omitir`): las `date`/`datetime`/`timestamp` con **hoy** (`Carbon::now()->toDateString()`, y `config/app.php:70` tiene `timezone = UTC`, no Buenos Aires), las numéricas con `0` y el resto con `''`; una
columna que figure en `$opciones` nace `2` (`SIN_CARGAR`) o, con `'opcion'`, `0`. Resultado sobre la base real:

| Tabla | Columnas que rellena | Valor |
|---|---|---|
| `alimentacions` | las 4 casillas / los 4 detalles | `0` / `''` |
| `antecedentes_familiares` | las 15 casillas / los 15 detalles / `nota` (31 en total) | `0` / `''` / `''` |
| `serologias_hepatitis` | las 16 | `0` |
| `c_p_s` | las 5 y `total_puntos` | `0` |
| ecografía, videoendoscopia, biopsia, TC/RMN | `descripcion` | `''`; **`fecha` NO** (admite NULL: queda NULL si quien llama no la manda) |
| `estudios_cardiologicos` | `descripcion` y **`fecha`** | `''` y **hoy en UTC** |
| `examenes_complementarios` | `numero_consulta`, `numero`, `solicito`, `fechaSolicitud`, `respuesta`, `consulta_respuesta` | `0`, `0`, `''`, **hoy en UTC**, `''`, `0` |
| `interconsultas` | las mismas más `especialista` | `''` (y `fechaSolicitud` hoy en UTC) |

Hallazgos de esto: (a) para que `0` signifique «sin cargar» en serologías y Child-Pugh y «no marcada» en alimentación y antecedentes basta **no pasar `$opciones`** (el `2` de `SIN_CARGAR` es la convención de
pediatría para 1/0 y la web de clínica no lo usa); (b) **nunca hay que dejar que `vacias` ponga `fecha` o `fechaSolicitud`**: las pone el servidor desde la fecha clínica de la consulta (E1 la guarda en
`consultas.created_at`, `EscrituraService.php:116`) y la fecha UTC puede caer en otro día que la de Buenos Aires (la web usa `date_default_timezone_set('America/Argentina/Buenos_Aires')`, `MedicoController.php:2315` y `:2955`).

**Datos (solo agregados, ningún dato de pacientes).** Filas por `activo` en `hc_clinica`: las tablas de formulario y estudios solo tienen `activo = 1` (alimentación 44; serologías 1676; Child-Pugh 1670; ecografía 1787; videoendoscopia
1786; biopsia 1786; TC/RMN 1786; cardiológicos 708); `antecedentes_familiares` 962 con `activo = 1` y **1 con `activo = 0`**; exámenes: 11 con `1` y **1 con `2`**; interconsultas: 6 con `1` y **1 con `2`**. **Ninguna tabla tiene dos filas
activas por (consulta, paciente)** salvo exámenes e interconsultas, donde varias por consulta son normales. Valores: serologías solo `0`, `1` y `2`; alimentación y antecedentes solo `0` y `1`; Child-Pugh de 0 a 3 por parámetro y
`total_puntos` **igual a la suma en las 1670 filas**; 1667 de las 1670 filas de Child-Pugh están todas en 0 (la web las crea vacías al abrir). Largos máximos: nota de antecedentes 451, detalle de alimentación 30,
`solicito` 49 (exámenes) y 56 (interconsultas), `respuesta` 35 (exámenes) y 211 (interconsultas), `especialista` 30. Exámenes: `consulta_respuesta` en 0 en 7, igual a la propia consulta en 2 y **de otra consulta en 3**; interconsultas: 3, 3 y **1** (completar
desde otra consulta pasa de verdad). `numero` de 1 a 4; `numero_consulta` hasta 2. El registro `salud360_envios` guarda `remoto_id` `bigint(20) unsigned` (entran los ids `int(10) unsigned` de exámenes) y `clave` `varchar(191)`.

**Cómo escribe hoy la web, verificado en el clon actual (rama `salud360-api`, `5ebb410`).** Se comprobó una por una, contra la función, que **siguen valiendo las líneas citadas** en T48 y en este anexo
(`MedicoController.php`: `:1198-1315`, `:1202-1204`, `:1321-1338`, `:1340-1373`, `:1375-1385`, `:1390-1408`, `:1917-1959`, `:1961-1971`, `:2030-2038`, `:2040-2110`, `:2212-2216`, `:2225-2278`, `:2280-2350`,
`:2954-3006`, `:3682-3734`, `:5955-5962`, `:5964-5999`, `:6017-6042`, `:6060-6193`; `ConsultasService.php:108-112`, `:150-161`, `:163-166`, `:299-306`; `ConsultasReglas.php:139-147`, `:192-203`, `:456-460`, `:585-616`,
`:618-624`; `AppServiceProvider.php:31`): no hubo que corregir ninguna. Se agregan los detalles que faltaban:

| Sección | Función y líneas | Qué hace exactamente (lo que importa al servidor de E2) |
|---|---|---|
| Alimentación | `guardarAlimentacion` `:1917-1959` | Fila de (consulta, paciente) con `activo` 1 o 2 (`existeAlimentacion` `:1961-1971`, `first()` = la de menor id); escribe **los cuatro** pares casilla/detalle del pedido (la web siempre manda todos); `null` en el detalle → `""`; fuerza `activo = 1` (`:1955`); `celiaco` → `seliaco` (`:1943`), `otro` → `otra` (`:1949`) |
| Antecedentes familiares | `guardarAntecedentesFamiliares` `:1198-1315` | Igual: fila `activo` 1 o 2 de (consulta, paciente) (`:1375-1385`); las 15 casillas, sus detalles y la `nota`; `dependiente_terceros` → `dependientes_terceros` (`:1294`); fuerza `activo = 1`; perfiles 3 y 4 de la web: solo la nota (`:1202-1204`, `:1321-1338`, el resto en 0 por `:1340-1373`); pestaña del perfil 4 aparte en `antecedentes_familiares_perfil4` (`:1390-1408`, tabla que **no existe** en la copia local) |
| Serologías | `guardarSerologiasHepatitis` `:5964-5999` | Fila `activo != 0` de (consulta, paciente) (`existeTablaConsultaPaciente` `:5955-5962`, `first()`); alta con `activo = 1`; los 16 valores del pedido tal cual (0/1/2); no toca `activo` en una existente |
| Child-Pugh | `guardarCps` `:6017-6042` | Igual; los 5 puntos y **`total_puntos` los manda el navegador** (`:6037`), no el servidor |
| Cinco estudios | `:6060-6193` (videoendoscopia `:6060`, ecografía `:6087`, TC/RMN `:6114`, biopsia `:6141`, cardiológicos `:6168`) | Fila `activo != 0`; alta `activo = 1`; `descripcion` null → `""` (`:6075-6078`, `:6183-6186`); `fecha` siempre de `convertirFechaGuardar($request->fecha)` (`:2212-2216`: `d/m/Y` → `Y-m-d`, sin validar). No hay baja. Con perfil 2 la web crea al abrir la consulta una fila vacía de motivo y de cada uno de los cinco estudios (seis filas; `inicializarCampos` `:2954-3006`, solo `if($user->perfil == 2)` `:3025-3027`, con `fecha` = hoy en Buenos Aires y `descripcion = ''`) |
| Exámenes complementarios | `guardarExamenesComplementarios` `:2280-2350` | Alta (`ex_compl_id` 0 o -1) con `activo = 2` (`:2321`), `numero` del navegador (`:2285-2288`), `numero_consulta` = pendientes de la consulta + 1 (`:2290-2295`), `fechaSolicitud` = hoy en Buenos Aires (`:2315-2317`); **editar por id** (`:2323-2337`) no comprueba paciente ni consulta y **siempre reescribe** `solicito`, `respuesta` y `consulta_respuesta` (= la consulta actual si la respuesta no está vacía, `0` si está vacía) |
| Interconsultas | `guardarInterconsulta` `:2040-2110` | Busca **solo** una confirmada (`activo = 1`) con ese `numero` del paciente (`existeInterconsulta` `:2030-2038`): si existe, solo cambia `respuesta` y `consulta_respuesta` (`:2054-2064`); si no (incluida una pendiente `activo = 2`), **inserta otra** con `activo = 2` (`:2095`) y `fechaSolicitud` del navegador (`""` si falta, `:2090-2093`). La API no debe copiar esto: editar una pendiente es actualizar su fila |
| Confirmar al cerrar | `setActivoExamenesComplementarios` `:2225-2234`, `setActivoInterconsulta` `:2269-2278` (y `Laboratorio*`, `Peso` `:2236-2267`) | Ponen `activo = 1` a **todas** las filas de la consulta (también las dadas de baja); la API confirma solo las `activo = 2` |
| Lectura | `ConsultasService::historia` `:101-190`, `leer` `:199-236`, ayudas `:299-306`, `:338-368` | Formularios por consulta: `ultimaActiva` = `activo = 1` de mayor `id` (`:360-367`); estudios, motivos, exámenes e interconsultas del paciente con `activo = 1` de consultas `activo` 1 o 2 (`delPaciente`); antecedentes y pendientes: la última fila activa del paciente. `ConsultasReglas::estudios` (`:569-572`) y `examenes` (`:585-616`) **omiten la fila sin texto**: un estudio vaciado deja de verse, igual que una fila de examen sin ningún texto |

**Hallazgos de T48 que no coinciden con lo que se suponía (para quien decida las preguntas).**
1. **El tope de 191 caracteres existe solo en `estudios_cardiologicos.descripcion`.** Las otras cuatro descripciones son `longtext` y los textos de exámenes, interconsultas, alimentación y antecedentes son `text`. La spec (RF-39) ya
   decía «en estudios cardiológicos»; las migraciones y Q-E2-3 daban 191 para todos. Contra una base que se parezca más a las migraciones (la de producción no se leyó) el límite podría ser otro: se mide solo con la aprobación de producción.
2. **Las migraciones no describen la base real** (tipos y nulidad de cinco tablas): cualquier cosa que se decida «porque la migración dice» hay que cotejarla con la base.
3. **`fecha` admite NULL en cuatro de los cinco estudios** pero no en cardiología; el servidor tiene que exigir fecha en los cinco (RF-39 habla de «fecha y descripción» y la web siempre manda fecha).
4. **`ColumnasLegacy::vacias` pone hoy en UTC** a `estudios_cardiologicos.fecha`, `examenes_complementarios.fechaSolicitud` e `interconsultas.fechaSolicitud` si quien llama no las manda: el servicio de E2 las manda siempre.
5. **La web y la lectura no eligen la misma fila**: la web edita la de **menor id** con `activo != 0` (o 1 o 2), la lectura muestra la de **mayor id** con `activo = 1`. Con los datos de hoy (cero repetidas) coincide; si apareciera una repetida, el servidor
   de E2 debe escribir la que lee la lectura (`activo = 1`, mayor id). Tampoco hay filas `activo = 2` en estas seis tablas hoy.
6. **`antecedentes_familiares` tiene una fila `activo = 0`** (el cierre y los `set*` no la tocan): la lectura la ignora y la API tampoco la reactiva.
7. **`antecedentes_familiares_perfil4` no existe en la copia local** (confirmado en las dos bases); la migración está en el clon sin correr. Cualquier prueba de esa nota necesitaría correrla en la copia local (es una tabla nueva, `Toca API Laravel: sí`).
8. **La web no valida la fecha de un estudio** (`convertirFechaGuardar` solo parte el texto): el servidor de E2 la valida (formato y que exista en el calendario).
9. **Editar una interconsulta pendiente en la web duplica la fila**; la API no lo replica (ver la tabla).
10. **No hay `UNIQUE` en ninguna de las once tablas que existen**: la unicidad por (consulta, paciente) depende de la transacción con la consulta bloqueada (`conConsulta`), no de la base.

## 12. E3 — laboratorio, peso y pendientes
- **Laboratorio general** `guardarLaboratorioGeneral` `:6195-6535`, **reducido** `guardarLaboratorioGeneral2` `:6537-6655` y **peso** `guardarPeso` `:6697-6731`: siempre un `INSERT` (la web no actualiza una
  carga ya guardada); cada columna que no viene queda `''`; la fecha llega `d/m/Y` y, **si falta, se guarda `2000-01-01`** (`:6515-6521`, `:6635-6641`, `:6711-6717`; la lectura lo trata como «sin fecha»,
  `ConsultasReglas::fechaDeCarga` `:456-460`); **`activo = 1` desde el alta** (`:6523`, `:6643`, `:6719`): visibles al instante (D20).
- **`medico_infos`:** el laboratorio general (`:6526-6532`) y el reducido (`:6646-6652`) ponen `es_nueva_consulta = 8`; el de `guardarPeso` está **comentado** en el clon (`:6722-6728`) - corrige §2 (reverificado en T73, ver §12.1). **La puerta RF-16 no cambia: la API no escribe `medico_infos` en ningún caso** (ni el 8 del laboratorio, ni el 3 o 4 de las fotos).
- **Baja** `eliminarLaboratorioGeneral` `:3682-3698`, `…General2` `:3700-3716`, `eliminarPeso` `:3718-3734`: `activo = 0` solo si estaba en 1 y el paciente coincide. Es la única forma de «editar» en la web:
  borrar y volver a cargar.
- **Pendientes** `guardarPendientes` `:4808-4842` (y `guardarPendientesCancelar` `:4773-4806`, que escribe `""`): una fila por (paciente, consulta) con `activo = 1` en `aux_pendientes`, texto completo en
  `pendientes`; la lectura devuelve la **última fila activa del paciente** (`ConsultasService.php:163-166`, `ConsultasReglas::pendientes` `:618-624`). No existe «resuelto».
- Pasos de la app que dependen de esto: `laboratorio` y `pendiente` no tienen `remoto_id` hoy (`HistoriaClinica.sq`), los emparejaba por id la lectura (T29 (c)); `CargasLaboratorio.kt` dice «las guardadas no se
  editan, se borran y se recargan» (Q-E3-1).

### 12.1 T73 - El esquema real de E3 (verificado el 2026-11-12, solo lectura)
**Alcance (decisión del usuario, 2026-11-12):** el contrato y las tareas siguientes son solo para **laboratorio general (`laboratorio_generals`) y peso (`pesos`)**. El laboratorio reducido
(`laboratorio_general2s`) y los pendientes (`aux_pendientes`) se verifican igual porque los scripts de `e0` y la lectura los usan, pero **no se implementan**. La tabla `pendiente` NO es de la
base de la web (no existe en `hc_clinica`): es la tabla local de la app (SQLDelight, `HistoriaClinica.sq`).
Se leyó `information_schema` de `hc_clinica` (solo `SELECT`; `hc_clinica_respaldo` tiene exactamente las mismas columnas, tipos y `NOT NULL`). Las cuatro tablas son InnoDB `utf8mb4_unicode_ci`, sin
triggers, con `id` autoincremental, dos claves foráneas (`consulta_id` → `consultas`, `paciente_id` → `pacientes`) y **sin ningún `UNIQUE`**: una consulta puede tener muchas cargas y dos
con la misma fecha conviven. Las migraciones del clon dicen lo mismo (`2024_08_25_001654_create_laboratorio_generals_table`, `2025_12_09_130726_create_laboratorio_general2s_table`, `2026_04_30_120000_add_campos_laboratorio_generals_table`).

| Tabla (filas hoy en `hc_clinica`) | Columnas, en orden | Tipo | NULL | Defecto | `ColumnasLegacy::vacias` rellena |
|---|---|---|---|---|---|
| `laboratorio_generals` (686) | `id` | `bigint(20) unsigned` | NO | autoincremental | - |
| | `consulta_id`, `paciente_id` | `int(10) unsigned` | NO | - | los pone la API |
| | `fecha` | `date` | **NO** | **ninguno** | **la fecha de hoy** (`Carbon::now()`): la API tiene que mandarla siempre |
| | 40 analitos: `hto, hb, blancos, plaquetas, glucemia, urea, creatinina, na, k, cl, ca, mg, albumina, fosforo, tgo, tgp, fal, bt, bd, bi, ggt, tp, rin, concentracion, kptt, ldh, pcr, vsg, ct, ldl, tgc, hdl, ferritina, sat_de_transf, meld, proteinograma, tsh_t4l_antitpo, hba1c, vitamina_d3, acido_urico` | `varchar(255)` | NO | ninguno | `''` |
| | 11 analitos (agregados por la migración de 2026-04-30): `b12_homocisteina_folico, psat, ags_igg_anticore_antis_hbv, igg_hav, igg_hcv, hiv, vdrl, fan, asma_ama_antilkm, somf, peso` | `varchar(255)` | NO | **`''`** | nada (los cubre la base) |
| | `otro` | `varchar(500)` | NO | ninguno | `''` |
| | `activo` | `int(11)` | NO | ninguno | lo pone la API (`1`) |
| | `created_at`, `updated_at` | `timestamp` | SÍ | ninguno | los pone la API |
| `laboratorio_general2s` (9) | `id`, `consulta_id`, `paciente_id`, `fecha` (`date`, NO, ninguno), `activo`, `created_at`, `updated_at` | como arriba | | | `fecha` = hoy |
| | 15 analitos: `hemograma, ferritina, hepatograma, glucemia, hba1c, colesterol_total, ldl, tg, urea, creatinina, rac, cortisol_matutino, testosterona_total, cpk, otro` | `varchar(191)` | NO | ninguno | `''` |
| `pesos` (6) | `id`, `consulta_id`, `paciente_id`, `fecha` (`date`, NO, ninguno), `activo`, `created_at`, `updated_at` | como arriba | | | `fecha` = hoy |
| | `valor` (la columna del peso; el id de la app es `peso`) | `varchar(255)` | NO | ninguno | `''` |
| `aux_pendientes` (1742) | `id` (`int(10) unsigned`, no `bigint`), `consulta_id`, `paciente_id`, `activo`, `created_at`, `updated_at` | | | | |
| | `pendientes` | `text` | NO | ninguno | `''` |

Qué cambia para la escritura:
- **Largos reales** (la conexión de Laravel es estricta: pasarse da error de base, no corta): analitos del laboratorio general `varchar(255)` **caracteres** y `otro` `varchar(500)`; el reducido `varchar(191)`; el peso `valor` `varchar(255)`;
  `pendientes` `text` (65535 bytes). Hoy el máximo en uso es 26 caracteres en `otro`, 5 en el peso y 299 bytes en los pendientes.
- **Los nombres de las columnas son sin acento ni guion bajo** (`albumina`, `fosforo`, `concentracion`, `acido_urico`, `b12_homocisteina_folico`): lo confirman `information_schema` (bytes ASCII), la migración y la web (`MedicoController.php:6275-6284`).
  `MapaClinica::LABORATORIO_GENERAL` está escrito **columna de la web => id de la app** (como `ANTECEDENTES_FAMILIARES`), y los **cinco ids de la app** que no coinciden con la columna son `alb_mina`, `f_sforo`, `concentraci_n`, `_cido_rico` y
  `b12_homociste_na_f_lico` (salen de etiquetas con tilde); en el reducido la columna `otro` es el id `otros`; en el peso la columna `valor` es el id `peso`. No es un error del mapa. (`laboratorio_generals.peso` es **un analito más**, distinto de la tabla `pesos`.)
- **`ColumnasLegacy::vacias` pone la fecha de HOY** en estas tablas porque `fecha` es `NOT NULL` sin defecto: **la API nunca puede apoyarse en el relleno para la fecha**; la manda siempre (la web, si falta, guarda `2000-01-01`, que la lectura trata como «sin fecha»).
- **Filas `activo = 2`:** hoy hay una por tabla (todas de la consulta 1983, el fixture S de E1: laboratorio 720, reducido 28, peso 42, pendientes 1756). La web las crea siempre con `activo = 1` (`:6523`, `:6643`, `:6719`); **la lectura (`activo = 1`) no las muestra**.
  Quien las pasa a 1 es el **cierre desde la web** (`establecerActivo` `:3442-3461` → `setActivoLaboratorioGeneral`, `...General2`, `...Peso`, `...ExamenesComplementarios`, `...Interconsulta`, `:2225-2267`): pone `activo = 1` a **todas** las filas de la consulta,
  **incluidas las dadas de baja (`activo = 0`)**. La API no hace eso (D20: su cierre confirma solo exámenes e interconsultas), pero una consulta creada por la app que alguien cerrara desde la web reactivaría sus cargas dadas de baja.
- Marcador de «sin fecha» (`2000-01-01`): hoy **0 filas** lo tienen en `hc_clinica` en las tres tablas de carga.
- Reparto de `activo` hoy: laboratorio general 683 con 1, 2 con 0 y 1 con 2; reducido 8 con 1 y 1 con 2; peso 5 con 1 y 1 con 2; pendientes 1741 con 1 y 1 con 2. La baja de la web deja `activo = 0` (`:3694`, `:3712`, `:3730`).
- Cómo escribe la web (verificado el 2026-11-12 contra el clon, mismos números de línea que arriba): `guardarLaboratorioGeneral` `:6195-6535` (un `INSERT` con todas las columnas, `''` las no enviadas; fecha `d/m/Y` → `Y-m-d`, falta → `2000-01-01` `:6515-6521`; `activo = 1` `:6523`; **`medico_infos.es_nueva_consulta = 8`** `:6526-6532`),
  `guardarLaboratorioGeneral2` `:6537-6655` (igual; `:6635-6641`, `:6643`, **`:6646-6652`**), `guardarPeso` `:6697-6731` (igual, `valor` ← `peso`; `:6711-6717`, `:6719`; el bloque de `medico_infos` **`:6722-6728` está comentado**), bajas `eliminarLaboratorioGeneral` `:3682-3698`, `...General2` `:3700-3716`, `eliminarPeso` `:3718-3734`
  (`activo = 0` solo si estaba en 1 y el paciente coincide), pendientes `guardarPendientes` `:4808-4842` y `guardarPendientesCancelar` `:4773-4806` (una fila por (paciente, consulta) con `activo = 1`, se **actualiza**; la lectura toma la última del paciente).
- Lo que lee la lectura de estas tablas (`ConsultasService::historia` + `ConsultasReglas::cargas`): solo `activo = 1` de consultas `activo` 1 o 2; una carga **sin ningún valor** o **sin fecha útil** (`2000-01-01`, `1000-01-01`, `0000-00-00`) **no se manda** (se cuenta en `omitidas.laboratorio_sin_fecha`); devuelve `{id, consulta_id, fecha, valores:{<id de la app>: texto}}` en
  `historia.laboratorios.laboratorio_general`, `.laboratorio_reducido` y `.peso`. Por eso una carga de la API **sin valores** o **sin fecha** nunca se vería: el contrato las rechaza (`carga_vacia`, `fecha_invalida`).
## 13. E4 — adjuntos
- **PDF** `subirConsultaPdf` `:3592-3655`: hasta 15 MB (`:3624`), solo extensión `.pdf` (`:3619-3622`), carpeta `public_path('img/<usuario>/consulta_pdf')` con `mkdir` si falta (`:3628-3633`; en el hosting de
  pediatría `mkdir` no estaba permitido), nombre `time()_azar.pdf` (`:3635`), fila `consulta_pdfs` (`consulta_id` o 0, `paciente_id`, `nombre_original`, `ruta_archivo` relativa a `img/`, `activo = 1`).
  `listarConsultaPdf` `:3571-3590` lista **por paciente** (todas las consultas). Se abre con `verConsultaPdf` / `descargarConsultaPdf` (`:3769-3797`, rutas `routes/web.php:271-275`), que
  comprueban que el archivo exista (`resolveConsultaPdfFile` `:3739-3767`). Baja `eliminarConsultaPdf` `:3657-3680`: `activo = 0`, **no borra el archivo**.
- **Fotos de exámenes complementarios** `guardarExamenesComplementariosFotos` `:2432-2510`: si el examen no existe lo **crea con `activo = 1`** (`:2437-2459`); guarda cada foto con
  `Image::make(...)->resize(1980, 1920)` (`:2490`) en `img/<usuario>/examenes_complementarios` y la fila en `examenes_complementarios_fotos` (`numero` = cantidad + 1, `:2512-2522`); y **escribe
  `medico_infos.es_nueva_consulta` 3 o 4** (`:2497-2506`; la API no lo hace). Se muestran en `cargarExamenesComplementarios` (`:2352-2404`: fotos `activo = 1` del examen `activo = 1`) y la vista arma
  `img/<foto>` (`resources/views/medico/seccion/examenes_complementarios.blade.php:504-518`). Pendiente de confirmar a mano en la copia local (T95, D24).
- **Límites:** `config/salud360.php:37` (`SALUD360_IMG_PATH`) existe para forzar la carpeta `img` cuando `public_path()` no coincide con la que sirve el hosting; los límites de PHP del hosting
  (`upload_max_filesize`, `post_max_size`) mandan sobre los 15 MB (`docs/hc-pediatria-estado-y-plan.md`, «Al subir un adjunto…»). CORS ya permite `POST`, `PUT`, `DELETE`, `Authorization` y `X-Salud360-Token`
  (`app/Http/Middleware/Salud360Cors.php:33-35`).
- **App:** `HcApiClient.subir` (multipart, un archivo por pedido, tiempo largo) y `descargar` (rechaza un JSON en lugar del archivo) ya existen (`HcApiClient.kt:139-194`); `archivo.remoto_id` existe desde `5.sqm`;
  el selector de `documentos` acepta `image/*` y `application/pdf` (`ArchivosSeccion.kt:78`); `archivosPendientesDeConsulta` (`HistoriaClinica.sq:236-240`) toma también los adjuntos por paciente sin consulta
  (lo que RF-46 prohíbe enviar a clínica).

### 13.1 T95 - Límites locales, carpeta y extensión (medido el 2026-11-13, solo lectura)
Se midió la **copia local** con `C:\xampp\php\php.exe -i` y la copia servida con lecturas de solo lectura (ACL y estructura de carpetas). **No se escribió nada**: ni filas, ni
archivos, ni configuración. Los límites de **producción** siguen sin medirse: eso es la etapa de producción (D18) y **Q-E4-2** (qué carpeta del hosting y cómo se crea) **sigue pendiente**.

| Dato | Valor local | De dónde sale |
|---|---|---|
| PHP | 7.3.0, 32 bits, ZTS | `php -i`; es el mismo binario que carga Apache (`php7apache2_4.dll`, `PHPRC=\xampp\php`) |
| `php.ini` | `C:\xampp\php\php.ini` | `php -i` → `Loaded Configuration File` |
| `upload_max_filesize` | **64M** | `php -i` |
| `post_max_size` | **64M** | `php -i` |
| `memory_limit` | **128M** | `php -i` |
| `max_execution_time` | **300** en el `php.ini` (Apache lo usa); `php -i` por consola informa **0** porque la CLI lo desactiva | `php.ini` y `php -i` |
| `max_input_time` | 60 | `php.ini` |
| `file_uploads` | On | `php -i` |
| Extensión GD | **cargada** (`bundled (2.1.0 compatible)`) | `php -i` → `GD Support: enabled` |
| Máximo de la web | **15 MB** = **15.728.640 bytes** | `MedicoController.php:3624` (`15 * 1024 * 1024`) |
| **Límite efectivo de E4** | **15 MB**: `min(15 MB, upload_max_filesize 64 MB, post_max_size 64 MB)` | `AdjuntosReglas::limiteEfectivo` (T96) |
| `config('salud360.img_path')` | **vacío** (`SALUD360_IMG_PATH` no está en el `.env` de la copia servida) → se resuelve a `public_path('img')` | `config/salud360.php:37` + `.env` |
| Carpeta `img` que usaría la API | `C:\xampp\htdocs\HClinica-salud360\public\img` | `public_path('img')` |
| Contenido de `img` | **existe**; 104 archivos y carpetas sueltas: `examenes_complementarios`, `medico1` (con `consulta_pdf` adentro), y carpetas de usuarios reales con `consulta_pdf` | lectura del árbol (no se listan nombres de usuarios) |
| Permisos de `img` | `Usuarios autenticados: Modify` (además de Administradores y SYSTEM con control total) → `mkdir` funciona en la copia local | ACL de la carpeta |
| Carpeta `consulta_pdf` | ya existe para el médico de prueba y para los usuarios de la copia servida | árbol de `img` |

Consecuencias para T96 a T99:
- El tope que ve el médico es **15 MB** (el de la web) y no hay que recortarlo por PHP en la copia local. `post_max_size` (64M) deja aire para el `multipart` (el archivo más la cabecera),
  así que el límite práctico es el de la web.
- La carpeta sale de `config('salud360.img_path')` **si está definida**; hoy está vacía y se resuelve con `public_path('img')`, que en la copia local es la de la copia servida. En producción
  hay que decidir si se define `SALUD360_IMG_PATH` (Q-E4-2) y si el hosting permite `mkdir`: si no, `500 carpeta_no_escribible` **nombrando la ruta exacta**.
- GD está, pero E4 **no redimensiona ni convierte**: el archivo viaja tal cual (T97). GD solo importaría si algún día se quisiera achicar imágenes (no es de esta spec).
- **Fotos de exámenes complementarios (D24):** **no se verificaron ni se implementan** en esta tanda (T100 y T105, fuera de alcance por decisión del usuario). Lo que ya se sabe del código está
  arriba (§13) y no hizo falta escribir filas ni archivos de prueba: la web muestra una foto si `examenes_complementarios_fotos.activo = 1` y el examen está confirmado (`MedicoController.php:2352-2404`,
  `examenes_complementarios.blade.php:504-518`). Queda para cuando el usuario lo pida.
- **Scripts de los ensayos (T4 ampliados en T95):** `consulta_pdfs` entra en `escritura-contar.sql` (tabla `tabla_e4`, con activas y de baja), en `escritura-suma.sql` (`tablas_de_e4_presentes`) y en
  `cargar-prueba.sql` (una fila inventada, `filas_e4_sembradas = 1`); `escritura-limpiar.sql` ya la tenía en la lista fija de hijas y en el control de hijas de otro paciente (sin cambios de código).
  `examenes_complementarios_fotos` queda **nombrada como fuera de alcance** y sigue en la lista fija de la limpieza, sin sembrarse. Ensayado **solo en `hc_clinica_respaldo`** (`ensayo-t95.cmd` y
  `ensayo-t95-extra.cmd`, con y sin `STRICT_TRANS_TABLES`): cargar → limpiar → cargar → limpiar con las tres sumas idénticas, `FALLOS=0`. `hc_clinica` quedó **igual** (suma y conteos con el mismo
  SHA-256 antes y después; no se corrió nada que escriba en la base de trabajo).
