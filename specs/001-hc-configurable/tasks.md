# Tareas 001 — Perfiles configurables de la HC general

Plan: `plan.md` (aprobado el 2026-10-09). Las tareas van por tandas; cada tanda se aprueba antes de generar la siguiente.
Reglas para todas: commits locales como `rodri89` (ya configurado), **sin push**; nada contra producción; ningún
dato real, contraseña ni token en este repo (los valores de prueba se anotan solo en las máquinas locales).

## Tanda A1 — Entorno local, base de la API de hclinica y endpoint de turnos

Estado verificado al generar la tanda: el MySQL de XAMPP (MariaDB 10.1) ya tiene `hc_clinica` (61 tablas, igual que
el volcado), `turnosonlinebb` y `tobb`; Apache responde; `htdocs\TurnosOnlineBB-salud360` responde
`POST .../public/index.php/api/salud360/auth/login` y no tiene git.

- [x] **T1. Clon de hclinica, rama y copia servida.** Entorno (fase 0)
  - Clonar `rodri89/hclinica` en `C:\Users\flor\rebonline\hclinica` y crear la rama `salud360-api` desde `main`
    (no tocar `C:\Users\flor\hclinica` ni `htdocs\HClinica`).
  - Copiar a `C:\xampp\htdocs\HClinica-salud360` (con `vendor/` copiado de `C:\Users\flor\hclinica`, no enlazado) y un
    `.env` local: base `hc_clinica` del XAMPP, `APP_DEBUG=true` solo acá.
  - Hecho cuando: `git -C rebonline\hclinica branch --show-current` dice `salud360-api` y
    `http://localhost/HClinica-salud360/public/index.php/login` responde 200.

- [x] **T2. Turnos local al día y con acceso de prueba.** Entorno (fase 0)
  - Comparar `htdocs\TurnosOnlineBB-salud360` con `main` de `rebonline\turnosonlinebb` (API `Api/Salud360`, rutas,
    `config`) y actualizar la copia servida si está atrasada (respaldo de la copia antes de pisar).
  - En la base local `turnosonlinebb`: una contraseña de prueba para un médico que esté vinculado en `hc_clinica`
    (`users.medico_id_tobb`) y para un administrador, y la HC `clinica` habilitada a ese médico en
    `salud360_medico_hc`. Los valores no se anotan en este repo.
  - Hecho cuando: `POST auth/login` de turnos local con el médico de prueba devuelve un token y su perfil trae
    `historias_clinicas` con `clinica`; lo mismo para el administrador con `rol: admin`.

- [x] **T3. La app contra todo local.** Entorno (fase 0) (login en navegador probado por el usuario el 2026-10-09)
  - Confirmar el comando de `devWeb` con `-Psalud360.entorno.dev.turnos=...` (turnos local) y
    `-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php`, sin tocar
    `gradle.properties`, y anotarlo en `AGENTS.md` (sección de comandos) y en el plan.
  - Hecho cuando: con `.\gradlew devWeb` y esos `-P`, el login en http://localhost:8080 entra con el médico y con el
    administrador de prueba, y el login muestra el entorno `dev`.

- [x] **T4. Configuración y CORS de la API en hclinica.** RNF solo el administrador (base)
  - En `rebonline\hclinica` (rama `salud360-api`): `config/salud360.php` (copia de pediatría con
    `hc_codigos = ['clinica', 'hepatologia']`), `app/Http/Middleware/Salud360Cors.php` registrado primero en el
    `$middleware` global de `app/Http/Kernel.php`. Copiar a la copia servida.
  - Hecho cuando: `curl -X OPTIONS .../api/salud360/auth/perfil` responde 204 con `Access-Control-Allow-Origin: *` y
    el encabezado `X-Salud360-Token` permitido, y la web de hclinica sigue entrando normal.

- [x] **T5. Validación del token de turnos.** RF-29 (servidor), RNF solo el administrador
  - `app/Services/Salud360/TobbAuthService.php` y `app/Http/Middleware/Salud360Api.php` (alias `salud360`), copiados
    de pediatría, con: acepta el médico si turnos le habilita **cualquiera** de `hc_codigos`; admin = `rol: admin` →
    usuario `usuario_tipo = 1`; licencia con `medico_licencias` igual que la web; **arreglo del modo "turnos caído"**
    (resolver por el `user_id` de la caché, incluido el admin, y pasar siempre por la licencia); tablas
    `salud360_token_cache` y `salud360_vinculos_pendientes` en caliente. `SALUD360_TOBB_URL` = turnos local.
  - Hecho cuando: contra la copia servida, sin token → `401 sin_token`; token inventado → `401 token` (no
    `tobb_caido`); médico sin `clinica` ni `hepatologia` habilitada → `403 hc_no_habilitada`.

- [x] **T6. `auth/perfil` y rutas.** RF-29 (servidor), RNF solo el administrador
  - `app/Http/Controllers/Api/Salud360/Salud360Controller.php` (base: respuestas `{ok, mensaje, codigo}`, rol por
    `usuario_tipo`, sin tocar nunca `medico_infos`), `AuthController@perfil`, y `routes/api.php` con el grupo
    `salud360`. `md/API_SALUD360_CLINICA.md` con lo hecho hasta acá.
  - Hecho cuando: con el token del médico de prueba → 200 con `rol: medico`, `medico_id_tobb` y `licencia`; con el del
    administrador → 200 con `rol: admin` y sin licencia; con licencia vencida del médico (cambiada en la copia local y
    restaurada después) → `403 licencia_vencida`; con `SALUD360_TOBB_URL` apuntando a un puerto muerto y el token ya
    en caché → sigue entrando el médico y el administrador, y la licencia vencida sigue bloqueando.

- [x] **T7. Endpoint de turnos para editar las HC de un médico.** RF-18 (servidor)
  - En `rebonline\turnosonlinebb`, rama nueva `salud360-hc-general` desde `main`: `PUT /api/salud360/medicos/{id}/historias-clinicas`
    `{historias_clinicas: [...]}`, solo administrador, que reemplaza las filas de `salud360_medico_hc` de ese médico
    con el mismo criterio que `admin_hc_medico` de la web; códigos fuera del catálogo → 422. Documentarlo en
    `md/API_SALUD360.md`. Copiar a la copia servida.
  - Hecho cuando: con el token del administrador, el `PUT` cambia las filas y el `auth/perfil` de turnos del médico
    devuelve las nuevas `historias_clinicas`; con el token de un médico → 403; con un código inventado → 422.

Al terminar la tanda: `MEMORY.md` con el estado del entorno (sin credenciales) y propuesta de la tanda A2.

## Tanda A2 — Modelo y API de configuración en hclinica

Fase 2 del plan (secciones 2, 3, 6, 9 y 11): RF-1 a RF-12, RF-15, RF-17, RF-31 y RF-32, lado servidor. Todo en
`rebonline\hclinica` (rama `salud360-api`), un commit local por tarea como `rodri89`, **sin push**, y copiado a
`C:\xampp\htdocs\HClinica-salud360` antes de probar (`php -l` de cada archivo tocado). Si se toca `config/` o `.env`:
`php artisan config:cache` en las dos copias servidas.

Reglas de prueba de la tanda:
- URL base: `http://localhost/HClinica-salud360/public/index.php/api/salud360/...`; tokens del médico y del
  administrador de prueba sacados de `auth/login` de turnos local, sin escribir credenciales en ningún repo.
- La licencia del médico de prueba (`users.id` 9, perfil web 2) está vencida a propósito: para probar como médico,
  extenderla y al terminar restaurarla con `UPDATE` puntual (incluidos `activo` y `updated_at`, ver `MEMORY.md`).
- Los perfiles y asignaciones de prueba se borran con `DELETE` puntuales al terminar cada tarea (nunca `DROP TABLE`);
  lo único que queda en la base local es lo que deja la carga inicial (T12).
- phpunit corre en la copia servida (el clon no tiene `vendor/`; PHPUnit 7.5):
  `C:\xampp\php\php.exe vendor\bin\phpunit tests\Unit\PerfilesTest.php`. El test extiende
  `PHPUnit\Framework\TestCase` (sin la app de Laravel ni base).

- [x] **T8. Catálogo de secciones y tablas en caliente.** RF-1, RF-2 (base), RF-16
  - `app/Services/Salud360/CatalogoSecciones.php`: constante `SECCIONES` con los 24 ids de la tabla del plan
    (sección 2), en ese orden, con `nombre` y `descripcion`, más `ids()`/`existe($id)`.
  - `app/Services/Salud360/PerfilesService.php` con `asegurarTablas()` (`Schema::hasTable`/`Schema::create`, como
    `TobbAuthService::asegurarTablas`): `salud360_perfiles`, `salud360_perfil_pestanias`, `salud360_perfil_secciones`,
    `salud360_perfil_medicos` y `salud360_cargas` con las columnas y claves del plan (InnoDB, sin claves foráneas).
  - `ConfigController@catalogo` (`GET config/catalogo`, solo admin: médico → `403 sin_permiso`) y la ruta en
    `routes/api.php`; los endpoints de configuración llaman a `asegurarTablas()` al empezar.
  - Hecho cuando: con el token del administrador → 200 con `secciones` de 24 elementos `{id, nombre, descripcion}`
    en el orden del plan; con el del médico → `403 sin_permiso`; `SHOW CREATE TABLE` de las cinco tablas muestra los
    `unique` (`nombre`; `perfil_id`+`nombre`; `perfil_id`+`seccion`; `medico_id_tobb`; `nombre`) y un segundo pedido
    no falla (tablas ya creadas).

- [x] **T9. Validación de perfiles (pura) con phpunit.** RF-2, RF-4, RF-8, RF-9
  - `PerfilesService::validar(array $perfil, array $otrosNombres): array` sin base: nombres de perfil, pestaña y
    sección no vacíos y ≤ 60 (`mb_strlen` después de `trim`); al menos una sección; nombre de perfil no usado por otro
    (sin mayúsculas ni espacios de más); pestañas sin nombres repetidos en el perfil; cada sección del catálogo, una
    sola vez y con al menos un tipo. Cada error `{campo, mensaje}` con el lugar (`nombre`, `pestanias.1.nombre`,
    `pestanias.1.secciones.0`) y qué corregir.
  - `tests/Unit/PerfilesTest.php` con un caso por regla (válido sin errores; vacío; 61 caracteres; nombre repetido
    con otra capitalización; pestaña repetida; sección inventada; sección dos veces en pestañas distintas; sección sin
    tipos; perfil sin secciones) y que cada error traiga el `campo` esperado.
  - Hecho cuando: phpunit de `PerfilesTest.php` en verde en la copia servida y `php -l` sin errores.

- [x] **T10. CRUD de perfiles (administrador).** RF-3 a RF-12, RF-15
  - `PerfilController`: `GET config/perfiles` (`{perfiles: [{id, nombre, medicos}]}`), `GET config/perfiles/{id}`,
    `POST config/perfiles` (201), `PUT config/perfiles/{id}` y `DELETE config/perfiles/{id}`, solo admin
    (`403 sin_permiso`). Perfil = `{id, nombre, pestanias: [{nombre, secciones: [{seccion, nombre, consulta,
    telemedicina}]}]}`; el orden es el del array. Errores de `validar` → `422 validacion` con `errores`.
  - `PUT`: valida primero; reemplazo total en `DB::transaction` con `lockForUpdate` sobre la fila del perfil (borra
    pestañas y secciones y las vuelve a insertar), conserva el `id`. `DELETE` con médicos asignados →
    `409 perfil_con_medicos` `{medicos: n}`. Id inexistente → `404 no_encontrado`.
  - Hecho cuando, con curl y el token del administrador: `POST` de un perfil de prueba con dos pestañas y tres
    secciones → 201 y el `GET {id}` lo devuelve igual y en orden; `POST` con el mismo nombre en otra capitalización y
    con una sección inventada → 422 con el `campo` correcto; `PUT` que mueve una sección de pestaña, la renombra y
    cambia tipos → mismo `id` y el `GET` refleja todo; dos `PUT` seguidos → el `GET` devuelve el último completo;
    `PUT`/`DELETE` de un id inexistente → 404; `DELETE` con una fila de prueba en `salud360_perfil_medicos`
    (insertada y borrada por SQL) → 409 con `medicos: 1`, y sin ella → `{ok: true}` y no quedan filas del perfil en
    las tres tablas; con el token del médico → 403 en los cinco.

- [x] **T11. Asignaciones y configuración del médico.** RF-10, RF-17 (y la base de RF-19/RF-20 para la tanda B)
  - `GET config/asignaciones` (admin): `{asignaciones: [{medico_id_tobb, nombre, vinculado, activo, perfil_id, perfil_web}]}`
    (`activo` es null si no está vinculado) = médicos de `users` (`usuario_tipo = 2`, `medico_id_tobb <> 0`) + asignaciones de números todavía no vinculados.
  - `PUT config/asignaciones/{medicoIdTobb}` (admin) `{perfil_id: n | null}` → `{asignacion}`; `null` borra la fila;
    perfil inexistente → 404; número no entero positivo → 422. Acepta números no vinculados (para el alta, RF-18).
  - `ConfigController@config` (`GET config`, solo médico; admin → `403 sin_permiso`): `{perfil: {...} | null}` del
    perfil asignado a su `medico_id_tobb`, pestañas y secciones en orden.
  - Hecho cuando: el médico de prueba sin asignación → `GET config` da `perfil: null`; el administrador le asigna un
    perfil de prueba → el médico lo recibe con esos nombres y orden; un `PUT` del perfil → el médico ve el cambio con
    el mismo `id` (RF-10); `GET config/asignaciones` lista al médico con `vinculado: true`, `perfil_web: 2` y el
    perfil, y un número inventado asignado aparece con `vinculado: false`; `perfil_id: null` lo borra; perfil
    inexistente → 404, número `abc` → 422. Al terminar: sin asignaciones ni perfiles de prueba y licencia restaurada.

- [x] **T12. Carga inicial de perfiles.** RF-31, RF-32
  - `app/Services/Salud360/CargaInicialPerfiles.php`, llamada por `asegurarTablas()`: si `salud360_cargas` tiene
    `perfiles_iniciales`, no hace nada; si no, en una transacción crea "Clínica general" y "Hepatología" (solo si no
    existe un perfil con ese nombre) con las pestañas "Antec. Familiares" y "Consulta" y las secciones, tipos y orden
    de la tabla de la spec (datos subjetivos y objetivos antes de exámenes complementarios), definidos como array en
    la clase; asigna con `perfilPara(int $perfilWeb)` (pura: 2 → Hepatología; 1, 5, 9 → Clínica general; resto →
    ninguno) a `usuario_tipo = 2` con `medico_id_tobb <> 0` sin asignación previa (`WHERE NOT EXISTS`); inserta el marcador.
  - `PerfilesTest.php` suma `perfilPara` (1, 2, 5, 9, 3, 4, 0) y que los dos perfiles del array pasen `validar`.
  - Hecho cuando: phpunit en verde; después del primer pedido del administrador, SQL sobre `hc_clinica`: "Clínica
    general" tiene 1 + 11 secciones (Antec. Familiares: `antecedentes_familiares` C+T; Consulta: `datos_subjetivos` T,
    `datos_objetivos` T, `alimentacion`, `diuresis_catarsis`, `somnia`, `habitos`, `examen_fisico` C,
    `examenes_complementarios`, `interconsulta`, `conductas` C+T, `nota` C) y "Hepatología" 1 + 16 (Consulta:
    `motivo_consulta`, `documentos`, `laboratorio_general`, `eco_abdominal`, `serologias`, `tabla_peso`, `child_pugh`,
    `veda`, `biopsia_hepatica`, `estudios_cardiologicos`, `tc_rmn` C; `datos_subjetivos`, `datos_objetivos`,
    `examenes_complementarios`, `interconsulta`, `conductas` T), en ese orden y sin `laboratorio_reducido`; el agrupado
    `users.perfil` → perfil asignado da 2 → Hepatología, 1/5/9 → Clínica general y el resto sin asignación; el médico
    de prueba (`users.id` 9) queda en Hepatología. Un segundo pedido no cambia nada, y borrando solo el marcador y
    pidiendo otra vez no se duplican perfiles, pestañas, secciones ni asignaciones (mismos ids y conteos).

- [x] **T13. Documentación y cierre de la API de configuración.** RF-1 a RF-12, RF-15, RF-17, RF-31, RF-32
  - `md/API_SALUD360_CLINICA.md`: tabla "Código" con los archivos nuevos; endpoints `config`, `config/catalogo`,
    `config/perfiles[/{id}]` y `config/asignaciones[/{medicoIdTobb}]` con quién, request/response de ejemplo
    (sin datos reales), errores (`sin_permiso`, `no_encontrado`, `validacion` con `errores`, `perfil_con_medicos`),
    tablas en caliente, carga inicial y su marcador, cómo correr phpunit y curl de ejemplo; quitar lo ya hecho de
    "Lo que viene".
  - Hecho cuando: `PerfilesTest.php` en verde, `php -l` de todos los archivos de la tanda sin errores, la copia
    servida igual al clon (sin contar `.env`/`vendor/`), una pasada de curl de humo por cada endpoint con el código
    documentado, y el `.md` no tiene tokens, contraseñas ni nombres de médicos.

Al terminar la tanda: validación del reviewer y MEMORY.md.

Validada por el reviewer el 2026-10-10: APROBADO.

## Tanda B — Lógica y datos de la app

Fases 3 y 3b del plan (secciones 4 `core/model`, `core/database`, `core/data` y `especialidades/clinica`; 5; 7; 9
y 11). RF del lado app: RF-2, RF-4, RF-7, RF-8, RF-9, RF-11, RF-12, RF-16, RF-20 a RF-22, RF-27, RF-28 (lógica),
RF-31 (catálogo de la app) y RF-23, RF-25, RF-26 (caché). Todo en este repo.

Reglas de la tanda:
- Al empezar T14: rama `hc-general` creada desde `main` (ahí se entregan juntas la 001 y la 002). Un commit local
  por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: cada tarea escribe su test, lo ve fallar y después el código. Si Gradle pide `JAVA_HOME`:
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- **No se toca** `AppDi.kt` (la contribución de clínica sigue siendo `clinicaContribution`), ni `hepatologiaContribution`,
  ni pantallas, ni `HcApiSync`/`SyncEngine`: eso es de las tandas C y D (RF-33: pediatría y las demás siguen igual).
- La lógica pura va en `core/data/src/commonMain/kotlin/com/salud360/core/data/hcgeneral/` y sus tests en
  `core/data/src/commonTest/kotlin/com/salud360/core/data/hcgeneral/`: sin red, sin Compose, sin base, sin "hoy".
- Ningún dato real en los tests: perfiles, nombres y números de médico inventados.
- `normalizarHistoriasClinicas` (RF-29) queda para la tanda D, junto con `7.sqm` (fase 6 del plan), y las consultas
  de lectura de `HistoriaClinica.sq` para RF-22 van en la tanda C, con su pantalla.

- [x] **T14. Tipos del modelo para perfiles y pestañas agrupadas.** RF-7, RF-20 (base), RF-33
  - Rama `hc-general` desde `main`.
  - `core/model/.../especialidad/EspecialidadDefinition.kt`: `data class PestaniaDef(id, titulo, secciones: List<String>)`
    y `TipoConsultaDef.grupos: List<PestaniaDef> = emptyList()` **como último parámetro** (los constructores
    posicionales de las demás especialidades no cambian; con `grupos` vacío todo sigue igual).
  - `core/model/.../especialidad/PerfilHc.kt` (nuevo, `@Serializable` lo que va al caché): `TipoConsultaHc`
    (`CONSULTA` ↔ `SeccionesComunes.TIPO_CONTROL`, `TELEMEDICINA` ↔ `TIPO_TELEMEDICINA`), `SeccionPerfil(seccion,
    nombre, tipos)`, `PestaniaPerfil(nombre, secciones)`, `PerfilHc(id: Long?, nombre, pestanias)` (`id` null = todavía
    no guardado), `SeccionCatalogoRemota(id, nombre, descripcion)`, `ResumenPerfil(id, nombre, medicos)`,
    `AsignacionPerfil(medicoIdTobb, nombre?, vinculado, activo?, perfilId?, perfilWeb?)` (nulos como en el contrato de
    `config/asignaciones`), `CatalogoHc` (base `EspecialidadDefinition` + secciones de la app por id con lo que cada
    una necesita: `SeccionDef`, categorías de antecedentes, laboratorios, registros; y la lista de secciones legadas)
    y `CodigosHc.GENERAL = "clinica"`.
  - Hecho cuando: `.\gradlew :core:model:compileKotlinJvm` compila, y `.\gradlew :core:data:jvmTest` y
    `.\gradlew :composeApp:compileKotlinWasmJs` siguen en verde sin tocar ninguna especialidad.

- [x] **T15. Validación del perfil y sección nueva.** RF-2, RF-4, RF-8, RF-9
  - `hcgeneral/PerfilValidacion.kt`: `validarPerfil(perfil, otrosNombres, catalogoIds): List<ErrorPerfil>` espejo de
    `PerfilesService::validar` de hclinica: mismas reglas, mismos `campo` (`nombre`, `pestanias`,
    `pestanias.i.nombre`, `pestanias.i.secciones.j.nombre`, `pestanias.i.secciones.j.seccion`,
    `pestanias.i.secciones.j`), repeticiones marcadas en la segunda aparición, nombres de perfil y de pestaña
    comparados con `trim`, espacios colapsados, sin mayúsculas y **sin tildes** (más estricto que el servidor, a
    propósito: avisa antes del 422; "Decidido" B4), largo ≤ 60 contando caracteres como `mb_strlen` (code points, no unidades UTF-16).
    `catalogoIds` son los del catálogo **remoto** (`config/catalogo`), no los de la app, para no marcar como error
    lo que la app no conoce pero el perfil ya tiene (RF-16). `ErrorPerfil(campo, mensaje)` es la misma forma que
    devuelve la API en `errores`, así la pantalla muestra igual los dos.
  - `nuevaSeccion(seccionId, catalogo): SeccionPerfil`: nombre por defecto del catálogo y solo `CONSULTA` (RF-8).
  - `PerfilValidacionTest`: válido sin errores; vacío y 61 caracteres en perfil, pestaña y sección; nombre usado por
    otro con otra capitalización y espacios; "Clínica general" vs "clinica  general" (perfil y pestaña) repetido;
    pestaña repetida; sección fuera del catálogo; sección dos veces en
    pestañas distintas (error en la segunda); sección sin tipos; perfil sin secciones; `nuevaSeccion` sale con
    Consulta y el nombre por defecto. Cada caso comprueba el `campo` exacto.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PerfilValidacionTest*'` en verde.

- [x] **T16. Duplicar, borrar y catálogo del administrador.** RF-1, RF-11, RF-12, RF-16
  - `hcgeneral/PerfilCopia.kt`: `nombreCopia(nombre, existentes)` ("Copia de X", "Copia de X (2)", …, comparando
    como `validarPerfil`; recorta X para no pasar 60 con el sufijo) y `duplicarPerfil(perfil, existentes)` (mismas
    pestañas, secciones, nombres y tipos; `id` null). `puedeBorrar(perfil, asignaciones)` = ningún médico asignado.
  - `hcgeneral/CatalogoAdmin.kt`: `catalogoParaAdmin(remoto, catalogoApp)` en el orden del remoto, con nombre y
    descripción, marcando `soportada` las que la app sabe mostrar; las no soportadas no se ofrecen para agregar.
  - `PerfilCopiaTest` (copia simple, con "(2)" y "(3)" ocupados, nombre de 60 recortado, la copia pasa
    `validarPerfil`, borrar con y sin médicos) y `CatalogoAdminTest` (sección del remoto que la app no tiene →
    no soportada; orden del remoto).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*PerfilCopiaTest*' --tests '*CatalogoAdminTest*'` en verde.

- [x] **T17. Definición de la HC general, visibles y estado de la configuración.** RF-7, RF-19, RF-20, RF-21, RF-23,
  RF-24, RF-25, RF-26, RF-27, RF-28
  - `hcgeneral/DefinicionHcGeneral.kt`: `armarDefinicion(catalogo, perfil?): DefinicionArmada` → definición
    "clinica" con todas las secciones que la app conoce (título del perfil si está en él, si no el por defecto),
    tipos `control` y `telemedicina` con `grupos` = pestañas del perfil filtradas por tipo, en orden y con sus
    nombres; pestañas sin secciones para ese tipo, fuera; ids que la app no conoce, omitidos y listados en
    `desconocidas`; sin perfil (RF-19), tipos sin grupos. Tipo `foto` solo para leer consultas viejas ("Decidido" 5 del plan).
  - `seccionesVisibles(tipo, ocultas)`: grupos sin las ocultas por el médico, nunca algo fuera del perfil; las
    ocultas que ya no están en el perfil se ignoran pero no se podan (RF-28).
  - `hcgeneral/EstadoConfig.kt`: `estadoConfig(guardada, remota): EstadoConfigHc` (`ConPerfil` / `SinPerfil` /
    `PrimeraVezSinConexion`): la remota buena manda; si falla, vale la guardada sin vencimiento; distingue "sin
    perfil" (guardada con `null`) de "nunca recibida" (sin fila). Cuando no hay config usable lleva el motivo del
    fallo (`codigo` y `mensaje`: no vinculado, licencia vencida, HC no habilitada, sin conexión), igual que el aviso
    de la cartera de pacientes ("Decidido" B2).
  - `DefinicionHcGeneralTest` (con un catálogo chico armado en el test: nombres propios por perfil y por pestaña,
    pestaña vacía en telemedicina, id desconocido, sin perfil, mismo id en dos perfiles con nombres distintos) y
    `EstadoConfigTest` (remota buena con y sin caché, remota caída con caché con perfil y "sin perfil", remota caída
    sin caché con motivo `red` y con un motivo de la API como licencia vencida, remota con perfil `null`).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*DefinicionHcGeneralTest*' --tests '*EstadoConfigTest*'` en verde.

- [x] **T18. Secciones con datos fuera del perfil, solo para leer.** RF-22, RF-28, RF-30
  - `hcgeneral/SeccionesSoloLectura.kt`: `campoTieneDato(campo, valor)` (`0`, `0|` y vacío no cuentan) y
    `seccionesConDatos(datos, def)` según el tipo de sección (texto/form, examen físico, registros, laboratorios,
    archivos, antecedentes, diagnósticos de esa consulta), con `datos` como un tipo propio que la tanda C llena desde
    la base; `seccionesSoloLectura(catalogo, enPerfilParaTipo, conDatos, ocultas)`: con datos y fuera del perfil para
    ese tipo, con el **nombre por defecto** (el del catálogo del servidor, "Decidido" B1), al final, en orden de catálogo y después las legadas (diagnósticos,
    audio, fotos, antecedentes personales).
  - `SeccionesSoloLecturaTest`: sección quitada con datos → aparece; sin datos (o solo `0|`) → no; sección de
    Consulta en una telemedicina → aparece; legada con datos (diagnóstico) → aparece después de las del catálogo;
    oculta por el médico pero en el perfil → no aparece como solo lectura (RF-28).
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*SeccionesSoloLecturaTest*'` en verde.

- [x] **T19. Catálogo de la HC general en la app.** RF-31, RF-33
  - `especialidades/clinica/.../HcGeneral.kt` (nuevo; **convive** con `ClinicaMedica.kt` hasta la tanda C):
    `catalogoHcGeneral: CatalogoHc` con las 24 secciones de `CatalogoSecciones.php`, mismos ids, orden y **nombres**:
    el título por defecto de cada sección es el del catálogo del servidor ("Decidido" B1); reutiliza las `SeccionDef`
    de clínica, hepatología y `SeccionesGenericas` copiándolas con el título cambiado cuando difiere (lo que hoy es
    `private` en `ClinicaMedica.kt` pasa a `internal` sin cambiar `clinicaMedica` ni `hepatologia`); `diuresis_catarsis` y `somnia` como texto; base
    `hcGeneralBase` (código "clinica", nombre "Clínica", examen físico con peso, talla, IMC, TA y nota) y las legadas
    (diagnósticos, audio, fotos, antecedentes personales) solo para leer. `hcGeneralContribution` todavía **no** se
    crea ni se registra (tanda C).
  - `especialidades/clinica/src/commonTest/.../CatalogoHcGeneralTest.kt` (primer test del módulo): ids y nombres por
    defecto iguales, en orden, a la lista de `CatalogoSecciones.php` (copiada en el test); cada id del
    catálogo tiene `SeccionDef` y sus dependencias (laboratorio, registro o categoría de antecedentes); los dos
    perfiles iniciales (copiados en el test de la tabla de T12) armados con `armarDefinicion` dan las pestañas y
    secciones de la tabla de la spec, sin `desconocidas`; `clinicaMedica` y `hepatologia` siguen iguales. Pediatría
    no se prueba desde acá: alcanza con sus tests existentes y la compilación ("Decidido" B5).
  - Hecho cuando: `.\gradlew :especialidades:clinica:jvmTest --tests '*CatalogoHcGeneralTest*'` en verde y
    `.\gradlew :composeApp:compileKotlinWasmJs` compila.

- [x] **T20. Caché de la configuración del médico.** RF-23, RF-25, RF-26
  - `core/database/.../migrations/6.sqm` + `HcConfig.sq` (nuevo) con la misma tabla:
    `hc_config(medico_id TEXT, hc_codigo TEXT, config_json TEXT NOT NULL, recibida_en INTEGER NOT NULL,
    PRIMARY KEY(medico_id, hc_codigo))`, sin `dirty`/`deleted` (dueño remoto); consultas `configGuardada` y
    `guardarConfig` (`INSERT OR REPLACE`). `config_json` es el perfil o `null` (sin perfil, RF-19).
  - `core/data/.../repos/ConfigHcRepository.kt`: interfaz `ConfigHcApi { suspend fun config(): PerfilHc? }`;
    `refrescar(medicoId)` (pide y guarda; si falla, lanza la `TobbException` sin tocar lo guardado), `guardada(medicoId)`
    y `estado(medicoId, conRed)` con `estadoConfig`, propagando el motivo del fallo (`codigo`/`mensaje` de la
    `TobbException`) para el aviso ("Decidido" B2). Sin cliente "clinica" configurado (dev sin URL de hclinica) se
    comporta como sin conexión; en producción la URL siempre existe ("Decidido" B6). Nunca se llama desde el autoguardado ni pasa por `HcApiSync`/`SyncEngine`.
  - `core/data/src/jvmTest/.../repos/ConfigHcRepositoryTest.kt` (base SQLite en archivo temporal, como los E2E, y una
    `ConfigHcApi` falsa): guarda y relee con la API caída (RF-25); sin fila y sin red → `PrimeraVezSinConexion`
    (RF-26) con motivo `red`; fallo de la API (p. ej. no vinculado) sin fila → el estado trae ese `codigo`;
    sin cliente "clinica" → como sin conexión; `perfil: null` → `SinPerfil`; una respuesta nueva reemplaza la anterior; un fallo no borra lo guardado;
    y la migración: borrando `hc_config` de una base nueva y corriendo `Schema.synchronous().migrate` de la versión 6 a
    la 7, la tabla vuelve y funciona.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*ConfigHcRepositoryTest*'` y `.\gradlew :core:data:jvmTest`
    completos en verde.

- [x] **T21. DTOs, repositorio del administrador, Koin y URL de producción.** RF-1 a RF-17 (datos), RF-13, RF-23, RF-25
  - `core/data/.../network/hc/HcConfigDtos.kt`: DTOs del contrato real (`md/API_SALUD360_CLINICA.md`): perfil
    `{id, nombre, pestanias: [{nombre, secciones: [{seccion, nombre, consulta, telemedicina}]}]}`, catálogo, resumen
    (`medicos`), asignación (con nulos) y errores `{campo, mensaje}`, y su paso a/desde `PerfilHc`. `ConfigHcApi`
    implementada sobre `HcApiClient` (`GET config`).
  - `repos/PerfilesHcRepository.kt` (administrador, **nada local**): catálogo, lista, leer, crear (`POST`), guardar
    (`PUT`), borrar (`DELETE`, 409 con `medicos`), asignaciones y asignar. Los fallos devuelven el `mensaje`, el
    `codigo` y los `errores` de la API (RF-13); `codigo` `red` = sin conexión (RF-14).
  - Errores campo por campo ("Decidido" B3): `TobbException` suma `errores: List<ErrorPerfil>` (o equivalente
    `{campo, mensaje}`) opcional, **al final** y con valor por defecto (vacío), así no cambia ninguna llamada
    existente; `HcApiClient.interpretar` lo llena desde el 422 `validacion`.
  - `DataModule.kt`: `single` de `ConfigHcRepository` y `PerfilesHcRepository` sobre `porEspecialidad["clinica"]`
    (nullable). **No** se agrega "clinica" a `HcBackends`.
  - `core/data/build.gradle.kts`: propiedades `salud360.test.hc.clinica`, `.clinica.token`, `.clinica.token.admin`.
  - `gradle.properties`: **solo** la línea `salud360.entorno.release.hc.clinica=https://clinica.hclinicadigital.com`.
    Ojo: cambia el build de producción (la web release crea un cliente para hclinica y le copia el token al
    entrar); no se usa hasta la tanda C y no se publica sin la 002.
  - `PerfilesDtoTest` (commonTest): ida y vuelta del JSON de ejemplo del `.md` (perfil, `perfil: null`, asignaciones
    con nulos, 422 con `errores`) y `HcApiClient.interpretar` con la respuesta 422 del servidor → `TobbException`
    con `codigo` `validacion` y los `errores` campo por campo.
  - Hecho cuando: `.\gradlew :core:data:jvmTest` completo en verde (incluido `PerfilesDtoTest`),
    `.\gradlew :composeApp:compileKotlinWasmJs` compila y `git diff main -- gradle.properties` muestra una sola línea.

### Decidido (2026-10-10)
1. **Nombre por defecto de cada sección**: manda el del catálogo del servidor (`CatalogoSecciones.php` de hclinica).
   `catalogoHcGeneral` (T19) usa esos nombres como título por defecto (puede reutilizar las `SeccionDef` existentes
   copiándolas con el título cambiado). `CatalogoHcGeneralTest` compara ids **y** nombres por defecto contra la
   lista del servidor (copiada en el test). RF-22 muestra lo de solo lectura con ese nombre (T18).
2. **Avisos con motivo**: `estadoConfig` (T17) lleva el código/mensaje del fallo cuando no hay config (no vinculado,
   licencia vencida, HC no habilitada, sin conexión), igual que el aviso de la cartera de pacientes.
   `ConfigHcRepository` (T20) lo propaga.
3. **Errores campo por campo (RF-13)**: `TobbException` suma un campo `errores` opcional, al final y con valor por
   defecto, que `HcApiClient.interpretar` llena desde el 422 (T21; test con la respuesta del servidor).
4. **Tildes**: `validarPerfil` (T15) compara nombres de perfil y de pestaña ignorando tildes, mayúsculas y espacios
   de más (más estricto que el servidor, a propósito: avisa antes del 422). Test con "Clínica general" vs
   "clinica  general".
5. **Pediatría**: alcanza con sus tests existentes y la compilación (no se prueba desde `especialidades/clinica`).
6. **Sin URL de hclinica en dev**: se trata como sin conexión (en producción la URL siempre existe) (T20).
7. **AGENTS.md** (módulos con tests) se actualiza en el cierre (tanda D).

Al terminar la tanda: validación del reviewer y MEMORY.md.

Validada por el reviewer el 2026-10-12: APROBADO.

## Tanda C — Administración y pantalla del médico

Fases 4 y 5 del plan (sección 4: `features/admin`, `features/hc`, `composeApp` y las consultas de lectura de
`HistoriaClinica.sq`; secciones 7, 9 y 11; "Decidido" 5 a 8). RF: RF-1 a RF-18 (interfaz del administrador),
RF-19 a RF-28, RF-33 y RF-34 (médico). Todo en este repo, rama `hc-general`.

Reglas de la tanda:
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Lo que tenga lógica va a `core/data/.../hcgeneral/` (o al repositorio) con su test, primero en rojo; los
  ViewModels y las pantallas no tienen tests en este repo: solo muestran y delegan (constitución 4).
- Interfaz: el agente deja compilado `.\gradlew :composeApp:compileKotlinWasmJs` y los pasos manuales; los prueba el
  usuario con el `devWeb` contra XAMPP de `AGENTS.md`. La tarea no se marca hecha hasta que el usuario confirme.
- Datos de prueba: perfiles con nombres inventados que empiezan con "Prueba C", borrados al terminar cada tarea (o
  al final de la tanda si la siguiente los usa). "Clínica general" y "Hepatología" de la carga inicial no se
  editan; si se cambia la asignación del médico de prueba, al terminar vuelve a "Hepatología". Licencia del médico
  de prueba: extenderla y restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.
- **No se toca** (tanda D, "Decidido" C5): `7.sqm`, `normalizarHistoriasClinicas`, `hepatologiaContribution`. Tampoco
  `HcApiSync`/`SyncEngine`, ni se registra un `HcBackend` para "clinica"; la configuración nunca se pide desde el
  autoguardado.
- `HistoriaClinica.sq` solo suma consultas de lectura: sin columnas ni tablas nuevas, sin migración.

### Decidido (2026-10-13)
1. **C1 — Datos por paciente en "Datos"** (decisión del usuario): los antecedentes personales del paciente y, en
   general, los datos que se guardan por paciente y no por consulta, de secciones que no están en el perfil, **no**
   salen como solo lectura al final de cada consulta: se muestran una sola vez, solo lectura, en la pestaña "Datos"
   (ficha del paciente) de la HC general. Al final de la consulta van solo las secciones con datos en **esa**
   consulta. Se ajustan `seccionesConDatos`/`seccionesSoloLectura` (`hcgeneral/SeccionesSoloLectura.kt`, de T18) con
   su test (T27) y se muestra en T30.
2. **C2 — Copia de diagnósticos** (reviewer, bloqueante): `HistoriaClinicaViewModel.nuevaConsulta`
   (`ConsultaViewModel.kt`, ~línea 379) copia los últimos diagnósticos si `definicion.secciones` tiene una sección
   de diagnósticos; con la legada `diagnostico` en la definición armada eso dispararía. Tiene que mirar las secciones
   de los tipos de consulta, con test, en la **misma** tarea que registra `hcGeneralContribution` (T28).
3. **C3 — `enPerfil` obligatorio** (reviewer): `seccionesSoloLectura` pierde el valor por defecto de `enPerfil`, así
   la pantalla tiene que pasarlo (T27, usado en T30).
4. **C4 — Borrar un perfil** (reviewer): el `409 perfil_con_medicos` no trae el número; alcanza con mostrar el
   mensaje del servidor y recargar la lista. Borrar se deshabilita con `ResumenPerfil.medicos` > 0 (T23).
5. **C5 — Hepatología sigue hasta la D**: en C, `hcGeneralContribution` reemplaza a `clinicaContribution` en
   `AppDi.kt` (T28); `hepatologiaContribution`, `7.sqm` y `normalizarHistoriasClinicas` son de la tanda D.
6. **C6 — Migración en el navegador**: se prueba en `devWeb` que un navegador con la base en versión 6 (de antes de
   la T20) migra a 7 sin recrearla vacía (T23, primer paso manual de la tanda).
7. **C7 — C1 pasa a la spec** (decisión del usuario, opción 1): cambio aprobado en `spec.md`: RF-22 queda acotado a
   los datos de **esa** consulta y RF-34 (nuevo) muestra los datos por paciente fuera del perfil una sola vez, solo
   lectura, en "Datos". `plan.md` suma RF-34 a la tabla de trazabilidad. Lo implementan T27 y T30.
8. **C8 — Hepatología visible hasta la D**: la casilla "Hepatología" sigue en el alta y la ficha del médico hasta la
   tanda D. Las asignaciones de T25 listan a los médicos con `clinica` **o** `hepatologia` habilitada.
9. **C9 — Espera de `DefinicionesHc`**: hasta 5 s esperando la configuración; si vence, se usa la guardada
   (`estadoConfig` como sin conexión, RF-25/RF-26) (T28).
10. **C10 — Consulta abierta sin perfil**: el médico que queda sin perfil con una consulta ya abierta puede seguirla
    (solo "Datos"), cerrarla y anularla; no puede crear consultas nuevas (RF-19) (T28, T29).

- [x] **T22. Edición del perfil en borrador (lógica pura).** RF-5, RF-6, RF-7, RF-8, RF-16
  - `hcgeneral/EditorPerfil.kt`: funciones sobre `PerfilHc` que devuelven el perfil cambiado, sin red ni Compose:
    `agregarPestania` (al final, con un nombre por defecto que no repita otro, comparando como `claveDeNombre`),
    `renombrarPestania`, `moverPestania(i, ±1)`, `quitarPestania` (con sus secciones), `agregarSeccion(pestania,
    seccionId, catalogo)` con `nuevaSeccion` (nombre por defecto y solo Consulta, RF-8), `renombrarSeccion`,
    `marcarTipo(pestania, j, tipo, marcado)`, `moverSeccion(pestania, j, ±1)`, `moverSeccionAPestania(origen, j,
    destino)` (va al final de la otra) y `quitarSeccion`. Índices fuera de rango → perfil sin cambios. Las secciones
    que la app no conoce se conservan intactas en todas las operaciones (RF-16); el `id` del perfil nunca cambia.
  - `EditorPerfilTest`: cada operación (orden resultante exacto), bordes (subir la primera, bajar la última, mover a
    la misma pestaña), nombre por defecto con "Pestaña 1" ya usada, quitar la pestaña con una sección desconocida
    adentro (se va con ella; mover o renombrar otras no la toca), la sección agregada sale con Consulta.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*EditorPerfilTest*'` en verde.

- [x] **T23. Solapa "Perfiles HC": lista, duplicar y borrar.** RF-1 (lectura), RF-11, RF-12, RF-13, RF-14; C4, C6
  (compila; falta que el usuario confirme los pasos manuales 1 a 4)
  - `features/admin/.../PerfilesHcViewModel.kt` (registrado en `AdminModule.kt`; recibe `PerfilesHcRepository` y el
    `CatalogoHc`): al abrir pide catálogo, perfiles y asignaciones; estado con la lista, `cargando`, `mensaje` y
    `sinConexion` (fallo con `codigo` `red`, RF-14) y `reintentar()`. Duplicar = `perfil(id)` → `duplicarPerfil` con
    los nombres de la lista → `crear` → recargar (RF-12). Borrar con confirmación, solo si `medicos == 0`; un `409
    perfil_con_medicos` muestra el mensaje del servidor y recarga (C4, RF-11). Los fallos muestran el `mensaje` de la
    `TobbException` (RF-13).
  - `PerfilesHcScreens.kt` (nuevo): solapa "Perfiles HC" al final de `SOLAPAS` en `AdminScreens.kt`; por perfil:
    nombre, médicos asignados, Editar (sin acción hasta T24), Duplicar y Borrar (deshabilitado con médicos). Sin
    conexión: aviso, acciones deshabilitadas y "Reintentar".
  - `composeApp/.../AppDi.kt`: `single<CatalogoHc> { catalogoHcGeneral }` (la lista de contribuciones no cambia).
  - Hecho cuando: `.\gradlew :features:admin:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs`
    compilan, y el usuario confirma en `devWeb`:
    1. (C6) En el navegador que ya usaba la app antes de la T20, sin borrar datos del sitio: la app arranca sin error
       en la consola y, entrando como el médico de prueba, siguen sus pacientes y consultas (la base pasó de la
       versión 6 a la 7, no se recreó vacía).
    2. Como administrador, "Perfiles HC" lista "Clínica general" y "Hepatología" con su número de médicos, y Borrar
       está deshabilitado en los dos.
    3. Duplicar "Hepatología" dos veces → "Copia de Hepatología" y "Copia de Hepatología (2)", con 0 médicos; Borrar
       las dos → desaparecen.
    4. Con Apache de XAMPP detenido, "Reintentar" → aviso de sin conexión y nada se puede tocar; prendido otra vez,
       "Reintentar" → vuelve la lista.

- [x] **T24. Editor de perfiles.** RF-1 a RF-9, RF-13, RF-14, RF-15, RF-16
  (compila; falta prueba manual del usuario, pasos 1 a 6)
  - En `PerfilesHcViewModel`: borrador del perfil en edición ("Nuevo perfil" arranca con una pestaña vacía; Editar
    lo lee con `perfil(id)`), operaciones de T22, y Guardar: primero `validarPerfil(perfil, otros nombres de la
    lista, ids del catálogo remoto)`; si pasa, `guardar` (POST o PUT). Errores locales o del 422
    (`TobbException.errores`) se guardan por `campo`; cualquier fallo deja el borrador intacto para reintentar
    (RF-13); `red` → sin conexión y edición deshabilitada (RF-14). Guardado bien → vuelve a la lista y recarga.
  - En `PerfilesHcScreens.kt`: nombre del perfil; pestañas (agregar, renombrar, subir/bajar, quitar); en cada una sus
    secciones (renombrar, casillas Consulta/Telemedicina, subir/bajar, mover a otra pestaña, quitar) y "Agregar
    sección" con `seccionesParaAgregar` (nombre y descripción). El catálogo completo (`catalogoParaAdmin`) se ve con
    nombre y descripción; las no soportadas, "requiere actualizar la app" y sin poder agregarlas; si el perfil ya
    tiene una, se muestra marcada así y se conserva al guardar (RF-16). Cada error junto a su campo. Salir con
    cambios sin guardar pide confirmación.
  - Hecho cuando: `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`:
    1. Crear "Prueba C" con pestaña "Uno" (Motivo de consulta, Nota) y "Dos" (Conductas, Consulta y Telemedicina) →
       aparece en la lista; al reabrirlo está igual y en ese orden.
    2. No guarda y marca el campo: nombre vacío, de 61 caracteres, "clinica  general"; dos pestañas "Uno"; una
       sección sin tipos; perfil sin secciones.
    3. Renombrar "Nota" a "Nota final", moverla a "Dos", subir "Dos" y guardar → al reabrir, mismo perfil (misma
       fila en la lista) con todo eso.
    4. Abrir "Prueba C" en dos pestañas del navegador, guardar un cambio distinto en cada una → al reabrir queda
       completo el último guardado (RF-15).
    5. Con Apache detenido, Guardar → mensaje y el borrador sigue en pantalla; prendido otra vez, Guardar anda (RF-13).
    6. (RF-16) Agregando por un rato en la **copia servida** (no en el clon) una sección `prueba_c` a
       `CatalogoSecciones.php` y una fila de `prueba_c` en "Prueba C" por SQL puntual: el catálogo la muestra como
       "requiere actualizar la app" sin poder agregarla y, guardando el perfil desde la app, la fila sigue. Después
       se quitan las dos cosas.

- [x] **T25. Asignación de perfiles a médicos.** RF-10, RF-14, RF-17; C8
  (compila; falta prueba manual del usuario)
  - `PerfilesHcViewModel` suma `AdminRepository.observarMedicos()`: médicos con "clinica" **o** "hepatologia"
    habilitada (C8: hepatología sigue como casilla aparte hasta la tanda D), cruzados por
    número de turnos (`TobbIds.numero`) con `asignaciones()`, como la solapa de licencias. Asignar = `asignar(número,
    perfilId | null)` y recargar perfiles y asignaciones (los conteos de la lista cambian solos).
  - Pantalla: sección "Médicos" en "Perfiles HC" con un selector de perfil por médico que incluye "Sin perfil"; si la
    asignación es de un número no vinculado en hclinica (`vinculado == false`), se indica. Sin conexión, selectores
    deshabilitados (RF-14).
  - Hecho cuando: `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`: el médico de
    prueba aparece con "Hepatología"; asignarle "Prueba C" → "Prueba C" muestra 1 médico y Borrar se deshabilita;
    renombrar "Prueba C" → sigue asignado (RF-10); "Sin perfil" → queda sin perfil (y `GET config` como médico da
    `perfil: null`); se le vuelve a poner "Hepatología".

- [x] **T26. Alta y ficha del médico con la HC general y su perfil.** RF-18, RF-14
  (compila; falta prueba manual del usuario)
  - `core/data`: `AgendaTurnosOnline` + `TurnosRepository.guardarHistoriasClinicas(medicoId, codigos)` →
    `PUT medicos/{id}/historias-clinicas` de turnos (T7), requiere conexión como `crearUsuario`; `crearUsuario` deja
    disponible el `medico_id` de `TobbUsuarioCreado` (hoy devuelve solo `Usuario`).
  - `AdminViewModel.setEspecialidadHc`: manda la lista completa a turnos y, si la acepta, guarda el médico en el
    dispositivo **sin** `dirty` (dueño remoto; hoy `AdminRepository.setEspecialidadHc` solo cambia la base local);
    si falla, mensaje y nada cambia.
  - Alta (`UsuariosTab`): al marcar "Clínica" (`CodigosHc.GENERAL`) aparece el selector de perfil (perfiles de
    `PerfilesHcRepository` + "Sin perfil"); después del alta, si hay perfil y `medico_id`, se asigna; si la
    asignación falla, "Usuario creado; falta asignarle el perfil: <motivo>". Ficha (`MedicoCard`): con "Clínica"
    habilitada, el mismo selector que T25.
  - Test en `core/data` commonTest del cuerpo `{historias_clinicas: [...]}` y de la lectura de `medico_id` del alta.
  - Hecho cuando: `.\gradlew :core:data:jvmTest` en verde, `.\gradlew :composeApp:compileKotlinWasmJs` compila y el
    usuario confirma en `devWeb`: alta de un médico "Prueba C" con "Clínica" y perfil "Prueba C" → turnos local le
    habilita `clinica` y `config/asignaciones` lo trae con `vinculado: false` y ese perfil; en la ficha del médico de
    prueba, destildar y volver a tildar "Clínica" → `auth/perfil` de turnos lo refleja; con Apache detenido, tildar
    → mensaje y la casilla queda como estaba. Al terminar: el usuario de prueba desactivado en turnos local por SQL
    puntual y su asignación quitada (`perfil_id: null`).

- [x] **T27. Datos de la consulta para el bloque de solo lectura (C1, C3, C7).** RF-22, RF-28, RF-30, RF-34
  - `HistoriaClinica.sq`, solo lectura: `tiposRegistroDeConsulta`, `tiposLaboratorioDeConsulta` y
    `seccionesArchivoDeConsulta` (`SELECT DISTINCT ... WHERE consulta_id = ? AND deleted = 0 AND activo = 1`).
  - `HcRepository`: flujo de `DatosConsulta` de una consulta (valores, examen físico, tipos de registro y de
    laboratorio, secciones de archivo y cantidad de diagnósticos de esa consulta) y, aparte, los antecedentes del
    paciente en la HC.
  - `SeccionesSoloLectura.kt` (C1): `seccionesConDatos` cuenta solo lo cargado en la consulta (las secciones de
    antecedentes, y cualquier registro `porPaciente`, ya no cuentan; `DatosConsulta.antecedentes` sale de ahí); nueva
    `seccionesDelPacienteSoloLectura(catalogo, antecedentes, enPerfil, ocultas)`: las secciones por paciente con datos
    que no están en el perfil en ningún tipo, con el nombre por defecto, en orden de catálogo y después las legadas,
    para la pestaña "Datos" (RF-34). (C3) `enPerfil` de `seccionesSoloLectura` sin valor por defecto.
  - `SeccionesSoloLecturaTest` ajustado: antecedentes personales con datos → no salen en la consulta y sí en las del
    paciente; antecedentes familiares en el perfil → no salen en ninguna; oculta por el médico y en el perfil → en
    ninguna. `core/data/src/jvmTest/.../repos/DatosConsultaTest.kt` (SQLite en archivo temporal, como
    `ConfigHcRepositoryTest`): una consulta con registro, laboratorio, archivo y diagnóstico, más uno borrado, uno
    inactivo y uno de otra consulta → `DatosConsulta` exacto.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*SeccionesSoloLecturaTest*' --tests '*DatosConsultaTest*'`
    y `.\gradlew :core:data:jvmTest` completo en verde.

- [x] **T28. HC general registrada: definición resuelta, historia clínica y copia de diagnósticos (C2, C5, C9,
  C10).**
  RF-19, RF-24, RF-25, RF-26, RF-33
  (compila; falta prueba manual del usuario)
  - `EspecialidadRegistry.kt`: `EspecialidadContribution.catalogo: CatalogoHc? = null` al final; el registro lo
    expone por código. `especialidades/clinica/.../HcGeneral.kt`: `hcGeneralContribution` con
    `armarDefinicion(catalogoHcGeneral, null).definicion` (para nombre y `existe()`), el renderer de Child-Pugh y
    `catalogo = catalogoHcGeneral`. `AppDi.kt`: `clinicaContribution` → `hcGeneralContribution`;
    `hepatologiaContribution` se queda (C5).
  - `features/hc/.../DefinicionesHc.kt` (`single` en `HcModule.kt`): `resolver(codigo, medicoId): DefinicionResuelta`
    (definición + `sinPerfil` + `primeraVezSinConexion` con `codigo`/`mensaje` + `desconocidas`). Sin catálogo →
    la definición estática de siempre (RF-33). "clinica": `ConfigHcRepository.estado(medicoId, conRed = true)` con
    espera de hasta 5 s (C9: vencida = `estadoConfig` como sin conexión, vale lo guardado, RF-25/RF-26) y
    `armarDefinicion`. `AvisosHcGeneral` en el
    mismo archivo (`single`), para T30.
  - `HistoriaClinicaViewModel`/`HistoriaClinicaScreen.kt`: definición resuelta en un `StateFlow`. Sin perfil →
    "Falta que el administrador te asigne un perfil" (RF-19); primera vez sin configuración → "La primera vez hace
    falta conexión" con el motivo (RF-26). En los dos casos no se ofrece "Nueva consulta" y las anteriores se abren
    para leer ("Decidido" 8); una consulta que ya estaba abierta se puede seguir, cerrar y anular (C10, T29). Con perfil, solo los tipos con secciones (nunca "HC digitalizada"); con uno solo,
    directo.
  - C2: `copiaDiagnosticos(def)` en `hcgeneral/DefinicionHcGeneral.kt`: true si una sección de diagnósticos está en
    `secciones` o `pestanias` de **algún** tipo (no en `definicion.secciones`): unión de tipos para que hematología,
    que la tiene solo en Control, siga copiando igual (RF-33). `nuevaConsulta` la usa. Test en
    `DefinicionHcGeneralTest`: definición armada con la legada de diagnósticos → false; diagnósticos en un solo tipo
    → true; en `secciones` pero en ningún tipo → false.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*DefinicionHcGeneralTest*'`,
    `.\gradlew :especialidades:clinica:jvmTest` en verde, `.\gradlew :composeApp:compileKotlinWasmJs` compila, y el
    usuario confirma en `devWeb` con el médico de prueba: con "Hepatología" la HC "Clínica" de un paciente ofrece
    Consulta y Telemedicina; con un perfil "Prueba C" sin nada en Telemedicina, "Nueva consulta" abre Consulta
    directo; sin perfil → aviso de RF-19, sin "Nueva consulta" y las anteriores se abren; con
    `-Psalud360.entorno.dev.hc.clinica` apuntando a un puerto sin servicio: en un navegador sin datos del sitio →
    aviso de RF-26, y en el navegador que ya recibió la configuración → se arma con la guardada (RF-25).

- [x] **T29. Consulta con las pestañas del perfil y definición congelada.** RF-19, RF-20, RF-21, RF-23, RF-24,
  RF-33; C10
  (compila; falta prueba manual del usuario)
  - `ConsultaViewModel`: resuelve la definición **una vez** al abrir con `DefinicionesHc` y la guarda fuera del
    `flatMapLatest` de `ui` (RF-23: un cambio de perfil con la consulta abierta se ve la próxima vez, sin perder lo
    cargado); `ConsultaUi` suma lo resuelto (sin perfil, primera vez). `seccionesVisibles` delega en la función pura
    de T17 cuando el tipo tiene `grupos`.
  - `ConsultaScreen.kt`: si `tipo.grupos` no está vacío, pestañas = "Datos" + grupos con al menos una sección visible
    (`seccionesVisibles(tipo, ocultas)` y `cumpleCondicion`), abriendo en "Datos" ("Decidido" 7); cada pestaña
    renderiza sus secciones con `RenderSeccion` y no queda nada suelto debajo. Sin perfil o primera vez: solo "Datos"
    y el aviso; una consulta abierta se puede seguir (solo "Datos"), cerrar y anular (C10, RF-19). Sin grupos
    (pediatría y las demás): el código de hoy, intacto.
  - Hecho cuando: `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`: con "Prueba C"
    asignado, una Consulta nueva muestra "Datos", "Uno", "Dos" con esos nombres y secciones en ese orden y abre en
    "Datos"; una Telemedicina solo muestra las pestañas con secciones de Telemedicina; con la consulta abierta, el
    administrador (en otra ventana privada) renombra "Uno" → la pantalla abierta no cambia y lo cargado sigue; al
    reabrirla, el nombre nuevo (RF-23, RF-24); con una consulta abierta, el administrador le quita el perfil → al
    reabrirla solo "Datos" y el aviso, y se puede cerrar o anular, pero la HC no ofrece "Nueva consulta" (C10); se
    le vuelve a poner "Prueba C"; una consulta de pediatría u otra especialidad se ve igual que antes (RF-33).

- [x] **T30. Solo lectura al final de la consulta, datos del paciente en "Datos" y aviso de secciones
  desconocidas.** RF-22, RF-27, RF-30, RF-34; C1, C3, C7
  (compila; falta prueba manual del usuario)
  - `ConsultaViewModel`: con los flujos de T27, `seccionesSoloLectura(catalogo, enPerfilParaTipo = secciones de los
    grupos del tipo, conDatos = seccionesConDatos(...), ocultas, enPerfil = las del perfil en todos los tipos)`
    (C3, explícito) y `seccionesDelPacienteSoloLectura(...)` para "Datos" (C1).
  - `ConsultaScreen.kt`: debajo de la pestaña elegida ("Decidido" 7), el bloque de solo lectura con un
    `SeccionContext` envuelto en `soloLectura = true`; en "Datos", debajo de la ficha y una sola vez, las secciones
    por paciente fuera del perfil, también solo lectura (C1, RF-34). RF-27: si hay `desconocidas` y `AvisosHcGeneral` no
    avisó en esta sesión, un aviso de que hace falta actualizar la app (una vez por sesión, en la HC o la consulta).
  - Hecho cuando: `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`: en una
    consulta con datos en "Nota final" de "Prueba C", el administrador quita esa sección del perfil → al reabrir, sale
    al final, solo lectura, como "Nota" (nombre por defecto); con antecedentes familiares cargados y la sección
    quitada del perfil, se ven en "Datos" y **no** al final de cada consulta (C1); con una fila `prueba_c` en el
    perfil por SQL puntual → el resto se ve y el aviso sale una vez por sesión (otra vez al recargar la página); al
    terminar se borra la fila.

- [x] **T31. Configuración de secciones con el perfil.** RF-28, RF-21, RF-33
  (compila; falta prueba manual del usuario)
  - `hcgeneral/DefinicionHcGeneral.kt`: `mostrarTodas(ocultas, enPerfil): Set<String>` = las ocultas sin las del perfil
    (las de fuera del perfil se conservan); test en `DefinicionHcGeneralTest`.
  - `ConfigSeccionesHcViewModel`/`ConfigSeccionesHcScreen.kt`: para "clinica" usa `DefinicionesHc`: lista las
    secciones del perfil (todas las pestañas, con el nombre del perfil, sin legadas ni desconocidas), misma
    preferencia `secciones_ocultas_clinica`; `setVisible` nunca poda las ocultas de fuera del perfil y "Mostrar
    todas" usa `mostrarTodas` (hoy guarda vacío y las borraría, contra RF-28). Sin perfil o primera vez: el aviso en
    vez de la lista. Las demás especialidades, igual que hoy.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*DefinicionHcGeneralTest*'` en verde,
    `.\gradlew :composeApp:compileKotlinWasmJs` compila y el usuario confirma en `devWeb`: Configuración → "Clínica"
    lista las secciones de "Prueba C" con sus nombres; ocultar "Conductas" → no se ve en la consulta ni sale como
    solo lectura aunque tenga datos; el administrador la quita del perfil y la repone → sigue oculta; "Mostrar todas"
    → vuelve; si el médico tiene pediatría, se ve aparte e igual que antes.

Al terminar la tanda: validación del reviewer, prueba manual del usuario y MEMORY.md.

## Tanda C2 — Cambios de la prueba manual (2026-10-14)

Cambio aprobado por el usuario el 2026-10-14 (spec: definiciones "Cuerpo de la consulta" y "Estudios con historial",
RF-20 ajustado, RF-35 a RF-42; plan: "Decidido" 9 y 10, que reemplazan al 7). Lo de T29/T30 sobre dónde van las
secciones y el bloque de solo lectura queda reemplazado por T32/T33. Todo en este repo, rama `hc-general`.

Reglas de la tanda (las mismas de la tanda C):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: la lógica va a `core/data/.../hcgeneral/` con su test en `commonTest`, que se ve fallar antes del
  código. ViewModels y pantallas no tienen tests: solo muestran y delegan (constitución 4). Si Gradle pide
  `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Interfaz: el agente deja compilado `.\gradlew :features:hc:compileKotlinJvm` y
  `.\gradlew :composeApp:compileKotlinWasmJs` y los pasos manuales; los prueba el usuario con el `devWeb` contra
  XAMPP de `AGENTS.md`. La tarea no se marca hecha hasta que el usuario confirme.
- Sin migración ni columnas nuevas: `registro_clinico` queda como está. No se toca `HcApiSync`/`SyncEngine`, no se
  registra un `HcBackend` para "clinica" y el guardado de los estudios nunca llama a la API.
- Datos de prueba: el médico de prueba con "Hepatología" (ya tiene "Antec. Familiares" + "Consulta" con los cinco
  estudios); los perfiles extra con nombres que empiezan con "Prueba C2", borrados al terminar; al terminar, el
  médico vuelve a "Hepatología". "Clínica general" y "Hepatología" no se editan. Licencia del médico de prueba:
  extenderla y restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.
- RF-33: pediatría y las demás especialidades se ven igual (sin `grupos` y con registros en modo `LISTA`).

- [x] **T32. Cuerpo de la consulta (lógica pura).** RF-20, RF-21, RF-28, RF-35, RF-36
  - `hcgeneral/DefinicionHcGeneral.kt`: `data class LayoutConsulta(pestanias: List<PestaniaDef>, cuerpo:
    List<String>)` y `layoutConsulta(tipo, ocultas, cumple)` en lugar de `pestaniasConsulta`: con los grupos visibles
    de hoy (sin ocultas ni los que no cumplen la condición), `cuerpo` = las secciones del primer grupo cuyo
    `claveDeNombre(titulo) == claveDeNombre("Consulta")` y `pestanias` = "Datos" + los demás grupos en orden. Sin
    grupos: pestañas y cuerpo vacíos (pantalla de siempre). `ConsultaViewModel.pestanias` pasa a devolver el
    `LayoutConsulta` (y `seccionesVisibles` junta pestañas y cuerpo), sin tocar todavía la pantalla más que para
    compilar.
  - `DefinicionHcGeneralTest`: los casos de `pestaniasConsulta` adaptados; "Antec. Familiares" + "Consulta" →
    pestañas "Datos", "Antec. Familiares" y cuerpo con las secciones de "Consulta" en orden; " CONSULTA " y
    "Cónsulta" también son cuerpo, "Consulta del día" no; sin "Consulta" → todo pestañas y cuerpo vacío; solo
    "Consulta" → pestañas = solo "Datos" + cuerpo; "Consulta" con todas sus secciones ocultas → cuerpo vacío y las
    demás pestañas igual; Telemedicina con "Consulta" sin secciones de ese tipo → sin cuerpo; sin grupos → vacío.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*DefinicionHcGeneralTest*'` en verde,
    `.\gradlew :core:data:jvmTest` completo en verde y `.\gradlew :features:hc:compileKotlinJvm` compila.

- [x] **T33. Consulta con "Datos" y pestañas arriba, cuerpo debajo y solo lectura al final.** RF-20, RF-22, RF-34,
  RF-35, RF-36, RF-37, RF-33 — compila; falta prueba manual del usuario.
  - `ConsultaScreen.kt`, solo la rama con `grupos`: `ScrollableTabRow` con `layout.pestanias` (abre en "Datos"; en
    "Datos" la ficha y, debajo, lo de RF-34; en las demás, sus secciones con `RenderSeccion`); después, fuera de la
    pestaña elegida y siempre visibles, las secciones de `layout.cuerpo` con `RenderSeccion`, como las secciones
    sueltas de pediatría; al final, `SoloLecturaFueraDelPerfil(ui.fueraDelPerfil.alFinal, ...)` (RF-37). Sin grupos
    (pediatría y las demás, sin perfil, HC digitalizada): el código de hoy, intacto.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs` compilan y
    el usuario confirma en `devWeb`:
    1. Con "Hepatología", una Consulta nueva muestra arriba solo "Datos" y "Antec. Familiares" (abre en "Datos") y
       debajo, sin pestaña "Consulta", Motivo de consulta … TC / RMN / colangio-RMN en ese orden; al pasar a
       "Antec. Familiares" el cuerpo sigue debajo, con lo cargado.
    2. Una Telemedicina con "Hepatología": arriba "Datos" y "Antec. Familiares", debajo Datos subjetivos, Datos
       objetivos, Exámenes complementarios, Interconsultas y Conductas.
    3. Con un perfil "Prueba C2 sin consulta" (pestañas "Uno" y "Dos"): las dos como pestañas junto a "Datos" y nada
       debajo (RF-36); con "Prueba C2 solo consulta" (una pestaña "consulta" con Nota): arriba solo "Datos" y Nota
       debajo.
    4. Con datos en "Nota" de una consulta (cargada con el médico asignado a "Clínica general") y el médico vuelto a
       "Hepatología" (que no tiene Nota): al reabrirla,
       "Nota" sale al final, solo lectura, debajo del cuerpo, con "Datos" y con "Antec. Familiares" elegidas (RF-37).
    5. Una consulta de pediatría u otra especialidad se ve igual que antes (RF-33).

- [x] **T34. Estudios con historial: modo del registro y lógica pura.** RF-38, RF-39, RF-33
  - `core/model/.../EspecialidadDefinition.kt`: `enum class ModoRegistro { LISTA, UNO_POR_CONSULTA }` y
    `RegistroDef.modo: ModoRegistro = ModoRegistro.LISTA` como último parámetro.
  - `especialidades/clinica/.../HcGeneral.kt`: los registros de `eco_abdominal`, `veda`, `biopsia_hepatica`,
    `estudios_cardiologicos` y `tc_rmn` copiados con `modo = UNO_POR_CONSULTA` (los de `hepatologia` no cambian).
  - `hcgeneral/EstudioConHistorial.kt`: `entradaDeConsulta(registros, consultaId)` (la de fecha más reciente de esa
    consulta, `null` si no hay), `historialEstudio(registros, editada)` (todas menos la editada, sin `detalle` en
    blanco, por `fecha DESC` y `updated_at DESC`) y `cambioEstudio(actual, fecha, texto)` → `Crear`/`Actualizar`/
    `Borrar`/`Nada` (texto en blanco con entrada → `Borrar`; sin entrada → `Nada`; mismo texto y fecha → `Nada`).
  - `EstudioConHistorialTest` (commonTest, registros inventados): consulta sin entrada → `null` y todas en el
    historial; con entrada → no sale en el historial; dos entradas en la misma consulta → se edita la más reciente y
    la otra va al historial; vacías y `"   "` fuera; orden por fecha y, a igual fecha, por `updated_at`; los cuatro
    casos de `cambioEstudio`. `CatalogoHcGeneralTest`: los cinco estudios con `UNO_POR_CONSULTA`, exámenes
    complementarios e interconsultas con `LISTA`.
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*EstudioConHistorialTest*'`,
    `.\gradlew :core:data:jvmTest` completo y `.\gradlew :especialidades:clinica:jvmTest` en verde, y
    `.\gradlew :composeApp:compileKotlinWasmJs` compila.

- [x] **T35. Pantalla de los estudios con historial.** RF-38, RF-39, RF-40, RF-41, RF-42, RF-22 — compila; falta
  prueba manual del usuario.
  - `secciones/EstudioConHistorialSeccion.kt` (nuevo) y `RegistrosSeccion` que delega en él con `UNO_POR_CONSULTA`,
    como dice el plan (sección 4, `features/hc`): campo de esta consulta (fecha + texto) a la izquierda y "Consultas
    previas" a la derecha con desplazamiento y el mismo alto; debajo con menos de 600 dp de ancho; "Ver todo" con la
    ventana grande; con `ctx.soloLectura`, sin editar. `ConsultaViewModel.guardarEstudio` con `cambioEstudio`, en el
    dispositivo, con la espera y el volcado al salir del autoguardado; nunca la API.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs` compilan y
    el usuario confirma en `devWeb` con "Hepatología", en un paciente de prueba:
    1. Consulta A: escribir en Biopsia hepática "texto A" (fecha por defecto = la de la consulta) y cerrarla.
       Consulta B: el campo arranca vacío y a la derecha "Consultas previas" muestra la fecha y "texto A"; escribir
       "texto B", salir enseguida y volver → "texto B" sigue (no se pierde lo último).
    2. Borrar todo el texto de B → B queda sin entrada: no sale en el historial de otra consulta ni como dato.
    3. Con cuatro o más entradas largas, la derecha se desplaza sola sin estirar la sección; "Ver todo" abre la
       ventana grande con "Esta consulta" primero y las previas debajo; cerrarla no cambia nada.
    4. Con la ventana del navegador angosta (menos de 600 px, o el modo teléfono de las herramientas de desarrollo),
       las previas salen debajo del campo.
    5. Consulta A (cerrada): fecha y texto se ven sin poder editarlos; previas y "Ver todo" andan.
    6. Las otras cuatro (Ecografía abdominal, Videoendoscopía digestiva alta, Estudios cardiológicos, TC / RMN /
       colangio-RMN) se ven y guardan igual; Exámenes complementarios e Interconsultas siguen como antes (lista con
       "Agregar"), y un registro de pediatría también (RF-33).
    7. Con el médico en un perfil "Prueba C2" sin Biopsia hepática, la consulta A muestra Biopsia hepática al final,
       solo lectura, con el mismo formato (RF-22, RF-42).

### Decidido (2026-10-14)
1. **Arrastre del último estudio al campo:** no se arrastra. El campo arranca vacío y la última entrada se ve
   primera en "Consultas previas" (RF-38/RF-39, T34/T35).
2. **Fecha del estudio:** se mantiene el selector de fecha, por defecto la fecha de la consulta.
3. **Historial en una consulta vieja:** "Consultas previas" muestra todas las demás entradas del paciente, también
   las posteriores, como la web.
4. **Estudios de hepatología viejos en el dispositivo:** aparecen recién con la tanda D (`7.sqm`); en la prueba
   manual, usar consultas creadas en "Clínica".

Al terminar la tanda: validación del reviewer y prueba manual del usuario.

## Tanda C3 — Laboratorio (2026-10-14)

Cambio aprobado por el usuario el 2026-10-14 (2) (spec: definiciones "Laboratorio" y "Filas ocultas", RF-43 a
RF-54, RF-33 ajustado; plan: sección 4 `features/hc` y `core/ui`, sección 5, "Decidido" 11 a 16). Todo en este repo,
rama `hc-general`.

Reglas de la tanda (las mismas de la tanda C2):
- Un commit local por tarea como `rodri89`, **sin push** (push a `main` publica la web).
- Tests primero: la lógica va a `core/data/.../hcgeneral/` (y los colores a `core/ui`) con su test, que se ve fallar
  antes del código. ViewModels y pantallas no tienen tests: solo muestran y delegan (constitución 4). Si Gradle pide
  `JAVA_HOME`: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`.
- Compilación de interfaz: `.\gradlew :features:hc:compileKotlinJvm` y
  `.\gradlew :composeApp:compileKotlinWasmJs -Pentorno=dev "-Psalud360.entorno.dev.turnos=http://localhost/TurnosOnlineBB-salud360/public/index.php" "-Psalud360.entorno.dev.hc.clinica=http://localhost/HClinica-salud360/public/index.php"`.
  Para que el `devWeb` que ya está corriendo tome el cambio:
  `.\gradlew :composeApp:wasmJsDevelopmentExecutableCompileSync -Pentorno=dev` con las mismas dos `-P`, y recargar
  la página. **No** correr `--stop` ni matar el `devWeb` en uso.
- La tarea no se marca hecha hasta que el usuario confirme los pasos manuales, en modo claro **y** en modo oscuro
  (el tema sigue al sistema: en el navegador, DevTools → Rendering → "Emulate CSS prefers-color-scheme: dark", o el
  modo oscuro del sistema).
- Sin migración ni columnas nuevas: las filas ocultas van en `medico_preferencia` como `secciones_ocultas_clinica`.
  No se toca `HcApiSync`/`SyncEngine`, no se registra un `HcBackend` para "clinica" y nada llama a la API.
- Datos de prueba: el médico de prueba con "Hepatología" (tiene Laboratorio general y Peso) y un perfil
  "Prueba C3" (Consulta: Laboratorio general, Laboratorio reducido, Peso), borrado al terminar; el médico vuelve a
  "Hepatología". Pacientes de prueba con dos o más cargas de laboratorio. Licencia del médico de prueba: extenderla y
  restaurarla como dice `MEMORY.md`. Ningún dato real ni credencial en el repo.

- [x] **T36. Filas visibles del laboratorio (lógica pura).** RF-44, RF-45, RF-47, RF-48, RF-49
  - `hcgeneral/FilasLaboratorio.kt`: `clavePreferenciaFilasOcultas(codigo, laboratorioTipo)`,
    `ocultasDePreferencia(valor?)`, `puedeElegirFilas(codigo, analitos)`, `filasVisibles(analitos, ocultas)`,
    `alternarFila(analitos, ocultas, clave, visible)`, `esUltimaVisible(analitos, ocultas, clave)`,
    `mostrarTodasLasFilas(analitos, ocultas)` y `textoBotonFilas(analitos, ocultas)`, como dice el plan (sección 5).
  - `FilasLaboratorioTest` (commonTest, analitos inventados con dos grupos): clave
    `filas_ocultas_clinica_laboratorio_general` y distinta para `laboratorio_reducido`; `ocultasDePreferencia` con
    null, "" y "a,,b"; `filasVisibles` sin ocultas → todas en orden, con una oculta → sin ella y el resto en orden,
    con todas ocultas → todas, con una clave que el laboratorio no tiene → se ignora; `alternarFila` oculta, muestra,
    se niega a ocultar la última visible y conserva claves de afuera; `esUltimaVisible`; `mostrarTodasLasFilas`
    conserva las de afuera; `textoBotonFilas` sin ocultas, con 3 y sin contar las de afuera; `puedeElegirFilas`
    true para "clinica" con 2 analitos, false con 1 (Peso) y false para "pediatria".
  - Hecho cuando: `.\gradlew :core:data:jvmTest --tests '*FilasLaboratorioTest*'` y `.\gradlew :core:data:jvmTest`
    completo en verde.

- [x] **T37. "Elegir filas" en el laboratorio.** RF-43, RF-44, RF-45, RF-46, RF-47, RF-48, RF-49, RF-33
  (compila; falta prueba manual del usuario)
  - `secciones/LaboratorioSeccion.kt`, como dice el plan (sección 4, `features/hc`): filas de
    `filasVisibles(labDef.analitos, ocultas)` (cabeceras de grupo de la lista filtrada), botón en el `trailing` del
    `SectionCard` solo con `puedeElegirFilas` (también en solo lectura) con `textoBotonFilas`, y diálogo con una
    `CheckboxField` por analito (agrupadas, con desplazamiento), la última visible deshabilitada con "Tiene que
    quedar al menos una fila visible", "Mostrar todas" y "Cerrar"; cada casilla guarda con
    `ctx.vm.guardarPreferencia`. El mapa `valores` de la carga sigue con todas las claves.
  - Hecho cuando: `.\gradlew :features:hc:compileKotlinJvm` y `:composeApp:compileKotlinWasmJs` (con las `-P` de
    arriba) compilan, el `devWeb` se refresca con `wasmJsDevelopmentExecutableCompileSync`, y el usuario confirma,
    en modo claro y en modo oscuro:
    1. Con "Hepatología", en Laboratorio general, "Elegir filas" abre la lista con las 51 filas en orden y todas
       tildadas (los laboratorios de la HC general no tienen grupos; las cabeceras de grupo salen de la lista
       filtrada y se cubren con el código de siempre); destildar dos (una con valores en una carga anterior) →
       desaparecen al momento de todas las columnas, y el botón dice "Elegir filas (2 ocultas)".
    2. Ocultar todas menos una → la casilla de la última queda deshabilitada y sale el mensaje.
    3. "Nueva carga" (o "Editar" una carga con valores en una fila oculta): la columna editable no muestra la fila
       oculta; guardar; "Mostrar todas" → la fila vuelve con su valor intacto en esa carga y en las anteriores.
    4. Recargar la página y reabrir la consulta → las filas siguen ocultas; una consulta cerrada del mismo paciente
       también las oculta y ofrece el botón.
    5. Con "Prueba C3": ocultar "Glucemia" en Laboratorio reducido no la oculta en Laboratorio general, y Peso no
       muestra "Elegir filas".
    6. Con otro médico de prueba de la HC general, si lo hay en la base local, el mismo paciente muestra todas las
       filas (si no lo hay, se anota como no probado); un laboratorio de pediatría no tiene el botón y se ve igual
       que antes, salvo la celda editable de T38 (RF-33, RF-49).

- [ ] **T38. Celda editable que se distingue.** RF-50, RF-51, RF-52, RF-53, RF-54
  - `core/ui`: `theme/ColoresCelda.kt` (`ColoresCelda`, `coloresCelda(oscuro)`, `contraste`) con los valores de la
    tabla del plan, y `CeldaEditable` en `components/FormFields.kt` (fondo, borde 1 dp, borde de foco 2 dp, cursor
    y texto de ayuda "Valor"), como dice el plan (sección 4, `core/ui`).
  - `LaboratorioSeccion.kt`: la `CeldaEditable` privada se borra y se usa la de `core/ui`; las celdas de solo
    lectura no cambian.
  - `core/ui/src/jvmTest/.../theme/ColoresCeldaTest.kt`: `contraste(negro, blanco)` = 21 (±0,1); en los dos modos,
    borde y bordeFoco contra fondo ≥ 3, borde contra `surface` y contra `surfaceVariant` al 50 % sobre `surface` ≥ 3,
    texto y ayuda contra fondo ≥ 4,5, fondo ≠ `surface`.
  - Hecho cuando: `.\gradlew :core:ui:jvmTest` en verde, `.\gradlew :features:hc:compileKotlinJvm` y
    `:composeApp:compileKotlinWasmJs` (con las `-P` de arriba) compilan, el `devWeb` se refresca con
    `wasmJsDevelopmentExecutableCompileSync`, y el usuario confirma, en modo claro y en modo oscuro:
    1. En "Nueva carga" de Laboratorio general, cada celda de la columna editable tiene fondo y borde visibles en
       las filas blancas y en las grises, y las de las fechas anteriores no tienen ni borde ni fondo propio.
    2. Las celdas vacías dicen "Valor" (atenuado); al escribir desaparece, y al guardar no queda "Valor" guardado.
    3. Con clic y con tabulador, la celda con foco tiene el borde más grueso y de otro color, y se ve el cursor
       (también en oscuro); al salir vuelve el borde normal.
    4. Un laboratorio de pediatría muestra la misma celda nueva y carga y guarda igual que antes (RF-54).

Al terminar la tanda: validación del reviewer y prueba manual del usuario.
