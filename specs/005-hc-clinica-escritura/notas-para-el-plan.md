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
