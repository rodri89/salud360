# Historia clínica de pediatría contra su propia base: estado y plan

Documento de continuidad. Lo que sigue alcanza para retomar sin el historial de la conversación
donde se hizo. Última actualización: 2026-09-28 (las tres fases terminadas, probadas desde la pantalla
y desplegadas).

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
3. La licencia de pediatría está vigente → si no, `licencia_vencida`.

La licencia es el tercero y es distinto de los otros dos: no lo decide la app ni turnos, lo decide la
base de pediatría (`medico_licencias`), que es donde se cobra. Es la misma regla que la web aplica al
iniciar sesión, así que un médico que no puede entrar por la web tampoco entra por la app.
**El administrador no tiene licencia y entra siempre**: la licencia es del médico.

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
queda afuera de la prueba es la pantalla.

**La fase 3, los archivos, también anda de punta a punta.** `HcPediatriaE2ETest` manda dos adjuntos
—uno de la consulta y otro colgado de un examen complementario—, los encuentra en la galería y los
vuelve a bajar. Verificado el 2026-09-24 contra el XAMPP de la máquina de Windows, con las bases de
producción restauradas: las dos filas quedaron en `consulta_fotos` y en
`examenes_complementarios_fotos`, y la imagen de 8×8 píxeles de la prueba **no** se agrandó, o sea
que el redimensionado respeta la proporción.

**Y las tres fases están probadas desde la pantalla, contra producción** (2026-09-26). Se entró con
un médico real desde la web compilada con `-Pentorno=release`, se dio de alta un paciente, se abrió
una consulta, se escribió, y se adjuntó una foto y un PDF: aparecen en la web de pediatría. Con eso
el plan queda cumplido; lo que sigue son los pendientes del paso 3 y lo que no es código.

Cómo se corre esa prueba manual, que es la que importa:

```
./gradlew :composeApp:wasmJsBrowserDevelopmentRun -Pentorno=release
```

Levanta la web en `http://localhost:8080` **apuntando a producción** (turnos y pediatría), con la
página servida localmente. Es la forma de probar el código nuevo con datos y servidores de verdad sin
desplegar la app.

Cómo correrla (se saltea sola si no se le pasan las propiedades, así el `jvmTest` de siempre no
necesita servidor):

```
./gradlew :core:data:jvmTest \
  -Psalud360.test.hc.pediatria=http://localhost:8888/HCPediatria/public_html/pediatria/HCDPediatria/public/index.php \
  -Psalud360.test.hc.token=<token en claro de tobb.salud360_tokens>
```

La consulta que crea la prueba se borra al terminar, salvo que haya caído sobre una consulta que ya
existía (ver la regla de reutilización más abajo): en ese caso no borra nada.

### Lo primero a hacer al retomar

Los dos repositorios están al día y la app está desplegada. Lo que queda no es código:

1. **Los límites del hosting**, que es lo que rompe la web de a ratos. Ver la advertencia sobre la
   saturación de conexiones. Hay una mitigación puesta —el conector reintenta— pero el arreglo de
   fondo es juntar las llamadas de la web, y es lo que más molesta hoy a los médicos.
2. **`APP_DEBUG=false` en producción.** Hoy cualquier error devuelve el volcado completo, con rutas
   absolutas del servidor y la traza entera.
3. Los médicos que faltan dar de alta (paso 2) y las fichas de pacientes que quedaron sin vincular
   (ver "Los pacientes y su vínculo con turnos").
4. Las pruebas sin señal, que es lo único de la app que nunca se probó: modo avión, escribir, recuperar
   y confirmar que se envía solo. Ver el paso 1.
5. **Subir las licencias a pediatría**: `TobbAuthService.php`, `Salud360Api.php`,
   `LicenciaController.php` y `routes/api.php`, con el `config:clear` de siempre. Revisar antes las
   fechas vencidas, porque desde ese momento bloquean. Ver "Las licencias".

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
6. **Un privado tapando a un protegido tumbaba la API entera.** Al mover `atiende` a la clase base
   quedó la copia privada de `PacienteController`, y eso en PHP no es un aviso sino un error fatal:
   toda la API respondía 500. No lo agarró `php -l`, que mira un archivo por vez y no la herencia;
   lo agarró la prueba de punta a punta en el primer pedido.
7. **Un archivo que no se subió tiraba el ingreso de todos los médicos.** En turnos faltaba
   `app/Services/Salud360/HistoriaClinicaService.php`, que `formatearMedico` invoca al armar el
   perfil. Como el administrador no pasa por ahí, entraba; cualquier médico recibía 500. **Ninguna
   prueba sin credenciales lo detecta**, porque el rechazo por falta de token ocurre antes de que se
   cargue esa clase. Si después de un despliegue "entra el admin y no los médicos", buscar ahí.
8. **El aviso que nadie mostraba.** `HistoriaClinicaViewModel` guardaba en `mensaje` el "no se pudo
   traer la historia clínica" y `HistoriaClinicaScreen` no lo dibujaba. Cuando la carga fallaba, la
   pantalla quedaba idéntica a la de un paciente sin consultas previas, así que un fallo de red se veía
   como "este paciente no tiene historia". En una historia clínica eso invita a cargar de nuevo algo
   que ya existe. Costó medio día de diagnóstico: se buscaba la causa del fallo cuando el problema era
   que el fallo no se veía.
9. **Las consultas previas no se traían desde la ficha del paciente.** Solo al entrar en la historia
   clínica de la especialidad. El médico abría la ficha, veía el listado vacío y concluía que no había
   nada. Ahora la ficha las pide sola para las especialidades con servidor propio.
10. **El buscador de pacientes se des-filtraba solo.** Dos búsquedas escribían sobre la misma lista: la
    local filtra en SQL, y la de turnos pegaba su resultado arriba **sin filtrar**, unos cientos de
    milisegundos después. Se veía filtrar bien y enseguida volver la lista entera. No se puede quitar
    la remota, que es la que permite encontrar y vincular a un paciente que todavía no es del médico:
    ahora se limpia al tocar una tecla y lo que llega pasa por el mismo criterio que el SQL.

---

## Archivos

### Repo de pediatría (`/Applications/MAMP/htdocs/HCPediatria/public_html/pediatria/HCDPediatria`)

En la rama `salud360-api` de `github.com/rodri89/hc_pediatria`, ya pusheada. **Desde el 2026-09-24 está
en producción**, subida por FTP; el paso 3 dice qué se verificó.

> **Al trabajar acá hay tres copias, y es fácil confundirlas.** El clon para commitear es
> `C:\Users\flor\hc_pediatria`; la que sirve Apache para probar es
> `C:\xampp\htdocs\HCDPediatria-salud360`; y la tercera es el servidor. Al tocar el backend hay que
> copiar el archivo cambiado a las dos primeras, o la prueba corre contra código viejo, y después
> subirlo a la tercera.
>
> **Pushear antes de subir por FTP.** Durante varios días producción tuvo código que no estaba en el
> repositorio, y dos diagnósticos largos salieron de archivos que quedaron sin subir.

| Nuevos | |
|---|---|
| `app/Http/Controllers/Api/Salud360/` | `Salud360Controller` (base, con los permisos sobre una consulta), `Auth`, `Paciente`, `Consulta`, `Foto` |
| `app/Services/Salud360/` | `TobbAuthService`, `PacienteVinculoService`, `SeccionesService` (textos, formularios y desarrollo madurativo), `RegistrosService` (las listas), `FotosService` (las galerías), `ColumnasLegacy` |
| `app/Http/Middleware/` | `Salud360Api`, `Salud360Cors` |
| `config/salud360.php` | URL de turnos, caché, gracia, autoprovisión, y `img_path` para forzar la carpeta de las fotos |
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
| `core/database/.../migrations/5.sqm` | Columna `archivo.remoto_id` (fase 3) |
| `core/data/src/jvmTest/.../repos/HcPediatriaE2ETest.kt` | La prueba de punta a punta contra la API real |
| `composeApp/src/wasmJsMain/resources/favicon.svg` | Ícono de la pestaña, el mismo isotipo que Android |

Modificados: `DataModule`, `Mappers`, `AuthRepository` (propaga el token), `HcRepository` (backends y
`especialidadesConApi`), `HistoriaClinica.sq`, `Consulta.kt` (campos `remotoId`), `ConsultaViewModel`,
`HcModule`, `ArchivosSeccion.kt`, `HistoriaClinicaScreen.kt` (muestra el aviso),
`PacientesViewModel.kt` (el buscador y la precarga de la ficha), `PacientesScreens.kt`,
`PacientesModule.kt`, `MainShell.kt`, `FormFields.kt` (`DateField` sobre fondo oscuro),
`Calendario.kt` (elegir año y mes), `index.html` (el ícono),
`gradle.properties` (dominio de pediatría), `core/data/build.gradle.kts` (propiedades de la prueba).

**Cambios de interfaz que salieron de usarla, no de la fase 3:** el aviso cuando no se pudo traer la
historia clínica, la precarga de consultas en la ficha del paciente, el buscador que ya no se
des-filtra, la fecha legible sobre el panel de marca, el ícono de la pestaña, el calendario con
elección de año y mes —antes había que pasar mes por mes hasta una fecha de nacimiento— y el estado de
sincronización del servidor propio, que quedó **oculto** detrás de una constante en `MainShell.kt`
porque contaba pendientes de un servidor que no existe y se leía como que algo no se había guardado.

---

## Entorno de prueba local

**Importante:** en desarrollo la app apunta al **MAMP**, no a producción. Por eso se puede probar todo
sin subir nada. `pediatria.hclinicadigital.com` solo se usa al compilar para producción.

### El entorno de la máquina de Windows (XAMPP)

Armado el 2026-09-24 a partir de dos volcados de producción. Sirve para correr la prueba de punta a
punta; la app se compila igual sin nada de esto.

| Qué | Dónde |
|---|---|
| Bases | `hc_pediatrica` y `turnosonlinebb`, restauradas en el MySQL del XAMPP |
| Volcados | `bd_test/`, ignorado por git: **son copias con datos reales** |
| Laravel de pediatría | `C:\xampp\htdocs\HCDPediatria-salud360`, copia de la rama `salud360-api` |
| Clon para commitear | `C:\Users\flor\hc_pediatria` |
| URL de la API | `http://localhost/HCDPediatria-salud360/public/index.php` |

Cuatro cosas para entenderlo:

- **La copia vieja `htdocs/HCDPediatria` no se tocó**: tiene cambios sin commitear y apunta a otro
  repositorio. Por eso el directorio nuevo va aparte. Su `vendor/` se copió, no se enlazó: con un
  enlace, el autocargador de Composer resuelve la ruta real y busca las clases en el directorio viejo.
- **Turnos no se consulta desde esta máquina.** `SALUD360_TOBB_URL` apunta a un puerto muerto a
  propósito, para que ningún pedido salga a producción. La sesión vale igual porque hay una fila
  sembrada en `salud360_token_cache`, que es el camino que la API ya usa cuando turnos no responde.
  El token en claro es `salud360-prueba-fase3` y corresponde al usuario 2 de pediatría (médico 1 de
  turnos), y `salud360-prueba-admin` al administrador (usuario 1), que es el que hace falta para las
  licencias. **La identidad queda sin ejercitar acá**: eso se probó en su momento contra el MAMP.
- **El paciente de prueba lo crea la propia prueba**, con documento 42555111. No se usa ninguna
  historia clínica real.
- **`gradle.properties` no se tocó**: sigue apuntando al MAMP, que es lo correcto para la otra
  máquina. La prueba recibe la URL por parámetro, y para probar la app entera se usa
  `-Pentorno=release`, que apunta a producción. Nunca hizo falta cambiar esa línea.

Dos cosas más que aparecieron usándolo:

- **La máquina se queda sin memoria y Gradle muere.** Con 7,5 GB y el navegador abierto, el proceso de
  Gradle que sirve la web es lo primero que el sistema mata. **El servidor de Node sobrevive** y sigue
  entregando lo último compilado, así que la web no se cae: lo que se pierde es la recompilación
  automática. Si hace falta compilar, conviene cerrar algo antes. También falla el demonio de Kotlin,
  y ahí Gradle compila en proceso y avisa con `Could not connect to Kotlin compile daemon`; conviene
  correr la tarea otra vez y confirmar que quede al día.
- **Al restaurar los volcados, ojo con la versión.** Vienen de MariaDB 11.8 y el XAMPP tiene 10.1. En
  este caso entraron sin tocar nada, pero conviene revisar antes que no traigan collations `uca1400`,
  columnas `json` ni columnas generadas, que 10.1 no soporta. Y el MySQL local **no está en modo
  estricto**, así que un `INSERT` al que le falta una columna obligatoria pasa acá y falla en
  producción: para reproducir esos casos hay que `SET SESSION sql_mode = 'STRICT_TRANS_TABLES'`.

Cómo se corre acá:

```
./gradlew :core:data:jvmTest --rerun --tests '*HcPediatriaE2ETest*' \
  -Psalud360.test.hc.pediatria=http://localhost/HCDPediatria-salud360/public/index.php \
  -Psalud360.test.hc.token=salud360-prueba-fase3
```

Y las licencias, que van con el token del administrador porque a un médico la API no le informa
ninguna. La prueba le cambia la fecha a la primera, la vuelve a leer y la deja como estaba:

```
./gradlew :core:data:jvmTest --rerun --tests '*HcLicenciasE2ETest*' \
  -Psalud360.test.hc.pediatria=http://localhost/HCDPediatria-salud360/public/index.php \
  -Psalud360.test.hc.token.admin=salud360-prueba-admin
```

**Cuando terminemos, borrar las dos bases y `bd_test/`**: son pacientes, contraseñas y credenciales
de cobro reales en un MySQL sin contraseña.

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
  configuración cacheada no incluye archivos nuevos y la URL de turnos queda vacía. Ya costó un rato,
  y volvió a pasar al subir a producción el 2026-09-24. **Se manifiesta como `503 tobb_caido`**, que
  parece una caída de turnos y no lo es: el pedido sale hacia una dirección vacía y ni siquiera se
  completa. Cómo distinguirlo sin credenciales, y sin tocar nada:

  ```
  curl -s -H "Authorization: Bearer token-inventado" \
    https://pediatria.hclinicadigital.com/api/salud360/auth/perfil
  ```

  Si contesta `codigo: tobb_caido`, pediatría no llegó a turnos. Si contesta `codigo: token`, sí
  llegó y turnos rechazó el token, que es lo correcto. El motivo exacto queda en el log del servidor
  de pediatría, en la línea `salud360: no se pudo consultar el perfil en turnosonlinebb:`.
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
- **Las fotos de la app van todas a `img/salud360`, y esa carpeta hay que crearla a mano.** El PHP del
  hosting no tiene permiso para crear directorios, y la web nunca lo necesitó porque sus carpetas
  estaban creadas de antes. Si falta, la API responde `500 carpeta_no_escribible` **nombrando la ruta
  exacta** que intentó escribir, que es el dato que resuelve el problema. Y no se resuelve con
  `public_path()`: ver el paso 5.
- **Al subir un adjunto, los límites que mandan son los del PHP del hosting**, no el de 12 MB de la
  API: `upload_max_filesize` y `post_max_size`. Cuando los pasa, PHP entrega el archivo incompleto y
  la API contesta `422 archivo_invalido`, que es distinto de `archivo_grande`.
- **Las columnas nuevas de la base del dispositivo van siempre últimas.** `upsertX` es
  `INSERT OR REPLACE INTO x VALUES ?`, o sea posicional: si una columna se cuela en el medio, todas
  las filas se guardan corridas. Vale para `archivo.remoto_id` y para lo que venga.
- **El hosting se queda sin conexiones y la web falla de a ratos.** Error
  `SQLSTATE[HY000] [2002] Operation not permitted`, que **no es un permiso de archivos**: es MySQL
  que no acepta una conexión más. Aparece en cualquier consulta, incluso en
  `select * from users where id = ?`, que es la que Laravel hace en todos los pedidos para saber quién
  está logueado. Si el error cae siempre en la misma consulta es un bug; si cambia de lugar, es esto.

  La causa está en la web, no en la app: **abrir una consulta dispara del orden de 97 llamadas** en 25
  secciones, cada una con su conexión. La app hace una sola por consulta —la API devuelve secciones,
  examen y listas juntos— y las de guardado salen en secuencia, no en paralelo. Además la app tolera
  el rechazo: guarda en el dispositivo y reintenta. La web pierde la llamada y muestra el error.

  Se mitiga subiendo el tope del plan; se arregla de verdad juntando las llamadas de la web, que es
  un trabajo grande y aparte.
- **En producción está `APP_DEBUG=true`.** Cualquier error devuelve el volcado completo con rutas
  absolutas del servidor y la traza entera. Credenciales no se filtran (verificado), pero hay que
  apagarlo: `APP_DEBUG=false` y después `php artisan config:clear`.
- **`php artisan` desde la consola escribe como el usuario de SSH, no como la web.** Si un archivo de
  `storage/` o `bootstrap/cache/` queda con otro dueño, el proceso web ya no puede tocarlo y aparecen
  errores de permisos que no tienen nada que ver con el código. Pasó como sospecha al correr
  `config:clear`; conviene revisar dueños después de usar artisan en el servidor.
- **La app recuerda a qué ficha de pediatría corresponde cada paciente y no lo revisa nunca más.** Se
  guarda en `paciente_extra` con la clave `hc_paciente_id`, y `resolverPaciente` corta ahí. Si la ficha
  se corrigió del lado del servidor, el dispositivo sigue apuntando a la vieja. En la versión web se
  arregla borrando el almacenamiento del sitio; en el teléfono, reinstalando. **Queda pendiente** que
  la app revalide ese vínculo en vez de confiar en él para siempre.

---

## Plan

### Paso 1: probar desde la pantalla

**Hecho en lo esencial** el 2026-09-26, contra producción: se entró con un médico real, se dio de alta
un paciente, se abrió una consulta, se escribió y se adjuntaron una foto y un PDF, y todo aparece en la
web de pediatría.

Lo que **sigue sin probarse**, y es lo que más importa de lo que queda, porque es el caso del
consultorio sin señal:

1. Modo avión: escribir, ver el indicador de pendientes, recuperar señal y confirmar que se envía solo.
2. Abrir una consulta **sin señal**, escribir, recuperar y verificar que se creó en pediatría con todo.
3. Escribir una palabra y salir de la pantalla **en el acto**, antes del segundo: tiene que llegar
   igual. Es el bug 4, y no lo cubre la prueba automática.
4. Escribir treinta segundos seguidos y contar los pedidos en la pestaña Red: tienen que ser unos
   pocos, no uno por tecla.
5. Cerrar la consulta desde la app y verla cerrada en la web, con fecha y edad correctas.
6. Cargar peso y talla y ver la curva de crecimiento en la web.

Ojo con el indicador de pendientes de la barra lateral al hacer estas pruebas: **cuenta lo que espera
al servidor propio de Salud 360, que no está desplegado**, así que siempre muestra pendientes y su nube
siempre está tachada. No tiene nada que ver con pediatría. El estado de un adjunto se mira en la nube
de su miniatura, que es la que pasa a verde cuando llegó.

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

### Los pacientes y su vínculo con turnos

Trabajado el 2026-09-25, a partir de un síntoma concreto: un paciente con nueve consultas en la web
aparecía sin ninguna en la app. La causa es que **son dos bases distintas y el vínculo casi no
existía**: de 4142 fichas de pediatría, solo 2 tenían `paciente_id_tobb`. Todo dependía de que el
documento fuera idéntico en las dos bases, y para la mitad no lo era.

Cómo quedaron clasificadas las 4142 fichas:

| Situación | Cuántas | Qué se hizo |
|---|---|---|
| Documento único en ambas bases | 1325 | Se escribió `paciente_id_tobb` |
| Documento repetido en alguna base | 42 | Nada: la API devuelve candidatos y elige el médico |
| Sin par en turnos | 2076 | Nada: existen solo en pediatría, la app no las ve |
| Documento inválido en pediatría (≤ 1000) | 719 | Nueve corregidas a mano; el resto sigue igual |

**Vincular no cambia el comportamiento de hoy**, porque la API ya empareja por documento. Sirve para
que no dependa de él: si alguien corrige un documento, el vínculo explícito sobrevive.

**Lo que sí importa son los 719 con documento inválido**, casi todos con `dni = 1`. Esos no coinciden
por ninguna vía, así que la app les crea una ficha paralela vacía y el médico ve la historia partida.
Tienen consultas 605, pero **solo 60** pertenecen a la cartera de alguno de los once médicos que hoy
pueden entrar, así que el daño real es chico y abordable a mano.

Tres cosas que aparecieron al hacerlo, y que conviene no repetir:

- **Pediatría ya venía duplicando pacientes por su cuenta**, desde antes de Salud 360. Hay 19 fichas con
  documento inválido que tienen una gemela con el documento real, y 15 de ellas **con consultas en las
  dos**. Un caso concreto: el mismo chico como ficha 17 (`dni = 1`, 6 consultas, de 2020) y ficha 3730
  (documento real, 5 consultas, de marzo de 2026), con el apellido escrito distinto. Unificarlas no es
  completar un documento: hay que decidir cuál queda y mover las consultas. Por decisión del usuario,
  **las de `dni = 1` con gemela no se tocan ni se borran**.
- **Antes de escribir un documento hay que ver que no exista ya en pediatría.** La primera tanda que se
  propuso incluía uno que chocaba justamente con su gemela.
- **La mitad de los documentos que turnos tiene para un chico son del padre o de la madre.** De 16
  candidatos a corregir, 6 tenían documento de adulto para un chico nacido después de 2021, y uno tenía
  nueve dígitos. Se descartaron: copiarlos le daría al chico el documento de su madre. El filtro
  práctico es que un documento de ocho dígitos por arriba de 45 millones es plausible para un menor.

La sentencia que puebla el vínculo lee de las dos bases, y en producción **el usuario de MySQL no llega
a las dos**. Por eso se resolvió generando las 1325 actualizaciones ya calculadas, que corren solo en
pediatría. Si hay que repetirlo, el criterio es: documento mayor a 1000, único en pediatría y único en
turnos, y la ficha sin vínculo previo.

### Los exámenes de otras consultas, y las fotos que ya estaban

Hecho el 2026-09-28, a partir de un pedido concreto: poder adjuntarle la foto del resultado a un
estudio pedido en una consulta anterior. Es el flujo real —el estudio se pide una vez y el resultado
llega semanas después— y **la web ya lo hacía**, así que lo que se hizo fue que la app se comportara
igual y no inventara un camino propio.

Tres cosas para entenderlo:

- **La lectura de exámenes complementarios e interconsultas es acumulativa.** La API devolvía solo los
  de la consulta pedida, así que desde una consulta nueva el estudio anterior no existía. Ahora
  devuelve toda la historia del paciente más lo cargado en la consulta abierta, que es lo que hacen
  `cargarExamenesComplementarios` y `cargarInterconsulta` de la web (esta última tiene el filtro por
  consulta comentado a propósito). Sobre producción, una consulta que devolvía 3 exámenes devuelve 14.
- **Cada registro viaja con su consulta de origen y su consulta de respuesta.** Lo primero es
  imprescindible: sin eso la app etiqueta como propios de la consulta abierta todos los exámenes
  viejos que bajen, y el médico los ve como si los hubiera pedido hoy. Al editar uno anterior se
  conserva su consulta original, y pediatría anota aparte dónde se cargó la respuesta.
- **En la pantalla, un registro anterior se puede completar y se le pueden adjuntar fotos, pero no
  quitar.** Cargar el resultado de un pedido ajeno es parte del trabajo; borrar lo que pidió otra
  consulta, no.

**Y la app ahora muestra los adjuntos que ya están en pediatría**, que antes no existían para ella: lo
subido desde la web, desde otro teléfono, o desde el mismo antes de reinstalar. Las fotos vienen en la
misma lectura de la consulta y se guardan como filas locales **sin archivo**; el contenido se baja
recién cuando hay que mostrarlo y queda en el dispositivo. Una consulta con diez fotos no puede costar
diez descargas al abrirla, ni una por cada vez que se dibuja la miniatura.

Para que eso funcionara, las fotos de un examen pasaron a leerse **por paciente y no por consulta**: el
vínculo real es el estudio. Filtrar por consulta las escondía justo en el caso para el que existen, y
es por eso que la web ignora a propósito el `consulta_id` de la foto.

### Paso 3: subir a producción, en este orden

**El backend ya está arriba, desde el 2026-09-24.** Se subieron los 18 archivos de la API (las tres
fases) y se corrió a mano el equivalente de la migración, porque en ese hosting `artisan migrate`
falla con migraciones viejas sin registrar.

Verificado contra `pediatria.hclinicadigital.com` sin credenciales, que es lo que se puede comprobar
sin escribir nada:

| Qué se pidió | Qué contestó |
|---|---|
| `auth/perfil` sin token | `401 {"ok":false,"codigo":"sin_token"}`, o sea el contrato propio y no un error genérico |
| `consultas/1/fotos` y `fotos/consulta/1/archivo` | 401, no 404: **las rutas de la fase 3 están publicadas** |
| una ruta inventada | 404, que es el control de que el enrutador distingue |
| preflight `OPTIONS` de subida de fotos | 204 con `POST` permitido y `X-Salud360-Token` entre los encabezados |
| `auth/perfil` con un token inventado | `401 codigo: token`, o sea que **pediatría llegó a turnos y turnos contestó** |

La API de turnos también está publicada y sana: contesta `401 sin_token` en su `auth/perfil` y 404 en
una ruta inventada.

**El paso 2 ya se corrió contra producción**, en las dos bases, que son distintas: el cruce por correo
escribe en `users.medico_id_tobb` de pediatría y la habilitación inserta en `salud360_medico_hc` de
turnos. Sobre la copia del 2026-09-24 el cruce sumaba 4 médicos a los 8 que ya estaban vinculados, y
quedaban 11 habilitados. El duodécimo está vinculado a un médico dado de baja en turnos, así que no
entra igual.

**Y está probado con un médico real** (2026-09-26): ingreso, alta de paciente, consulta, texto y
adjuntos, todo visible en la web de pediatría. Para que llegara a andar hubo que corregir, en el
camino, cuatro cosas que están descritas en la lista de bugs y en las advertencias: el archivo que
faltaba en turnos, una columna de `pacientes` sin valor por defecto, la carpeta de las fotos y la ruta
donde se escriben.

**Todo esto ya se hizo.** El repo de pediatría está pusheado, los archivos están en el servidor y la
app se desplegó el 2026-09-28 uniendo la rama a `main`.

**Para la próxima vez, lo que hay que saber del despliegue de la app:** es un push a `main` del repo
de la app. Hay un flujo de GitHub Actions que compila la web y la sube a Hostinger por SSH con cada
push a esa rama, filtrando por los directorios de código. **O sea que ese merge es el despliegue**, no
un paso previo. Conviene probar antes con `-Pentorno=release`, que es lo mismo que va a quedar
publicado pero servido desde la máquina.

Y del backend de pediatría: **se sube por FTP, archivo por archivo**. Eso es lo que hizo que producción
tuviera durante días código que no estaba en el repositorio, y lo que llevó a dos diagnósticos largos
por archivos que quedaron sin subir. Conviene pushear antes de subir, no después.

Si se despliega la app antes que el backend no se rompe nada: todo se guarda igual en el dispositivo y
queda pendiente de envío, pero se ven avisos de que no se pudo enviar.

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

**Terminada y probada de punta a punta.** Los adjuntos ya existían enteros en la app —tabla `archivo`,
galería, cámara, almacenamiento por plataforma— pero solo subían al servidor propio de Salud 360, que
no está desplegado. Ahora van a las galerías de pediatría, que son las que muestra la web.

Cinco galerías, no cuatro: además de las de la consulta, los antecedentes neonatales, el examen
complementario y la internación, está el familigrama, que cuelga del paciente.

| En la app | En pediatría |
|---|---|
| sección `fotos`, `documentos`, `evolucion` | `consulta_fotos` |
| sección `neonatales` | `antecedentes_neonatales_fotos` |
| adjunto de un registro `examen_complementario` | `examenes_complementarios_fotos` |
| adjunto de un registro `internacion` | `internaciones_fotos` |
| sección `familigrama` | `familigramas` |

Cinco cosas para entenderlas:

- **Los adjuntos se mandan después de las listas**, en el mismo `drenar`. La foto de un examen
  complementario cuelga de esa fila, así que necesita el id con el que quedó del otro lado; si todavía
  no llegó, el envío se reintenta entero en vez de subir una foto huérfana.
- **Uno por pedido, y en multiparte.** La foto se saca en el consultorio, donde la señal es mala: si
  se corta a la mitad conviene reintentar esa sola y no las diez de la consulta. Lleva su propio
  tiempo de espera, de dos minutos, porque una foto de teléfono no entra en los treinta segundos de
  un pedido de texto.
- **Hizo falta `archivo.remoto_id`** (migración `5.sqm`), por lo mismo que las listas: sin saber con
  qué id quedó, el segundo envío sube la misma foto otra vez. `subido` y `url_remota`, que ya estaban,
  son del servidor propio y se dejaron como estaban.
- **Se aceptan imágenes y PDF**, hasta 12 MB. Las imágenes se achican conservando la proporción, que
  es lo que la web **no** hace: llama a `resize(1980, 1920)` a secas y deforma todo lo que no tenga
  esa medida. Los PDF se guardan tal cual; la web todavía no los muestra.
- **El archivo se baja por la API** (`fotos/{tipo}/{id}/archivo`), que comprueba de quién es el
  paciente, y no por su URL pública: `public/img/` lo abre cualquiera que tenga el enlace. La URL
  pública igual viene en la respuesta, porque es la que usa la web.

**Dónde terminan las fotos, que costó dos vueltas.** La web reparte las suyas en una carpeta por
médico y otra por sección, y **nunca las crea**: su código asume que están porque alguien las creó a
mano. La API sí las creaba, y el hosting no se lo permite (`mkdir permission denied`), así que ninguna
foto llegaba. Dos correcciones:

1. **Una sola carpeta para todos**, `img/salud360`, creada una vez y a mano. La sección va adelante del
   nombre del archivo, así el contenido sigue siendo legible sin subcarpetas. La web las muestra igual
   porque lee la ruta que está en la columna.
2. **No se usa `public_path()`.** Con la carpeta creada y en 777 la subida seguía fallando: en este
   hosting el contenido del `public` de Laravel se copia dentro del `public_html` del dominio, así que
   `public_path()` apunta a una carpeta que el servidor no publica. La web no se enteró nunca porque
   guarda con rutas **relativas** (`Image::save('img/…')`), que caen en el directorio del script. Ahora
   la API resuelve igual: la carpeta `img` que está junto al `index.php` que atendió el pedido. Se
   puede forzar con `SALUD360_IMG_PATH`.

Y el error de esa familia nombra la ruta exacta que intentó escribir, en vez de mandar al log.

### Las licencias

La licencia es el permiso del médico para entrar a la historia clínica. Estaba solo en la web de
pediatría; ahora la aplican las dos puntas y la administra el panel de la app.

**En pediatría.** `TobbAuthService::conLicencia` repite la regla de `LoginController::validarLicencia`:
sin fila no entra, vencida o desactivada no entra (y se le baja el `activo`), y dentro de la ventana de
aviso entra con la fecha a la vista. El administrador se resuelve antes, así que nunca se le mira la
licencia. El rechazo sale como `403 licencia_vencida` con la fecha, para que el mensaje diga qué pasó y
no "no anda". `LicenciaController` agrega `GET licencias` y `PUT licencias/{medicoIdTobb}`, las dos solo
para el administrador; viajan con el **número de médico de turnos**, que es como la app nombra a los
médicos, y por eso los que todavía no están vinculados no se informan.

`vencida` y `por_vencer` los resuelve la API, para que las dos puntas usen la misma regla y la misma
fecha de hoy.

**En la app.** El panel de administración las lee de pediatría al abrirse y las escribe ahí mismo: la
tabla del dispositivo queda como copia (`dirty = false`), porque el dueño del dato es la especialidad.
La pestaña muestra solo a los médicos con historia clínica habilitada —al resto no se le vence ni se le
cobra nada— y al que no tiene licencia le ofrece cargarla, que es el paso de habilitarlo. Si el guardado
no llega a pediatría se avisa, en vez de dejar la pantalla como si hubiera andado: lo que manda es lo
que quedó allá.

Al médico, el rechazo le llega con el texto de la API cuando la pantalla trae la historia clínica, así
que lee "Tu licencia de la historia clínica está vencida. Avisale al administrador."

**Antes de subirlo hay que mirar las fechas.** Al quedar en línea, los médicos ya vinculados con la
licencia vencida dejan de entrar por la app en el mismo momento (hoy son cuatro, y otros cinco entre los
que faltan dar de alta). Es la regla pedida, pero conviene renovarlas antes y no descubrirlo con el
médico en el consultorio.

### Paso 6: fase 4

Pendientes, secretarias, aviso de edición simultánea, y extraer la base común entre el cliente de turnos
y el de historia clínica, que hoy son gemelos a propósito.

Se le suman tres cosas que salieron de usar la app y que no entran en ninguna fase:

- **Que la app revalide el vínculo del paciente** en lugar de confiar para siempre en el
  `hc_paciente_id` que guardó la primera vez. Hoy, si la ficha se corrigió del lado del servidor, el
  dispositivo sigue apuntando a la vieja y no hay forma de enterarse desde la app.
- **Que la API no cree una ficha nueva en silencio** cuando no encuentra al paciente por documento pero
  hay parecidos por nombre y fecha de nacimiento. Ya existe el camino de los candidatos, que se usa
  cuando el documento está repetido: sería reutilizarlo acá, para que la app pregunte en vez de abrir
  una historia clínica paralela.
- **Juntar las llamadas de la web de pediatría.** Es la causa de la saturación de conexiones: 97 en 25
  secciones al abrir una consulta. La API de la app ya devuelve todo junto y sería el modelo a seguir.
  Es un trabajo grande y toca la web entera, pero es lo que hoy más molesta a los médicos.

---

## Verificación

App: `./gradlew :androidApp:compileDebugKotlin`, `:composeApp:compileKotlinWasmJs`,
`:server:compileKotlin`, `:core:data:jvmTest`.

Punta a punta contra el MAMP (`HcPediatriaE2ETest` y `HcLicenciasE2ETest`, ver arriba cómo se corren): es la versión
automática de "cargar algo y verlo del otro lado", sin la pantalla. Desde la fase 3 también manda dos
adjuntos —uno de la consulta y otro colgado de un examen complementario—, los busca en la galería y
los vuelve a bajar. Los da de baja al terminar, como a las listas.

Backend: `php -l` sobre los archivos tocados, y las pruebas con curl del contrato, que están en
`md/API_SALUD360_PEDIATRIA.md` del repo de pediatría.

La prueba que importa sigue siendo la de siempre: cargar algo **desde la app** y verlo en la web de
pediatría con el mismo médico. Se hizo el 2026-09-26 con
`./gradlew :composeApp:wasmJsBrowserDevelopmentRun -Pentorno=release`, que levanta la web local contra
producción, y pasó para las tres fases.

**Una advertencia sobre qué alcanza a verificar cada cosa.** Las pruebas sin credenciales —`php -l`, los
curl contra producción, el compilar— no detectan una clase que falta ni un archivo que no se subió,
porque el rechazo por falta de token ocurre antes. Lo aprendimos dos veces: con
`HistoriaClinicaService.php` en turnos y con la sospecha sobre `FotosService.php`. Un 401 correcto en
una ruta **no** prueba que el controlador de esa ruta exista.
