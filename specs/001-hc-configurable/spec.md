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
- **Laboratorio**: una sección en tabla por fechas (Laboratorio general, Laboratorio reducido, Peso): una **fila** por
  analito (por ejemplo "Hemoglobina") y una **columna** por **carga** (una fecha propia y sus valores, guardada en
  una consulta). En la HC general una consulta puede tener varias cargas de cada laboratorio; las ya guardadas, de
  esta consulta o de otras, se ven solo para leer y, mientras el médico carga una nueva, se suma una columna con
  **celdas editables**. En las demás historias clínicas cada consulta tiene a lo sumo una carga por laboratorio, que
  se edita como hasta ahora (RF-64).
- **Filas ocultas**: las filas de un laboratorio que un médico eligió no ver. Son una preferencia de ese médico para
  ese laboratorio; no cambian los datos.
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

### Laboratorio
- RF-43: CUANDO el médico toca "Elegir filas" en un laboratorio de la HC general, EL SISTEMA abre una lista con una
  casilla por fila, en el orden y con los grupos del laboratorio, tildadas las visibles; cada casilla que cambia se
  aplica al momento en la tabla, sin otro paso para confirmar.
- RF-44: EL SISTEMA guarda las filas ocultas por médico y por laboratorio en el dispositivo, igual que las secciones
  ocultas (RF-28): no cambian lo que ven otros médicos ni la web, no cambian otro laboratorio que tenga el mismo
  analito, y no se envían a la HC general.
- RF-45: MIENTRAS una fila esté oculta, EL SISTEMA no la muestra en ninguna columna de ese laboratorio (cargas
  de otras consultas, cargas de esta consulta, celdas editables y consulta vista solo para leer), aunque tenga valores; un
  grupo sin filas visibles tampoco se muestra.
- RF-46: CUANDO el médico guarda una carga de laboratorio, EL SISTEMA conserva sin cambios los valores que esa carga
  ya tenía en filas ocultas; ocultar o mostrar una fila nunca borra ni cambia valores.
- RF-47: SI el médico intenta ocultar la última fila visible de un laboratorio, ENTONCES EL SISTEMA no la oculta (la
  casilla queda tildada y deshabilitada) y muestra "Tiene que quedar al menos una fila visible"; SI lo guardado no
  deja ninguna fila visible de ese laboratorio, ENTONCES EL SISTEMA muestra todas.
- RF-48: CUANDO el médico toca "Mostrar todas" en la lista, EL SISTEMA vuelve a mostrar todas las filas de ese
  laboratorio. MIENTRAS haya filas ocultas, el botón dice cuántas ("Elegir filas (3 ocultas)").
- RF-49: EL SISTEMA ofrece "Elegir filas" solo en los laboratorios de la HC general con más de una fila (no en Peso),
  también al ver una consulta solo para leer; los laboratorios de las demás historias clínicas no lo ofrecen (RF-33).
- RF-50: MIENTRAS el médico carga o edita un laboratorio, EL SISTEMA muestra cada celda editable con un fondo propio
  y un borde en todo su contorno, y las celdas de las cargas anteriores sin borde ni fondo propio, en modo claro y
  en modo oscuro.
- RF-51: EL SISTEMA dibuja el borde de la celda editable con un contraste de al menos 3:1 (WCAG 2.1, criterio 1.4.11)
  contra el fondo de la celda y contra el fondo de la fila, y el valor escrito y el texto de ayuda con al menos 4,5:1
  contra el fondo de la celda, en modo claro y en modo oscuro.
- RF-52: CUANDO una celda editable recibe el foco (clic, toque o tabulador), EL SISTEMA le pone un borde de otro color
  y del doble de grosor, con al menos 3:1 contra el fondo de la celda, y un cursor de escritura con al menos 3:1
  contra ese fondo, en modo claro y en modo oscuro; al perder el foco vuelve el borde de RF-50.
- RF-53: MIENTRAS una celda editable esté vacía, EL SISTEMA muestra adentro el texto de ayuda "Valor", que desaparece
  al escribir y no se guarda.
- RF-54: EL SISTEMA aplica RF-50 a RF-53 a la carga de laboratorio de todas las historias clínicas (pediatría y las
  demás incluidas): cambia el aspecto de la celda editable, no lo que se carga ni cómo se guarda.
- RF-55: MIENTRAS una consulta de la HC general se pueda editar y no haya una columna editable abierta en ese
  laboratorio, EL SISTEMA muestra el botón "Nueva carga", aunque la consulta ya tenga cargas de ese laboratorio.
- RF-56: CUANDO el médico toca "Nueva carga", EL SISTEMA abre, primera en la tabla, una columna editable vacía con la
  fecha de la consulta, que el médico puede cambiar; sus celdas son las de RF-50 a RF-53 y solo están en las filas
  visibles (RF-45). Hay a lo sumo una columna editable por laboratorio.
- RF-57: CUANDO el médico toca "Guardar laboratorio", EL SISTEMA guarda la columna editable como una carga nueva de
  esa consulta, con su fecha, sin cambiar ninguna carga ya guardada, cierra la columna editable y muestra la carga
  como una columna más (RF-60). SI la columna no tiene ningún valor escrito, ENTONCES EL SISTEMA no la guarda, la deja
  abierta y muestra "Cargá al menos un valor".
- RF-58: CUANDO el médico toca "Cancelar" en la columna editable, EL SISTEMA la cierra sin guardar nada.
- RF-59: EL SISTEMA no permite editar una carga de laboratorio ya guardada en la HC general, sea de esta consulta o
  de otra (no hay "Editar"): para corregirla, el médico la borra (RF-60) y hace una carga nueva, como en la web.
- RF-60: MIENTRAS la consulta se pueda editar, EL SISTEMA muestra un tacho en la cabecera de cada columna guardada
  (de esta consulta y de las otras); CUANDO el médico lo toca y confirma ("¿Eliminar el laboratorio del <fecha>?"),
  EL SISTEMA da de baja solo esa carga, que deja de verse; las demás no cambian. MIENTRAS la consulta esté cerrada o
  se vea solo para leer, EL SISTEMA no muestra tachos.
- RF-61: EL SISTEMA muestra la columna editable primero y después las cargas guardadas ordenadas por su fecha, de la
  más reciente a la más antigua y, a igual fecha, de la última guardada a la primera; dos cargas con la misma fecha
  son dos columnas, nunca se juntan. Las cargas de esta consulta llevan "Esta consulta" debajo de la fecha.
- RF-62: EL SISTEMA muestra todas las cargas de esta consulta y, de las de otras consultas, las 8 primeras según el
  orden de RF-61.
- RF-63: MIENTRAS la consulta esté cerrada o se vea solo para leer, EL SISTEMA muestra sus cargas de ese laboratorio
  y las de otras consultas (RF-61, RF-62) sin "Nueva carga" ni tachos; SI esa consulta no tiene ninguna carga de ese
  laboratorio, ENTONCES EL SISTEMA muestra la sección vacía, como hasta ahora.
- RF-64: EL SISTEMA aplica RF-55 a RF-63 solo a los laboratorios de la HC general; en pediatría y las demás historias
  clínicas el laboratorio sigue con una carga por consulta, "Nueva carga" o "Editar" y las columnas de hoy (RF-33).

### Serologías
- RF-65: CUANDO el médico ve la sección Serologías de hepatitis en una consulta de la HC general que puede editar, EL
  SISTEMA muestra los 16 marcadores como una grilla de tarjetas, cada una con el nombre del marcador y dos botones,
  "SI" y "NO", en este orden (por filas, de izquierda a derecha): IgM HAV, AgS HBV, Ig anti HCV, FAN, Anti LKM, IgG
  HAV, Ig anti core, Ig anti S HBV, Ig anti HDV, ASMA, Fibrotest, HIV, Ag e, Anti Ag e, Ig anti HEV, AMA.
- RF-66: MIENTRAS el ancho disponible para la sección sea menor de 600 puntos (un teléfono en vertical), EL SISTEMA
  muestra 2 tarjetas por fila; con 600 puntos o más, 4 por fila (no hay un paso intermedio). Las tarjetas de una fila
  tienen el mismo ancho y el nombre del marcador se ve completo, sin cortarse (puede ocupar dos líneas).
- RF-67: CUANDO el médico toca "SI" o "NO" en un marcador sin cargar o con el otro valor, EL SISTEMA lo deja con ese
  valor, con un solo toque (sin desplegable ni confirmación); CUANDO toca el botón que ya estaba elegido, EL SISTEMA
  deja el marcador sin cargar.
- RF-68: EL SISTEMA guarda cada marcador como hoy: "SI", "NO" o vacío (sin cargar), en el mismo campo; un marcador sin
  cargar nunca se guarda como "NO" (RF-26 de la spec 002), y los marcadores ya cargados antes de este cambio se ven
  con su valor.
- RF-69: EL SISTEMA muestra el botón elegido con fondo relleno y una tilde, y el no elegido solo con borde; un
  marcador sin cargar tiene los dos botones solo con borde. El estado no depende solo del color, en modo claro y en
  modo oscuro.
- RF-70: EL SISTEMA dibuja el borde del botón no elegido y el relleno del elegido con un contraste de al menos 3:1
  contra el fondo de la tarjeta, y el texto de cada botón y el nombre del marcador con al menos 4,5:1 contra su
  fondo (como RF-51), en modo claro y en modo oscuro.
- RF-71: EL SISTEMA permite usar cada botón con el teclado (tabulador para llegar, Enter o espacio para tocarlo), con
  un borde de foco visible de al menos 3:1 contra el fondo de la tarjeta; cada botón tiene un área táctil de al menos
  48 × 48 puntos; y un lector de pantalla anuncia el nombre del marcador, la opción y si está elegida ("IgM HAV, SI,
  elegido"), y "sin cargar" cuando el marcador no tiene valor.
- RF-72: MIENTRAS la consulta esté cerrada o la sección se vea solo para leer (RF-22), EL SISTEMA muestra solo los
  marcadores cargados, en la misma grilla y con las mismas columnas (RF-66), cada uno con su nombre y su valor y sin
  botones que se puedan tocar; SI ninguno está cargado, ENTONCES muestra la sección vacía, como hoy.
- RF-73: EL SISTEMA aplica RF-65 a RF-72 solo a Serologías de hepatitis de la HC general; los demás formularios,
  pediatría y las demás historias clínicas se ven como hoy (RF-33).

### Child-Pugh
- RF-74: CUANDO el médico ve la sección Child-Pugh en una consulta que puede editar y el ancho disponible para la
  sección es de 600 puntos o más, EL SISTEMA la muestra como una tabla, como en la web: arriba "Total: N · Clase X" y
  la leyenda "A: ≤ 6 · B: 7-9 · C: ≥ 10"; una columna "Parámetro" y, bajo el título "Puntos", tres columnas "1", "2"
  y "3"; y una fila por parámetro, en este orden, con estos criterios (1 | 2 | 3): Ascitis (Ausente (I) | Moderada
  (II) | Severa (III) o refractaria), Bilirrubina total (mg/dl) (≤ 2 | 2 - 3 | > 3), Albúmina (g/dl) (> 3,5 | 2,8 -
  3,5 | < 2,8), TP (seg sobre control) / RIN (1-3 / < 1,8 | 4-6 / 1,8-2,3 | > 6 / > 2,3) y Encefalopatía (No | Grado
  I/II | Grado III/IV o refractaria). Cada criterio es una celda que se toca, sin el sufijo "(1)", "(2)", "(3)".
- RF-75: MIENTRAS el ancho disponible para la sección sea menor de 600 puntos (un teléfono en vertical), EL SISTEMA
  muestra el mismo encabezado y, en vez de la tabla, un bloque por parámetro, en el mismo orden, con su nombre y
  debajo sus tres opciones apiladas a todo el ancho, cada una con sus puntos y su criterio ("2 puntos · Moderada
  (II)"). En los dos anchos los textos se ven completos, sin cortarse (pueden ocupar varias líneas).
- RF-76: CUANDO el médico toca la celda de un parámetro sin cargar o con otro puntaje, EL SISTEMA le pone ese puntaje,
  con un solo toque (sin desplegable ni confirmación); CUANDO toca la celda que ya estaba elegida, EL SISTEMA deja el
  parámetro sin cargar. Tocar el nombre del parámetro no hace nada.
- RF-77: EL SISTEMA muestra en "Total: N" la suma de los puntos de los parámetros cargados (0 si no hay ninguno), y en
  "Clase X" "—" mientras falte cargar alguno de los cinco, y con los cinco cargados A si el total es 6 o menos, B si
  es de 7 a 9 y C si es 10 o más; los dos se actualizan al tocar una celda, sin otro paso.
- RF-78: EL SISTEMA guarda cada parámetro como hoy: "1", "2", "3" o vacío (sin cargar), en el mismo campo; no guarda
  el total ni la clase (se calculan). Los parámetros cargados antes de este cambio se ven con su puntaje.
- RF-79: EL SISTEMA muestra la celda elegida con fondo relleno y una tilde, y las no elegidas solo con borde (un
  parámetro sin cargar tiene las tres con borde), con los colores y contrastes de RF-69 y RF-70, en modo claro y en
  modo oscuro; el estado no depende solo del color.
- RF-80: EL SISTEMA permite usar cada celda con el teclado (tabulador para llegar, Enter o espacio para tocarla), con
  el borde de foco de RF-71; cada celda tiene un área táctil de al menos 48 × 48 puntos; y un lector de pantalla
  anuncia el parámetro, los puntos, el criterio y si está elegida ("Ascitis, 2 puntos, Moderada (II), elegido"), y
  "sin cargar" cuando el parámetro no tiene puntaje.
- RF-81: MIENTRAS la consulta esté cerrada o la sección se vea solo para leer (RF-22), EL SISTEMA muestra "Total: N ·
  Clase X", la leyenda y los cinco parámetros, cada uno con su nombre y, si está cargado, sus puntos y su criterio
  ("2 puntos · Moderada (II)"), o "—" si no lo está, como texto y sin nada que se pueda tocar; SI ninguno está
  cargado, ENTONCES muestra la sección vacía, como las demás secciones.
- RF-82: EL SISTEMA aplica RF-74 a RF-81 a Child-Pugh de la HC general; las demás secciones e historias clínicas se ven
  como hoy (RF-33).

### Motivo de consulta con historial
- RF-83: CUANDO el médico ve Motivo de consulta en una consulta de la HC general, EL SISTEMA muestra a la izquierda el
  texto de esa consulta y a la derecha, en un recuadro del mismo alto con desplazamiento propio y con el título
  "Consultas previas", el motivo de las demás consultas del paciente (RF-84), cada uno con la fecha de su consulta, de
  la fecha más reciente a la más antigua y, a igual fecha, primero la consulta modificada más recientemente, sin los
  que tienen el texto vacío.
- RF-84: EL SISTEMA cuenta para RF-83 todas las consultas del paciente en la HC general que no están anuladas, de
  cualquier tipo (Consulta o Telemedicina) y también las posteriores a la que se ve, menos la que se ve.
- RF-85: EL SISTEMA no ofrece una fecha propia para el motivo: la fecha de cada motivo es la de su consulta; SI cambia
  la fecha de una consulta, ENTONCES su motivo se muestra con la fecha nueva y en el lugar que le toca.
- RF-86: CUANDO el médico escribe el motivo en una consulta abierta, EL SISTEMA lo guarda como hoy (el mismo campo de
  esa consulta, con el autoguardado de siempre, sin llamar a la API); los motivos de las otras consultas no se pueden
  editar desde ahí.
- RF-87: CUANDO el médico toca "Ver todo" en Motivo de consulta, EL SISTEMA abre la misma ventana que RF-40, con el
  motivo de esa consulta primero, marcado "Esta consulta" y con su fecha, y después los de RF-83, con
  desplazamiento; cerrarla no cambia nada.
- RF-88: MIENTRAS la pantalla tenga menos de 600 puntos de ancho, EL SISTEMA muestra las consultas previas del motivo
  debajo del campo y no a la derecha (como RF-41).
- RF-89: MIENTRAS la consulta esté cerrada o se vea solo para leer (RF-22), EL SISTEMA muestra el motivo de esa
  consulta sin poder editarlo, y las consultas previas y "Ver todo" siguen disponibles; SI esa consulta no tiene
  motivo y no hay consultas previas con motivo, ENTONCES muestra la sección vacía, como las demás.
- RF-90: EL SISTEMA aplica RF-83 a RF-89 solo a Motivo de consulta de la HC general; las demás secciones de texto de la
  HC general, pediatría y las demás historias clínicas se ven como hoy (RF-33).

### Configuración inicial
- RF-31: EL SISTEMA trae armados los perfiles "Clínica general" y "Hepatología" exactamente como la tabla del
  catálogo inicial.
- RF-32: EL SISTEMA asigna "Hepatología" a los médicos que tienen el perfil 2 en la web, y "Clínica general" a los
  que tienen el perfil 1, 5 o 9 (que en la web ven las mismas secciones). Los de perfil 3 o 4, los de un perfil
  desconocido y los que no están vinculados con turnosonlinebb quedan sin perfil.
- RF-33: EL SISTEMA no cambia nada de las historias clínicas que no viven en la HC general (pediatría y las demás),
  salvo el aspecto de la celda editable del laboratorio (RF-54).

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
- El médico oculta una fila con valores en cualquier carga (de esta consulta o de otras): no se ve en ninguna columna
  (RF-45) y los valores siguen guardados (RF-46); "Mostrar todas" los vuelve a mostrar (RF-48). Una carga nueva de la
  HC general nunca tiene valores en filas ocultas (no se pueden escribir), y las guardadas no se editan (RF-59).
- Una columna anterior que solo tenía valores en filas ocultas: la columna se sigue mostrando, con sus celdas vacías.
- Filas ocultas que el laboratorio ya no tiene (porque cambió en una versión nueva de la app): se ignoran y no se
  borran de lo guardado; "Mostrar todas" tampoco las borra (mismo criterio que RF-28).
- El mismo analito en Laboratorio general y en Laboratorio reducido: ocultarlo en uno no lo oculta en el otro (RF-44).
- Un laboratorio de la HC general que aparece solo para leer (RF-22) también respeta las filas ocultas (RF-45).
- El médico de la HC general que también tiene pediatría: en pediatría el laboratorio se ve con todas sus filas y con
  la celda editable nueva (RF-49, RF-54), y con "Nueva carga"/"Editar" de una carga por consulta (RF-64).
- Consulta de la HC general con una carga de laboratorio hecha antes de este cambio (que se podía editar): queda como
  una carga más de esa consulta, sin "Editar"; se puede borrar y volver a cargar (RF-59, RF-60).
- Dos cargas del mismo laboratorio con la misma fecha, en la misma consulta o en distintas: dos columnas con la misma
  fecha, la última guardada primero (RF-61); el tacho de cada una borra solo esa (RF-60).
- Una carga de esta consulta con fecha anterior a cargas de otras consultas: va en su lugar por fecha, marcada "Esta
  consulta" (RF-61), y se muestra aunque haya 8 o más de otras consultas (RF-62).
- El médico sale de la consulta, la cierra o recarga la página con la columna editable abierta sin guardar: lo
  escrito en ella se pierde, como hasta ahora (no hay autoguardado del laboratorio).
- El médico borra con el tacho una carga mientras tiene la columna editable abierta: la columna editable sigue abierta
  con lo escrito.
- Una carga se borra por error: no se recupera desde la app; se vuelve a cargar (como en la web).
- Un marcador de serologías con un valor guardado que no es "SI", "NO" ni vacío (dato viejo o de otra versión): la
  tarjeta muestra ese valor como texto, sin ningún botón elegido; tocar "SI" o "NO" lo reemplaza y, si no se toca, no
  cambia. Cuenta como cargado: en solo lectura se muestra con su valor (RF-72).
- El médico toca dos veces seguidas el mismo botón de un marcador sin cargar: queda sin cargar (RF-67); se guarda lo
  último, con el autoguardado de siempre.
- La ventana cambia de ancho con la consulta abierta (girar el teléfono, achicar el navegador): la grilla pasa de 4 a
  2 columnas, o al revés, sin perder lo cargado ni cambiar el orden (RF-65, RF-66).
- Serologías con datos en una consulta y fuera del perfil del médico que la mira: sale al final, solo lectura, con la
  grilla de RF-72 (RF-22).
- Un parámetro de Child-Pugh con un valor guardado que no es "1", "2", "3" ni vacío (dato viejo o de otra versión):
  se muestra ese valor como texto junto al nombre, sin ninguna celda elegida; no suma al total y la clase queda en "—"
  (RF-77); tocar una celda lo reemplaza y, si no se toca, no cambia. Cuenta como cargado para "tener datos" (RF-22) y
  en solo lectura se muestra con su valor (RF-81).
- Child-Pugh con cuatro de los cinco parámetros cargados: el total es la suma de esos cuatro y la clase "—" (RF-77).
- El médico toca dos veces seguidas la misma celda de un parámetro sin cargar: queda sin cargar (RF-76); se guarda lo
  último, con el autoguardado de siempre.
- La ventana cambia de ancho con la consulta abierta: Child-Pugh pasa de la tabla a los bloques, o al revés, sin perder
  lo cargado (RF-74, RF-75).
- Child-Pugh con datos en una consulta y fuera del perfil del médico que la mira: sale al final, solo lectura, como en
  RF-81 (RF-22).
- Motivo de consulta en una consulta vieja: "Consultas previas" muestra también los motivos de las consultas
  posteriores del paciente, como los estudios con historial (RF-84).
- Una consulta anulada con motivo: su motivo no sale en las consultas previas de ninguna otra (RF-84); los datos no se
  borran.
- Dos consultas del paciente con la misma fecha y con motivo: salen las dos, primero la consulta modificada más
  recientemente (RF-83).
- Un motivo con solo espacios o saltos de línea: cuenta como vacío y no sale en las consultas previas (RF-83).
- El médico escribe el motivo y sale enseguida de la consulta: lo último escrito queda guardado, como hoy (RF-86).
- Motivos que la hepatología guardó en el dispositivo antes de ser un perfil: salen en las consultas previas de la HC
  general, porque esas consultas pasaron a "clinica" (RF-29).
- Motivo de consulta con datos en una consulta y fuera del perfil del médico que la mira: sale al final, solo lectura,
  con el mismo formato (RF-22, RF-89).
- Un paciente con muchas consultas con motivo: salen todas en las consultas previas, con desplazamiento, sin límite
  (RF-83).

## Fuera de alcance
- Que la web lea esta configuración en vez de sus perfiles (spec aparte).
- Crear secciones nuevas sin programar, o configurar los campos de una sección (analitos, casillas).
- Las secciones propias de los perfiles 3 y 4, vacunas y audio (spec aparte); armar esos perfiles lo hace el
  administrador cuando estén.
- Leer y escribir los datos clínicos en la HC general: es la spec 002.
- Secretarias. Que un médico vea pacientes o consultas de otro.
- Elegir filas en los laboratorios de pediatría y las demás historias clínicas, o desde Configuración; que el
  administrador elija las filas de un laboratorio para un perfil.

## Criterios de finalización
- Cada RF de lógica (validaciones de RF-4, RF-8, RF-9, RF-11, RF-12; armado y filtrado de RF-20 a RF-22; caché y
  primera vez de RF-25 y RF-26; RF-27 a RF-30; RF-34; mapeo inicial de RF-32; cuerpo de la consulta de RF-35 y RF-36;
  entrada e historial de RF-38 y RF-39; filas visibles y ocultas de RF-44 a RF-49; contrastes de RF-51 y RF-52;
  columnas, acciones y carga vacía de RF-55, RF-57, RF-60 a RF-62 y RF-64; columnas, toque y valores de RF-66 a
  RF-68, contrastes de RF-70 y el alcance de RF-73; toque, total, clase y valores de RF-76 a RF-78; historial del
  motivo de RF-83 a RF-85 y el alcance de RF-90) con test en verde.
- Contra la copia local: el administrador crea un perfil de prueba con dos pestañas y tres secciones, se lo asigna a
  un médico de prueba, y ese médico lo ve con esos nombres y en ese orden, sin actualizar la app.
- Contra la copia local: "Clínica general" y "Hepatología" coinciden con la tabla del catálogo inicial, y la
  asignación inicial con RF-32.

Cambio aprobado el 2026-10-13: RF-22 acotado a la consulta y RF-34 nuevo (datos por paciente en 'Datos').

Cambio aprobado el 2026-10-14 (prueba manual de la tanda C): la pestaña "Consulta" del perfil deja de ser pestaña y
va debajo de "Datos" y las demás, siempre visible, como en pediatría y en la web (definición "Cuerpo de la consulta",
RF-20 ajustado, RF-35 a RF-37); y las secciones de estudios (ecografía abdominal, videoendoscopía digestiva alta,
biopsia hepática, estudios cardiológicos, TC / RMN / colangio-RMN) pasan al formato de la web: campo de esta consulta
a la izquierda, consultas previas a la derecha y "Ver todo" (definición "Estudios con historial", RF-38 a RF-42).

Cambio aprobado el 2026-10-14 (2): en los laboratorios de la HC general el médico elige qué filas ve, desde la misma
sección ("Elegir filas", con casillas que se aplican al momento; una fila con valores se oculta igual y los datos no
se borran), como preferencia suya en el dispositivo, igual que las secciones ocultas (definiciones "Laboratorio" y
"Filas ocultas", RF-43 a RF-49); y la celda editable del laboratorio se distingue de las de solo lectura con fondo,
borde, foco y texto de ayuda con contraste mínimo, en modo claro y oscuro y en todas las historias clínicas (RF-50 a
RF-54; RF-33 ajustado).

Cambio aprobado el 2026-10-14 (3): en los laboratorios de la HC general cada "Nueva carga" agrega una columna nueva
(varias cargas por consulta y por laboratorio, con fecha propia, por defecto la de la consulta); las cargas guardadas
ya no se editan, ni las de esta consulta: se borran con el tacho (mientras la consulta se pueda editar) y se vuelven a
cargar, como en la web; columnas por fecha descendente y, a igual fecha, la última guardada primero, con todas las de
esta consulta marcadas y hasta 8 de otras. Pediatría y las demás siguen con una carga por consulta (definición
"Laboratorio", RF-45 ajustado, RF-55 a RF-64).

Cambio aprobado el 2026-10-15: Serologías de hepatitis de la HC general deja de ser una fila de 16 desplegables
(ilegible y sin uso en el teléfono) y pasa a una grilla de tarjetas, 4 por fila en pantalla ancha y 2 en el teléfono,
cada una con el nombre del marcador y dos botones "SI" / "NO" que se completan con un toque; tocar el elegido otra vez
lo deja sin cargar. Los valores guardados no cambian ("SI", "NO" o vacío) y en solo lectura se ven solo los cargados,
en la misma grilla. Solo en la HC general; las demás secciones e historias clínicas no cambian (RF-65 a RF-73).

Cambio aprobado el 2026-10-15 (2): Child-Pugh deja de ser cinco grupos de radios apilados (con una fila que se
desborda en el teléfono y sin forma de volver a "sin cargar") y se ve como en la web: una tabla Parámetro | 1 | 2 | 3
bajo "Puntos", donde se toca la celda del criterio; la elegida va rellena y con tilde, como los SI / NO de serologías,
y tocarla otra vez deja el parámetro sin cargar. Arriba "Total: N · Clase X" (clase "—" hasta tener los cinco) y la
leyenda "A: ≤ 6 · B: 7-9 · C: ≥ 10", con los textos corregidos de la app. En el teléfono, un bloque por parámetro con
las tres opciones apiladas; en solo lectura, el criterio elegido como texto. Los valores guardados no cambian ("1",
"2", "3" o vacío). Vale para la HC general y para la hepatología aparte, que comparten el dibujo (RF-74 a RF-82).

Cambio aprobado el 2026-10-15 (3): Motivo de consulta de la HC general muestra las consultas previas como Biopsia
hepática y los demás estudios con historial: a la izquierda el motivo de esta consulta, a la derecha "Consultas
previas" (el motivo de las demás consultas no anuladas del paciente en la HC general, también las posteriores, con la
fecha de cada consulta, de la más reciente a la más antigua, sin los vacíos) con desplazamiento propio, "Ver todo" en
una ventana grande y, en el teléfono, las previas debajo. Sin fecha propia: la del motivo es la de su consulta. Se
guarda como hoy (mismo campo y mismo autoguardado, nada a la API) y en solo lectura no se edita. Solo Motivo de
consulta de la HC general; las demás secciones de texto e historias clínicas no cambian (RF-83 a RF-90).
