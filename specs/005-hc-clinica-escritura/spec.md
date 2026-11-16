# Spec 005 — La HC general escribe sus consultas en la base de la web (entrega E de la 002)

Estado: **aprobada por el usuario el 2026-11-04**. Decisiones D1 a D15 (2026-11-03) y D16 a D27 (2026-11-04); pasó dos
clarificaciones y la corrección final, y el usuario resolvió D17, D19 y D24 al aprobar: **no queda ninguna duda abierta**. El plan
y las tareas están en borrador (`plan.md`, `tasks.md`). Lleva una **enmienda a la spec 004** (D22), todavía no aplicada a la 004:
el plan la trata como tarea.
**Cambio del 2026-11-13 (E4):** el usuario revisó D24 y resolvió Q-E4-1 y Q-E4-3: la sección «Documentos (PDF e imágenes)» de la HC
general acepta **PDF, JPG, JPEG y PNG** (ya no es solo PDF) y el listado de los adjuntos depende de si la consulta es nueva (todos los
del paciente) o previa (solo los de esa consulta); las fotos de exámenes complementarios siguen **fuera de alcance** (RF-44, RF-45, D24).
**Decisión del 2026-11-11 (E2, Q-E2-3):** el tope de los textos de los estudios lo valida el **servidor**, no la app; si lo rechaza
por largo, la consulta queda «No se pudo enviar» con el motivo y el texto se conserva (RF-39).
Depende de la spec 002 (parte 1: pacientes y cartera; entrega L: lectura, implementada y validada en local, falta el reviewer
y producción), de la 001 (qué secciones ve cada médico lo decide su perfil), de la 003 (el patrón de una sección que escribe
contra la API de la web, el vínculo de pacientes y la pantalla de elegir ficha) y de la 004 (licencia que informa el servidor,
enmendada por D22; la spec 004 está en la rama principal y en `hc-general`, pero su código solo en la principal: hay que integrar
el código a `hc-general` —su tarea T9, que se pide aparte— antes de E1).
Esta spec **reemplaza** lo que la 002 dejaba como «Entrega E (escritura)» (RF-5 a RF-7, RF-15 a RF-24 y RF-26 a RF-34): la 002
ya lo dice y apunta acá.
Lo técnico (rutas, archivos, columnas, números de línea, límites del hosting, la verificación en el código) está en
`notas-para-el-plan.md`, para quien escriba el plan; esta spec dice QUÉ y POR QUÉ.

Leyenda de las marcas:
- **(Dn)** = decidido por el usuario; ver «Decididas».
- **[NECESITA ACLARACIÓN Dn]** = duda que sigue abierta; ver «Dudas abiertas».
- **[VERIFICADO]** = comprobado leyendo el código (detalle en las notas para el plan).
- **[NO VERIFICADO]** = hay que comprobarlo en la copia local o en producción antes de planificar o de implementar.

## Contexto y objetivo
La HC general (la web `hclinica`, con sus perfiles) ya se **lee** desde la app (entrega L): el médico ve las consultas que
cargó en la web, sin que la app escriba nada. Todo lo que el médico carga hoy en la app para la HC general queda **solo en
el dispositivo**. La web es la dueña del dato clínico; la base del dispositivo es caché y modo sin señal (constitución,
punto 2).

El objetivo es cerrar el ida y vuelta, como ya funciona en pediatría: **el médico crea, completa, cierra y envía una consulta
de la HC general desde la app, y la ve igual en la web (y al revés)**, con o sin señal, sin pisar nunca un dato con vacíos que
el médico no puso (constitución, punto 3) y sin mover lo que el médico tiene abierto en la web.

Es la entrega más riesgosa de la 002: escribe en una base con datos reales de pacientes que otros médicos usan en el mismo
momento. Por eso se parte en subentregas, cada una probada en la copia local, y **nada de esta spec se escribe en producción
sin respaldo restaurable probado y aprobación aparte** (002, decisión D3; D4 de esta spec).

## Usuarios
- **Médico de la HC general**, con o sin agenda, con cualquier perfil (clínica general, hepatología, enfermedades crónicas…).
- **Administrador**: en la subentrega E5 (aviso y panel de licencias de la HC general).
- Las secretarias quedan fuera, como en la 002.

## Historias de usuario
- HU-1. Como médico, quiero crear una consulta en la app, completarla y cerrarla para verla en la web sin cargarla dos veces.
- HU-2. Como médico con perfil de hepatología, quiero cargar serologías, Child-Pugh, estudios y laboratorios desde la app.
- HU-3. Como médico, quiero seguir cargando sin señal y que se envíe solo, una vez, cuando vuelva la conexión.
- HU-4. Como médico, quiero saber qué ya llegó a la web, qué está esperando y qué rechazó la web (y por qué), para no creer
  que algo se guardó cuando no.
- HU-5. Como médico, quiero que mi historial viejo del dispositivo no salga solo hacia la web y poder enviarlo consulta por
  consulta cuando yo lo decida.
- HU-6. Como administrador, quiero ver y cambiar la licencia de la HC general de cada médico, aparte de la de pediatría (E5).

## Definiciones
- **Consulta propia de la app**: consulta de la HC general creada con la app **después** de habilitado el envío (o una del
  histórico que el médico decidió enviar, RF-63). Es la única que la app puede editar, cerrar y anular (D1). Cuando llega a la
  web queda identificada allá.
- **Consulta de la web**: la que se cargó en la web (definición de la 002, entrega L). La app la muestra solo para leer.
- **Histórico del dispositivo**: consultas y datos de la HC general que **existen en el dispositivo al migrar** a la versión que
  habilita el envío y nunca se enviaron, incluidos los que eran de «hepatología» y la migración pasó a la HC general, y los
  antecedentes que esa migración dejó desactivados. Lo creado después queda como propio (D26, RF-8).
- **Dato por paciente**: lo que la web guarda por paciente y no por consulta: antecedentes, estudios, exámenes complementarios,
  interconsultas, laboratorio, peso y pendientes (la web los guarda con la consulta en que se cargaron, pero los muestra como
  historial del paciente).
- **Pendiente de confirmar (en la web)**: filas provisorias que la web guarda mientras la consulta está abierta —**solo**
  exámenes complementarios e interconsultas— y confirma al cerrarla (D20). El laboratorio general, el reducido y el peso
  **no**: la web los muestra apenas se cargan.
- **Envío**: un ciclo de mandar a la web lo pendiente de una consulta.
- **Enviado / pendiente de envío / en espera / rechazado**: un dato que la web confirmó / que la web todavía no tiene / que no
  se puede enviar por una causa ajena al dato que se resuelve sola o a mano (RF-6) / que la web rechazó por una causa del dato
  (RF-30).
- **Vaciado a propósito / nunca cargado / tocado**: «vaciado a propósito» es un campo que tenía texto y el médico lo borró con
  una acción explícita; «nunca cargado», uno que el médico no tocó. **Tocado** es lo que el médico cambió después de la última
  vez que ese dato se envió o se trajo de la web. Un vaciado a propósito se envía vacío (RF-69); un vacío que el médico no
  tocó, nunca.
- **Borrar un examen complementario o una interconsulta**: quitar esa fila con una acción explícita del médico; no es
  editarla hasta dejarla vacía (RF-70).
- **Envío habilitado por sección**: una sección cuyo envío a la web está construido y probado en la versión instalada.

### Textos de aviso
Cada texto tiene un solo significado. La etiqueta «Solo en este dispositivo (no se envía a la web)» que la 002 puso en la Q14
**deja de ser cierta** para lo que sí se envía: se reemplaza por los textos de esta tabla.

| Texto | Significado único | RF |
|---|---|---|
| «Solo en este dispositivo» | Consulta del histórico que todavía no se envió (se puede enviar con el botón si cumple RF-67) | RF-8, RF-10 |
| «No se envía a la web» | Campo o sección que la web no guarda; se conserva solo para leer | RF-3 |
| «Descartado: no se envió a la web» | Dato que la web rechazó y el médico decidió no enviar; queda solo en el dispositivo | RF-30 |
| «Esta sección todavía no se envía a la web» | Sección que la web guarda pero cuyo envío aún no está habilitado en esta versión | RF-4 |
| «Falta enviar» | La consulta propia tiene algo pendiente de envío (incluido el cierre) | RF-24 |
| «Enviando el cierre…» | El cierre de la consulta está viajando; no se puede reabrir hasta saber el resultado | RF-55 |
| «En espera: <motivo>» | No se puede enviar por una causa ajena al dato; se reintenta sola | RF-6 |
| «No se pudo enviar: <motivo>» | La web rechazó el dato; hace falta una acción del médico | RF-30 |
| «Consulta en curso» | Consulta propia abierta del médico, editable desde la app (enviada o no) | RF-62 |
| «Abierta en la web» | Consulta abierta cargada en la web, o propia que esta app no puede editar; solo para leer | RF-2, RF-65 |
| «Ya no figura en la web» | Lo que la web dejó de mandar y la app conserva (002 RF-63) | RF-33 |
| «<Nombre> tiene una consulta abierta de este paciente en la web» | Aviso informativo antes o después de crear una consulta | RF-18, RF-59 |

## Subentregas
Las subentregas son de **desarrollo y validación local**; cada una se aprueba antes de pasar a la siguiente (D5). **La escritura
se publica recién con E1 a E3 cerradas** (D23); E4 y E5 pueden ir después. Publicar siempre es decisión aparte del usuario.

| Sub | Contenido | RF | Migración local | Qué necesita del servidor de la web |
|---|---|---|---|---|
| **E0** | Precondiciones: limpieza de la deuda de pruebas, respaldo restaurable verificado, médico y paciente de prueba (D4) | — | no | no |
| **E1** | Crear la consulta; textos; examen físico; cerrar, anular; el envío (diferido, sin conexión, sin duplicar, rechazo); histórico del dispositivo; candado por sección; la licencia de la HC general en el ingreso y el panel de licencias sin regresión (D22) | RF-1 a RF-34, RF-50 a RF-69 | **sí, chica: marca de histórico/propio (D26)** | crear, secciones, examen, cerrar, baja; registro de lo recibido (D21) |
| **E2** | Formularios y listas sin migración: alimentación, antecedentes familiares, serologías, Child-Pugh, los 5 estudios, exámenes complementarios, interconsultas | RF-35 a RF-40, RF-70 | no | formularios y listas; baja de exámenes e interconsultas (capacidad nueva) |
| **E3** | Laboratorio general y reducido, peso y pendientes | RF-41 a RF-43 | **sí: identificador de la web en laboratorio y pendiente (D8), distinta de la de E1** | laboratorio, peso, pendientes |
| **E4** | Adjuntos: los documentos de la HC general en **PDF, JPG, JPEG y PNG**, listados según la consulta (nueva: todos los del paciente; previa: solo los de esa consulta); las fotos de exámenes complementarios quedan **fuera de alcance por ahora** (D24 revisada); es lo primero que se recorta (D12) | RF-44 a RF-46 | no | subir, bajar, borrar, límites del hosting; listar por paciente y por consulta |
| **E5** | Aviso de vencimiento y panel de licencias de la HC general (D11); el ingreso ya está en E1 (D22) | RF-47 a RF-49 | no (D11) | administración de licencias |

Orden: E0 → E1 → E2 → E3 → E4, con E5 aparte (D5). Por qué esta partición:
1. **El cierre va en E1**, no al final: sin cerrar, la consulta queda abierta en la web (ya hay muchas abandonadas) y las filas
   «pendientes de confirmar» no aparecen allá.
2. **Exámenes e interconsultas van en E2**, no con el laboratorio: ya guardan el identificador de la web, así que no necesitan
   esquema nuevo. Todo lo que necesita la segunda migración queda junto en E3 y se puede frenar ahí sin trabar nada más.
3. **Adjuntos aparte (E4)** porque es lo más frágil (límites del hosting, carpetas creadas a mano en pediatría, archivos grandes,
   PDF o imágenes).
4. **La licencia en el ingreso va en E1 (D22)**: la escritura necesita que el médico sin licencia vigente no entre; el aviso y el
   panel (E5) no tocan consultas. Entre E1 y E5, una licencia de clínica se renueva desde la web de hclinica, como hoy (RF-66).
5. **Candado por sección (RF-4):** mientras una sección no tenga su envío, la consulta nueva no la ofrece para cargar. Así
   ninguna versión deja al médico cargando algo que cree que se envía y no sale. (Con D23 las versiones con E1 y E2 solamente
   no se publican; el candado protege las compilaciones intermedias y a E4, cuyos adjuntos pueden salir después.)

## Requisitos funcionales

### Qué puede escribir la app (candados) — E1
- RF-1: EL SISTEMA envía a la web solo lo que pertenece a una **consulta propia de la app** y lo que cuelga de ella (levanta el
  RF-47 de la 002 únicamente para esto). Continuar las consultas abiertas cargadas en la web no se hace (D1).
- RF-2: MIENTRAS una consulta sea de la web, EL SISTEMA la muestra solo para leer —sin editar, cerrar, reabrir ni anular— aunque
  la haya cargado el mismo médico, y no envía nada sobre ella ni sobre sus filas (002 RF-45, RF-62; D1).
- RF-3: EL SISTEMA no ofrece cargar, en una consulta que se envía a la web, lo que la web no guarda: la casilla de tabaquismo de
  los antecedentes familiares, la frecuencia cardíaca y la circunferencia abdominal del examen físico, los diagnósticos, el audio
  y la sección «antecedentes personales» del catálogo viejo (D9); lo que ya está cargado en el dispositivo se conserva, a la
  vista, solo para leer y con el aviso «No se envía a la web». El resto de los antecedentes familiares y la nota que los
  acompaña, aunque algún perfil la rotule «antecedentes personales», **sí** viajan (RF-36).
- RF-4: MIENTRAS el envío de una sección no esté habilitado en la versión instalada, EL SISTEMA no la ofrece para cargar en una
  consulta nueva de la HC general y dice «Esta sección todavía no se envía a la web»; lo que ya tenía cargado en el dispositivo
  no se envía ni se marca como enviado.
- RF-5: EL SISTEMA no cambia el comportamiento de pediatría ni de ninguna otra historia clínica (002 RF-60; 001 RF-33).
- RF-6: SI el envío no puede hacerse por una causa ajena al dato —la HC general no está habilitada para el médico, el médico no
  está vinculado en la web, la licencia está vencida o no existe, el paciente no está en su cartera, la sesión venció (también
  a mitad de un envío), no hay conexión, o la web no responde (incluidos «demasiados pedidos» y «turnos caído»)—, ENTONCES EL
  SISTEMA conserva todo en el dispositivo como «En espera: <motivo>», con el motivo visible en palabras del usuario **nombrando
  la HC** (los motivos de la cartera, 002 RF-9), no pierde ni modifica ningún dato, lo vuelve a intentar solo cuando la app
  comprueba de nuevo la causa (RF-29, RF-57) y a mano si el médico lo pide. «En espera» no es un rechazo y no usa «No se pudo
  enviar» (RF-30).

### Sesión y médico — E1
- RF-50: EL SISTEMA envía lo pendiente de un médico solo con la sesión de ese médico: si otro médico inicia sesión en el mismo
  dispositivo, lo pendiente del primero no se envía ni se modifica y sigue «En espera» hasta que vuelva su dueño. [NO VERIFICADO:
  qué ve el segundo médico de lo pendiente del primero; el plan lo comprueba.]
- RF-51: CUANDO el médico cierra sesión con datos sin enviar, EL SISTEMA se lo avisa (cuántas consultas) y le deja elegir entre
  quedarse e intentar enviar o cerrar igual; si cierra igual, nada se borra y nada se envía con otra sesión (RF-50).

### Licencia en el ingreso y en el panel — E1
- RF-58: CUANDO un médico con la HC general habilitada inicia sesión, restaura la sesión o refresca el perfil, EL SISTEMA consulta
  la licencia al servidor de la HC general y decide como la 004 para pediatría, enmendada por D22 y D25: se bloquea la app entera
  **solo si todas** las HC con licencia del médico contestan «vencida» con fecha; si una está vencida y otra vigente, sin fila de
  licencia o sin respuesta concluyente, el médico **entra** y la HC vencida queda frenada (el servidor sigue rechazando cada
  lectura y cada envío de esa HC, y lo pendiente de esa HC queda «En espera» con el motivo que nombra la HC, RF-6). Médico solo de
  clínica: vencida con fecha, no entra; sin fila, entra y la HC queda frenada (004, P3); vigente, entra; sin respuesta
  concluyente, entra, porque no hay copia local de clínica (D11). Una licencia que vence con la sesión abierta no bloquea la app:
  lo pendiente queda «En espera» (RF-6).
- RF-66: MIENTRAS la HC general pueda escribir (desde E1), EL SISTEMA no cambia el comportamiento del panel de licencias ni del
  guardado de licencias: un médico solo de clínica sigue guardándose sin error y sin llamar al servidor de la HC general. Hasta
  que se haga E5, una licencia de clínica vencida se renueva desde la web de hclinica, como hoy; el panel de la app solo administra
  la de pediatría.

### Lo viejo sin enviar (el histórico del dispositivo) — E1
- RF-7: EL SISTEMA no envía por su cuenta nada del histórico del dispositivo —ni consultas, secciones, examen, listas,
  laboratorios, pendientes o adjuntos, **ni datos por paciente**—: ni al abrir la app, ni al iniciar sesión, ni al volver la
  conexión, ni al reintentar, ni al abrir o cerrar una consulta nueva (el RF-47/RF-48 de la 002 sigue valiendo para esto) (D2).
- RF-8: EL SISTEMA distingue de forma **persistente** qué consultas son del histórico y cuáles son propias, y lo muestra a la
  vista (el histórico, «Solo en este dispositivo»): lo que existe en el dispositivo al migrar queda como histórico y lo creado
  después, como propio (D26). La distinción sobrevive a cerrar la app, a actualizar la versión y a la migración, y no depende de
  la hora del dispositivo ni de la memoria de la sesión: una consulta del histórico que el médico edita sigue siendo histórico.
- RF-9: EL SISTEMA no envía nunca un antecedente desactivado (los que dejó la migración de hepatología), ni lo cuenta como
  pendiente, ni de una consulta nueva ni del histórico (D2).
- RF-10: CUANDO el médico toca «Enviar a la web» en una consulta del histórico **que él cargó**, EL SISTEMA le muestra paciente,
  fecha, estado (abierta o cerrada), las secciones que se enviarán, **lo que no viajará** (lo que la web no guarda, RF-3, y los
  datos por paciente sin consulta asociada) y, si el paciente todavía no tiene ficha vinculada en la web, que se la va a buscar o
  crear; le pide confirmación y recién entonces la envía. Hasta entonces sigue «Solo en este dispositivo». No hay envío en lote ni
  se la puede marcar como resuelta sin enviarla (D2). El histórico solo lo envía su dueño: desde la sesión de otro médico en el
  mismo dispositivo el botón no está disponible (RF-50).
- RF-63: CUANDO el médico envía una consulta abierta del histórico, EL SISTEMA la envía abierta, con el estado que tiene, y desde
  ese momento es una consulta propia, editable y cerrable desde la app (RF-62); la confirmación de RF-10 se lo dice.
- RF-67: SI la consulta del histórico tiene datos en una sección cuyo envío todavía no está habilitado en la versión instalada,
  ENTONCES «Enviar a la web» no está disponible para esa consulta: el médico ve la lista de esas secciones y el motivo, la
  consulta sigue «Solo en este dispositivo» y **no cuenta como pendiente de envío**. Nunca se envía una parte y se la da por
  enviada. «Con datos» significa alguna casilla, campo, texto o fila con valor, sin contar filas dadas de baja ni antecedentes
  desactivados, e incluyendo los datos por paciente asociados a esa consulta. Hasta que estén E3 y E4, el histórico que tenga
  laboratorio, peso, pendientes o adjuntos (casi todo el ex-hepatología) no podrá enviarse; es esperado (D16).
- RF-68: EL SISTEMA envía un dato por paciente solo si está asociado a una consulta propia; los asociados a una consulta del
  histórico, o a ninguna, no viajan por estar en el mismo paciente. SI el médico edita una casilla o fila del histórico dentro de una consulta
  propia, ENTONCES esa casilla o fila pasa a asociarse a la consulta propia y viaja **solo lo que tocó**.

### Crear la consulta — E1
- RF-11: CUANDO el médico abre una consulta nueva de un tipo (consulta o telemedicina) y ya tiene en el dispositivo una consulta
  propia abierta del mismo paciente y tipo, EL SISTEMA reutiliza esa; nunca devuelve, continúa ni reutiliza una consulta de la
  web ni una del histórico (002 RF-15 ajustado; RF-46 de la L).
- RF-12: EL SISTEMA guarda la consulta primero en el dispositivo y permite cargarla sin conexión; la consulta se crea en la web
  en el envío diferido, nunca al abrir la pantalla.
- RF-13: CUANDO hay que enviar la primera consulta de un paciente que todavía no tiene ficha vinculada en la web, EL SISTEMA
  resuelve el vínculo antes de enviar (002 RF-10 a RF-13 y RF-36); SI hay que elegir ficha, el documento no sirve o la ficha
  es de otro paciente de turnos, ENTONCES la consulta queda «En espera» con ese motivo, sin enviarse, y el médico lo resuelve
  desde esa consulta con la pantalla de elegir ficha de la 003 (D15).
- RF-14: CUANDO la consulta se crea en la web, EL SISTEMA la deja a nombre del médico de la sesión (nunca de otro), con el tipo,
  la **fecha clínica y la edad que cargó el médico** (no la fecha del envío) y abierta.
- RF-15: SI un envío se repite —corte a mitad, respuesta que no llegó, reintento, cierre y reapertura de la app o reenvío
  manual—, ENTONCES EL SISTEMA no duplica nada en la web: ni la consulta, ni sus cargas de laboratorio y peso, ni sus listas, ni
  sus pendientes, ni sus adjuntos. El servidor recuerda de forma permanente lo que ya recibió de cada dispositivo, sin tocar
  ningún dato de la web, y responde lo mismo sin escribir de nuevo (D21). **Riesgo declarado:** si ese registro se perdiera, el
  servidor trataría como nuevo lo que ya no reconoce y un reenvío podría duplicar; por eso no se borra nunca salvo limpieza de
  filas de prueba por identificador, y se respalda junto con la base.
- RF-16: EL SISTEMA no cambia lo que la web usa para saber qué paciente y qué consulta está mirando el médico en su pantalla
  (nunca mueve la pantalla del médico mientras atiende, tampoco al cargar laboratorio o peso).
- RF-17: CUANDO una consulta creada por la app se abre en la web con un médico de cada perfil de la web (1, 2, 3, 4 y 9), EL
  SISTEMA la deja en un estado que la web abre sin errores, con las secciones de ese perfil vacías y editables. [NO VERIFICADO: la
  web crea filas vacías al abrir una consulta de hepatología; hay que probar si las necesita. Se verifica a mano en la copia
  local.]
- RF-18: SI el paciente ya tiene una consulta abierta en la web (cargada allá, de cualquier médico) y el dato es fresco,
  ENTONCES EL SISTEMA se lo avisa al médico antes de crear la nueva, con el nombre de quien la abrió, y la crea igual sin
  reutilizarla ni tocarla (D6). «Dato fresco» es la lista de consultas de ese paciente leída hace menos de un minuto (la misma
  espera de la lectura, 002 RF-57). No avisa por una consulta propia abierta del médico de otro tipo (esa se ve como «Consulta en
  curso»). Sin dato fresco o sin conexión no hay aviso previo y tampoco se bloquea nada: la consulta se crea igual y el aviso sale
  al enviarla (RF-59).
- RF-59: CUANDO se envía la creación de una consulta y la web informa que el paciente tenía otra abierta (de cualquier médico),
  EL SISTEMA muestra el aviso informativo con el nombre de quien la abrió; no rechaza ni bloquea nada.
- RF-19: SI el paciente de la consulta fue dado de baja en la web o no existe, ENTONCES EL SISTEMA conserva la consulta en el
  dispositivo «En espera» con el motivo y no crea ninguna ficha nueva por su cuenta. **Precedencia cuando coinciden varias causas:**
  primero el paciente dado de baja o inexistente (RF-19, «En espera», porque una ficha se puede reactivar); después la consulta
  dada de baja o inexistente en la web (si ya estaba enviada: «Ya no figura en la web», RF-33, y lo pendiente queda «No se pudo
  enviar», RF-30, sin recrearla); después cualquier otro rechazo (RF-30).
- RF-62: MIENTRAS una consulta propia ya enviada esté abierta, EL SISTEMA la sigue tratando como propia —editable, cerrable,
  anulable— aunque ya esté identificada en la web, y la muestra como «Consulta en curso», no entre las «abiertas en la web»; una
  vez cerrada y enviado el cierre, pasa a las consultas anteriores, solo para leer (RF-25).
- RF-64: SI el mismo médico usa la app en dos dispositivos, ENTONCES cada dispositivo crea y envía sus propias consultas; el otro
  dispositivo las ve como consultas de la web, solo para leer; puede haber dos abiertas del mismo paciente, igual que con D6, y no
  hay edición desde dos dispositivos.
- RF-65: SI una consulta propia sigue abierta en la web y no se puede editar desde esta app (la creó otro dispositivo, o se
  reinstaló la app), ENTONCES EL SISTEMA la muestra como «Abierta en la web» y el médico la cierra desde la web, como cualquier
  consulta abierta.

### Textos y examen físico — E1
- RF-20: CUANDO el médico escribe en una sección de texto (motivo, datos subjetivos, datos objetivos, conductas, nota, hábitos,
  diuresis y catarsis, somnia), EL SISTEMA lo guarda y la web lo muestra en la misma consulta; el motivo lleva la fecha de la
  consulta (002 RF-16).
- RF-21: CUANDO el médico carga el examen físico, EL SISTEMA envía peso, talla, IMC, tensión arterial y nota, y la web los
  muestra (002 RF-17). Un valor «0.00» de peso, talla, tensión o IMC se entiende como vacío; «0» y «0.0» son datos (regla de
  la lectura).
- RF-69: CUANDO el médico vacía a propósito un campo que tenía texto (acción explícita), EL SISTEMA lo envía vacío: es una
  decisión del médico y no un vacío que pisa un dato (constitución, punto 3). EL SISTEMA nunca envía un vacío que el médico no
  tocó (D27).

### Cierre y anulación — E1
- RF-22: CUANDO el médico cierra una consulta propia, EL SISTEMA la marca cerrada en la web con la fecha y la edad que cargó y,
  **solo en ese momento**, confirma lo que estaba pendiente de confirmar de esa consulta, que son únicamente exámenes
  complementarios e interconsultas (002 RF-24 y RF-22 ajustados; D20). El laboratorio y el peso no cambian con el cierre.
- RF-23: EL SISTEMA, al cerrar, confirma solamente los exámenes complementarios y las interconsultas pendientes de confirmar: no
  vuelve a activar los que el médico borró (RF-70; la web hoy reactiva todas las filas de la consulta; la app no copia ese
  defecto) (D14, D27). Con D20 el cierre ya no toca el laboratorio ni el peso.
- RF-24: SI el cierre no se puede enviar todavía, ENTONCES EL SISTEMA muestra la consulta cerrada en la app con la marca «Falta
  enviar» (la web la sigue viendo abierta); al enviarse, la marca desaparece.
- RF-25: EL SISTEMA permite reabrir una consulta propia cerrada solo mientras el cierre no se haya enviado a la web y no esté
  viajando; una vez enviado el cierre, la consulta queda cerrada y solo para leer (D13; la web no tiene reapertura y el servidor
  no la inventa; D19(a)).
- RF-26: CUANDO el médico anula una consulta propia que todavía no se creó en la web, EL SISTEMA no envía nada (queda anulada solo
  en el dispositivo). CUANDO ya se creó y sigue abierta, EL SISTEMA descarta lo que quedaba pendiente de enviar de esa consulta,
  termina antes el envío que estuviera en curso y la da de baja en la web (nunca la borra), de modo que deja de verse como abierta
  allá (D14). **Una consulta cerrada con el cierre ya enviado no se puede anular** (queda solo para leer, RF-25, y el servidor no
  acepta escrituras sobre ella, RF-53); si hubo un error, se corrige con una consulta nueva o desde la web. Los datos por paciente
  ya enviados (laboratorio, peso, estudios, exámenes, interconsultas, antecedentes) **no** se dan de baja en cascada: pertenecen al
  historial del paciente, y la confirmación de anular lo avisa. [Pasa al plan: cómo los trata la web y la lectura cuando su
  consulta está dada de baja.]

### Envío: autoguardado, ritmo, sin conexión, rechazo y no duplicar — E1 (vale para todas las subentregas)
- RF-27: EL SISTEMA guarda en el dispositivo cada cambio sin esperar a la web y sin hacer ningún pedido a la web mientras el
  médico escribe (el autoguardado dispara cada 600 ms).
- RF-28: CUANDO una consulta tiene cambios pendientes, EL SISTEMA espera **4 segundos sin cambios nuevos** en esa consulta (la
  espera del envío diferido que ya usa pediatría) y entonces los envía juntos, agrupados por tipo de dato. Es verificable con un
  reloj falso: cambios cada 600 ms durante 30 segundos no producen ningún envío durante la escritura y producen uno solo a los 4
  segundos del último cambio. El número de pedidos por envío lo fija y justifica el plan dentro de los límites que impone la web.
- RF-29: MIENTRAS no haya conexión, EL SISTEMA permite crear y editar consultas de la HC general y las envía cuando vuelve la
  conexión, sin duplicarlas (002 RF-33). Lo pendiente se reintenta a los 15 segundos, al minuto y a los 5 minutos (las esperas del
  envío diferido que ya existen). **Después no se rinde:** MIENTRAS la app esté abierta y haya algo pendiente o «En espera»
  (por ejemplo, con el servidor de la web caído un rato), EL SISTEMA sigue reintentando de forma periódica, no solo ante eventos,
  con una espera acotada que fija el plan sin ráfagas; los disparadores de RF-57 se suman a esos reintentos. Nada se pierde por no
  haber llegado.
- RF-30: SI la web rechaza un cambio por una causa del dato que no se arregla reintentando (datos que no acepta, consulta o
  paciente que ya no existe, consulta cerrada o dada de baja), ENTONCES EL SISTEMA lo conserva en el dispositivo marcado «No se
  pudo enviar», muestra el motivo en palabras del usuario (002 RF-34), no lo reintenta solo, deja reintentarlo a mano o
  descartarlo (queda solo en el dispositivo con el texto «Descartado: no se envió a la web»; D19(d)) y no frena el envío de las
  demás consultas. Las causas ajenas al dato (RF-6) no son un rechazo. «Demasiados pedidos» y los errores del servidor se
  reintentan con espera, sin ráfaga.
- RF-31: EL SISTEMA muestra cuántas consultas de la HC general tienen cambios sin enviar, contando todo tipo de dato (también
  laboratorio y pendientes) y sin contar el histórico (RF-67), y cuáles están «En espera» o «No se pudo enviar».
- RF-32: CUANDO un envío es parcial (llegó una parte y otra no), EL SISTEMA marca como enviado solo lo que la web confirmó y deja
  el resto pendiente; nunca marca como enviado lo que la web no confirmó.
- RF-33: CUANDO la consulta propia ya está en la web, EL SISTEMA la actualiza con lo que se cambie allá en lo que el
  dispositivo **no** tenga pendiente de envío, la muestra una sola vez (sin duplicar con la lista de la web) y empareja sus
  filas con las de la web (estudios, exámenes, interconsultas, laboratorio, pendientes) por su identificador de la web, sin
  duplicarlas. SI la web la dio de baja, ENTONCES la app no la vuelve a crear: la marca «Ya no figura en la web» (002 RF-63; orden
  de precedencia en RF-19).
- RF-34: SI la web y la app cambiaron el mismo campo, ENTONCES vale la última escritura **por campo**, entendida como la que llega
  última al servidor (su hora de recepción, no la hora del dispositivo). Lo pendiente del dispositivo pisa solo los campos que el
  médico tocó; un campo que no tocó nunca se envía, así que no pisa el cambio de la web. Ejemplo: la web cambia un campo a las
  10:00, el médico lo había cambiado en el dispositivo a las 9:55 y el envío llega a las 10:05: queda el valor del dispositivo.
  No hay pantalla de conflictos (D7).
- RF-54: EL SISTEMA no ejecuta dos envíos de la misma consulta a la vez: si se dispara otro mientras uno está en curso, espera a
  que termine y recién entonces envía lo que quedó pendiente.
- RF-55: CUANDO el médico cambia algo mientras viaja un envío, EL SISTEMA no lo da por enviado cuando llega la confirmación: solo
  marca como enviado lo que viajó, y lo nuevo sale en el envío siguiente. Cerrar con un envío en curso deja el cierre pendiente
  hasta que ese envío termine y se cumpla RF-56. Mientras el cierre viaja no se puede reabrir; el médico ve «Enviando el
  cierre…» y decide con el resultado (RF-25).
- RF-56: EL SISTEMA envía una consulta en este orden: primero la creación, después todo el contenido (secciones, examen,
  formularios, listas, cargas, pendientes, adjuntos) y **al final el cierre**; el cierre solo sale cuando todo lo pendiente de esa
  consulta llegó. SI una parte es rechazada (RF-30), ENTONCES la consulta no se cierra en la web: queda abierta allá con «Falta
  enviar» hasta que el médico corrija y reenvíe lo rechazado o decida descartarlo; recién entonces sale el cierre. SI una parte
  queda «En espera» (RF-6), el cierre también espera. (La parte del descarte es una propuesta a validar: D19(d).)
- RF-57: EL SISTEMA dispara el envío al recuperar la conexión, al abrir la app y al iniciar sesión, además de los reintentos
  periódicos (RF-29) y del pedido manual. **Esos tres disparadores no existen hoy: son trabajo nuevo en cada plataforma que
  soporta la app.** Todos pasan por la misma espera de 4 segundos del envío diferido y nunca por el ritmo del autoguardado (600
  ms), y ninguno corre en paralelo con un envío en curso (RF-54).
- RF-60: EL SISTEMA no deja como pendiente de envío —ni lo cuenta— lo que nunca va a viajar (lo que la web no guarda, RF-3; los
  antecedentes desactivados, RF-9; las secciones del histórico), y tampoco lo muestra como enviado: se ve «No se envía a la web»
  o «Solo en este dispositivo», según el caso.
- RF-61: CUANDO la web confirma un envío, EL SISTEMA registra la confirmación y el vínculo de lo enviado con lo que quedó en la web
  de modo que un corte en cualquier momento no deje lo enviado como pendiente ni lo duplique al reintentar.

### El servidor de la web — E1 (vale para todas las subentregas)
- RF-52: EL SISTEMA (el servidor) acepta escrituras de consultas solo de un médico, solo sobre consultas que él mismo creó a pedido
  de la app para ese médico, y solo de pacientes de su cartera; a un administrador, a una secretaria, a otro médico o sobre una
  consulta cargada en la web responde que no corresponde, sin escribir (equivalente del RF-51 de la 002). D1 no es solo una regla
  de la pantalla.
- RF-53: EL SISTEMA (el servidor) no acepta escrituras nuevas sobre una consulta cerrada o dada de baja; repetir una escritura que
  ya recibió responde lo mismo que la primera vez, sin escribir de nuevo.

### Formularios y listas — E2
- RF-35: CUANDO el médico carga alimentación (hiposódica, diabetes, celíaco, otra, cada una con detalle), EL SISTEMA la guarda en
  la web (002 RF-19); una casilla que el médico no tocó no se envía.
- RF-36: CUANDO el médico marca antecedentes familiares (con detalle) o escribe la nota que los acompaña, EL SISTEMA los guarda en
  la web donde la web los lee y muestra los últimos del paciente aunque vengan de otra consulta (002 RF-18); lo que el médico no
  tocó conserva el valor vigente en la web. Solo viajan los activos (RF-9) y no viaja la casilla de tabaquismo, que la web no
  guarda (RF-3).
- RF-37: CUANDO el médico carga serologías (16 marcadores, cada uno SI / NO / sin cargar), EL SISTEMA las guarda en la web; un
  marcador sin cargar queda «sin cargar» allá y nunca «NO» (002 RF-26).
- RF-38: CUANDO el médico carga Child-Pugh (5 parámetros), EL SISTEMA guarda en la web los puntos de cada uno y el total, y la
  clase se muestra como A (hasta 6), B (7 a 9) o C (10 o más) (002 RF-27).
- RF-39: CUANDO el médico carga ecografía abdominal, videoendoscopía digestiva alta, biopsia hepática, TC/RMN o estudios
  cardiológicos (fecha y descripción), EL SISTEMA los guarda y los ve en el historial del paciente, cada uno con la consulta
  donde se cargó (002 RF-28). El tope del texto lo valida **el servidor**, no la app: en la copia local la descripción de
  `estudios_cardiologicos` es `varchar(191)` y las demás son `longtext` (65535 bytes). La app **no** corta el texto ni avisa
  antes de enviarlo. SI el servidor rechaza el dato por largo (responde `422 texto_demasiado_largo`), ENTONCES la consulta
  queda «No se pudo enviar» con el motivo (RF-30) y el texto se conserva completo, tal como el médico lo escribió: nunca se
  corta en silencio ni se pierde. **(Decisión del usuario del 2026-11-11, Q-E2-3:** no se aplica el tope en la app.)**
- RF-40: CUANDO el médico pide un examen complementario o una interconsulta, EL SISTEMA los guarda en la web como pendientes de
  confirmar y los confirma al cerrar la consulta (RF-22); son acumulativos del paciente, y uno cargado en una consulta anterior
  se puede completar (resultado) pero no quitar desde otra consulta (002 RF-22).
- RF-70: CUANDO el médico borra, en la consulta propia donde lo cargó y mientras esté abierta, un examen complementario o una
  interconsulta ya enviado, EL SISTEMA lo da de baja en la web y nunca lo borra físicamente (como D14); si todavía no se había
  enviado, no se envía nada. Hoy la web no tiene una función para dar de baja exámenes ni interconsultas: es una **capacidad nueva
  del servidor** (D27).

### Laboratorio, peso y pendientes — E3
- RF-41: CUANDO el médico carga laboratorio (general o reducido) con una fecha, EL SISTEMA agrega esa carga a la tabla del
  paciente, **visible en la web apenas llega**, como cuando se carga desde la web (D20; no queda pendiente de confirmar). CUANDO
  borra una carga, EL SISTEMA la da de baja en la web (nunca la borra) y deja de verse en ambas. SI la carga no tiene fecha,
  ENTONCES usa la fecha de la consulta; nunca envía una fecha vacía ni la que la web usa para «sin fecha» (002 RF-20, RF-21). Dos
  cargas con la misma fecha conviven; editar una carga ya enviada la actualiza, no crea otra, y al volver a leer el historial la
  app reconoce cada carga que ella envió y no la duplica (D8).
- RF-42: CUANDO el médico carga peso con fecha, EL SISTEMA lo agrega a la tabla de peso del paciente con las mismas reglas que el
  laboratorio (002 RF-29; D20).
- RF-43: CUANDO el médico escribe los pendientes del paciente en una consulta propia, EL SISTEMA los envía como **un solo texto de
  esa consulta**: los pendientes escritos en la consulta, uno por línea y en el orden en que están en la app; cada envío
  reemplaza al anterior (el último texto manda, como en la web). La web identifica ese texto como una unidad, así que la app lo
  trata también como una unidad: al volver a leer, si el texto de la web es el que la app envió, **no crea ningún pendiente
  nuevo**; si la web lo cambió y el dispositivo no tiene cambios sin enviar, lo muestra como un solo pendiente «de la web», solo
  para leer, y conserva los pendientes propios (marcándolos «Ya no figura en la web» si ya no corresponden); si el dispositivo
  tiene cambios sin enviar, manda el dispositivo (RF-34). No se envía «Resuelto» (D10) y, siguiendo el criterio de RF-3, la app
  no lo ofrece sobre lo que se envía (D19(b)). Si el médico borra todos los pendientes a propósito, se envía el texto vacío
  (RF-69). Un pendiente pertenece a la HC de la consulta en que se cargó: los pendientes del histórico sin consulta asociada, o de
  otra HC del mismo paciente, no se envían a la HC general.

### Adjuntos — E4
- RF-44: CUANDO el médico adjunta un archivo a la sección «Documentos (PDF e imágenes)» de la consulta, EL SISTEMA acepta **PDF, JPG,
  JPEG y PNG** y nada más (D24 revisada); lo guarda en el dispositivo, lo sube a la web y la web lo muestra; sin señal queda en el
  dispositivo y se sube después, **una sola vez**: una subida cortada a la mitad no deja un adjunto a medias ni duplicado al
  reintentar (002 RF-30; D21). SI el contenido del archivo no es uno de esos cuatro tipos —aunque el nombre o la extensión digan otra
  cosa—, ENTONCES EL SISTEMA no lo acepta y lo dice: el tipo se valida por el **contenido** del archivo, no solo por la extensión. SI
  el archivo supera el límite —el menor entre el máximo que la web de hclinica ya acepta para sus adjuntos y el que acepta el
  hosting—, ENTONCES EL SISTEMA lo dice antes de subirlo y no lo da por enviado. El límite del hosting se mide en local; leerlo en
  producción solo se hace cuando el usuario apruebe la etapa de producción de la escritura, y sin escribir allá (D12, D18). Las
  **fotos de exámenes complementarios** no son parte de esta sección y quedan **fuera de alcance por ahora**: solo entrarían si se
  verifica que la web las muestra y el usuario lo pide (D24 revisada).
- RF-45: CUANDO el médico abre una consulta, EL SISTEMA lista los adjuntos de la sección «Documentos» de la HC general y permite
  abrirlos, bajándolos con la sesión del médico y nunca por un enlace público (002 RF-31); hoy la lectura solo avisa que existen. SI
  la consulta es **nueva y en curso**, ENTONCES lista **todos los documentos del paciente**, de todas sus consultas anteriores (los que
  la web ya tiene y los del dispositivo); SI la consulta es **previa** (cerrada, o abierta pero no la que se está cargando), ENTONCES
  lista **solo los documentos subidos en esa consulta** (Q-E4-3). En los dos casos, EL SISTEMA nunca lista los de otra historia clínica
  del mismo paciente ni los que no cuelgan de una consulta (RF-46).
- RF-46: CUANDO el médico borra un adjunto propio ya subido, EL SISTEMA lo da de baja en la web y deja de mostrarlo en ambas;
  EL SISTEMA nunca envía a la HC general adjuntos que pertenecen a otra historia clínica del mismo paciente ni adjuntos sin
  consulta asociada (002 RF-32; D12).

### Licencia: aviso y panel — E5
- RF-47: CUANDO la licencia del médico para la HC general está dentro del período de aviso, EL SISTEMA le avisa la fecha de
  vencimiento de esa historia clínica (002 RF-5; D11).
- RF-48: EL SISTEMA muestra y administra una licencia por médico **y por historia clínica**: cambiar la de la HC general no
  cambia la de pediatría (002 RF-7). La lee y la escribe cada vez en la base de la web, que es su dueña, y no guarda una copia
  por historia clínica (D11).
- RF-49: EL SISTEMA deja entrar siempre al administrador, sin licencia (002 RF-6). [VERIFICADO que la web ya lo hace.]

## Requisitos no funcionales
- La web es la dueña del dato clínico: lo que el médico tiene sin enviar manda en el dispositivo hasta enviarse; lo que la web
  trae no pisa lo sin enviar, y ningún dato se reemplaza por un vacío que el médico no puso ni se vacía por una respuesta con
  error (constitución, punto 3; el vaciado a propósito, RF-69, es una acción deliberada del médico).
- **Nada se borra en la web**: baja lógica o conservar (002 Q15, aplicado también al escribir). La app no borra físicamente.
- Atomicidad: lo que sale en un pedido entra completo o no entra; una respuesta cortada no deja una consulta a medias.
- Cada envío es repetible sin duplicar (RF-15, D21).
- Lo que la app hace no cambia lo que el médico tiene abierto en la web.
- El servidor traduce los nombres entre la app y la web con su mapa, al revés que en la lectura (D9; 002 Q12); la app no cambia
  sus identificadores.
- La escritura funciona en una base en **modo estricto** (la de producción lo es probablemente; la local no): la web tiene casi
  todas las columnas sin valor por defecto [VERIFICADO].
- **Sin vuelta atrás en un dispositivo migrado** (D8, D26): una app más vieja sobre una base más nueva descarta los datos del
  navegador. Las specs 001 y 002 se publican juntas, así que un dispositivo con la app publicada pasa a la versión nueva de una
  vez; el despliegue lo documenta y se recomienda enviar lo pendiente antes de actualizar.
- **Riesgo declarado, motor viejo de sincronización:** el motor que hoy pega a rutas de turnos que no existen no se toca en esta
  spec, pero con el histórico pendiente de envío (RF-7) agrava el riesgo ya documentado: si alguien crea esas rutas, saldría todo
  lo pendiente. El plan lo cierra excluyendo del motor viejo las tablas de HC que tienen envío propio, o apagándolo.
- **Riesgo declarado, registro de lo recibido:** ver RF-15.
- Funciona en web, Android e iOS.
- Ningún dato real de pacientes, ni tokens, ni nombres de personas en el repositorio, en los tests ni en las pruebas
  documentadas; las pruebas usan pacientes «PRUEBA SALUD360» creados por la propia prueba.
- Las pruebas de escritura corren solo contra la copia local (los E2E se saltean sin propiedades). Producción solo con
  aprobación aparte, respaldo probado y limpieza por id (D4).

## Casos límite
- Histórico del dispositivo con ex-hepatología: abrir la app, iniciar sesión o volver la conexión no envía nada (RF-7).
- Consulta del histórico con laboratorio, peso, pendientes o adjuntos antes de E3/E4: no se puede enviar y no cuenta como
  pendiente (RF-67).
- Consulta propia abierta en un dispositivo y el mismo médico entra en otro: la ve como consulta de la web, solo lectura (RF-64).
- Se reinstala la app con una consulta propia sin enviar: se pierde (el dispositivo es la única copia). Se documenta; la
  mitigación es enviar seguido. Si ya estaba creada en la web y abierta, se cierra desde la web (RF-65).
- Paciente creado en la app sin conexión: el vínculo se resuelve al primer envío con conexión (RF-13).
- Dos fichas posibles con el mismo documento: queda «En espera» hasta que el médico elige (RF-13).
- La web da de baja una consulta propia ya enviada: la app no la recrea; la marca (RF-33).
- Paciente de baja y consulta de baja a la vez: manda el paciente de baja («En espera»); la precedencia está en RF-19.
- Examen complementario o interconsulta borrado en la app y consulta cerrada: no reaparece al cerrar (RF-23, RF-70). Una carga de
  laboratorio o peso dada de baja sigue de baja: el cierre no la toca (D20).
- Campo vaciado a propósito: se envía vacío; un campo que el médico no tocó no se envía (RF-69).
- Texto con tildes, ñ, emojis y saltos de línea: llega igual (como en la lectura).
- Un archivo llamado `.pdf` que no es un PDF, o `.jpg` que no es una imagen: no se acepta (el tipo se valida por el contenido, RF-44).
- Consulta nueva en curso: lista todos los documentos del paciente; consulta previa: solo los de esa consulta (RF-45).
- Un documento de la sección `documentos` con imagen (JPG, JPEG o PNG) se sube, se lista y se abre igual que un PDF (RF-44, RF-45).
- Sesión que vence mientras se envía, o médico que cierra sesión con datos sin enviar: queda «En espera»; al volver a entrar su
  dueño, sale (RF-6, RF-50, RF-51).
- Servidor de la web caído un rato con la app abierta: lo pendiente queda «En espera» y se reintenta de forma periódica (RF-29).
- Otro médico entra en el mismo dispositivo: no se envía lo pendiente del primero ni el botón del histórico (RF-10, RF-50).
- Médico con comportamiento especial en la web (uno ve las consultas de otro): no se replica.
- Dos pedidos iguales casi a la vez (doble toque, reintento y envío manual): una sola consulta, una sola carga (RF-15, RF-54).
- Un cambio mientras viaja el envío: no se da por enviado (RF-55).
- «Demasiados pedidos» de la web: se espera, no se reintenta en ráfaga y no se muestra como rechazo (RF-30).
- Una parte rechazada: la consulta no se cierra en la web hasta resolverla (RF-56).
- Consulta con una sección cuyo envío todavía no existe (compilaciones intermedias, o adjuntos antes de E4): la sección no se
  ofrece (RF-4).
- Paciente con una consulta abierta abandonada en la web (hay muchas): la app avisa y crea la suya; no toma ni cierra la de la
  web (RF-11, RF-18, RF-59).
- Consulta del histórico que el médico decide enviar sin conexión: el botón la deja pendiente con la confirmación ya dada y se
  envía al volver la conexión (RF-10).
- Médico con pediatría y clínica y una licencia vencida con la otra vigente: entra, y la vencida queda frenada (D25).
- Si un médico cierra en la web una consulta de la app, la web reactiva las filas que la app dio de baja (defecto de la web que
  la app no controla; lo documenta el plan).

## Fuera de alcance
- Continuar, editar o cerrar desde la app consultas cargadas en la web, aunque sean propias y estén abiertas: **subentrega
  futura con su propia spec** (D1).
- Reabrir una consulta ya cerrada y enviada, o anularla (D13, RF-26).
- Envío en lote del histórico y «marcarlo como resuelto» (D2).
- Grabación de audio y transcripción; historia digitalizada; diagnósticos; antecedentes personales; la sección Enfermedades (spec
  003, ya escribe por su cuenta).
- Avisar en pantalla cuando la web y la app editan lo mismo; pantalla de conflictos (D7); unificar fichas duplicadas; revalidar
  vínculos.
- Secretarias.
- Corregir cómo pediatría marca el médico de las consultas ajenas (002 Q17).
- Las fotos de exámenes complementarios de E4: fuera de alcance por ahora (T100 y T105; D24 revisada).
- Cambiar las pantallas de la web de hclinica.
- Publicar y subir el servidor a producción: son decisiones y aprobaciones aparte.

## Qué necesita el servidor de la web (repo aparte, rama `salud360-api`; este repo no lo implementa)
**Autorización (D3):** el usuario autorizó tocar el Laravel de hclinica para **todas** las rutas de escritura juntas. Alcance:
implementarlas y probarlas en el clon y en la copia **local**; **producción sigue requiriendo aprobación aparte y respaldo
probado** (D4). Es una decisión explícita del usuario, **contra la recomendación del planificador** de ir por subentregas. Por eso
cada capacidad dice de qué subentrega es y **no se despliega a producción hasta que su subentrega esté implementada y validada en
la app** (el planificador lo lee así: el clon y la copia local las tienen todas desde el principio, para probarlas; D19(c)).
Hoy no existe ninguna ruta de escritura de consultas [VERIFICADO]. Los nombres de las rutas están en las notas para el plan.

| Sub | Capacidad | Se despliega a producción cuando |
|---|---|---|
| E1 | Crear una consulta de forma repetible sin duplicar, a nombre del médico del token, e informar si el paciente tenía otra abierta | E1 cerrada |
| E1 | Guardar textos y examen físico; cerrar (confirmando solo lo pendiente de confirmar); dar de baja una consulta propia | E1 cerrada |
| E1 | Recordar de forma permanente lo recibido de cada dispositivo (registro propio de la API, D21) y rechazar escrituras de administrador, secretaria, otro médico, consultas de la web y consultas cerradas (RF-52, RF-53) | E1 cerrada |
| E2 | Guardar formularios, antecedentes familiares y listas (estudios, exámenes, interconsultas), devolviendo los identificadores de la web; **dar de baja exámenes e interconsultas (capacidad nueva)** | E2 cerrada |
| E3 | Guardar, actualizar y dar de baja laboratorio y peso (visibles al instante) y el texto de pendientes, devolviendo identificadores | E3 cerrada |
| E4 | Subir los documentos de la HC general (PDF, JPG, JPEG y PNG, validados por contenido), bajarlos, listarlos según la consulta (nueva: todos los del paciente; previa: solo los de esa consulta) y darlos de baja; informar los límites del hosting sin escribir | E4 cerrada |
| E5 | Leer y guardar la licencia de cada médico para la HC general | E5 cerrada |

**Orden de despliegue (cada paso con su aprobación):** 1) servidor en la copia local → 2) app contra la copia local (tests, E2E,
prueba manual del usuario) → 3) servidor en producción con **respaldo restaurable verificado**, comprobación sin token (`401`, no
`404`), y revisión de la configuración de producción (depuración apagada, privilegios, carpetas de adjuntos), **solo con las
capacidades de las subentregas cerradas**, y el registro nuevo de lo recibido aprobado junto al respaldo (D21) → 4) recién entonces
publicar la app, y no antes de cerrar E1 a E3 (D23). Publicar la app antes de que producción tenga lo del servidor deja todo lo
enviado en «En espera» o «No se pudo enviar». Producción no se toca en esta spec.

## Criterios de finalización
Por subentrega (la anterior debe estar cerrada):
- **E0:** deuda de limpieza de la copia local (datos de la L y de la 003; la lista está en la memoria del proyecto) saldada,
  siempre por id; respaldo de la base local restaurado en una base descartable y verificado con suma de control; médico de prueba
  local y paciente «PRUEBA SALUD360» creado por la propia prueba (D4).
- **Cada RF de lógica** con test en `commonTest`/`jvmTest` (en rojo antes del código). RF de pantalla: lista de pasos para el
  usuario en la web local.
- **Prueba de punta a punta (E2E) contra la copia local** (como la de pediatría): crea una consulta con todas las secciones de la
  subentrega, la lee de vuelta, la cierra y **deja todo como estaba**. La limpieza se hace por SQL directo fuera de la API, por
  id, nunca con `DROP`. Se compara, antes y después, el conteo y la suma de control de **todas las tablas que la prueba pudo
  tocar** (salvo la caché de sesión), y el registro de accesos tiene solo lo esperado. El registro de lo recibido (RF-15) **no se
  vacía**: solo se limpian sus filas de prueba, por identificador.
- **Puerta bloqueante:** lo que la web usa para saber qué mira el médico (RF-16) queda **idéntico**, también tras cargar
  laboratorio o peso; si cambia, la subentrega no se cierra.
- **Una consulta creada en la app se ve completa en la web** (abierta con cada perfil, RF-17) y **una cargada en la web se ve
  completa en la app**, sección por sección (lista de comprobación en el plan de tareas).
- **Sin señal:** modo avión, cargar, recuperar la señal, verificar que se envió una vez (prueba manual; nunca se probó para la HC
  general).
- **Histórico:** con una base del dispositivo migrada de hepatología y consultas viejas sin enviar, ninguna se envía al abrir la
  app, iniciar sesión ni volver la conexión (test); una se envía solo con la confirmación y solo por su dueño (RF-10), y una con
  secciones sin envío habilitado no se puede enviar (RF-67). Lo que existe al migrar queda como histórico y lo creado después,
  como propio (RF-8).
- **Rechazo y espera:** con el servidor devolviendo `422`, `403` y `404`, y con licencia vencida, vínculo faltante, sesión
  vencida y sin conexión, el dato queda y se ve el motivo correcto («No se pudo enviar» o «En espera», RF-6, RF-30; test con
  servidor falso). Con el servidor caído un rato y la app abierta, lo pendiente sale solo al volver (RF-29).
- **Repetición:** un envío repetido tras perder la respuesta no duplica la consulta ni sus filas (RF-15).
- **Cierre:** un examen complementario o una interconsulta borrado no reaparece al cerrar (test en el servidor y en el E2E); una
  carga de laboratorio dada de baja sigue de baja tras el cierre; el cierre solo sale cuando todo lo pendiente llegó (RF-56).
- **Antes de publicar la escritura:** E1 a E3 cerradas (D23) y una **comprobación de solo lectura de cuántos médicos de clínica
  tienen la licencia vencida o sin fila de licencia** (con D22 quedarían afuera o con la HC frenada), tarea del plan y no una
  decisión.
- Producción: **fuera** de esta spec.

## Decididas (2026-11-03, usuario)
Cada una con la alternativa descartada.

- **D1 — Alcance de la edición.** La app crea consultas nuevas y edita o cierra **solo las que ella creó**; las de la web siguen
  en solo lectura. *Descartado:* continuar las abiertas propias de la web (o editar cualquiera propia) —queda fuera de alcance,
  subentrega futura con su spec—. Motivo: la lectura no trae las filas pendientes de confirmar de una consulta abierta de la web
  ni sus PDF, audio o diagnósticos, y continuarla arriesga duplicar lo pendiente.
- **D2 — Lo viejo sin enviar.** Nada sale solo; botón «Enviar a la web» consulta por consulta, con confirmación que muestra
  paciente, fecha y secciones; los antecedentes desactivados no se envían nunca (RF-7 a RF-10). *Descartados:* lote (mandaría
  años de historial de golpe a producción y puede crear fichas) y «marcar como resuelto» (miente y pierde información).
- **D3 — Autorización para tocar el Laravel de hclinica.** Se autorizan **todas** las rutas de escritura juntas, para
  implementarlas y probarlas en la copia local; producción, aparte y con respaldo probado (D4). Orden: servidor local, app
  local, producción (aprobación aparte), recién después publicar la app. *Descartado:* aprobar por subentrega, que era la
  recomendación del planificador; el usuario eligió lo contrario. Por eso cada capacidad lleva su subentrega y su condición de
  despliegue.
- **D4 — Respaldo, limpieza y cartera de prueba.** Primero se salda la deuda de limpieza de la copia local (datos de la L y de
  la 003); respaldo de la base local restaurado en una base descartable y verificado con suma de control; limpieza siempre por
  id, nunca `DROP`; datos de prueba creados por la propia prueba (paciente «PRUEBA SALUD360») con el médico de prueba local; en
  producción no se escribe hasta que el usuario indique médico y paciente de prueba y confirme un respaldo probado.
  *Descartado:* probar con un paciente real de la cartera con respaldo (restaurar tablas enteras pisaría el trabajo de otros
  médicos).
- **D5 — Partición.** Orden E0, E1, E2, E3, E4; E5 (licencia) aparte. *Descartados:* dejar el cierre para el final (deja
  consultas abiertas en la web) y la partición «textos / formularios / laboratorio y adjuntos / cierre y licencia». La regla de
  publicación de D5 («no antes de cerrar E1 y E2») fue **reemplazada por D23**.
- **D6 — Consulta abierta en la web al crear una nueva.** La app avisa con el nombre de quien la abrió y crea igual (RF-18,
  RF-59). *Descartados:* bloquear (hay muchas abandonadas) y reutilizar la de la web (tomaría trabajo de otro médico).
- **D7 — Conflictos.** Gana la última escritura por campo; no se envía lo que el médico no tocó; sin pantalla de conflictos
  (RF-34). *Descartado:* control optimista por versión (versión en el dispositivo y en el servidor, y pantalla de conflicto).
- **D8 — Migración local para laboratorio y pendiente (E3).** Aprobada una migración local que agrega a laboratorio y a pendiente
  el identificador de la web (el detalle técnico está en las notas para el plan), a implementar cuando llegue E3. En pendiente identifica al
  texto único de la consulta (RF-43). Es **distinta de la migración de E1** (D26): son dos migraciones, cada una con su número,
  reservado mirando **todas** las ramas justo antes de crearla (la rama principal llega a la 5 y `hc-general`, a la 8; ver las
  notas para el plan); se coordina con las demás ramas de HC y, si otra rama ya tomó el número, se vuelve a preguntar. Sin vuelta
  atrás en un dispositivo migrado; 001 y 002 se publican juntas. *Descartados:* renombrar las filas enviadas con el id de lo
  traído de la web sin migrar (quedarían «de la web» y no se podría borrar la carga enviada) y dejar el laboratorio fuera de la
  entrega.
- **D9 — Lo que la web no tiene.** Se aplica la decisión del 2026-10-09: tabaquismo, frecuencia cardíaca, circunferencia
  abdominal, diagnósticos, audio y antecedentes personales salen del catálogo de las consultas que se envían; lo ya cargado
  queda en solo lectura con el aviso «No se envía a la web» (RF-3). El mapa de nombres vive en el servidor, también al revés
  (002 Q12 = A, ratificada). *Descartados:* guardar tabaquismo en «otro» (inventa un significado en la web) y dejarlo sin
  enviar (el médico creería que se guardó). Esto cambia el catálogo de la HC general que define la 001: el plan lo declara como
  cambio sobre la 001 y sobre el test que hoy lo fija.
- **D10 — Pendientes.** Se envían como el texto único por consulta de la web, sin «Resuelto» (RF-43). *Descartado:* simular
  «resuelto» dentro del texto o agregar una columna a la web.
- **D11 — Licencia.** La HC general **sí** cobra licencia (la web ya la exige en cada pedido); se lee y escribe la licencia de cada
  servidor sin copia local por HC, reutilizando la 004; sin migración de licencias. Aviso y panel en E5, aparte; **el ingreso pasa a
  E1 por D22**. *Descartado:* agregar la especialidad a la tabla local de licencias.
- **D12 — Adjuntos y PDF.** En E4, al final y lo primero que se recorta; el límite es el menor entre la web y el hosting, medido
  en local (y leído en producción solo según D18); nunca se envían a clínica adjuntos de otra HC del mismo paciente (RF-44 a
  RF-46). *Descartados:* meterlos en E1 (lo más frágil) y dejarlos solo en la web.
- **D13 — Reabrir.** Una consulta cerrada se puede reabrir solo mientras el cierre no se haya enviado (RF-25). *Descartado:*
  crear una reapertura en el servidor (la web no la tiene).
- **D14 — Anular y cierre.** Anular una consulta ya enviada la da de baja, nunca la borra; el cierre confirma solo las filas
  pendientes y no copia el defecto de la web que resucita lo borrado (RF-23, RF-26). *Descartados:* replicar el cierre de la web
  al pie de la letra y prohibir anular las enviadas (quedarían abiertas en la web).
- **D15 — Elegir ficha al enviar.** Reutiliza la pantalla de la 003, abierta desde la consulta en espera (RF-13). *Descartado:*
  una pantalla nueva en la lista de pacientes (la 003 ya descartó resolver toda la cartera).

## Decididas (2026-11-04, usuario)
- **D16 — Histórico con secciones sin envío (ratificada).** «Enviar a la web» está disponible para una consulta del histórico
  solo si todas sus secciones con datos tienen envío habilitado; si no, el médico ve cuáles faltan, la consulta sigue «Solo en este
  dispositivo» y no cuenta como pendiente (RF-67). *Descartado:* enviar solo las secciones habilitadas y dejar el resto «en
  espera» (la consulta quedaría partida entre la app y la web).
- **D18 — Límite de adjuntos en producción (ratificada).** Se mide en local; leerlo en producción, con una ruta de solo lectura
  que forma parte de lo autorizado por D3, solo cuando el usuario apruebe la etapa de producción de la escritura; nada se escribe
  allá antes (RF-44). *Descartado:* medirlo con una subida de prueba en producción antes de esa aprobación.
- **D20 — Laboratorio y peso visibles al instante.** El laboratorio general, el reducido y el peso se crean en la web visibles
  apenas llegan, igual que la web hoy; el cierre confirma **solo** exámenes complementarios e interconsultas (RF-22, RF-41,
  RF-42). *Descartado:* dejarlos pendientes hasta cerrar. Motivo: el reviewer verificó en el código de la web que solo exámenes e
  interconsultas nacen pendientes. **Se corrigió la afirmación falsa** que traía esta spec y las notas que se habían puesto en la
  002 (RF-20 y RF-22), y el anexo.
- **D21 — Idempotencia.** La repetición de los envíos se guarda en una tabla chica propia de la API, creada por la API como las
  demás tablas propias de la API, **sin tocar ninguna tabla de la web**; vale para la creación de la consulta y para las filas
  hijas (laboratorio, peso, registros, pendientes, adjuntos), de modo que reenviar tras perder la respuesta no duplique nada
  (RF-15, RF-44). Autorizado solo para la copia local (D3); en producción se aprueba aparte junto al respaldo (D4), porque es una
  tabla nueva en la base de producción. *Descartados:* una columna en las tablas de la web e idempotencia solo al crear.
- **D22 — Licencia de la HC general desde E1.** La licencia de la HC general se extiende a clínica **desde E1** (no en E5): se
  **enmienda la spec 004** para que la HC general cuente como «HC que lleva licencia» en el ingreso y en el refresco (consulta al
  servidor antes de publicar la sesión, médico con licencia vencida bloqueado, médico sin fila de licencia en clínica según P3
  de la 004). E5 queda reducida a aviso de vencimiento y panel de licencias (RF-58, RF-47 a RF-49); el guardado de licencias no
  puede regresar desde E1 (RF-66). La enmienda es **propuesta, no aplicada**: la lista completa de lo que cambia en la 004 está en
  las notas para el plan. *Descartado:* dejar la licencia de clínica para E5.
- **D23 — Publicación.** La escritura se publica recién con E1 a E3 cerradas; E4 (adjuntos) y E5 (aviso y panel) pueden ir
  después. Reemplaza la regla de D5. Elimina que se publique una versión que pierda laboratorio, peso o pendientes en consultas
  nuevas. *Descartado:* publicar con E1 y E2 (RF-4 ocultaría laboratorio, peso y pendientes a los médicos).
- **D25 — Licencia con dos historias clínicas.** El médico con pediatría y clínica **entra** a la app aunque una HC esté vencida y
  la otra vigente o sin fila; solo se bloquea la app entera si **todas** las HC con licencia contestan «vencida» con fecha; la HC
  vencida queda frenada y su pendiente «En espera» con el motivo que nombra la HC (RF-58, RF-6). El usuario aclaró que el caso no
  se espera, porque el médico paga por el total: la regla es **defensiva**. *Descartado:* bloquear la app cuando una sola HC está
  vencida (como hoy con una HC). La comprobación de solo lectura de cuántos médicos de clínica tienen licencia vencida o sin fila,
  antes de publicar, queda como **tarea del plan** (ver «Criterios de finalización»).
- **D26 — Marca de histórico/propio (migración chica en E1).** Se aprueba una migración local **chica** en E1 que marca cada
  consulta de la HC general: lo que existe al migrar queda como «histórico»; lo creado después, como «propio» (RF-8, RF-68).
  Es **distinta** de la migración de E3 (D8): son dos migraciones, cada una con su número, reservado mirando **todas** las ramas
  justo antes de crearla (la rama principal llega a la 5 y `hc-general`, a la 8); si otra rama ya tomó el número se vuelve a
  preguntar; sin vuelta atrás en un dispositivo migrado, y 001 y 002 se publican juntas. *Descartado:* una regla por fecha
  (frágil: depende del reloj del dispositivo y cambia al editar). Se quita la salida «si es imposible vuelve a preguntar» de la
  versión anterior de RF-8.
- **D27 — Vaciar y borrar a propósito.** Un campo que el médico **vacía a propósito** se envía vacío (acción explícita; nunca un
  vacío que el médico no tocó); borrar un examen complementario o una interconsulta ya enviado lo da de baja en la web (nunca lo
  borra físicamente), igual que D14 (RF-69, RF-70). El QA no encontró hoy en la web una función para dar de baja exámenes ni
  interconsultas (sí laboratorio, peso, PDF y audio): es una **capacidad nueva del servidor**, declarada en las notas para el
  plan. *Descartado:* que el vacío nunca se envíe (el médico no podría borrar un texto de la web desde la app). Es una acción
  deliberada, por lo que no contradice el punto 3 de la constitución.

## Decididas al aprobar (2026-11-04, usuario)
- **D17 — RF-50 de la 002.** Se enumera lo que el middleware escribe: la última cláusula del RF-50 de la 002 pasa a listar lo que
  se registra además de la sesión —la caché de sesiones, la baja de la licencia vencida, la lista de médicos sin vincular y las
  tablas propias de la API, incluido el registro de lo recibido—, sin tocar datos clínicos ni de pacientes. Aplicado en la 002.
  *Descartado:* dejar la frase como estaba (inexacta) o aflojarla a «no escribe datos clínicos» sin enumerar (no verificable).
- **D19 — Las cuatro propuestas, validadas.** (a) Una consulta propia cerrada con el cierre enviado queda solo para leer y no se
  puede anular (RF-25, RF-26). *Descartado:* permitir editarla o anularla, que obligaría al servidor a aceptar escrituras sobre
  consultas cerradas. (b) «Resuelto» no se ofrece en los pendientes que se envían (RF-43). *Descartado:* dejarlo como dato solo
  local, un estado que la web no tiene y que el médico creería enviado. (c) «No se despliega» hasta cerrar la subentrega significa
  hacia producción; todas las capacidades del servidor existen en la copia local desde el principio. *Descartado:* copiar cada ruta
  a la copia local recién al empezar su subentrega. (d) Si una parte de una consulta es rechazada, la consulta no se cierra hasta
  que el médico la corrija y reenvíe o decida descartarla («Descartado: no se envió a la web») (RF-30, RF-56). *Descartado:*
  cerrar igual con lo que llegó, que dejaría en la web una consulta cerrada e incompleta sin aviso.
- **D24 — Adjuntos: tipos y fotos (revisada el 2026-11-13).** La sección «Documentos (PDF e imágenes)» de la HC general se envía con
  **PDF, JPG, JPEG y PNG**, validados por contenido; **ya no es solo PDF**. Las **fotos de exámenes complementarios** quedan **fuera de
  alcance por ahora**: solo entrarían si se verifica que la web las muestra (T95) y el usuario lo pide, y son una parte aparte de los
  documentos. *Descartados:* seguir con solo PDF (el usuario pidió las imágenes, que la web ya muestra en esa sección) y todas las fotos
  sin verificar (se escribiría en tablas que la web quizá no muestra). El cambio vale solo para `documentos`; **no** alcanza a las fotos
  de exámenes.

## Dudas abiertas
Ninguna. (Q-E4-2 —qué carpeta del hosting y cómo se crea en producción— no es una duda de comportamiento: es un **paso de la etapa de
producción** (D18, E9 de la escritura) y sigue pendiente.)

## Hallazgos de la clarificación (2026-11-04) y cómo se resolvieron
Numeración del reviewer. «Pasa al plan» = la spec fija el QUÉ y el plan resuelve el cómo.
1. **RF-28 no verificable / números sin origen** → reescrito con reloj falso y origen de los 4 s; sin tope numérico de pedidos (RF-28).
2. **RF-34 «última escritura»** → hora de llegada al servidor, solo campos tocados, ejemplo 10:00 / 9:55 / 10:05 (RF-34).
3. **RF-18 umbral y sin conexión** → dato fresco = lista leída hace menos de un minuto; sin dato fresco se crea y se avisa al enviar (RF-18, RF-59).
4. **Partir RF-6** → RF-6 (causas ajenas), RF-50/RF-51 (sesión y médico), RF-58 (licencia en el ingreso).
5. **Formato (RF-17, marcas inline, párrafo suelto, reabrir/anular)** → RF-17 sin sujeto externo; marcas de aclaración fuera del texto de RF-10 y RF-25; el párrafo suelto pasó a requisitos no funcionales; tabla de capacidades sin «reabrir» (D13).
6. **Textos de aviso** → una sola tabla con un significado cada uno; la etiqueta de la Q14 de la 002 deja de ser cierta (ver «Textos de aviso»).
7. **RF-6 vs RF-30** → un solo comportamiento: causas ajenas al dato = «En espera» con motivo visible, reintento solo y a mano, sin pérdida; rechazo solo por causa del dato (RF-6, RF-30, D22).
8. **Laboratorio y peso pendientes de confirmar** → falso; D20 (RF-22, RF-41, RF-42), corregido también en la 002.
9. **RF-3 vs RF-36** → se oculta la sección legada «antecedentes personales»; la nota que acompaña los antecedentes familiares viaja (RF-3, RF-36).
10. **Licencia (D22)** → RF-58 y D25; enmienda propuesta a la 004.
11. **Propia enviada y abierta** → sigue editable y como «Consulta en curso» (RF-62).
12. **Histórico vs propio persistente** → RF-8, resuelto con la marca de D26 (migración chica en E1).
13. **Datos por paciente con histórico** → RF-7 y RF-68, con la marca de D26 sobre las consultas.
14. **Histórico abierto / qué no viaja** → RF-63 y RF-10 (la confirmación lo avisa).
15. **Dos dispositivos** → RF-64 (como D6).
16. **Publicación** → D23.
17. **Coordinación del número de migración** → D8 y D26 (dos migraciones, cada una con su número reservado mirando todas las ramas); **pasa al plan** la reserva de los números.
18. **Idempotencia** → D21, RF-15 (también para filas hijas).
19. **Cambio durante el viaje; cerrar/reabrir durante un envío** → RF-55.
20. **Orden de envío y parte rechazada** → RF-56 (cierre al final) y RF-53 (el servidor no acepta escrituras en cerradas); el descarte del dato rechazado queda como propuesta a validar (D19(d)).
21. **Disparadores inexistentes** → RF-57: trabajo nuevo por plataforma, misma espera de 4 s, nunca 600 ms; **pasa al plan** el detalle por plataforma.
22. **Sesión y médico, 401, 503, 429** → RF-50, RF-51, RF-6, RF-30.
23. **Anular: borde y cascada** → RF-26 (cascada: los datos por paciente no se dan de baja; **pasa al plan** cómo los trata la web).
24. **Dos dispositivos** → RF-64 (junto con 15).
25. **Datos solo locales y desactivados** → RF-60 (no pendientes, no «enviados»).
26. **Adjuntos** → RF-44 a RF-46 (origen del límite, descarga con la sesión, subida cortada con D21); fotos: **duda D24**; la lectura de límites del hosting **pasa al plan**.
27. **Servidor** → RF-52 y RF-53.
28. **Pendientes: texto único vs varias filas** → RF-43 (uno por línea, el último manda, se muestra como texto único); segunda clarificación: ver A4.
29. **Panel de licencias y guardado de licencias cuando clínica deje de ser solo lectura** → RF-66 (ahora en E1); el detalle **pasa al plan**.
30. **Cosas del plan dentro de la spec** → rutas, migración, columnas, líneas y límites movidos a `notas-para-el-plan.md`.
31. **Criterio del E2E** → limpieza por SQL directo, qué se compara, puerta bloqueante RF-16 (Criterios); el detalle de las tablas **pasa al plan**.
32. **Laboratorio y peso** → D20 (junto con 8).
33. **Lo verificado** → se mantiene (en las notas para el plan).
34. **Lo verificado** → se mantiene (en las notas para el plan).
35. **Pendientes por HC** → un pendiente pertenece a la HC de la consulta en que se cargó; los sin consulta no viajan (RF-43); cómo se persiste, **pasa al plan**.
36. **D16** → **ratificada por el usuario** (Decididas): «con datos» definido, el médico ve las secciones que faltan, el histórico con secciones sin envío queda «Solo en este dispositivo» y no cuenta como pendiente, y se advierte que casi todo el ex-hepatología no podrá enviarse hasta E3/E4 (RF-67).
37. **D17** → cambio propuesto al RF-50 de la 002, pendiente de aprobación; sigue como duda menor con recomendación y alternativa.
38. **D18** → **ratificada por el usuario**: la lectura de límites del hosting en producción solo cuando el usuario apruebe la etapa de producción de la escritura; hasta entonces se mide en local (RF-44).
39. **D19** → RF-65 (cómo cierra el médico una propia abierta que no puede editar); las demás propuestas quedan en D19 con recomendación y alternativa.
40. **Envío de una misma consulta en paralelo** → RF-54.
41. **Vínculo con el id de la web y limpieza del pendiente** → RF-61 (un corte no deja lo enviado como pendiente ni duplicado), junto con RF-60.
42. **Cualquier disparador nuevo** → pasa por la espera de 4 s (RF-57).
43. **Motor viejo de sincronización** → riesgo declarado en requisitos no funcionales; el plan lo cierra excluyendo las tablas de HC con envío propio o apagándolo (**pasa al plan**).
44. **El anexo no debe citar los volcados de producción de la carpeta de pruebas** → el anexo se movió a las notas para el plan y no los cita; los datos de prueba son los que crea la propia prueba (D4).

### Segunda clarificación (2026-11-04)
La numeración A1 a A4 sigue el pedido de corrección; A2 y A3 se asignaron a D25 y D27 según ese pedido.
- **A1 (histórico persistente, hallazgos 12-13)** → D26: migración chica en E1 que marca lo que existe al migrar como histórico y lo creado después como propio; RF-8, RF-68, cuadro de subentregas (E1 lleva migración), D8 (migración distinta, en E3) y fuera «si es imposible vuelve a preguntar».
- **A2 (licencia con dos historias)** → D25 decidida: la duda se cierra; regla defensiva en RF-58; comprobación de solo lectura antes de publicar como tarea del plan (Criterios).
- **A3 (vaciar y borrar a propósito)** → D27: RF-69 y RF-70; capacidad nueva del servidor declarada (tabla de capacidades y notas).
- **A4 (RF-23 y caso límite del laboratorio)** → RF-23 reescrito para exámenes complementarios e interconsultas (con D20 el cierre ya no toca laboratorio); «borrar» definido (Definiciones, RF-70); caso límite y criterio de finalización corregidos.
- **Pendientes de la web frente a la lista de la app (RF-43, RF-33, D8)** → RF-43: la web ve un texto único; la app lo trata como unidad, no crea un pendiente nuevo si coincide con lo enviado, muestra el texto de la web como un solo pendiente si cambió y manda el dispositivo si tiene cambios sin enviar; qué fila local lleva el identificador **pasa al plan**.
- **RF-26 vs RF-25 y RF-53** → una consulta cerrada con el cierre enviado no se puede anular (RF-26, D19(a)).
- **RF-19, RF-30, RF-33 (precedencia)** → orden fijo en RF-19: paciente de baja o inexistente, después consulta de baja, después otro rechazo.
- **RF-66 en E1** → pasa a la sección de licencia de E1 y sale de E5; hasta E5 la licencia de clínica se renueva desde la web de hclinica.
- **RF-10 (dueño del histórico)** → el botón solo está disponible para su dueño.
- **RF-52 y registro de lo recibido** → permanente, sin vencimiento; si se perdiera, riesgo declarado y mitigación (RF-15, requisitos no funcionales); el E2E no lo vacía.
- **RF-29 y RF-57 (reintento periódico)** → RF-29: mientras la app esté abierta sigue reintentando de forma periódica, no solo por eventos.
- **D19(d) y la tabla de textos** → texto propio «Descartado: no se envió a la web»; «No se envía a la web» queda solo para lo que la web no guarda.
- **Implementación dentro de RF** → reescritos como comportamiento: RF-15, RF-41, RF-48, RF-61, RF-57, RF-8; el detalle (tabla de idempotencia, migraciones, columnas) está en las notas.
- **RF-3 (tabaquismo)** → es una casilla de los antecedentes familiares; RF-3 y RF-36 alineados.
- **Tabla de textos** → agregados «Enviando el cierre…», «Consulta en curso» y «Abierta en la web».
- **RF-18 (otro tipo)** → no avisa por una consulta propia abierta de otro tipo.
- **Notas §2 (`es_nueva_consulta`)** → la carga de laboratorio y de peso en la web también escribe el estado de «consulta nueva» del médico; anotado en las notas y en RF-16 y en la puerta bloqueante.
- **Dudas D17, D19, D24** → cada una con recomendación y alternativa; D25 ya no es duda; D16 y D18 pasan a Decididas. Al aprobar, el
  usuario resolvió D17, D19 y D24 (ver «Decididas al aprobar»).
- **Estado** → aprobada el 2026-11-04, sin dudas abiertas.
