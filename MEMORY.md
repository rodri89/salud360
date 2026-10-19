# MEMORY.md — Salud 360

Memoria del proyecto entre sesiones. Máximo ~50 líneas: resumir o borrar lo que ya no aporte.
Nunca datos sensibles (tokens, contraseñas, datos de pacientes, fechas de registros de personas): el repo es público.

## Estado actual
- App desplegada en web (push a `main` → GitHub Actions → Hostinger). SDD adoptado (`docs/constitution.md`, `specs/`).
- HC de pediatría lee y escribe su base real (consulta, secciones, examen físico, adjuntos); detalle y pendientes en
  `docs/hc-pediatria-estado-y-plan.md`. Licencias y alta de usuarios desde el admin, leyendo de su dueño.
  El médico que solo usa HC ve su cartera de pacientes y no una agenda vacía.
- Spec 001 (perfiles configurables de la HC general): spec y plan aprobados; tanda A1 hecha y validada. T3 parcial:
  falta el login en el navegador (prueba manual del usuario). Commits sin push: hclinica rama `salud360-api`
  (`a3dccdb` config + CORS, `b729af4` middleware + `TobbAuthService`, `56fe71c` controller + `auth/perfil` + doc) y
  turnosonlinebb rama `salud360-hc-general` (`0077ed4` `PUT medicos/{id}/historias-clinicas`, solo admin).
  Tanda A2 en curso: T8 hecha (`fd24365` catálogo + 5 tablas `salud360_perfil*`/`salud360_cargas` ya creadas,
  vacías, en la base local), T9 hecha (`488bbb7` `PerfilesService::validar` + `PerfilesTest`), T10 hecha (`d5c38ec`
  CRUD `config/perfiles` en `PerfilController` + `PerfilesService` leer/guardar/borrar; tablas otra vez vacías).
  T11 hecha (`9f69ee0` `config/asignaciones` + `GET config`). T12 hecha (`4cbb989` `CargaInicialPerfiles`): la base
  local queda con la carga inicial aplicada (perfiles 1 Clínica general y 2 Hepatología, asignaciones, marcador);
  no vaciarla. T13 hecha (`97e7fe4` md completo; humo de los 9 endpoints OK, base igual que tras T12). Tanda A2
  validada. Tanda B en la rama `hc-general` de este repo (sin push): T14 hecha (tipos `PerfilHc.kt`,
  `TipoConsultaDef.grupos`). T15 hecha (`hcgeneral/PerfilValidacion.kt`: `validarPerfil`, `nuevaSeccion`,
  `claveDeNombre` pública para T16). T16 hecha (`PerfilCopia.kt`: `nombreCopia` recorta X, nunca el número;
  `CatalogoAdmin.kt`: `catalogoParaAdmin` + `seccionesParaAgregar`). T17 hecha (`DefinicionHcGeneral.kt`: tipos
  con `pestanias` = solo la ficha, `grupos` con id `pestania_<índice en el perfil>`, `secciones` = las de los grupos,
  `foto` vacío; legadas en el perfil = desconocidas; `EstadoConfig.kt`: `ConfigGuardada?`/`ConfigRemota`). T18 hecha (`SeccionesSoloLectura.kt`:
  `DatosConsulta` que llena la tanda C —antecedentes del paciente, el resto de la consulta—; `seccionesSoloLectura`
  con `enPerfil` opcional para no mostrar ocultas del perfil). T19 hecha (`especialidades/clinica/HcGeneral.kt`:
  `catalogoHcGeneral` + `hcGeneralBase`, sin contribución todavía; primer test del módulo, `CatalogoHcGeneralTest`;
  legada `antecedentes_personales` con `personalesEstandar`, que también muestra la nota libre de clínica).
  T20 hecha (`6.sqm` + `HcConfig.sq`, esquema ahora v7; `ConfigHcRepository(db, api: ConfigHcApi?)`: sin cliente o
  sin red = `red`; fallos no Tobb de la API → `TobbException` `respuesta`; JSON guardado ilegible = sin fila).
  T21 hecha (`HcConfigDtos.kt`, `ConfigHcApiRemota`, `PerfilesHcRepository` que lanza `TobbException`;
  `TobbException.errores` desde el 422; ambos repos en `DataModule` sobre el cliente "clinica", fuera de `HcBackends`;
  URL release de hclinica en `gradle.properties`: la web release ya crea ese cliente). Tanda B completa, falta
  validación del reviewer. Después C admin + médico, D hepatología + E2E.
- Tanda C: T22 hecha (`hcgeneral/EditorPerfil.kt`: extensiones de `PerfilHc`; `agregarSeccion` ignora ids fuera
  del catálogo o ya en el perfil; "Pestaña N" con el primer N libre). T23 hecha, falta la prueba manual
  (`PerfilesHcViewModel`/`PerfilesHcScreens.kt`, solapa "Perfiles HC"; `CatalogoHc` como `single` en `AppDi.kt`).
  T24 hecha, falta la prueba manual (editor en el mismo ViewModel, `PerfilesHcUi.editor`; errores con
  `hcgeneral/ErroresPorCampo.kt` `ubicarErrores`; tras un intento fallido cada cambio revalida en la app).
  T25 hecha, falta la prueba manual (`hcgeneral/AsignacionesMedicos.kt` `medicosParaAsignar` + sección "Médicos";
  selector por id, no por nombre; las asignaciones de números que no están en la lista local no se muestran).
  T26 hecha, falta la prueba manual (HC del médico por `PUT medicos/{id}/historias-clinicas` de turnos, sin
  `dirty`; `AdminRepository.setEspecialidadHc` quitado; un solo `PerfilesHcViewModel` para todo el panel, que
  recarga con `PerfilesHcRepository.asignacionesCambiadas` cuando el alta asigna un perfil).
  T27 hecha (`HcRepository.observarDatosConsulta` + 3 consultas de lectura en `HistoriaClinica.sq`; `DatosConsulta`
  sin antecedentes; `seccionesDelPacienteSoloLectura` solo mira antecedentes; `enPerfil` obligatorio).
  T28 hecha, falta la prueba manual (`hcGeneralContribution` en lugar de `clinicaContribution`, que se borró;
  `DefinicionesHc`/`AvisosHcGeneral`; `DefinicionResuelta`, `resolverDefinicion` y `copiaDiagnosticos` en
  `DefinicionHcGeneral.kt`; `ConfigHcRepository.estadoConEspera`).
  T29 hecha, falta la prueba manual (`pestaniasConsulta`, `soloLecturaPorTipo`/`puedeReabrir` en
  `DefinicionResuelta`; `EspecialidadDefinition.examenFisicoSoloLectura` + `modoCampoExamen` para FC/CA; la consulta
  resuelve con `DefinicionesHc` una vez; sin perfil o primera vez, las cerradas no se reabren).
  T30 hecha, falta la prueba manual (`soloLecturaConsulta` → `ConsultaUi.fueraDelPerfil`; vale para toda definición
  `porPerfil`, también sin perfil y "HC digitalizada"; `AvisoSeccionesDesconocidas` en la HC y en la consulta).
  T31 hecha, falta la prueba manual (`seccionesDelPerfil` y `mostrarTodas` en `DefinicionHcGeneral.kt`; la
  Configuración resuelve "clinica" con `DefinicionesHc`). Tanda C completa: falta reviewer y prueba manual.
- Tanda C2: T32 hecha (`layoutConsulta`/`LayoutConsulta` reemplazan a `pestaniasConsulta`; hasta T33 la pantalla
  no dibuja el cuerpo: en `devWeb` las secciones de "Consulta" no se ven). T33 compila, falta la prueba manual
  (cuerpo debajo de las pestañas en `ConsultaScreen`). `devWeb` no recompila solo (no es `-t`): para refrescarlo,
  `:composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` con las mismas `-P` y webpack lo toma.
  T34 hecha (`ModoRegistro`/`RegistroDef.modo`; `hcgeneral/EstudioConHistorial.kt`: el desempate por updated_at es
  el orden de entrada de `registrosDePaciente`, que el modelo no trae). T35 compila, falta la prueba manual
  (`EstudioConHistorialSeccion`; `ConsultaViewModel.guardarEstudio`/`flushEstudios` con espera de 600 ms, `Mutex` y
  volcado en `onCleared` y al cerrar; lo pendiente se suelta recién guardado). Tanda C2 completa: falta reviewer.
- Tanda C3: T36 hecha (`hcgeneral/FilasLaboratorio.kt`; "Elegir filas" solo en la HC general, C3-1; si lo
  guardado oculta todas, `alternarFila` parte de "todas visibles"; botón "(1 oculta)" en singular).
  T37 compila, falta la prueba manual (`LaboratorioSeccion`: `ElegirFilasDialog`; las ocultas no se pisan al guardar
  porque `valores` arranca con toda la carga y `guardarLaboratorio` solo descarta vacíos).
  T38 compila, falta la prueba manual (`theme/ColoresCelda.kt` + `CeldaEditable` en `FormFields.kt`, todas las
  especialidades; foco claro 3,91:1, el más justo). Tanda C3 completa: falta reviewer y prueba manual.
- Tanda C4 (varias cargas de laboratorio, solo HC general: C4-1 decidido): T39 hecha (`hcgeneral/CargasLaboratorio.kt`
  + `ORDER BY fecha DESC, updated_at DESC`; sin él, a igual fecha SQLite devuelve el orden del índice/rowid). T40 compila, falta la prueba manual (rama `LaboratorioVariasCargas` en
  `LaboratorioSeccion.kt`; la de pediatría intacta). Tanda C4 completa: falta reviewer y prueba manual.
- Tanda C5 (serologías en grilla SI/NO; C5-1 a C5-4 decididos): T41 hecha (`ModoForm` en `SeccionDef`, `SiNoVacio.kt`
  con `alternarSiNo`/`columnasPara`, `ColoresSiNo.kt`; foco por fuera del botón porque en oscuro no hay color a 3:1
  contra tarjeta y relleno a la vez; el más justo, textoRelleno claro 4,84:1). T42 compila, falta la prueba manual
  (composable `SiNoVacio` + `GrillaSiNo` en `FormSeccion.kt`; `modo` va antes de `onCambio` para no romper las
  lambdas finales). Tanda C5 completa: falta reviewer y prueba manual.
- Tanda C6 (Child-Pugh en tabla, C6-1 a C6-4 decididos): T43 hecha (`especialidades/clinica/ChildPugh.kt` +
  `ChildPughTest`). T44 compila, falta la prueba manual (`core/ui` `OpcionElegible`, que usa `SiNoVacio`;
  `ColoresSiNo.textoTotal`: Indigo en oscuro daba 1,74:1, va `texto`). Tanda C6 completa: falta reviewer y prueba manual.
- Tanda C7 (motivo con consultas previas; C7-1 y C7-2 decididos): T45 hecha (`SeccionDef.conHistorial`, solo en el
  motivo del catálogo; `valoresDeCampoDePaciente` + `observarValoresDeCampo`; `hcgeneral/TextoConHistorial.kt`). T46 compila, falta la
  prueba manual (`CampoConHistorial` común a estudios y motivo; `TextoConHistorialSeccion`; `TextoSeccion` recibe `ui`).
  Tanda C7 completa: falta reviewer y prueba manual.
- hclinica: MySQL compara nombres sin tildes (`utf8mb4_unicode_ci`) y `validar` no; el `unique` lo frena y la API
  contesta 422 igual. La app (`claveDeNombre`) ignora tildes y ñ = n para avisar antes.
- Spec 002 (HC general contra hclinica) en borrador.
- Spec 002 parte 1 y 003 aprobadas (`648c763`). Tanda E1: E1.1 hecha (hclinica `6c7507f`: `PacienteVinculoReglas`
  estática, con `elegible` y `comoLaWeb` además de lo del plan, + `ColumnasLegacy` + `PacienteVinculoReglasTest`). E1.2 hecha (`a7a4b67` `PacienteVinculoService`; la web
  guarda sexo solo `M`/`F`, `X` → vacío; error extra `ocupado` si no se toma el `GET_LOCK`; la columna
  `paciente_id_tobb` + índice ya existe en la base local, sin filas vinculadas). E1.3 hecha (`2eb1bdf`
  `PacienteController` + `soloMedico`/`atiende`/`formatearPaciente`; `ocupado` → 503). E1.4 hecha (`cad1d2d`
  sección "Pacientes" del md; humo con curl OK, incluido `503 ocupado` con un `GET_LOCK` tomado desde otra
  conexión; base igual que antes, sin fichas vinculadas). Falta E1.5 (pediatría) y el reviewer de la tanda.

## Entorno local (Windows + XAMPP)
- Clones de trabajo: `rebonline\hclinica` (no `C:\Users\flor\hclinica`, que tiene cambios sin commitear del
  transcriptor) y turnos. Copias servidas: `HClinica-salud360` y `TurnosOnlineBB-salud360` (sin git; los commits se
  copian a mano; la de turnos ya no es igual a `main`). Comando de `devWeb` contra ellas en `AGENTS.md`.
- Bases `hc_clinica`, `turnosonlinebb` y `tobb` con datos reales: solo locales, nunca al repo.
- Usuarios de prueba en `turnosonlinebb` local: un médico (vinculado en `hc_clinica`, con HC `clinica`) y un admin;
  las credenciales no van en el repo.
- La licencia del médico de prueba en `hc_clinica` está extendida a propósito para las pruebas manuales del
  usuario: no restaurarla. El respaldo para volver a como estaba, en
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
- Endpoint nuevo en turnos para editar HC habilitadas: mismo servicio que `admin_hc_medico` (desactiva, no borra).

## Aprendizajes y errores a evitar
- Gradle necesita `JAVA_HOME` (JBR de Android Studio) y se queda sin memoria si el navegador está abierto.
- Las URLs del entorno se pisan por máquina desde `local.properties` (no versionado), con las mismas claves que
  `gradle.properties`: precedencia `-P` > `local.properties` > `gradle.properties`. En esta compu apunta al XAMPP
  (turnos y HC clínica); en la Mac sigue mandando el MAMP `:8888` de `gradle.properties`. `release` no se altera.
- La app guarda para siempre a qué ficha de pediatría corresponde cada paciente (`hc_paciente_id`); no lo revalida.
- En la web el perfil 1 NO tiene motivo de consulta; telemedicina es igual para todos; perfiles 5 y 9 = 1.
- En PowerShell no escribir markdown con here-strings: los backticks se toman como escapes. Usar el editor.
- hclinica: Laravel 5.8 / PHP 7.3; la HC cambia según `users.perfil` (2 = hepatología). Producción:
  `https://clinica.hclinicadigital.com`. Su API va con `throttle:60,1` por IP: tenerlo en cuenta en la 002.
  Commits como rodri89.
- PowerShell no distingue mayúsculas en variables (`$t` pisa `$T`): un logout falló así; tokens de turnos en
  `salud360_tokens` (revocar = borrar la fila). Quedan filas viejas de pruebas anteriores sin revocar.
- Renderers CUSTOM de la HC: leer con `ctx.vm.valores.collectAsState()`, no con `ctx.valor` (no se observa y Compose
  saltea la sección: el bug de Child-Pugh que no se pintaba).
- El PHP del XAMPP es de 32 bits (`PHP_INT_SIZE` 4): `(int)` de números grandes satura en 2147483647.
- phpunit en la copia servida: si un test falla, el printer se cae (`phpDocumentor` del `vendor` usa sintaxis PHP
  7.4 y XAMPP tiene 7.3); se ve `E`/`F` pero no el mensaje. Para verlo, llamar el código con `php -r`.
- OpenCode no habla con Anthropic por API key: el plugin `@xtruder/opencode-claude-max-plugin` (provider
  `anthropic-sdk`) usa el OAuth de Claude Code de `~/.claude/.credentials.json`. Si ese archivo se vacía (la sesión
  OAuth venció, ~8 h de vida del refresh), el error es "Could not resolve authentication method" y no es de red:
  se arregla con `claude auth login` (abre el navegador y la página muestra un código que hay que pegar).
  Diagnóstico rápido: `claude auth status` → `loggedIn: false`.
