# Historia clínica de pediatría contra su propia base: estado y plan

Documento de continuidad. Lo que sigue alcanza para retomar sin el historial de la conversación
donde se hizo. Última actualización: 2026-09-24 (fases 1 y 2 terminadas y verificadas).

## Qué se está haciendo y por qué

La historia clínica de pediatría vivía **solo en el dispositivo**: el motor de sincronización de la app
apunta al servidor propio en Kotlin, que no está desplegado, así que cada fila quedaba marcada como
pendiente para siempre. La historia clínica real está en el Laravel de pediatría, el que los médicos
usan en `pediatria.hclinicadigital.com`.

El objetivo es que la app lea y escriba **esa misma base**, para que lo que el médico carga en el
teléfono lo vea en la web y al revés. Una sola historia clínica, no dos paralelas.

## Decisiones ya tomadas (no volver a discutirlas)

| Tema | Decisión |
|---|---|
| Dónde guarda la API | En las tablas que ya usa la web de pediatría, traduciendo desde el modelo genérico de la app |
| Identidad | turnosonlinebb es el único proveedor. La API de pediatría **no tiene login ni emite tokens** |
| Vínculo de pacientes | Columna `pacientes.paciente_id_tobb` en pediatría, espejo de `users.medico_id_tobb` |
| Alcance | Por fases. Fase 1: consulta, secciones de texto y examen físico |
| Sin señal | La app sigue funcionando. La consulta se abre local y se reconcilia después |
| Si turnos no responde | La sesión ya validada vale 24 horas |

### Cómo funciona el ingreso

El médico entra **una sola vez, contra turnosonlinebb, con su contraseña de turnosonlinebb**. La app
manda ese mismo token a pediatría; pediatría lo valida contra `GET auth/perfil` de turnos y ubica al
usuario local por `users.medico_id_tobb`. La contraseña de pediatría no se usa desde la app.

Hacen falta **dos interruptores independientes**, cada uno con su código de error:

1. El administrador habilita `pediatria` al médico en turnos (`salud360_medico_hc`) → si falta, `hc_no_habilitada`.
2. El usuario de pediatría tiene cargado el número de médico de turnos → si falta, `medico_no_vinculado`.

---

## Estado

### Hecho y verificado

**Backend de pediatría** (probado con curl contra el MAMP):

- Identidad completa: sin token, token inválido, historia clínica no habilitada, médico no vinculado.
  Cada uno con su código propio. Las tablas de apoyo se crean solas.
- `pacientes/resolver`: crea, vincula por documento, es idempotente, y ante documento duplicado
  devuelve los candidatos en vez de adivinar. Probado con dos hermanos con el mismo documento.
- Consultas: abrir, listar, leer, guardar secciones, guardar examen, cerrar con fecha.
- Permisos: se escribe solo sobre consultas propias, se lee cualquiera de un paciente de la cartera.
- **`medico_infos` no se toca**: verificado con un hash de la tabla antes y después.
- El contenido cargado por la API aparece en `motivo_consultas`, `observaciones`, `conductas` y
  `examen_fisicos`, que es lo que lee la web de pediatría.

**App**: compila en Android, web y servidor; los tests pasan.

**Médicos**: en el MAMP ya pueden entrar 10 de los 23. Ver el paso 2, que además explica por qué los
otros están frenados por una decisión y no por trabajo pendiente.

**Las fases 1 y 2 andan de punta a punta.** `HcPediatriaE2ETest` recorre el camino completo del lado
de la app —base local con filas pendientes, `HcApiSync`, `HcPediatriaBackend` y el cliente HTTP—
contra la API de verdad, y vuelve a leer de la API lo que quedó guardado. Pasa: la consulta se crea,
y llegan a las tablas que lee la web las secciones de texto, el examen físico, los nueve formularios,
el desarrollo madurativo y las cuatro listas, con su alta, su modificación y su baja. Lo único que
queda afuera de la prueba es la pantalla. Falta la fase 3: los archivos.

Cómo correrla (se saltea sola si no se le pasan las propiedades, así el `jvmTest` de siempre no
necesita servidor):

```
./gradlew :core:data:jvmTest \
  -Psalud360.test.hc.pediatria=http://localhost:8888/HCPediatria/public_html/pediatria/HCDPediatria/public/index.php \
  -Psalud360.test.hc.token=<token en claro de tobb.salud360_tokens>
```

La consulta que crea la prueba se borra al terminar, salvo que haya caído sobre una consulta que ya
existía (ver la regla de reutilización más abajo): en ese caso no borra nada.

### Lo primero a verificar al retomar

Lo que falta probar es **la pantalla**, no el envío: que lo que el médico teclea llegue a la base
local. La consulta 5, la que había creado la app, estaba vacía porque no se guardó nada en el
dispositivo, no porque el envío fallara —la prueba de punta a punta usó esa misma consulta y el
contenido llegó sin tocar nada.

Queda entonces: entrar, escribir en una sección, salir de la pantalla y mirar la pestaña Red
(¿sale el `PUT .../secciones`?). Si no sale, mirar `HcApiSync.drenar`; si sale y no aparece en la
base, el log de Laravel de pediatría.

### Bugs ya encontrados y corregidos

Ninguno salió al compilar; todos al probar:

1. **Permisos que no cortaban.** El control devolvía la consulta o una respuesta de error, y se
   distinguía con `is_object()`. Una respuesta también es un objeto, así que seguía de largo. Se
   cambió por `instanceof JsonResponse`.
2. **Koin borra los genéricos.** `Map<String, HcApiClient>` y `Map<String, HcBackend>` quedaban como la
   misma definición: una pisaba a la otra y pedir los clientes desde los backends se resolvía a sí
   mismo, con recursión infinita al arrancar. Se envolvieron en `HcApiClients` y `HcBackends`.
3. **Orden del bloque de arranque.** El `init` del modelo de vista estaba antes de declarar el flujo de
   la consulta; como ese ámbito ejecuta de inmediato, leía un valor nulo y la app se caía al abrir la
   consulta. Va después.
4. **El último guardado no ocurría.** Al salir de la pantalla, `onCleared` volcaba lo que quedaba en
   memoria con `viewModelScope`, pero ese ámbito ya está cancelado cuando `onCleared` corre: el
   `launch` no hacía nada y se perdía lo tecleado en los últimos 600 ms, **incluso en el dispositivo**.
   Ahora usa un ámbito propio, que nadie cancela. Es el candidato más probable de la consulta vacía.
5. **Las secciones que pediatría ignora pasaban en silencio.** La API contesta `desconocidas` con las
   secciones que todavía no traduce (todas las estructuradas, que son fase 2) y la app no lo miraba.
   Ahora queda en el log. Ojo: igual se marcan como enviadas, ver la nota de la fase 2.

---

## Archivos

### Repo de pediatría (`/Applications/MAMP/htdocs/HCPediatria/public_html/pediatria/HCDPediatria`)

En la rama `salud360-api` de `github.com/rodri89/hc_pediatria`. **Todavía no está en producción, a
propósito**: el paso 3 explica en qué orden va.

| Nuevos | |
|---|---|
| `app/Http/Controllers/Api/Salud360/` | `Salud360Controller` (base), `Auth`, `Paciente`, `Consulta` |
| `app/Services/Salud360/` | `TobbAuthService`, `PacienteVinculoService`, `SeccionesService` (textos, formularios y desarrollo madurativo), `RegistrosService` (las listas), `ColumnasLegacy` |
| `app/Http/Middleware/` | `Salud360Api`, `Salud360Cors` |
| `config/salud360.php` | URL de turnos, caché, gracia, autoprovisión |
| `database/migrations/2026_09_22_000000_add_paciente_id_tobb_to_pacientes_table.php` | |
| `md/API_SALUD360_PEDIATRIA.md` | Contrato completo de la API |

| Modificados | |
|---|---|
| `routes/api.php` | Grupo nuevo debajo del stub |
| `app/Http/Kernel.php` | Dos líneas: middleware de dominios cruzados y alias |

El `.env` local se tocó para apuntar a turnos del MAMP. Está ignorado por git.

### Repo de la app

En la rama `hc-pediatria-fase-2`. Todavía sin unir a `main`.

| Nuevos | |
|---|---|
| `core/data/.../network/hc/` | `HcApiClient` (+ `HcApiClients`), `HcDtos` |
| `core/data/.../repos/HcBackend.kt` | Interfaz (+ `HcBackends`) |
| `core/data/.../repos/HcPediatriaBackend.kt` | Implementación |
| `core/data/.../sync/HcApiSync.kt` | Motor de envío diferido |
| `core/database/.../migrations/3.sqm` | Columna `consulta.remoto_id` |
| `core/database/.../migrations/4.sqm` | Columna `registro_clinico.remoto_id` |
| `core/data/src/jvmTest/.../repos/HcPediatriaE2ETest.kt` | La prueba de punta a punta contra la API real |

Modificados: `DataModule`, `Mappers`, `AuthRepository` (propaga el token), `HcRepository` (backends),
`HistoriaClinica.sq`, `Consulta.kt` (campo `remotoId`), `ConsultaViewModel`, `HcModule`,
`gradle.properties` (dominio de pediatría), `core/data/build.gradle.kts` (propiedades de la prueba).

---

## Entorno de prueba local

**Importante:** en desarrollo la app apunta al **MAMP**, no a producción. Por eso se puede probar todo
sin subir nada. `pediatria.hclinicadigital.com` solo se usa al compilar para producción.

```
dev     → http://{host}:8888/HCPediatria/public_html/pediatria/HCDPediatria/public/index.php
release → https://pediatria.hclinicadigital.com
```

Levantar la app: `./gradlew devWeb` y abrir `http://localhost:8080`.

**Médico de prueba:** el médico 1 de turnos, que es el usuario 2 de pediatría (el mail está en
`tobb.medicos`). Es el único vinculado que además tiene pacientes.

**Paciente de prueba:** `MATEO PRUEBA HC`, documento 42555111. Es el id 24726 en turnos y el 3660 en
pediatría, ya vinculados. Se creó porque el paciente que había tenía documento `3`, que la API rechaza
con razón.

**Tokens de prueba** en `tobb.salud360_tokens`, para probar con curl o con la prueba automática sin
contraseñas. Los valores en claro **no se anotan acá**: este repo es público. En la base quedan cuatro
filas —una de médico vinculado (`user_id` 4) y tres para los casos de error, `ClaudePruebaVinculado`,
`ClaudePruebaSinVincular` y `ClaudePruebaSinHc`—, pero de un hash no se vuelve al texto. Para trabajar
hay que generar uno nuevo, que es una sola sentencia:

```sql
INSERT INTO tobb.salud360_tokens (user_id, nombre, token_hash, expira_en, created_at, updated_at)
VALUES (4, 'Prueba', SHA2('<texto elegido>', 256), DATE_ADD(NOW(), INTERVAL 30 DAY), NOW(), NOW());
```

**Para limpiar** los datos de prueba: paciente 24726 en turnos, paciente 3660 con sus consultas y los
cuatro tokens. La prueba automática da de baja lo que crea —la consulta y las cuatro listas—, pero los
antecedentes perinatales y neonatales **no**: son del paciente, no de la consulta, así que sobreviven
a cada corrida (fila 1 de `antecedentes_perinatales` y de `antecedentes_neonatales`).

---

## Cosas que hay que saber antes de tocar

- **Nunca escribir `medico_infos` desde la API.** Guarda qué paciente y qué consulta está mirando el
  médico **en la web**; pisarla le mueve la pantalla mientras atiende. La API recibe los identificadores
  explícitos. No copiar el patrón de `MedicoController`.
- **Nunca pisar la ficha del paciente con vacíos.** Pediatría tiene padre, madre, sexo y hermanos que
  turnos no conoce, y la app manda vacío. Solo se completan campos vacíos.
- **Nunca adivinar con documentos duplicados.** Hay 19 casos reales en pediatría. Se desempata por
  fecha de nacimiento y apellido; si queda duda, elige el médico.
- **Al agregar o cambiar `config/salud360.php` hay que correr `php artisan config:clear`.** La
  configuración cacheada no incluye archivos nuevos y la URL de turnos queda vacía. Ya costó un rato.
- **No correr `php artisan migrate` en producción** sin revisar antes: en turnos hay migraciones viejas
  sin registrar cuyas columnas ya existen, y el comando falla antes de llegar a las nuevas. Las tablas
  de esta API y la columna del vínculo **se crean solas**.
- **El autoguardado de la consulta dispara cada 600 ms.** Nunca llamar a la API desde ahí: para eso está
  `HcApiSync`, que junta los cambios con una espera de 4 segundos.
- **La fecha clínica de la consulta es `created_at`**, no hay columna de fecha. Igual que en
  `establecerActivo` de la web.
- **`consultas/abrir` reutiliza la consulta abierta que el médico ya tenga de ese paciente y tipo**, y
  lo avisa con `reutilizada: true`. Es a propósito: evita duplicados cuando la app reintenta sin señal.
  Dos consecuencias: al probar, nunca borrar la consulta que devuelve `abrir` sin fijarse si ya existía
  (la prueba automática lo controla), y si el médico dejó una consulta abierta de ayer, la de hoy se le
  suma encima. Lo segundo todavía no se resolvió; hoy no molesta porque la app también reutiliza la
  consulta abierta del dispositivo.
- **El documento es un entero en pediatría.** Hay 57 pacientes en turnos con documento inválido, varios
  **negativos** (`-57758898`). Conviene corregir ese dato en turnos.
- **En pediatría, las columnas de opciones tienen tres estados: `1`, `0` y `2` = "sin cargar"**, que
  es con lo que nacen. La web solo pregunta por el `1`. Un campo que la app no manda tiene que quedar
  en 2: escribir 0 sería inventar una respuesta ("no", "-", "anormal") que el médico nunca dio.
- **Salvo en la consulta prenatal, que numera al revés**: ahí es `1` y `2`, y el vacío es `0`. Son
  tres tablas (`familias`, `embarazo_actuals`, `antecedentes_obstetricos`) y el traductor las marca
  con el tipo `opcion`. No se unificó a propósito: son las columnas que ya lee la web.
- **Exámenes complementarios e interconsultas nacen con `activo = 2` y se confirman al cerrar la
  consulta.** Es lo que hacen `setActivoExamenesComplementarios` y `setActivoInterconsulta` de la web;
  la API hace lo mismo en `cerrar`. Sin esa vuelta, la web no los vuelve a mostrar nunca.
- **La internación cuelga de la ficha de antecedentes personales por clave foránea.** Si no existe, la
  API la crea vacía: insertar con `antecedentes_personales_id = 0` falla.
- **`medicos.castigo_automatico` no castiga nada: decide si el médico se muestra para sacar turno.**
  Es el "Mostrar Medico" del administrador, y las vistas del paciente saltean al que tiene 0. Nada en
  el código lo usa para penalizar. Sirve para el médico que solo usa historia clínica (ver el paso 2).

---

## Plan

### Paso 1: cerrar la fase 1 (lo inmediato)

El envío ya está confirmado por `HcPediatriaE2ETest`. Lo que queda es todo de pantalla, así que va
con la app abierta en el navegador:

1. Escribir en una sección y confirmar que llega. Es lo que quedó sin probar desde la interfaz.
2. Escribir treinta segundos seguidos y contar los pedidos en la pestaña Red: tienen que ser unos
   pocos, no uno por tecla.
3. Modo avión: escribir, ver el indicador de pendientes, recuperar señal y confirmar que se envía solo.
4. Abrir una consulta **sin señal**, escribir, recuperar y verificar que se creó en pediatría con todo.
5. Cerrar desde la app y verla cerrada en la web de pediatría, con fecha y edad correctas.
6. Cargar peso y talla y ver la curva de crecimiento en la web.
7. Escribir una palabra y salir de la pantalla **en el acto**, antes del segundo: tiene que llegar
   igual. Es el bug 4, recién corregido, y no lo cubre la prueba automática.

### Paso 2: los médicos (bloquea usuarios, no es trabajo de código)

De los 23 médicos activos de pediatría, hoy **pueden entrar 10**. De los 13 que no: 10 son médicos de
verdad que hay que dar de alta en turnos, 2 son usuarios de prueba y 1 está de baja en turnos. Hacen
falta los dos interruptores: el número de turnos en pediatría y la habilitación en turnos.

**Hecho en el MAMP** (falta correrlo en producción; las dos sentencias son idempotentes y se pueden
repetir sin hacer daño):

```sql
-- 1. Vincular por mail a los que ya existen en turnos. En la copia de hoy resolvió 3
--    (los médicos 25, 26 y 30 de turnos).
UPDATE hc_pediatrica.users p
JOIN tobb.medicos m ON LOWER(TRIM(m.mail)) = LOWER(TRIM(p.email))
SET p.medico_id_tobb = m.id, p.updated_at = NOW()
WHERE p.usuario_tipo = 2 AND p.activo = 1 AND p.medico_id_tobb = 0 AND m.activo = 1;

-- 2. Habilitar "pediatria" en turnos a todo médico ya vinculado. Resolvió 9.
INSERT INTO tobb.salud360_medico_hc (medico_id, hc_codigo, activo, created_at, updated_at)
SELECT p.medico_id_tobb, 'pediatria', 1, NOW(), NOW()
FROM hc_pediatrica.users p
JOIN tobb.medicos m ON m.id = p.medico_id_tobb AND m.activo = 1
WHERE p.usuario_tipo = 2 AND p.activo = 1 AND p.medico_id_tobb <> 0
  AND NOT EXISTS (SELECT 1 FROM tobb.salud360_medico_hc h
                  WHERE h.medico_id = p.medico_id_tobb AND h.hc_codigo = 'pediatria');
```

Probado con un segundo médico (el 8 de turnos): turnos informa `historias_clinicas: ["pediatria"]` y
pediatría lo acepta y lo resuelve contra su propia cartera. Los dos interruptores funcionan para
alguien que no sea el médico 1.

**El cruce por mail rinde poco**: de los 15 en cero resolvió 3. Los otros 10 (sin contar los dos
usuarios de prueba) directamente **no existen en turnos**, ni por mail ni por apellido. Ordenados por
tamaño de cartera en pediatría, que es lo que se deja afuera mientras no estén:

Van por id de `hc_pediatrica.users`, no por nombre: este repo es público y son personas reales. El
nombre sale de la base.

| Usuario de pediatría | Pacientes |
|---|---|
| 10 | 850 |
| 13 | 466 |
| 29 | 384 |
| 8 | 230 |
| 34 | 84 |
| 9 | 73 |
| 36 | 11 |
| 26 | 1 |
| 14 | 0 |
| 35 | 0 |

También queda afuera el usuario 12, que sí está vinculado (médico 6 de turnos) pero está dado de baja
allá (`medicos.activo = 0`). Por eso no se le habilitó nada: con la baja no puede entrar.

#### Cómo darlos de alta sin publicarlos para sacar turno

El que solo usa historia clínica no debería aparecer en la lista de médicos del paciente: no tiene
horarios cargados. Y **el médico tiene que estar activo igual**, porque la API lo busca así
(`medicoPropio` es `medicos.user_id = ? AND activo = 1`); si no lo encuentra, el perfil viene con
`medico: null` y pediatría rechaza con `sin_permiso`.

No hace falta nada nuevo: **la columna que decide si se publica ya existe y es
`medicos.castigo_automatico`**, aunque el nombre no lo diga. En el alta de médicos del administrador
es el par de opciones rotulado **"Mostrar Medico"**, y las tres vistas que listan médicos
(`seleccionar_medico`, `seleccionar_medico_consultorio`, `mostrar_medicos_index`) saltean al que
tiene 0. Ya hay 6 médicos activos así, o sea que es un camino usado, no un invento.

Entonces el alta de estos diez es:

1. Usuario en `tobb.users` con `usuario_tipo = 2` y su contraseña (es con la que entran a la app).
2. Fila en `tobb.medicos` con ese `user_id`, especialidad Pediatría, un consultorio cualquiera,
   **`activo = 1` y `castigo_automatico = 0`** ("Mostrar Medico: No").
3. Las dos sentencias de arriba, que los vinculan y les habilitan la historia clínica.

Se probó primero con uno y se mira la web pública antes de seguir con el resto. Nada de esto toca
código: es carga de datos desde el administrador de turnos.

El nombre `castigo_automatico` para "se muestra o no" es una trampa para el que venga después —
conviene renombrarlo alguna vez, pero no mientras se dan de alta médicos.

### Paso 3: subir a producción, en este orden

1. Commitear y subir el repo de pediatría.
2. `php artisan config:clear` en el servidor.
3. Correr las dos sentencias del paso 2 contra las bases de producción, y revisar el resultado: el
   cruce por mail puede resolver más o menos médicos que en la copia local.
4. Probar con curl contra producción los mismos casos que en local.
5. Recién ahí desplegar la app.

Si se despliega la app primero no se rompe nada: todo se guarda igual en el dispositivo y queda
pendiente de envío, pero se ven avisos de que no se pudo enviar.

### Paso 4: fase 2, las secciones estructuradas

**Terminada.** Todo lo que la app carga en una consulta de pediatría llega a las tablas que lee la
web, y `HcPediatriaE2ETest` lo prueba de punta a punta: manda un caso de cada forma y lo relee de la
API.

| En la app | En pediatría | |
|---|---|---|
| `alimentacion` | `alimentacions` | una fila por consulta |
| `perinatales` | `antecedentes_perinatales` | una por paciente |
| `neonatales` | `antecedentes_neonatales` | una por paciente |
| `antecedentes_personales` | `antecedentes_personales` | una por consulta |
| `antecedentes_familiares` | `antecedentes_familiares` | una por consulta |
| `prenatal_familia` | `familias` | una por consulta |
| `prenatal_embarazo` | `embarazo_actuals` | una por consulta |
| `prenatal_obstetricos` | `antecedentes_obstetricos` | una por consulta |
| `desarrollo` | `desarrollo_madurativo_pacientes` | una fila por hito |
| las cuatro listas | `examenes_complementarios`, `interconsultas`, `screenings`, `internaciones` | varias filas |

Las de columnas fijas están declaradas en `SeccionesService::FORMULARIOS` y las listas en
`RegistrosService::TIPOS`: agregar una es agregar una entrada.

**Lo que hubo que tocar en la app** (las secciones ya viajaban solas; esto no):

- Los **antecedentes** no son una sección: viven en la tabla `antecedente`. Viajan como si lo fueran,
  una sección por categoría, con el valor `"<tilde>|<detalle>"`.
- Las **listas** necesitaron `registro_clinico.remoto_id` (migración `4.sqm`), porque la fila se crea
  en el dispositivo sin señal y hay que saber con qué id quedó del otro lado: sin eso, el segundo
  envío la duplica. La API devuelve `ids: {id local => id de pediatría}` y la app lo anota.
- `guardarAntecedente`, `guardarRegistro` y `eliminarRegistro` ahora marcan la consulta para enviar;
  antes solo lo hacían las secciones y el examen.

**Lo que quedó fuera de la fase 2, a propósito:**

- Los **archivos** de las secciones que los aceptan (neonatales, internaciones, exámenes
  complementarios) son la fase 3.
- Las **vacunas en grilla** siguen como texto libre, igual que antes.
- Lo que ya se haya cargado en el dispositivo **y se haya marcado como enviado sin que pediatría lo
  guardara** no se reenvía solo. Pasó con todo lo estructurado mientras la API no lo traducía: la app
  lo mandaba, pediatría lo contestaba en `desconocidas` y la app lo daba por enviado igual (si no, el
  médico vería el aviso de pendientes en rojo para siempre). Hoy no hay datos reales así —solo los de
  prueba—, pero si aparecen, hay que recorrer las consultas con `remoto_id` y mandar sus valores otra
  vez sin mirar el pendiente.

### Paso 5: fase 3, los archivos

Las cuatro tablas de fotos. Envío multiparte y almacenamiento del hosting. Ojo: las fotos se guardan con
ruta relativa bajo `public/img/<prefijo del mail del médico>/`, así que hay que exponer una URL absoluta
o un endpoint propio.

### Paso 6: fase 4

Pendientes, secretarias, aviso de edición simultánea, y extraer la base común entre el cliente de turnos
y el de historia clínica, que hoy son gemelos a propósito.

---

## Verificación

App: `./gradlew :androidApp:compileDebugKotlin`, `:composeApp:compileKotlinWasmJs`,
`:server:compileKotlin`, `:core:data:jvmTest`.

Punta a punta contra el MAMP (`HcPediatriaE2ETest`, ver arriba cómo se corre): es la versión
automática de "cargar algo y verlo del otro lado", sin la pantalla.

Backend: `php -l` sobre los archivos tocados, y las pruebas con curl del contrato, que están en
`md/API_SALUD360_PEDIATRIA.md` del repo de pediatría.

La prueba que importa sigue siendo la de siempre: cargar algo **desde la app** y verlo en la web de
pediatría con el mismo médico.
