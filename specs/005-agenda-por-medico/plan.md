# Plan 005 — Agenda por médico

Estado: **aprobado por el usuario el 2026-11-04; implementada, falta la prueba manual (T9).** Spec: `spec.md` (aprobada el 2026-11-04, D1 a D3 resueltas; RF-10 aclarado el mismo día por decisión Q1 del usuario, y Q2 decidida: los campos van también en `secretaria.medicos[]`).
Servidor: `servidor-turnos.md` (lo hace el usuario en la otra PC; es dependencia, este repo no lo implementa).

Rama `hc-pediatria-improve-functionality-1`, sobre `main`. Las rutas y líneas de abajo son de ese árbol (`main` llega a `5.sqm`, esquema v6;
`hc-general` tiene otros números de línea, ver "Integración con `hc-general`").

## Cómo está hoy (verificado leyendo el código)
1. **`TurnosImportador.importarMedico`** (`core/data/.../repos/TurnosImportador.kt:75-78`): `tieneTurnos = consultorioId != null && m.tieneHorarios == 1`
   y `visibleEnTurnos = previo?.visibleEnTurnos ?: true`. Lee el médico previo; `visibleEnTurnos` nunca viene del servidor.
2. **`AgendaTurnosOnline.sincronizarAdministracion`** (`.../repos/AgendaTurnosOnline.kt:424-438`): `tieneTurnos = m.tieneHorarios == 1` y **no lee el
   médico previo**, así que `visibleEnTurnos` queda en el valor por omisión del modelo (`true`) y se pisa cada vez que se abre el panel o se toca
   «Sincronizar ahora». Es el origen de «los interruptores no se quedan». Además usa `TobbIds.consultorio(m.consultorioId)` sin filtrar el `0` que
   manda turnos para «sin consultorio» (el importador sí lo filtra, `:62`): en el panel un médico sin consultorio queda con el id `tobb-c0`.
3. **Los dos interruptores del panel** (`features/admin/.../AdminScreens.kt:183,185`) llaman a `AdminViewModel.guardarMedico` →
   `AdminRepository.guardarMedico` (`:94`): `upsertMedico` solo local y con `dirty = true`. Turnos no se entera.
4. **`MainShell.kt`** decide con condiciones sueltas: médico → Agenda `tieneTurnos && consultorioId != null` (`:169`), Recetas `tieneTurnos` (`:172`),
   Config `tieneTurnos || hay HC` (`:174`); dentro de Config, `HORARIOS -> consultorioId != null` (**ese es el síntoma del pedido original**: no mira
   `tieneTurnos`), `HISTORIA_CLINICA -> hay HC`, el resto `tieneTurnos` (`:292-298`); secretaria → Agenda con `consultorioId != null` (`:179`), Obras
   sociales siempre (`:182`), y al elegir médico `tieneTurnos = consultorioId.isNotBlank()` (`:241`). El botón «Nuevo turno» de la ficha del paciente
   sale con `consultorioId != null` (`:267`).
5. **`SelectorMedicoScreen`** (`features/turnos/.../ConfigScreens.kt:596`) ya muestra «Con agenda / Sin agenda» desde `Medico.tieneTurnos` y deja elegir
   igual (`:600`). **No necesita cambios**: pasa a decir la verdad cuando el dato sea real.
6. **`AuthRepository.refrescarPerfil()`** (`:243`) **no consulta a turnos**: rearma la sesión desde la base local. El estado nuevo del médico entra por
   `login` (importa el perfil, `:134`) y por `restaurar` → `refrescarPerfilRemoto` (`:116-127`, `turnos.perfil()` + `importar`). RF-15 se cumple por esos
   dos caminos, no por `refrescarPerfil`.
7. **Alta de usuarios**: `TurnosRepository.crearUsuario` (`:121`) ya lanza `IllegalStateException` sin conexión; `AdminViewModel.crearUsuario` (`:72`) arma
   `TobbAltaUsuario`. El formulario ya tiene «Mostrar para pedir turno» (`AdminScreens.kt:139`) y no tiene «Tiene agenda».
8. **`tobbJson`** tiene `encodeDefaults = false` (`TobbDtos.kt:16`); `TobbAltaUsuarioTest:39-41` lo fija. Un `Boolean = false` por omisión no viaja.
9. `AgendaTurnosOnline.sinRutaEnLaWeb` (`:355`) ya traduce 404/405 sin `codigo` a «la web de turnos todavía no permite … (falta actualizar turnosonlinebb)».
10. `RevocarSesionClientesTest`, que cita `MEMORY.md` como patrón de servidor falso, **no existe en este árbol**. Los patrones vigentes son
    `AuthRepositoryLicenciaServidorTest` (clase `TurnosFalso`) y `HcApiClientErrorTest`: `HttpServer` en `127.0.0.1` y base temporal con
    `createDatabase(DriverFactory(archivoTemporal))`.
11. `server/.../TurnosBridge.kt:110` (réplica del importador en el servidor propio, que la app no usa) fija `tieneTurnos = true`. **No se toca.**

## Qué cambia (resumen)
- Pasa a existir un solo lugar con las reglas de «quién ve qué» (`AgendaDelMedico`, puro) y todas las pantallas lo usan en vez de condiciones sueltas.
- `Medico.tieneTurnos` deja de significar «tiene horarios cargados» y pasa a ser **«tiene agenda» tal como lo informa turnos** (decisión del administrador).
  `Medico.visibleEnTurnos` deja de ser un valor local y se toma de turnos. Cuando turnos no informa (servidor sin actualizar) rige el criterio de hoy.
- Los dos interruptores del administrador escriben en turnos (`PUT medicos/{id}/agenda`) y la copia local solo cambia después de que turnos confirma.

## Módulos y archivos

### `core/model`
- `tobb/TobbPerfil.kt` (`TobbMedico`): campos nuevos `tiene_agenda: Int? = null` y `visible_en_turnos: Int? = null` (`null` = el servidor no lo informa).
  Se actualiza el comentario de `tiene_horarios` («respaldo»). RF-12, RF-13.
- `auth/Medico.kt`: **sin cambios de código**; se reescribe el KDoc de `tieneTurnos` («tiene agenda: decisión del administrador, la informa turnos») y de
  `visibleEnTurnos` («lo informa turnos»). `auth/Usuario.kt` (`PerfilMedico.tieneTurnos`) igual: solo KDoc. Ver D-P1.

### `core/database`
- **Ningún cambio.** `Auth.sq` ya tiene `medico.tiene_turnos` y `medico.visible_en_turnos`. Sin `.sqm`.

### `core/data` (commonMain)
- `network/tobb/TobbDtos.kt`:
  - `TobbMedicoCatalogo`: `tiene_agenda: Int? = null` y `visible_en_turnos: Int? = null`; KDoc de `tiene_horarios` actualizado. RF-12, RF-13.
  - `TobbAltaUsuario`: `tiene_agenda: Boolean? = null`. El alta de **médico** siempre lo manda explícito (`true` o `false`); para secretaria y administrador queda
    `null` y no viaja. Con `encodeDefaults = false` un `false` explícito **sí** viaja (solo se omite el valor por omisión, que acá es `null`). RF-14. Ver D-P5.
  - `TobbAgendaRequest` (`tiene_agenda: Boolean? = null`, `visible_en_turnos: Boolean? = null`): solo viaja lo que el administrador tocó. RF-1, RF-2.
  - `TobbAgendaResponse` (`medico_id`, `tiene_agenda`, `visible_en_turnos`, `turnos_futuros`): los dos booleanos **sin valor por omisión**; una respuesta 200 que no
    los traiga no se toma como confirmación. `medico_id` y `turnos_futuros` con omisión (`0`). RF-2, RF-18.
- `repos/AgendaDelMedico.kt` (**nuevo**, funciones y tipos puros, sin Compose ni red; no hay lógica de fechas, así que «hoy» no entra: `turnos_futuros` lo cuenta
  el servidor):
  - `tieneAgendaDe(tieneAgenda: Int?, tieneHorarios: Int)`: `tiene_agenda` si el servidor lo informa; si no, `tiene_horarios == 1` (criterio de hoy). RF-12, RF-13.
  - `visibleDe(visible: Int?, previo: Boolean?)`: lo que informa turnos; si no informa, el valor previo del dispositivo y, sin previo, `true` (lo de hoy). RF-12, RF-13.
  - `tieneConsultorio(consultorioId: String?)`: ni nulo, ni vacío, ni `tobb-c0` (consultorio 0 de turnos = sin consultorio).
  - `puedeVerAgenda(tieneAgenda, consultorioId)` y `puedeVerHorarios(tieneAgenda, consultorioId)`: agenda y consultorio. RF-7, RF-8, RF-9, RF-10.
  - `puedeVerConfigDeAgenda(tieneAgenda)` y `puedeVerRecetas(tieneAgenda)`: obras sociales, reservas, cupo, módulos, mensajes y recetas. RF-7, RF-8, RF-9.
  - `puedeVerMenuConfig(tieneAgenda, especialidadesHc)`: el ítem «Config» del médico existe con agenda **o** con alguna HC. RF-7, RF-16.
  - `puedeCambiarVisible(tieneAgenda, visible)`: habilitado si tiene agenda **o** ya está visible (siempre se puede apagar; ver D-P11). RF-6.
  - `cambioDeAgenda(actual: EstadoAgenda, tieneAgenda: Boolean?, visible: Boolean?)` → `Enviar(tieneAgenda?, visible?)` o `Rechazado(motivo)`. Resuelve RF-5 (apagar
    agenda manda solo `tiene_agenda = false`; turnos deja `visible = false`) y RF-6 (`visible = true` sin agenda, ni existente ni pedida, se rechaza antes de salir a la red;
    apagar agenda y pedir visible en el mismo cambio se rechaza; un cambio sin ningún campo se rechaza).
  - `avisoDeAgendaGuardada(antes: EstadoAgenda, ahora: AgendaGuardada)` → texto o `null`: si la agenda pasó de prendida a apagada y `turnosFuturos > 0`, informa la cantidad y que
    los turnos siguen vigentes. RF-18. Los textos van acá para probarlos sin pantalla.
  - Tipos: `EstadoAgenda(tieneAgenda, visible)`, `AgendaGuardada(tieneAgenda, visible, turnosFuturos)`, `CambioDeAgenda`.
- `repos/TurnosImportador.kt` (`importarMedico`): `tieneTurnos = tieneAgendaDe(m.tieneAgenda, m.tieneHorarios)` (se va la condición `consultorioId != null`: el consultorio lo
  mira `puedeVerAgenda`, ver D-P8) y `visibleEnTurnos = visibleDe(m.visibleEnTurnos, previo?.visibleEnTurnos)`. RF-12, RF-13, RF-15, RF-17. Alcanza también a las secretarias:
  `importarSecretaria` (`TurnosImportador.kt:94-100`, verificado) importa cada elemento de `secretaria.medicos[]` con `importarMedico(m, null)`, así que toma `tiene_agenda` / `visible_en_turnos` con **la misma regla**, sin código aparte (Q2). `TobbSecretaria.medicos` ya es
  una lista de `TobbMedico`, el mismo DTO que recibe los campos nuevos. RF-10, RF-12.
- `repos/AgendaTurnosOnline.kt`:
  - `sincronizarAdministracion`: lee antes de la transacción los médicos previos (como `sincronizarHorarios` lee los locales) y arma `tieneTurnos` / `visibleEnTurnos` con las
    mismas dos funciones. No se toca el resto de la fila (el resto de campos, incluido el `0` de consultorio, queda como está: otro defecto, ver Riesgos). RF-12, RF-13, RF-17.
  - `guardarAgenda(medicoId, tieneAgenda: Boolean?, visible: Boolean?): AgendaGuardada` (**nuevo**): `PUT medicos/{n}/agenda` envuelto en `sinRutaEnLaWeb("cambiar la agenda de un médico")`,
    decodifica `TobbAgendaResponse` y **recién entonces** hace `upsertMedico` del médico local con el estado final que informó turnos y `dirty = false` (mismo criterio que las licencias).
    Si el médico local no está, no escribe nada y devuelve igual el resultado. No toca `especialidadesHc` ni licencias. RF-1, RF-2, RF-3, RF-5, RF-13, RF-16, RF-18.
  - `crearUsuario` no cambia (ya vuelve a traer la administración).
- `repos/TurnosRepository.kt`: `guardarAgenda(...)` delega en `online`; sin `online` lanza `IllegalStateException("Cambiar la agenda necesita conexión con turnos.")` como `crearUsuario`.
- `repos/AdminRepository.kt`: **sin cambios** (`guardarMedico` y `setEspecialidadHc` siguen locales: fuera de alcance). Ver Riesgos.

### `features/admin`
- `AdminViewModel.kt`:
  - `cambiarAgenda(m: Medico, activa: Boolean)` y `cambiarVisible(m: Medico, visible: Boolean)`: arman el pedido con `cambioDeAgenda`; `Rechazado` → `mensaje`; `Enviar` → `turnos.guardarAgenda`;
    éxito → `mensaje` con `avisoDeAgendaGuardada` (o «Agenda guardada en turnos»); falla → `mensaje = "No se pudo guardar la agenda: <motivo de turnos>"`. **Sin cambio optimista.** RF-2, RF-3, RF-5, RF-6, RF-18.
  - Estado `guardandoAgenda: StateFlow<Set<Id>>` para deshabilitar los interruptores de la tarjeta mientras su pedido está en vuelo (evita doble toque).
  - `crearUsuario(...)` recibe `tieneAgenda`; para médico manda `tieneAgenda` explícito, y `mostrarEnTurnos` solo si hay agenda. RF-14.
- `AdminScreens.kt`: en `MedicoCard`, los dos `Switch` llaman a `cambiarAgenda` / `cambiarVisible` (no a `guardarMedico`); «Visible para pacientes» con `enabled = puedeCambiarVisible(...)`; los botones
  «Módulos y configuración» (`puedeVerConfigDeAgenda`) y «Horarios» (`puedeVerHorarios`) solo con agenda; el chip «Turnos» ya sale con `tieneTurnos`. En el alta de usuario: casilla «Tiene agenda de turnos»
  (apagada por omisión) y «Mostrar para pedir turno» deshabilitada y desmarcada mientras no haya agenda. RF-2, RF-6, RF-9, RF-14.

### `features/turnos`
- `ConfigScreens.kt`: sin cambios de lógica (ver punto 5 de «Cómo está hoy»). Solo se corrige el comentario del parámetro `onElegido` («vacío si el médico no tiene agenda»), que hoy no es cierto. RF-10.

### `composeApp`
- `MainShell.kt`: todas las condiciones pasan por `AgendaDelMedico`:
  - `ContextoMedico.tieneTurnos` es ahora el dato de agenda (médico: de la sesión; secretaria: de `m.tieneTurnos` del médico elegido). Al elegir médico (`:241`) deja de ser `consultorioId.isNotBlank()`;
    sin agenda la secretaria va a Pacientes (no a Agenda).
  - Menú del médico: Agenda → `puedeVerAgenda`; Recetas → `puedeVerRecetas`; Config → `puedeVerMenuConfig`. Menú de la secretaria: Agenda → `puedeVerAgenda`; Obras sociales → `puedeVerConfigDeAgenda`
    (decidido, Q1: se oculta cuando el médico elegido no tiene agenda; RF-10); Pacientes siempre.
  - Secciones de Config: `HORARIOS` → `puedeVerHorarios`; `HISTORIA_CLINICA` → hay HC (sin cambio: RF-16); `OBRAS_SOCIALES`, `RESERVAS`, `CUPO`, `MODULOS`, `MENSAJES` → `puedeVerConfigDeAgenda`.
  - «Nuevo turno» de la ficha del paciente (`:267`) → `puedeVerAgenda` (vale para el médico y para la secretaria, que usan el mismo contexto; RF-10).
  - Rutas de turnos (`AGENDA`, `AGENDA_SEMANA`, `ASIGNAR`, `HORARIOS`, `OBRAS_SOCIALES`, `CONFIG_SECCION`, `RECETAS`): si el contexto no tiene agenda muestran «Este médico no tiene agenda de turnos» en vez de la
    pantalla. Hace falta porque en web la URL restaura la ruta (la sesión del médico pudo quedar en `config/horarios` antes de que el administrador apague la agenda). RF-7, RF-10.
  - `CONFIG_HC` y las rutas de administración no cambian. RF-16.

## Datos locales y migraciones
- **No hay migración.** Se reutilizan `medico.tiene_turnos` y `medico.visible_en_turnos`; cambia su significado (ver D-P1) y la copia queda `dirty = 0`. Así no se pelea con los `6.sqm` a `8.sqm` de `hc-general`.
- Un dispositivo con datos viejos se corrige solo: el médico al iniciar sesión (o al reabrir la app, `restaurar`) y el administrador al abrir el panel (o «Sincronizar ahora») reescriben su fila con lo que informa turnos.

## Cambios fuera de este repo (dependencia: turnosonlinebb, lo hace el usuario en la otra PC)
Detalle completo en `servidor-turnos.md`. Lo que esta app **consume**, y nada más (no se inventa ninguna respuesta que el brief no defina):
| Qué | Forma que lee la app |
|---|---|
| `GET catalogos` → cada elemento de `medicos` | además de `tiene_horarios`: `tiene_agenda` y `visible_en_turnos`, enteros `0`/`1` |
| `GET auth/perfil` → `medico` | los mismos dos campos |
| `GET auth/perfil` (rol secretaria) → cada elemento de `secretaria.medicos[]` | los mismos dos campos, enteros `0`/`1` (decisión Q2; ya está en `servidor-turnos.md`) |
| `PUT medicos/{id}/agenda` (solo administrador; `{id}` = número del médico en turnos) | cuerpo con al menos uno de `tiene_agenda` / `visible_en_turnos` (booleanos). `200` → `{medico_id, tiene_agenda, visible_en_turnos, turnos_futuros}` (booleanos y entero) |
| Errores del `PUT` | `403` (no administrador), `404` (médico inexistente), `422` `datos_invalidos` y `422` `agenda_requerida`. **La app no ramifica por el código**: muestra el `mensaje` que manda turnos. Solo distingue el `404`/`405` **sin** `codigo` (ruta inexistente → RF-13) |
| `POST usuarios` | acepta `tiene_agenda` (booleano); la app lo manda siempre para el médico |
| Relleno de la columna nueva | `tiene_agenda = 1` si tiene horarios, `0` si no (RF-17); `castigo_automatico` no se toca |

**Orden de despliegue:** (1) respaldo de producción; (2) migración / SQL y verificación del conteo de RF-17; (3) subir los PHP; (4) `GET` de comprobación contra producción (sin escribir);
(5) recién entonces publicar la versión de la app (push a `main` publica la web: confirmar antes). Con la app publicada antes que el servidor, nada se rompe: usa `tiene_horarios` y el `PUT` da el aviso de RF-13.

**Secretaria (Q2, decidido):** una secretaria recibe a sus médicos en `auth/perfil` → `secretaria.medicos[]` y los importa `importarSecretaria`. El brief del servidor ya pide los campos nuevos también ahí, y que se revise si esa lista
se arma en otro lugar que `medico` y `catalogos`. Si el servidor no los manda en esa lista, la secretaria queda con el criterio de `tiene_horarios` (RF-13) y RF-10 no se cumple para ella: **T9 lo verifica** (M8) antes de dar el servidor por bueno.

## Sin conexión y reconciliación
- **Leer:** la app usa la última copia (`medico.tiene_turnos` / `visible_en_turnos`), como el resto; el médico sin señal sigue con lo que su dispositivo tenía (`login` sin conexión rearma desde la base local).
- **Escribir:** exige conexión (D0-c). Sin `online` → `IllegalStateException`; con red caída `TobbException(0, "red", …)`; en los dos casos el interruptor queda como estaba y `mensaje` dice por qué. No hay cola ni reintento.
- **Dueño del dato:** turnos. La copia se escribe `dirty = false` y solo después de la confirmación, así `SyncEngine` nunca la empuja (regla de `AGENTS.md`: los datos de dueño remoto no quedan `dirty`).
- **Dos administradores a la vez:** gana el último pedido; el cuerpo solo lleva el campo tocado (D-P4), así que uno no pisa con un valor viejo el campo que el otro no tocó.

## Decisiones (con la alternativa descartada)
- **D-P1. Sin migración; `tieneTurnos` cambia de significado y no de nombre.** Se documenta en KDoc. *Descartado:* renombrar la columna y el campo (`tieneAgenda`) con una migración: exige un `N.sqm` que
  hay que reservar contra `hc-general` (`6` a `8`), toca ~14 usos y tests, y en la web una migración fallida borra la base del navegador; el beneficio es solo el nombre. Se puede hacer más adelante.
- **D-P2. Una sola fuente de reglas** (`AgendaDelMedico`) para menú, configuración, panel y selector. *Descartado:* seguir con condiciones sueltas en cada pantalla (como hoy): fue lo que dejó a `HORARIOS`
  mirando el consultorio y a la secretaria mirando `consultorioId.isNotBlank()`.
- **D-P3. Escribir en turnos primero y en la copia después.** El `Switch` está atado al valor guardado, así que si turnos rechaza, vuelve solo. *Descartado:* cambio optimista con marcha atrás: RF-2 pide mostrar
  el estado nuevo solo tras la confirmación y el dueño del dato es remoto.
- **D-P4. Un `PUT` por interruptor, con solo el campo tocado.** *Descartado:* mandar siempre los dos valores desde la copia local: con una copia vieja pisaría lo que otro administrador cambió en el campo que no se tocó.
- **D-P5. `tiene_agenda: Boolean? = null` en el alta.** Con `encodeDefaults = false` el `null` no viaja y el `false` sí. *Descartado:* `@EncodeDefault` (anotación experimental y manda el campo también al alta de secretaria
  y administrador, que el brief no contempla) y quitar el valor por omisión (obliga a pasar el campo en todas las altas, incluidos los tests de secretaria).
- **D-P6. `Int?` en los DTO, `null` = «no informa».** *Descartado:* valor por omisión `1` como `tiene_horarios`: no distingue «el servidor dijo 1» de «el servidor no dijo nada» y rompe RF-13.
- **D-P7. Si turnos no informa `visible_en_turnos`, se conserva el valor previo del dispositivo.** *Descartado:* el valor por omisión del modelo (`true`), que es el defecto actual de `sincronizarAdministracion`.
- **D-P8. La regla del consultorio sale de `tieneTurnos` y vive en `puedeVerAgenda` / `puedeVerHorarios`.** Así `tieneTurnos` es solo la decisión del administrador (RF-8: con agenda, sin horarios, ve la configuración).
  *Descartado:* conservar `consultorioId != null && …` en el importador: mezcla decisión con datos completos y esconde la configuración de turnos al médico con agenda que aún no tiene consultorio.
- **D-P9. Las pantallas de turnos también se cierran por ruta**, no solo por menú. *Descartado:* ocultar solo el menú: en web la URL restaura la ruta anterior.
- **D-P10. Interruptores deshabilitados mientras el pedido está en vuelo.** *Descartado:* dejar tocar dos veces (dos `PUT` cruzados con la misma copia).
- **D-P11. «Visible» se puede apagar siempre; encender exige agenda.** Permite limpiar la anomalía «visible sin agenda» que el servidor informa tal cual (caso límite de la spec). *Descartado:* deshabilitarlo del todo sin
  agenda: el administrador no podría corregir esos médicos sin prender y apagar la agenda. Se apoya en la regla 5 del brief (solo `visible_en_turnos = true` exige agenda), que hay que confirmar en la prueba del servidor.
- **D-P12. El aviso de RF-18 se da después de guardar** (con el `turnos_futuros` de la respuesta), sin confirmación previa. Es lo que fija D2. *Descartado:* pedir confirmación antes: exigiría contar los turnos antes de cambiar.

## Riesgos y pendientes
- **RF-17 depende del relleno del servidor.** Si la migración no iguala `tiene_agenda` a «tiene horarios», al publicar la app cambian estados. Verificar el conteo en producción antes del paso 5.
- **Defecto hermano (fuera de alcance, anotar como pendiente):** `setEspecialidadHc`, consultorio, especialidad, teléfono y mail de la misma tarjeta también guardan **solo local** (`dirty = true`) en `main` y turnos
  los pisa. Esta spec arregla solo los dos interruptores.
- **Alta con servidor viejo:** ignora `tiene_agenda`; el médico queda según `mostrar_en_turnos`. La app no puede detectarlo en el momento; al volver a traer la administración se verá el estado real.
- **Médico con agenda y sin consultorio:** no puede abrir Agenda ni Horarios (las pantallas necesitan el consultorio) aunque la agenda esté prendida. Turnos exige consultorio en toda ficha, así que es raro; no se agrega mensaje.
- **Consultorio `0` en el panel:** `sincronizarAdministracion` guarda `tobb-c0` para el médico sin consultorio (punto 2). No se corrige acá (cambiaría datos que nadie pidió tocar); `tieneConsultorio` lo trata
  como «sin consultorio» para que el botón «Horarios» y la agenda no se ofrezcan con un consultorio inexistente. Queda anotado como pendiente.
- **Secretaria y `secretaria.medicos[]`:** depende de que el servidor mande los campos ahí (ver «Cambios fuera de este repo»); con servidor viejo o incompleto rige `tiene_horarios`.
- **Integración con `hc-general`:** esa rama normaliza `hepatologia → clinica` en `TurnosImportador` y `AgendaTurnosOnline`. Los cambios de este plan van en líneas propias (`tieneTurnos` / `visibleEnTurnos` y un método nuevo) para
  que el merge sea trivial; no se reformatea nada alrededor. Si `hc-general` se integra antes, rehacer los números de línea.
- **`:server` (`TurnosBridge`)** sigue con `tieneTurnos = true`: la app no habla con él (AGENTS.md). No se toca.
- `refrescarPerfil()` no consulta a turnos (punto 6): no usarlo como mecanismo de RF-15.

## Estrategia de tests
Gradle (Mac): `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` y `./gradlew :core:data:jvmTest` (Windows: `.\gradlew`). Uno solo: `--tests '*AgendaDelMedicoTest*'`.
Los de red usan `com.sun.net.httpserver.HttpServer` en `127.0.0.1`, nunca un `TurnosOnlineClient()` por defecto (apunta a producción), con base temporal `createDatabase(DriverFactory(archivo))`. Fixtures con ids y nombres inventados.

| RF | Test (módulo `core:data`) |
|---|---|
| RF-1 | `AgendaTurnosOnlineAgendaTest` (jvmTest): el `PUT` sale a `/api/salud360/medicos/<n>/agenda` con método, token y cuerpo esperados. El guardado en la base de turnos se prueba en el servidor (curl del brief). |
| RF-2 | `AgendaTurnosOnlineAgendaTest`: la copia local cambia solo después de la respuesta y queda `dirty = 0` con el estado final que informó turnos (aunque difiera del pedido); respuesta 200 sin los booleanos = no confirmada. `TobbAgendaDtosTest` (commonTest): decodificación de la respuesta. |
| RF-3 | `AgendaTurnosOnlineAgendaTest`: `422 agenda_requerida`, `403`, `404` con `codigo`, `500` y red caída lanzan `TobbException` con el motivo y la copia queda igual. `TurnosRepository` sin `online` lanza `IllegalStateException` (mismo archivo). |
| RF-4 | Servidor (curl: token de médico/sin token). En la app, solo el administrador ve el panel (guarda existente `pantallaAdmin`); se mira a mano (M10). |
| RF-5 | `AgendaDelMedicoTest` (commonTest): apagar agenda manda solo `tiene_agenda = false`; apagar + pedir visible = `Rechazado`. `AgendaTurnosOnlineAgendaTest`: la respuesta con `visible = false` deja la copia no visible. |
| RF-6 | `AgendaDelMedicoTest`: `visible = true` sin agenda = `Rechazado`; con agenda pedida en el mismo cambio = `Enviar`; `puedeCambiarVisible` (con/sin agenda, visible/no visible). |
| RF-7 | `AgendaDelMedicoTest`: tabla sin agenda → `puedeVerAgenda`, `puedeVerHorarios`, `puedeVerConfigDeAgenda`, `puedeVerRecetas` = false; `puedeVerMenuConfig` = true solo con HC. Pantalla: M2, M11. |
| RF-8 | `AgendaDelMedicoTest`: con agenda → todo true (con consultorio); consultorio nulo / vacío / `tobb-c0` → agenda y horarios false. Pantalla: M3. |
| RF-9 | `AgendaDelMedicoTest` (mismas reglas). Pantalla: M1. |
| RF-10 | `AgendaDelMedicoTest` (con agenda → agenda y configuración de turnos, incluidas obras sociales, sí; sin agenda → no, y `puedeVerAgenda` también cubre «nuevo turno»). `TobbPerfilTest` (perfil de secretaria: `secretaria.medicos[]` decodifica los dos campos). `TurnosImportadorAgendaTest`: `importar` de un perfil de secretaria guarda a cada médico de `secretaria.medicos[]` con el estado de agenda que informa turnos (sin campos → `tiene_horarios`). Pantalla: M8. |
| RF-11 | Solo servidor (curl del brief y la web del paciente). Sin test en la app. |
| RF-12 | `TobbPerfilTest` y `TobbAgendaDtosTest` (commonTest): campos presentes → valor; ausentes → `null`. `TurnosImportadorAgendaTest` (jvmTest): `importar` toma `tiene_agenda` / `visible_en_turnos` aunque `tiene_horarios` diga otra cosa. `AgendaTurnosOnlineAgendaTest`: `sincronizarAdministracion` lo mismo, y **no pisa `visible`** (el caso del defecto). |
| RF-13 | `AgendaDelMedicoTest` (`tieneAgendaDe`, `visibleDe` con `null`). `TurnosImportadorAgendaTest` y `AgendaTurnosOnlineAgendaTest`: sin campos nuevos, `tieneTurnos` = `tiene_horarios == 1` y `visible` = el previo (o `true`). `AgendaTurnosOnlineAgendaTest`: `PUT` con `404`/`405` sin `codigo` → `TobbException` `sin_ruta` con el aviso «todavía no permite… (falta actualizar turnosonlinebb)». |
| RF-14 | `TobbAltaUsuarioTest` (commonTest, ampliado): `tiene_agenda` viaja siempre para el médico (`true` y también `false`); no viaja si es `null` (secretaria); con `mostrar_en_turnos`. Pantalla: M7. |
| RF-15 | `TurnosImportadorAgendaTest`: tras `importar` con el estado nuevo, `resolverPerfil(db, usuario).medico.tieneTurnos` cambia (es lo que hacen `login` y `restaurar`). Camino completo: M9. |
| RF-16 | `AgendaTurnosOnlineAgendaTest`: tras `guardarAgenda`, `especialidadesHc` y la fila de licencia del médico quedan idénticas. |
| RF-17 | Servidor: conteo posterior a la migración (brief). App: `TurnosImportadorAgendaTest` y `AgendaTurnosOnlineAgendaTest` (sin campos nuevos, el estado es el de hoy). |
| RF-18 | `AgendaDelMedicoTest` (`avisoDeAgendaGuardada`: apagar con N > 0 informa N; con 0 no; prender no; ya apagada no). `AgendaTurnosOnlineAgendaTest`: `turnos_futuros` llega al resultado. Pantalla: M6. |

Tests existentes que se amplían: `TobbPerfilTest`, `TobbAltaUsuarioTest`. Los que construyen `Medico(..., tieneTurnos = true)` / `PerfilMedico(...)` (`LicenciaTest`, `LicenciaDelServidorTest`, `AuthRepositoryLicenciaServidorTest`, `HcLicenciasE2ETest`) no cambian: no se renombra nada.
No hay tests en `features/*` ni `composeApp`: la lógica nueva está toda en `core/data` y la interfaz se verifica a mano (el cierre compila `:composeApp` para web).

### Verificación a mano (`devWeb` contra el turnos local con el servidor nuevo; nunca producción)
Actualización 2026-11-05: el servidor nuevo ya está pusheado y subido a **producción**, y la copia de MAMP de esta Mac es un repo git sincronizado con producción y con los cambios de la spec. Por decisión del usuario, M1 a M11 las hace él contra producción (la app aún no la usa nadie más); M5 y M12 (servidor viejo) ya no se pueden probar a mano y quedan cubiertos por `AgendaTurnosOnlineAgendaTest` y `TurnosImportadorAgendaTest`. Necesita un administrador, un médico solo con HC, un médico con agenda y una secretaria de prueba, con credenciales fuera del repo.
- M1 (RF-2, 5, 6, 9): administrador apaga «Tiene agenda» de un médico con agenda → el interruptor queda apagado recién tras la respuesta, «Visible» queda apagado y deshabilitado, y desaparecen «Módulos y configuración» y «Horarios».
- M2 (RF-7, 16): ese médico entra de nuevo → sin Agenda ni Recetas; «Config» solo ofrece «Secciones de la historia clínica» (sin «Horarios de atención»); sus HC siguen abriendo.
- M3 (RF-8): administrador prende la agenda de un médico sin horarios cargados → al entrar ve Agenda, Config con Horarios y el resto.
- M4 (RF-11): con agenda interna (visible apagado) el médico no figura en la web del paciente; al prender «Visible» figura.
- M5 (RF-3, 13): con el servidor apagado y con token vencido, el interruptor no cambia y el mensaje dice por qué. (El caso «servidor viejo: todavía no permite…» ya no se puede probar a mano: lo cubre `AgendaTurnosOnlineAgendaTest`.)
- M6 (RF-18): apagar la agenda de un médico con turnos futuros → mensaje con la cantidad; los turnos siguen en la agenda de la web.
- M7 (RF-14): alta de médico → «Tiene agenda» apagada por omisión, «Mostrar para pedir turno» deshabilitada sin agenda; con agenda marcada el médico queda con agenda.
- M8 (RF-10, Q2): secretaria → el selector muestra Con/Sin agenda reales (si muestra «Con agenda» para todos, el servidor no está mandando los campos en `secretaria.medicos[]`); elegir uno sin agenda lleva a Pacientes y no hay Agenda, Obras sociales ni «Nuevo turno» en la ficha del paciente; elegir uno con agenda, sí (aunque no sea visible).
- M9 (RF-15): médico con sesión abierta; el administrador cambia el interruptor; el médico recarga o vuelve a entrar → ve el cambio.
- M10 (RF-4, HU-3): un médico no ve el panel; «Sincronizar ahora» del administrador no cambia los interruptores.
- M11 (RF-7): en web, médico sin agenda escribe a mano en la barra del navegador la dirección de la pantalla de horarios (la ruta interna `config/horarios`) → «Este médico no tiene agenda de turnos».
- M12 (RF-13): contra un servidor viejo la app se comporta como hoy (agenda según `tiene_horarios`). Ya no se puede probar a mano (el servidor de producción está actualizado): lo cubren `TurnosImportadorAgendaTest` y `AgendaTurnosOnlineAgendaTest`.

## Qué RF cubre cada parte
| Parte | RF |
|---|---|
| DTO y modelo (`TobbMedico`, `TobbMedicoCatalogo`, alta, `TobbAgenda*`) | RF-1, RF-2, RF-12, RF-13, RF-14, RF-18 |
| `AgendaDelMedico` | RF-5, RF-6, RF-7, RF-8, RF-9, RF-10, RF-12, RF-13, RF-18 |
| `TurnosImportador` | RF-10, RF-12, RF-13, RF-15, RF-17 |
| `AgendaTurnosOnline` (`sincronizarAdministracion`, `guardarAgenda`) | RF-1, RF-2, RF-3, RF-5, RF-12, RF-13, RF-16, RF-17, RF-18 |
| `TurnosRepository` | RF-2, RF-3 |
| `AdminViewModel` y `AdminScreens` | RF-2, RF-3, RF-5, RF-6, RF-9, RF-14, RF-18 |
| `MainShell` (y `SelectorMedicoScreen` sin cambios) | RF-7, RF-8, RF-10, RF-16 |
| Servidor (fuera del repo) | RF-1, RF-4, RF-5, RF-6, RF-11, RF-17, y la entrega de RF-12 |

## Documentación a actualizar al cerrar
- `AGENTS.md`, «Arquitectura que no se ve en los nombres», una línea: la agenda del médico se decide con `tiene_agenda` de turnos; la escribe el administrador con `PUT medicos/{id}/agenda`; `Medico.tieneTurnos` guarda ese dato;
  `tiene_horarios` es solo respaldo; el resto de los campos de la tarjeta del médico siguen siendo locales.
- `MEMORY.md` (estado de la spec 005 y, al cerrar, los aprendizajes).
- `specs/README.md` (estado de la fila).
- `docs/hc-pediatria-estado-y-plan.md`, sección «Cómo darlos de alta sin publicarlos para sacar turno» (los párrafos sobre `tiene_horarios`, `castigo_automatico` y el alta del panel): ahora la agenda es una decisión aparte, con `tiene_agenda`.
- `docs/ARQUITECTURA.md`, «Roles y acceso» (dice que `tieneTurnos` es «si tiene consultorio en turnosonlinebb»: ya no es cierto desde antes de esta spec).
- KDoc de `Medico.tieneTurnos`, `Medico.visibleEnTurnos`, `PerfilMedico.tieneTurnos`, `TobbMedico` y `TobbMedicoCatalogo`.

## Preguntas abiertas
Ninguna. Q1 y Q2 las decidió el usuario el 2026-11-04 (ver «Decididas» en `spec.md`).
