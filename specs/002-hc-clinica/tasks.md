# Tareas 002 — parte 1: pacientes y cartera

Plan: `plan-parte1.md` (aprobado el 2026-10-19, con PD1 decidido: E1.5 entra). Spec: `spec.md`, parte 1 (RF-8 a
RF-14 y RF-35 a RF-37), aprobada el 2026-10-19. Las tareas van por tandas; cada tanda se aprueba antes de generar la
siguiente (la E2, app, se genera después de validar la E1).

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
  envío de consultas de la HC general es el resto de la 002).
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

- [ ] **E2.5. `PacientesHcRepository`: vínculo y elegir ficha.** RF-10, RF-11, RF-12, RF-35, RF-36
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

- [ ] **E2.6. Pediatría delega; `HcBackend`, `HcRepository` y `DataModule`.** RF-35 (RF-33 de la 001: pediatría
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

- [ ] **E2.7. Lista de pacientes: motivos y `traerCarteras`.** RF-8, RF-9
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

- [ ] **E2.8. E2E de la HC general y verificación manual de RF-10 y RF-14.** RF-8, RF-10, RF-14
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
