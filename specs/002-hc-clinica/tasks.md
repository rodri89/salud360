# Tareas 002 — parte 1: pacientes y cartera (E1, E2) · parte 2, entrega L: lectura (E3 a E9, al final)

Plan: `plan-parte1.md` (aprobado el 2026-10-19, con PD1 decidido: E1.5 entra). Spec: `spec.md`, parte 1 (RF-8 a
RF-14 y RF-35 a RF-37), aprobada el 2026-10-19. Las tareas van por tandas; cada tanda se aprueba antes de generar la
siguiente (la E2, app, se genera después de validar la E1).

**Parte 2, entrega L (solo lectura): tandas E3 a E9, abajo, aprobadas por el usuario el 2026-10-28** (plan en
`plan.md`; Q15 = nunca borrar; la tanda S, E3 y E4, puede empezar; E9, producción, requiere aprobación aparte). La
entrega E (escritura) es borrador sin aprobar y no tiene tareas.

Reglas para todas:
- Commits locales como `rodri89` (ya configurado), uno por tarea, **sin push** en ningún repo. Subir a producción
  (hclinica o pediatría) lo decide el usuario después.
- Nada contra producción. Ningún dato real, contraseña ni token en ningún repo (tampoco en `.md`, tests ni
  mensajes de commit). Las pruebas que necesiten token usan los usuarios de prueba locales, sin escribir
  credenciales en ningún lado.
- Las bases locales `hc_clinica` y `hc_pediatrica` tienen datos reales: solo se mira **estructura**
  (`information_schema`, `SHOW CREATE TABLE`, valores distintos de una columna de códigos), nunca se muestran filas
  de pacientes. Los datos de prueba se crean con nombre "PRUEBA SALUD360" y documentos verificados libres antes de
  usarlos (`SELECT COUNT(*)`), y se borran al terminar cada tarea.
- Restaurar siempre con `UPDATE`/`INSERT`/`DELETE` puntuales; **nunca** restores con `DROP TABLE`.
- Copias servidas (sin git; los archivos se copian a mano desde el clon): `C:\xampp\htdocs\HClinica-salud360`
  (`http://localhost/HClinica-salud360/public/index.php`, clon `C:\Users\flor\rebonline\hclinica`) y
  `C:\xampp\htdocs\HCDPediatria-salud360` (`http://localhost/HCDPediatria-salud360/public/index.php`, clon
  `C:\Users\flor\rebonline\hc_pediatria`). La copia vieja `htdocs\HCDPediatria` no se toca. `php -l` de cada archivo
  tocado antes de probar.
- En XAMPP las dos apps Laravel comparten el entorno del proceso de Apache: después de tocar `.env` o `config/`,
  `php artisan config:cache` en las copias servidas (no `config:clear`).
- phpunit en la copia servida (el clon no tiene `vendor/`): `C:\xampp\php\php.exe vendor\bin\phpunit tests\Unit\...`.
  Si un test falla el printer se cae y no muestra el mensaje: verlo llamando el código con `php -r`.
- PHP 7.3 de 32 bits (`PHP_INT_SIZE` 4): nada de sintaxis 7.4 (`fn`, propiedades tipadas) y números grandes
  comparados como texto antes de `(int)`.
- Licencia del médico de prueba de hclinica: extendida a propósito para las pruebas manuales del usuario; no
  restaurarla (ver `MEMORY.md`).
- No matar el `devWeb` (Gradle/Node) que tenga abierto el usuario.

## Tanda E1 — Servidor (hclinica y pediatría)

Sección "Servidor" del plan y diferencias con pediatría (P4 a P6, PD1). E1.1 a E1.4 en
`rebonline\hclinica` (rama `salud360-api`); E1.5 en `rebonline\hc_pediatria` (rama `salud360-api`). URL base de
las pruebas de clínica: `http://localhost/HClinica-salud360/public/index.php/api/salud360/...`, tokens del médico y
del administrador de prueba sacados de `auth/login` de turnos local.

- [x] **E1.1. Relleno de columnas y reglas del vínculo (puras) con phpunit.** RF-11, RF-12, RF-13, RF-36
  - `app/Services/Salud360/ColumnasLegacy.php` copiada de pediatría, con una constante propia en lugar de
    `SeccionesService::SIN_CARGAR` (que en hclinica no existe).
  - `app/Services/Salud360/PacienteVinculoReglas.php` sin base: `normalizarDni` (quita puntos y espacios; cualquier
    otro no dígito → inválido; compara como texto contra `2147483647` antes de convertir; < 1000 y 99999 →
    inválido), `normalizarFecha` (`1000-01-01` y `0000-00-00` = sin fecha), `normalizarTexto` (mayúsculas, sin
    tildes, espacios juntos), `posibles($candidatos, $idTobb)`, `desempatar` (fecha y apellido; solo si queda
    exactamente una) y `cambiosParaCompletar($fila, $datos)` (solo columnas vacías, mayúsculas como `altaPaciente`
    de la web).
  - `tests/Unit/PacienteVinculoReglasTest.php` (extiende `PHPUnit\Framework\TestCase`, sin Laravel ni base):
    documento con puntos y espacios válido; `-12345678`, `12345678A`, `0`, `1`, `999`, `99999`, `2147483648`,
    `99999999999` inválidos; `2147483647` válido; fechas vacías; desempate por fecha y apellido con y sin tildes;
    mellizos (misma fecha y apellido) sin desempate; todas vinculadas con otro paciente de turnos → ninguna posible;
    completar: vacío no pisa, cargado no cambia, fecha `1000-01-01` cuenta como vacía.
  - Hecho cuando: phpunit de `PacienteVinculoReglasTest.php` y de `PerfilesTest.php` en verde en la copia servida,
    `php -l` sin errores en los dos archivos nuevos, y buscar `fn (` y `SeccionesService` en ellos no da
    resultados.

- [x] **E1.2. Servicio del vínculo: columna en caliente, resolver y alta.** RF-10, RF-11, RF-12, RF-13, RF-35, RF-36
  - Antes de escribir: SQL de solo lectura sobre `hc_clinica` (estructura): `SHOW CREATE TABLE pacientes` y
    `medico_pacientes` (tipo de `dni`, columnas NOT NULL sin default) y `SELECT DISTINCT sexo FROM pacientes` (solo
    los valores) para el mapeo `M/F/X` (plan, Riesgos); anotar el mapeo en el KDoc/PHPDoc del servicio.
  - `app/Services/Salud360/PacienteVinculoService.php`: `asegurarColumna()` (`pacientes.paciente_id_tobb`
    `unsignedInteger default 0` con índice; recuerda en estático que existe; "duplicate column" de un pedido
    simultáneo = hecho); `resolver($medico, $datos)` en el orden del plan (por `paciente_id_tobb`; elegida
    `hc_paciente_id` activa, del mismo documento y no vinculada con otro, si no `ficha_no_elegible`; por documento:
    crea / `paciente_ya_vinculado` sin candidatas / vincula / desempata o `paciente_ambiguo` con candidatas con
    `cantidad_consultas` de `activo > 0`); alta con `ColumnasLegacy::vacias('pacientes')` + campos de la app en
    mayúsculas como la web, sin fecha → `1000-01-01`; `medico_pacientes` activo (crea o reactiva); todo en una
    transacción con `GET_LOCK('salud360_pac_<dni>')` y `RELEASE_LOCK` en `finally`. Usa `PacienteVinculoReglas`.
    Nunca toca `medico_infos`.
  - Hecho cuando: `php -l` sin errores; phpunit de E1.1 sigue en verde; `git diff` muestra solo el archivo nuevo; y
    se ejercita con `php artisan tinker` (o `php -r` con el bootstrap) en la copia servida con un médico de prueba
    y un documento "PRUEBA" libre: primera llamada crea (`creado` true), segunda vincula por `paciente_id_tobb` sin
    crear otra fila; `SHOW CREATE TABLE pacientes` muestra `paciente_id_tobb` con su índice una sola vez. Filas de
    prueba (`pacientes` y `medico_pacientes`) borradas con `DELETE` puntual; la columna nueva queda (es la misma
    que creará la API).

- [x] **E1.3. Controladores y rutas de pacientes.** RF-8, RF-10, RF-11, RF-12
  - `Salud360Controller`: `formatearPaciente` de clínica (sin padre/madre; con `nombre_familiar`,
    `telefono_familiar`, `paciente_id_tobb`), `fechaCorta`, `atiende`, `soloMedico` (admin y secretaria →
    `403 sin_permiso`).
  - `PacienteController` (nuevo): `index` (cartera paginada `desde`, `limite` 1..1000, `distinct`, orden apellido,
    nombre, id; `total`, `hay_mas`), `resolver` (200/201 con `paciente`, `creado`, `vinculado_por`; `dni_invalido`
    422 con el mensaje de RF-12, `paciente_ambiguo` 409 con `candidatos`, `paciente_ya_vinculado` 409,
    `ficha_no_elegible` 422) y `show` (`404 paciente_no_encontrado`, `403 fuera_de_cartera`). Sin el caso especial
    usuario 8 → 6 de la web.
  - `routes/api.php`: `GET pacientes`, `POST pacientes/resolver`, `GET pacientes/{id}` dentro del grupo `salud360`.
  - Hecho cuando, copiado a la servida y con curl: sin token → `401 sin_token`; token del administrador en los tres
    → `403 sin_permiso`; médico (licencia extendida): `GET pacientes?limite=5` → 200 con a lo sumo 5, `total` y
    `hay_mas` (solo se miran los conteos y las claves, no los datos); `limite=0` y `limite=5000` se acotan;
    `POST pacientes/resolver` con `dni` `12345678A` → `422 dni_invalido`; `GET pacientes/0` →
    `404 paciente_no_encontrado`; `php artisan route:list` muestra las tres rutas con el middleware `salud360`;
    la web de hclinica sigue entrando normal.

- [x] **E1.4. Documentación y prueba de humo completa.** RF-8, RF-10, RF-11, RF-12, RF-36
  - `md/API_SALUD360_CLINICA.md`: sección "Pacientes" (cuerpos, respuestas, códigos, columna creada en caliente,
    curl de humo sin credenciales) y tabla "Código" con los archivos nuevos.
  - Humo con curl contra la copia servida, con fichas de prueba creadas por SQL ("PRUEBA SALUD360", documentos
    libres): paciente nuevo → 201 `creado: true` y aparece en `GET pacientes`; mismo paciente otra vez → 200
    `vinculado_por` = por id de turnos; dos fichas con el mismo documento sin desempate → `409 paciente_ambiguo` con
    2 candidatas; elegir una de ellas → 200 `vinculado_por` = elegida; elegir una ficha de otro documento →
    `422 ficha_no_elegible`; documento con una sola ficha vinculada a otro `paciente_id_tobb` →
    `409 paciente_ya_vinculado` sin candidatas; `GET pacientes/{id}` de una ficha de prueba fuera de la cartera →
    `403 fuera_de_cartera`.
  - Hecho cuando: todos los códigos de arriba salen como se listan; phpunit de E1.1 en verde; la copia servida es
    igual al clon en los archivos de la tanda (comparación de contenido, sin contar `.env`/`vendor/`); el `.md` no
    tiene tokens, contraseñas ni nombres de médicos o pacientes; al terminar, `SELECT COUNT(*)` de `pacientes` y
    `medico_pacientes` con los documentos de prueba da 0 y la licencia del médico de prueba está restaurada.

- [x] **E1.5. Mismos arreglos en pediatría, sin cambiar lo que responde a la app.** PD1 (P4, P6), RF-11, RF-12
  - En `rebonline\hc_pediatria` (rama `salud360-api`): elegida (`pediatria_paciente_id`) solo si es activa, del
    mismo documento y no vinculada con otro paciente de turnos (P4); documento con la regla de E1.1 (P6: signo o
    letras → `dni_invalido`; comparación como texto para 32 bits); `asegurarColumna` con estático y "duplicate
    column" tolerado. Puede reutilizar una `PacienteVinculoReglas` copiada de E1.1 (con su test). Sin cambiar las
    claves ni los códigos de las respuestas que ya usa la app (`dni_invalido`, `paciente_ambiguo`,
    `paciente_ya_vinculado`, `no_encontrado`, formato del paciente). `md/API_SALUD360_PEDIATRIA.md` actualizado.
  - Hecho cuando: phpunit del test de reglas en verde en `HCDPediatria-salud360`; `php -l` sin errores; `git diff`
    en `hc_pediatria` limitado a los servicios, el test y el `.md`; con curl contra la copia servida y el token de
    prueba de pediatría (sembrado en `salud360_token_cache`, sin escribirlo): `dni` `-12345678` y `12345678A` →
    `422 dni_invalido`, elegida de otro documento → rechazada; `HcPediatriaE2ETest` y `HcCarteraE2ETest` con
    `--rerun` contra la copia servida de pediatría siguen en verde (respuestas a la app sin cambios). Filas de prueba
    borradas con `DELETE` puntual.
  - Decidido (coordinador, 2026-10-19): la elegida que no corresponde → `422 ficha_no_elegible`, como hclinica (la
    inexistente o inactiva sigue `404 no_encontrado`); por eso el `git diff` incluye también un `case` en
    `PacienteController`. La copia servida de pediatría ya tiene `config:cache`.

Al terminar la tanda: validación del reviewer y MEMORY.md.

Validada por el reviewer el 2026-10-20: APROBADO.

## Tanda E2 — App (pacientes y cartera)

Sección "App" del plan, en este repo (rama `hc-general`). Contrato del servidor: `md/API_SALUD360_CLINICA.md`
("Pacientes") de `rebonline\hclinica` y `md/API_SALUD360_PEDIATRIA.md` de `rebonline\hc_pediatria`, tal como
quedaron en la E1.

Reglas de la tanda (además de las de arriba):
- Tests primero: cada test nuevo se corre en rojo antes de escribir el código que lo pone en verde.
- Commits locales como `rodri89`, uno por tarea, **sin push** (pushear `main` publica).
- Nada contra producción. Los E2E solo contra las copias servidas locales, con `-Psalud360.test.hc.clinica…`
  (o `.pediatria…`) y `--rerun`; lo que creen se borra por SQL puntual (`DELETE`) al terminar.
- **No se registra un `HcBackend` para "clinica"**: `HcBackends` sigue con `"pediatria" -> …; else -> null` (el
  envío de consultas de la HC general es el resto de la 002). *(Nota 2026-10-28, L aprobada por el usuario: la
  entrega L registra uno **de solo lectura**, E6.5, y solo después de los candados de E5. Hasta que E5 y E6.5 estén
  hechas, esta regla sigue vigente en el código: no se registra ninguno.)*
- Dueño remoto, nunca `dirty`: cartera y vínculo se guardan con `dirty = false`, también el de pediatría.
- Sin cambio de esquema local (el vínculo vive en `paciente_extra`, especialidad + `hc_paciente_id`). Si apareciera
  la necesidad, se pregunta antes (migración nueva y columnas al final).
- No usar `--stop` ni matar el `devWeb` que tenga abierto el usuario. Para refrescarlo:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` (en esta máquina las URLs locales
  salen de `local.properties`; no hacen falta `-P` de URL).
- Contrato que hay que respetar (reviewer de la E1): `resolver` contesta `201` (`creado: true`,
  `vinculado_por: "nuevo"`) o `200` (`vinculado_por`: `tobb_id`, `dni` o `elegido`); `422 dni_invalido`,
  `422 ficha_no_elegible`, `409 paciente_ambiguo` (con `candidatos` en el cuerpo), `409 paciente_ya_vinculado`,
  `503 ocupado` (otro pedido con el mismo documento; se reintenta). Cartera de clínica paginada `desde`/`limite`
  (1..1000) con `total` y `hay_mas` (`0`/`1`, número). La ficha elegida va en `hc_paciente_id` en clínica y en
  `pediatria_paciente_id` en pediatría. La `X` de sexo la web no la guarda (el servidor la toma como vacía).

- [x] **E2.1. DTOs y API de pacientes por especialidad.** RF-8, RF-11, RF-13
  - `core/data/.../network/hc/HcDtos.kt`: `HcPacienteRequest` sin `pacienteElegido` (se va el
    `@SerialName("pediatria_paciente_id")` fijo) y con `nombre_familiar`, `telefono_familiar` opcionales;
    `HcPacienteFicha` suma `nombre_familiar`, `telefono_familiar` con default `""` (pediatría no los manda; clínica
    no manda padre/madre ni `cantidad_hermanos`, que quedan con su default; `fecha_nacimiento` puede venir `null`).
    Respuesta de `resolver`: `paciente`, `creado` y `vinculado_por` (los cuatro valores de arriba).
  - `core/data/.../network/hc/PacientesHcApi.kt` (nuevo): `interface PacientesHcApi` con `pagina(desde, limite)`
    (las fichas de la página y si hay más) y `resolver(pedido, elegida: Long?)`; `PacientesHcApiRemota(cliente:
    HcApiClient, claveElegida: String)` que agrega la elegida al cuerpo con su clave solo si no es null; las
    candidatas del `409 paciente_ambiguo` llegan a quien llama en `TobbException.candidatos` (D1); la página lee
    `hay_mas` como número (D7).
    `class PacientesHcApis(val porEspecialidad: Map<String, PacientesHcApi>)` (envuelta por los genéricos de Koin,
    como `HcApiClients`).
  - `core/data/src/commonTest/.../network/hc/PacientesDtoTest.kt`: con `claveElegida` `"hc_paciente_id"` el cuerpo
    lleva `hc_paciente_id` y no `pediatria_paciente_id`, y al revés; sin elegida no va ninguna de las dos; los nulos
    viajan como `null`; una ficha de clínica (sin padre/madre, con familiar, `fecha_nacimiento: null`) y una de
    pediatría (sin familiar) se leen con los defaults; `hay_mas` `1`/`0` se lee bien; el `409 paciente_ambiguo`
    pasado por `interpretar` trae las candidatas en `candidatos` y otro error las trae vacías (D1).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PacientesDtoTest*'` en verde (antes, en rojo) y
    `.\gradlew :core:data:compileKotlinJvm` sin errores (`HcPediatriaBackend` adaptado a que el request ya no tiene
    la elegida, sin cambiar lo que manda).

- [x] **E2.2. Lógica pura del vínculo (`VinculoPacientes.kt`).** RF-8, RF-11, RF-12, RF-13, RF-36, RF-37
  - `core/data/.../hcgeneral/VinculoPacientes.kt` (commonMain, sin red ni Compose): `documentoParaHc(dni): String?`
    (misma regla que `PacienteVinculoReglas::normalizarDni`: quita puntos y espacios; otro no dígito → null; tope
    `2147483647` comparado como texto/longitud, sin desbordar; < 1000 y 99999 → null) y
    `mensajeDocumentoInvalido(dni)` con el texto exacto de RF-12; `pedidoResolver(paciente, idTobb)` (vacíos →
    `null`; nunca un `""` como dato; familiar de contacto incluido); `sealed interface ResultadoVinculo`
    (`Vinculado(fichaId)`, `ElegirFicha(candidatas)`, `DocumentoInvalido(mensaje)`, `FichaDeOtroPaciente(mensaje)`,
    `FichaNoElegible` (D2), `NoSePudo(motivo)` que conserva la `TobbException` original (D5)) y
    `resultadoDe(e: TobbException)` (candidatas desde `e.candidatos`, D1); `historiasConCartera(especialidadesHc, conApi)`
    ("hepatologia" cuenta como `CodigosHc.GENERAL`; solo las que tienen API; sin repetir);
    `idLocalDeFicha(especialidad, ficha)` (`TobbIds.paciente(...)` si `paciente_id_tobb > 0`, si no
    `"hc-<especialidad>-p<id>"`; nunca mira el documento, RF-37).
  - `core/data/src/commonTest/.../hcgeneral/VinculoPacientesTest.kt`: **la misma tabla que
    `PacienteVinculoReglasTest`** (`12.345.678` y `30 111 222` válidos; `-12345678`, `12345678A`, `0`, `1`, `999`,
    `99999`, `2147483648`, `99999999999`, vacío inválidos; `2147483647` válido); mensaje de RF-12 con el documento
    tal como lo escribió el médico; pedido con vacíos → `null`; `paciente_ambiguo` → `ElegirFicha` con las
    candidatas, `paciente_ya_vinculado` → `FichaDeOtroPaciente` con el texto de RF-36, `dni_invalido` →
    `DocumentoInvalido`, `ficha_no_elegible` → `FichaNoElegible`, otro código → `NoSePudo` con la misma excepción; `historiasConCartera` con `pediatria`+`hepatologia` → `{pediatria, clinica}`, sin API →
    afuera; `idLocalDeFicha` con y sin `paciente_id_tobb`, y dos fichas del mismo documento sin vínculo → dos ids.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*VinculoPacientesTest*'` en verde (antes, en rojo).

- [x] **E2.3. Motivos por historia clínica (`MotivoHc`).** RF-9
  - En `VinculoPacientes.kt` (o archivo propio en `hcgeneral/`): `MotivoHc` + `motivoHc(nombreHc, e)`: sin conexión
    (`red`, `tobb_caido`), sesión vencida (`token`, `sin_token`), `hc_no_habilitada`, `medico_no_vinculado`,
    `licencia_vencida`, otro (con el mensaje de la web; también `ocupado` agotado y el `500` sin código). El texto
    nombra la historia clínica (el nombre visible sale del mapa de D3). Es el que van a usar el resto de la 002 y
    la 003.
  - `core/data/src/commonTest/.../hcgeneral/MotivoHcTest.kt`: cada código da su motivo y su texto contiene el nombre
    de la HC; un código desconocido conserva el mensaje de la web; `status 0` sin código = sin conexión.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*MotivoHcTest*'` en verde (antes, en rojo).

- [x] **E2.4. `PacientesHcRepository`: cartera.** RF-8, RF-9, RF-13, RF-35, RF-37
  - `core/data/.../repos/PacientesHcRepository.kt` (nuevo), `PacientesHcRepository(db, apis: PacientesHcApis,
    nombresHc: Map<String, String>)` (código de HC → nombre visible para los motivos, D3):
    `traerCartera(medicoId, especialidad): Int` (lo que hoy es `HcPediatriaBackend.traerPacientes`, genérico:
    páginas de 300 —≤ 1000—, una transacción por página, corta con página vacía o sin `hay_mas`; no pisa un
    paciente que ya está; asegura `medico_paciente`; guarda `hc_paciente_id` en `paciente_extra` con la
    especialidad y `dirty = false`; llena familiar de contacto al crear; id con `idLocalDeFicha`).
    `sincronizarCarteras(medicoId): ResultadoCarteras(traidos, motivos)`: lee del dispositivo las HC habilitadas
    del médico (`Medico.especialidadesHc`), aplica `historiasConCartera` y trae una por una; cada fallo queda como
    `MotivoHc` y no frena a las demás (**el error ya no se traga**). `fichaConocida(pacienteId, especialidad)`.
  - `core/data/src/jvmTest/.../repos/PacientesHcRepositoryTest.kt` (SQLite en archivo temporal y `PacientesHcApi`
    falsa, como `ConfigHcRepositoryTest`): dos páginas llegan todas; repetir no duplica; un paciente local no se
    pisa; el mismo `paciente_id_tobb` en la cartera de pediatría y de clínica = una sola ficha con los dos vínculos;
    sin vínculo → `hc-clinica-p<id>` estable; nada queda `dirty` (pacientes, `medico_paciente`, `paciente_extra`);
    una cartera que falla da su motivo y la otra llega; médico sin la HC habilitada → no se pide; `hepatologia` →
    se pide la de clínica.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PacientesHcRepositoryTest*'` en verde (antes, en rojo).

- [x] **E2.5. `PacientesHcRepository`: vínculo y elegir ficha.** RF-10, RF-11, RF-12, RF-35, RF-36
  - `vinculo(pacienteId, especialidad): ResultadoVinculo`: con vínculo guardado lo devuelve sin red; si no, valida
    el documento en el dispositivo (inválido → `DocumentoInvalido`, **sin pedido**), arma `pedidoResolver` (id de
    turnos con `TobbIds.numero` o el extra `turnos/tobb_id`) y llama a `resolver`; `200`/`201` → guarda el vínculo
    (`dirty = false`) y `Vinculado`; `503 ocupado` → un reintento a los 2 s y, si sigue, `NoSePudo` con motivo
    "ocupado" (D4); el resto con `resultadoDe` (`NoSePudo` conserva la `TobbException`, D5).
    `elegirFicha(pacienteId, especialidad, fichaId)`: reenvía con la elegida (la clave la pone la API de cada
    especialidad), guarda y devuelve `Vinculado`; `422 ficha_no_elegible` → `FichaNoElegible` (D2) y no guarda nada.
    Sin señal: `NoSePudo(sin conexión)` y nada guardado a medias.
  - Más casos en `PacientesHcRepositoryTest`: guardado → no pide; documento inválido → cero pedidos; `201` creado y
    `200` por `tobb_id`/`dni`/`elegido` → guardado sin `dirty`; `409 paciente_ambiguo` → `ElegirFicha` con las
    candidatas y nada guardado; elegir → se manda la elegida y queda guardada; `422 ficha_no_elegible` →
    `FichaNoElegible` y nada guardado; `409 paciente_ya_vinculado` → texto de RF-36; `503 ocupado` una vez y después
    `200` → `Vinculado`; `503` dos veces → `NoSePudo` "ocupado" (D4);
    el vínculo de clínica no pisa el de pediatría del mismo paciente (RF-35).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PacientesHcRepositoryTest*'` en verde (antes, en rojo
    los casos nuevos).

- [x] **E2.6. Pediatría delega; `HcBackend`, `HcRepository` y `DataModule`.** RF-35 (RF-33 de la 001: pediatría
  no cambia de comportamiento)
  - `HcPediatriaBackend`: crea su `PacientesHcRepository` con `PacientesHcApiRemota(api, "pediatria_paciente_id")`
    (el constructor no cambia); `resolverPaciente` delega en `vinculo` y, si no queda vinculado, lanza una
    `TobbException` con el mismo `status`/`codigo`/mensaje que hoy (la original que conserva `NoSePudo`, D5; con
    documento inválido no hay pedido y fabrica `TobbException(422, "dni_invalido", <mensaje de RF-12>)`, D5); el
    vínculo queda **`dirty = false`** (antes
    `true`); `traerPacientes` queda como atajo público (sin `override`) para `HcCarteraE2ETest`, igual que
    `PAGINA_PACIENTES` y `EXTRA_HC_PACIENTE_ID`.
  - `HcBackend`: se quita `traerPacientes`. `HcRepository`: se quita `sincronizarPacientes` (el `runCatching`).
  - `DataModule`: `single { PacientesHcApis(...) }` con `"pediatria"` y `CodigosHc.GENERAL` (si hay cliente) y
    `single { PacientesHcRepository(get(), get(), <nombres>) }`, con el mapa de nombres armado en `AppDi` desde el
    registro de especialidades (D3); `HcBackends` sin cambios.
  - Hecho cuando: `.\gradlew :core:data:jvmTest` completo en verde; buscar `traerPacientes` y
    `sincronizarPacientes` solo da el atajo de `HcPediatriaBackend` y su E2E; `HcPediatriaE2ETest` y
    `HcCarteraE2ETest` con `--rerun` contra la copia servida de pediatría
    (`-Psalud360.test.hc.pediatria=http://localhost/HCDPediatria-salud360/public/index.php` y el token de prueba,
    sin escribirlo) en verde; lo que deja el E2E de pediatría (la consulta de baja y sus imágenes) borrado por id.

- [x] **E2.7. Lista de pacientes: motivos y `traerCarteras`.** RF-8, RF-9 — compila; falta prueba manual del usuario.
  - `features/pacientes`: `PacientesListViewModel(…, PacientesHcRepository, medicoId, traerCarteras: Boolean)` en
    lugar de `HcRepository`; pide `sincronizarCarteras(medicoId)` solo si `traerCarteras`; expone
    `motivos: StateFlow<List<String>>` en lugar de `mensaje`. `PacientesModule` y `PacientesListScreen` reciben
    `traerCarteras` (parámetro de Koin junto con `medicoId`; cambio de firma aceptado, D6). `PacientesScreens`: los motivos arriba de la lista y,
    con la lista vacía, en lugar de "Todavía no hay pacientes cargados" (con búsqueda sigue "No se encontraron").
  - `composeApp` `MainShell`: `traerCarteras = rol == Rol.MEDICO` (secretaria y administrador no la piden).
  - Hecho cuando: `.\gradlew :features:pacientes:compileKotlinJvm` y
    `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev` sin errores; `.\gradlew :core:data:jvmTest` en verde;
    refresco del `devWeb` con `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` y prueba
    manual en `http://localhost:8080` (XAMPP): el médico de prueba (HC general, sin agenda) ve su cartera de la web
    sin agenda ni recetas; con la copia de hclinica apagada o sin red ve "sin conexión" nombrando la HC y la lista
    con lo que ya tenía; administrador y secretaria no ven motivos de cartera. (La prueba con licencia vencida queda
    para el usuario: la licencia del médico de prueba está extendida a propósito, D8.)

- [x] **E2.8. E2E de la HC general y verificación manual de RF-10 y RF-14.** RF-8, RF-10, RF-14 — E2E en verde
  contra la copia servida; falta prueba manual del usuario (RF-10, RF-14).
  - `core/data/src/jvmTest/.../repos/HcClinicaPacientesE2ETest.kt`: se saltea sin `salud360.test.hc.clinica` /
    `.clinica.token`; con ellas y `salud360.test.hc.dni/.nombre/.apellido` (paciente "PRUEBA SALUD360" con un
    documento verificado libre antes por `SELECT COUNT(*)`), sobre una base SQLite temporal: trae la cartera dos
    veces sin duplicar y sin nada `dirty`; resuelve el paciente de prueba (`201`, crea la ficha, que aparece en la
    cartera al volver a traerla); lo vuelve a resolver sin pedido (vínculo guardado) y con un dispositivo nuevo por
    `paciente_id_tobb` (`vinculado_por: "tobb_id"`). El KDoc trae el comando y el SQL de limpieza (`DELETE` de las
    filas de prueba en `pacientes` y `medico_pacientes` de `hc_clinica`), sin credenciales.
  - Comando: `.\gradlew :core:data:jvmTest --rerun --tests '*HcClinicaPacientesE2ETest*'
    "-Psalud360.test.hc.clinica=http://localhost/HClinica-salud360/public/index.php"
    "-Psalud360.test.hc.clinica.token=<token de prueba>"` + las de `dni/nombre/apellido`.
  - Manual en el `devWeb`: RF-14, el administrador da de alta un médico con la HC general y "Sin agenda" y no
    aparece para sacar turno en turnos local; RF-10, un médico crea un paciente nuevo, queda en turnos sin
    publicarse y **no** se crea su ficha en hclinica hasta que algo la pide. Lo creado (usuario, médico, paciente)
    se borra por SQL puntual en la base local de turnos.
  - Hecho cuando: el E2E en verde contra la copia servida; sin las propiedades, `.\gradlew :core:data:jvmTest` en
    verde y el E2E se saltea; `SELECT COUNT(*)` con el documento de prueba en `pacientes` y `medico_pacientes` da 0
    al terminar; los dos pasos manuales anotados con su resultado. La licencia vencida no se prueba acá (D8).
  - Resultado (2026-10-20): E2E en verde con el médico de prueba (cartera de una sola página: se quitó la
    aserción de "más de una página" de pediatría; la paginación la cubre `PacientesHcRepositoryTest`); suma un
    documento inválido sin pedido. `hc_clinica` limpia con `DELETE` puntual (conteos iguales a antes), token
    revocado (401 en turnos y hclinica) y `salud360_token_cache` vacía. Pasos manuales: pendientes del usuario.

Decidido (2026-10-20, coordinador), sobre los puntos que no cerraban con el código:
- D1. `TobbException` suma `candidatos: List<HcPacienteCandidato> = emptyList()` **al final**, con valor por defecto;
  `HcApiClient.interpretar` lo llena desde el `409 paciente_ambiguo` (como `errores` del 422).
- D2. `ResultadoVinculo` tiene un caso propio `FichaNoElegible` (`422 ficha_no_elegible`).
- D3. `PacientesHcRepository` recibe por constructor un `Map<String, String>` código de HC → nombre visible, armado
  en `composeApp` (`AppDi`) desde el registro de especialidades; `core/data` no depende de `features/hc`.
- D4. `503 ocupado`: un reintento a los 2 s; si sigue, `NoSePudo` con motivo "ocupado".
- D5. `ResultadoVinculo.NoSePudo` conserva la `TobbException` original. Con documento inválido la app no hace el
  pedido y, para la delegación de pediatría, fabrica `TobbException(422, "dni_invalido", <mensaje de RF-12>)`.
- D6. Se aceptan los cambios en `PacientesModule` y en la firma de `PacientesListScreen` (`traerCarteras`).
- D7. `hay_mas` de clínica se lee como número (`0`/`1`) en el DTO.
- D8. La licencia vencida no se prueba en E2.7/E2.8 (la del médico de prueba está extendida): queda para la prueba
  manual del usuario.

Al terminar la tanda: validación del reviewer, prueba manual del usuario y MEMORY.md.

Validada por el reviewer el 2026-10-20: APROBADO.
Nota del reviewer: en RF-10 la prueba manual solo comprueba que no se cree la ficha en hclinica antes de tiempo; el
vínculo lo cubren el E2E (`HcClinicaPacientesE2ETest`) y los tests del repositorio (`PacientesHcRepositoryTest`).

## Pendientes para el envío de consultas (parte 2, aún sin tareas)

*(Nota 2026-10-28: en la **entrega L** estos dos puntos se cubren con los candados de E5, que impiden todo envío de la
HC general y dejan lo viejo sin tocar. Su resolución definitiva —filtrar `activo = 0`, decidir qué hacer con lo viejo
sin enviar— es de la **entrega E**.)*

Anotados el 2026-10-27 por el cierre de la tanda D de la 001 (T54, hallazgo H2 del reviewer). **No se implementan en la parte 1.**
Al registrar el backend de `clinica` en `HcApiSync` (el `HcBackend` de la HC general):
- **Antecedentes `activo = 0`:** la migración `8.sqm` deja con `activo = 0` al antecedente perdedor cuando la misma clave estaba en
  `clinica` y en `hepatologia` (no se borra). El envío tiene que filtrar los antecedentes `activo = 0` (o decidir a propósito qué
  hacer con ellos): si no, el servidor recibiría como vigente lo que el médico ya no ve.
- **Envío en bloque:** todas las consultas migradas de hepatología traen `dirty` y `remoto_id = ''` de antes (los datos nunca
  tuvieron API). Al registrar el backend saldrían juntas, como historial viejo. Hay que decidir antes (confirmación del médico, tope por
  lote o marcarlas como ya resueltas) y probarlo con una base migrada.

---

# Parte 2, entrega L — Consultas de la web en la app, solo lectura (tandas E3 a E9)

**Aprobadas por el usuario el 2026-10-28** (redactadas ese mismo día). Spec: `spec.md` ("Parte 2 en entregas", RF-38 a
RF-63). Plan: `plan.md`. Las dudas Q1 a Q18 están resueltas (Q15 = nunca borrar; las demás, las recomendaciones del
planificador). Cada tanda se aprueba antes de empezar la siguiente, **salvo la tanda S, que el usuario aprobó entera**
("arranquemos con la tanda S"). Equivalencia con el pedido: **tanda S = E3 y E4** (servidor), **tanda A = E5, E6 y E7**
(app), **tanda V = E8** (validación local) **y E9** (producción, **requiere aprobación aparte**). Orden dentro de la
tanda A (D6, 2026-10-29): E5, E6.1 a E6.4b, E6.6, E7.1 a E7.3, **E6.5**, E7.4.

Tamaños: S = hasta 20 min, M = hasta 30 min. Si una tarea M no entra, se divide antes de empezar.
"Toca API Laravel: sí" = ya no hay que preguntar en las tareas de E3 y E4 (la aprobación de la tanda S las cubre,
incluida la base local de datos de prueba de E3.1); para cualquier otra tarea que toque ese repo, o para E9, se pregunta.

Decisiones de la spec de las que dependen (todas resueltas): E3.1 (Q11), E3.2 (Q2, Q12), E4.1 (Q1, Q6, Q7, Q9), E6.2 a
E6.4b (Q7, **Q15 = nunca borrar**), E7.2 (Q3, Q4, Q9, Q14).

**Alcance cambiado por Q15 (2026-10-28):** el planificador había supuesto un "espejo" opcional (`quitarDeLoTraido`).
Con "nunca borrar" esa función no existe: se reemplaza por `marcarAusentes` (E6.2), el backend no emite ningún
`DELETE` ni vacía nada (E6.3, E6.4), y la marca "Ya no figura en la web" tiene su tarea propia, **E6.4b** (nueva, M), y
sus textos y pantallas (E7.1 a E7.3). E6.3 y E6.4 no crecen porque la marca salió a E6.4b.

## Reglas para todas las tareas de la L (además de las de arriba)
- Commits locales como `rodri89` (ya configurado), uno por tarea, **sin push** en ningún repo. Pushear `main` publica la
  app: no se hace hasta que producción tenga las rutas y el usuario lo pida (plan, "Orden de despliegue").
- **Nada contra producción** salvo la tanda E9, que tiene aprobación aparte y no usa tests automáticos. Las pruebas
  de la app corren con `-Pentorno=dev` (en esta máquina las URLs locales salen de `local.properties`).
- Los datos de prueba son **inventados** ("PRUEBA SALUD360", documentos verificados libres con `SELECT COUNT(*)`
  antes de usarlos). Las bases locales `hc_clinica`, `hc_pediatrica` y `turnosonlinebb` tienen datos reales: solo se
  mira estructura y agregados (`SHOW COLUMNS`, `COUNT`, valores distintos de columnas de códigos), nunca filas de
  pacientes; no se copia nada de ahí al repo ni a los tests.
- Restaurar con `UPDATE`/`INSERT`/`DELETE` puntuales (por id, en el orden de las claves foráneas: hijas → `consultas` →
  `medico_pacientes` → `pacientes`); **nunca** `DROP TABLE`. Scripts y volcados auxiliares en
  `C:\Users\flor\AppData\Local\Temp\opencode\lectura\` (fuera del repo).
- Servidor: PHP 7.3 de 32 bits (sin `fn`, sin propiedades tipadas; ids largos comparados como texto antes de `(int)`);
  `php -l` de cada archivo; copiar a la copia servida `C:\xampp\htdocs\HClinica-salud360` y `php artisan route:clear`
  (y `config:cache` si se toca `config/`); phpunit con `C:\xampp\php\php.exe vendor\bin\phpunit tests\Unit\<Test>.php`
  (si falla, ver el mensaje con `php -r`). La licencia del médico de prueba de hclinica está extendida a propósito: no
  restaurarla. Vaciar `salud360_token_cache` en las pruebas que revoquen tokens. Throttle de 60 pedidos por minuto por IP:
  las pruebas con curl se espacian (~1,1 s).
- App: tests primero (en rojo), después el código, tests en verde; `.\gradlew :core:data:jvmTest --tests '*Nombre*'`;
  `features/*` no tiene tests, la lógica nueva vive en `core/data` (commonMain) con "hoy"/reloj como parámetro.
  Si Gradle falla por `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- La migración de esquema está **fuera** de la L: si una tarea parece necesitarla, se para y se pregunta (`9.sqm`
  sería la próxima; REQUIERE aprobación explícita).
- No matar el `devWeb` (Gradle/Node) del usuario; para refrescarlo:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev`.
- Si al hacer una tarea aparece un envío a la web (cualquier método distinto de `GET`) hacia `clinica`, se para todo y se avisa.

## Tanda E3 — Servidor I: datos de prueba, mapa y lectura de una consulta (hclinica, rama `salud360-api`)

Contrato: `plan.md`, sección "Servidor". URL base de las pruebas:
`http://localhost/HClinica-salud360/public/index.php/api/salud360/...`; token del médico de prueba sacado de
`auth/login` de turnos local (credenciales que la sesión pide al usuario, sin escribirlas en ningún lado).

- [x] **E3.1. Datos de prueba en la copia local y scripts de limpieza y suma de control.** RF-47 a RF-50 (base de todas las
  pruebas) — **M** — Toca API Laravel: no (toca la base local de datos de prueba; cubierta por la aprobación de la tanda S del 2026-10-28)
  - Antes de insertar, solo estructura y agregados sobre `hc_clinica`: `SHOW COLUMNS` de las tablas de plan.md ("Mapa") y
    `SELECT activo, COUNT(*) FROM consultas GROUP BY activo` (igual para `tipo_consulta` y `es_foto`; Q11); los nombres
    de las tablas de audio y fotos de consulta (`SHOW TABLES LIKE …`). Se anota todo en
    `...\opencode\lectura\esquema.txt` (sin datos de pacientes). Si una columna del mapa no existe, se avisa antes de seguir.
  - Fixture inventado (script `fixture.sql`, fuera del repo): un paciente "PRUEBA SALUD360" en la cartera del médico de
    prueba y consultas: (a) cerrada propia tipo 1 con motivo (con `fecha`), conductas, nota, hábitos, diuresis, somnia,
    examen físico, alimentación (celíaco con detalle), serologías (mezcla de 0, 1 y 2), Child-Pugh, los 5 estudios, dos
    cargas de laboratorio general (incluidos albúmina, fósforo, concentración, ácido úrico y B12) y una del reducido
    (con `otro`), peso, antecedentes familiares (con `dependientes_terceros` y nota), exámenes complementarios,
    interconsultas y un pendiente; (b) cerrada de **otro** médico, tipo 4; (c) abierta propia con filas `activo = 2`;
    (d) `es_foto = 1`; (e) `activo = 0`; (f) con un PDF en `consulta_pdfs` (y una cardiológica de 191 caracteres);
    (g) `tipo_consulta = 2`.
  - `limpiar.sql` (`DELETE` puntuales por `paciente_id` en orden de claves foráneas) y `suma.sql` (`CHECKSUM TABLE` de
    las tablas leídas + `consultas`, `pacientes`, `medico_pacientes` y `medico_infos`).
  - Hecho cuando: `COUNT(*)` de cada tabla con el paciente de prueba coincide con lo esperado; `limpiar.sql` deja 0 y
    `fixture.sql` se vuelve a correr sin error (dos vueltas); `suma.sql` da el mismo resultado dos veces seguidas sin
    tocar nada; ningún dato real se copió a ningún archivo del repo.
  - Hecho (2026-10-28): fixture cargado y dejado cargado en `hc_clinica` local (documentos 99000100 y 99000101, texto
    "Prueba Salud360", un médico extra de `users`); scripts y notas fuera del repo en `%TEMP%\opencode\lectura\`
    (`fixture.sql`, `limpiar.sql`, `contar.sql`, `ids.sql`, `suma.sql`, `cargar.cmd`, `limpiar.cmd`, `esquema.txt`) y
    `%TEMP%\opencode\e31\` (`ids-y-limpieza.txt` y los conteos/sumas de las 68 tablas "de antes"). Probado: carga con
    `STRICT_ALL_TABLES,NO_ZERO_DATE,...` y con `STRICT_TRANS_TABLES`; `contar.sql` da lo esperado; `limpiar.sql` probado con
    `ROLLBACK`, aplicado 4 veces (cargar-limpiar-cargar) y las 68 sumas y conteos volvieron idénticos a los de antes;
    `suma.sql` igual dos veces seguidas. Con el fixture cambian 29 tablas y las otras 39 (incluido lo de la spec 003, sin
    tocar) quedan idénticas.
  - Hallazgos que cambian tareas siguientes (detalle en `esquema.txt`): (1) **no existe tabla de diagnósticos** en
    hclinica ni base local, y el audio es `grabaciones_audio`, que **no existe en la base local** (migración sin aplicar):
    E3.3 no puede hacer `SELECT` ciego de esas dos y debe tolerar tabla ausente sin `Schema::`; (2) la base local no tiene
    `consultas.activo = 0`, `tipo_consulta` 2 ni `es_foto = 1` (solo `activo` 1 y 2, tipos 1 y 4): esos casos son solo del
    fixture y Q11 no se puede confirmar con datos reales (E9.1); (3) el texto de E4.1 omite que (f) también se lista (4
    consultas: a, b, c y f); (4) casi todo es `NOT NULL` sin default y la base local no es estricta: el cliente `mysql -e
    "source"` **sigue tras un error** (por eso las cargas van por stdin); (5) se sumó una consulta (h) de otro médico en un
    segundo paciente fuera de la cartera, que E4.2 necesita para `403 fuera_de_cartera` de una consulta. Los ids cambian
    en cada carga (`ids.sql`). El fixture de E3.1 sigue cargado para E3.2+ y E4; se limpia con `limpiar.cmd` (E4.3/E8.1).

- [x] **E3.2. Mapa y reglas puras, con phpunit.** RF-40, RF-42, RF-43, RF-52 — **M** — Toca API Laravel: **sí (aprobada con la tanda S el 2026-10-28)**
  - Tests primero: `tests/Unit/MapaClinicaTest.php` (sin Laravel ni base): para cada analito de `labGeneralClinica` y
    del reducido de la app (la lista va copiada en el test y el id se deriva con la regla de la app
    `lowercase().replace(Regex("[^a-z0-9]+"), "_")`) existe una columna; casos nombrados: albúmina, fósforo, concentración,
    ácido úrico, B12 y `otros`→`otro`; `celiaco`→`seliaco`; `dependiente_terceros`→`dependientes_terceros`;
    `tabaquismo` sin columna; las 16 serologías; las 5 tablas de estudios. `tests/Unit/ConsultasReglasTest.php`:
    tipo y estado (1→`control`, 4→`telemedicina`, `es_foto`/otros→omitida; `activo` 2/1/0), fecha útil (`1000-01-01` y
    `0000-00-00`→`null`), `limite` 1..200, serologías 0/1/2→vacío/`SI`/`NO` (nunca `NO` por 0), alimentación `"1|detalle"` y
    `0` sin detalle no se manda, Child-Pugh `0`/vacío no se manda, armado de laboratorio con el mapa, qué entra en `omitidas`.
  - Código: `app/Services/Salud360/MapaClinica.php` y `ConsultasReglas.php` (nuevos, sin base). El mapa se contrasta con
    `esquema.txt` de E3.1 (cualquier columna web que falte se anota y se resuelve antes de seguir).
  - Hecho cuando: phpunit de los dos tests en verde (antes, en rojo) en la copia servida; `php -l` sin errores; buscar
    `fn (`, propiedades tipadas, `DB::` e `insert|update|delete` en los dos archivos no da resultados; los analitos
    desalineados del plan coinciden con los que el test calcula (si difieren, se corrige el plan).
  - Hecho (2026-10-29): hclinica `a5ec2cc` (rama `salud360-api`, sin push). Nuevos: `app/Services/Salud360/MapaClinica.php`,
    `ConsultasReglas.php`, `tests/Unit/MapaClinicaTest.php`, `ConsultasReglasTest.php`; nada más cambió. Rojo previo (copiados
    solo los tests a la servida): 91 y 159 errores por clase inexistente. Verde: 91 tests / 308 aserciones y 159 / 222;
    `EnfermedadesReglasTest` (51), `PacienteVinculoReglasTest` (15), `PerfilesTest` (19) y `tests/Unit` entera (336) en verde en la
    servida; `php -l` limpio; sin `fn`, propiedades tipadas, `DB::`, `Schema::`, `medico_infos` ni `insert|update|delete` en los dos
    archivos de código; la servida es igual al clon en los cuatro. Mutaciones probadas (`otros`→`otro`, ácido úrico sin mapear, 0→NO):
    los tests fallan. El mapa se contrastó con `information_schema` de `hc_clinica` local (solo estructura): 21 tablas, 0
    diferencias, laboratorio general (52) y reducido (15) en el mismo orden que la tabla. **Los desalineados del plan coinciden con
    los que calcula el test** (albúmina, fósforo, concentración, ácido úrico, B12 y `otros`); no apareció ningún otro de laboratorio ni
    de serologías, Child-Pugh, estudios o textos; el plan no cambia. Se sumó `DESTINO_SECCION` (la tabla de las 25 secciones del plan,
    verificada contra `CatalogoSecciones`).
  - Decisiones chicas que la spec no fijaba (cambiables, con test): `limite` que no es un entero → 50 y `desde` → 0 (no `422`; el
    `422` queda para `paciente_id`); un texto de solo espacios cuenta como vacío y no aparece (un `"0"` sí); Child-Pugh `4` (valor viejo
    que la app ya ignora) no se manda; una casilla de alimentación sin marcar pero con detalle se manda como `0|detalle`; `clasificar`
    da prioridad a `baja` > `foto` > `otro` > `sin_fecha` (una consulta suma en una sola clase) y trata `es_foto` distinto de 0 (o nulo)
    como foto. Los enteros se saturan por dígitos, sin `(int)` (PHP de 32 bits).
  - Para E3.3 y E4.1 (no son de esta tarea): `ConsultasReglas` no arma antecedentes familiares ni estudios (E4.1 decide, por ejemplo,
    si una casilla en 0 sin detalle se manda); `diagnosticos` en `no_leidas` sigue sin tabla de donde leerse (hallazgo 1 de E3.1).

- [x] **E3.3. Servicio de lectura de una consulta.** RF-39, RF-40, RF-42, RF-43, RF-50 — **M** — Toca API Laravel: **sí (aprobada con la tanda S el 2026-10-28)**
  - `app/Services/Salud360/ConsultasService.php` (nuevo, solo `SELECT`): `leer($consulta)` con `secciones`,
    `examen_fisico` y `no_leidas` (solo ids con filas `activo = 1` en `consulta_pdfs`, audio, fotos, diagnósticos y
    antecedentes personales; nombres de tablas de E3.1). Textos: fila `activo = 1` de mayor `id`.
  - Hecho cuando: llamado con `php -r` (bootstrap de Laravel de la copia) sobre las consultas (a) a (g) de E3.1, devuelve
    el JSON esperado de plan.md (a: todo mapeado; b: igual; c: sin las filas `activo = 2`; f: `no_leidas` con
    `documentos`); un campo vacío no aparece; `suma.sql` antes y después es idéntica; `php -l` sin errores; la búsqueda de
    `insert|update|delete|save\(|create\(|Schema::|DB::statement|asegurarColumna|inicializarCampos|medico_infos` en el
    archivo no da resultados.
  - Hecho (2026-10-29): hclinica `92e5977` (rama `salud360-api`, sin push). Nuevo: `app/Services/Salud360/ConsultasService.php`
    (`leer`, `noLeidas`, `tablasExistentes`; solo `SELECT`). Reglas puras nuevas, con tests, en `ConsultasReglas` (`idTexto`,
    `idDe`, `clasificarFila`, `mapa`, `tablasNoLeidas`, `noLeidas`) y `MapaClinica::NO_LEIDAS`. Rojo previo: 28 errores en
    `ConsultasReglasTest` y 3 en `MapaClinicaTest` (método y constante inexistentes). Verde: `MapaClinicaTest` 94, `ConsultasReglasTest`
    188, `EnfermedadesReglasTest` 51, `PacienteVinculoReglasTest` 15, `PerfilesTest` 19 y `tests/Unit` entera 368 (antes 336). `php -l`
    limpio; la búsqueda de `insert|update|delete|save(|create(|Schema::|DB::statement|asegurarColumna|inicializarCampos|medico_infos|
    paciente_id_tobb` (también sin distinguir mayúsculas) en el servicio da 0, y sin `fn` ni propiedades tipadas; la servida es igual al clon
    en los cinco archivos. Leído el fixture con el bootstrap de Laravel de la servida: **a** 11 secciones (textos, alimentación `1|`+detalle
    en hiposódica y celíaco, serologías 12 de 16, Child-Pugh 5) + examen físico de 5, `no_leidas` `[]`; conductas lee la fila activa de
    mayor id (no la de baja con id mayor); **b** 7 secciones (alimentación solo `diabetes: "1|"`, serologías 2), examen de 2, el PDF
    `activo = 0` no da `documentos`; **c** motivo y nota, sin alimentación, serologías ni Child-Pugh (todo en 0), examen `{}`; **f** solo
    motivo, `no_leidas` `["documentos"]`; **h** (otro paciente) motivo; **d**, **e** y **g** dan null (no se leen). Mismas salidas con
    cuatro `sql_mode` (el de Laravel, `STRICT_ALL_TABLES…`, `STRICT_TRANS_TABLES` y sin modo). Los casos que el fixture no tiene (filas
    `activo = 2` en las tablas por consulta, audio presente, fotos y antecedentes personales con datos, texto de solo espacios, `"0"`, texto
    con ñ/emoji/salto, PDF de otro paciente) se probaron con 21 aserciones en una base descartable `e33_scratch` (copia de las filas del
    fixture, ya borrada) y dos mutaciones del servicio (sin filtro `activo`, orden ascendente) las hacen fallar. `CHECKSUM TABLE` de las 68
    tablas, conteos de las 68 y `suma.sql` iguales antes y después de las lecturas; el fixture de E3.1 sigue cargado.
  - Decisiones que la spec no fijaba (cambiables, con test): (1) `leer` recibe la fila de `consultas` y devuelve `null` para todo lo que
    `clasificar` no manda a la lista (`baja`, `foto`, `otro`, `sin_fecha`), no solo `activo = 0`: el controlador (E4.2) lo contesta
    `404 consulta_no_encontrada`; una consulta que la lista no ofrece no tiene cabecera que mostrar. (2) `secciones` y `examen_fisico`
    salen siempre como objeto, también vacíos (un `[]` rompería la lectura de la app como mapa). (3) `no_leidas`: `fotos` suma
    `consulta_fotos` y `examenes_complementarios_fotos`; todas las tablas se filtran por `consulta_id`, `paciente_id` y `activo = 1`
    (la de PDFs no tiene clave foránea: una fila con otro `paciente_id` no cuenta). (4) Tablas ausentes: se averiguan en
    `information_schema` (una consulta por `leer`), sin `Schema::` ni `hasTable`; si esa consulta fallara, `leer` falla (500) en vez de callar
    un aviso. Las tablas de las secciones (`motivo_consultas`…) no son opcionales: si faltan en producción es un error visible (E9.1).
    (5) `diagnosticos` queda fuera de `no_leidas`: sin tabla, el id nunca se informa (si en producción existiera una tabla de diagnósticos, no
    se leería: confirmarlo en E9.1). (6) Los formularios (alimentación, serologías, Child-Pugh, examen físico) también toman la fila
    `activo = 1` de mayor id. (7) El motivo de `secciones` lleva solo `texto` (como el JSON del plan); su `fecha` va en `historia.motivos` (E4.1).
  - Para E4.1/E4.2: los antecedentes familiares son del historial del paciente (E4.1), no de `leer`; allí falta decidir la casilla en 0 sin
    detalle: se propone la misma regla que la alimentación (no se manda) y `ConsultasReglas` todavía no tiene la función. El controlador
    arma la cabecera (`consulta`, `propia`, `medico_nombre`) y busca la fila (`consultaVisible`).

Al terminar la tanda: validación del reviewer y MEMORY.md. **Aprobación de la E4 recién después.**

## Tanda E4 — Servidor II: lista, historial del paciente, rutas y no-escritura (hclinica, rama `salud360-api`)

- [x] **E4.1. Servicio: lista de consultas e historial del paciente.** RF-38, RF-41, RF-43, RF-50 — **M** — Toca API Laravel: **sí (aprobada con la tanda S el 2026-10-28)**
  - En `ConsultasService`: `listar($pacienteId, $limite, $desde)` (activo 1/2, tipos 1 y 4, `es_foto = 0`, `total`, `hay_mas`,
    `omitidas`, `propia`, `medico_nombre` con un solo `SELECT … WHERE id IN (…)`) e `historia($pacienteId)` (antecedentes,
    laboratorios, estudios, motivos, exámenes complementarios, interconsultas, pendientes; solo `activo = 1` y de
    consultas con `activo` 1 o 2).
  - Hecho cuando: sobre el paciente de prueba, `listar` devuelve (a), (b) y (c) —no (d), (e), (g)— con `omitidas` =
    `{foto: 1, otro: 1}` y `propia` verdadero solo en (a) y (c); `historia` devuelve los laboratorios con ids de la app,
    los estudios con su `consulta_id`, el motivo de todas las consultas y los antecedentes; el tamaño del JSON del
    paciente de prueba se anota; `EXPLAIN` de los `SELECT` por paciente sin recorridos completos de tablas grandes (o lo
    anotado); `suma.sql` idéntica; `php -l` y búsqueda de escrituras sin resultados.
  - Hecho (2026-10-29): hclinica `3905b0d` (rama `salud360-api`, sin push). Cambian `ConsultasService.php` (`listar`, `historia`), `ConsultasReglas.php`
    y `tests/Unit/ConsultasReglasTest.php`. Rojo previo: 66 errores de 255 (métodos inexistentes). Verde: `ConsultasReglasTest` 255 (antes 188),
    `MapaClinicaTest` 94, `EnfermedadesReglasTest` 51, `PacienteVinculoReglasTest` 15, `PerfilesTest` 19 y `tests/Unit` entera 435 (antes 368). `php -l` limpio;
    sin `fn` ni propiedades tipadas; la búsqueda de `\b(insert|update|delete)\b|save\(|create\(|Schema::|DB::statement|DB::raw|asegurarColumna|inicializarCampos|medico_infos|paciente_id_tobb`
    da 0 (ojo: la búsqueda simple `update` encuentra la columna `updated_at` de la cabecera en `ConsultasReglas`; E4.3 tiene que usar `\b`). La servida es igual al clon.
    Fixture (paciente 1, médico de prueba): `listar` = c, b, a, f (`total` 4), `omitidas {foto: 1, otro: 1, sin_fecha: 0}` (la baja e no se cuenta, Q11), `propia`
    solo en c, a y f (b es de otro médico); `historia` = laboratorio general 2, reducido 1, peso 2, `laboratorio_sin_fecha` 1 (la de f), estudios 2/1/1/2/1, motivos 6
    (a, b, c, d, f, g; no e), exámenes 2, interconsultas 1, pendientes 1 y antecedentes de la fila activa de a (6 casillas y nota; la fila de b, de `activo` 0 y mayor id,
    no cuenta). Todo contrastado con SQL directo (PDO, sin el mapa) con cuatro `sql_mode` (el de Laravel, `STRICT_TRANS_TABLES`, `STRICT_ALL_TABLES…` y sin modo):
    mismas salidas. Queries: `listar` 2 (1 si el paciente no tiene consultas) y `historia` 13, siempre, también con 300 cargas de 52 valores y 300 motivos (sin N+1);
    `EXPLAIN`: todos por `paciente_id` con su índice y `consultas` por clave primaria (solo `pesos` de 6 filas elige recorrer la tabla, por tamaño). Tamaño del JSON del fixture:
    lista 996 bytes, historia 3664 (paciente 2: 326 y 441). Sobre los 1015 pacientes reales de la copia (solo agregados): historia máxima 6361 bytes (p95 2812), lista máxima 5553,
    13 y 2 queries siempre, 0 errores y 0 fallas de `json_encode`; peor caso inventado (300 cargas de laboratorio general + 300 motivos en una base descartable `e41_scratch`, ya
    borrada) 309 KB. `CHECKSUM TABLE` de las 68 tablas, conteos y `suma.sql` iguales antes y después; `contar.sql` 32 OK; el fixture sigue cargado. Mutaciones probadas (9): casilla en
    0 que se manda, sin la fecha `2000-01-01`, `hay_mas` con `<=`, la baja contada en `omitidas`, sin el filtro de consulta activa, sin `activo = 1` de la fila, pendientes y
    antecedentes la fila más vieja y el SELECT de nombres por médico: los tests o la base descartable las detectan.
  - Decisiones que la spec no fijaba (cambiables, con test): (1) `listar` recibe un cuarto parámetro `$medicoId` (`users.id` del pedido) para `propia`; el texto de la tarea no lo
    nombra. (2) `omitidas` de la lista es `{foto, otro, sin_fecha}`: la baja no figura (Q11), aunque el pedido de esta tarea mencionaba `baja`. (3) La cabecera no lleva `edad_meses`
    (el plan sí): en clínica `consultas.edad_paciente` vale 0 en todas las filas reales, no es una edad. (4) La web guarda `2000-01-01` cuando una carga de laboratorio o de peso
    no trae fecha (`MedicoController`: `guardarLaboratorioGeneral`, `guardarLaboratorioGeneral2`, `guardarPeso`); `fechaDeCarga` la toma como vacía, además de `1000-01-01` y
    `0000-00-00`. Esto corrige el fixture y las reglas de E3.1/E3.2, que solo sabían de `1000-01-01`; `fechaUtil` no cambia. (5) Una carga de laboratorio o de peso sin fecha útil
    NO se manda (el plan ya tenía `omitidas.laboratorio_sin_fecha`) y se cuenta, salvo que no tenga ningún valor (no hay nada que perder); entra el peso en esa cuenta. Una carga sin
    ningún valor, y un estudio, motivo, examen o interconsulta sin texto, no se mandan (la web crea filas vacías). Estudios y motivos con texto y sin fecha sí, con `fecha: null`.
    (6) Antecedentes familiares: `{flag, detalle}` con `flag` 1 o 0 (solo el 1 es marcada), `detalle` solo si lo hay; una casilla en 0 sin detalle no se manda; en 0 con detalle va con
    `flag` 0 (como la alimentación); `nota` como texto; la fila toda en 0 y sin nota da `antecedentes_familiares: null`. Es la ÚLTIMA fila activa y válida: si está vacía no se busca
    una anterior (la web muestra esa fila); igual para `pendientes`. (7) Todo lo de la historia filtra también por `consultas.activo` 1 o 2, como pide la tarea; la web no filtra la
    consulta para antecedentes y laboratorio, así que una carga de una consulta anulada que la web muestra, acá no sale. (8) Exámenes e interconsultas se ordenan por `numero`, el
    resto por `fecha desc, id desc`; no llevan `consulta_respuesta` (el JSON del plan no lo incluye aunque el mapa lo nombra). (9) `historia` y `listar` no toleran tablas ausentes
    (las de la historia no son opcionales, igual que en E3.3: si faltan en producción es un error visible, E9.1); `listar` y `historia` no leen audio, fotos ni PDFs, así que la
    función de tablas existentes no se usa acá. (10) Los ids salen como entero; en el PHP de 32 bits uno mayor que 2147483647 se satura (la base llega a miles). Los ids de
    estudios se repiten entre tablas (veda, biopsia y tc_rmn comparten id 1797 en el fixture): la app tiene que armar el id local con el tipo (E6.2).

- [x] **E4.2. Controlador y rutas.** RF-38, RF-39, RF-41, RF-50, RF-51, RF-59 — **M** — Toca API Laravel: **sí (aprobada con la tanda S el 2026-10-28)**
  - `ConsultaController` (`index`, `show`, `historia`); `Salud360Controller` suma `consultaVisible` y `formatearConsulta`;
    `routes/api.php` suma `GET consultas`, `GET consultas/{id}`, `GET pacientes/{id}/historia` dentro del grupo `salud360`.
  - Hecho cuando (copiado a la servida y con curl, espaciando los pedidos): sin token → `401 sin_token`; token del
    administrador en las tres → `403 sin_permiso`; médico: `consultas?paciente_id=<prueba>` → 200 con lo de E4.1;
    `limite=0` y `limite=5000` se acotan; `paciente_id` faltante → `422 datos`; paciente fuera de la cartera →
    `403 fuera_de_cartera`; paciente inexistente → `404 paciente_no_encontrado`; consulta (e) → `404 consulta_no_encontrada`;
    `consultas/{id de otro paciente fuera de cartera}` → `403 fuera_de_cartera`; `consultas/<a>` y `pacientes/<id>/historia`
    → 200; `php artisan route:list` muestra las tres con el middleware `salud360`; la web de hclinica sigue entrando normal.
  - Hecho (2026-10-29): hclinica `2d60bf9` (rama `salud360-api`, sin push). Nuevo `ConsultaController` (`index`, `show`, `historia`);
    `Salud360Controller` suma `idWebValido`, `pacienteVisible`, `consultaNoEncontrada`, `consultaVisible` y `formatearConsulta`; `routes/api.php`
    suma las tres `GET`. Sin lógica pura nueva (no hay test en rojo): phpunit existente en verde en la servida (`MapaClinicaTest` 94,
    `ConsultasReglasTest` 255, `EnfermedadesReglasTest` 51, `PacienteVinculoReglasTest` 15, `PerfilesTest` 19, `tests/Unit` 435). `php -l`
    limpio en los tres; copiados a la servida (iguales al clon en contenido) y `route:clear` (esa copia no tiene caché de rutas: solo
    `config.php`, que no se tocó). `route:list --path=salud360`: las 3 nuevas, todas `GET|HEAD`, middleware `api,salud360` (el `throttle:60,1`
    es del grupo `api`); ninguna ruta de escritura de consultas. Curl (fixture de E3.1, token del médico de prueba; 38 pedidos espaciados
    ~1,25 s): sin token 401 `sin_token` (las 3 rutas); token malo 401 `token`; lista del paciente 200 con 4 consultas (c, b, a, f; `total` 4;
    `omitidas` `{foto 1, otro 1, sin_fecha 0}`; `propia` solo en c, a y f); `limite=0` 1 consulta y `hay_mas` 1; `limite=5000` las 4;
    `limite=2&desde=1` b y a; `limite=abc&desde=-5` y `limite[]=1` = valores por defecto (200); `paciente_id` faltante, `abc`, `[]`, `-1` y de
    10 cifras 422 `datos_invalidos`; paciente fuera de cartera 403 `fuera_de_cartera`; inexistente 404 `paciente_no_encontrado`; consulta a, b
    (`propia: false`, `telemedicina`), c (`ABIERTA`) y f (`no_leidas: ["documentos"]`) 200; d (foto), e (baja), g (tipo otro), inexistente y `0`
    404 `consulta_no_encontrada`; h (paciente fuera de cartera) 403 `fuera_de_cartera`; id `abc` o de 10 cifras 422; historia del paciente 200
    (3690 bytes), fuera de cartera 403, inexistente 404, `abc` o 10 cifras 422; `POST consultas`, `PUT` y `DELETE consultas/<id>` y `POST
    pacientes/<id>/historia` 405 (no hay rutas). JSON válido en todo (`examen_fisico` y `secciones` vacíos salen `{}`). Cabeceras HTTP
    idénticas a las de `pacientes/{id}`. `/`, `/login` e `index.php` de la web de hclinica 200 (como el 21/10). `CHECKSUM TABLE` de las 68
    tablas, conteos de las 68 y `suma.sql` iguales antes y después, salvo `salud360_token_cache` (escrita por el middleware; vaciada al final,
    quedó en 0). Falla de la base simulada con `php -r` (servicio que lanza): las tres rutas dan 500 `consultas` con mensaje fijo, sin SQL ni
    traza aunque `APP_DEBUG` esté en `true`; el detalle va a `storage/logs` (el archivo de la prueba se borró). **No se probó el token del
    administrador** (no hay token de administrador de clínica en `respaldos`; las tres rutas lo cortan con `soloMedico`, igual que
    `PacienteController`/`EnfermedadController`, que sí están probados así).
  - Decisiones que la spec no fijaba (cambiables): (1) `paciente_id`, `limite` y `desde` se leen solo de la query (no del cuerpo); `limite` y
    `desde` inválidos no dan 422 (decisión de E3.2: valores por defecto). (2) En `show` el paciente sale de la propia consulta (la ruta no
    lleva paciente); el orden es consulta existente → ficha activa (404) → cartera (403) → recién ahí `leer`: una baja o foto de un paciente
    ajeno da 403, no 404 (no se dice nada de lo que no es del médico), y una consulta de un paciente dado de baja da `404 paciente_no_encontrado`.
    Por eso «consulta que no es del paciente» no existe como caso en `show`. (3) Un fallo de la base en las tres rutas da `500` con código
    `consultas` y el mensaje fijo «No se pudo leer la historia clínica.» (ningún otro controlador atrapa excepciones; este sí para no mostrar
    la traza). (4) `pacienteVisible` vive en la base y no reemplaza al `show` de pacientes ni a enfermedades (no se tocaron). (5) La app
    puede ver el nombre del médico de una consulta ajena (`medico_nombre`), ya previsto en el plan.
  - Para E4.3: el registro de Apache tiene cuatro pedidos NO `GET` a rutas con ese nombre, míos de esta tarea, a las 11:45:44-48 del
    2026-10-29 (`POST consultas`, `PUT` y `DELETE consultas/<id>`, `POST pacientes/<id>/historia`), todos `405` porque esas rutas no
    existen; no tocaron nada. Hay además pedidos viejos a `consultas/abrir`, `…/secciones`, etc. de septiembre y octubre: son de la copia de
    **pediatría** (otra base), no de esta. El 405 de una ruta inexistente lo contesta el manejador de Laravel y con `APP_DEBUG=true` trae la
    traza (9 KB): es de todas las rutas de la API, no de las nuevas; mirarlo en E9.1 (`APP_DEBUG` de producción).

- [x] **E4.3. Prueba de no-escritura y documentación.** RF-50 — **S** — Toca API Laravel: **sí (aprobada con la tanda S el 2026-10-28)** (solo el `.md`)
  - Una pasada completa de lecturas (las tres rutas, todas las consultas del paciente de prueba) con `suma.sql` antes y
    después; búsqueda de escrituras en `ConsultasService.php`, `ConsultasReglas.php`, `MapaClinica.php` y
    `ConsultaController.php`; revisión del registro de accesos de Apache (`C:\xampp\apache\logs\access.log`): ningún
    pedido a `/api/salud360/consultas` ni a `.../historia` con método distinto de `GET`.
  - `md/API_SALUD360_CLINICA.md`: sección "Consultas (lectura)" (rutas, cuerpos, códigos, mapa, `curl` de humo sin
    credenciales), tabla "Código" y "Lo que viene" actualizadas; sin tokens, contraseñas ni nombres de personas.
  - Hecho cuando: `suma.sql` antes = después; cero escrituras en el grep y cero métodos distintos de `GET`; la copia
    servida es igual al clon en los archivos de la tanda (comparación de contenido, sin `.env` ni `vendor/`); phpunit de
    E3.2, `PerfilesTest`, `PacienteVinculoReglasTest` y `EnfermedadesReglasTest` en verde; `limpiar.sql` aplicado y
    `COUNT(*)` del paciente de prueba en 0 (o se deja para E8.1, anotándolo).
  - Hecho (2026-10-29): hclinica `3948d3c` (rama `salud360-api`, sin push). Solo cambia `md/API_SALUD360_CLINICA.md` (sin código): sección
    "Consultas (solo lectura)" (tres rutas, parámetros, cuerpos del fixture con nombres inventados, errores, `propia`/`omitidas`/`no_leidas`/`hay_mas`,
    fechas sin dato, mapa de laboratorio, qué no devuelve, «la lectura no escribe», curl de humo sin credenciales), tabla "Código", "Tests" y
    "Lo que viene" (sale solo la lectura; la escritura sigue pendiente). Sin tokens, contraseñas ni nombres reales (comprobado).
    **(a) Grep estático** (con `\b`, sin distinguir mayúsculas) sobre `ConsultaController`, `ConsultasService`, `ConsultasReglas`, `MapaClinica` y las
    66 líneas nuevas de `Salud360Controller` (`idWebValido`, `pacienteVisible`, `consultaNoEncontrada`, `consultaVisible`, `formatearConsulta`): `\b(insert|update|delete|truncate|replace)\b`
    da **1** coincidencia, justificada: la palabra `replace` en el comentario de `MapaClinica.php:12` (la regla de ids de la app en Kotlin, `...replace(Regex(...))`; no es
    código); el resto da 0: `save(`, `create(`, `firstOrCreate`, `updateOrCreate`, `Schema::`, `DB::statement`, `DB::raw`, `inicializarCampos`, `medico_infos`,
    `paciente_id_tobb`, `asegurarColumna`, `touch(`, `fn (`, y también `->save|update|delete|insert|create(`, `DB::insert|update|delete|unprepared`, transacciones, locks y DDL.
    `updated_at` no coincide con `\b`. `DB::` en el servicio: 5 `table` y 1 `select` (de `information_schema`); en los ayudantes: 3 `table`; todo lectura.
    **(b) Dinámico** con el general log de MariaDB 10.1 (`log_output='TABLE'`, `general_log='ON'` solo entre 11:52:56 y 11:53:57; token del médico de prueba vigente):
    37 pedidos `GET` con curl (1 sin token, 36 con token; 25 `200`, 6 `404`, 3 `403`, 2 `422`, 1 `401`): lista del fixture (por defecto, `limite=2&desde=1`, `0`, `5000`,
    sin `paciente_id`, fuera de cartera, inexistente), las consultas a-h del fixture, inexistente e inválida, historial del fixture, fuera de cartera e inexistente, y 5 pacientes
    y 6 consultas más de la copia local (de la cartera del médico de prueba; solo ids, sin mirar cuerpos: 1 con 25 consultas, 1 sin consultas, abiertas, telemedicina, de otro médico).
    Log: 1984 filas. `hc_clinica` (36 conexiones, 1 por pedido con token): 608 sentencias = **535 `SELECT`**, 72 `SET` (los dos de cada conexión de Laravel: `set names` y
    `set session sql_mode`; de sesión, no escriben) y **1 `INSERT`** en `salud360_token_cache` (lo hace el middleware con la caché vacía, en el primer pedido con token);
    **0** `UPDATE`, `DELETE`, `REPLACE`, `TRUNCATE` ni DDL; `Prepare` = `Execute` en todos los tipos. De los `SELECT`, 72 son las comprobaciones de existencia de las
    tablas del middleware (`information_schema`, 2 por conexión), 36 la búsqueda del token en la caché y 10 el aviso `no_leidas`. Tablas tocadas en `hc_clinica` (34 más
    `information_schema.tables`): `alimentacions`, `antecedentes_familiares`, `antecedentes_personales`, `aux_pendientes`, `biopsia_hepaticas`, `c_p_s`, `catarses`, `conductas`,
    `consulta_fotos`, `consulta_pdfs`, `consultas`, `datos_objetivos`, `datos_subjetivos`, `ecografia_abdominals`, `estudios_cardiologicos`, `examen_fisicos`,
    `examenes_complementarios`, `examenes_complementarios_fotos`, `habitos`, `interconsultas`, `laboratorio_general2s`, `laboratorio_generals`, `medico_licencias`,
    `medico_pacientes`, `motivo_consultas`, `notas`, `pacientes`, `pesos`, `salud360_token_cache`, `serologias_hepatitis`, `somnias`, `tc_rmn_colangiormns`, `users` y
    `videoendoscopia_digestiva_altas`; la única escrita es `salud360_token_cache`. 0 menciones de `medico_infos` y de `paciente_id_tobb`; `salud360_vinculos_pendientes` solo en
    36 comprobaciones de existencia. Además, por la caché vacía, el middleware llamó una vez a `auth/perfil` de turnos y **en `turnosonlinebb`** hubo 12 `SELECT`, 2 `SET` y **1 `UPDATE`
    de `salud360_tokens.ultimo_uso_en`** (lo hace turnos al validar la sesión; no es código de esta tarea ni toca datos clínicos). Restaurado: `general_log` OFF, `log_output` FILE,
    `general_log_file` original, `mysql.general_log` en 0 filas (estaba en 0); el volcado crudo del log se borró. **(c)** `CHECKSUM TABLE` y conteos de las 68 tablas y `suma.sql`:
    iguales antes y después (dos tomas de "antes" iguales entre sí; la única diferencia con la caché llena fue `salud360_token_cache`, 1 fila; vaciada, iguales; repetido al final,
    después de 8 `GET` más para sacar los ejemplos del `.md`). **(d)** Apache: 86 pedidos a `consultas`/`pacientes/{id}/historia` de `HClinica-salud360` en todo el log (82 `GET` —45 `200`,
    14 `404`, 11 `422`, 7 `403`, 5 `401`— y 4 no `GET`); los 4 no `GET` son los `405` de las 11:45:44-48 de E4.2, descontados: **0** métodos distintos de `GET` de verdad. (El mismo nombre de ruta
    en `HCDPediatria-salud360` es de la copia de pediatría y no se cuenta. En esa ventana la app abierta hizo 3 `POST sync/push` a turnos: `404` conocido, no a clínica.)
    phpunit en la servida: `MapaClinicaTest` 94, `ConsultasReglasTest` 255, `EnfermedadesReglasTest` 51, `PacienteVinculoReglasTest` 15, `PerfilesTest` 19 y `tests/Unit` entera 435 en verde;
    `php -l` limpio en los 8 archivos de la tanda; servida igual al clon (comparación de contenido) en los 8 archivos y en el `.md`; `route:list` muestra las 3 rutas `GET|HEAD` con `api,salud360`.
    **`limpiar.sql` NO se aplicó**: el fixture (pacientes 99000100 y 99000101) sigue cargado para E8.1; `COUNT(*)` del paciente de prueba ≠ 0 a propósito. `salud360_token_cache` vacía.
  - Avisos y decisiones de E4.1/E4.2 que siguen abiertos para la revisión del usuario (nada nuevo; ver las notas de esas tareas): (1) **fecha vacía `2000-01-01`**: la web la guarda en laboratorio
    y peso sin fecha; esas cargas no se mandan y se cuentan en `historia.omitidas.laboratorio_sin_fecha` (`fechaDeCarga`; `fechaUtil` no cambia). (2) `omitidas` de la lista es `{foto, otro,
    sin_fecha}`, **sin `baja`** (Q11), aunque el texto del pedido de E4.1 la nombraba. (3) **No hay `edad_meses`** (el plan la tenía): en clínica `consultas.edad_paciente` vale 0 en todas las
    filas reales; `edad_mostrar` sí va. (4) **`antecedentes_familiares_perfil4` no está mapeada**: la web tiene el modelo `AntecedentesFamiliaresPerfil4` (`consulta_id`, `paciente_id`, `nota`,
    `activo`; `MedicoController::guardarAntecedentesFamiliaresPerfil4`) y la migración figura en `migrations`, pero en la base local no existe ninguna tabla con ese nombre; la L lee solo
    `antecedentes_familiares`. Hay que ver en E9.1 si existe en producción y si el perfil 4 (lo arma el usuario) la necesita. (5) **Los ids de estudios se repiten entre tablas** (veda, biopsia y
    tc_rmn comparten 1797 en el fixture): E6.2 arma el id local con el tipo. (6) **`APP_DEBUG=true`** hace que el `405` (y cualquier ruta inexistente) devuelva la traza de Laravel, también acá:
    mirarlo en E9.1; las tres rutas dan `500 consultas` sin traza aunque esté encendido. (7) **No se probó `403 sin_permiso` con un token de administrador** (no hay token de admin de clínica en
    `respaldos`): las tres rutas lo cortan con `soloMedico`, como `PacienteController` y `EnfermedadController`, que sí se probaron así. Otras decisiones chicas ya anotadas en E4.1/E4.2 y
    cambiables: `listar` recibe `$medicoId` para `propia`; `limite`/`desde` inválidos usan los valores por defecto (no `422`); en `consultas/{id}` el paciente ajeno da `403` antes que `404`;
    `500` con código `consultas` y mensaje fijo; antecedentes familiares con `flag` y sin casillas en 0; todo filtra consultas `activo` 1 o 2 (una carga de una consulta anulada que la web muestra, acá
    no sale).

- [x] **E4.4. Ajustes de la validación de la tanda S (observaciones del reviewer y decisiones D1 a D5 del 2026-10-29).** RF-39, RF-41, RF-43, RF-50 — **M** — Toca API Laravel: **sí (aprobada con la tanda S el 2026-10-28; esta tarea la aprobó el usuario el 2026-10-29)** (sin cambios de esquema ni escritura)
  - Decisiones del usuario (2026-10-29): **D1** las cargas de laboratorio y de peso sin fecha se omiten con aviso (el servidor ya las cuenta en
    `omitidas.laboratorio_sin_fecha`; el aviso visible es de la app, tanda A); **D2** `antecedentes_familiares_perfil4` se agrega ya como tabla
    OPCIONAL; **D3** una consulta ajena sigue dando `403 fuera_de_cartera` y se corrige el texto del `.md`; **D4** el IMC `"0"` se muestra como en la
    web y `"0.00"` es vacío; **D5** los diagnósticos nunca se informan (no hay tabla).
  - Puntos: (1) **H1** examen físico: `"0.00"` es vacío en peso, talla, TA e IMC (regla de `examen_fisico.blade.php`), `"0"` del IMC es dato; test puro primero;
    revisar otras columnas numéricas que la web trate igual. (2) **H2/H3/H4/H10** `md/API_SALUD360_CLINICA.md`: comportamiento exacto de `limite` y `desde`
    (negativos, cero, texto, arreglos); lo que sí escribe el camino del middleware (`activo = 0` de la licencia, `salud360_vinculos_pendientes`, `Schema::create`
    de sus dos tablas, `ultimo_uso_en` en turnos) sin tocar RF-50; el 403/404 de `consultas/{id}`; la cifra del peor caso medida de nuevo. (3) **H7**
    `ConsultaController`: `\Throwable` y el `return $this->ok(...)` dentro del `try`; un `\Error` y un JSON no codificable dan el `500 consultas` fijo y sin
    traza con `APP_DEBUG=true`. (4) **H6** `ultimaActiva` filtra también por `paciente_id`. (5) **H5** fixture ampliado (segunda fila de antecedentes
    familiares, segunda de `aux_pendientes` y una `activo = 2`, PDF de otro paciente) con sus scripts, y repetir las 4 mutaciones que sobrevivieron (S01, S02,
    S06, S09) sobre una copia. (6) **D2** `historia.antecedentes_familiares_perfil4` (`{nota}` o `null`), tabla opcional detectada en `information_schema`
    con función pura para decidir si se consulta; probada en una base descartable creada desde la migración. (7) **Cabecera**: `created_at` completo
    (`AAAA-MM-DD HH:MM:SS`) además de `fecha`, en la lista y en `leer`, para que la app ordene por `(created_at, id)`.
  - NO se hace: índice en `medico_pacientes` ni ningún cambio de esquema (nota para E9.1), cambios a `PacienteController`/`EnfermedadController`, escritura de
    ninguna clase, 404 en lugar de 403 (D3).
  - Hecho cuando: todos los puntos hechos; los tests nuevos en rojo antes del código y `tests/Unit` entero en verde; `php -l` limpio; grep estático con `\b`
    de escrituras sin coincidencias injustificadas; el fixture ampliado detecta S01, S02, S06 y S09; conteos y `CHECKSUM TABLE` de las 68 tablas y `suma.sql`
    iguales antes y después de las lecturas (con el fixture ampliado cargado, salvo `salud360_token_cache`); `historia` y `leer` del fixture sin cambios
    inesperados respecto de E4.1/E4.3 más lo nuevo; servida igual al clon en los archivos de la tarea; la base descartable borrada.
  - Hecho (2026-10-29): hclinica `0d0d394` (rama `salud360-api`, sin push, un solo commit con el `.md`). Cambian `ConsultasService.php`, `ConsultasReglas.php`,
    `MapaClinica.php`, `ConsultaController.php`, `md/API_SALUD360_CLINICA.md` y los dos tests; sin cambios de esquema ni escritura. **Rojo previo:** `ConsultasReglasTest`
    32 errores y 2 fallas (de 287; métodos inexistentes y la cabecera sin `created_at`) y `MapaClinicaTest` 2 errores (de 96). **Verde:** `ConsultasReglasTest` 287 (antes 255),
    `MapaClinicaTest` 96 (94), `EnfermedadesReglasTest` 51, `PacienteVinculoReglasTest` 15, `PerfilesTest` 19 y `tests/Unit` entera **469** (435), 1077 aserciones. `php -l` limpio en
    los 6 archivos PHP; grep estático con `\b` (insert/update/delete/truncate/replace/alter/drop, `save(`, `create(`, `Schema::`, `DB::statement|raw|unprepared|insert|update|delete`,
    `touch(`, transacciones y locks, `medico_infos`, `paciente_id_tobb`, `fn (`, propiedades tipadas) sobre los 4 archivos del servidor: 1 coincidencia, justificada (`replace` en
    el comentario de `MapaClinica.php:12`); servida igual al clon (comparación de contenido) en los 7.
    **(1) H1, la regla exacta:** la pantalla de la web (`examen_fisico.blade.php`, `ocultarSecciones`) oculta peso, talla, TA e IMC cuando el valor es `""` o exactamente `"0.00"`
    (`localeCompare`); nada más (`examenFisicoCompleto` del resumen usa el mismo par). El servidor: `"0.00"` y vacío/`null`/solo espacios → no se manda; `"0"` (IMC "0", D4) → `"0"`;
    **`"0.0"` → se manda `"0.0"`** (la web lo muestra), igual que `"0.000"`, `"0,00"` y `" 0.00"` (no es el valor exacto); la nota no tiene la regla. Es solo del examen físico:
    el único `0.00` de toda la web es este (peso de la tabla de pesos y laboratorio no lo tienen), así que nada más cambió. En la copia local no hay ningún `"0.00"` (4 IMC `"0"`).
    **(2) Documentación:** `limite=-5` vale **1** (no el defecto: es un entero y se acota; `0` y negativos → 1, más de 200 → 200, no entero/vacío/`abc`/`1.5`/arreglo → 50; `desde`
    negativo o no entero → 0, enorme se satura en 999999999) y comprobado con curl; H3: el middleware escribe además `medico_licencias.activo = 0` (licencia vencida), `salud360_vinculos_pendientes`
    (médico no vinculado), hace `Schema::create` de sus dos tablas la primera vez (privilegio `CREATE`), el `UPDATE` (no solo `INSERT`) de la fila de la caché y turnos pone
    `ultimo_uso_en`; las dos escrituras de licencia y vínculo ocurren solo en pedidos que terminan en `403`; H4: `consultas/{id}` da `403 fuera_de_cartera` si existe y es de un paciente ajeno y
    `404 consulta_no_encontrada` si no existe, dicho tal cual (tabla de errores y bullet propio); H10: la cifra de 309 KB **no se reprodujo exacta** (no estaban los parámetros): el peor caso
    inventado (300 cargas de 52 analitos + 300 motivos, base descartable) da ≈290 KB con valores de 4 caracteres y motivos de 40, ≈312 KB con 5 y 60; mediciones reales sobre 1017 pacientes
    y 1921 consultas legibles: historia máx 6226 B (p95 2868), lista máx 6438 B (con `created_at`; era ≈5,5 KB), una consulta máx 3699 B.
    **(3) H7:** `catch (\Throwable)` y `return $this->ok(...)` dentro del `try` en las tres acciones, `falloDeLectura(\Throwable)`. Prueba (`php -r` con un servicio falso que lanza, bootstrap de la servida,
    `APP_DEBUG=true`, 15 casos = 5 × 3 rutas): con el controlador de HEAD **9 casos escapan** (`TypeError`, `Error` e `InvalidArgumentException: Malformed UTF-8` salen sin atrapar); con el nuevo
    **15 de 15**: los 12 de falla dan `500 consultas`, mensaje fijo, sin `trace`, `.php`, SQL ni el texto de la excepción, y los 3 «todo bien» dan 200. Las entradas que esa prueba dejó en
    `storage/logs` (marca `E44H7`, y las de mis scripts que fallaron) se borraron truncando al tamaño de antes: los dos logs quedaron byte a byte iguales a su copia previa.
    **(4) H6:** `ultimaActiva` filtra también por `paciente_id` (todas las tablas por consulta, incluidos alimentación, serologías, Child-Pugh y examen físico). Sobre la base real local
    (solo conteos): 0 filas con `paciente_id` distinto del de su consulta en las 12 tablas por consulta (5489 filas, con el fixture) y la huella (crc32) de `listar`/`historia` de los 1017 pacientes y de `leer` de las
    1921 consultas es idéntica entre el código de HEAD y el nuevo (3958 salidas, 0 distintas, sin las novedades de esta tarea). En una base descartable (`e44_scratch`) con una fila de otro
    paciente colgada de la misma consulta en las 12 tablas: el servicio nuevo lee la del paciente (13 controles de H6 + 1 de consulta consistente, todos OK) y la copia mutada sin el filtro (S10) falla 12 de ellos.
    **(5) H5, fixture ampliado** (fuera del repo, `%TEMP%\opencode\lectura` y `e31`; `fixture.sql`, `contar.sql` 32 OK, `ids.sql`, `limpiar.sql` y `suma.sql` con notas, `ids-y-limpieza.txt`): segunda fila activa de
    antecedentes familiares (en (c)), segunda de `aux_pendientes` activa y una `activo = 2`, `conductas` y `examen_fisicos` de (c) con una fila `activo = 2` de id mayor (el examen de (c) trae `0.00`,
    `0.0`, vacío y `0`), un PDF activo y una nota del paciente 2 colgados de (a). Carga/limpieza probadas en 3 vueltas (modo por defecto, `STRICT_TRANS_TABLES`, `STRICT_ALL_TABLES…`): las 68 sumas,
    los 68 conteos y `suma.sql` **idénticos** a los de justo antes de cargar (el «antes» = E3.1 salvo `salud360_token_cache`). Con el fixture cargado, `verificar-servicio.php` compara el servicio con un
    oráculo SQL independiente (PDO, sin `MapaClinica` ni `ConsultasReglas`). **Mutaciones sobre una COPIA (nunca el clon ni la servida):** S01 (antecedentes con `asc`) detectada (5 controles), S02 (pendientes
    con `asc`) detectada (1), S06 (`ultimaActiva` con `activo >= 1`) detectada (2), S09 (`noLeidas` sin `paciente_id`) detectada (1); y de más: S10 (`ultimaActiva` sin `paciente_id`) detectada (1), S11 (examen
    físico sin la regla del `0.00`) detectada (1) y S12 (`created_at` solo con el día) detectada (4); el código real: 0 fallas. Con el código de HEAD el oráculo marca H6 y H1 como fallas.
    Cambios esperados del fixture en las salidas (comparadas contra las de E4.1/E4.3, con los ids enmascarados): `historia.antecedentes_familiares` pasa a la fila de (c) (`acv` + nota «(c)»),
    `historia.pendientes` a «pendiente 2 (c)», `leer(c)` suma `conductas` y `examen_fisico {talla "0.0", imc "0"}`; lo demás (lista, `omitidas`, resto de la historia, `leer` de a, b, f, h) sin cambios.
    **(6) D2:** `historia.antecedentes_familiares_perfil4` = `{nota}` o `null`. La web (`MedicoController::guardarAntecedentesFamiliaresPerfil4`/`cargar…`, `antecedentes_familiares_perfil4.blade.php`)
    guarda solo `nota` (varchar, `''` si no hay), una fila por consulta, y muestra la última fila `activo = 1` del paciente. Regla igual a la de antecedentes familiares (`delPaciente`: `activo = 1` y consulta
    `activo` 1 o 2, `id` mayor, sin buscar una anterior si la última está vacía); la decisión «consultar o no» es la función pura `ConsultasReglas::consultarPerfil4($existentes)` sobre
    `information_schema` (una consulta más; sin `Schema::`), y la forma pura `antecedentesFamiliaresPerfil4($fila)`. `historia` hace ahora 14 `SELECT` (15 con la tabla), no 13. **`hc_clinica` local (sin la tabla):**
    `historia` devuelve lo mismo de antes con `antecedentes_familiares_perfil4: null` (huellas iguales; 0 errores). **Base descartable** `e44_scratch` (estructura copiada con `CREATE TABLE … LIKE`; la tabla del
    perfil 4 creada ejecutando la **migración real** de hclinica) con datos inventados: 13 controles OK (última nota entre consulta abierta y cerrada, `activo = 2` y consulta anulada no cuentan, última fila vacía o
    de espacios → `null` sin buscar la vieja, un `"0"` es un texto, paciente sin filas → `null`, convive con las casillas); sin la tabla: clave presente y `null`, sin excepción. La base se borró sola y se
    comprobó que no existe (ni `e44_peor`, la del peor caso).
    **(7) Cabecera:** `created_at` (`AAAA-MM-DD HH:MM:SS`, texto, siempre presente) después de `fecha` en la lista y en `consultas/{id}`; test puro rojo→verde y documentado; comprobado con curl.
    **Pasada final** (33 `GET` con curl, espaciados ~1,3 s, fixture ampliado cargado): sin token 401; lista 200 con 4 consultas (c, b, a, f) y `created_at`; `limite=-5`/`0`/`1` → 1, `5000`/`abc`/`[]`/vacío → 4,
    `desde=-5`/`abc` → 0; `consultas/{a,b,c,f}` 200 (`a` con `no_leidas []` aunque tenga un PDF de otro paciente; `f` con `documentos`), `d`, `e`, `g`, inexistente y `0` 404, `h` 403; historia 200; fuera de cartera 403;
    inexistente 404; ids inválidos 422. `CHECKSUM TABLE` y conteos de las 68 tablas y `suma.sql` iguales antes y después, salvo `salud360_token_cache` (el middleware actualizó su fila); vaciada al final (0 filas).
    Apache: 35 `GET` a consultas/historia desde las 15:00 y 0 métodos distintos de `GET`. El fixture ampliado **sigue cargado** para E8.1 (limpiar con `limpiar.cmd`).
  - Para la tanda A (contrato JSON que cambia, todo retrocompatible): (1) `GET pacientes/{id}/historia` suma **`antecedentes_familiares_perfil4`**: `{"nota": "…"}` o `null` (siempre presente en esta versión; un
    servidor anterior no lo trae: el DTO tiene que tolerar la ausencia, p. ej. nulo por defecto). Es una nota suelta distinta de las casillas de `antecedentes_familiares`: decidir en E6.2/E7.2 dónde se muestra
    (propuesta: en la misma sección «Antecedentes familiares», como nota aparte, y solo si no es `null`). (2) La cabecera (`consultas` y `consulta` de `consultas/{id}`) suma **`created_at`** (`"AAAA-MM-DD HH:MM:SS"`):
    ordenar por `(created_at, id)` y seguir usando `fecha` (el día) para mostrar; también con valor por defecto en el DTO. (3) `examen_fisico` ya no trae `peso`/`talla`/`tension_arterial`/`imc` en `"0.00"`; sigue
    trayendo `"0"` y `"0.0"` si están guardados (no hay que filtrar nada en la app). (4) `limite` y `desde`: ver el `.md` (negativos se acotan, no vuelven al defecto). (5) `consultas/{id}` ajena: `403 fuera_de_cartera`
    si existe, `404` si no existe.
  - Dudas y avisos para el usuario: (a) **RF-50 de la spec** dice «lo único que se registra es la sesión»; el camino de autenticación escribe también `medico_licencias.activo = 0`, `salud360_vinculos_pendientes` y
    hace `Schema::create` de sus dos tablas. No toca datos clínicos ni de pacientes ni `medico_infos` (la cláusula principal se cumple) y las dos primeras ocurren en pedidos que terminan en `403`, pero la frase final
    es inexacta: **no se editó la spec**; conviene decidir si se afloja a «lo único que se registra es la sesión de la app y, si corresponde, el aviso de licencia vencida y la lista de médicos sin vincular». (b) `pacienteVisible`
    y `consultaVisible` se ejecutan **antes** del `try` del controlador (fuera de las líneas que pidió la tarea): una excepción de la base ahí todavía saldría con el `500` de Laravel y su traza si `APP_DEBUG=true` (con la base
    caída el middleware ya falla antes); no se tocó, es una línea más si se quiere. **(Resuelto el 2026-10-29, hclinica `64849c9`: ambas llamadas dentro del `try`; con el controlador anterior las 9 pruebas de falla —6 con servicio que lanza en `pacienteVisible`/`consultaVisible` y 3 con la base caída— escapaban con `APP_DEBUG=true`, con el nuevo dan `500 consultas` sin traza; los 401/403/404/422 por curl iguales; `tests/Unit` 469 en verde.)** (c) La cifra de 309 KB no se pudo reproducir exacta; queda «≈300 KB según el largo de los textos». (d) Dónde mostrar la nota del perfil 4
    (arriba). (e) Índice en `medico_pacientes` y otros cambios de esquema: **no se hicieron** (nota para E9.1); el perfil 4 de producción: confirmar en E9.1 si la tabla existe.

**Tanda S (E3 y E4): implementada y con ajustes (E4.4); falta validación final del reviewer solo si cambió algo mayor** (E3.1 a E3.3 y E4.1 a E4.4 hechas; E9, producción, sin aprobación). Al terminar la tanda: validación del reviewer y MEMORY.md.
**Aprobación de la E5 recién después** (la E5 no depende del servidor y puede aprobarse en paralelo).

## Tanda E5 — App I: candados contra el envío (antes de registrar nada)

Esta tanda **no registra** ningún backend: solo agrega los candados y los prueba. Es una de las condiciones de E6.5 (la otra es E7.3, D6).

- [x] **E5.1. `HcBackend.soloLectura` y candado en `HcApiSync`.** RF-47, RF-48 — **M** — Toca API Laravel: no
  - Tests primero: `core/data/src/jvmTest/.../repos/HcApiSyncSoloLecturaTest.kt` (SQLite en archivo temporal, como
    `PacientesHcRepositoryTest`; backend falso que cuenta cada llamada y `scope` inyectado): con consultas de `clinica`
    sucias, con secciones, examen, registro, archivo y antecedente `activo = 0` también sucios, un backend con
    `soloLectura = true` no recibe **ninguna** llamada en `empujarTodo`, `empujarYa` ni `marcarSucia(…, esperaMs = 0)`, ni
    programa reintentos, y todas las filas siguen con `dirty = 1`; un backend con `soloLectura = false` de otra
    especialidad (pediatría falsa) sigue recibiendo su envío.
  - Código: `core/data/.../repos/HcBackend.kt` (`val soloLectura: Boolean get() = false`) y `core/data/.../sync/HcApiSync.kt`
    (`drenar`, `consultasDeAntecedentesPendientes`, `marcarSucia`, `empujarYa`, `empujarTodo` cortan por backend de solo lectura).
    El contador `pendientes` no cambia (Q16-A).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*HcApiSyncSoloLecturaTest*'` en verde (antes, en rojo) y
    `.\gradlew :core:data:jvmTest` completo en verde.
  - **Hecho (2026-10-29).** `HcBackend.soloLectura` (`get() = false`, con KDoc que obliga a declararlo) y el candado en `HcApiSync`, en **cuatro
    puntos** (el motor, no el backend): `marcarSucia` y `empujarYa` salen sin programar nada si la consulta es de un backend de solo lectura
    (`esDeSoloLectura`: una lectura de la consulta, nada más); `drenar` —por donde pasan `empujarTodo`, `marcarSucia`, `empujarYa` y los
    reintentos de 15/60/300 s— sale antes de leer lo pendiente (`backends[...]?.takeUnless { it.soloLectura } ?: return`, el mismo lugar del
    `?: return` de siempre, antes del `try`: no limpia, no marca, no recuenta); `consultasDeAntecedentesPendientes` salta esos backends (igual que
    hoy salta una especialidad sin backend, así **`pendientes` no cambia**: Q16-A). `empujarTodo` no necesitó código propio: junta ids y cada
    uno cae en `drenar`. Pediatría (`soloLectura = false`) sigue por el mismo camino; los tests existentes pasan sin tocarlos (640 en total).
    **Tests** (`jvmTest`, `repos/`, SQLite en archivo temporal, backend falso que anota cada llamada, motor en `Dispatchers.Unconfined`):
    `HcApiSyncSoloLecturaTest` (10): con clínica de solo lectura y sucios consulta abierta y cerrada, sección, examen, registro, dos adjuntos (subir y
    dar de baja), antecedente `activo = 1` y otro `activo = 0` sin consulta, laboratorio y pendiente, `empujarTodo`, `empujarYa` y `marcarSucia`
    (con `esperaMs = 0` y con la espera de siempre) dejan **cero llamadas** (ni `asegurarConsulta`, ni `pacientes/resolver`, ni `enviar*`, ni
    `subir/borrarArchivo`), **ningún `Job` activo** (ni reintento ni envío esperando) y las filas sucias **idénticas columna por columna** (`id`,
    `dirty`, `updated_at`, `remoto_id`…); antecedentes y registros sucios sin consulta sucia tampoco salen ni se cuentan; el backend falso de
    clínica lanza si lo llaman, así que una fuga dejaría además un reintento programado. Controles: pediatría falsa recibe las 8 llamadas y queda
    limpia; con `marcarSucia`/`empujarYa` también; un envío que falla deja **un** reintento y una `marcarSucia` normal deja **un** envío esperando
    (prueba de que el detector de `Job` ve lo que tiene que ver); mezcla pediatría + clínica en el mismo motor: pediatría envía, clínica no.
    `HcBackendsSoloLecturaTest` (1, Koin real con URLs de pediatría y clínica): cada backend que `HcBackends` registra tiene decidido su valor
    (`pediatria` → `false`, `clinica` → `true`) y **cualquier otro código falla** hasta que se lo agregue ahí: es la defensa contra «un backend
    nuevo envía por descuido». Hoy `clinica` no está registrado (E6.5).
    **Rojo previo** (con la propiedad ya declarada y sin candado): 6 de los 11 tests nuevos fallaban —3 de solo lectura (`empujarTodo`, `empujarYa`, `marcarSucia` sin espera) con
    `[asegurarConsulta, pacientes/resolver, asegurarConsulta]` en vez de `[]`, el de `marcarSucia` con espera con un envío esperando (1 `Job` en vez de 0),
    el de antecedentes con `[asegurarConsulta]`, y la mezcla por tiempo agotado (el reintento de clínica)—; los controles de pediatría, el de la consulta inexistente y el de `HcBackends` pasaban.
    **Mutación:** quitando solo el candado de `drenar` (quedando los de `marcarSucia`/`empujarYa`) fallan `empujarTodo` y la mezcla.
    **Verde:** `.\gradlew :core:data:jvmTest --rerun` completo (640 tests, 0 fallas, 0 salteados), `:features:hc:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs -Pentorno=dev` compilan.
    **Decisión (default de `soloLectura`):** `false`, como pide el texto de E5.1: es lo que mantiene a pediatría enviando sin tocarla. El riesgo
    —un backend de solo lectura que olvide declararlo enviaría— se cubre con `HcBackendsSoloLecturaTest` (y con el KDoc de la propiedad). La
    alternativa más segura sería una propiedad **abstracta** (sin valor por defecto: el compilador obliga a declararla en cada backend); no se hizo
    porque cambia el diseño aprobado del plan y obligaría a los fakes de E6 a declararla; es una línea si se la quiere.
    **Call-sites de los métodos de envío del backend** (`git grep`, sin tests): `HcApiSync.kt` `asegurarConsulta`, `enviarSecciones`,
    `enviarRegistros`, `borrarArchivo`, `subirArchivo`, `enviarExamen`, `enviarEstado` —**todos dentro de `drenar`**, tras el candado— y
    `AdminRepository.kt:84` `guardarLicencia` (NO pasa por `HcApiSync`: lo cierra E5.3). `pacientes/resolver` solo se llega desde el envío por
    `asegurarConsulta` (`HcPediatriaBackend.resolverPaciente`, `PacientesHcRepository.vinculo`), que el candado corta; el otro llamador de
    `vinculo` es `EnfermedadesControlador` (acción del médico en la sección Enfermedades, spec 003, no un envío diferido). `bajarArchivo` (lectura)
    queda permitido. **`SyncEngine` no llama a ningún backend**: es otro canal, hacia `<turnos>/api/salud360/sync/push|pull` y `archivos` (rutas
    que no existen: 404 ignorado); sus adaptadores incluyen las tablas clínicas (también las de `clinica`), y **no se tocó** (fuera de E5.1;
    aviso para el usuario: es la deuda ya anotada en `AGENTS.md`, y si turnos crea esas rutas saldría todo lo `dirty` de la HC general).

- [x] **E5.2. El candado frente a una base migrada de hepatología (H2).** RF-47, RF-48 — **S** — Toca API Laravel: no
  - Test: partiendo de una base v8 con consultas de `hepatologia` sucias, antecedentes repetidos y secciones ocultas
    (la base de `MigracionHepatologiaTest`), migrada a v9, con un backend falso de solo lectura para `clinica`:
    `empujarTodo` y abrir "consulta" no envían nada; las filas quedan exactamente igual (comparación de `id`, `dirty`,
    `activo`, `updated_at` antes y después); el antecedente con `activo = 0` sigue con `activo = 0`.
  - Hecho cuando: el test (en rojo si se quita el candado) en verde; vale como regresión de H2 para la entrega E.
  - **Hecho (2026-10-29, salud360 `0e3c461`; sin cambios en código de producción).** `HcApiSyncBaseMigradaTest` (9, `jvmTest`, `repos/`): base SQLite en
    archivo temporal que parte de una v8 con datos inventados (pacientes `99000101` a `99000103`) y todo `dirty = 1` como en producción —consultas de
    `hepatologia` abiertas, cerradas y anulada, otra abierta ya en `clinica` del mismo paciente, secciones, examen, dos adjuntos (subir y dar de baja),
    registros, laboratorio, `paciente_extra` (uno que choca y se queda en `hepatologia`), preferencias, cuatro antecedentes perdedores del dedupe (tres
    de un paciente con consultas sucias y uno cuya única consulta está limpia), más una consulta de pediatría— que el driver migra a v9 con `8.sqm`
    (como `MigracionHepatologiaTest`). Un test comprueba la base de partida (v9, cero `hepatologia` en las cuatro tablas, los cuatro `activo = 0`, los
    `dirty` de antes intactos). Con un backend falso de solo lectura para `clinica` **que lanza si lo llaman** (una fuga dejaría un reintento):
    `empujarTodo`; el `init` de `ConsultaViewModel` para cada consulta (`tieneApi` + `traerConsultaRemota` + `empujarTodo`); `marcarSucia` (con y sin
    espera) y `empujarYa` de cada consulta, incluida la anulada, comprobando tras cada uno que no queda ningún `Job` programado; y todo junto, dos veces.
    En todos: **cero llamadas** (ni `asegurarConsulta`, ni `pacientes/resolver`, ni `enviar*`), y **todas las tablas de la base** (no solo las
    clínicas) idénticas columna por columna (`dirty`, `updated_at`, `remoto_id`, `activo`…) antes y después. **Contador (Q16-A):** `pendientes` = 7 con el
    candado y con un motor sin backend, y 7 después de todos los caminos (los antecedentes `activo = 0` no suman; `c-p3-cerrada` está limpia y no
    cuenta). **Pediatría** sobre la misma base, junto a la clínica de solo lectura: recibe sus 6 llamadas en orden, queda limpia y el resto de la base
    queda idéntico, con `pendientes` = 6. **SyncEngine** (sin cambiarlo): contra un `HttpServer` local que contesta 404, `sincronizar()` da `false` tras
    **un** `POST /sync/push` con las tablas `consultas`, `seccion_valores`, `examen_fisico`, `archivos`, `registros_clinicos`, `laboratorios`,
    `antecedentes` (con los cuatro `activo = false`), `paciente_extras` y `medico_preferencias`, y no limpia nada.
    **Rojo previo:** ninguno —el candado de E5.1 ya hacía lo correcto— así que se comprobó con mutaciones sobre `HcApiSync.kt` (revertidas con
    `git checkout`): (1) quitar el candado de `drenar` → fallan 5 de 9 (`empujarTodo`, `init` del ViewModel, todo junto, pediatría y el límite conocido);
    (2) `consultasDeAntecedentesPendientes` sin el salto de solo lectura → fallan el contador y pediatría (los dos miran `pendientes`); (3) `esDeSoloLectura` siempre
    `false` → falla `marcarSucia`/`empujarYa` (queda un envío esperando). **Verde:** `.\gradlew :core:data:jvmTest --rerun` completo (649 tests, 0 fallas,
    0 salteados). No se compiló `composeApp` (la tarea no toca código de producción).
    **Avisos.** (a) *Límite conocido, fijado en un test sin cambiarlo:* `archivo` no tiene HC y `archivosPendientesDeConsulta` toma los sucios sin consulta
    (`consulta_id` NULL) del paciente; el envío de pediatría de una consulta de un paciente que también tiene HC general recoge sus adjuntos por paciente
    (p. ej. el `familigrama`): si el archivo está en el dispositivo lo subiría a pediatría y, si no, lo marca limpio sin enviarlo. En la L no pierde nada
    (clínica no envía) pero **la entrega E debe resolverlo antes de enviar adjuntos de clínica** (filtrar por la HC de la consulta de origen). (b) "Abrir
    consulta" se probó con la secuencia del `init` de `ConsultaViewModel` y no con `HcRepository.abrirConsulta` (que retoma la abierta sin escribir hoy,
    pero E5.3 cambia su regla). (c) `SyncEngine` sigue fuera del candado: el test fija qué manda (deuda de AGENTS.md); si turnos crea esas rutas, ese test
    avisa. (d) El fake de backend está duplicado en este archivo y en `HcApiSyncSoloLecturaTest` (a propósito, para no tocar la prueba de E5.1).

- [x] **E5.3. `AdminRepository` y `HcRepository` ante un backend de solo lectura.** RF-46, RF-61 — **S** — Toca API Laravel: no
  - Tests primero: `AdminRepositoryLicenciasTest` (`guardarLicencia` de un médico solo de clínica devuelve `true` y no
    llama al backend de solo lectura; con pediatría sigue llamándola); `HcRepositoryTest` (`esSoloLectura`;
    `abrirConsulta` con una consulta de la web abierta del mismo médico crea una **nueva** local y no devuelve la de la
    web; con pediatría sigue reutilizando la abierta remota).
  - Código: `AdminRepository` (destinos sin backends de solo lectura), `HcRepository.esSoloLectura` y `abrirConsulta`,
    consulta SQL `consultaAbiertaLocal` en `HistoriaClinica.sq` (solo la consulta, **sin migración**).
  - Hecho cuando: ambos tests en verde (antes, en rojo) y `.\gradlew :core:data:jvmTest` completo en verde.
  - **Hecho (2026-10-29).** Sin dependencias, sin migración (la consulta SQL nueva no cambia tablas), sin tocar `DataModule` ni el servidor.
    **Código:** `AdminRepository.destinosDeLicencia` (= `backends` sin los de `soloLectura`) lo usan `guardarLicencia` y `sincronizarLicencias`;
    `HcRepository.esSoloLectura(especialidad)` (`backends[…]?.soloLectura == true`) y `abrirConsulta`, que con un backend de solo lectura usa la
    consulta `consultaAbiertaLocal` (`HistoriaClinica.sq`, junto a `consultaAbierta`, que queda para el resto); `hcgeneral/LecturaClinica.kt`
    (nuevo, mínimo: E6.2 lo completa) con `PREFIJO_ID_WEB = "hcc-"` y `Consulta.esDeLaWeb()`.
    **Criterio de «consulta de la web» (hoy):** `remoto_id` no vacío **o** id que empieza con `hcc-`. La consulta SQL aplica lo mismo
    (`trim(remoto_id) = ''` y `instr(id, :prefijoWeb) <> 1`, con el prefijo pasado como parámetro: una sola constante) y un test recorre 8 casos
    comparando la SQL con `esDeLaWeb()`. Solo vale frente a un backend de solo lectura: en pediatría una consulta creada en el dispositivo también
    tiene `remoto_id` tras enviarse, y ahí se sigue reutilizando la abierta como siempre (la rama `consultaAbierta` no cambió). Una especialidad sin
    backend registrado (la clínica hoy) tampoco cambia. Las locales de clínica (sin `remoto_id`, sin `hcc-`) se siguen retomando aunque haya una
    de la web más reciente; la de la web no se devuelve, no se escribe (fila idéntica, `dirty` y `updated_at` incluidos) ni se marca.
    **Tests** (`jvmTest`, `repos/`, SQLite en archivo temporal, backends falsos que fallan si algo intenta enviar): `AdminRepositoryLicenciasTest` (6):
    médico solo de clínica con backend de clínica de solo lectura registrado → `guardarLicencia` `true`, cero llamadas al backend, licencia local
    guardada; mismo resultado y misma fila (`dirty` incluido) que **sin** el backend registrado (la vara es lo que hace hoy el código); pediatría
    recibe la licencia, queda limpia (`dirty = 0`) y si la rechaza devuelve `false` y queda sucia; médico de pediatría + clínica → manda la
    respuesta de pediatría y clínica no cuenta; `sincronizarLicencias` no le pregunta a la de solo lectura. `HcRepositoryTest` (16): `esSoloLectura`
    (clínica `true`, pediatría y sin backend `false`); `tieneApi`/`especialidadesConApi` cuentan a la de solo lectura (la historia y la ficha siguen
    trayendo la lista); `traerConsultasRemotas`/`traerConsultaRemota` solo leen; `abrirConsulta` con una abierta de la web (con `remoto_id`; con
    `remoto_id` y sin prefijo; con prefijo y sin `remoto_id`) crea una nueva local y deja la de la web idéntica; retoma la local aunque la web sea
    más reciente; otro tipo crea una nueva; sin ninguna crea una local sucia; sigue sin retomar la de otro médico; pediatría reutiliza la abierta
    remota y la local ya enviada; sin backend y «clínica sin backend registrado» como hoy; `esDeLaWeb` y su coincidencia con la SQL.
    **Rojo previo** (con `esSoloLectura` y `esDeLaWeb` como stubs en `false` para que compile): 10 de 22 fallaban —los 4 de licencias que pasan por un
    backend de clínica (`guardarLicencia` daba `false`: `expected:<true> but was:<false>`; pediatría + clínica llamaba también a clínica;
    `sincronizarLicencias` la llamaba) y 6 de `HcRepositoryTest` (`abrirConsulta` devolvía `hcc-c77` / `x-1` / `hcc-c78` en vez de una nueva y no
    retomaba `local-1`; `esSoloLectura` y `esDeLaWeb`)—. **Verde:** los 22; `.\gradlew :core:data:jvmTest --rerun` completo (671 tests, 0 fallas,
    0 salteados; los 649 anteriores, pediatría incluida, **sin ajustar ninguno**); `:features:hc:compileKotlinJvm`, `:features:admin:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs -Pentorno=dev` compilan.
    **Revisión de los caminos de lectura (sin cambios):** `traerConsultasRemotas` solo lo llaman `PacienteDetalleViewModel` y `PacientesViewModel`
    (ficha: lectura de la lista; el plan no los cambia) y `HistoriaClinicaViewModel` (init, tras `tieneApi`); `traerConsultaRemota` solo el `init`
    de `ConsultaViewModel` (y solo si hay `remoto_id`). Ninguno pasa por el autoguardado. `tieneApi("clinica")` será `true` al registrar el backend,
    como pide el plan; **no se registró nada** (E6.5).
    **Avisos para E6/E7:** (a) `HcRepository.cerrarConsulta`, `reabrirConsulta`, `anularConsulta`, `guardar*` **no** distinguen consultas de la web
    (nunca lo pidió E5.3; el candado de E5.1 impide que salgan, pero localmente se podrían editar): lo cierra E7.3 en el `ConsultaViewModel`.
    (b) La pantalla de historia filtra «Consulta en curso» por `estado == ABIERTA` (E7.2 usa `agruparConsultas`, E7.1): hasta E7.2 una abierta de
    la web se vería como continuable y editable. Solo es alcanzable con el backend registrado, y E6.5 (registro) iba **antes** que E7.1 a E7.3 en el
    orden de las tandas: **resuelto el 2026-10-29 por decisión del usuario (D6): E6.5 pasó a después de E7.3**. (c) Con el backend registrado, `PacienteDetalleViewModel` pedirá 2 pedidos por ficha abierta contra
    clínica (throttle 60/min por IP; `429` ya contemplado en E6.5). (d) `sincronizarLicencias` también salta los backends de solo lectura (decisión
    mía, conservadora: así registrar clínica no cambia nada; hoy `traerLicencias` de la interfaz es vacío de todos modos).

- [x] **E5.4. Pediatría sigue enviando.** RF-60 — **S** — Toca API Laravel: no (usa la copia servida de pediatría)
  - `HcPediatriaE2ETest` y `HcCarteraE2ETest` con `--rerun` contra la copia servida de pediatría
    (`-Psalud360.test.hc.pediatria=http://localhost/HCDPediatria-salud360/public/index.php` y el token de prueba, sin
    escribirlo): la prueba de envío sigue en verde porque se tocó `HcApiSync`.
  - Hecho cuando: ambos E2E en verde; lo que deja el de pediatría (una consulta de baja y sus imágenes) borrado por id.
  - **Hecho (2026-10-30, sin cambios de código; solo esta nota).** Con el motor `HcApiSync` tal como queda tras E5.1 a E5.3 (candado;
    `soloLectura = false` para pediatría) y la API real local (`http://localhost/HCDPediatria-salud360/public/index.php`, base
    `hc_pediatrica`; Apache y MySQL del XAMPP vivos, `pacientes` sin token da `401`): **`HcPediatriaE2ETest` y `HcCarteraE2ETest` en
    verde** (`--rerun`, una corrida, ~12 s y ~10 s: corrieron de verdad, no se saltearon). El de pediatría recorre consulta, secciones de texto y
    formulario, antecedentes, cuatro registros (examen complementario, interconsulta, screening, internación) con su `remoto_id`, examen físico,
    dos adjuntos (galería y descarga) y el estado, y después los da de baja; el de cartera trae y no duplica la cartera del médico de prueba.
    Fue con el token del médico (rol `medico`); el del admin solo se usó para comprobar el rol en `auth/perfil`, **`HcLicenciasE2ETest` no se
    corrió** (E5.4 no la nombra; ya la cubren los tests de `AdminRepository` de E5.3). **Sin propiedades:** `.\gradlew :core:data:jvmTest --rerun`
    completo 671 tests, 0 fallas, 0 errores (los E2E vuelven antes de hacer nada, como siempre).
    **Qué no se probó aquí:** un backend de solo lectura de clínica registrado en el mismo motor junto a pediatría con la API real (E5.4 no lo pide;
    lo cubren `HcApiSyncSoloLecturaTest` y `HcApiSyncBaseMigradaTest` con backends falsos, y E8.2 con la app real).
    **Limpieza.** Antes: `COUNT(*)` de las 55 tablas de `hc_pediatrica` (solo números, en `%TEMP%\opencode\e54\antes.txt`), 8 imágenes en
    `public/img/salud360`, 15 consultas con `activo = 0`, `salud360_token_cache` 5 filas. La corrida dejó una consulta de baja (id 28285) y
    una fila en cada una de 16 tablas (`alimentacions`, `antecedentes_familiares`, `antecedentes_obstetricos`, `antecedentes_personales`,
    `conductas`, `consulta_fotos`, `examen_fisicos`, `examenes_complementarios`, `examenes_complementarios_fotos`, `familias`,
    `interconsultas`, `internaciones`, `motivo_consultas`, `observaciones`, `embarazo_actuals`, `screenings`), tres en
    `desarrollo_madurativo_pacientes` y dos imágenes. Se borraron por id (`limpieza.sql`: fotos → padres, `internaciones` antes que
    `antecedentes_personales`, `consultas` al final, sin `DROP`) y las dos imágenes por nombre: **los conteos de las 55 tablas quedaron
    idénticos a los de antes**, 8 imágenes, 15 consultas de baja. **`salud360_token_cache` de pediatría NO se vació a propósito:** sus 5 filas
    incluyen los dos tokens de prueba sembrados (ver `docs/hc-pediatria-estado-y-plan.md`) y vaciarla impediría las próximas corridas; la corrida
    no agregó ninguna (5 antes y después). Ningún token quedó en el repo, en este texto ni en el commit.

Al terminar la tanda: validación del reviewer y MEMORY.md.

## Tanda E6 — App II: backend de lectura y mapeo

- [x] **E6.1. Cliente de solo lectura y DTOs.** RF-38 a RF-41, RF-47 — **M** — Toca API Laravel: no
  - Tests primero: `commonTest` `HcClinicaDtosTest` (cada respuesta de plan.md —lista, consulta, historial— leída con
    sus ejemplos de JSON: `hay_mas` como número, `null`, campos que faltan, `omitidas`, `no_leidas`, valores de más).
  - Código: `core/data/.../network/hc/HcApiSoloLectura.kt` (expone solo `get`, `leer`, `leerOpcional`, `leerTodo`) y
    `HcClinicaDtos.kt`; `HcDtos.kt` no se toca.
  - Hecho cuando: el test en verde (antes, en rojo) y `.\gradlew :core:data:compileKotlinJvm` sin errores; buscar `post(`,
    `put(`, `delete(`, `subir(`, `pedir(` en `HcApiSoloLectura.kt` no da resultados.
  - **Hecho (2026-10-30, tanda A encadenada).** Archivos nuevos en `core/data/.../network/hc/`: `HcApiSoloLectura.kt` (envoltorio de
    `HcApiClient` con solo `get`, `leer`, `leerOpcional`, `leerTodo`; el cliente completo queda `private`), `HcClinicaDtos.kt` (los DTOs de
    las 3 respuestas) y `ConsultasClinicaApi.kt` (**agregado a lo que pedía el plan, por pedido de la tanda**: interfaz con solo
    `listar(pacienteWebId, limite = 50, desde = 0)`, `consulta(id)`, `historia(pacienteWebId)` y `ConsultasClinicaApiRemota(HcApiSoloLectura)`,
    con el patrón de `EnfermedadesApi`). E6.3 puede recibir cualquiera de las dos capas (el plan dice `HcApiSoloLectura`; la interfaz sirve para
    probar el backend con una API falsa). Tests: `commonTest` `HcClinicaDtosTest` (los ejemplos del md, ausencias, claves de más, `hay_mas`
    número, errores del contrato), `ConsultasClinicaApiRutasTest` y `EjemplosConsultasClinica` (JSON inventado); `jvmTest`
    `ConsultasClinicaApiSoloLecturaTest` (reflexión: lo único público de `HcApiSoloLectura` es `get`/`leer`/`leerOpcional`/`leerTodo`, de la
    interfaz y de la clase remota `listar`/`consulta`/`historia`, la remota solo recibe un `HcApiSoloLectura` y ningún método recibe más que
    ids y números; y contra un `HttpServer` en `127.0.0.1`: solo `GET`, la query `paciente_id`/`limite`/`desde`, sin cuerpo ni `Content-Type`,
    403/404/422/429/500 con status y `codigo`, **un solo pedido** ante cada error (sin reintento, RF-57), JSON cortado y servidor apagado).
    **Rojo previo:** `:core:data:compileTestKotlinJvm` fallaba con 229 errores `Unresolved reference` solo en los 3 archivos de test nuevos
    (los tipos que faltaban). **Verde:** `:core:data:jvmTest --rerun` completo 729 tests (671 + 58 nuevos), 0 fallas, 0 errores;
    `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila; `post(`, `put(`, `delete(`, `subir(`, `pedir(` no aparecen en
    `HcApiSoloLectura.kt` ni en `ConsultasClinicaApi.kt`. Mutación: un método `enviar` (con `put`) agregado a `HcApiSoloLectura` hace fallar el
    test de reflexión (revertido). **Contrastado con el JSON real** de la copia local (fixture de la L: lista, 4 consultas, historia, errores
    403/404/422): los DTOs lo leen sin error (los JSON reales no se guardaron en el repo; los de los tests son inventados).
    Decisiones: **(1) Qué puede faltar (Q15):** son obligatorios los contenedores de los que se deduce «ya no está en la web» (`consultas`,
    `total`, `desde`, `hay_mas`, `secciones`, `examen_fisico`, `laboratorios` y sus 3 listas, `estudios` y sus 5, `motivos`,
    `examenes_complementarios`, `interconsultas`) y las claves `antecedentes_familiares` y `pendientes` (pueden ser `null`, pero tienen que
    venir): sin ellas la respuesta es `TobbException` `respuesta` y no se aplica, para que una ausencia nunca se lea como «vacío» y dispare
    una marca de E6.4b. Pueden faltar `created_at`, `antecedentes_familiares_perfil4`, `omitidas` y `no_leidas` (solo avisan) y los campos
    opcionales de cada fila (`medico_id`, `medico_nombre`, `tipo`, `tipo_numero`, `updated_at`, `consulta_id`, `fecha`, `respuesta`, ...).
    **(2) `antecedentes_familiares`** mezcla casillas (objetos) y `nota` (texto) en un objeto: serializador propio solo de lectura →
    `AntecedentesFamiliaresDto(casillas: Map<String, CasillaAntecedenteDto>, nota)`; guarda también las casillas que la app no conoce.
    **(3) Los valores** (laboratorio, formularios, examen físico) se leen como cadenas aunque el servidor mandara un número JSON. **(4)** `ids`
    como `Long`; los de estudios, exámenes e interconsultas quedan crudos (el id local lo arma E6.2 con el tipo). **(5)** Sin conversión al
    modelo (no la pide E6.1; es E6.2). `estado` queda como texto `ABIERTA`/`CERRADA`.
    **Diferencias entre el plan y el JSON real:** el plan (`plan.md`) lista `edad_meses` en la cabecera: el servidor **no lo manda** (ya lo
    corrigió E4.x; el DTO lo ignoraría igual); el plan no tiene `created_at` ni `antecedentes_familiares_perfil4` (E4.4); `datos` del plan es
    `datos_invalidos` en el código del 422; el `404` de una consulta de un paciente fuera de la cartera es `403 fuera_de_cartera` (D3).

- [x] **E6.2. Ids y conversión pura (`LecturaClinica`).** RF-40, RF-41, RF-42, RF-52 — **M** — Toca API Laravel: no
  - Tests primero: `commonTest` `LecturaClinicaTest`: ids `hcc-c`, `hcc-m`, `hcc-l`, `hcc-r`, `hcc-p`, disjuntos de `hcp-c…`
    y `hc-clinica-p…`; serologías `SI`/`NO`, Child-Pugh `"1"`–`"3"`, alimentación `"1|detalle"`; cada analito (los 5
    desalineados y `otros`) en su fila; antecedente `dependiente_terceros`; carga de laboratorio con `consulta_id`
    que no está local → `null`; médico propio → el de la sesión, ajeno → `hcc-m<id>`; `esDeLaWeb`, `esPropia`; nunca un `""`
    inventado. **Q15 (nunca borrar):** `marcarAusentes(lo ya traído, respuesta, cobertura)` devuelve solo marcas (ids de
    consultas, cargas, filas de estudio o lista, pendientes, antecedentes, secciones y campos que dejaron de venir);
    una consulta fuera de la cobertura de la respuesta (lista con `hay_mas`, más allá de la última consulta pedida) no
    se marca; una respuesta con error o incompleta no marca nada; lo que sigue viniendo, o vuelve a venir, no se
    marca (la marca vieja se retira); el resultado **no tiene forma de expresar un borrado** (solo marcar y desmarcar).
  - Código: `core/data/.../hcgeneral/LecturaClinica.kt` (puro). Aquí se fija el formato de las claves `hcc_ausente_…`.
  - Alcance (2026-10-28, Q15): reemplaza a la `quitarDeLoTraido` del borrador (que ya no existe); sigue en **M**.
  - Hecho cuando: el test en verde (antes, en rojo).
  - **Hecho (2026-10-30, tanda A encadenada).** `hcgeneral/LecturaClinica.kt` (puro: sin red, sin base, sin Compose; sin dependencias ni
    migraciones; `SeccionesSoloLectura.tieneDato` pasó de `private` a `internal` para usar la misma regla de «hay dato» en la
    alimentación). Tests: `commonTest` `LecturaClinicaTest` (ids y conversión), `LecturaClinicaAusentesTest` (Q15) y el helper
    `RespuestasClinicaDeEjemplo` (los ejemplos inventados de E6.1 ya leídos con los DTOs); `jvmTest` `LecturaClinicaSinBorradoTest`
    (reflexión: `MarcasAusentes` solo tiene `marcar`/`desmarcar`, ningún nombre de la lectura sugiere un borrado o un vaciado y nada
    toca `Queries`/`Salud360Db`); y `especialidades/clinica` `commonTest` `LecturaClinicaCatalogoTest` (**fuera de `core/data` a
    propósito**: `catalogoHcGeneral` vive en ese módulo y `core:data` no lo ve) que recorre una respuesta con todas las claves del
    contrato y comprueba contra el catálogo real que cada sección, campo, analito, tipo de registro y antecedente existe (los 5
    analitos desalineados y `otros` incluidos), que `tabaquismo` no se inventa y que cada una de las 25 secciones del catálogo tiene
    una decisión (se escribe por consulta, por paciente, es el examen, o no tiene equivalente: `enfermedades` y `documentos`).
    **Rojo previo:** `:core:data:compileTestKotlinJvm` fallaba con 728 errores de compilación (`Unresolved reference` y la inferencia
    que arrastran) solo en los archivos de test nuevos. **Verde:** `:core:data:jvmTest --rerun` completo 825 tests (729 + 96 nuevos),
    0 fallas, 0 errores; `:especialidades:clinica:jvmTest --rerun` 32 (7 nuevos); `:composeApp:compileKotlinWasmJs -Pentorno=dev`
    compila. Mutaciones (revertidas): una lista parcial que cubre, un examen que se pisa con `""` y una serología que acepta cualquier
    valor hacen fallar 5 tests.
    **Ids (RF-52), siempre `hcc-` + letra + tipo + id de la web:** consulta `hcc-c7003`; médico de otra `hcc-m14` (`hcc-m0` si la web no lo
    informó); carga `hcc-l<tipo>-<id>` (`hcc-llaboratorio_general-703`, `hcc-lpeso-25`); estudio, examen o interconsulta `hcc-r<tipo>-<id>`
    (`hcc-rveda-1797`, `hcc-rbiopsia_hepatica-1797`, `hcc-rexamen_complementario-22`); pendiente `hcc-p1744`; antecedente familiar
    `hcc-a<ficha>-<clave>` (`hcc-a5001-hta`, `-nota`, `-nota_perfil4`). Salen solo de lo que la web mandó (no del paciente local ni de un
    contador): traer lo mismo dos veces da el mismo id. Test de tabla: sin repetidos entre tipos, números y fichas, ninguno empieza con
    `hcp-`, `hc-clinica-p` ni `tobb-`, y 500 `newId()` nunca empiezan con `hcc-`.
    **`marcarAusentes(traido, marcadas, lectura)`** devuelve `MarcasAusentes(marcar, desmarcar)` (dos conjuntos de «claves de ausencia») y nada
    más: no existe un resultado, una función ni un nombre que exprese un borrado. `lectura` es `LecturaWeb`: `Lista(pacienteWebId, páginas)`,
    `UnaConsulta`, `ConsultaNoEncontrada` (el `404 consulta_no_encontrada`), `Historia(pacienteWebId, respuesta)` y `ConError` (no marca
    ni desmarca nada). Clave de ausencia = el id local de la cosa (`hcc-c7003`, `hcc-rveda-1797`, `hcc-p1744`, `hcc-a5001-hta`) o, para el
    contenido de una consulta, `<consulta>:<sección>:<campo>` (`hcc-c7003:habitos:texto`, `hcc-c7003:examen_fisico:peso`); la marca es la
    fila de `paciente_extra` `hcc_ausente_<clave de ausencia>` con la fecha como valor (`marcaAusente`, `claveDeLaMarca`;
    `filaDeLaWeb` la deja con `dirty = 0`). Solo marca lo que la respuesta cubre: la lista, únicamente si las páginas de un mismo
    «Actualizar» empiezan en 0, encadenan, tienen el mismo `total`, todas menos la última dicen `hay_mas` y la suma da el total, sin
    repetidos y todas del paciente; la consulta, su contenido; la historia, lo del paciente (antecedentes, cargas, filas, pendiente y
    el motivo de cada consulta). Lo que vuelve a venir se desmarca y una marca ya puesta no se repite. `traido` y `marcadas` los arma
    E6.4b y tienen que ser solo del paciente. `clavesDeLaConsulta` y `clavesDeLaHistoria` dan las claves de lo que una respuesta trae.
    **Decisiones que la spec no fijaba (para revisar):** (1) las conversiones devuelven el modelo con `updatedAt = null` y las
    `fusionar…` (consulta, examen físico, antecedente, carga, registro) devuelven lo que hay que escribir o `null` si no cambia nada:
    lo que viene pisa, lo que no viene o viene vacío se conserva (el examen conserva FC y circunferencia abdominal); `consulta_id`,
    fecha y detalle ya traídos no vuelven a `null` ni a `""`. (2) El motivo de `historia.motivos` va como el texto
    `motivo_consulta/texto` de `hcc-c<consulta_id>` y **solo si esa consulta está local** (no se crea una consulta ni una fila
    huérfana); por eso no tiene id propio, y su marca es la del contenido de la consulta. (3) Un antecedente de la web usa siempre el
    id `hcc-a…`; si el dispositivo ya tiene una fila de esa clave con otro id, o sucia, `fusionarAntecedente` devuelve `null` (lo del
    dispositivo manda y no se duplica). La nota del perfil 4 es el antecedente `familiares/nota_perfil4`; dónde se muestra lo decide
    E7.2, y **nunca se marca como ausente** porque el DTO no distingue una clave que falta de un `null` (servidor anterior o tabla
    inexistente). (4) `numero` (orden de exámenes e interconsultas) se guarda como un campo más; `remoto_id` de los registros es el id
    de la web. (5) Una consulta con tipo (`control`/`telemedicina`, o `tipo_numero` 1/4 si falta), estado o fecha que no se
    reconocen devuelve `null`: no se muestra ni se inventa nada (E6.3 decide si lo registra). (6) La lista solo cubre con todas las
    páginas, como pidió la tanda; no se marca «por rango» una consulta más nueva que la última de una página parcial (más fino, y
    más riesgoso). Una página parcial sí quita la marca de lo que trae. (7) `created_at` no se guarda (no hay columna): dos
    consultas del mismo día se desempatan en la base por `updated_at`, así que E6.3 debería escribirlas de la más vieja a la más nueva
    con `updated_at` creciente. (8) `desconocidos(catalogo, …)` informa lo que el catálogo no conoce pero la conversión lo guarda
    igual; una serología que no es `SI`/`NO` o un punto de Child-Pugh fuera de 1 a 3 no se guarda (RF-42: nada en una casilla
    equivocada). (9) Un historial con `omitidas.laboratorio_sin_fecha` hace que una carga ya traída que perdió la fecha se marque como
    ausente (D1).

- [x] **E6.3. `HcClinicaBackend.traerConsultas`.** RF-38, RF-41, RF-43, RF-45, RF-47, RF-49, RF-52 a RF-54, RF-56, RF-57 — **M** — Toca API Laravel: no
  - Tests primero: `jvmTest` `HcClinicaBackendTest` y `HcClinicaBackendSoloLecturaTest` (servidor falso con
    `com.sun.net.httpserver.HttpServer` en `127.0.0.1`, que registra método y ruta): trae lista + historial (2 pedidos,
    solo `GET`) y guarda las cabeceras con `dirty = 0`, `remoto_id` y `medico_id` (propio / `hcc-m…` + nombre en
    `medico_preferencia`); `omitidas` y `no_leidas` en `paciente_extra`; traer dos veces no duplica; segunda llamada
    antes de 60 s (reloj falso) → 0 pedidos, con `forzar` → 2; paciente sin `hc_paciente_id` → **0 pedidos**, aviso de RF-49
    y nada escrito; servidor apagado, `500`, `429` y JSON cortado → excepción con el motivo, nada cambia y sin reintento
    propio; una consulta local sucia con la misma clave no se pisa; los métodos de escritura lanzan. **Q15:** aplicar una
    lista más corta que la que ya había en el dispositivo (una consulta que desapareció) no borra ninguna fila
    (conteo y contenido de `consulta` iguales antes y después; la marcación la prueba E6.4b).
  - Código: `core/data/.../repos/HcClinicaBackend.kt` (`soloLectura = true`; recibe `HcApiSoloLectura`, `Salud360Db`,
    `PacientesHcRepository` y un reloj; usa `fichaConocida`, nunca `vinculo`). Sin `DELETE` sobre tablas clínicas.
  - Hecho cuando: ambos tests en verde (antes, en rojo) y ningún pedido que no sea `GET` en el registro del servidor falso.
  - **Hecho (2026-10-30, tanda A encadenada).** Código: `repos/HcClinicaBackend.kt` y `hcgeneral/OmitidasLectura.kt` (`hcc_omitidas`,
    formato `foto|otro|sin_fecha`, que E7.1 lee con `omitidasDeTexto`). Tests: `jvmTest` `HcClinicaBackendTest` (45, API falsa de
    `ConsultasClinicaApi` y base SQLite temporal), `HcClinicaBackendSoloLecturaTest` (20, servidor `HttpServer` en `127.0.0.1` que anota
    método y ruta, con la API remota de verdad), `HcClinicaPruebas` (apoyo) y `commonTest` `OmitidasLecturaTest` (4). **Rojo previo:**
    `:core:data:compileTestKotlinJvm` sin `HcClinicaBackend` ni `OmitidasLectura`: 94 errores de compilación, solo en los tests nuevos.
    **Verde:** `:core:data:jvmTest --rerun` completo 894 tests (825 + 69), 0 fallas, 0 errores; `:composeApp:compileKotlinWasmJs
    -Pentorno=dev` compila; `git grep` de `post(`, `put(`, `delete(`, `subir(`, `pedir(`, `descargar(`, `HttpMethod`, `HcApiClient`,
    `DELETE` y `vinculo(` en `HcClinicaBackend.kt`: solo un comentario que nombra `pacientes/resolver`. Mutación (revertida): escribir de
    la más nueva a la más vieja hace fallar el test del orden. **Pedidos:** una página = 1; 160 consultas = 4 (`desde` 0, 50, 100, 150,
    `limite` 50); sin ficha = 0; segunda llamada antes de 60 s = 0 (con `forzar` otra vez); dos llamadas a la vez = 1 (un `Mutex`);
    tope de 20 páginas. Todos `GET consultas?paciente_id=&limite=&desde=`.
    **«Local gana» o «web pisa»:** por consulta de la web (`hcc-c<id>`): no se toca si el id está en `consultasDirty()`, si el
    dispositivo ya tiene otra fila con el mismo `remoto_id` (no se duplica), o si la fila `hcc-c…` es de otro paciente local; las
    creadas acá (UUID, sin `remoto_id`) no chocan nunca. Si no, `fusionarConsulta` (E6.2): lo que viene pisa, la edad vacía no, y
    `null` (no se escribe, `updated_at` intacto) si no cambia nada. Se escribe con `dirty = 0`, de la más vieja a la más nueva con
    `updated_at = ahora + i`, todo en una transacción. `medico_id`: el de la sesión si `propia`, si no `hcc-m<id>` (`hcc-m0` sin id),
    con el nombre en `medico_preferencia` (vacío no pisa; uno `dirty` no se toca).
    **Errores:** 401/403/404/422/429/500, sin conexión y respuesta incompleta (falta `total` o `consultas`) → la `TobbException` sale tal
    cual, un solo pedido, la base idéntica (volcado de 11 tablas). Un error en la página N descarta también las N-1 anteriores (se
    leen todas y se escribe de una vez, para ordenar bien y ser atómico): se vuelven a pedir en el próximo intento. Un trigger que
    rechaza la última escritura prueba que no queda media lista. **Decisiones para revisar:** (1) **`traerConsultas` hace solo la lista, no
    el historial:** el texto de arriba y `plan.md` hablan de «lista + historial (2 pedidos)», pero `aplicarHistoria` es de E6.4 y pedirlo sin
    aplicarlo sería un pedido gastado contra el límite de 60 por minuto; E6.4 agrega el pedido de `historia` dentro de `traerConsultas`. Del
    mismo modo `no_leidas` solo viene de `GET consultas/{id}` y es de E6.4; acá se guarda `omitidas` de la lista. (2) `traerConsulta` lanza
    `UnsupportedOperationException` hasta E6.4 (el backend no se registra antes, E6.5). (3) `bajarArchivo` devuelve `null` y las licencias
    quedan con el valor por defecto de `HcBackend` (vacío / `false` / `null`), sin llamar a la API. (4) `forzar` es un método de
    `HcClinicaBackend`, no de `HcBackend`: E7.2 tiene que decidir cómo llega «Actualizar» desde `HcRepository`. (5) `hcc_omitidas` sigue a
    la web también hacia cero (es una cuenta, no un dato clínico); sin fila previa y en cero no se escribe. (6) Una cabecera con tipo,
    estado o fecha desconocidos no se escribe (se registra en el log). (7) Con un `total` que cambia, una página vacía o un `desde` que
    no cuadra se deja de pedir, se escribe lo recibido y no se lanza error; `marcarAusentes` (E6.4b) lo vuelve a comprobar y no marca. (8) La
    espera de 60 s se cuenta por (paciente, médico) y solo arranca tras una lectura buena. (9) Las consultas se recorren todas (tope
    20 páginas), no solo las 50 más recientes de Q9: lo pidió la tanda para poder marcar ausentes; la pantalla (E7.2) decide qué muestra.

- [x] **E6.4. `traerConsulta` y aplicar el historial.** RF-39, RF-40, RF-41, RF-53 a RF-55 — **M** — Toca API Laravel: no
  - Tests primero (en `HcClinicaBackendTest`): una consulta de la web llega completa a `seccion_valor`, `examen_fisico`,
    `registro_clinico`, `laboratorio`, `antecedente` y `pendiente`, todo `dirty = 0`; una respuesta cortada o con `500`
    no deja nada de esa consulta (transacción); una clave sucia (antecedente local, laboratorio local) no se toca; **Q15
    (nunca borrar):** una sección, un texto o un campo que no vino, o vino vacío, no borra ni vacía el que ya estaba
    (`seccion_valor`, `examen_fisico`, `registro_clinico`, `laboratorio`, `antecedente` y `pendiente` iguales fila por
    fila antes y después de aplicar una respuesta más corta o vacía); una fila traída que dejó de venir sigue en la
    base; un valor vacío nunca reemplaza a uno cargado; el examen conserva la frecuencia cardíaca y la circunferencia
    abdominal locales; una cardiológica de 191 caracteres entra entera.
  - Código: `traerConsulta` y `aplicarHistoria` en `HcClinicaBackend`, escribiendo campo por campo y solo lo que viene
    con valor (sin `DELETE`, sin `INSERT OR REPLACE` de una fila armada solo con lo recibido).
  - Alcance (2026-10-28, Q15): sin `quitarDeLoTraido` ni "aplicar el historial" como espejo. Las marcas "Ya no figura
    en la web" se ponen en E6.4b.
  - Hecho cuando: los tests en verde (antes, en rojo).
  - **Hecho (2026-10-30, tanda A encadenada).** Código: `repos/HcClinicaBackend.kt` (`traerConsulta`, `aplicarConsulta`,
    `aplicarHistoria`, `leerHistorial`, `guardarAviso`), `hcgeneral/OmitidasLectura.kt` (claves y formato de los avisos nuevos) y una
    consulta nueva en `HistoriaClinica.sq` (`pendientePorId`, solo lectura: no cambia el esquema, sin migración). Tests: `jvmTest`
    `HcClinicaBackendConsultaTest` (32), `HcClinicaBackendHistoriaTest` (38), `HcClinicaBackendSoloLecturaTest` (+9, servidor
    `HttpServer` en `127.0.0.1`), `HcClinicaPruebas` (la API falsa pasó acá, compartida por los tests de `HcClinicaBackend`) y
    `commonTest` `OmitidasLecturaTest` (+6). Los de las tareas pedían estar en `HcClinicaBackendTest`: están en archivos aparte
    (como E6.3 con su `SoloLectura`) para no pasar de 1.500 líneas; `HcClinicaBackendTest` solo cambió para usar la API falsa compartida y
    comprobar que, si la lista falla, no sale el pedido del historial. **Rojo previo:** `:core:data:compileTestKotlinJvm` fallaba con 30
    errores de compilación (los avisos nuevos), y con eso compilando, `jvmTest` daba 74 fallas de 154 en los tres archivos nuevos o
    cambiados (Consulta 31/32, Historia 31/38, SoloLectura 12/29). **Verde:** `:core:data:jvmTest --rerun` completo 979 tests (894 +
    85), 0 fallas, 0 errores; `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila; `git grep` de `post(`, `put(`, `delete(`,
    `subir(`, `pedir(`, `descargar(`, `HttpMethod`, `HcApiClient`, `DELETE` y `vinculo(` en `HcClinicaBackend.kt`: solo dos comentarios que
    nombran `pacientes/resolver` y `DELETE`. Mutaciones (revertidas, 4 a la vez; las detectan 6 tests): sacar el filtro de claves sucias
    de `seccion_valor`, el de cargas sucias, la espera del historial y el `dirty` del aviso.
    **Pedidos (RF-57), todos `GET`:** abrir la historia = 1 de lista por cada 50 consultas + 1 de `pacientes/{ficha}/historia` (una con 3
    consultas = 2; 160 = 4 + 1 = 5; 50 justas = 1 + 1; paciente sin consultas = 1 + 1); abrir una consulta = 1 (`GET consultas/{id}`, sin
    query ni cuerpo); sin ficha = 0 (ni lista, ni historial, ni consulta, ni `resolver`); segunda llamada antes de 60 s = 0; con
    `forzar` = los dos otra vez. `429` y cualquier otro error: un solo pedido de ese tipo, sin reintento (RF-58).
    **Lista e historial son independientes (decisión de la tarea, para revisar):** son dos pedidos, dos transacciones y dos esperas de
    60 s. (a) Si **la lista falla** (cualquier error) el historial **no se pide**: un `429`, un `401` o una caída valen para los dos y RF-57
    pide no insistir; nada cambia. (b) Si **el historial falla** (error, respuesta de otra ficha o la base rechaza una fila), la lista ya
    escrita **se queda**, el historial no deja nada (transacción) y el error sale tal cual; como la lista ya arrancó su minuto, el
    próximo pedido sin «Actualizar» repite solo el historial (1 pedido). (c) `traerConsultas` lanza en ese caso, así que quien llama no
    recibe el resultado: lee la lista de la base (`observarConsultas`), que ya está actualizada.
    **`traerConsulta(consultaLocalId, remotoId)`:** pide `GET consultas/{id}` solo si `remotoId` es un número positivo, el id local es
    exactamente `hcc-c<remotoId>`, la consulta está en el dispositivo (clínica), **no está sucia** y su paciente tiene ficha; si no, 0
    pedidos y nada escrito (una del dispositivo con el mismo `remoto_id` tampoco recibe lo de la web). Escribe con `dirty = 0`, en una
    transacción, **solo `seccion_valor` y `examen_fisico` de esa consulta y el aviso**: `valoresDeLaWeb` (solo lo que tiene dato; no
    reescribe un valor igual), `fusionarExamenFisico` (conserva frecuencia cardíaca y circunferencia) y `hcc_no_leidas_<id web>`. Una
    clave sucia no se toca. Los errores salen tal cual (`404 consulta_no_encontrada`, `403`, `429`, ...): el código permite a E6.4b marcarla.
    Una respuesta de otra consulta o de otra ficha es dudosa: `TobbException` `respuesta` y no se aplica. **No toca la cabecera** (estado,
    edad, médico): la refresca la lista, porque `traerConsulta` no conoce al médico de la sesión.
    **`no_leidas` (RF-43, decisión para revisar):** fila de `paciente_extra` `hcc_no_leidas_<id web>` (especialidad `clinica`), valor =
    los ids del servidor separados por coma, sin repetidos y en su orden (`documentos,audio`; los que la app no conoce también);
    `noLeidasDeTexto` distingue «sin fila» (`null`, no se sabe) de «fila vacía» (ya no tiene nada). Sin fila y sin nada que avisar no se
    escribe; sigue a la web también hacia cero (como `hcc_omitidas`: es un aviso, no un dato clínico); una fila sucia no se toca.
    **Historial (`aplicarHistoria`):** sin `DELETE` ni `INSERT OR REPLACE` de una fila armada con lo recibido: cada fila pasa por la
    `fusionar…` de E6.2 (devuelve `null` si no cambia nada, así que traerlo dos veces no cambia ni `updated_at`) y se salta si su id está
    en `…Dirty()`. Antecedentes familiares por `fusionarAntecedente` (uno del dispositivo con la misma casilla manda y no se duplica;
    casilla 0 sin detalle no se manda: no se inventa), la nota del perfil 4 como `nota_perfil4`, cargas por id determinista (`hcc-l…`,
    varias del dispositivo de la misma fecha conviven: solo chocan por id), estudios/exámenes/interconsultas por tipo + id (los tres
    1797 son tres filas; una cardiológica de 191 caracteres entra entera), motivos de las consultas **que están** (sin consulta no se crea
    nada), pendiente del médico de la sesión (`pendientePorId`; si otro médico lo trae después, pasa a ser de él). Una fila cuyo id ya
    pertenece a otro paciente local no se mueve (cargas, estudios, pendiente; el antecedente no tiene esa guarda: su id lleva la ficha).
    **D1 (cargas sin fecha):** las que el servidor omite se cuentan en `omitidas.laboratorio_sin_fecha` y se guardan en una fila **aparte**,
    `hcc_omitidas_laboratorio` (el número). Decisión para revisar: la tarea decía «en la cuenta de omitidas», pero `hcc_omitidas` es
    `foto|otro|sin_fecha` de la lista (E6.3; lo lee E7.1 con `omitidasDeTexto`, que exige tres números) y la lista y el historial son
    pedidos que pueden fallar por separado; E7.1 suma las dos cuentas en el aviso. Una carga que llegara sin fecha (o `1000-01-01`) igual
    no se guarda. **Q15:** un historial vacío o más corto, una consulta vacía y un `404` no borran ni vacían ninguna fila (comparado fila por
    fila de las seis tablas); no se pone ninguna marca `hcc_ausente_…` (E6.4b). Sigue sin registrarse el backend (E6.5) ni tocarse
    pantallas o servidor.

- [x] **E6.4b. Marcar lo que ya no figura en la web (Q15).** RF-55, RF-63 — **M** — Toca API Laravel: no
  - Tarea **nueva** (2026-10-28) por la decisión Q15 = nunca borrar. Va después de E6.2, E6.3 y E6.4.
  - Tests primero: `jvmTest` `HcClinicaBackendAusentesTest` (servidor falso como en E6.3): **una consulta que desaparece de
    la respuesta queda en el dispositivo y se marca** (la fila de `consulta` y todas sus hijas iguales antes y después,
    más la clave `hcc_ausente_…` en `paciente_extra`); lo mismo con una consulta dada de baja, con un `404
    consulta_no_encontrada` al abrirla, con un texto o una sección que la web vació, y con una carga de laboratorio, un
    estudio, una interconsulta, un pendiente y un antecedente que dejaron de venir; si el dato vuelve a venir, la marca
    se retira y se aplica el valor de la web; una respuesta con `500`, cortada, con JSON inválido, o la página 2 de la
    lista (con `hay_mas`) **no marca nada**; ninguna operación del backend ejecuta un `DELETE` sobre `consulta`,
    `seccion_valor`, `examen_fisico`, `registro_clinico`, `laboratorio`, `antecedente` ni `pendiente` (se comprueba
    contando filas antes y después, y revisando el código).
  - Código: aplicar las marcas de `marcarAusentes` (E6.2) desde `traerConsultas`, `traerConsulta` y `aplicarHistoria`, en
    la misma transacción que la escritura de lo que sí vino; lectura de las marcas para la pantalla (función de consulta
    del repositorio o del backend, sin cambiar el esquema).
  - Hecho cuando: el test en verde (antes, en rojo) y `.\gradlew :core:data:jvmTest` completo en verde.
  - **Hecho (2026-10-30, tanda A encadenada).** Código: `repos/MarcasDeLaWeb.kt` (nuevo, `internal`: `aplicar` y `delPaciente`),
    `repos/HcClinicaBackend.kt` (llama a `marcarAusentes` desde `traerConsultas`, `aplicarHistoria` y `traerConsulta`; función pública
    `marcasAusentes(pacienteId)` para la pantalla: clave de ausencia → fecha), `hcgeneral/LecturaClinica.kt` (`clavesDelExamenTraido`, para que
    el examen físico local y el de la respuesta usen los mismos nombres de campo) y dos consultas de lectura en `HistoriaClinica.sq`
    (`laboratoriosDePacienteTodos`, `pendientesDePacienteTodos`; sin migración, sin tocar el esquema). Tests: `jvmTest`
    `HcClinicaBackendAusentesTest` (35, API falsa y base SQLite temporal) y 8 existentes ajustados (ver abajo). **Rojo previo:** con solo
    `marcasAusentes()` escrito (la lectura de las marcas, que sin escritura devuelve vacío) el test nuevo daba 24 fallas de 35 (las 11 que
    pasaban eran las de «no marca nada», que lo cumplen sin código). **Verde:** `:core:data:jvmTest --rerun` completo 1014 tests (979 + 35),
    0 fallas, 0 errores, 0 salteados (dos corridas completas seguidas); `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila; `git grep`
    de `DELETE`, `post(`, `put(`, `delete(`, `subir(`, `pedir(`, `descargar(`, `HttpMethod`, `HcApiClient` y `vinculo(` en `HcClinicaBackend.kt` y
    `MarcasDeLaWeb.kt`: solo un comentario que nombra `DELETE`. Mutaciones (revertidas, las tres a la vez): marcar en una transacción aparte
    de la escritura de la lista, no filtrar `dirty` al armar `traido`, y marcar ante cualquier `404` hacen fallar 3 tests (uno cada una).
    **Cómo marca (decisiones para revisar):** (1) **Las marcas se escriben primero** dentro de la transacción de cada respuesta (lista,
    historial, consulta), antes que lo que la web mandó: así un test con un `TRIGGER` que falla en una escritura posterior prueba que la marca
    se deshace, y uno que falla en la marca prueba que no queda lo demás. (2) **`traido` y `marcadas` salen solo del paciente** (`paciente_id`
    de cada fila: consultas, antecedentes, cargas, filas de estudio, pendientes, y las marcas de `paciente_extra`) y solo de filas con `dirty = 0`
    y con el prefijo `hcc-`: lo sucio, lo creado acá (UUID) y las consultas del dispositivo con `remoto_id` no entran nunca. Según el tipo
    de respuesta solo se arma lo que esa respuesta puede cubrir (la lista, las consultas; la consulta, su contenido: valores no vacíos de
    `seccion_valor` y los cinco campos del examen que la web manda —la frecuencia cardíaca y la circunferencia abdominal no—; el historial,
    antecedentes, cargas, filas, pendientes y el motivo de cada consulta). (3) **Quitar una marca es dar de baja su fila** (`deleted = 1`,
    `dirty = 0`, por `upsertPacienteExtra`): no se agregó ningún `DELETE` al `.sq`; `marcasAusentes` y `extrasDePaciente` ya filtran
    `deleted = 0`, y una marca nueva sobre la misma clave la reemplaza. Quedan filas dadas de baja de marcas que ya reaparecieron (a lo sumo una por
    clave); si se prefiere borrarlas de verdad es un `DELETE` acotado a `hcc_ausente_%`. (4) **`404 consulta_no_encontrada`** (`status` 404 y ese
    `codigo`; cualquier otro `404`, `403`, `429`... no marca) escribe la marca de la consulta en su propia transacción **y relanza el mismo
    error**; si ni eso se puede escribir se anota y se conserva el `404` (`addSuppressed`). Sigue sin pedirse nada para una consulta sucia,
    del dispositivo, de un paciente sin ficha o cuyo id remoto no es el suyo. (5) La fecha de la marca es el día del reloj del backend
    (zona del dispositivo) y una marca vigente no se reescribe: dos corridas iguales dejan la base idéntica, `updated_at` incluido.
    **Qué respuesta marca qué:** lista completa (todas las páginas de un «Actualizar», `total` constante, suma exacta, tope no alcanzado,
    todas de la ficha) → las consultas `hcc-c<id>` limpias del paciente que no vinieron; lista parcial, con error, de una página no pedida, de
    otra ficha o que pasa del tope → nada. Historial de la ficha → antecedentes `hcc-a<ficha>-<clave>` (menos `nota_perfil4`, que nunca se
    marca), cargas `hcc-l…` (también las que la web guarda ahora sin fecha), filas `hcc-r…`, pendiente `hcc-p…` y `<consulta>:motivo_consulta:texto`;
    con error o de otra ficha → nada. `GET consultas/{id}` correcto → `<consulta>:<sección>:<campo>` de esa consulta (un texto en blanco cuenta
    como vacío); `404 consulta_no_encontrada` → la consulta, no su contenido. Lo que vuelve a venir (en cualquiera de las tres) pierde la marca.
    **Tests existentes que había que ajustar** (decían «ninguna marca, eso es E6.4b»): `HcClinicaBackendTest` (lista más corta),
    `HcClinicaBackendHistoriaTest` (historial vacío y más corto), `HcClinicaBackendConsultaTest` (respuesta vacía y `404`; los demás errores
    siguen sin marca) y `HcClinicaBackendSoloLecturaTest` (`404` de una consulta): comparan con `volcarTodoSinMarcas` (nuevo en `HcClinicaPruebas`)
    y comprueban la marca; los demás siguen con `volcarTodo`, o sea que ahí ni una marca. **Nunca se borra:** `ninguna_rama_de_la_lectura_borra_ni_vacia_nada…`
    instala un `TRIGGER` por tabla que aborta todo `INSERT`/`UPDATE`/`DELETE` salvo filas `hcc_ausente_…` de `paciente_extra`, corre la lista, el
    historial, una consulta vacía y un `404`, y compara las 11 tablas antes y después sin las marcas. **Dudas para el revisor:** (a) si la lista dice
    que una consulta ya no está y su `GET consultas/{id}` contesta bien, la segunda respuesta le quita la marca (E6.2 lo definió así: la consulta
    abierta existe); el servidor contesta `404` por lo mismo que la lista omite, así que no debería pasar, pero si pasara la marca se alternaría;
    (b) el motivo de una consulta lo cubren el historial (`motivos`) y la consulta (`secciones.motivo_consulta`): si el servidor los desalineara
    se alternaría igual; (c) `traido` es por paciente, no por (paciente, médico): una consulta traída con la sesión de un médico y vista después por otro
    de la misma base local se marca según lo que diga la lista en ese momento. Esto da por hecho que la lista del servidor no depende de
    quién pide más que por `propia` (no se verificó en esta tarea, que no toca el servidor); si dependiera, la lista de un médico marcaría
    las consultas que solo veía el otro.

- **E6.5 (registrar el backend de solo lectura) se movió: va DESPUÉS de E7.3**, entre E7.3 y E7.4 (decisión D6 de la spec,
  2026-10-29). La tarea conserva su número y su texto más abajo, en la tanda E7. En esta tanda no se registra nada.

- [x] **E6.6. Coherencia con lo que viene de la web.** RF-62 — **S** — Toca API Laravel: no
  - Tests primero: `CargasLaboratorioTest` ampliado (una carga `hcc-l…` tiene `puedeBorrar = false` aunque la consulta sea
    editable; una local sigue `true`); `commonTest` `PendientesWebTest` (los pendientes `hcc-p…` no se marcan resueltos
    al cerrar una consulta local ni con `resolverPendiente`; los locales sí).
  - Código: `CargasLaboratorio.kt`, función pura de pendientes y su uso en `ConsultaViewModel.cerrarConPendientes` /
    `resolverPendiente`.
  - Hecho cuando: ambos tests en verde (antes, en rojo) y `.\gradlew :features:hc:compileKotlinJvm` compila.
  - **Hecho (2026-10-30, tanda A encadenada).** Código: `hcgeneral/CargasLaboratorio.kt` (`Laboratorio.esDeLaWeb()` = id `hcc-…`;
    `puedeBorrarCarga(carga, editable)` = `editable && !esDeLaWeb`; `columnasLaboratorio` la usa para `puedeBorrar`: la columna de la
    web se sigue mostrando, sin tacho), `hcgeneral/PendientesWeb.kt` (nuevo, puro: `Pendiente.esDeLaWeb()`, `puedeResolverse()`,
    `textoDePendientesLocales`, `cambioDePendientes(pendientes, texto)` → `CambioDePendientes(resolver, crear)`) y
    `ConsultaViewModel` (`resolverPendiente` sale sin hacer nada con uno de la web; `cerrarConPendientes` aplica `cambioDePendientes`;
    `textoPendientes()` es solo el de los locales). Tests: `CargasLaboratorioTest` +3 y `PendientesWebTest` (10, nuevo; el plan lo
    pedía en `commonTest`). **Rojo previo:** con la regla vieja a modo de esqueleto (`puedeBorrar = editable`, cierre que resuelve
    todos) fallaban 10 de los 22 tests de las dos clases. **Verde:** `:core:data:jvmTest --rerun` completo 1027 tests (1014 + 13), 0 fallas,
    0 errores, 0 salteados; `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` compilan. Ningún test
    existente cambió de resultado.
    **Decisiones para revisar:** (1) la web guarda **un** pendiente por paciente; al cerrar, el texto que se prellena y con el que se
    compara es solo el de los pendientes **locales** (si incluyera el de la web, editar el texto lo duplicaría en uno local mientras el
    de la web sigue). El de la web nunca se resuelve y sigue contando (Q16-A: el contador no cambia). (2) Un pendiente es «de la web» por
    su id `hcc-p…` (la tabla no tiene `remoto_id`); una carga, por `hcc-l…` (`Laboratorio` tampoco lo tiene). (3) **Lo que E6.6 no
    hace, a propósito:** ninguna guarda en `HcRepository` (`cerrarConsulta`/`reabrirConsulta`/`anularConsulta`/`guardar*` siguen sin
    distinguir consultas de la web: el plan lo deja para E7.3, y E5.3 ya lo había anotado), ninguna pantalla. **E7.3 tiene que:** ocultar
    «Resuelto» en `ConsultaScreen` con `p.puedeResolverse()` (hoy el botón de un pendiente de la web aparecería y no haría nada) y
    cambiar el texto del diálogo de cierre (dice «vacío = no queda nada pendiente», pero uno de la web sigue ahí; conviene listarlo aparte);
    el tacho de `LaboratorioSeccion` ya sale de `puedeBorrar`, pero `ConsultaViewModel.eliminarLaboratorio(id)` y `HcRepository.eliminarLaboratorio`
    siguen sin mirar el id (un `hcc-l…` borrado por otra ruta quedaría `dirty = 1` con `activo = 0`; hoy no hay otra ruta). **No se
    tocó:** estudios/exámenes/interconsultas (`hcc-r…`): el plan no los nombra y la consulta solo edita sus propias entradas
    (`observarRegistrosDeConsulta(consultaId, …)`), que nunca son `hcc-r…` porque una consulta de la web no se edita.

Al terminar la tanda: validación del reviewer y MEMORY.md.

## Tanda E7 — App III: pantalla

Orden de la tanda (D6, 2026-10-29): **E7.1, E7.2, E7.3, E6.5, E7.4**. E7.1 a E7.3 no registran el backend: se comprueban con
tests de lógica pura, con compilación y con revisión de código (la comprobación visual es de E8.3), y los tests de
`HcRepository`/`ConsultaViewModel` usan un backend falso inyectado, como los de E5. E6.5 (registro en `DataModule`) cierra la
tanda de código y E7.4 refresca el `devWeb` ya con el backend registrado.

- [x] **E7.1. Lógica pura de la lista y de los avisos.** RF-43 a RF-46, RF-63 — **S** — Toca API Laravel: no
  - Tests primero: `commonTest` `ConsultaWebTest`: `agruparConsultas` separa "abiertas en la web", "consulta en curso"
    (solo local), "anteriores" (cerradas, con origen) y "solo en este dispositivo"; la etiqueta de ajena con y sin nombre;
    una consulta marcada "ya no figura" **sigue en su grupo** (no se oculta ni pasa a otro) y lleva la etiqueta;
    `AvisosLecturaTest`: textos de `omitidas`, `no_leidas`, sin vínculo, abierta en la web y "Ya no figura en la web"
    (consulta, sección y fila).
  - Código: `core/data/.../hcgeneral/AvisosLectura.kt` y el agrupador (en `LecturaClinica.kt` o archivo propio).
  - Hecho cuando: ambos tests en verde (antes, en rojo).
  - **Hecho (2026-10-30, tanda A encadenada).** Código (todo puro, `core/data/.../hcgeneral`, sin dependencias ni migraciones, sin tocar
    pantallas ni servidor): `ConsultaWeb.kt` (`agruparConsultas`, `ConsultaDeLaLista`, `GruposDeConsultas`, `OrigenConsulta`,
    `Consulta.origen()`, `Consulta.idWeb()`, `nombresDeMedicosWeb`), `AusenciasEnLaWeb.kt` (`AusenciasEnLaWeb`, `ItemAusente`, `itemAusente`) y
    `AvisosLectura.kt` (los textos como constantes, `extrasDeLectura`, `avisosDeLaHistoria`, `avisosDeConsulta`, `avisoNoLeidas`, `notaPerfil4`).
    Tests (`commonTest`, 51): `ConsultaWebTest` (20), `AvisosLecturaTest` (22; los textos fijos TAL CUAL) y `AusenciasEnLaWebTest` (9).
    **Rojo previo:** `:core:data:compileTestKotlinJvm` fallaba con 350 errores de compilación (`Unresolved reference`), solo en los tres
    archivos nuevos. **Verde:** `:core:data:jvmTest --rerun` completo 1078 tests (1027 + 51), 0 fallas, 0 errores, 0 salteados (dos corridas
    completas); `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila. Mutaciones (revertidas, las tres a la vez; las detectan 6 tests):
    desempatar por id como texto, sacar de la lista a la consulta marcada y sumar las cargas sin fecha a las consultas omitidas.
    **Qué consume E7.2/E7.3:** (a) `agruparConsultas(consultas, medicoSesionId, nombresMedicos, ausencias)` → `GruposDeConsultas(abiertasEnLaWeb,
    enCurso, anteriores)` de `ConsultaDeLaLista(consulta, origen, propia, autor, cargadaPor, ausenteDesde)` con `soloLectura`, `abiertaEnLaWeb`,
    `ausente` y `etiquetas` (origen, «Ya no figura en la web», «Cargada por …»); `soloEnEsteDispositivo` es una vista de `anteriores`. (b)
    `extrasDeLectura(filas de paciente_extra)` → `ExtrasDeLectura(omitidasConsultas, cargasSinFecha, noLeidas por id web, ausencias)`;
    `avisosDeLaHistoria(vinculado, lectura)` y `avisosDeConsulta(consulta, medicoSesionId, nombresMedicos, lectura)`. (c) `AusenciasEnLaWeb`
    (`consulta`, `seccion`, `campo`s, `contenido`, `carga`, `fila`, `pendiente`, `antecedente`, `items()`) con el id local de lo que se muestra.
    (d) `notaPerfil4(antecedentes)` y `SECCION_DE_LA_NOTA_PERFIL4` (= `antecedentes_familiares`).
    **Textos (de la spec y del plan, tal cual):** «Ya no figura en la web» (RF-63), «Esta consulta ya no figura en la web: lo que ves es lo último
    que se trajo», «Este paciente todavía no está vinculado con la historia clínica de la web» (RF-49), «Abiertas en la web (solo lectura)»,
    «Consulta de la web — solo lectura», «Abierta en la web: lo pendiente de confirmar no se ve hasta que se cierre», «Web», «Solo en este
    dispositivo» y «Solo en este dispositivo (no se envía a la web)» (Q14), «Cargada por <nombre>» / «Cargada por otro médico», «N consultas de
    la web de un tipo que esta versión no muestra», «La web tiene <adjuntos PDF, audio, …> que esta versión no muestra». **Textos que NO están en la
    spec ni en el plan** (pedir que el usuario los revise): (1) el aviso de D1, «N cargas de laboratorio/peso sin fecha no se muestran», sale de la
    descripción de la tarea (la spec solo dice «se cuenta en el aviso»); (2) el singular de las dos líneas con cantidad («1 consulta de la web de un
    tipo…», «1 carga de laboratorio/peso sin fecha no se muestra»); (3) los nombres de `no_leidas` (`documentos` = «adjuntos PDF», `diagnosticos` =
    «diagnósticos», `antecedentes_personales` = «antecedentes personales», de RF-43) y la unión «A, B y C»; un id que el servidor mande y la app no
    conozca se nombra tal cual vino; (4) «Cargada por otro médico» para el caso sin nombre (el plan dice «"Cargada por <nombre>" (o "otro médico")»).
    **Decisiones que la spec no fijaba (para revisar):** (1) **dos avisos y no uno**: las consultas omitidas de la lista (`hcc_omitidas`:
    foto + otro + sin_fecha) son consultas; las cargas sin fecha de `hcc_omitidas_laboratorio` son cargas. La nota de E6.4 decía «E7.1 suma las dos
    cuentas»; sumarlas daría «N consultas de un tipo que esta versión no muestra» con cargas adentro, así que cada una tiene su línea y no se
    duplica ninguna. (2) El orden es por `fecha` descendente y, dentro del mismo día, el orden en que llegan las consultas (el de la base, que
    equivale a `(created_at, id)` porque E6.3 las escribe de la más vieja a la más nueva): la base no guarda `created_at` y comparar ids como texto
    pondría `hcc-c9` antes que `hcc-c10`. (3) «Anteriores» mezcla cerradas de la web y del dispositivo con su etiqueta, como dice el plan;
    `soloEnEsteDispositivo` es solo una vista de ese grupo. (4) `enCurso` es una lista (normalmente una): una base migrada de hepatología puede tener
    dos abiertas locales y ninguna se oculta. (5) «Cargada por …» solo en las consultas de la web (el nombre de un médico solo se guarda para las
    `hcc-m…`). (6) Las marcas de una consulta del dispositivo no se muestran nunca. (7) Las anuladas no se listan. (8) La nota del perfil 4 solo se
    muestra si el antecedente `familiares/nota_perfil4` existe, está activo y no está en blanco; la pantalla pone el título (la spec no fija uno).
    **Para E7.2/E7.3:** «Ver más» (50 por vez) y el filtro de lo visible no están acá (la lista llega completa, hasta 20 páginas); `notaPerfil4` no saca
    la nota de la lista de casillas (revisar que el renderer de `antecedentes_familiares` no muestre `nota_perfil4` como casilla); el pendiente de la
    web no entra en ningún aviso (Q16-A: el contador no cambia). Duda para el usuario: RF-43 pide avisar «que se ve en la web»; el texto del plan
    («La web tiene … que esta versión no muestra») lo da a entender pero no lo dice.

- [x] **E7.2. Historia del paciente.** RF-38, RF-44, RF-46, RF-49, RF-56, RF-63 — **M** — Toca API Laravel: no
  - `HistoriaClinicaViewModel` / `HistoriaClinicaScreen`: grupos de E7.1 (la "Consulta en curso" con Continuar y Anular
    solo para la local), etiquetas "Web" / "Solo en este dispositivo" / "Cargada por …" / "Ya no figura en la web" (la
    consulta marcada se sigue listando y abriendo), "Ver más" mientras `hay_mas`,
    "Actualizar" (fuerza lista e historial), el fallo con `motivoHc` en la tarjeta que ya existe, el aviso de paciente
    sin vínculo y la línea de `omitidas`.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` sin errores;
    revisión de código: ninguna acción de esta pantalla llama a escritura sobre una consulta de la web; la
    comprobación visual queda para E8.3.
  - **Hecho (2026-10-30, tanda A encadenada).** Código en `core/data` (todo puro salvo dos métodos de repositorio, sin dependencias ni
    migraciones, servidor sin tocar): `hcgeneral/VistaDeLaHistoria.kt` (`vistaDeLaHistoria` → `VistaDeLaHistoria(abiertasEnLaWeb, enCurso,
    anteriores, totalAnteriores, ocultas, avisos)` con `hayMas`; `CONSULTAS_POR_VEZ = 50`, `masConsultas`, `vinculadoConLaWeb`, `TEXTO_VER_MAS`,
    `TEXTO_ACTUALIZAR`), `hcgeneral/MotivoHc.kt` (`motivoDeLaLectura`, `TEXTO_DEMASIADOS_PEDIDOS`), `repos/Refrescable.kt` (interfaz mínima:
    `traerConsultas(pacienteId, medicoId, forzar)`; `HcClinicaBackend` la implementa y `HcBackend` no cambia), `HcRepository`
    (`puedeActualizar`, `actualizarConsultasRemotas` → `null` si el backend no es `Refrescable`, `observarNombresDeMedicosWeb`), consulta
    `nombresDeMedicosWeb` en `HistoriaClinica.sq` (solo `SELECT`, sin migración) y `PacientesRepository.observarFilasExtra`. En `features/hc`:
    `HistoriaClinicaViewModel` (`soloLectura`, `puedeActualizar`, `vista`, `leyendo`, `actualizar`, `reintentar`, `verMas`; `anular` ignora una
    consulta de la web), `HistoriaDeLaWeb.kt` (composables nuevos), `HistoriaClinicaScreen` y `AntecedentesSeccion` (nota del perfil 4).
    Tests (`commonTest`/`jvmTest`, 31 nuevos): `VistaDeLaHistoriaTest` (17), `MotivoDeLaLecturaTest` (8), `HcRepositoryActualizarTest` (6, uno con
    el backend de verdad: abrir dos veces = 2 pedidos, «Actualizar» = 4) y uno en `especialidades/clinica` (la nota del perfil 4 no es una casilla
    del catálogo). **Rojo previo:** `:core:data:compileTestKotlinJvm` con 59 errores de compilación (`Unresolved reference`), solo en los tres
    archivos nuevos. **Verde:** `:core:data:jvmTest --rerun` completo 1109 tests (1078 + 31), 0 fallas, 0 errores, 0 salteados;
    `:especialidades:clinica:jvmTest --rerun` 33 (32 + 1); `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev`
    compilan. Ningún test existente cambió de resultado ni se tocó.
    **Cómo se ve (solo con un backend de solo lectura registrado, o sea después de E6.5; hoy `clinica` no lo tiene y la pantalla es la de
    siempre, igual que pediatría):** cabecera y pendientes como hoy; tarjeta «No se pudo traer la historia clínica» (la que ya existía) con el
    texto de `motivoDeLaLectura` y un botón «Reintentar»; los avisos de `avisosDeLaHistoria` (sin vínculo, consultas omitidas, cargas sin fecha;
    cada uno con ícono y `liveRegion`); «Actualizar»; «Consulta en curso» (una tarjeta por cada abierta del dispositivo, con «Solo en este
    dispositivo (no se envía a la web)», Continuar y Anular) o «Nueva consulta»; «Abiertas en la web (solo lectura)» si hay; «Consultas anteriores
    (N)» con la web y el dispositivo juntas, cada fila con fecha, tipo · edad, etiquetas («Web» / «Solo en este dispositivo», «Ya no figura en la
    web», «Cargada por …») y «Ver»; «Ver más» mientras `ocultas > 0`. Todo botón nuevo tiene 48 dp como mínimo y los renglones de botones son
    `FlowRow`. **«Actualizar» llega así:** `HistoriaClinicaViewModel.actualizar()` → `HcRepository.actualizarConsultasRemotas` → el backend, solo si
    es `Refrescable` (hoy `HcClinicaBackend`): `traerConsultas(…, forzar = true)`, que se salta los 60 s de la lista y del historial; pediatría y
    las demás no tienen el botón y `HcBackend` no se tocó. «Reintentar» es `traerConsultasRemotas` (sin `forzar`): si falló la lista vuelve a
    pedirla; si falló solo el historial repite solo ese. **Decisiones que la spec no fijaba (para revisar):** (1) **«Ver más» es sobre lo ya
    traído** (la lista llega completa, hasta 20 páginas, E6.3): `hay_mas` del servidor no interviene; el tope de 50 cuenta las abiertas en la web y
    las cerradas juntas por fecha (las del dispositivo cerradas también) y la «Consulta en curso» del dispositivo siempre se ve y no cuenta; una
    abierta en la web muy vieja puede quedar detrás de «Ver más». (2) **El `429` tiene texto propio en `motivoDeLaLectura`, no en `motivoHc`:** el
    plan pone ese caso en E6.5 (`MotivoHc`), pero `motivoHc` lo usa también la sección Enfermedades (003) y pediatría, y cambiarlo ahora movía su
    comportamiento; E6.5 puede pasarlo a `motivoHc` (con `TEXTO_DEMASIADOS_PEDIDOS`) o dejarlo. (3) Los errores que no son de la API dicen su mensaje
    («error» si no hay). (4) La nota del perfil 4 se muestra en `AntecedentesSeccion` (solo la sección `antecedentes_familiares`, solo para leer,
    también en una consulta del dispositivo) con el rótulo «Nota» y cuenta como contenido (una sección fuera del perfil, solo para leer, ya no sale
    vacía si solo tiene esa nota); como la HC general no tiene `notaLibre` en familiares, ese rótulo no choca con otro. (5) El aviso RF-44
    («Abierta en la web: lo pendiente…») no está en la lista, solo en la consulta (E7.3), como dice el plan. (6) `vista` es `null` mientras se lee
    la base y se muestra «Cargando la historia clínica…». **Textos:** todos de la spec/plan o de E7.1 salvo los de abajo. Nuevos para revisar:
    «demasiados pedidos seguidos. Intentá de nuevo en un momento.» (RF-56 y el plan dicen «demasiados pedidos» / «demasiados pedidos seguidos»; la
    segunda oración copia la de «sin conexión»); el rótulo «Nota» de la nota del perfil 4 (la spec no fija título). Ya existentes: «Reintentar»
    (`TEXTO_REINTENTAR`, 003), «Cargando…» (mientras lee, en el lugar de «Actualizar»), «Continuar», «Anular», «Ver», «Nueva consulta», «Consultas
    anteriores», «Todavía no hay consultas cerradas». **Para la prueba manual (E8.3):** que no haya `nota_perfil4` como casilla; el `FlowRow` con
    360 dp de ancho (botones sin recortes); «Ver más» con más de 50 consultas (de a 50); «Actualizar» deshabilitado mientras lee y sin pedido
    antes de 60 s al abrir dos veces; sin conexión y `429` (mensaje, «Reintentar» a mano, lo traído sigue visible); lector de pantalla (avisos y
    tarjeta de error se anuncian; la fila se lee junta); el foco después de «Reintentar» (la tarjeta desaparece si sale bien); que una consulta de
    la web abierta no ofrezca Continuar ni Anular.

- [x] **E7.3. Consulta de la web.** RF-39, RF-43, RF-44, RF-45, RF-63 — **M** — Toca API Laravel: no
  - `ConsultaViewModel` (`ConsultaUi.soloLecturaWeb`, `soloLectura` la incluye) y `ConsultaScreen`: banner "Consulta de la
    web — solo lectura"; abierta en la web → aviso de RF-44; `no_leidas` → aviso nombrando qué; consulta, sección o fila
    marcada → "Ya no figura en la web" con su contenido a la vista; sin "Guardar y cerrar" ni
    "Reabrir para editar"; las secciones, el cuerpo y lo de fuera del perfil como hoy (spec 001). La consulta no manda
    nada: `onCleared` y `empujarTodo` quedan sin efecto por el candado de E5.1.
  - Hecho cuando: ambos módulos compilan sin errores; `.\gradlew :core:data:jvmTest` completo en verde; revisión de
    código: ninguna ruta de esta pantalla puede editar, cerrar, reabrir ni anular una consulta con `remoto_id`.
  - **Hecho (2026-10-30, tanda A encadenada).** Decisión del usuario sobre la duda de E6.6: además de la pantalla, **guardas
    defensivas en `HcRepository`**. Código en `core/data` (sin dependencias, sin migraciones, servidor sin tocar):
    `hcgeneral/SoloLecturaDeLaWeb.kt` (puro: `esIdDeLaWeb`, `Consulta.esSoloLecturaWeb(backendSoloLectura)` = id `hcc-…` siempre, o
    `remoto_id` con backend de solo lectura; `edicionDeConsulta` → `EdicionDeConsulta(soloLectura, puedeCerrar, puedeReabrir)`;
    `etiquetaDeEstadoEnLectura`; `puedeEditarRegistro` / `puedeEditarAntecedente` / `pendientesDeLaWeb`; `avisosDeLaPantallaDeConsulta`;
    `marcaDeSeccion`; `LecturaDeLaConsulta` y `mensajeDeLaLecturaDeConsulta`), `AusenciasEnLaWeb.antecedenteDeClave`,
    `AvisosDeConsulta.NINGUNO` y las guardas de `HcRepository`. **Guardas:** `guardarConsulta`, `cerrarConsulta`, `reabrirConsulta`,
    `anularConsulta`, `guardarValor`/`guardarSeccion`, `guardarExamenFisico`, `guardarAntecedente`, `guardarRegistro`/`eliminarRegistro`,
    `guardarLaboratorio`/`eliminarLaboratorio`, `guardarPendiente`, `guardarArchivo`/`eliminarArchivo`, `asignarDiagnostico` y
    `guardarVacuna` devuelven `Boolean` (`true` = escribió; `false` = no escribió: era de la web, `hcc-…` o consulta con `remoto_id` de
    un backend de solo lectura, o no existía) y, si es de la web, no tocan la base. Lo del dispositivo y pediatría no cambian. En
    `features/hc`: `ConsultaViewModel` (`ConsultaUi.soloLecturaWeb`/`avisos`/`ausencias`/`edicion`; `lecturaWeb`, `reintentarLectura`; todo
    mutador, `onCleared` y `empujarTodo` salen sin hacer nada si `soloLecturaWeb`; el resto marca `marcarSucia` solo si el repositorio
    escribió), `ConsultaDeLaWeb.kt` (composables nuevos), `ConsultaScreen` y las secciones de laboratorio, registros, estudios y
    antecedentes. Tests: `SoloLecturaDeLaWebTest` (24, `commonTest`) y `HcRepositoryGuardasDeLaWebTest` (14, `jvmTest`, base temporal:
    cada operación sobre lo de la web se rechaza y el volcado de las tablas queda idéntico y sin filas sucias; lo local y pediatría
    siguen). **Rojo previo:** `:core:data:compileTestKotlinJvm` con 160 errores de compilación (`Unresolved reference`), solo en los dos
    archivos nuevos; mutación (revertida): sin cuatro guardas (`eliminarLaboratorio`, `cerrarConsulta`, `guardarPendiente`,
    `guardarAntecedente`) fallan 7 de los 14 tests. **Verde:** `:core:data:jvmTest --rerun` completo 1147 tests (1109 + 38), 0 fallas, 0 errores,
    0 salteados; `:especialidades:clinica:jvmTest --rerun` 33; `:features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs
    -Pentorno=dev` compilan. Ningún test existente cambió.
    **Cómo se ve la consulta de la web (hoy inalcanzable hasta E6.5):** abre siempre en solo lectura (`SeccionContext.soloLectura = true`
    aunque esté abierta allá). Arriba de las pestañas, los avisos de `avisosDeConsulta`, cada uno con ícono y `liveRegion`: «Consulta de la
    web — solo lectura», «Cargada por …» (de otro médico), «Esta consulta ya no figura en la web: …» (marcada), «Abierta en la web: lo
    pendiente de confirmar no se ve hasta que se cierre» y «La web tiene … que esta versión no muestra»; la cabecera dice «Solo lectura» si
    está abierta allá y «Cerrada» si no. Sin «Guardar y cerrar», sin «Reabrir para editar», sin fecha editable, sin «Resuelto», sin
    agregar/borrar/editar en secciones, laboratorio, registros, estudios y adjuntos, sin «Elegir filas». «Cargando…» mientras lee (se ve lo
    ya traído); si falla, la tarjeta «No se pudo traer la historia clínica» con el motivo de `motivoDeLaLectura` nombrando la HC, «Lo que
    está cargado en este dispositivo se sigue viendo y nada se pierde.» y «Reintentar» (48 dp, a mano); el `404 consulta_no_encontrada` no
    muestra tarjeta: lo dice el aviso de la marca. **Marcas «Ya no figura en la web»:** consulta (aviso), sección (chip sobre la tarjeta,
    con el nombre de los campos si el catálogo los conoce), carga de laboratorio (en su columna), estudio (el de la consulta y los previos),
    registro/examen/interconsulta, pendiente y antecedente (casilla y nota); todo se sigue viendo entero. **En una consulta del
    dispositivo de la HC general:** igual que hoy, salvo que las filas de la web (cargas sin tacho, registros sin «Editar»/«Quitar»,
    antecedentes de la web solo para leer, el pendiente de la web con el chip «Web» y sin «Resuelto») no se tocan, y el cierre lista el
    pendiente de la web aparte, solo para leer, con el campo de texto solo para los locales.
    **Decisiones que la spec no fijaba (para revisar):** (1) **Antecedentes familiares de la web en una consulta del dispositivo pasan a
    solo lectura** (la guarda de `guardarAntecedente` rechaza la fila `hcc-a…`; dejarlos editables en pantalla sería una casilla que no
    guarda): cambia respecto de hoy solo para esas filas. (2) **«Elegir filas» del laboratorio no se ofrece en una consulta de la web** y
    `guardarPreferencia` sale sin hacer nada ahí: guarda `medico_preferencia` con pendiente de envío y la condición era que la pantalla no
    marque nada; se sigue eligiendo desde cualquier consulta del dispositivo. (3) Las guardas devuelven `Boolean` en vez de lanzar: la pantalla
    ya no ofrece nada de esto y una excepción tiraría el autoguardado; `false` incluye «no existía». (4) La regla `hcc-…` vale con cualquier
    backend (ese prefijo solo lo usa esta lectura); `remoto_id` solo cuenta si el backend de la especialidad es de solo lectura (pediatría
    sigue editando sus consultas con `remoto_id`). (5) `traerConsulta` no tiene espera propia (la de 60 s es de la lista y del historial): cada
    apertura de una consulta de la web es **un** pedido, y el VM no reintenta solo. (6) Las «Consultas previas» del motivo no llevan marca por
    entrada (la consulta a la que pertenece sí la lleva en su fila de la lista). (7) Si ni la marca del `404` pudo escribirse (E6.4b lo anota
    y conserva el error), no hay aviso. (8) `Enfermedades` (spec 003) no se tocó: sus cambios van por su propia API y no marcan nada en la HC.
    **Textos: ninguno nuevo.** Usados: los de la spec/plan y las constantes de E7.1/E7.2 (`BANNER_CONSULTA_DE_LA_WEB`,
    `CONSULTA_ABIERTA_EN_LA_WEB`, `CONSULTA_YA_NO_FIGURA_EN_LA_WEB`, `YA_NO_FIGURA_EN_LA_WEB`, `etiquetaCargadaPor`, `avisoNoLeidas`,
    `ETIQUETA_WEB`, `TEXTO_REINTENTAR`, `motivoDeLaLectura`) y literales que ya existían («Solo lectura», «Cerrada», «Cargando…», «No se pudo
    traer la historia clínica», «Lo que está cargado en este dispositivo se sigue viendo y nada se pierde.»). **Duda:** el texto del cierre
    («Dejalo vacío si no hay pendientes») sigue igual aunque haya un pendiente de la web que no se va; no escribí uno nuevo.
    **Para la prueba manual (E7.4/E8.3):** que cada aviso se anuncie con lector de pantalla; «Reintentar» (foco al volver) sin conexión y con
    `429`; borrar una consulta en la web y abrirla (aviso + contenido a la vista, sin tarjeta); un solo `GET consultas/{id}` por apertura en
    el log de Apache y ningún método distinto de `GET`; marcas de sección, carga, estudio, pendiente y antecedente con datos reales de la
    copia local (vaciar/borrar allá); 360 dp (marcas con campos largos, columna de laboratorio con la marca en dos líneas, avisos largos);
    una consulta local de clínica: sin «Resuelto» en el pendiente de la web, cierre con el pendiente de la web aparte, antecedente de la web
    solo para leer, cargas sin tacho.

- [x] **E6.5. Registrar el backend de solo lectura.** RF-38, RF-47, RF-56, RF-60 — **S** — Toca API Laravel: no
  - **Movida aquí (D6, 2026-10-29, usuario):** va después de E7.3 y antes de la tanda V. Motivo: `cerrarConsulta`,
    `reabrirConsulta`, `anularConsulta` y `guardar*` de `HcRepository` no distinguen consultas de la web hasta E7.3; con el
    backend registrado antes habría una ventana en que la pantalla dejaría editar una consulta de la web. Alcance y «Hecho
    cuando» no cambian.
  - **Condición previa verificable: E5 completa (E5.1 a E5.4 hechas y en verde) y E7.3 hecha** (y E6.1 a E6.4b, para que lo
    que se registra ya no pueda borrar nada de lo traído; E6.6 y E7.1 a E7.2, para que la pantalla ya distinga lo de la web).
  - `DataModule`: `CodigosHc.GENERAL -> HcClinicaBackend(get(), HcApiSoloLectura(cliente), get(), …)` en `HcBackends`;
    se actualizan los comentarios (ya no dice "la general no tiene `HcBackend`"); `MotivoHc`: caso `429`
    ("demasiados pedidos seguidos") con su test en `MotivoHcTest`. (E7.2 ya dejó ese texto en `motivoDeLaLectura`, que usa la historia del
    paciente, sin tocar `motivoHc`: acá se decide si `motivoHc` lo adopta para las demás pantallas.)
  - Hecho cuando: `.\gradlew :core:data:jvmTest` completo en verde; `:composeApp:compileKotlinWasmJs -Pentorno=dev` compila;
    con el `devWeb` contra la copia local, abrir la historia de un paciente de clínica no manda ningún pedido que no sea
    `GET` (registro de accesos de Apache) y las consultas viejas sucias siguen con `dirty = 1`. (Para esa comprobación el
    `devWeb` se refresca con el comando de E7.4; E7.4 después repite la compilación completa.)
  - **Hecho (2026-10-30, tanda A encadenada).** Sin dependencias nuevas, sin migraciones, servidor sin tocar. **Código:** una sola
    entrada en `DataModule` (`CodigosHc.GENERAL -> HcClinicaBackend(get(), HcApiSoloLectura(cliente), get<PacientesHcRepository>())`
    dentro de `HcBackends`), con el **mismo** `HcApiClient` por código que usan `ConfigHcRepository`, `PerfilesHcRepository`,
    `PacientesHcApis`, `EnfermedadesRepository` y el login (el token que `AuthRepository` le pone es el que viaja). Sin URL de la HC
    general (`HC_URLS` sin `clinica`, o en blanco) no se registra y queda como antes. Comentarios de `DataModule` actualizados (ya
    no dice «la general no tiene `HcBackend`»). **Tests:** `DataModuleClinicaTest` (8, `jvmTest`, `dataModule` real de Koin + base
    temporal + `HttpServer` local que anota método, ruta, query, cuerpo y `Authorization`): clínica registrada de solo lectura y
    pediatría igual (`false`, `HcPediatriaBackend`); sin URL / URL en blanco no se registra (`tieneApi` falso, `traerConsultasRemotas`
    y `actualizarConsultasRemotas` devuelven `null`, cero pedidos); el cliente es el del login (`Authorization` con el token que se le
    pone al cliente de `HcApiClients`); flujo completo sin pantalla (`traerConsultasRemotas` = `GET consultas` + `GET
    pacientes/5001/historia`; repetirlo dentro del minuto no pide nada; `traerConsultaRemota` = un `GET consultas/9003`;
    `actualizarConsultasRemotas` = otra vez lista + historial) con una consulta local sucia que queda **idéntica** (mismas filas
    `dirty` antes y después, y la lectura no deja nada nuevo `dirty`); el servidor no ve ningún `POST`/`PUT`/`DELETE`/`PATCH`, ningún
    `GET` con cuerpo y ninguna ruta fuera de `consultas`, `consultas/{id}` y `pacientes/{id}/historia`; paciente sin vínculo: 0
    pedidos (tampoco `pacientes/resolver`) y la base idéntica; el motor de envío con la base sucia (una consulta local con valor,
    examen físico, antecedente y registro, más una vieja `dirty`): `HcApiSync` armado con los backends REALES del módulo y el que
    arma Koin (`empujarTodo`, `empujarYa`, `marcarSucia` con y sin espera) = cero pedidos, nada programado y la base idéntica;
    licencia de un médico solo de clínica: mismo resultado (`true`) y misma fila que sin registrar clínica, cero pedidos, y
    `avisosDeLicencia` vacío. **`HcBackendsSoloLecturaTest`** (único test existente tocado): ahora exige que `clinica` esté registrada
    (antes solo la decidía si aparecía) con `true`; `pediatria` con `false`; cualquier otro código sigue fallando hasta decidirlo.
    **Rojo previo:** 6 de los 9 tests (los 5 de registro/flujo/motor de `DataModuleClinicaTest` y `HcBackendsSoloLecturaTest`; «sin URL»,
    «URL en blanco» y «licencia» pasaban a propósito: fijan lo que ya era así). **Verde:** `:core:data:jvmTest --rerun` completo 1155
    (1147 + 8), 0 fallas, 0 errores, 0 salteados; `:especialidades:clinica:jvmTest --rerun` 33; `:features:hc:compileKotlinJvm`,
    `:features:admin:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs -Pentorno=dev` (klib) compilan. **Ningún camino de la app
    queda con un envío posible a clínica** (`git grep` de los call-sites, código de producción): (1) `HcApiSync.drenar` es el único que
    llama a `asegurarConsulta`/`enviarSecciones`/`enviarRegistros`/`borrarArchivo`/`subirArchivo`/`enviarExamen`/`enviarEstado` y a
    esos métodos solo se llega por `backends[…]?.takeUnless { it.soloLectura }` (`drenar` es por donde pasa todo, también
    `empujarTodo` y los reintentos); antes de eso `marcarSucia`/`empujarYa` salen por `esDeSoloLectura(consulta)` y
    `consultasDeAntecedentesPendientes` salta los backends de solo lectura (E5.1), y si algo los llamara igual, `HcClinicaBackend`
    lanza `UnsupportedOperationException` sin tocar la red; (2) `AdminRepository.guardarLicencia`/
    `sincronizarLicencias` usan `destinosDeLicencia` (sin los de solo lectura, E5.3): el médico solo de clínica sigue en `true`;
    (3) `HcRepository` solo llama a `traerConsultas`, `traerConsulta`, `bajarArchivo` (clínica: `null` sin red) y `avisoDeLicencia`
    (clínica: `null` sin red); (4) `PacientesHcRepository.resolver` (`POST pacientes/resolver`) es el vínculo/cartera de la 002 parte
    1, de iniciativa del médico, ya existía y registrar el backend no lo alcanza (la lectura nunca lo llama, RF-49); (5)
    `SyncEngine` **no cambió** (otro canal, hacia turnos; sus rutas siguen dando 404 como antes): no recibe `HcBackends`. **Decisión
    cerrada (E7.2, pendiente en el texto de arriba):** el `429` se queda con su texto en `motivoDeLaLectura` y `motivoHc` **no
    cambia** (sigue sin caso `429`, para no alterar Enfermedades ni pediatría); por eso este paso no toca `MotivoHcTest`. **Cambios de
    comportamiento a mirar:** (a) `PacienteDetalleViewModel` ya pide a todas las HC con API: al abrir la ficha de un paciente
    vinculado con la HC general salen la lista (50 por pedido) y el historial (throttle de 60/min por IP; el `429` se traga con
    `runCatching`); uno sin vínculo no pide nada; no filtra por las HC habilitadas del médico (hoy igual para pediatría). (b) Sin
    URL de clínica en un entorno (p. ej. release sin `salud360.entorno.release.hc.clinica`) no se registra: el despliegue a producción
    (E9) no debe llevar este commit antes de que producción tenga las rutas (D6 y nota del traspaso). **No hecho aquí (a propósito):**
    la comprobación de «Hecho cuando» contra el `devWeb` con el log de Apache (necesita E7.4 y navegador, no se abrió ninguno; la
    reemplaza el servidor local de los tests) y `wasmJsDevelopmentExecutableCompileSync`. **El `devWeb` que corre en :8080 no
    cambia:** sirve el último ejecutable de desarrollo (`composeApp/build/compileSync/wasmJs/main/developmentExecutable`, del
    2026-10-27, anterior a E5) y esta tarea solo compiló el klib y los tests, que no reescriben ese ejecutable; sigue sin backend de
    clínica y sin las pantallas de E7 hasta que E7.4 corra `wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` (con las mismas
    `-P` del `devWeb`) y se recargue la página; la base local es v9 y E6.5 no la migra.

- [x] **E7.4. Refresco del `devWeb` y compilación completa.** RF-38 a RF-63 — **S** — Toca API Laravel: no
  - **Condición previa: E6.5 hecha** (D6): el refresco tiene que llevar el backend ya registrado.
  - `.\gradlew :core:data:jvmTest`, `:features:hc:compileKotlinJvm`, `:composeApp:compileKotlinWasmJs -Pentorno=dev` y
    `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` (sin matar el `devWeb` del usuario).
  - Hecho cuando: todo en verde y el `devWeb` refrescado (con esquema v9, sin cambios de base).
  - **Hecho (2026-10-30), solo infraestructura: ningún cambio de código.** Antes: `devWeb` escuchando en :8080 (200), Apache y MySQL
    del XAMPP vivos, ~2,2 GB libres de 7,4 GB (la memoria bajó a ~0,4 GB durante la compilación, sin fallos). El wasm servido era del
    2026-10-27 (42 778 797 bytes). **Compilación:** `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev`, un
    solo Gradle, sin `--stop` ni matar procesos: `BUILD SUCCESSFUL in 4m 30s`, código de salida 0 (34 de 157 tareas ejecutadas). Sin
    `-P` de URL (las toma `local.properties`). **URLs confirmadas** en el `Entornos.kt` generado, antes de dar la compilación por
    buena: `TURNOS_URL = http://localhost/TurnosOnlineBB-salud360/public/index.php` y `HC_URLS["clinica"] =
    http://localhost/HClinica-salud360/public/index.php` (la de pediatría es el MAMP `{host}:8888` de `gradle.properties`; ninguna de
    producción). **Resultado:** `composeApp/build/compileSync/wasmJs/main/developmentExecutable/kotlin/salud360.wasm` = 43 528 858 bytes,
    2026-10-30 15:21:34. El `devWeb` de :8080 (mismo proceso del usuario, sin tocarlo) lo sirve ya: el `salud360.wasm` descargado de
    `http://localhost:8080/` es **idéntico byte a byte** (mismo SHA-256) y contiene «Abiertas en la web (solo lectura)» y «Ya no figura
    en la web»; el índice y el `salud360.js` dan 200. **Sin migraciones nuevas:** la última es `8.sqm` (esquema local v9); desde `8.sqm`
    solo cambió `HistoriaClinica.sq` con tres consultas nuevas (`consultaAbiertaLocal`, `laboratoriosDePacienteTodos`,
    `pendientePorId`), sin tocar tablas. **No se abrió el navegador ni se cargó la app.** **Alcance (aviso):** esta corrida es la
    compilación del ejecutable de desarrollo (que compila `composeApp` y todos sus módulos wasm); NO se repitieron
    `:core:data:jvmTest` ni `:features:hc:compileKotlinJvm` (el código no cambió desde E6.5/E8.1, última corrida: 1161 tests de
    `core:data` en verde en E8.1). Falta la prueba con la app real (E8.2 y E8.3), que necesita al usuario con el navegador.

Al terminar la tanda: validación del reviewer y MEMORY.md.

## Tanda E8 — Validación local (contra la copia servida; nada contra producción)

Condición previa de la tanda (D6): E6.5 y E7.4 hechas (E6.5 va después de E7.3). E8.1 no usa la app registrada (arma
`HcClinicaBackend` a mano); E8.2 y E8.3 sí.

- [x] **E8.1. E2E de lectura.** RF-38 a RF-42, RF-52 a RF-54 — **M** — Toca API Laravel: no
  - `core/data/src/jvmTest/.../repos/HcClinicaConsultasE2ETest.kt`: se saltea sin `salud360.test.hc.clinica`,
    `.clinica.token` y `.clinica.ficha` (se agrega a la lista de `core/data/build.gradle.kts`); sobre SQLite temporal y
    **sin `resolver`** (siembra `hc_paciente_id` en `paciente_extra`): trae la lista y cada consulta del paciente de
    prueba (E3.1) y comprueba cabeceras, textos, formularios, laboratorio con los ids de la app, estudios con historial,
    antecedentes, pendientes, `omitidas` y `no_leidas`; el total de Child-Pugh que calcula la app coincide con `total_puntos`
    de la web; nada `dirty`; traer dos veces no duplica. El KDoc trae el comando y el SQL de limpieza, sin credenciales.
  - Comando: `.\gradlew :core:data:jvmTest --rerun --tests '*HcClinicaConsultasE2ETest*'
    "-Psalud360.test.hc.clinica=http://localhost/HClinica-salud360/public/index.php"
    "-Psalud360.test.hc.clinica.token=<token de prueba>" "-Psalud360.test.hc.clinica.ficha=<id de la ficha de prueba>"`.
  - Hecho cuando: el E2E en verde contra la copia servida; sin las propiedades, `.\gradlew :core:data:jvmTest` en verde y el
    E2E se saltea; token revocado y `salud360_token_cache` vacía.
  - **Hecho (2026-10-30).** `HcClinicaLecturaE2ETest` (no `HcClinicaConsultasE2ETest`: el nombre lo pidió el usuario) en
    `core/data/src/jvmTest/.../repos/`, 6 tests. Usa el `HcClinicaBackend` de verdad (constructor interno, con reloj falso para los 60 s)
    sobre `ConsultasClinicaApiRemota` + `HcApiSoloLectura` + `HcApiClient` reales, SQLite en archivo temporal y `PacientesHcRepository`
    con la API de pacientes prohibida (sembrada la ficha, nunca `resolver`). Se saltea sola si falta cualquiera de **seis** propiedades
    (en `core/data/build.gradle.kts`): `.clinica`, `.clinica.token`, `.clinica.ficha`, y tres más que no estaban en el texto de la tarea:
    `.clinica.ficha.ajena` (paciente fuera de cartera), `.clinica.consultas.ocultas` (ids de d, e, g, separados por coma) y
    `.clinica.consulta.ajena` (la h). Los ids del fixture cambian en cada carga (`ids.sql`) y no se escriben en el repo. Cubre: (a) lista
    (c, b, a, f; `omitidas` 1|1|0; `propia`; ABIERTA/CERRADA), (b) historial (laboratorio general/reducido/peso con ids `hcc-l…`, `2000-01-01`
    fuera y `laboratorio_sin_fecha`, estudios con id por tipo aunque el número se repita, exámenes, interconsulta, motivos, pendiente,
    antecedentes por paciente con la última fila activa), (c) a, b, c y f con secciones y examen físico, `no_leidas` de f = `documentos`,
    (d) d/e/g y una consulta inexistente sembradas en el dispositivo: se marcan «Ya no figura en la web», `GET consultas/{id}` da `404
    consulta_no_encontrada` y no se borra ni vacía nada, (e) `403 fuera_de_cartera` (lista y consulta), `404 paciente_no_encontrado`,
    `401`: un pedido cada uno y la base idéntica, (f) segunda lectura = misma base, 0 pedidos a los 59,999 s, 2 a los 60 s, (g) lo sucio y
    lo creado en el dispositivo ni se pide ni se toca. Los pedidos van espaciados 1,1 s (`throttle:60,1`). Mutadas 6 aserciones (una por
    test): las 6 fallaron y se revirtieron. **Diferencias con el texto de la tarea:** (1) la web no manda `total_puntos` de Child-Pugh,
    así que el test solo comprueba los cinco puntos (suma 9); la clase la calcula `especialidades/clinica`, que `core:data` no ve;
    (2) el texto pedía «cada consulta del paciente», pero solo son pedibles las cuatro de la lista. Servidor: en el registro de Apache,
    solo `GET` de `consultas`, `consultas/{n}` y `pacientes/{n}/historia` (0 métodos distintos), `CHECKSUM` y conteos de las 68 tablas
    iguales (salvo `salud360_token_cache`, vaciada). Una corrida falló por un `request timeout` de 30 s de la copia local (Apache lento,
    como los frenones ya conocidos de esta máquina) y la repetición salió verde. Token: el de la prueba local, sin revocar (no hace falta
    para una copia local; vencido, lo regenera el usuario).

- [ ] **E8.2. Verificación de que no se escribe, con la app real.** RF-47, RF-48, RF-50 — **S** — Toca API Laravel: no
  - Con una base del dispositivo que tenga consultas viejas de la HC general sucias (la de pruebas anteriores del
    usuario o una armada): abrir historias y consultas en el `devWeb` contra la copia local con `suma.sql` antes y
    después; contar las filas sucias del dispositivo antes y después; revisar el registro de accesos.
  - Hecho cuando: `suma.sql` antes = después; las filas sucias del dispositivo son las mismas; el registro de accesos
    no tiene ningún método distinto de `GET` hacia `clinica` en la sesión; resultado anotado en esta tarea.

- [ ] **E8.3. Prueba manual del usuario en `devWeb`.** RF-38 a RF-46, RF-49, RF-56, RF-62, RF-63 — **S** — Toca API Laravel: no
  - Pasos (copia local, médico de prueba con el perfil equivalente al de la web, Q10; el paciente "PRUEBA SALUD360"):
    1. Abrir la historia del paciente: ve las consultas (a), (b) y (c) de E3.1, con origen; no ve (d), (e), (g); ve la línea de
       consultas de tipos no mostrables.
    2. Abrir (a) y compararla con la web **lado a lado**, sección por sección: motivo (con consultas previas), conductas,
       nota, hábitos, diuresis, somnia, examen físico, alimentación (celíaco), antecedentes familiares (dependiente de
       terceros), serologías (los "sin cargar" quedan vacíos), Child-Pugh (clase), los cinco estudios, laboratorio general
       y reducido (albúmina, fósforo, concentración, ácido úrico, B12, "otros"), peso, exámenes complementarios,
       interconsultas y pendientes. Cada valor en la casilla correcta.
    3. Abrir (b): dice que la cargó otro médico y no se puede editar. Abrir (c): "abierta en la web", sin "Continuar"
       ni "Anular", con el aviso de lo pendiente de confirmar. Abrir (f): aviso de que la web tiene un PDF.
    4. Ninguna consulta de la web tiene "Guardar y cerrar" ni "Reabrir". "Nueva consulta" crea una local con la marca
       "Solo en este dispositivo" y no devuelve ninguna de la web.
    5. Cambiar un texto en la web y tocar "Actualizar" (y reabrir la consulta): se ve el cambio en la app. **Vaciar** el
       texto en la web y actualizar: la app **sigue mostrando el texto anterior** con la marca "Ya no figura en la web"
       (Q15 = nunca borrar, RF-63); no se vacía ni se oculta.
    5b. (Al final, después del paso 9, porque cambia la base de la web: el agente da de baja con un `UPDATE` puntual
       una consulta ya traída de la copia local y la restaura por id al terminar.) Actualizar: la consulta **sigue en la
       lista y se abre con todo su contenido**, con la marca "Ya no figura en la web"; no desaparece.
    6. Apagar Apache de la copia local (o modo avión) y volver a abrir: se ve lo traído y el motivo "sin conexión".
    7. Un paciente de la app sin ficha de la web: aviso "todavía no está vinculado", y en `hc_clinica` no aparece una ficha nueva.
    8. En una consulta local, no se puede borrar una carga de laboratorio ni resolver un pendiente que vino de la web.
    9. `suma.sql` antes y después de toda la prueba: igual.
  - Hecho cuando: el usuario confirma cada paso o anota qué falla; `limpiar.sql` aplicado y token revocado.

- [ ] **E8.4. Validación del reviewer.** RF-38 a RF-63 — **S** — Toca API Laravel: no
  - RF por RF: qué test lo cubre y el resultado (plan.md, "Estrategia de tests"); lista de los candados con su test;
    contradicciones y riesgos del plan resueltos o anotados; `MEMORY.md` actualizado (sin datos sensibles).
  - Hecho cuando: el reviewer aprueba o devuelve observaciones, y las observaciones quedan cerradas o anotadas.

Al terminar la tanda: **el usuario decide si pasa a producción (E9)**. Sin aprobación explícita, la L queda validada
solo en local.

## Tanda E9 — Producción (otra aprobación, aparte de la L): solo lectura, sin escribir

**No empieza sin aprobación explícita del usuario para producción.** No se corren tests automáticos ni E2E contra
producción (AGENTS.md): todo se hace a mano, con la app corrida en esta máquina contra producción
(`.\gradlew devWeb -Pentorno=release`, sin publicar nada), con un paciente de la **cartera de prueba** del médico.

- [ ] **E9.1. Preparación y respaldo.** RF-50, RF-59 — **S** — Toca API Laravel: no (pero toca producción: aprobación aparte)
  - Confirmar con el usuario: qué versión de hclinica corre en producción (la ruta `pacientes` ya contesta `401` aunque
    `MEMORY.md` diga que los repos hermanos no se subieron), cómo se despliega, `APP_DEBUG`, si hay un **respaldo
    reciente y restaurable** de la base de producción (hecho y probado, no solo "existe"), y qué paciente y qué
    médico de la cartera de prueba se usan.
  - Hecho cuando: respaldo confirmado por el usuario por escrito; versión y forma de despliegue anotadas; paciente de
    prueba elegido; si falta el respaldo, **se para** y no se sigue.

- [ ] **E9.2. Subir el servidor y comprobarlo sin token.** RF-50, RF-51 — **M** — Toca API Laravel: **sí** (producción: aprobación aparte)
  - Subir solo los archivos de E3 y E4 (sin migraciones; no correr `php artisan migrate`), limpiar la caché de rutas
    como se haya acordado y revisar dueños de archivos si se usó `artisan` en el servidor.
  - Hecho cuando: sin token, `consultas`, `consultas/1` y `pacientes/1/historia` contestan `401 sin_token` (no `404`); con un
    token inventado, `401 token` (no `tobb_caido`); la web de clínica sigue entrando; el usuario confirma.

- [ ] **E9.3. Lectura en producción con el paciente de la cartera de prueba.** RF-38 a RF-46, RF-50, RF-56 — **M** — Toca API Laravel: no (solo `GET`)
  - Lista de comprobación (la ejecuta el usuario o con el usuario mirando):
    1. Antes: anotar `COUNT(*)` y `MAX(updated_at)` de `consultas` y de las tablas hijas **del paciente de prueba**, y la
       fila de `medico_infos` del médico (solo lectura de la base).
    2. Iniciar sesión con el médico de prueba y abrir la historia del paciente: no más de 2 pedidos; comparar la cantidad
       de consultas con la web.
    3. Abrir una consulta cerrada propia, una de otro médico (si existe) y una abierta: comparar con la web lado a lado.
    4. Confirmar que nada se puede editar y que no hay botones de cerrar ni reabrir.
    5. Modo avión: se ve lo traído.
    6. Después: los mismos `COUNT` y `MAX(updated_at)`; `medico_infos` igual; en el registro de accesos, solo `GET`.
    7. No crear consultas en la app con ese paciente durante la prueba, y no abrir la sección "Enfermedades" (puede vincular).
  - **Parar y avisar** ante: cualquier `500`, un valor que no está en la web, cualquier cambio en los conteos, un
    pedido que no sea `GET`, o más de 10 pedidos en la prueba.
  - Hecho cuando: la lista completa con su resultado anotado (sin datos de pacientes ni tokens) y el usuario confirma.

- [ ] **E9.4. Cierre.** — **S** — Toca API Laravel: no
  - `MEMORY.md` y la tabla de `specs/README.md` actualizados; decisión del usuario sobre la entrega E.
  - Hecho cuando: el usuario aprueba el cierre de la L.

## Entrega E (escritura) — pendiente, sin tareas
Crear, editar y cerrar desde la app; laboratorio y pendientes con id de la web (`9.sqm`, **REQUIERE aprobación
explícita**); filtro de antecedentes `activo = 0`; qué hacer con lo viejo sin enviar; `tabaquismo` (Q13) y el mapa
al revés; adjuntos; aviso de licencia; sin conexión. Antes: respaldo probado de producción y limpieza de lo que dejen
las pruebas. Se planifica después de ver funcionar la L.

## Contradicciones, riesgos y lo no verificado
Ver `plan.md` ("Contradicciones", "Riesgos de la entrega L" y "No verificado"). Lo que más pesa: H2 (E5), el esquema
y permisos de producción (E9.1) y que `MEMORY.md` y la respuesta de producción no coinciden sobre qué versión de hclinica
está arriba.
