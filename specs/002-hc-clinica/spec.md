# Spec 002 — La HC general (clínica) contra su propia base

Estado: borrador · Depende de la spec 001 (qué secciones ve cada médico lo decide su perfil)

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
- **Cartera**: los pacientes que la web tiene asignados al médico.
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

### Médico sin agenda y pacientes
- RF-8: MIENTRAS el médico no tenga agenda, EL SISTEMA no muestra agenda ni recetas y lista su cartera de cada
  historia clínica habilitada.
- RF-9: SI la cartera no se pudo traer, ENTONCES EL SISTEMA muestra el motivo (sin conexión, no vinculado,
  licencia vencida, no habilitada) en vez de una lista vacía.
- RF-10: CUANDO un médico crea un paciente nuevo, EL SISTEMA lo registra en turnosonlinebb sin publicarlo para
  sacar turno, y lo vincula con la web al enviar su primera consulta.
- RF-11: CUANDO se vincula un paciente con la web y hay más de una ficha posible con ese documento, EL SISTEMA
  desempata por fecha de nacimiento y apellido; si sigue habiendo duda, le pide al médico que elija.
- RF-12: SI el documento es inválido, ENTONCES EL SISTEMA no crea la ficha en la web y le dice al médico qué corregir.
- RF-13: EL SISTEMA nunca reemplaza un dato de la ficha del paciente en la web por un valor vacío.
- RF-14: CUANDO el administrador da de alta un médico con la HC general y sin agenda, EL SISTEMA lo crea
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

## Requisitos no funcionales
- Lo que el médico hace en la app no cambia lo que tiene abierto en la web en ese momento.
- La app no multiplica los pedidos a la web: abrir una consulta trae todo junto; los guardados se agrupan.
- Funciona en web, Android e iOS.
- Ningún dato real de pacientes, ni tokens, en este repositorio.
- Consultas, pacientes y adjuntos de clínica nunca se mezclan con los de otra historia clínica en el dispositivo.

## Casos límite
- Médico con clínica y pediatría a la vez: cada una con su licencia, su cartera y sus consultas.
- Paciente que está en la cartera de clínica y en la de pediatría: una sola ficha en la app.
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

## Criterios de finalización
- Todos los RF con test en verde, o con su paso de verificación manual hecho, contra una copia local de la web.
- Prueba de punta a punta (como la de pediatría) que crea una consulta con todas las secciones, la lee de vuelta y
  deja todo como estaba.
- Una consulta cargada en la app se ve completa en la web, y una cargada en la web se ve completa en la app.
- Un médico sin agenda entra, ve su cartera, crea un paciente y le carga una consulta.
- Prueba sin señal: modo avión, cargar, recuperar la señal, verificar que se envió una sola vez.

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

## Dudas abiertas
- (ninguna)
