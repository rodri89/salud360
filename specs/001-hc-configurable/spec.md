# Spec 001 — Perfiles configurables de la HC general

Estado: aprobada (2026-10-09, después de la revisión de QA)

## Contexto y objetivo
La web `hclinica` no es solo la historia clínica de clínica médica: es una **historia clínica general**. Todos sus
médicos usan la misma historia clínica, y lo que cambia es qué secciones ve cada uno según su **perfil** (hoy:
clínica general, hepatología, enfermedades crónicas…). Mañana se van a sumar otras (por ejemplo, medicina
familiar) reutilizando las secciones que ya existen y agregando solo las que falten.

Hoy esa composición está escondida en el código: en la web, en los perfiles; en la app, en definiciones fijas que
no coinciden con la web (la app trata hepatología como otra historia clínica, y su clínica muestra motivo,
diagnósticos, frecuencia cardíaca y tabaquismo, que el perfil de clínica general de la web no tiene).

El objetivo es que **el administrador arme cada perfil eligiendo secciones de un catálogo**, con sus nombres y sus
pestañas, que le asigne a cada médico su perfil, y que la app muestre exactamente eso. Un perfil que solo usa
secciones existentes no requiere programar nada ni publicar una versión nueva de la app.

## Usuarios
- **Administrador**: el administrador de Salud 360, que entra con su cuenta de administrador de turnosonlinebb
  (el mismo que gestiona licencias). Arma los perfiles y se los asigna a los médicos. No usa la HC general como médico.
- **Médico de la HC general**: usa el perfil que tiene asignado.
- Las secretarias quedan fuera de esta spec.

## Historias de usuario
- HU-1. Como administrador, quiero crear un perfil nuevo eligiendo secciones del catálogo, para sumar una
  especialidad sin pedir un desarrollo.
- HU-2. Como administrador, quiero ponerle a cada sección y pestaña el nombre que usa ese perfil, para que el
  médico la reconozca.
- HU-3. Como administrador, quiero asignarle a cada médico su perfil, para que vea las secciones que le corresponden.
- HU-4. Como médico, quiero ver en la app las secciones de mi perfil, y al principio las mismas que conozco de la web.

## Definiciones
- **HC general**: la web `hclinica` y su base. Es la dueña de los datos clínicos y de esta configuración. En
  turnosonlinebb se habilita como una sola historia clínica, "Clínica".
- **Sección del catálogo**: una parte de la historia clínica que ya existe programada en la HC general, con un
  nombre por defecto y una descripción. Agregar una sección al catálogo requiere programarla en la HC general y en
  la app; usarla en otro perfil no.
- **Ficha del paciente**: la pestaña "Datos" de la web. Está siempre, primera, y no se configura.
- **Cuerpo de la consulta**: la pestaña del perfil que se llama "Consulta" (comparando sin tildes, sin mayúsculas y
  sin espacios de más). En la consulta no se muestra como pestaña: sus secciones van debajo de las pestañas, siempre
  visibles, como en la web (arriba "Datos" y "Antec. Familiares", debajo las secciones de la consulta).
- **Estudios con historial**: las secciones Ecografía abdominal, Videoendoscopía digestiva alta, Biopsia hepática,
  Estudios cardiológicos y TC / RMN / colangio-RMN. Cada consulta guarda a lo sumo una **entrada** por sección (una
  fecha y un texto), y se muestra junto con las entradas de las otras consultas del paciente, como en la web.
- **Perfil**: una forma de ver la HC general. Tiene un nombre y una lista ordenada de pestañas (además de la ficha);
  cada pestaña tiene un nombre y una lista ordenada de secciones del catálogo, cada una con el nombre que se
  muestra y los tipos de consulta en los que aparece.
- **Tipos de consulta**: "Consulta" y "Telemedicina" (lista fija en esta spec).
- **Asignación**: el perfil que usa cada médico. Un médico tiene uno solo, o ninguno.
- **Tener datos en una sección**: haber guardado en ella, en esa consulta, al menos un valor que no sea vacío ni
  "sin cargar". En las secciones que se guardan por paciente y no por consulta (por ejemplo antecedentes
  personales), haber guardado ese valor para el paciente, en cualquier consulta.

## Catálogo inicial y perfiles iniciales
Relevado de la web (`nueva_consulta.blade.php` y `nueva_consulta_telemedicina.blade.php`). "C" = aparece en
Consulta, "T" = en Telemedicina.

| Sección del catálogo (nombre por defecto) | Clínica general | Hepatología |
|---|---|---|
| **Pestaña "Antec. Familiares"** | | |
| Antecedentes familiares (casillas con detalle + nota) | C, T | C, T |
| **Pestaña "Consulta"** | | |
| Motivo de consulta | — | C |
| Adjuntos (PDF) | — | C |
| Laboratorio general (tabla por fechas) | — | C |
| Laboratorio reducido (tabla por fechas) | — | — |
| Ecografía abdominal | — | C |
| Serologías de hepatitis | — | C |
| Peso (tabla por fechas) | — | C |
| Child-Pugh | — | C |
| Videoendoscopía digestiva alta | — | C |
| Biopsia hepática | — | C |
| Estudios cardiológicos | — | C |
| TC / RMN / colangio-RMN | — | C |
| Datos subjetivos | T | T |
| Datos objetivos | T | T |
| Alimentación | C | — |
| Diuresis y catarsis | C | — |
| Somnia | C | — |
| Hábitos | C | — |
| Examen físico (peso, talla, TA, IMC, nota) | C | — |
| Exámenes complementarios | C, T | T |
| Interconsultas | C, T | T |
| Conductas | C, T | T |
| Nota | C | — |

El orden de la tabla es el de la web, en Consulta y en Telemedicina (cambio aprobado el 2026-10-09: datos
subjetivos y objetivos van antes de exámenes complementarios, como en la telemedicina de la web). Quedan fuera del catálogo inicial (otra spec): checklist de enfermedades
(perfil 3), antecedentes en tabla propia (perfil 4), vacunas y grabación de audio. Los pendientes no son una
sección: se muestran siempre, como en la web.

## Requisitos funcionales

### Catálogo
- RF-1: EL SISTEMA muestra al administrador el catálogo de secciones, cada una con su nombre por defecto y su descripción.
- RF-2: EL SISTEMA no permite crear secciones nuevas desde el administrador: solo elegir del catálogo.

### Armar perfiles
- RF-3: CUANDO el administrador guarda un perfil con nombre y al menos una sección en alguna pestaña, EL SISTEMA lo
  guarda en la HC general.
- RF-4: SI el nombre del perfil, de una pestaña o de una sección está vacío o supera 60 caracteres, o el perfil no
  tiene ninguna sección, o el nombre del perfil ya lo usa otro, o hay dos pestañas con el mismo nombre en el
  perfil, ENTONCES EL SISTEMA no lo guarda y dice qué corregir.
- RF-5: CUANDO el administrador crea, renombra, quita o reordena pestañas de un perfil, EL SISTEMA guarda el cambio.
- RF-6: CUANDO el administrador agrega, quita o reordena secciones dentro de una pestaña, o las mueve de pestaña,
  EL SISTEMA guarda el cambio.
- RF-7: CUANDO el administrador cambia el nombre que se muestra de una sección o de una pestaña en un perfil, EL
  SISTEMA muestra ese nombre en ese perfil y no cambia el de los demás.
- RF-8: CUANDO el administrador agrega una sección a un perfil, EL SISTEMA la marca para "Consulta"; el
  administrador puede marcarla también o solo para "Telemedicina". SI la deja sin ningún tipo, ENTONCES EL SISTEMA
  no lo guarda y lo dice.
- RF-9: SI el administrador intenta guardar un perfil con una misma sección dos veces, ENTONCES EL SISTEMA no lo
  guarda y dice cuál está repetida.
- RF-10: CUANDO el administrador renombra un perfil, EL SISTEMA lo guarda sin cambiar su asignación a médicos.
- RF-11: EL SISTEMA no permite borrar un perfil que tenga médicos asignados.
- RF-12: CUANDO el administrador duplica un perfil, EL SISTEMA crea uno nuevo llamado "Copia de <nombre>" (con un
  número si ya existe) con las mismas pestañas, secciones y nombres.
- RF-13: SI la HC general rechaza o no recibe un cambio, ENTONCES EL SISTEMA se lo muestra al administrador con el
  motivo y conserva en pantalla lo que estaba editando para reintentar.
- RF-14: MIENTRAS no haya conexión, EL SISTEMA no permite al administrador editar perfiles ni asignaciones, y lo dice.
- RF-15: SI dos administradores guardan el mismo perfil, ENTONCES EL SISTEMA se queda con el último guardado
  completo (nunca una mezcla) y el otro lo ve al volver a abrirlo.
- RF-16: SI el catálogo de la HC general tiene una sección que la app del administrador no sabe mostrar, ENTONCES
  EL SISTEMA la lista como "requiere actualizar la app" y no permite agregarla a un perfil desde esa versión.

### Asignar médicos
- RF-17: CUANDO el administrador le asigna o le cambia el perfil a un médico de la HC general, EL SISTEMA lo guarda
  en la HC general; CUANDO se lo quita, el médico queda sin perfil.
- RF-18: CUANDO el administrador da de alta o edita un usuario médico, EL SISTEMA le ofrece habilitar la HC general
  (como una sola historia clínica, junto a pediatría y las demás) y, si está habilitada, elegir su perfil.
- RF-19: SI un médico tiene la HC general habilitada pero no tiene perfil, ENTONCES EL SISTEMA no le muestra
  secciones al abrir una consulta, le muestra "Falta que el administrador te asigne un perfil" y le deja ver sus
  consultas anteriores solo para leer.

### Lo que ve el médico
- RF-20: CUANDO el médico abre una consulta de la HC general, EL SISTEMA muestra la ficha del paciente y después
  las pestañas y secciones de su perfil para ese tipo de consulta, en ese orden y con esos nombres (con el cuerpo de
  la consulta debajo de las pestañas, según RF-35 y RF-36).
- RF-21: EL SISTEMA no muestra al médico, para editar, ninguna sección que no esté en su perfil, aunque la app la conozca.
- RF-22: CUANDO una consulta tiene datos en esa consulta en una sección que no está en el perfil del médico que la
  mira (porque se quitó, porque es de otro médico o porque nunca estuvo en el catálogo), EL SISTEMA la muestra solo
  para leer, al final de la consulta, con su nombre por defecto. Los datos guardados por paciente no salen ahí:
  van según RF-34.
- RF-23: CUANDO el médico abre una consulta con conexión, EL SISTEMA trae la configuración vigente de su perfil;
  un cambio que llega con una consulta ya abierta en pantalla se aplica la próxima vez que la abre, sin perder lo
  que estaba cargando.
- RF-24: CUANDO el administrador crea un perfil que usa solo secciones del catálogo, EL SISTEMA lo muestra a los
  médicos asignados sin publicar una versión nueva de la app.
- RF-25: MIENTRAS no haya conexión, EL SISTEMA usa la última configuración recibida, sin vencimiento.
- RF-26: SI el médico nunca recibió la configuración y no hay conexión, ENTONCES EL SISTEMA le dice que la primera
  vez hace falta conexión, y no pierde nada de lo que ya tenga en el dispositivo.
- RF-27: SI la app del médico recibe una sección que no sabe mostrar, ENTONCES EL SISTEMA la omite, muestra el
  resto y avisa una vez por sesión que hace falta actualizar la app.
- RF-28: EL SISTEMA sigue permitiendo que cada médico oculte para sí secciones de su perfil (no pestañas), como hoy
  en Configuración; lo que ocultó se mantiene aunque el administrador quite la sección y la vuelva a poner. Nunca
  puede mostrar una que no esté en su perfil.
- RF-29: EL SISTEMA no ofrece hepatología como una historia clínica aparte: es un perfil de la HC general. Lo que
  el dispositivo tenga guardado como "hepatología" (consultas, secciones ocultas) pasa a la HC general sin perderse,
  y una habilitación de "hepatología" en turnos cuenta como habilitación de la HC general.
- RF-30: EL SISTEMA nunca borra ni vacía en el dispositivo datos de secciones que no estén en el catálogo (por
  ejemplo diagnósticos ya cargados en clínica): se ven según RF-22 o, si se guardan por paciente, según RF-34.
- RF-34: CUANDO el paciente tiene datos guardados por paciente (no por consulta) en una sección que no está en el
  perfil del médico (por ejemplo antecedentes personales), EL SISTEMA los muestra una sola vez, solo para leer, en
  la ficha del paciente ("Datos").
- RF-35: CUANDO el médico abre una consulta de la HC general y su perfil tiene, para ese tipo de consulta, una
  pestaña que se llama "Consulta", EL SISTEMA muestra arriba las pestañas "Datos" y las demás del perfil (abriendo en
  "Datos"), y debajo, sin pestaña propia y siempre visibles sea cual sea la pestaña elegida, las secciones de
  "Consulta" en su orden y con sus nombres.
- RF-36: SI el perfil no tiene, para ese tipo de consulta, ninguna pestaña que se llame "Consulta", ENTONCES EL
  SISTEMA muestra todas sus pestañas junto a "Datos" y nada debajo; SI "Consulta" es la única pestaña, ENTONCES EL
  SISTEMA muestra arriba solo "Datos" y debajo el cuerpo de la consulta.
- RF-37: EL SISTEMA muestra las secciones de solo lectura de RF-22 al final de la consulta, debajo del cuerpo de la
  consulta (o, sin cuerpo, debajo de la pestaña elegida), visibles con cualquier pestaña elegida; lo de RF-34 sigue
  saliendo solo en "Datos".

### Estudios con historial
- RF-38: CUANDO el médico ve una sección de estudios con historial en una consulta, EL SISTEMA muestra a la izquierda
  la fecha y el texto de la entrada de esa consulta, y a la derecha, en un recuadro del mismo alto con desplazamiento
  propio y con el título "Consultas previas", las demás entradas del paciente en esa sección (fecha y texto de cada
  una, de la fecha más reciente a la más antigua), sin las que tienen el texto vacío.
- RF-39: CUANDO el médico escribe el texto (o cambia la fecha) de un estudio con historial en una consulta abierta,
  EL SISTEMA lo guarda como la única entrada de esa consulta en esa sección, con la fecha de la consulta si no eligió
  otra; SI deja el texto vacío, ENTONCES la consulta queda sin entrada en esa sección.
- RF-40: CUANDO el médico toca "Ver todo" en un estudio con historial, EL SISTEMA abre una ventana que ocupa la mayor
  parte de la pantalla con el historial completo de la sección (primero la entrada de esa consulta, marcada "Esta
  consulta", y después las demás como en RF-38), con desplazamiento; cerrarla no cambia nada.
- RF-41: MIENTRAS la pantalla tenga menos de 600 puntos de ancho (un teléfono en vertical), EL SISTEMA muestra las
  consultas previas debajo del campo y no a la derecha.
- RF-42: MIENTRAS la consulta esté cerrada o se vea solo para leer, EL SISTEMA muestra la fecha y el texto de la
  entrada de esa consulta sin poder editarlos; las consultas previas y "Ver todo" siguen disponibles.

### Configuración inicial
- RF-31: EL SISTEMA trae armados los perfiles "Clínica general" y "Hepatología" exactamente como la tabla del
  catálogo inicial.
- RF-32: EL SISTEMA asigna "Hepatología" a los médicos que tienen el perfil 2 en la web, y "Clínica general" a los
  que tienen el perfil 1, 5 o 9 (que en la web ven las mismas secciones). Los de perfil 3 o 4, los de un perfil
  desconocido y los que no están vinculados con turnosonlinebb quedan sin perfil.
- RF-33: EL SISTEMA no cambia nada de las historias clínicas que no viven en la HC general (pediatría y las demás).

## Requisitos no funcionales
- La configuración vive en la base de la HC general; la app la lee y, para el administrador, la escribe a través de
  ella. Nunca queda pendiente de envío en el dispositivo.
- Solo el administrador puede ver y cambiar la configuración; la HC general lo reconoce por su cuenta de
  turnosonlinebb, igual que en pediatría.
- **Para la app manda la configuración de la app.** La web sigue mostrando sus secciones por su perfil de siempre
  y esta spec no cambia sus pantallas: después de la carga inicial, si el administrador cambia un perfil, la web y
  la app pueden mostrar cosas distintas hasta la spec que pase la web a esta configuración.
- Requiere tablas y API nuevas en la HC general (aprobado por el usuario el 2026-10-09). La carga inicial se prueba
  primero en una copia local de la base, nunca directo en producción.
- Se entrega junto con la spec 002: no se publica sola (un push a `main` publica la web).
- La pantalla de administración funciona en web, Android e iOS, como el resto del panel.

## Casos límite
- Médico con la HC general y pediatría: en Configuración ve cada una por separado.
- Médico que cambia de perfil: sus consultas anteriores siguen visibles; lo que no esté en el perfil nuevo, según RF-22.
- Perfil borrado (sin médicos) con consultas hechas: las consultas no guardan el perfil; se ven con el perfil de
  quien las mira y RF-22.
- Consulta de telemedicina con secciones que solo están en "Consulta": se ven según RF-22.
- Diagnósticos y antecedentes fuera del perfil: los diagnósticos son de cada consulta y salen al final de la
  consulta en la que se cargaron (RF-22); los antecedentes personales son del paciente y salen una sola vez en
  "Datos" (RF-34), no al final de cada consulta.
- Médico del laboratorio reducido en la web: si su perfil es 2, recibe "Hepatología" (con el laboratorio general)
  y el administrador le arma un perfil propio duplicando "Hepatología".
- Médicos que en la web ven pacientes o consultas de otro médico: la app no lo replica (fuera de alcance).
- Pestaña sin secciones para un tipo de consulta: no se muestra en ese tipo.
- Consultas viejas de tipo "HC digitalizada" en el dispositivo: se ven solo para leer; no se ofrece el tipo para
  consultas nuevas (decidido en el plan, 2026-10-09).
- Frecuencia cardíaca y circunferencia abdominal ya cargadas en el examen físico: se ven solo para leer dentro del
  examen físico (decidido en el plan, 2026-10-09).
- El administrador renombra la pestaña "Consulta" (por ejemplo, "Evolución"): pasa a ser una pestaña más (RF-36);
  si la vuelve a llamar "Consulta" (o "consulta", "CONSULTA "), vuelve a ir debajo.
- El médico oculta para sí todas las secciones de "Consulta" (RF-28): debajo de las pestañas no queda nada; las demás
  pestañas no cambian.
- Telemedicina: el cuerpo es la pestaña "Consulta" con sus secciones de Telemedicina; si no tiene ninguna para ese
  tipo, no hay cuerpo (RF-36).
- Un estudio con historial con más de una entrada en la misma consulta (cargadas con la versión anterior de la app,
  que permitía agregar varias): el campo muestra la de fecha más reciente y las otras salen en "Consultas previas";
  ninguna se borra.
- Un estudio con historial que no está en el perfil pero tiene entrada en esa consulta: se ve según RF-22, con el
  mismo formato y solo para leer (RF-42).

## Fuera de alcance
- Que la web lea esta configuración en vez de sus perfiles (spec aparte).
- Crear secciones nuevas sin programar, o configurar los campos de una sección (analitos, casillas).
- Las secciones propias de los perfiles 3 y 4, vacunas y audio (spec aparte); armar esos perfiles lo hace el
  administrador cuando estén.
- Leer y escribir los datos clínicos en la HC general: es la spec 002.
- Secretarias. Que un médico vea pacientes o consultas de otro.

## Criterios de finalización
- Cada RF de lógica (validaciones de RF-4, RF-8, RF-9, RF-11, RF-12; armado y filtrado de RF-20 a RF-22; caché y
  primera vez de RF-25 y RF-26; RF-27 a RF-30; RF-34; mapeo inicial de RF-32; cuerpo de la consulta de RF-35 y RF-36;
  entrada e historial de RF-38 y RF-39) con test en verde.
- Contra la copia local: el administrador crea un perfil de prueba con dos pestañas y tres secciones, se lo asigna a
  un médico de prueba, y ese médico lo ve con esos nombres y en ese orden, sin actualizar la app.
- Contra la copia local: "Clínica general" y "Hepatología" coinciden con la tabla del catálogo inicial, y la
  asignación inicial con RF-32.

## Dudas abiertas
- (ninguna)

Cambio aprobado el 2026-10-13: RF-22 acotado a la consulta y RF-34 nuevo (datos por paciente en 'Datos').

Cambio aprobado el 2026-10-14 (prueba manual de la tanda C): la pestaña "Consulta" del perfil deja de ser pestaña y
va debajo de "Datos" y las demás, siempre visible, como en pediatría y en la web (definición "Cuerpo de la consulta",
RF-20 ajustado, RF-35 a RF-37); y las secciones de estudios (ecografía abdominal, videoendoscopía digestiva alta,
biopsia hepática, estudios cardiológicos, TC / RMN / colangio-RMN) pasan al formato de la web: campo de esta consulta
a la izquierda, consultas previas a la derecha y "Ver todo" (definición "Estudios con historial", RF-38 a RF-42).
