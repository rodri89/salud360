# Spec 002 — La HC general (clínica) contra su propia base

Estado: parte 1 (pacientes y cartera, RF-8 a RF-14 y RF-35 a RF-37) aprobada el 2026-10-19; el resto en borrador.
**La parte 2 (consultas) se parte en la Entrega L, solo lectura (RF-38 a RF-63, redactada abajo), aprobada por el
usuario el 2026-10-28 (con Q15 = nunca borrar), y la Entrega E, escritura (borrador sin aprobar, sin tareas).** Ver la
sección "Parte 2 en entregas".
Depende de la spec 001 (qué secciones ve cada médico lo decide su perfil). La parte 1 (con RF-35 a RF-37) se
planifica en `plan-parte1.md` y la usa la spec 003 (enfermedades); la Entrega L se planifica en `plan.md`; la
Entrega E se planifica después de ver funcionar la L.

## Contexto y objetivo
La HC general (la web `hclinica`, clínica médica con sus perfiles: clínica general, hepatología, enfermedades
crónicas…) la usan los médicos hoy. En la app existe, pero guarda **solo en el dispositivo**: lo que el médico
carga en el teléfono no lo ve en la web, y al revés. Con pediatría ya se resolvió lo mismo: la app lee y escribe la
misma base que la web, con el ingreso único de turnosonlinebb.

El objetivo es repetirlo para la HC general: **una sola historia clínica**, la misma en la web y en la app, para
cualquier perfil, incluido el médico que trabaja solo con la historia clínica y no tiene turnos.

## Usuarios
- **Médico de la HC general con agenda** en turnosonlinebb, con cualquier perfil.
- **Médico sin agenda** (especialista que solo usa la historia clínica): está dado de alta en turnosonlinebb pero
  no se publica para sacar turno.
- **Administrador**: da de alta médicos, les habilita historias clínicas y gestiona sus licencias.
- Las secretarias quedan fuera de esta spec.

## Historias de usuario
- HU-1. Como médico de clínica, quiero cargar la consulta en la app y verla en la web (y al revés) para no llevar
  dos historias clínicas.
- HU-2. Como médico con perfil de hepatología, quiero cargar serologías, Child-Pugh, estudios y laboratorios desde la app para atender
  sin la computadora.
- HU-3. Como médico sin agenda, quiero ver mis pacientes y crear pacientes nuevos sin que me aparezca una agenda
  vacía ni me publiquen para sacar turno.
- HU-4. Como médico, quiero seguir cargando sin señal y que se envíe solo cuando vuelva.
- HU-5. Como administrador, quiero dar de alta un médico solo de historia clínica y ver y cambiar su licencia de
  cada historia clínica.

## Definiciones
- **La web**: la historia clínica `hclinica` que los médicos usan hoy en el navegador. Es la dueña de los datos clínicos.
- **HC general / perfil**: ver la spec 001. Hepatología no es otra historia clínica: es un perfil de la HC general.
  Esta spec hace que las secciones del catálogo lean y escriban la base de la web; cuáles ve cada médico lo decide
  su perfil.
- **Cartera**: los pacientes que la web tiene asignados al médico (activos, en la web y en la asignación).
- **Ficha de la web**: la fila del paciente en la base de la web (sin padre ni madre; con familiar de contacto).
- **Vínculo del paciente**: a qué ficha de la web corresponde un paciente de la app, por historia clínica (el de
  la HC general no es el de pediatría). Se busca por el paciente de turnosonlinebb y, si no, por documento.
- **Documento válido**: solo dígitos (se aceptan puntos y espacios como separadores, que se quitan), entre 1000 y
  2147483647 (el máximo que guarda la web) y distinto de 99999, el paciente con el que turnosonlinebb bloquea
  horarios. Vacío, con signo menos, con letras u otros signos no es válido.
- **Ficha posible**: una ficha activa de la web con el mismo documento que no esté vinculada con otro paciente de
  turnosonlinebb.
- **Laboratorio longitudinal**: cargas fechadas de analitos que se muestran como tabla, una columna por fecha.
- **Consulta abierta / cerrada**: en curso o confirmada por el médico; lo pendiente de confirmar se confirma al cerrar.

## Requisitos funcionales

### Acceso
- RF-1: CUANDO un médico con la HC general habilitada en turnosonlinebb abre la historia clínica de un
  paciente, EL SISTEMA muestra los datos de la web para ese paciente, incluidas las consultas cargadas desde la web.
- RF-2: SI el médico no tiene esa historia clínica habilitada en turnosonlinebb, ENTONCES EL SISTEMA no la ofrece
  y, si llega a pedirla, muestra "historia clínica no habilitada".
- RF-3: SI el médico no está vinculado en la web, ENTONCES EL SISTEMA muestra que falta vincularlo, nombrando la
  historia clínica, y no pierde lo cargado en el dispositivo.
- RF-4: SI la licencia del médico para esa historia clínica está vencida o inactiva, ENTONCES EL SISTEMA no le
  deja abrirla y muestra "licencia vencida", con la misma regla que aplica la web al ingresar.
- RF-5: CUANDO la licencia esté dentro del período de aviso, EL SISTEMA avisa al médico la fecha de vencimiento de
  esa historia clínica.
- RF-6: EL SISTEMA deja entrar siempre al administrador, sin licencia.
- RF-7: EL SISTEMA guarda y muestra una licencia por médico **y por historia clínica**: cambiar la de clínica no
  cambia la de pediatría.

### Médico sin agenda y pacientes (parte 1)
- RF-8: CUANDO el médico (no la secretaria ni el administrador) abre su lista de pacientes, EL SISTEMA trae y
  agrega a la lista su cartera de cada historia clínica con base propia que tenga habilitada (pediatría y la HC
  general; una habilitación de "hepatología" cuenta como de la HC general, spec 001 RF-29), tenga o no agenda;
  MIENTRAS no tenga agenda, EL SISTEMA no le muestra agenda ni recetas.
- RF-9: SI la cartera de una historia clínica no se pudo traer, ENTONCES EL SISTEMA muestra arriba de la lista,
  nombrando esa historia clínica, el motivo: sin conexión, sesión vencida, historia clínica no habilitada, médico
  no vinculado, licencia vencida u otro error (con el mensaje de la web); si la lista está vacía, el motivo
  reemplaza al "no hay pacientes". Las demás historias clínicas y lo que ya estaba en el dispositivo se siguen
  mostrando.
- RF-10: CUANDO un médico crea un paciente nuevo, EL SISTEMA lo registra en turnosonlinebb sin publicarlo para
  sacar turno (como hoy) y no crea su ficha en la web hasta que haga falta: EL SISTEMA resuelve el vínculo del
  paciente con la HC general la primera vez que algo lo necesita —al abrir una sección que lee de la web (spec
  003) o al enviar su primera consulta (resto de esta spec)—; busca su ficha por paciente de turnosonlinebb, si
  no por documento y, si no hay ninguna ficha con ese documento, la crea y lo suma a la cartera del médico.
- RF-11: CUANDO al resolver el vínculo hay más de una ficha posible, EL SISTEMA se queda con la única que coincide
  en fecha de nacimiento y apellido (sin distinguir mayúsculas, tildes ni espacios de más); SI no queda
  exactamente una, ENTONCES EL SISTEMA no vincula y le muestra al médico las fichas posibles (nombre, apellido,
  documento, fecha de nacimiento y cantidad de consultas) para que elija una; CUANDO elige, guarda ese vínculo y
  suma el paciente a su cartera. EL SISTEMA solo acepta elegir una ficha posible de ese mismo documento.
- RF-12: SI el documento del paciente no es válido, ENTONCES EL SISTEMA no busca ni crea la ficha en la web y
  muestra "El documento «<documento>» no sirve para la historia clínica: corregilo en la ficha del paciente".
- RF-13: EL SISTEMA solo completa los datos vacíos de la ficha de la web con los del paciente de la app: nunca
  reemplaza un dato cargado, ni por un valor vacío ni por otro distinto. Al traer la cartera, tampoco pisa la ficha
  de un paciente que ya está en el dispositivo.
- RF-14: CUANDO el administrador da de alta un médico con la HC general habilitada y sin agenda, EL SISTEMA lo crea
  en turnosonlinebb sin publicarlo para sacar turno.

### Consulta (cada sección, si está en el perfil del médico)
- RF-15: CUANDO el médico abre una consulta nueva de un tipo (consulta, telemedicina) y ya tiene una abierta del
  mismo paciente y tipo, EL SISTEMA reutiliza esa en vez de crear otra.
- RF-16: CUANDO el médico escribe en una sección de texto (motivo, conductas, nota, datos subjetivos, datos
  objetivos, hábitos), EL SISTEMA lo guarda y la web lo muestra en la misma consulta.
- RF-17: CUANDO el médico carga el examen físico, EL SISTEMA guarda peso, talla, IMC, TA y nota, y la web los muestra.
- RF-18: CUANDO el médico marca antecedentes familiares (con su detalle) o escribe antecedentes, EL SISTEMA los
  guarda donde la web los lee, y muestra los últimos cargados del paciente aunque vengan de otra consulta.
- RF-19: CUANDO el médico carga alimentación (hiposódica, diabetes, celíaco, otra, con detalle), EL SISTEMA la guarda.
- RF-20: CUANDO el médico carga laboratorio con una fecha, EL SISTEMA agrega esa carga a la tabla longitudinal del
  paciente, visible en la app y en la web; CUANDO borra una carga, deja de verse en ambas.
- RF-21: SI una carga de laboratorio no tiene fecha, ENTONCES EL SISTEMA usa la fecha de la consulta.
- RF-22: CUANDO el médico pide un examen complementario o una interconsulta, EL SISTEMA los guarda como pendientes
  de confirmar, y los confirma al cerrar la consulta, para que la web los muestre.
- RF-23: CUANDO el médico escribe pendientes del paciente, EL SISTEMA los guarda y la web los muestra.
- RF-24: CUANDO el médico cierra la consulta, EL SISTEMA la marca cerrada en la web; CUANDO la reabre, vuelve a
  quedar abierta.
- RF-25: EL SISTEMA muestra las consultas anteriores del paciente, incluidas las de la web; las de otro médico son
  de solo lectura.

### Secciones de hepatología (del catálogo; hoy las usa el perfil de hepatología)
- RF-26: CUANDO el médico carga serologías (16 marcadores, cada uno SI / NO / sin cargar), EL SISTEMA las
  guarda; un marcador no cargado queda "sin cargar", nunca "NO".
- RF-27: CUANDO el médico carga Child-Pugh (5 parámetros), EL SISTEMA guarda los puntos de cada uno y el total,
  y muestra la clase (A ≤ 6, B 7-9, C ≥ 10).
- RF-28: CUANDO el médico carga ecografía abdominal, videoendoscopía digestiva alta, biopsia hepática, TC/RMN
  o estudios cardiológicos (fecha + descripción), EL SISTEMA los guarda y muestra el historial del paciente de cada uno.
- RF-29: CUANDO el médico carga peso con fecha, EL SISTEMA lo agrega a la tabla de peso del paciente.

### Adjuntos
- RF-30: CUANDO el médico adjunta un PDF o una foto a la consulta, EL SISTEMA lo sube a la web y la web lo muestra;
  sin señal queda en el dispositivo y se sube después, una sola vez.
- RF-31: CUANDO el médico abre una consulta con adjuntos cargados desde la web, EL SISTEMA los muestra y permite abrirlos.
- RF-32: CUANDO el médico borra un adjunto, EL SISTEMA deja de mostrarlo en la app y en la web.

### Sin conexión
- RF-33: MIENTRAS no haya conexión, EL SISTEMA permite crear y editar consultas de la HC general, y las
  envía solo cuando vuelve la conexión, sin duplicarlas.
- RF-34: SI la web rechaza un cambio, ENTONCES EL SISTEMA lo conserva en el dispositivo y muestra el motivo.

### Vínculo de pacientes (parte 1, se agregó el 2026-10-19)
- RF-35: EL SISTEMA guarda el vínculo de cada paciente por historia clínica, sin dejarlo pendiente de envío (el
  dueño es la web), y no lo vuelve a pedir mientras lo tenga; un paciente que llegó en la cartera ya trae su
  vínculo.
- RF-36: SI al resolver el vínculo hay fichas activas con ese documento pero ninguna es posible (todas están
  vinculadas con otro paciente de turnosonlinebb), ENTONCES EL SISTEMA no vincula, no crea otra ficha y muestra
  "La ficha de la web con ese documento ya corresponde a otro paciente de turnosonlinebb: avisale al
  administrador".
- RF-37: CUANDO llega en la cartera un paciente que la web tiene vinculado con turnosonlinebb, EL SISTEMA lo
  guarda como el mismo paciente que llega de turnosonlinebb (una sola ficha en la app, aunque esté en la cartera de
  dos historias clínicas); SI no tiene ese vínculo, ENTONCES EL SISTEMA lo guarda con un id propio de esa historia
  clínica, siempre el mismo, y no lo empareja por documento.

## Requisitos no funcionales
- Lo que el médico hace en la app no cambia lo que tiene abierto en la web en ese momento.
- La app no multiplica los pedidos a la web: abrir una consulta trae todo junto; los guardados se agrupan.
- Funciona en web, Android e iOS.
- Ningún dato real de pacientes, ni tokens, en este repositorio.
- Consultas, pacientes y adjuntos de clínica nunca se mezclan con los de otra historia clínica en el dispositivo.

## Casos límite
- Médico con clínica y pediatría a la vez: cada una con su licencia, su cartera y sus consultas; si falla una
  cartera, la otra se muestra igual (RF-9).
- Paciente que está en la cartera de clínica y en la de pediatría: una sola ficha en la app si alguna de las dos
  webs lo tiene vinculado con turnosonlinebb; si ninguna, quedan dos (RF-37: no se adivina por documento).
- Secretaria que abre la lista de un médico, o el administrador: no se trae la cartera de la web ni se muestra un
  motivo (RF-8).
- Dos hermanos con el mismo documento (el del padre o la madre) y la misma fecha de nacimiento (mellizos): el
  apellido coincide, no se desempata y elige el médico (RF-11).
- Documento con puntos ("12.345.678"): vale; con signo menos, letras, 0, 1 (el que pone la web cuando no hay) o
  99999: no vale (RF-12).
- La ficha elegida o vinculada se corrige después en la web (se le cambia el documento): el vínculo guardado sigue
  valiendo; no se revalida (como en pediatría).
- La web da de baja la ficha vinculada: lo resuelve quien la usa (spec 003 RF-12; el resto de esta spec al
  planificarse).
- Paciente creado en la app sin conexión: el vínculo se resuelve recién con conexión, la primera vez que hace
  falta (RF-10).
- Consulta abierta abandonada en la web (hay muchas): la app no la toma como propia si es de otro tipo.
- Médicos con comportamiento especial en la web (uno ve las consultas de otro; otro usa un laboratorio reducido).
- Documento negativo, 0 o de bloqueo de turnos.
- Laboratorio con fecha vacía; dos cargas con la misma fecha.
- Adjunto que supera el límite del hosting; archivo que falta en el disco del servidor.
- Turnos caído: la sesión ya validada sigue valiendo un tiempo, sin saltearse la licencia.
- El cartel de licencia por vencer con más de una historia clínica.

## Fuera de alcance
- Secretarias.
- Grabación de audio y transcripción.
- Las secciones propias del perfil 3 (checklist de enfermedades) y del perfil 4 (antecedentes en su tabla propia):
  entran al catálogo en otra spec; mientras, esos perfiles se arman con las secciones que ya hay.
- Vacunas, screening y las secciones heredadas de pediatría que la web de clínica no usa.
- Consulta tipo "HC digitalizada" (fotos).
- Diagnósticos, frecuencia cardíaca, circunferencia abdominal y tabaquismo en clínica (la web no los tiene).
- Qué secciones muestra cada historia clínica y con qué nombre: es la spec 001.
- Migrar datos de la web a otra base, o cambiar las pantallas de la web.
- Avisar cuando el médico edita la misma consulta en la web y en la app a la vez.
- Revalidar o corregir un vínculo de paciente ya guardado, y unificar fichas duplicadas de la web.
- La cartera compartida de la web (un médico que ve los pacientes de otro): no se replica (spec 003, D17).

## Criterios de finalización
- Todos los RF con test en verde, o con su paso de verificación manual hecho, contra una copia local de la web.
- Prueba de punta a punta (como la de pediatría) que crea una consulta con todas las secciones, la lee de vuelta y
  deja todo como estaba.
- Una consulta cargada en la app se ve completa en la web, y una cargada en la web se ve completa en la app.
- Un médico sin agenda entra, ve su cartera, crea un paciente y le carga una consulta.
- Parte 1: un médico sin agenda con la HC general ve su cartera de la web; sin conexión o con la licencia vencida
  ve el motivo; un paciente nuevo se vincula (o se crea su ficha) la primera vez que hace falta; con dos fichas
  posibles se pueden pedir las candidatas y elegir una (la pantalla de elegir la estrena la spec 003, RF-9).
- Prueba sin señal: modo avión, cargar, recuperar la señal, verificar que se envió una sola vez.

## Parte 2 en entregas (la L, aprobada por el usuario el 2026-10-28; la E, borrador sin aprobar)

### Por qué
El usuario quiere ver funcionar el "ida y vuelta" de consultas entre la app y la web de la HC general antes de
decidir cómo se escribe. Hoy el servidor de la web no tiene ninguna ruta de consultas y la app guarda lo de la HC
general solo en el dispositivo. Primero se hace lo que no puede dañar nada: **leer**. La escritura (crear, editar y
cerrar desde la app) es otra entrega, que se decide después de ver la lectura andando.

Decidido por el usuario el 2026-10-28:
1. La primera entrega es **solo lectura**: la app muestra las consultas creadas en la web y no escribe nada en el
   servidor.
2. Las pruebas van en orden: **primero contra la copia local** (base local de la web, paciente de prueba) y recién
   después contra producción, siempre con un paciente de la cartera de prueba y sin escribir.
3. La escritura contra producción solo con respaldo previo y limpieza probada, y es otra entrega.

### Qué entrega cada una
| Entrega | Contenido | RF | Estado |
|---|---|---|---|
| **L** (lectura) | La app muestra las consultas de la web, sin escribir nada y sin borrar nada de lo ya traído | RF-1 a RF-4 en lo que toca a leer, RF-25 (solo lectura), RF-38 a RF-63 | Aprobada por el usuario el 2026-10-28 (incluye la tanda S del servidor) |
| **E** (escritura) | Crear, editar, cerrar y enviar desde la app; adjuntos; aviso de licencia | RF-15 a RF-24, RF-26 a RF-34, RF-5 a RF-7 | Borrador sin aprobar, sin tareas |

Reparto de los RF que ya existían (ninguno se pierde):
- **A la L**: RF-1 (mostrar las consultas de la web), RF-2 a RF-4 (habilitación, vínculo y licencia, que valen
  también para leer) y RF-25 (consultas anteriores, las de otro médico solo para leer). Su redacción final para la L
  está en RF-38 a RF-46, RF-51, RF-56 y RF-59.
- **A la E** (escritura): RF-15 (reutilizar la abierta), RF-16 a RF-19 (guardar textos, examen físico, antecedentes,
  alimentación), RF-20 y RF-21 (laboratorio), RF-22 y RF-23 (pendientes), RF-24 (cerrar y reabrir), RF-26 a RF-29
  (serologías, Child-Pugh, estudios, peso: cargar), RF-30 a RF-32 (adjuntos), RF-33 y RF-34 (sin conexión: crear y
  enviar después; rechazo de la web), RF-5 a RF-7 (aviso y datos de licencia por historia clínica). En la L se
  **ven** los datos que cargó la web en esas secciones (RF-40); lo que la L no incluye es **cargarlos** desde la app.
- RF-8 a RF-14 y RF-35 a RF-37 (parte 1) no cambian.

### Definiciones (solo de la entrega L)
- **De la web / traída de la web**: una consulta que existe en la base de la web y que la app guardó en el
  dispositivo para mostrarla. Se reconoce porque tiene identificador de la web.
- **Solo en este dispositivo**: consulta de la HC general cargada antes en el dispositivo, que nunca fue enviada.
- **Solo lectura**: se ve, no se edita, no se cierra, no se reabre ni se anula.
- **Paciente vinculado**: paciente de la app que ya tiene la ficha de la web que le corresponde (parte 1, RF-35).
- **Por paciente / por consulta**: la web guarda algunos datos por paciente y fecha (laboratorio, peso, estudios,
  pendientes, exámenes complementarios, interconsultas, antecedentes familiares) y otros por consulta (textos,
  examen físico, alimentación, serologías, Child-Pugh).
- **Sin pendiente de envío**: dato que la app guarda sin marcarlo para enviar.
- **Ya no figura en la web**: marca que la app pone a algo ya traído que la web dejó de mandar en una respuesta completa
  y correcta (RF-63). No lo oculta, no lo borra y no lo vacía; solo avisa que puede estar desactualizado.

### Requisitos funcionales — Entrega L (lectura)

#### Lo que se ve
- RF-38: CUANDO el médico abre la historia clínica de un paciente vinculado, EL SISTEMA trae de la web las
  consultas de ese paciente (de tipo consulta y telemedicina, cerradas y abiertas, propias y de otros médicos), de
  la más reciente a la más antigua, hasta 50 por vez con "Ver más" mientras haya más (Q9), y las
  muestra junto a las que ya estaban en el dispositivo, cada una con su origen (de la web o solo en este dispositivo).
- RF-39: CUANDO el médico abre una consulta traída de la web, EL SISTEMA trae todo su contenido en un solo pedido y
  lo muestra con las secciones del perfil del médico (spec 001, RF-20 a RF-22); lo que la consulta tenga fuera de su
  perfil se muestra solo para leer al final, como dice la spec 001 (RF-22).
- RF-40: EL SISTEMA muestra lo que la web guardó en estas secciones del catálogo: motivo de consulta, datos subjetivos,
  datos objetivos, conductas, nota, hábitos, diuresis y catarsis, somnia, examen físico (peso, talla, IMC, tensión
  arterial y nota), alimentación, antecedentes familiares, serologías de hepatitis, Child-Pugh, ecografía abdominal,
  videoendoscopía digestiva alta, biopsia hepática, estudios cardiológicos, TC / RMN, laboratorio general, laboratorio
  reducido, peso, exámenes complementarios, interconsultas y los pendientes del paciente. "Enfermedades" sigue como
  lo definió la spec 003. (Q1.)
- RF-41: EL SISTEMA trae los datos que la web guarda por paciente y fecha (laboratorio, peso, estudios, exámenes
  complementarios, interconsultas, pendientes, antecedentes familiares) como historial del paciente: en una sección
  con tabla o con "consultas previas" (laboratorio, peso, motivo y estudios), la consulta muestra lo suyo junto con
  las demás fechas del paciente, aunque el médico no haya abierto antes esas consultas, con las mismas reglas de
  cuándo se muestra una sección vacía que ya tiene la app.
- RF-62: EL SISTEMA no deja editar, borrar ni marcar como resuelto, desde una consulta cargada en el dispositivo, un
  dato que vino de la web (una carga de laboratorio, un pendiente), porque ese cambio no llegaría a la web y
  dejaría los dos lados distintos.
- RF-42: EL SISTEMA muestra cada dato de la web en la casilla, fila o columna que le corresponde por su significado
  aunque la web lo llame distinto (por ejemplo albúmina, fósforo, ácido úrico, celíaco, dependiente de terceros);
  ningún valor aparece en una casilla equivocada ni se pierde por una diferencia de nombre.
- RF-43: SI la consulta de la web tiene datos en algo que esta entrega no muestra (adjuntos PDF, audio, fotos,
  diagnósticos, antecedentes personales), ENTONCES EL SISTEMA lo avisa en esa consulta nombrando qué es y que se ve
  en la web, y no deja esa parte como si no tuviera datos; SI la consulta es de un tipo que esta entrega no muestra
  (historia digitalizada u otro), ENTONCES EL SISTEMA lo informa en la lista con la cantidad. (Q2.)
- RF-44: MIENTRAS una consulta de la web esté abierta, EL SISTEMA la muestra como "abierta en la web" con lo que ya
  está confirmado y avisa que lo pendiente de confirmar no se ve hasta que se cierre; no la ofrece como "consulta en
  curso" del dispositivo. (Q3.)
- RF-45: EL SISTEMA muestra toda consulta de la web solo para leer, sea abierta o cerrada, propia o de otro médico:
  sin editar, sin cerrar, sin reabrir y sin anular; las de otro médico dicen que las cargó otro médico (con su nombre,
  Q4).
- RF-46: EL SISTEMA distingue a la vista las consultas de la web de las "solo en este dispositivo", y "Nueva consulta"
  nunca devuelve, continúa ni reutiliza una consulta de la web. (Q14.)

#### Lo que NO se escribe
- RF-47: EL SISTEMA no envía a la web, desde la HC general, ningún dato en esta entrega —consultas, secciones, examen
  físico, listas, estados, adjuntos, laboratorios, pendientes ni antecedentes— ni lo deja programado para después:
  ni al abrir una consulta, ni al salir, ni al volver la conexión, ni al reintentar, ni al cerrar sesión.
- RF-48: EL SISTEMA deja exactamente como están los datos que ya había en el dispositivo para la HC general (incluidas
  las consultas que antes eran de hepatología y los antecedentes que la migración dejó desactivados): no los envía,
  no los borra, no los modifica y no los marca como enviados. (Q5.)
- RF-49: SI el paciente todavía no está vinculado con una ficha de la web, ENTONCES EL SISTEMA no busca, no crea ni
  completa su ficha en la web y no pide sus consultas, y muestra "Este paciente todavía no está vinculado con la
  historia clínica de la web". (Q8. La sección Enfermedades de la spec 003 sigue vinculando como ya lo hace; la L no
  suma ninguna llamada de ese tipo.)
- RF-50: CUANDO la web atiende un pedido de lectura de la app, EL SISTEMA no crea, no modifica ni borra ningún dato
  clínico ni de pacientes, ni lo que la web usa para saber qué paciente y qué consulta está mirando el médico en su
  pantalla; lo único que se registra es la sesión.
- RF-51: EL SISTEMA responde las lecturas solo a médicos, solo de pacientes de su cartera; a un administrador, a una
  secretaria o por un paciente fuera de la cartera responde que no corresponde, sin devolver datos.

#### Lo que se guarda en el dispositivo
- RF-52: EL SISTEMA da a cada cosa traída de la web un identificador propio de la HC general que no coincide con el de
  otra historia clínica ni con lo cargado en el dispositivo, y traer lo mismo dos veces no lo duplica.
- RF-53: EL SISTEMA guarda lo traído de la web sin pendiente de envío.
- RF-54: SI el médico tiene en el dispositivo un dato sin enviar para la misma casilla, campo o consulta que llega de
  la web, ENTONCES EL SISTEMA conserva el del dispositivo y no lo toca.
- RF-55: EL SISTEMA nunca reemplaza ni borra un dato del dispositivo por un valor vacío, ni por uno que llegó de una
  respuesta con error, incompleta o dudosa. Vale también para una respuesta completa y correcta: que la web mande un
  valor vacío o no lo mande no vacía lo que la app ya tenía (RF-63).
- RF-63 (Q15 = nunca borrar): SI algo que la app ya trajo de la web deja de figurar en una respuesta completa y correcta
  —una consulta que desaparece de la lista, se da de baja o se anula allá; una sección o un texto que la web vació; una
  carga de laboratorio, un estudio, un examen, una interconsulta, un pendiente o un antecedente que la web ya no tiene—,
  ENTONCES EL SISTEMA lo conserva en el dispositivo, lo sigue mostrando y lo marca como "Ya no figura en la web"
  (en la lista, en la consulta y en la sección o fila que corresponda). Ninguna operación de la lectura (abrir,
  actualizar, reintentar, quedar sin conexión, recibir un error) borra, vacía ni oculta filas ya traídas. La marca solo
  se pone sobre lo que esa respuesta cubre (una consulta que no vino porque está en una página que no se pidió no se
  marca), y se quita sola si la web vuelve a mandar el dato, que entonces se muestra con el valor de la web.

#### Conexión, frescura y pedidos
- RF-56: MIENTRAS no haya conexión (o falle la lectura), EL SISTEMA sigue mostrando lo traído antes y dice, nombrando
  la historia clínica, el motivo: sin conexión, sesión vencida, no habilitada, médico no vinculado, licencia vencida,
  demasiados pedidos u otro (con el mensaje de la web), como en RF-9.
- RF-57: EL SISTEMA hace como máximo 2 pedidos a la web al abrir la historia de un paciente y 1 al abrir cada
  consulta; no repite el de la lista del mismo paciente antes de un minuto salvo que el médico pida "Actualizar";
  nunca pide nada mientras el médico escribe; SI la web responde "demasiados pedidos", ENTONCES no reintenta solo y
  lo avisa.
- RF-58: EL SISTEMA trae datos de la web al abrir la historia del paciente, al abrir una consulta y cuando el médico
  pide "Actualizar"; no los refresca por su cuenta en otro momento.

#### Lo que no cambia
- RF-59: EL SISTEMA aplica a la lectura la habilitación, el vínculo del médico y la licencia de RF-2 a RF-4: sin esa
  historia clínica habilitada, sin vínculo o con la licencia vencida no trae nada.
- RF-60: EL SISTEMA no cambia el comportamiento de pediatría ni de ninguna otra historia clínica por esta entrega
  (spec 001, RF-33).
- RF-61: EL SISTEMA no cambia el panel de licencias ni ninguna otra pantalla por registrar la lectura de la HC general.

### Requisitos no funcionales — Entrega L
- Nada de lo que hace la app en esta entrega cambia lo que el médico tiene abierto en la web.
- La web tiene un límite de 60 pedidos por minuto por dirección de red y un hosting con pocas conexiones: la app
  agrupa (RF-57) y la web responde cada lectura con pocas consultas a su base.
- Todo lo traído se puede ver sin conexión. Funciona en web, Android e iOS.
- Ningún dato real ni token en el repositorio, ni en los tests ni en las pruebas documentadas: los datos de prueba
  son inventados ("PRUEBA SALUD360").

### Casos límite — Entrega L
- Paciente vinculado sin consultas en la web: la historia queda como estaba, sin aviso de error.
- Paciente de la cartera del médico con consultas de otro médico: se ven, solo para leer (la web también las lista).
- Paciente que el médico no tiene en su cartera: la web responde que no corresponde (RF-51); la app lo muestra como
  motivo, no como "sin consultas".
- Consulta de la web de un tipo que la app no muestra, o con fecha vacía o inválida: se informa en la lista (RF-43); no
  se inventa una fecha.
- Consulta de la web abierta hace mucho (hay muchas abandonadas): se ve como abierta en la web, solo lectura.
- Consulta que desaparece entre pedir la lista y abrirla: la app avisa que ya no está, deja lo guardado y la marca
  "Ya no figura en la web" (RF-63).
- Consulta ya traída que la web da de baja, anula o borra, o texto que la web vacía: queda en el dispositivo, se sigue
  viendo con su contenido y lleva la marca (RF-63). Una consulta dada de baja que la app nunca había traído no se
  trae (Q11).
- Dos cargas de laboratorio con la misma fecha: se ven las dos; una con fecha vacía no se muestra y se cuenta en el aviso.
- Un valor de la web que no tiene casilla en la app, o una casilla de la app que la web no tiene: no se inventa
  nada; el primero se informa como "no se muestra" y la segunda queda vacía.
- Texto con tildes, ñ, emojis o saltos de línea: llega igual que en la web.
- La web responde un error del servidor (por ejemplo una columna que no existe en producción): se muestra el
  motivo y no se guarda nada a medias.
- Respuesta cortada a la mitad: no se guarda nada de esa consulta (todo o nada).
- Sesión que vence mientras se lee: se dice "sesión vencida" y se conserva lo traído.
- Turnos caído con sesión ya validada: la lectura sigue un tiempo (spec 002, caso "turnos caído").
- Médico con comportamiento especial en la web (uno ve las consultas de otro): no se replica, cada médico lee su cartera.
- Base del dispositivo ya migrada de hepatología con consultas viejas sin enviar: no se envía ninguna al abrir cualquier
  consulta (RF-47, RF-48).

### Fuera de alcance — Entrega L
- Escribir en la web de cualquier forma (es la Entrega E).
- Adjuntos (PDF, fotos), audio, historia digitalizada, diagnósticos y antecedentes personales: se avisa que existen
  (RF-43), no se muestran.
- Aviso de licencia por vencer y administración de licencias de la HC general (RF-5 a RF-7).
- Las secciones propias de los perfiles 3 y 4 de la web (checklist de enfermedades ya está en la spec 003).
- Corregir la forma en que pediatría marca el médico de las consultas ajenas (Q17).
- Sacar "tabaquismo" del catálogo de clínica (decidido el 2026-10-09 y no aplicado en el código; Q13).
- Probar o escribir contra producción: la L solo lee y tiene una etapa de producción aparte, con aprobación aparte.

### Criterios de finalización — Entrega L
- Cada RF de lógica (RF-40 a RF-42 en cuanto a nombres, RF-45, RF-47 a RF-49, RF-52 a RF-58, RF-61 a RF-63) con test en
  verde; para RF-63, el test "una consulta que desaparece de la respuesta queda en el dispositivo y se marca" (y lo
  mismo para un texto vaciado, una carga de laboratorio y un pendiente).
- Contra la copia local: un paciente de prueba con consultas creadas en la web (cerrada, abierta, de otro médico,
  telemedicina, con y sin laboratorio, estudios, serologías y Child-Pugh) se ve en la app sección por sección igual
  que en la web, con la lista de comprobación del `tasks.md`.
- Contra la copia local: antes y después de leer, las tablas de la web que la lectura toca tienen el mismo contenido
  (verificado con suma de control) y el registro de accesos del servidor no tiene ningún pedido que no sea de lectura.
- Contra la copia local: una consulta ya traída que se da de baja en la web (y un texto que se vacía) sigue en la app,
  con su contenido y la marca "Ya no figura en la web" (RF-63).
- Con una base del dispositivo migrada de hepatología y consultas viejas sin enviar, abrir historias y consultas no
  envía nada (test) y esas filas quedan como estaban.
- Prueba manual del usuario en `devWeb` con la lista del `tasks.md`.
- Después, y solo con aprobación aparte: la misma lectura contra producción con un paciente de la cartera de prueba,
  sin escribir, con la lista de comprobación del `tasks.md` (etapa de producción).

### Entrega E (escritura) — borrador, sin tareas
Queda como estaba redactada arriba (RF-15 a RF-24, RF-26 a RF-34, RF-5 a RF-7). Antes de planificarla hay que:
decidir qué se hace con lo viejo sin enviar del dispositivo (confirmación, lote o marcarlo resuelto), probar la
restauración de una base de producción respaldada, fijar la limpieza de lo que dejen las pruebas, decidir
"tabaquismo" y el mapa de nombres en sentido inverso (Q12, Q13), y aprobar la migración que guarde el identificador de
la web en laboratorio y pendientes (necesaria solo para escribir). La escritura contra producción solo con respaldo
previo y limpieza probada.

## Decisiones tomadas (2026-10-09)
- La app muestra lo que tiene la web, ni más ni menos. Diagnósticos, frecuencia cardíaca y tabaquismo (este último
  está comentado en la web) estaban en la app porque su definición de clínica se armó juntando las siete historias
  clínicas viejas, no copiando la web: salen de clínica. Lo que un médico ya haya cargado ahí en el dispositivo no
  se borra (spec 001, casos límite).
- Los nombres distintos de una misma sección (por ejemplo, la nota de antecedentes rotulada "Antecedentes
  personales" en un perfil y "Antec. personales patológicos" en otro) se resuelven con el nombre por perfil de
  la spec 001; el dato se guarda donde lo guarda la web.
- Se entrega junto con la spec 001, en una rama, y se une a `main` cuando las dos estén listas.
- Una licencia o habilitación de "hepatología" que ya exista cuenta como de la HC general (spec 001, RF-29).
- La consulta tipo "HC digitalizada" (fotos) queda fuera por ahora.

- Hepatología no es una historia clínica aparte: todo es la HC general y cada médico tiene un perfil (spec 001).
- Los perfiles 3 y 4 los arma el administrador con la pantalla de la spec 001.
- 2026-10-19: la vinculación de pacientes y la cartera (RF-10, RF-11) se hacen antes que el resto de la 002, porque
  la spec 003 (enfermedades) las necesita.

## Decisiones tomadas (2026-10-19)
- P1. Parte 1 = RF-8 a RF-14 y RF-35 a RF-37, copiando lo de pediatría. No activa el envío de consultas de la HC
  general: solo la cartera y el vínculo.
- P2. El vínculo se resuelve cuando algo lo necesita (abrir una sección que lee de la web, enviar la primera
  consulta), no solo al enviar la primera consulta, para que la spec 003 (D3) lo use (RF-10).
- P3. Elegir ficha: la parte 1 deja la lógica y los datos (candidatas, elegir, guardar); la pantalla la pone quien
  lo necesita, primero la sección Enfermedades (spec 003 RF-9). Descartado: una pantalla de "elegir ficha" en la
  lista de pacientes, que obligaría a resolver el vínculo de toda la cartera.
- P4. Solo se puede elegir una ficha posible del mismo documento (RF-11). Pediatría aceptaba cualquier id, lo
  que dejaba sumar a la cartera cualquier paciente de la web: no se copia.
- P5. Las fichas vinculadas con otro paciente de turnosonlinebb no son posibles (RF-11, RF-36). Descartado copiar
  pediatría, que las ofrece y después rechaza la elección.
- P6. Documento válido con puntos y espacios como separadores; signo menos o letras, no (definición). Pediatría
  quitaba cualquier no dígito y aceptaba "-12345678" o "12345678A".
- P7. RF-9 nombra cada historia clínica y no oculta la lista: el motivo va arriba y la lista sigue con lo que hay.
- P8. La cartera solo se trae para el médico de la sesión y solo de las historias clínicas que tiene habilitadas
  (RF-8): con el arreglo de RF-9, pedirla de más mostraría "no habilitada" o "sin permiso" a quien no corresponde.
- P9. Sin unificar por documento a un paciente que está en dos carteras sin vínculo con turnosonlinebb (RF-37).

## Decidido (2026-10-19, aprobación de la parte 1)
- PD1. Pediatría tiene en producción los mismos dos agujeros que no se copian (P4: elegir cualquier ficha; P6:
  documento con signo o letras). Decisión del usuario: **sí** se corrigen también en `hc_pediatria` (rama
  `salud360-api`), como tarea aparte al final de la tanda de servidor (E1.5), sin cambiar lo que responde a la app.
  Commits locales; subirlo a producción lo decide el usuario después.

## Decidido (2026-10-28)
- D1. La parte 2 se parte en Entrega L (solo lectura) y Entrega E (escritura). La L es la primera.
- D2. Las pruebas de la L van primero contra la copia local y recién después contra producción, con un paciente de la
  cartera de prueba y sin escribir.
- D3. La escritura contra producción (Entrega E) solo con respaldo previo y limpieza probada.
- D4. La spec, el plan y las tareas de la L quedaron **aprobados por el usuario el 2026-10-28**. La aprobación incluye
  que las tareas de la tanda S del servidor (E3 y E4, `hclinica`, rama `salud360-api`) pueden empezar a implementarse
  ("arranquemos con la tanda S"): el usuario aprobó la tanda como un todo. La subida a producción (E9) **requiere
  aprobación aparte**; la Entrega E sigue como borrador sin aprobar.
- D5. Q15 = **nunca borrar** (ver "Decididas"): la lectura no elimina ni vacía nada de lo ya traído; lo marca (RF-63).
- D6. (2026-10-29, usuario) **El backend de solo lectura de clínica se registra después de la pantalla de solo lectura**: la
  tarea E6.5 va después de E7.3 y antes de E7.4 y de la tanda V. Motivo: el implementador de E5.3 detectó que
  `cerrarConsulta`, `reabrirConsulta`, `anularConsulta` y `guardar*` no distinguen consultas de la web hasta E7.3; registrar
  antes dejaría una ventana en que la pantalla permitiría editar una consulta de la web. No cambia el alcance de ninguna tarea.
  (No confundir con la «D6» del plan, que es otra decisión de diseño.)

## Para decidir
- Nada en la Entrega L. Para la Entrega E: lo que dice "Entrega E (escritura)" arriba.

## Decididas (2026-10-28, usuario)
El usuario aprobó **Q1 a Q14 y Q16 a Q18 con la opción recomendada** y decidió **Q15 = "Nunca borrar"** (la estricta).
Debajo de cada una, las opciones que se evaluaron.

- **Q1 = A.** Todas las secciones del catálogo que tienen equivalente en la web (las de RF-40). Las tareas del
  servidor se ordenan en dos grupos (textos y formularios; laboratorio y estudios) para poder frenar a la mitad si algo
  no cierra. Descartadas: B) un subconjunto (el médico vería la consulta incompleta); C) solo textos y examen físico.
- **Q2 = A.** Avisar solo cuando la web tiene datos que la L no muestra (adjuntos PDF, audio, fotos, diagnósticos,
  antecedentes personales): el servidor informa cuáles hay (`no_leidas`) y la app lo dice en esa consulta; en la lista,
  una línea con la cantidad de consultas de tipos no mostrables. Descartadas: B) ocultarlo (viola RF-43); C) un cartel
  fijo en toda consulta.
- **Q3 = A.** Las consultas abiertas en la web se muestran como "abiertas en la web", solo lectura, en un grupo aparte
  (RF-44). Descartadas: B) no mostrarlas hasta que se cierren; C) editables (es escritura, Entrega E).
- **Q4 = A.** Las consultas de otro médico, solo lectura y con el nombre de quien las cargó (guardado en el
  dispositivo sin cambiar el esquema). Descartadas: B) sin nombre; C) no mostrarlas.
- **Q5 = A.** Las consultas viejas del dispositivo sin enviar (incluidas las ex-hepatología) no se tocan ni se envían:
  quedan sin enviar y su destino se decide en la Entrega E. Descartadas: B) marcarlas como enviadas; C) ocultarlas.
- **Q6 = A.** Laboratorio, peso y estudios por paciente y fecha, como la web (RF-41); cada estudio se asocia a la
  consulta donde se cargó. Descartada: B) solo las cargas de esa consulta.
- **Q7 = A.** Antecedentes familiares: los últimos activos del paciente, visibles en cualquier consulta, como los
  muestra la web. Descartada: B) solo los de la consulta donde se cargaron.
- **Q8 = A.** La lectura nunca crea ni completa la ficha de la web (`resolver`): un paciente sin vínculo muestra el
  aviso de RF-49 y se vincula como hoy (cartera, o la sección Enfermedades). Descartadas: B) un botón "Vincular"
  (escribe: sería de la E); C) automático al abrir.
- **Q9 = A.** Las 50 consultas más recientes más "Ver más"; se trae al abrir la historia y al abrir cada consulta, con
  botón "Actualizar" y sin pedir de nuevo la lista antes de un minuto. Descartadas: B) todas de una vez; C) 20.
- **Q10 = A.** Antes de probar, se le asigna al médico de prueba el perfil equivalente al que usa en la web (clínica
  general u hepatología); RF-22 de la spec 001 cubre lo que quede fuera del perfil. Descartada: B) probar con
  cualquier perfil.
- **Q11 = A.** Las consultas dadas de baja (`activo = 0`) en la web no se muestran; la primera tarea del servidor
  cuenta los valores reales en la copia local para confirmar que significan "baja". Una consulta que la app ya había
  traído y que después se da de baja **no se oculta**: queda y se marca (RF-63, Q15). Descartadas: B) como anuladas;
  C) en un grupo aparte.
- **Q12 = A.** El servidor conoce el mapa explícito de nombres (albúmina, fósforo, concentración, ácido úrico, B12,
  "otros" del reducido; celíaco, dependiente de terceros) y la app no cambia ninguno de sus identificadores; el mismo
  mapa sirve a la Entrega E al revés. Descartadas: B) migrar los identificadores de la app; C) el mapa en la app.
- **Q13 = A.** "Tabaquismo" queda en el catálogo durante la L (leer no lo afecta: la web nunca trae ese dato) y se
  resuelve al empezar la E. Descartadas: B) sacarlo ahora (toca la spec 001 y sus tests); C) guardarlo en "otro".
- **Q14 = A.** Las consultas nuevas del dispositivo en la HC general se siguen pudiendo crear, con la marca visible
  "Solo en este dispositivo (no se envía a la web)". Descartadas: B) bloquear "Nueva consulta"; C) como hoy, sin marca.
- **Q15 = nunca borrar (opción A, la estricta; el planificador había recomendado la B, "espejo").** La app **no borra,
  no vacía y no oculta** lo ya traído de la web cuando allá desaparece, se da de baja, se anula o se vacía: lo marca
  ("Ya no figura en la web") y lo sigue mostrando. Es la lectura literal de la constitución, punto 3. Está en RF-55 y
  RF-63. Descartada: B) espejo de lo traído (sacar de la copia lo que la web ya no tiene).
- **Q16 = A.** El contador "pendientes de enviar" no cambia: la L no lo empeora (las consultas viejas de la HC general
  ya se cuentan hoy). Descartada: B) separar "solo en este dispositivo".
- **Q17 = A.** Que pediatría marque como propias las consultas de otro médico queda fuera de la L; se anota como tarea
  aparte sobre pediatría (RF-60). Descartada: B) corregirlo dentro de la L.
- **Q18 = A.** Primero todo local; la subida del servidor a producción es una tarea aparte (E9), con respaldo
  verificado antes y comprobación sin token después (debe dar "sin token" y no "no existe"). Descartadas: B) subir y
  probar enseguida; C) probar antes sobre una copia de producción restaurada.

## Dudas abiertas
Ninguna en la Entrega L. Las de la Entrega E se listan cuando se la planifique (ver "Entrega E (escritura)" arriba).
