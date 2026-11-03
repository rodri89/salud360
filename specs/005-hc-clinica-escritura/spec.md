# Spec 005 — La HC general escribe sus consultas en la base de la web (entrega E de la 002)

Estado: **borrador con las decisiones D1 a D15 tomadas por el usuario el 2026-11-03**, a la espera de clarificación (dudas
D16 a D19) y de aprobación. Sin plan ni tareas.
Depende de la spec 002 (parte 1: pacientes y cartera; entrega L: lectura, implementada y validada en local, falta el reviewer
y producción), de la 001 (qué secciones ve cada médico lo decide su perfil) y de la 003 (el patrón de una sección que
escribe contra la API de la web, el vínculo de pacientes y la pantalla de elegir ficha). Para la licencia (subentrega E5)
reutiliza la 004 (licencia que informa el servidor); la 004 está en `origin/main` y hay que integrarla a la rama
`hc-general` antes de E5 (su tarea T9, que se pide aparte).
Esta spec **reemplaza** lo que la 002 dejaba como «Entrega E (escritura) — borrador» (RF-5 a RF-7, RF-15 a RF-24 y RF-26 a
RF-34): la 002 ya lo dice y apunta acá.

Leyenda de las marcas:
- **(Dn)** = decidido por el usuario; ver «Decididas (2026-11-03, usuario)».
- **[NECESITA ACLARACIÓN Dn]** = duda que sigue abierta (D16 a D19); ver «Dudas abiertas».
- **[VERIFICADO]** = comprobado leyendo el código; el archivo y la línea están en el anexo «Lo verificado en el código».
- **[NO VERIFICADO]** = hay que comprobarlo en la copia local o en producción antes de planificar o de implementar.

## Contexto y objetivo
La HC general (la web `hclinica`, con sus perfiles) ya se **lee** desde la app (entrega L): el médico ve las consultas que
cargó en la web, sin que la app escriba nada. Todo lo que el médico carga hoy en la app para la HC general queda **solo en
el dispositivo**, marcado «Solo en este dispositivo (no se envía a la web)». La web es la dueña del dato clínico; la base del
dispositivo es caché y modo sin señal (constitución, punto 2).

El objetivo es cerrar el ida y vuelta, como ya funciona en pediatría: **el médico crea, completa, cierra y envía una consulta
de la HC general desde la app, y la ve igual en la web (y al revés)**, con o sin señal, sin pisar nunca un dato con vacíos
(constitución, punto 3) y sin mover lo que el médico tiene abierto en la web.

Es la entrega más riesgosa de la 002: escribe en una base con datos reales de pacientes que otros médicos usan en el mismo
momento. Por eso se parte en subentregas, cada una probada en la copia local, y **nada de esta spec se escribe en producción
sin respaldo restaurable probado y aprobación aparte** (002, decisión D3; D4 de esta spec).

## Usuarios
- **Médico de la HC general**, con o sin agenda, con cualquier perfil (clínica general, hepatología, enfermedades crónicas…).
- **Administrador**: solo en la subentrega E5 (licencias de la HC general).
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
- **Consulta propia de la app**: consulta de la HC general creada con la app, después de habilitado el envío. Cuando llega a la
  web queda identificada allá por su identificador de la web. Es la única que la app puede editar, cerrar y anular (D1).
- **Consulta de la web**: la que se cargó en la web (definición de la 002, entrega L). La app la muestra solo para leer.
- **Histórico del dispositivo**: consultas de la HC general guardadas en el dispositivo **antes** de habilitar el envío y
  nunca enviadas, incluidas las que eran de «hepatología» y la migración pasó a la HC general, y los antecedentes que esa
  migración dejó desactivados.
- **Enviado / pendiente de envío / en espera / rechazado**: un dato que la web confirmó / que la web todavía no tiene / que no se
  puede enviar todavía por una causa que se resuelve sola (sin conexión, falta vincular al paciente, falta elegir ficha) / que
  la web rechazó con un motivo.
- **Pendiente de confirmar (en la web)**: filas provisorias que la web guarda mientras la consulta está abierta (laboratorio
  general y reducido, peso, exámenes complementarios, interconsultas) y confirma al cerrarla. La web no las muestra en las
  tablas del paciente hasta ese momento.
- **Campo vaciado a propósito / nunca cargado**: el primero tenía texto y el médico lo borró; el segundo, el médico no lo tocó.
  Solo el primero se envía vacío.
- **Envío habilitado por sección**: una sección cuyo envío a la web está construido y probado en la versión instalada.

## Subentregas
Las subentregas son de **desarrollo y validación local**; cada una se aprueba antes de pasar a la siguiente (D5). **No se
publica la escritura a `main` antes de cerrar E1 y E2** (D5); publicar siempre es decisión aparte del usuario.

| Sub | Contenido | RF | Migración local | Rutas del servidor (ver «Dependencias externas») |
|---|---|---|---|---|
| **E0** | Precondiciones: limpieza de la deuda de pruebas, respaldo restaurable verificado, médico y paciente de prueba (D4) | — | no | no |
| **E1** | Crear la consulta; textos; examen físico; cerrar; el envío (diferido, sin conexión, sin duplicar, rechazo); histórico del dispositivo; candado por sección | RF-1 a RF-34 | no | crear, secciones, examen, cerrar (y reabrir/anular si hace falta) |
| **E2** | Formularios y listas sin migración: alimentación, antecedentes familiares, serologías, Child-Pugh, los 5 estudios, exámenes complementarios, interconsultas | RF-35 a RF-40 | no | formularios y listas |
| **E3** | Laboratorio general y reducido, peso y pendientes | RF-41 a RF-43 | **`9.sqm` (v10), aprobada (D8)** | laboratorio, peso, pendientes |
| **E4** | Adjuntos (PDF y fotos); es lo primero que se recorta (D12) | RF-44 a RF-46 | no | subir, bajar, borrar |
| **E5** | Licencia de la HC general (aviso y panel del administrador), aparte (D11) | RF-47 a RF-49 | no (D11) | licencias |

Orden: E0 → E1 → E2 → E3 → E4, con E5 aparte (D5). Por qué esta partición:
1. **El cierre va en E1**, no al final: sin cerrar, la consulta queda abierta en la web (ya hay muchas abandonadas) y las filas
   «pendientes de confirmar» no aparecen allá.
2. **Exámenes e interconsultas van en E2**, no con el laboratorio: ya tienen la columna del identificador de la web (migración
   4.sqm de pediatría). Todo lo que necesita `9.sqm` queda junto en E3 y se puede frenar ahí sin trabar nada más.
3. **Adjuntos aparte (E4)** porque es lo más frágil (límites del hosting, carpetas creadas a mano en pediatría, PDF de hasta 15 MB).
4. **Licencia aparte (E5)** porque el servidor de la web ya exige la licencia en cada pedido [VERIFICADO]: falta solo mostrarla y
   administrarla, y no toca consultas.
5. **Candado por sección (RF-4):** mientras una sección no tenga su envío, la consulta nueva no la ofrece para cargar. Así
   ninguna subentrega deja al médico cargando algo que cree que se envía y no sale.

## Requisitos funcionales

### Qué puede escribir la app (candados) — E1
- RF-1: EL SISTEMA envía a la web solo lo que pertenece a una **consulta propia de la app** y lo que cuelga de ella (levanta el
  RF-47 de la 002 únicamente para esto). Continuar las consultas abiertas cargadas en la web no se hace (D1).
- RF-2: MIENTRAS una consulta sea de la web, EL SISTEMA la muestra solo para leer —sin editar, cerrar, reabrir ni anular— aunque
  la haya cargado el mismo médico, y no envía nada sobre ella ni sobre sus filas (002 RF-45, RF-62; D1).
- RF-3: EL SISTEMA no ofrece cargar, en una consulta que se envía a la web, lo que la web no guarda: tabaquismo, frecuencia
  cardíaca y circunferencia abdominal del examen físico, diagnósticos, audio y antecedentes personales (D9); lo que ya está
  cargado en el dispositivo se conserva, a la vista, solo para leer y con el aviso «No se envía a la web».
- RF-4: MIENTRAS el envío de una sección no esté habilitado en la versión instalada, EL SISTEMA no la ofrece para cargar en una
  consulta nueva de la HC general y dice «Esta sección todavía no se envía a la web»; lo que ya tenía cargado en el dispositivo
  no se envía ni se marca como enviado.
- RF-5: EL SISTEMA no cambia el comportamiento de pediatría ni de ninguna otra historia clínica (002 RF-60; 001 RF-33).
- RF-6: SI el médico no tiene la HC general habilitada, no está vinculado en la web o tiene la licencia vencida (o sin fila),
  ENTONCES EL SISTEMA no envía nada, conserva todo en el dispositivo como «en espera» con ese motivo (002 RF-2 a RF-4) y,
  cuando se resuelve, lo envía sin intervención del médico.

### Lo viejo sin enviar (el histórico del dispositivo) — E1
- RF-7: EL SISTEMA no envía por su cuenta ninguna consulta del histórico del dispositivo —ni sus secciones, examen, listas,
  laboratorios, pendientes o adjuntos—: ni al abrir la app, ni al iniciar sesión, ni al volver la conexión, ni al reintentar,
  ni al abrir una consulta nueva (el RF-47/RF-48 de la 002 sigue valiendo para esto) (D2).
- RF-8: EL SISTEMA distingue a la vista las consultas del histórico de las consultas nuevas que sí se envían.
- RF-9: EL SISTEMA no envía nunca un antecedente desactivado (los que dejó la migración de hepatología con `activo = 0`), ni lo
  cuenta como pendiente, ni de una consulta nueva ni del histórico (D2).
- RF-10: CUANDO el médico toca «Enviar a la web» en una consulta del histórico, EL SISTEMA le muestra paciente, fecha y qué
  secciones tiene (y, si el paciente todavía no tiene ficha vinculada en la web, que se la va a buscar o crear), le pide
  confirmación y recién entonces la envía; hasta entonces sigue «Solo en este dispositivo». No hay envío en lote ni se la
  puede marcar como resuelta sin enviarla (D2). [NECESITA ACLARACIÓN D16: qué pasa si la consulta tiene secciones cuyo
  envío todavía no está habilitado.]

### Crear la consulta — E1
- RF-11: CUANDO el médico abre una consulta nueva de un tipo (consulta o telemedicina) y ya tiene en el dispositivo una consulta
  propia abierta del mismo paciente y tipo, EL SISTEMA reutiliza esa; nunca devuelve, continúa ni reutiliza una consulta de la
  web ni una del histórico (002 RF-15 ajustado; RF-46 de la L).
- RF-12: EL SISTEMA guarda la consulta primero en el dispositivo y permite cargarla sin conexión; la consulta se crea en la web
  en el envío diferido, nunca al abrir la pantalla.
- RF-13: CUANDO hay que enviar la primera consulta de un paciente que todavía no tiene ficha vinculada en la web, EL SISTEMA
  resuelve el vínculo antes de enviar (002 RF-10 a RF-13 y RF-36); SI hay que elegir ficha, el documento no sirve o la ficha
  es de otro paciente de turnos, ENTONCES la consulta queda «en espera» con ese motivo, sin enviarse, y el médico lo resuelve
  desde esa consulta con la pantalla de elegir ficha de la 003 (D15).
- RF-14: CUANDO la consulta se crea en la web, EL SISTEMA la deja a nombre del médico de la sesión (nunca de otro), con el tipo,
  la **fecha clínica y la edad que cargó el médico** (no la fecha del envío) y abierta.
- RF-15: SI el mismo envío se repite (corte a mitad, respuesta que no llegó, reintento, cierre y reapertura de la app),
  ENTONCES EL SISTEMA no crea una segunda consulta en la web; cada consulta de la app corresponde a una sola de la web.
- RF-16: EL SISTEMA no cambia lo que la web usa para saber qué paciente y qué consulta está mirando el médico en su pantalla
  (nunca mueve la pantalla del médico mientras atiende).
- RF-17: CUANDO una consulta creada por la app se abre en la web con un médico de cada perfil de la web (1, 2, 3, 4 y 9), EL
  SISTEMA de la web la muestra sin errores y con las secciones de ese perfil vacías y editables. [NO VERIFICADO: la web crea
  filas vacías al abrir una consulta de hepatología; hay que probar si las necesita. Se verifica a mano en la copia local.]
- RF-18: SI, según lo último que trajo la lectura, el paciente ya tiene una consulta abierta en la web (de cualquier médico),
  ENTONCES EL SISTEMA se lo avisa al médico antes de crear la nueva, con el nombre de quien la abrió, y la crea igual sin
  reutilizarla ni tocarla (D6). Sin lectura reciente o sin conexión no hay aviso y tampoco se bloquea nada.
- RF-19: SI el paciente de la consulta fue dado de baja en la web, ENTONCES EL SISTEMA conserva la consulta en el dispositivo
  «en espera» con el motivo y no crea ninguna ficha nueva por su cuenta.

### Textos y examen físico — E1
- RF-20: CUANDO el médico escribe en una sección de texto (motivo, datos subjetivos, datos objetivos, conductas, nota, hábitos,
  diuresis y catarsis, somnia), EL SISTEMA lo guarda y la web lo muestra en la misma consulta; el motivo lleva la fecha de la
  consulta (002 RF-16).
- RF-21: CUANDO el médico carga el examen físico, EL SISTEMA envía peso, talla, IMC, tensión arterial y nota, y la web los
  muestra (002 RF-17). Un valor «0.00» de peso, talla, tensión o IMC se entiende como vacío; «0» y «0.0» son datos (regla de
  la lectura).

### Cierre — E1
- RF-22: CUANDO el médico cierra una consulta propia, EL SISTEMA la marca cerrada en la web con la fecha y la edad que cargó y,
  **solo en ese momento**, confirma lo que estaba pendiente de confirmar de esa consulta (002 RF-24 y RF-22 ajustados).
- RF-23: EL SISTEMA, al cerrar, confirma solamente las filas pendientes de confirmar: no vuelve a activar filas que el médico
  borró (la web hoy las reactiva todas; la app no copia ese defecto) (D14).
- RF-24: SI el cierre no se puede enviar todavía, ENTONCES EL SISTEMA muestra la consulta cerrada en la app con la marca «Falta
  enviar» (la web la sigue viendo abierta); al enviarse, la marca desaparece.
- RF-25: EL SISTEMA permite reabrir una consulta propia cerrada solo mientras el cierre no se haya enviado a la web; una vez
  enviado el cierre, la consulta queda cerrada y solo para leer (D13; la web no tiene reapertura y el servidor no la inventa).
  [NECESITA ACLARACIÓN D19(a): que una consulta propia cerrada y enviada no se pueda editar es consecuencia de D1 y D13.]
- RF-26: CUANDO el médico anula una consulta propia que todavía no se envió, EL SISTEMA no envía nada (queda anulada solo en el
  dispositivo); CUANDO ya estaba enviada, la da de baja en la web (`activo = 0`; nunca la borra) y deja de mostrarla como
  abierta allá (D14).

### Envío: autoguardado, ritmo, sin conexión, rechazo y no duplicar — E1 (vale para todas las subentregas)
- RF-27: EL SISTEMA guarda en el dispositivo cada cambio sin esperar a la web y sin hacer ningún pedido a la web mientras el
  médico escribe (el autoguardado dispara cada 600 ms).
- RF-28: CUANDO una consulta tiene cambios pendientes, EL SISTEMA los envía después de **4 segundos sin cambios nuevos** en
  esa consulta, agrupados; una escritura continua de 30 segundos produce como máximo 2 envíos de esa consulta y ninguno
  mientras haya cambios en los últimos 4 segundos. El envío de una consulta completa (sin adjuntos) usa los pedidos mínimos
  que fije el plan (objetivo: no más de 6), por el límite de 60 pedidos por minuto por red y las pocas conexiones del hosting.
- RF-29: MIENTRAS no haya conexión, EL SISTEMA permite crear y editar consultas de la HC general y las envía cuando vuelve la
  conexión, sin duplicarlas (002 RF-33). Lo pendiente se reintenta a los 15 segundos, al minuto y a los 5 minutos, y después al
  volver la conexión, al abrir la app y al iniciar sesión; nada se pierde por no haber llegado.
- RF-30: SI la web rechaza un cambio por una causa que no se arregla reintentando (datos que no acepta, consulta que ya no existe,
  paciente fuera de la cartera, médico no habilitado, licencia vencida), ENTONCES EL SISTEMA lo conserva en el dispositivo
  marcado «No se pudo enviar», muestra el motivo en palabras del usuario nombrando la HC (002 RF-34; los motivos de la
  cartera), no lo reintenta en bucle, deja reintentar a mano y no frena el envío de las demás consultas. SI el motivo es
  «demasiados pedidos» o un error del servidor, ENTONCES lo trata como reintentable, no como rechazo.
- RF-31: EL SISTEMA muestra cuántas consultas de la HC general tienen cambios sin enviar, contando todo tipo de dato (también
  laboratorio y pendientes), y cuáles están «en espera» o «no se pudo enviar».
- RF-32: CUANDO un envío es parcial (llegó una parte y otra no), EL SISTEMA marca como enviado solo lo que la web confirmó y deja
  el resto pendiente; nunca marca como enviado lo que la web no confirmó.
- RF-33: CUANDO la consulta propia ya está en la web, EL SISTEMA la actualiza con lo que se cambie allá en lo que el
  dispositivo **no** tenga pendiente de envío, la muestra una sola vez (sin duplicar con la lista de la web) y empareja sus
  filas con las de la web (estudios, exámenes, interconsultas, laboratorio, pendientes) por su identificador de la web, sin
  duplicarlas. SI la web la dio de baja, ENTONCES la app no la vuelve a crear: la marca «Ya no figura en la web» (002 RF-63).
- RF-34: SI la web y la app cambiaron el mismo campo, ENTONCES gana la última escritura **por campo**, nunca por consulta, y la
  app no envía nunca un campo que el médico no tocó; no hay pantalla de conflictos (D7).

### Formularios y listas — E2
- RF-35: CUANDO el médico carga alimentación (hiposódica, diabetes, celíaco, otra, cada una con detalle), EL SISTEMA la guarda en
  la web (002 RF-19); una casilla que el médico no tocó no se envía.
- RF-36: CUANDO el médico marca antecedentes familiares (con detalle) o escribe la nota, EL SISTEMA los guarda en la web donde la
  web los lee y muestra los últimos del paciente aunque vengan de otra consulta (002 RF-18). Solo viajan los activos (RF-9).
- RF-37: CUANDO el médico carga serologías (16 marcadores, cada uno SI / NO / sin cargar), EL SISTEMA las guarda en la web; un
  marcador sin cargar queda «sin cargar» allá y nunca «NO» (002 RF-26).
- RF-38: CUANDO el médico carga Child-Pugh (5 parámetros), EL SISTEMA guarda en la web los puntos de cada uno y el total, y la
  clase se muestra como A (hasta 6), B (7 a 9) o C (10 o más) (002 RF-27).
- RF-39: CUANDO el médico carga ecografía abdominal, videoendoscopía digestiva alta, biopsia hepática, TC/RMN o estudios
  cardiológicos (fecha y descripción), EL SISTEMA los guarda y los ve en el historial del paciente, cada uno con la consulta
  donde se cargó (002 RF-28). SI la descripción supera lo que la web acepta (191 caracteres en estudios cardiológicos), ENTONCES
  EL SISTEMA no deja escribir de más y lo dice; nunca corta el texto en silencio.
- RF-40: CUANDO el médico pide un examen complementario o una interconsulta, EL SISTEMA los guarda en la web como pendientes de
  confirmar y los confirma al cerrar la consulta (RF-22); son acumulativos del paciente, y uno cargado en una consulta anterior
  se puede completar (resultado) pero no quitar (002 RF-22).
- Los nombres de columnas y de claves entre la app y la web los resuelve el servidor con su mapa, al revés que en la lectura
  (D9; 002 Q12); la app no cambia sus identificadores.

### Laboratorio, peso y pendientes — E3
- RF-41: CUANDO el médico carga laboratorio (general o reducido) con una fecha, EL SISTEMA agrega esa carga a la tabla del
  paciente; queda **pendiente de confirmar hasta el cierre** y desde el cierre la ven la app y la web. CUANDO borra una carga,
  EL SISTEMA la da de baja en la web (nunca la borra) y deja de verse en ambas. SI la carga no tiene fecha, ENTONCES usa la
  fecha de la consulta; nunca envía una fecha vacía ni la «2000-01-01» (002 RF-20, RF-21). Dos cargas con la misma fecha
  conviven; editar una carga ya enviada la actualiza, no crea otra. Para no duplicar, la app guarda el identificador de la web
  de cada carga (`9.sqm`, D8).
- RF-42: CUANDO el médico carga peso con fecha, EL SISTEMA lo agrega a la tabla de peso del paciente con las mismas reglas que
  el laboratorio (002 RF-29).
- RF-43: CUANDO el médico escribe los pendientes del paciente en una consulta propia, EL SISTEMA los envía como **el texto único
  de esa consulta** (el último texto manda, como en la web) y no envía «Resuelto» (D10). Siguiendo el criterio de RF-3, no
  ofrece «Resuelto» sobre lo que se envía. [NECESITA ACLARACIÓN D19(b)]

### Adjuntos — E4
- RF-44: CUANDO el médico adjunta un PDF o una foto a la consulta, EL SISTEMA lo guarda en el dispositivo, lo sube a la web y la
  web lo muestra; sin señal queda en el dispositivo y se sube después, **una sola vez** (002 RF-30). SI el archivo supera el
  límite —el menor entre el de la web y el del hosting (D12)—, ENTONCES EL SISTEMA lo dice antes de subirlo y no lo da por
  enviado.
- RF-45: CUANDO el médico abre una consulta con adjuntos cargados desde la web, EL SISTEMA los lista y permite abrirlos (002
  RF-31); hoy la lectura solo avisa que existen.
- RF-46: CUANDO el médico borra un adjunto propio ya subido, EL SISTEMA lo da de baja en la web y deja de mostrarlo en ambas;
  EL SISTEMA nunca envía a la HC general adjuntos que pertenecen a otra historia clínica del mismo paciente (002 RF-32; D12).

### Licencia de la HC general — E5
- RF-47: CUANDO la licencia del médico para la HC general está dentro del período de aviso, EL SISTEMA le avisa la fecha de
  vencimiento de esa historia clínica (002 RF-5; D11).
- RF-48: EL SISTEMA muestra y administra una licencia por médico **y por historia clínica**: cambiar la de la HC general no
  cambia la de pediatría (002 RF-7). La lee y la escribe en la base de la web, que es su dueña, sin guardar una copia local por
  historia clínica y sin migración (D11); reutiliza la regla de la 004 (la app decide con lo que informa el servidor).
- RF-49: EL SISTEMA deja entrar siempre al administrador, sin licencia (002 RF-6). [VERIFICADO que la web ya lo hace.]

## Requisitos no funcionales
- La web es la dueña del dato clínico: lo que el médico tiene sin enviar manda en el dispositivo hasta enviarse; lo que la web
  trae no pisa lo sin enviar, y ningún dato se reemplaza por un vacío ni se vacía por una respuesta con error (constitución,
  punto 3).
- **Nada se borra en la web**: baja lógica o conservar (002 Q15, aplicado también al escribir). La app no borra físicamente.
- Atomicidad: lo que sale en un pedido entra completo o no entra; una respuesta cortada no deja una consulta a medias.
- Lo que la app hace no cambia lo que el médico tiene abierto en la web.
- La API de escritura funciona en una base en **modo estricto** de MySQL (la de producción lo es probablemente; la local no): la
  web tiene casi todas las columnas sin valor por defecto [VERIFICADO en E3.1 de la 002].
- **Sin vuelta atrás en un dispositivo migrado** (D8): una app más vieja sobre una base más nueva descarta los datos del
  navegador. Las specs 001 y 002 se publican juntas, así que un dispositivo con la app publicada (esquema v6) pasa a v10 de una
  vez; el despliegue lo documenta y se recomienda enviar lo pendiente antes de actualizar.
- Funciona en web, Android e iOS.
- Ningún dato real de pacientes, ni tokens, ni nombres de personas en el repositorio, en los tests ni en las pruebas
  documentadas; las pruebas usan pacientes «PRUEBA SALUD360» creados por la propia prueba.
- Las pruebas de escritura corren solo contra la copia local (los E2E se saltean sin propiedades). Producción solo con
  aprobación aparte, respaldo probado y limpieza por id (D4).

## Casos límite
- Histórico del dispositivo con ex-hepatología: abrir la app, iniciar sesión o volver la conexión no envía nada (RF-7).
- Consulta propia abierta en un dispositivo y el mismo médico entra en otro: la ve como consulta de la web, solo lectura (RF-2,
  D1). No hay edición desde dos dispositivos.
- Se reinstala la app con una consulta propia sin enviar: se pierde (el dispositivo es la única copia). Se documenta; la
  mitigación es enviar seguido.
- Paciente creado en la app sin conexión: el vínculo se resuelve al primer envío con conexión (RF-13).
- Dos fichas posibles con el mismo documento: queda «en espera» hasta que el médico elige (RF-13).
- La web da de baja una consulta propia ya enviada: la app no la recrea; la marca (RF-33).
- Carga de laboratorio borrada en la app y consulta cerrada: no reaparece (RF-23).
- Texto con tildes, ñ, emojis y saltos de línea: llega igual (como en la lectura).
- Sesión que vence mientras se envía: queda «en espera» con «sesión vencida»; al volver a entrar sale (RF-6, RF-29).
- Médico con comportamiento especial en la web (uno ve las consultas de otro): no se replica.
- Dos pedidos iguales casi a la vez (doble toque, reintento y envío manual): una sola consulta, una sola carga (RF-15).
- `429` de la web: se espera, no se reintenta en ráfaga y no se muestra como rechazo (RF-30).
- Consulta con una sección cuyo envío todavía no existe (E1 sin E2): la sección no se ofrece (RF-4).
- Paciente con una consulta abierta abandonada en la web (hay muchas): la app avisa y crea la suya; no toma ni cierra la de la
  web (RF-11, RF-18).
- Consulta del histórico que el médico decide enviar sin conexión: el botón la deja pendiente de envío con confirmación ya dada;
  se envía al volver la conexión (RF-10).

## Fuera de alcance
- Continuar, editar o cerrar desde la app consultas cargadas en la web, aunque sean propias y estén abiertas: **subentrega
  futura con su propia spec** (D1).
- Reabrir una consulta ya cerrada en la web (D13).
- Envío en lote del histórico y «marcarlo como resuelto» (D2).
- Grabación de audio y transcripción; historia digitalizada (fotos de consulta tipo 3); diagnósticos; antecedentes personales;
  la sección Enfermedades (spec 003, ya escribe por su cuenta).
- Avisar en pantalla cuando la web y la app editan lo mismo; pantalla de conflictos (D7); unificar fichas duplicadas;
  revalidar vínculos.
- Secretarias.
- El motor de sincronización hacia turnos (rutas que no existen): no se toca; sigue siendo la deuda de `AGENTS.md`.
- Corregir cómo pediatría marca el médico de las consultas ajenas (002 Q17).
- Cambiar las pantallas de la web de hclinica.
- Publicar a `main` y subir el servidor a producción: son decisiones y aprobaciones aparte.

## Dependencias externas (repo `hclinica`, rama `salud360-api`; este repo no las implementa)
**Autorización (D3):** el usuario autorizó tocar el Laravel de hclinica para **todas** las rutas de escritura juntas. Alcance:
implementarlas y probarlas en el clon y en la copia **local**; **producción sigue requiriendo aprobación aparte y respaldo
probado** (D4). Es una decisión explícita del usuario, **contra la recomendación del planificador** de ir por subentregas; por
eso esta tabla dice de qué subentrega es cada ruta, y **hasta que su subentrega esté implementada y validada en la app, esa
ruta no se despliega** a producción (el planificador lo lee así: el clon y la copia local servida las tienen todas desde el
principio, para probarlas). [NECESITA ACLARACIÓN D19(c): confirmar esa lectura de «no se despliega».]

Los nombres de las rutas son provisorios; los fija el plan. Todas van dentro del grupo de la API de la app (token de turnos,
licencia y cartera comprobadas como en la lectura). Hoy **no existe ninguna ruta de escritura de consultas** [VERIFICADO].

| Sub | Ruta provisoria | Para qué | Se despliega cuando |
|---|---|---|---|
| E1 | `POST consultas` | Crear la consulta (idempotente por una clave que manda el dispositivo), a nombre del médico del token | E1 cerrada |
| E1 | `PUT consultas/{id}/secciones` | Textos | E1 cerrada |
| E1 | `PUT consultas/{id}/examen` | Examen físico | E1 cerrada |
| E1 | `POST consultas/{id}/cerrar` | Cerrar con fecha y edad; confirma solo lo pendiente de confirmar (RF-23) | E1 cerrada |
| E1 | `DELETE consultas/{id}` (baja lógica) | Anular una consulta ya enviada (D14); **no** hay `reabrir` (D13) | E1 cerrada |
| E2 | `PUT consultas/{id}/formularios` (o dentro de `secciones`) | Alimentación, serologías, Child-Pugh | E2 cerrada |
| E2 | `PUT pacientes/{id}/antecedentes-familiares` | Antecedentes familiares y nota | E2 cerrada |
| E2 | `PUT consultas/{id}/registros` | Estudios, exámenes complementarios e interconsultas (devuelve los ids de la web) | E2 cerrada |
| E3 | `PUT/DELETE pacientes/{id}/laboratorios` y `/pesos` | Cargas con fecha (devuelve los ids de la web) | E3 cerrada |
| E3 | `PUT consultas/{id}/pendientes` | Texto único de pendientes (D10) | E3 cerrada |
| E4 | `POST consultas/{id}/adjuntos`, `DELETE`, `GET …/archivo` | Subir, dar de baja y bajar; y los adjuntos dentro de `consultas/{id}` | E4 cerrada |
| E5 | `GET licencias`, `PUT licencias/{medicoIdTobb}` | Panel del administrador (el aviso ya viaja en `auth/perfil`) | E5 cerrada |

**Orden de despliegue (cada paso con su aprobación):** 1) servidor en la copia local → 2) app contra la copia local (tests,
E2E, prueba manual del usuario) → 3) producción del servidor con **respaldo restaurable verificado**, comprobación sin
token (`401`, no `404`), `APP_DEBUG=false`, carpeta de adjuntos y privilegios revisados, **solo las rutas de las subentregas
cerradas** → 4) recién entonces publicar la app a `main`, y no antes de cerrar E1 y E2 (D5). Publicar la app antes de que
producción tenga las rutas deja todo lo enviado en «no se pudo enviar» (404). Producción no se toca en esta spec.

## Criterios de finalización
Por subentrega (la anterior debe estar cerrada):
- **E0:** deuda de limpieza de la copia local (datos de la L y de la 003, ver `MEMORY.md`) saldada, siempre por id; respaldo de
  `hc_clinica` local restaurado en una base descartable y verificado con suma de control; médico de prueba local y paciente
  «PRUEBA SALUD360» creado por la propia prueba (D4).
- **Cada RF de lógica** con test en `commonTest`/`jvmTest` (en rojo antes del código). RF de pantalla: lista de pasos para el
  usuario en `devWeb`.
- **E2E contra la copia local** (como el de pediatría): crea una consulta con todas las secciones de la subentrega, la lee de
  vuelta, la cierra y **deja todo como estaba**: conteos y suma de control de las tablas de la web iguales antes y después
  (salvo la caché de sesión); lo que mira el médico en la web sin modificar; el registro de accesos con solo lo esperado.
- **Una consulta creada en la app se ve completa en la web** (abierta con cada perfil, RF-17) y **una cargada en la web se ve
  completa en la app**, sección por sección (lista de comprobación en `tasks.md`).
- **Sin señal:** modo avión, cargar, recuperar la señal, verificar que se envió una vez (prueba manual; nunca se probó para la
  HC general).
- **Histórico:** con una base del dispositivo migrada de hepatología y consultas viejas sin enviar, ninguna se envía al abrir
  la app, iniciar sesión ni volver la conexión (test); una se envía solo con la confirmación (RF-10).
- **Rechazo:** con el servidor devolviendo `422`, `403` y `404`, el dato queda y se ve el motivo (test con servidor falso).
- **Cierre:** una carga borrada no reaparece al cerrar (test en el servidor y en el E2E).
- **Antes de publicar la escritura:** E1 y E2 cerradas (D5).
- Producción: **fuera** de esta spec.

## Decididas (2026-11-03, usuario)
Cada una con la alternativa descartada.

- **D1 — Alcance de la edición.** La app crea consultas nuevas y edita o cierra **solo las que ella creó**; las de la web siguen
  en solo lectura. *Descartado:* continuar las abiertas propias de la web (o editar cualquiera propia) —queda fuera de alcance,
  subentrega futura con su spec—. Motivo: la lectura no trae las filas pendientes de confirmar de una consulta abierta de la
  web ni sus PDF, audio o diagnósticos, y continuarla arriesga duplicar lo pendiente.
- **D2 — Lo viejo sin enviar.** Nada sale solo; botón «Enviar a la web» consulta por consulta, con confirmación que muestra
  paciente, fecha y secciones; los antecedentes `activo = 0` no se envían nunca (RF-7 a RF-10). *Descartados:* lote (mandaría
  años de historial de golpe a producción y puede crear fichas) y «marcar como resuelto» (miente y pierde información).
- **D3 — Autorización para tocar el Laravel de hclinica.** Se autorizan **todas** las rutas de escritura juntas, para
  implementarlas y probarlas en la copia local; producción, aparte y con respaldo probado (D4). Orden: servidor local, app
  local, producción (aprobación aparte), recién después publicar la app. *Descartado:* aprobar por subentrega, que era la
  recomendación del planificador; el usuario eligió lo contrario. Por eso cada ruta lleva su subentrega y su condición de
  despliegue (ver «Dependencias externas»).
- **D4 — Respaldo, limpieza y cartera de prueba.** Primero se salda la deuda de limpieza de la copia local (datos de la L y de
  la 003); respaldo de `hc_clinica` local restaurado en una base descartable y verificado con suma de control; limpieza siempre
  por id, nunca `DROP`; datos de prueba creados por la propia prueba (paciente «PRUEBA SALUD360») con el médico de prueba
  local; en producción no se escribe hasta que el usuario indique médico y paciente de prueba y confirme un respaldo probado.
  *Descartado:* probar con un paciente real de la cartera con respaldo (restaurar tablas enteras pisaría el trabajo de otros
  médicos).
- **D5 — Partición y publicación.** Orden E0, E1, E2, E3, E4; E5 (licencia) aparte; no se publica la escritura antes de cerrar
  E1 y E2. *Descartados:* dejar el cierre para el final (deja consultas abiertas en la web) y publicar apenas E1 (hepatología
  quedaría sin serologías ni estudios).
- **D6 — Consulta abierta en la web al crear una nueva.** La app avisa con el nombre de quien la abrió y crea igual (RF-18).
  *Descartados:* bloquear (hay muchas abandonadas) y reutilizar la de la web (tomaría trabajo de otro médico).
- **D7 — Conflictos.** Gana la última escritura por campo; no se envía lo que el médico no tocó; sin pantalla de conflictos
  (RF-34). *Descartado:* control optimista por versión (columna de versión, servidor y pantalla de conflicto).
- **D8 — Migración local.** Aprobada `9.sqm` (esquema v10) con `remoto_id` **al final** en `laboratorio` y `pendiente`, a
  implementar cuando llegue E3. El número exacto se reserva mirando todas las ramas (`origin/main` llega a `5.sqm`;
  `hc-general`, a `8.sqm`; la guía `docs/trabajo-por-historia-clinica.md` ya está en `origin/main`) justo antes de crearla, y se
  vuelve a preguntar si otra rama ya lo tomó. Sin vuelta atrás en un dispositivo migrado; 001 y 002 se publican juntas.
  *Descartados:* renombrar las filas enviadas al id `hcc-…` sin migrar (quedarían «de la web» y no se podría borrar la carga
  enviada) y dejar el laboratorio fuera de la entrega.
- **D9 — Lo que la web no tiene.** Se aplica la decisión del 2026-10-09: tabaquismo, frecuencia cardíaca, circunferencia
  abdominal, diagnósticos, audio y antecedentes personales salen del catálogo de las consultas que se envían; lo ya cargado
  queda en solo lectura con el aviso «No se envía a la web» (RF-3). El mapa de nombres vive en el servidor, también al revés
  (002 Q12 = A, ratificada). *Descartados:* guardar tabaquismo en «otro» (inventa un significado en la web) y dejarlo sin
  enviar (el médico creería que se guardó). Esto cambia el catálogo de la HC general que define la 001: el plan lo declara como
  cambio sobre la 001 (y sobre el test que hoy fija `tabaquismo` en el catálogo).
- **D10 — Pendientes.** Se envían como el texto único por consulta de la web, sin «Resuelto» (RF-43). *Descartado:* simular
  «resuelto» dentro del texto o agregar una columna a la web.
- **D11 — Licencia.** La HC general **sí** cobra licencia (la web ya la exige en cada pedido); aviso y panel en E5, aparte; se lee
  y escribe la licencia de cada servidor sin copia local por HC, reutilizando la 004; sin segunda migración (RF-47 a RF-49).
  *Descartado:* agregar la especialidad a la tabla local de licencias.
- **D12 — Adjuntos y PDF.** En E4, al final y lo primero que se recorta; el límite es el menor entre la web y el hosting,
  medido en local y en producción; nunca se envían a clínica adjuntos de otra HC del mismo paciente (RF-44 a RF-46).
  *Descartados:* meterlos en E1 (lo más frágil) y dejarlos solo en la web.
- **D13 — Reabrir.** Una consulta cerrada se puede reabrir solo mientras el cierre no se haya enviado (RF-25). *Descartado:*
  crear un `reabrir` en el servidor (la web no lo tiene).
- **D14 — Anular y cierre.** Anular una consulta ya enviada la da de baja (`activo = 0`), nunca la borra; el cierre confirma solo
  las filas pendientes y no copia el defecto de la web que resucita cargas borradas (RF-23, RF-26). *Descartados:* replicar el
  cierre de la web al pie de la letra y prohibir anular las enviadas (quedarían abiertas en la web).
- **D15 — Elegir ficha al enviar.** Reutiliza la pantalla de la 003, abierta desde la consulta en espera (RF-13). *Descartado:*
  una pantalla nueva en la lista de pacientes (la 003 ya descartó resolver toda la cartera).

## Dudas abiertas
- **D16 [NECESITA ACLARACIÓN] — Histórico con secciones sin envío (choque entre D2 y D5).** «Enviar a la web» se ofrece desde
  E1, pero una consulta vieja puede tener laboratorio, pendientes o adjuntos, que recién se envían en E3 y E4. Propuesta del
  planificador: el botón se habilita solo cuando todas las secciones **con datos** de esa consulta tienen envío habilitado, y si
  no, dice cuáles faltan (nunca se envía una parte y se la da por enviada). Alternativa: enviar solo las habilitadas y dejar el
  resto «en espera» (más complejo, y la consulta quedaría partida entre la app y la web).
- **D17 [NECESITA ACLARACIÓN, menor] — RF-50 de la 002** («lo único que se registra es la sesión»): el middleware escribe también la
  licencia vencida y los vínculos pendientes. Sigue como propuesta pendiente de la 002 (no se aplicó); se decide si se afloja la
  frase o se deja.
- **D18 [NECESITA ACLARACIÓN, menor] — Límite de adjuntos «medido en producción» (D12 frente a D4).** Medirlo con una subida de
  prueba es escribir en producción, que D4 impide hasta tener respaldo probado y paciente de prueba. Propuesta: medirlo en E4
  contra la copia local y, en producción, leer los límites del hosting con una ruta de solo lectura; recién después de la
  aprobación de producción, una subida de prueba con el paciente de prueba.
- **D19 [NECESITA ACLARACIÓN, menor] — Consecuencias que el planificador dedujo y conviene confirmar:** (a) una consulta propia
  cerrada cuyo cierre ya se envió queda solo para leer (D1 + D13); (b) «Resuelto» no se ofrece en pendientes que se envían (D10 +
  criterio de D9); (c) «no se despliega» hasta cerrar la subentrega significa hacia producción, y todas las rutas existen en la
  copia local desde el principio (D3). Además, para el plan: el panel de licencias muestra hoy una licencia por médico; con dos
  historias (D11) la pantalla cambia.

---

## Anexo: lo verificado en el código (referencias para el plan; no son requisitos)
Rutas relativas a este repo; `hclinica` = `C:\Users\flor\rebonline\hclinica` (solo se leyó). No se ejecutó nada contra bases.
Nota: `docs/trabajo-por-historia-clinica.md` no estaba en `docs/` de esta rama; se leyó la copia de
`C:\Users\flor\rebonline\salud360-docs\docs\` (el usuario indica que ya está en `origin/main`).

### Lo que la L ya construyó y la E tiene que abrir
- `HcBackend.soloLectura` y el candado del motor de envío: `core/data/.../repos/HcBackend.kt:37`; `core/data/.../sync/HcApiSync.kt:68-96`
  (`esDeSoloLectura`, `marcarSucia`, `empujarYa`), `:120` (`drenar` sale si el backend es de solo lectura), `:230`
  (`consultasDeAntecedentesPendientes`). `HcClinicaBackend.soloLectura = true` (`repos/HcClinicaBackend.kt:85`) y todo método
  de envío lanza `UnsupportedOperationException` (`:377-395`). El cliente de clínica es `HcApiSoloLectura` (no tiene `post`).
- Guardas por identificador en `HcRepository` (`repos/HcRepository.kt:129-164, 183-197, 221-225, 251-257, 290-359`): lo que
  empieza con `hcc-` o es una consulta con `remoto_id` de un backend de solo lectura no se escribe. `esSoloLecturaWeb`
  (`hcgeneral/SoloLecturaDeLaWeb.kt:20-27`) y `Consulta.esDeLaWeb()` = `remoto_id` no vacío **o** prefijo `hcc-`
  (`hcgeneral/LecturaClinica.kt:93`). Efecto para la E: una consulta propia, una vez enviada (con `remoto_id`), pasa a contarse
  «de la web» en la lista (`hcgeneral/ConsultaWeb.kt:16`); hay que decidir cómo se agrupa y que siga editable mientras esté abierta (D1).
- `abrirConsulta` con backend de solo lectura usa `consultaAbiertaLocal` (ignora las de la web): `repos/HcRepository.kt:117-127`.
- La lectura **no refresca** una consulta propia ya enviada: `aplicarLista` la trata como `intocable` si el dispositivo ya tiene
  otra fila con su `remoto_id` (`repos/HcClinicaBackend.kt:187-189`) y `traerConsulta` solo acepta ids `hcc-c<id>` (`:304-309`).
  → RF-33 es trabajo nuevo.
- La lectura empareja las filas del historial **por id**, no por identificador de la web: `registroPorId(web.id)`
  (`repos/HcClinicaBackend.kt:264-267`), `laboratorioPorId` (`:257-260`), `pendientePorId` (`:277-281`). Una fila enviada por la
  app (id propio, id de la web aparte) reaparecería como fila `hcc-…` duplicada. → RF-33 (emparejar) es trabajo nuevo.

### El motor de envío y lo que hoy no cubre
- Espera de 4 s y reintentos de 15 s, 60 s y 300 s, y después se rinde hasta el próximo disparo: `sync/HcApiSync.kt:189-205, 238-244`.
- **Solo registra el error en el log**: no hay motivo visible ni distinción entre error que se arregla reintentando y rechazo
  (`:189-205`). → RF-30 y RF-31 son trabajo nuevo. `HcBackend` no tiene laboratorio ni pendientes ni estado de rechazo (`repos/HcBackend.kt:27-102`).
- `empujarTodo` y `recontar` miran consultas, secciones, examen, adjuntos y antecedentes/listas, **no** laboratorio ni pendientes
  (`sync/HcApiSync.kt:100-111, 208-216`); `laboratoriosDirty`/`pendientesDirty` solo los usa el motor viejo hacia turnos
  (`sync/SyncAdapters.kt:104, 114`, 404 conocido). → RF-31 los incluye.
- Los antecedentes se mandan sin mirar `activo` (`sync/HcApiSync.kt:133-134`) → RF-9.
- El examen físico se arma con `HcPediatriaBackend.aCampos` (de pediatría) (`sync/HcApiSync.kt:180`) → clínica necesita el suyo.
- `archivosPendientesDeConsulta` toma por paciente los adjuntos sin consulta de cualquier HC (`core/database/.../HistoriaClinica.sq:220-224`;
  límite ya fijado en un test de la L) → RF-46.
- Pediatría es el modelo y no se toca: `repos/HcPediatriaBackend.kt:76-140` (`asegurarConsulta`, secciones, examen, registros,
  estado). Pediatría **no** envía laboratorio ni pendientes (nunca los tuvo).

### Esquema local
- Migraciones `1.sqm` a `8.sqm` en `core/database/src/commonMain/sqldelight/com/salud360/core/database/migrations/` (v9). La
  siguiente en esta rama es `9.sqm` (v10) (D8); el número se confirma mirando todas las ramas justo antes de crearla (no se pudo
  hacer con `git` en la sesión que escribió esto).
- `laboratorio` y `pendiente` **no tienen `remoto_id`** (`HistoriaClinica.sq:158-171, 227-237`); `registro_clinico` y `archivo` sí
  (`:141-143, 199-203`). `pendiente` y `archivo` **no tienen especialidad**: sus filas no distinguen la HC → riesgo de enviar a
  clínica pendientes o adjuntos de otra HC del mismo paciente (RF-46).
- La tabla local de licencias tiene como clave solo el médico (`Auth.sq:94-109`; `core/model/.../auth/Medico.kt:47-54`): no
  distingue la HC. Con D11 no se toca; la licencia de clínica no se copia localmente.

### El catálogo viejo todavía ofrece lo que la web no tiene
- `tabaquismo` en los antecedentes de clínica (`especialidades/clinica/.../ClinicaMedica.kt:58`); frecuencia cardíaca y
  circunferencia abdominal en el examen físico (`:63`, y `:110` para hepatología); diagnósticos y audio en las secciones por
  defecto (`:42, :48-49, :78`). La decisión del 2026-10-09 («la app muestra lo que tiene la web, ni más ni menos») no se aplicó.
  La L lo dejó (Q13) y un test lo fija (`especialidades/clinica/.../LecturaClinicaCatalogoTest.kt:136-138`). Con D9 hay que
  cambiar el catálogo y ese test.

### La web de hclinica (cómo escribe hoy)
- **Crear una consulta (camino activo)** `crearNuevaConsulta` crea siempre una consulta nueva `activo = 2` con `medico_id` del
  usuario, **sin mirar si ya hay una abierta**, y para el perfil 2 crea además filas vacías en motivo y en los cinco estudios;
  también **escribe `medico_infos`** (`hclinica/app/Http/Controllers/MedicoController.php:3008-3050`, `inicializarCampos` `:2954-3006`).
  Solo el camino viejo marcado DEPRECATED reutilizaba la abierta (`:282-320`, `:252-275`). → contradice el «reutiliza» del plan viejo de la 002.
- **Cerrar** `establecerActivo` pone `activo = 1`, cambia `created_at` por la fecha enviada y la edad, y llama a
  `setActivoLaboratorioGeneral/2`, `setActivoPeso`, `setActivoExamenesComplementarios`, `setActivoInterconsulta`
  (`:3442-3461`), que ponen `activo = 1` en **todas** las filas de la consulta, también las borradas (`:2225-2280`). → RF-23.
  Exámenes e interconsultas nacen `activo = 2` (`:2095, :2321`). El laboratorio y el peso se borran con `activo = 0` (`:3694-3730`).
- **No existe reabrir**: ningún camino pone `activo = 2` a una consulta cerrada (solo los de creación). → RF-25.
- **Pendientes**: `aux_pendientes` guarda una fila de texto por (paciente, consulta) (`:4808-4842`). → RF-43.
- **PDF**: `consulta_pdfs`, hasta 15 MB, ruta `img/<usuario>/consulta_pdf` creada con `mkdir` (`:3592-3655`); en el hosting de
  pediatría `mkdir` no estaba permitido y las fotos van a una carpeta creada a mano (`docs/hc-pediatria-estado-y-plan.md`). → RF-44.
- **Mapa inverso**: `MapaClinica::columnaWeb` ya existe (`hclinica/app/Services/Salud360/MapaClinica.php:253-258`) y el mapa está
  contrastado con la base; Q12 = A se cumple al revés sin mover nada.
- **Licencia**: el middleware exige licencia vigente en cada pedido y corta con `403 licencia_vencida` también sin fila;
  `auth/perfil` ya devuelve `licencia` (vence, aviso, por vencer) y el administrador no tiene (`hclinica/md/API_SALUD360_CLINICA.md:63-103, 146-168`;
  `AuthController.php:50-53`). **No hay rutas `licencias`** (`hclinica/routes/api.php:34-64`). La app no lee ni administra la
  licencia de clínica: `HcClinicaBackend` no implementa `avisoDeLicencia` ni `traerLicencias`/`guardarLicencia` (usa los defaults de
  `HcBackend`) y `AdminRepository` ignora los backends de solo lectura. La 004 cubre solo pediatría.
- **Rutas hoy**: solo `GET` de consultas, `GET pacientes/{id}/historia`, pacientes y enfermedades (`routes/api.php:49-63`); el
  `.md` dice «Consultas, escritura… pendiente» (`md/API_SALUD360_CLINICA.md:1116-1121`).

### No verificado (hay que ver en la copia local o en producción)
- Si la web de cada perfil abre sin errores una consulta creada sin las filas vacías de `inicializarCampos` (RF-17).
- Qué versión de hclinica corre en producción, su esquema real, modo estricto y respaldo restaurable (ya listado en la 002).
- Que no exista otro camino de la web que reabra, dé de baja o cierre consultas (se leyó `MedicoController`, no las vistas).
- El límite real de subida del hosting para PDFs y fotos y los permisos de la carpeta de adjuntos en producción (D18).
- Cómo muestra la web una consulta de la app que quedó con filas pendientes de confirmar si nunca se cierra.
- La numeración global de migraciones frente a las otras ramas de HC (D8).
