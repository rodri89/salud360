# Spec 004 — La licencia la decide el servidor

Estado: **aprobada por el usuario el 2026-11-02**, con las preguntas P1 a P6 decididas (ver «Decididas»). Alcance
aprobado: implementar T1 a T8 en el worktree, encadenadas, sin push ni publicación; publicar a `main` (la web) y la
tarea T9 (cherry-pick a `hc-general`) requieren pedido aparte. Es un arreglo de la app **publicada** (`origin/main` =
`d3b6e7d`, del 2026-09-29), no de la rama `hc-general`: el código se escribe en el worktree `..\salud360-pediatria` (rama
`pediatria-licencia-servidor`) y recién después se trae a `hc-general`. Depende de la spec de licencias que ya
existe en pediatría (`docs/hc-pediatria-estado-y-plan.md`, «Las licencias»), que ya está en producción.

## Contexto y objetivo
Para entrar a la historia clínica de pediatría un médico necesita una licencia vigente. La licencia vive en la base
de pediatría (es ahí donde se cobra y donde la administra el panel), y el servidor de pediatría ya aplica la regla:
con la licencia vencida, dada de baja o inexistente rechaza al médico con `licencia_vencida`.

La app, además, **repite la regla por su cuenta** al iniciar sesión, al recargar la página y al refrescar el perfil,
pero no la lee del servidor: la lee de una **copia guardada en el navegador o dispositivo**, que solo se actualiza
cuando un administrador abre el panel de licencias en ese mismo dispositivo.

**Problema real (2026-11-02):** un médico inició sesión en la app publicada y recibió «Tu licencia está vencida»
aunque en pediatría su licencia estaba vigente (activa, con vencimiento en 2027). La copia local del navegador estaba
desactualizada: alguien le había dado de baja la licencia desde el panel en ese navegador y, después, se la
había renovado en otro lugar. Se arregló a mano sincronizando desde el panel de administración. La decisión del
usuario: *«siempre debe leer el api, no debe leer local»*. Como la copia solo existe en dispositivos donde un
administrador abrió el panel, el error aparece justo en la computadora compartida; el error inverso (una copia que
dice «vigente» cuando el servidor ya la dio de baja) deja entrar a la app a quien el servidor rechaza.

**Objetivo:** cuando hay conexión, la app decide si el médico entra con **lo que contesta el servidor de pediatría
en ese momento**; la copia local pasa a ser solo un respaldo para cuando no hay señal (constitución #2: el dueño del
dato manda; la base del dispositivo es caché y modo sin señal).

## Usuarios
- **Médico con la HC de pediatría habilitada** (con o sin agenda): el único afectado. Hoy es la única HC con
  licencia.
- **Administrador**: no tiene licencia; no cambia nada para él. Sigue administrando licencias desde el panel.
- **Secretaria**: no tiene licencia en la app; no cambia nada.
- Médico **sin** ninguna HC que lleve licencia (solo agenda, o solo la HC general): no se le consulta nada.

## Historias de usuario
- HU-1. Como médico, quiero que la app me deje entrar si mi licencia está vigente en pediatría, aunque la copia
  guardada en mi navegador esté vieja, para no quedar afuera por un dato desactualizado.
- HU-2. Como administrador, quiero que un médico al que di de baja la licencia quede afuera en su próximo ingreso o
  recarga, aunque su navegador tenga una copia vieja que diga «vigente».
- HU-3. Como médico en un consultorio sin señal, quiero poder abrir la app con la última licencia conocida, para no
  perder la atención por una caída de internet.

## Definiciones
- **Consulta de licencia**: un pedido de lectura al servidor de pediatría, con la sesión del médico, que informa
  cómo está su licencia.
- **Respuesta concluyente**: el servidor contestó (a) *vigente*, con su fecha de vencimiento y su fecha de aviso,
  (b) *vencida* (rechazo `licencia_vencida` con una licencia cargada que está vencida o dada de baja: el cuerpo trae
  `licencia` con su fecha), o (c) *sin licencia* (rechazo `licencia_vencida` con `licencia` **nula** en el cuerpo: el
  médico no tiene ninguna fila de licencia en pediatría).
- **Sin respuesta concluyente**: todo lo demás. Sin conexión, tiempo agotado, error del servidor (5xx), límite de
  pedidos (429), `tobb_caido` (503), respuesta ilegible, otro rechazo 401/403 que no sea `licencia_vencida`, o una
  respuesta correcta que no trae licencia (servidor sin la mejora, o servidor en su gracia cuando turnos no responde).
- **Copia local**: la licencia del médico guardada en el dispositivo.
- **Momento de entrada**: iniciar sesión, restaurar la sesión guardada (recargar la página o reabrir la app) y
  refrescar el perfil con la sesión abierta.

## Requisitos funcionales
### Cuándo se consulta
- RF-1. CUANDO un médico con una HC que lleva licencia inicia sesión con conexión, EL SISTEMA consulta su licencia al
  servidor de esa HC y decide con la respuesta, antes de dejarlo entrar.
- RF-2. CUANDO se restaura una sesión guardada de un médico con una HC que lleva licencia, EL SISTEMA consulta su
  licencia al servidor y decide con la respuesta, antes de mostrarle la app.
- RF-3. CUANDO se refresca el perfil con la sesión abierta, EL SISTEMA decide con la consulta más reciente si tiene
  menos de 60 segundos y, si no, consulta de nuevo.
- RF-4. EL SISTEMA no consulta la licencia del administrador, de la secretaria ni del médico sin ninguna HC que lleve
  licencia.

### Quién decide
- RF-5. CUANDO el servidor contesta *vencida* (rechazo `licencia_vencida` con una licencia cargada), EL SISTEMA no deja
  entrar al médico (o le cierra la sesión), con el texto de licencia vencida que ya existe, **aunque la copia local
  diga vigente**.
- RF-6. CUANDO el servidor contesta *vigente*, EL SISTEMA deja entrar al médico, **aunque la copia local diga
  vencida, inactiva o no exista, y sin volver a comparar la fecha con el reloj del dispositivo**: manda el servidor.
- RF-7. SI no hay respuesta concluyente, ENTONCES EL SISTEMA decide con la copia local, con la misma regla de hoy,
  y no modifica la copia.
- RF-8. SI no hay respuesta concluyente y no hay copia local, ENTONCES EL SISTEMA deja entrar al médico (la copia
  ausente no es una licencia vencida; el servidor lo frena cuando haya señal). (P1, decidida.)
- RF-9. SI el servidor rechaza con 401 o 403 por un motivo que no es la licencia (HC no habilitada, médico no
  vinculado, sin permiso, sesión vencida), ENTONCES EL SISTEMA lo trata como «sin respuesta concluyente»: decide la
  copia local (RF-7); ni bloquea ni cierra la sesión por eso por sí mismo. (P4, decidida.)
- RF-10. SI el servidor rechaza con `licencia_vencida` y con `licencia` nula en el cuerpo (el médico no tiene ninguna
  licencia cargada en pediatría), ENTONCES EL SISTEMA **no bloquea el ingreso ni cierra la sesión** y no modifica la
  copia local: el médico entra a la app y a su agenda, y **solo la historia clínica de pediatría queda sin poder
  usarse**, porque el servidor sigue rechazando con 403 cada lectura de HC y la pantalla de HC muestra el error con el
  texto del servidor (hoy: «No se pudo traer la historia clínica…»). Esto vale **aunque la copia local diga vencida o
  inactiva**: el servidor dijo que no hay licencia que decidir. (P3, decidida: distinta de la recomendación del
  planificador, que era bloquear toda la app.)
- RF-21. EL SISTEMA distingue «sin licencia» de «vencida» solo cuando el cuerpo del rechazo `licencia_vencida` (un 403
  con `codigo` en un cuerpo JSON) trae la clave `licencia` **explícitamente nula**. Si el rechazo es `licencia_vencida`
  y la clave `licencia` falta, o es de otro tipo (por ejemplo texto, número o lista) o es un objeto con fecha: es
  *vencida* (RF-5). Ante la duda se bloquea, como antes de esta decisión. Un cuerpo que **no es JSON** (sin `codigo`)
  o un 403 sin código no se reconoce como `licencia_vencida`: es «sin respuesta concluyente» (RF-9) y decide la copia
  local.
  (aclaración 2026-11-02, T3b: la redacción anterior decía «cuerpo ilegible → *vencida*», que era imposible de
  cumplir: sin JSON no hay `codigo` y no se puede saber que el rechazo era `licencia_vencida`. No es un requisito
  nuevo: es lo que el código hace y lo único posible. «Ilegible → *vencida*» solo vale para un rechazo ya reconocido
  como `licencia_vencida` cuyo cuerpo no se conservó.)
- RF-22. EL SISTEMA conserva el cuerpo de un error de la API de HC para poder leer esa clave, **sin cambiar** el
  código, el estado HTTP ni el mensaje de ningún error existente (ni la forma de construir `TobbException`).

### La copia local
- RF-11. CUANDO hay respuesta concluyente, EL SISTEMA actualiza la copia local con lo que contestó el servidor: si
  es *vigente*, la fecha de vencimiento, la de aviso y la licencia como activa; si es *vencida*, la licencia como
  inactiva; si es *sin licencia*, la copia no se toca (no hay nada que informar: no se inventa una fecha ni se
  borra lo que hubiera). La copia nunca queda pendiente de envío (el dueño del dato es el servidor).
- RF-12. EL SISTEMA no pisa con vacíos lo que el servidor no informó: el importe y las fechas que el pedido no trae
  se conservan. Si no hay respuesta concluyente, la copia no se toca. Si la respuesta no cambia nada, no se reescribe.

### Cuándo se bloquea
- RF-13. CUANDO la licencia bloquea el ingreso, EL SISTEMA no deja la sesión abierta ni guardada en ningún momento:
  la pantalla de ingreso muestra el aviso, no se navega a la app y no arranca la sincronización.

### Rendimiento y tiempos
- RF-14. EL SISTEMA hace **a lo sumo un pedido adicional al servidor de la HC por cada inicio de sesión o
  restauración**, y el refresco de perfil que sigue al inicio de sesión reutiliza esa respuesta (RF-3). (El servidor
  limita a 60 pedidos por minuto por dirección IP, y un consultorio comparte la IP.)
- RF-15. EL SISTEMA espera la consulta como máximo 10 segundos al iniciar sesión y 5 al restaurar la sesión; pasado el
  tope es «sin respuesta concluyente» (RF-7). Recargar la página con mala señal no puede dejar la pantalla de carga
  más tiempo que ese tope por la licencia. (P5, decidida: se espera antes de mostrar la app.)
- RF-16. EL SISTEMA solo lee del servidor de la HC para esto: no le envía cambios de licencia.

### Lo que no cambia
- RF-17. EL SISTEMA mantiene como hoy el panel del administrador: leer las licencias de la HC y guardarlas allá (la
  copia queda limpia).
- RF-18. EL SISTEMA reutiliza los textos existentes («Tu licencia está vencida. Comunicate con el administrador para
  renovarla.» y «Tu licencia está por vencer el <fecha>»); esta spec no agrega ninguno. El aviso por vencer se muestra
  en las mismas condiciones que hoy.
- RF-19. EL SISTEMA sigue funcionando contra un servidor de HC que todavía no informa la licencia en el perfil (se
  vuelve «sin respuesta concluyente», RF-7).
- RF-20. EL SISTEMA no escribe tokens ni fechas de licencia en sus registros.

## Requisitos no funcionales
- **Sin conexión** (constitución #2): sin señal la app se comporta exactamente como hoy (RF-7). No se agrega ninguna
  restricción por antigüedad de la copia (P2).
- **Plataformas:** web, Android e iOS por igual (lógica común, sin código de plataforma).
- **Datos sensibles** (constitución #3): repo público; las pruebas usan fixtures sin nombres, mails ni tokens reales.
- **Sin cambios en la base local**: ni migraciones ni columnas nuevas (se usa la tabla de licencias que ya existe).
- **Sin cambios en ningún servidor**: pediatría ya está arriba con lo que hace falta.

## Casos límite
1. Médico con licencia vigente en el servidor y copia local vencida o dada de baja (el caso reportado): entra y la
   copia queda vigente.
2. Médico con licencia vencida o dada de baja en el servidor y copia local vigente o inexistente: no entra / se le
   cierra la sesión.
3. Médico que entra por primera vez en un dispositivo, sin copia y con la licencia vigente: entra y se crea la copia.
4. Pediatría no responde pero turnos sí: entra o no según la copia (RF-7); sin copia entra (RF-8).
5. El servidor de pediatría atiende con su gracia de 24 h porque turnos no le contesta: ese servidor no revisa la
   licencia en ese caso y contesta correcto **sin licencia**; para la app es «sin respuesta concluyente».
6. El servidor contesta `429` (demasiados pedidos): «sin respuesta concluyente»; no se reintenta en bucle.
7. Médico que además es de otra HC sin licencia (p. ej. la HC general): esa HC no cuenta; solo se consultan las que
   llevan licencia.
8. Licencia que vence hoy: la decide el servidor (su reloj y su regla de fechas, que se revisa aparte).
9. Dos médicos que comparten el mismo navegador: cada copia es de su médico; no se cruzan.
10. Sesión abierta durante días: la licencia no se vuelve a consultar sola (fuera de alcance); si venció, las
    pantallas de HC muestran el rechazo del servidor como hoy.
11. Ingreso sin conexión a turnos (el camino «sin señal» del inicio de sesión): no se consulta nada y se usa la copia,
    como hoy (RF-7).
12. Respuesta *vigente* con la fecha de vencimiento en blanco: no es concluyente.
13. Reloj del dispositivo corrido: con respuesta concluyente no importa; sin ella decide la copia con el reloj, como hoy.
14. **Médico con pediatría habilitada y sin ninguna fila de licencia en pediatría** (el servidor contesta `403
    licencia_vencida` con `licencia: null`): entra a la app y a su agenda (RF-10); la copia local no se crea ni se
    modifica; la HC de pediatría muestra el rechazo del servidor. Si además tiene HC general, esa funciona.
15. Médico sin fila de licencia **y con una copia local vencida o inactiva** (p. ej. de otro tiempo): entra igual
    (RF-10); manda lo que dijo el servidor, no la copia.
16. Rechazo `licencia_vencida` cuyo cuerpo JSON no trae la clave `licencia` o la trae de otro tipo: se trata como
    *vencida* y bloquea (RF-21). Un 403 cuyo cuerpo no es JSON, o sin `codigo`, es «sin respuesta concluyente» y
    decide la copia local (aclaración 2026-11-02, T3b).

## Fuera de alcance
- Cambios en los servidores (pediatría, turnos, `hclinica`) y en la rama `hc-general`/clínica.
- Migraciones o columnas nuevas en la base local.
- La regla del servidor que decide quién tiene licencia (`conLicencia`) y sus diferencias con la web: `activo = 0`
  dentro de la ventana de aviso, hora UTC contra hora de Buenos Aires, varias filas por médico. Se evalúan aparte.
  (El médico sin fila ya no es una diferencia a evaluar acá: la app lo deja entrar y el servidor frena la HC, RF-10.)
- Bloquear desde la app la HC de pediatría del médico sin fila o mostrarle un texto propio: lo único que se ve es el
  rechazo del servidor que ya sale hoy.
- Volver a consultar la licencia durante una sesión larga (reloj periódico) y reaccionar a un `licencia_vencida` que
  llegue desde cualquier otra pantalla de HC.
- Ahorrar el pedido del aviso por vencer que hace la pantalla principal (P6).
- Un límite de antigüedad de la copia sin señal (P2).
- Mostrar un mensaje específico al médico que pierde la sesión al recargar (hoy vuelve a la pantalla de ingreso sin
  texto, como antes).
- Cambios en `SyncEngine` (sigue sin llegar a ninguna ruta que exista).

## Criterios de finalización
1. `.\gradlew :core:data:jvmTest` en verde (incluye `LicenciaTest` sin tocar y los tests nuevos de esta spec).
2. `.\gradlew :features:auth:compileKotlinJvm` y `.\gradlew :composeApp:compileKotlinWasmJs` compilan.
3. `.\gradlew :core:data:jvmTest --rerun --tests '*HcLicenciasE2ETest*' -Psalud360.test.hc.pediatria=<url de la copia
   local> -Psalud360.test.hc.token=<token médico> -Psalud360.test.hc.token.admin=<token admin>` en verde contra la
   **copia local** de pediatría (nunca contra producción), con la licencia de prueba restaurada al terminar.
4. Cada RF de lógica tiene su test (matriz en `plan.md`, «Estrategia de tests»); los de interfaz (RF-10 en la HC,
   RF-13, RF-15, RF-18) los prueba el usuario con la lista M1 a M9 de `tasks.md`.
5. Revisión del reviewer (RF por RF) y `MEMORY.md` actualizado.
6. La integración a `hc-general` y la publicación a `main` **no** son parte de esta spec: cada una requiere pedido
   aparte (publicar a `main` publica la web).

## Decididas (2026-11-02, usuario)
- **P1.** Sin respuesta y sin copia local: **entra** (como hoy). Opción (a). RF-8.
- **P2.** Copia local **sin límite de antigüedad** cuando no hay señal. Opción (a).
- **P3.** Médico sin ninguna fila de licencia en pediatría: **no se bloquea la app; se bloquea solo la historia clínica
  de pediatría.** Opción (b), **distinta de la recomendación del planificador** ((a), bloquear toda la app). El servidor
  responde `403 licencia_vencida` con `licencia: null` (sin fila) o con `licencia: {vence, vencida: true}` (fila
  vencida o dada de baja): con fecha, se mantiene el bloqueo de toda la app (RF-5); sin fila (`licencia` nula), el
  ingreso no se bloquea y la HC de pediatría muestra el error con el texto del servidor, y la agenda sigue disponible
  (RF-10, RF-21, RF-22). Exige la tarea **T3b** (conservar el cuerpo del error).
- **P4.** Otros 401/403 (HC no habilitada, médico no vinculado, sin permiso, sesión): **decide la copia local**.
  Opción (a). RF-9.
- **P5.** Al recargar (`restaurar`): **esperar** la consulta antes de mostrar la app, con tope de 5 s (10 s al iniciar
  sesión). Opción (a). RF-15.
- **P6.** El aviso por vencer **hace su propio pedido**: se deja así. Opción (a). Fuera de alcance.

No queda ninguna pregunta abierta.

### Detalle de las preguntas (opciones que se consideraron; la elegida está en «Decididas»)
**P1. Sin respuesta y sin copia local. DECIDIDO: (a).**
- (a) **Entra** *(recomendada)*: hoy un médico sin licencia guardada entra (`LicenciaTest`); sin señal no hay con qué
  decidir; el servidor lo frena en cuanto haya conexión (toda lectura de HC responde 403). Respeta la constitución #2.
- (b) No entra: deja afuera a quien está sin señal en su primer ingreso en el dispositivo.
- (c) Entra pero con un cartel: texto nuevo.

**P2. Antigüedad máxima de la copia sin señal. DECIDIDO: (a).** El servidor **no** tiene nada equivalente: sus 24 h (`gracia_horas`)
son para cuando *turnos* no le contesta a *pediatría*, no para la licencia, y en ese caso ni siquiera mira la licencia.
- (a) **Sin límite** *(recomendada)*: igual que hoy; una licencia vencida *por fecha* igual se bloquea sin señal,
  porque la fecha de la copia ya pasó; lo único que una copia vieja no ve es una baja o una renovación posterior,
  que se corrigen al volver la señal.
- (b) Límite de 24 h (como la gracia): un médico sin señal un fin de semana quedaría afuera; choca con la constitución #2.
- (c) Límite largo (p. ej. 7 días) con aviso: pide guardar «última consulta» (columna o clave nueva) y un texto nuevo.

**P3. Médico sin ninguna fila de licencia en pediatría. DECIDIDO: (b).** El servidor lo rechaza con `licencia_vencida` (sin fecha); la
app de hoy lo deja entrar (sin licencia guardada no bloquea). Con el servidor ya arriba, toda llamada de HC de ese
médico da 403 igual.
- (a) **Bloquear toda la app** *(recomendada, es lo que dice el servidor)*, **pero solo después de confirmar** que todo
  médico con pediatría habilitada tiene su fila (el documento de estado y `MEMORY.md` dejan pendiente revisar las
  fechas reales y hay médicos sin alta): si no, al publicar queda afuera, también de la agenda de turnos, gente que
  hoy entra.
- (b) Dejarlo entrar y que solo falle la HC: no ensancha lo que hoy hace la app, pero deja de ser «el servidor decide»;
  además exige una tarea más (que el error conserve el cuerpo de la respuesta para distinguir «sin fila» de «vencida»).

**P4. Otros 401/403. DECIDIDO: (a).** (`hc_no_habilitada`, `medico_no_vinculado`, `sin_permiso`, `token`).
- (a) **No concluyente: decide la copia local** *(recomendada)*: lo que la app hace hoy; el servidor no opinó sobre la
  licencia; esos médicos en la práctica no tienen copia (el panel solo trae vinculados).
- (b) No concluyente y no bloquear nunca: ignora la copia incluso si dice «vencida».
- (c) Bloquear el ingreso: ensancha el alcance (la app hoy deja entrar a la agenda a un médico no vinculado).

**P5. Orden y topes en la recarga. DECIDIDO: (a).**
- (a) **Esperar la consulta antes de mostrar la app, con tope de 5 s al restaurar y 10 s al iniciar sesión**
  *(recomendada)*: es la única forma de no mostrar contenido a quien el servidor rechaza, y en la red normal cuesta
  décimas de segundo (el servidor guarda 10 min la sesión validada).
- (b) Mostrar enseguida con la copia y corregir en segundo plano: abre y cierra pantallas, arranca la sincronización de
  alguien que después queda afuera, y si la copia dice «vencida» el error de hoy sigue.
- (c) Topes más cortos (3 s / 5 s): menos espera, más caídas a la copia en redes lentas.

**P6. El aviso por vencer. DECIDIDO: (a).** La pantalla principal ya pide `auth/perfil` una vez al entrar. Con esta spec el inicio de
sesión hace dos pedidos iguales (consulta y aviso).
- (a) **Dejarlo así en esta spec** *(recomendada)*: un pedido más por entrada, tolerable para el límite de 60/min, y
  no toca la pantalla principal (`composeApp`, sin tests).
- (b) Que el aviso salga de la misma respuesta: ahorra el pedido, pero cambia `MainShell` y el repositorio de HC.
