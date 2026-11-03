# Spec 003 — Sección "Enfermedades" de la HC general

Estado: aprobada (2026-10-19; cambio aprobado el 2026-10-20, ver al pie) · Depende de la spec 001 (catálogo de
secciones, perfiles, `claveDeNombre`, RF-28 y RF-34) · Depende de la spec 002 **solo** en la pieza de vincular
pacientes (resolver la ficha de la web por paciente de turnos y DNI con desempate, y que el médico elija si hay más
de una: RF-11 de la 002) y la cartera (RF-8, RF-9 de la 002); no depende del envío de consultas de la 002.

## Contexto y objetivo
En la web de clínica (`hclinica`) el perfil de enfermedades crónicas (perfil 3) tiene una sección "Enfermedades":
una grilla de casillas con las enfermedades con las que trabaja el médico, donde marca las que tiene el paciente.
Cada médico arma su lista eligiendo de un catálogo común, y puede sumar enfermedades nuevas al catálogo.

El administrador está armando en la app ese perfil, pero "Enfermedades" no está en el catálogo de secciones de la
spec 001 (quedó fuera "para otra spec": es esta).

El objetivo es que:
- el administrador pueda elegir "Enfermedades" en un perfil, como cualquier sección del catálogo;
- el médico la vea en la consulta y marque las enfermedades del paciente, y
- el médico administre su lista de enfermedades desde la misma sección,

leyendo y escribiendo **la base de la web desde el primer día**: lo que se marca en la app se ve en la web y al
revés. La web es la dueña; el dispositivo guarda solo una copia para mostrar (RF-32 a RF-34), y sin conexión la
sección es solo de lectura (excepción a la constitución, ver abajo).

## Usuarios
- **Médico de la HC general** cuyo perfil de la app incluye la sección "Enfermedades" (con o sin agenda).
- **Administrador**: agrega la sección a un perfil con la pantalla de la spec 001. No ve la sección, no marca
  enfermedades ni administra listas (no usa la HC como médico, spec 001).
- **Secretaria**: no ve la sección.

## Historias de usuario
- HU-1. Como administrador, quiero agregar "Enfermedades" a un perfil, para armar el perfil de enfermedades
  crónicas igual que en la web.
- HU-2. Como médico, quiero marcar en la consulta las enfermedades que tiene el paciente, para tenerlas registradas
  y verlas también en la web.
- HU-3. Como médico, quiero administrar mi lista de enfermedades desde la misma sección (buscar, agregar del
  catálogo, quitar y crear una nueva), para no tener que ir a la web.
- HU-4. Como médico, quiero ver las enfermedades del paciente en una consulta cerrada, para consultarlas sin editar.

## Definiciones
- **Catálogo de enfermedades**: la lista maestra de enfermedades de la web (tabla `enfermedades`), compartida por
  todos los médicos. Cada enfermedad tiene un id, un nombre y está activa o dada de baja. No es el catálogo de
  secciones de la spec 001.
- **Lista del médico**: las enfermedades con las que trabaja un médico (`medico_enfermedades` activas de ese
  médico). Es de cada médico.
- **Enfermedades del paciente**: las enfermedades marcadas para un paciente (`paciente_enfermedades` activas). Son
  **por paciente, no por consulta**: marcar o desmarcar en cualquier consulta cambia el dato del paciente, y lo ven
  todos los médicos que lo atienden.
- **Marcable** (para un médico): una enfermedad que está en la lista activa del médico **y** activa en el catálogo.
  Solo esas se marcan o desmarcan, y solo para un paciente de su cartera.
- **Otras enfermedades del paciente**: las enfermedades del paciente que no son marcables para el médico que mira
  (no están en su lista, o están dadas de baja en el catálogo), incluidas las que marcaron otros médicos.
- **Estado de la sección**: para un médico y un paciente, la lista de marcables (cada una con si el paciente la
  tiene) más las "Otras enfermedades del paciente".
- **Estado de administrar**: para un médico, su lista y las enfermedades activas del catálogo que no están en su
  lista.
- **Clave de nombre**: `claveDeNombre` de la spec 001 (`core/data`, `hcgeneral/PerfilValidacion.kt`): recorta,
  junta los espacios seguidos en uno, pasa a minúsculas y saca tildes con la tabla fija de esa función (à á â ã ä å
  → a; è é ê ë → e; ì í î ï → i; ò ó ô õ ö → o; ù ú û ü → u; ý ÿ → y; ñ → n; ç → c). **Espacio** = espacio común,
  tabulación, salto de línea, retorno de carro, tabulación vertical y salto de página, en todas las plataformas.
- **Mismo nombre**: dos nombres son el mismo si tienen la misma clave de nombre. Se usa en la app y en la API
  (normalizando en PHP con la misma regla y la misma tabla), no la intercalación de MySQL.
- **Nombre normalizado**: el nombre recortado y con los espacios seguidos juntados en uno, respetando mayúsculas y
  tildes tal como lo escribió el médico.
- **Orden alfabético**: por clave de nombre y, a igual clave, por id de menor a mayor.
- **Vínculo del paciente**: a qué ficha de la web (`pacientes.id`) corresponde el paciente de la app, resuelto con la
  pieza de la spec 002.
- **Sin conexión**: un pedido a la web que no llega (error de red) o que no tiene respuesta en 10 segundos.
- **Motivo**: el texto que explica por qué la sección no muestra datos o no deja cambiar algo.
- **No confundir con**: el tipo de consulta "enfermedad" de la web, ni la sección legada "Diagnósticos /
  enfermedades" de la app (diagnósticos por consulta, solo en el dispositivo). Son datos distintos (RF-55).

## Requisitos funcionales

### Catálogo de secciones y perfiles
- RF-1: EL SISTEMA incluye en el catálogo de secciones de la HC general la sección "Enfermedades", con nombre por
  defecto "Enfermedades" y la descripción "Casillas con las enfermedades del paciente, de la lista de cada médico".
- RF-2: CUANDO el administrador agrega "Enfermedades" a un perfil, EL SISTEMA la trata como cualquier sección del
  catálogo (nombre propio, pestaña, orden y tipos de consulta, spec 001 RF-5 a RF-9).
- RF-3: SI una versión anterior de la app recibe la sección "Enfermedades", ENTONCES EL SISTEMA aplica la spec 001
  (RF-16 en el administrador, RF-27 en el médico).
- RF-4: EL SISTEMA no muestra la sección al administrador ni a la secretaria.

### Cuándo se ve la sección
- RF-5: CUANDO el médico abre una consulta de la HC general cuyo tipo (Consulta o Telemedicina) tiene
  "Enfermedades" en su perfil y no la ocultó en Configuración (spec 001 RF-28), EL SISTEMA muestra la sección.
- RF-6: SI el médico no tiene perfil (spec 001 RF-19), o "Enfermedades" no está en su perfil para ningún tipo de
  consulta, o la ocultó, ENTONCES EL SISTEMA no muestra la sección en ninguna consulta y aplica RF-7.
- RF-7: MIENTRAS se cumpla RF-6, CUANDO el médico abre la ficha del paciente ("Datos") y el dispositivo tiene el
  estado de la sección de ese paciente (recién recibido o la copia de RF-32) con al menos una enfermedad del
  paciente, EL SISTEMA las muestra una sola vez, solo para leer, en orden alfabético, bajo "Enfermedades" (spec 001
  RF-34); si no las tiene o no hay ninguna, no muestra nada. EL SISTEMA nunca muestra las enfermedades del paciente
  a la vez en "Datos" y en la sección, ni al final de las consultas (spec 001 RF-22).

### Paciente y motivos
- RF-8: CUANDO el médico abre la sección (o "Datos" según RF-7) y el paciente no tiene vínculo guardado, EL SISTEMA
  lo resuelve en ese momento con la pieza de la spec 002 (paciente de turnos y DNI, con su desempate) y lo guarda;
  SI ya tiene vínculo guardado, ENTONCES EL SISTEMA usa ese.
- RF-9: SI al resolver el vínculo hay más de una ficha posible, ENTONCES EL SISTEMA muestra en la sección "Elegí la
  ficha del paciente" con las candidatas (nombre, apellido, documento y fecha de nacimiento de cada una) y, cuando el
  médico elige una, guarda el vínculo y carga la sección; mientras no elija, no muestra casillas.
- RF-10: SI al resolver el vínculo no hay ninguna ficha posible (el documento del paciente no sirve para la historia
  clínica, la única ficha con ese documento ya corresponde a otro paciente de turnos, o la ficha elegida en RF-9 ya
  no es posible), ENTONCES EL SISTEMA muestra el texto de la spec 002 para ese caso, que dice qué hacer (corregir el
  documento en la ficha del paciente: 002 RF-12; avisar al administrador: 002 RF-36), y no muestra casillas. Para
  la ficha elegida que ya no es posible (`ficha_no_elegible`) el texto es "La ficha elegida no corresponde a este
  paciente. Elegí otra o pedile al administrador que revise el documento."
- RF-11: SI la web responde que el paciente no está en la cartera del médico, ENTONCES EL SISTEMA muestra "Este
  paciente no está en tu cartera", no muestra enfermedades (tampoco las de la copia del dispositivo) y borra de la
  copia el estado de la sección de ese paciente.
- RF-12: SI la web responde que la ficha vinculada no existe o está dada de baja, ENTONCES EL SISTEMA muestra "La
  ficha de este paciente no está disponible en la web" y no muestra casillas.
- RF-13: SI la web rechaza el pedido por historia clínica no habilitada, médico no vinculado, licencia vencida o
  sesión vencida, ENTONCES EL SISTEMA aplica a la sección el mismo motivo, el mismo texto y la misma conducta que el
  resto de la HC general (spec 002 RF-2, RF-3, RF-4), sin mostrar enfermedades.
- RF-14: CUANDO aplica más de un motivo, EL SISTEMA muestra solo el primero de este orden: (1) sesión vencida,
  (2) historia clínica no habilitada, (3) médico no vinculado, (4) licencia vencida, (5) sin conexión o error no
  previsto de la web (RF-30, RF-31), (6) elegir ficha (RF-9), (7) sin ficha (RF-10), (8) ficha no disponible (RF-12), (9) fuera de la cartera
  (RF-11). La lista vacía (RF-18) no es un motivo: se muestra junto con lo demás.

### Consulta abierta: la grilla
- RF-15: CUANDO el médico abre la sección en una consulta abierta propia, con conexión y el paciente vinculado, EL
  SISTEMA hace un solo pedido (estado de la sección) y muestra una casilla por cada marcable, marcada si el paciente
  la tiene, y reemplaza con eso la copia del dispositivo (RF-33).
- RF-16: EL SISTEMA muestra las casillas, las "Otras enfermedades del paciente" y las listas de administrar en orden
  alfabético.
- RF-17: MIENTRAS el ancho disponible para la sección sea menor de 600 puntos, EL SISTEMA muestra 2 casillas por
  fila; con 600 puntos o más, 4 por fila (como spec 001 RF-66). Las casillas de una fila tienen el mismo ancho y el
  nombre se ve completo, en las líneas que haga falta.
- RF-18: SI la lista del médico no tiene marcables, ENTONCES EL SISTEMA muestra "No tenés enfermedades en tu listado.
  Podés agregarlas con «Administrar enfermedades»." en lugar de la grilla, y sigue mostrando "Otras enfermedades del
  paciente" y el botón "Administrar enfermedades".
- RF-19: CUANDO el paciente tiene "Otras enfermedades del paciente", EL SISTEMA las muestra debajo de la grilla, bajo
  ese título, solo para leer y en orden alfabético; SI no tiene, ENTONCES EL SISTEMA no muestra ese bloque.
- RF-20: EL SISTEMA permite usar cada casilla con el teclado (tabulador y espacio), con un área táctil de al menos
  48 × 48 puntos, y un lector de pantalla anuncia el nombre de la enfermedad y si está marcada.

### Marcar y desmarcar
- RF-21: CUANDO el médico marca o desmarca una casilla, EL SISTEMA envía en ese momento a la web el estado elegido
  (tiene / no tiene) para ese paciente y esa enfermedad, sin esperar al autoguardado de la consulta, y muestra la
  casilla con el estado elegido y la marca "Guardando…".
- RF-22: MIENTRAS una casilla tenga un pedido en camino, EL SISTEMA no permite volver a tocarla; las demás siguen
  disponibles.
- RF-23: CUANDO llega la respuesta correcta de un pedido, EL SISTEMA aplica el estado de la sección que trae, salvo
  que ya haya aplicado la respuesta de un pedido enviado después (la descarta); al aplicarla, las casillas que tienen
  un pedido en camino conservan el estado elegido.
- RF-24: CUANDO la web confirma un cambio, EL SISTEMA muestra "Guardado" junto al título de la sección durante
  3 segundos; hay un solo aviso por sección, y una nueva confirmación lo vuelve a mostrar desde cero.
- RF-25: SI la web rechaza el cambio porque la enfermedad ya no está en la lista del médico o se dio de baja en el
  catálogo, ENTONCES EL SISTEMA muestra "«<nombre>» ya no está en tu lista" o "«<nombre>» se dio de baja en el
  catálogo", vuelve a pedir el estado de la sección y muestra ese.
- RF-26: SI la web rechaza el cambio por demasiados pedidos (429), ENTONCES EL SISTEMA vuelve la casilla al estado
  anterior y muestra "Demasiados cambios seguidos, esperá un momento".
- RF-27: SI la web rechaza el cambio por otro motivo de RF-11 a RF-13, ENTONCES EL SISTEMA vuelve la casilla al estado
  anterior y aplica ese motivo; SI lo rechaza con un error no previsto, ENTONCES EL SISTEMA vuelve la casilla al
  estado anterior y muestra "No se pudo guardar el cambio. Probá de nuevo.".
- RF-28: SI el pedido queda sin conexión (sin respuesta en 10 segundos o error de red), ENTONCES EL SISTEMA no vuelve
  la casilla al estado anterior: vuelve a pedir el estado de la sección y muestra ese; SI ese pedido también queda
  sin conexión, ENTONCES EL SISTEMA muestra la casilla con el último estado confirmado por la web, el aviso "No se
  pudo confirmar el cambio en «<nombre>»: puede haberse guardado. Volvé a abrir la sección con conexión." y pasa a
  solo lectura (RF-30). *(Ajuste tras validación F2, 2026-10-23.)* SI la web **rechaza** ese pedido del estado, ENTONCES
  se mira su motivo: si es del lugar 5 de RF-14 (error de red, `tobb_caido`, 429 o un error no previsto) cuenta como
  "sin respuesta" y lleva a solo lectura con el aviso de sin conexión (RF-30); con cualquier otro motivo (RF-11 a
  RF-13) se muestra ese motivo en lugar de las casillas.
- RF-29: EL SISTEMA muestra un solo motivo de rechazo por vez en la sección (el último), hasta la siguiente
  confirmación o hasta cerrar la consulta.

### Sin conexión (excepción a la constitución)
- RF-30: MIENTRAS no haya conexión, EL SISTEMA muestra el estado de la sección del paciente tal como está en la copia
  del dispositivo, sin permitir marcar, desmarcar ni abrir "Administrar enfermedades", con el aviso "Sin conexión:
  las enfermedades se pueden ver pero no cambiar" y un botón "Reintentar" que vuelve a pedir el estado de la
  sección. SI al abrir la sección la web responde con un error no previsto (error interno, ocupado después del
  reintento, respuesta que no se puede leer), ENTONCES EL SISTEMA hace lo mismo, con el texto que manda la web en
  lugar de ese aviso.
- RF-31: SI no hay conexión y el dispositivo no tiene el estado de la sección de ese paciente, ENTONCES EL SISTEMA
  muestra "Hace falta conexión para ver las enfermedades" (con un error no previsto, el texto que manda la web) y no
  muestra una grilla vacía.

### Copia en el dispositivo
- RF-32: EL SISTEMA guarda en una tabla nueva de la base del dispositivo (migración nueva) la lista del médico, el
  catálogo para el buscador y el estado de la sección de cada paciente abierto, con el médico al que pertenecen;
  nada de eso queda pendiente de envío ("dirty") ni se envía después.
- RF-33: EL SISTEMA reemplaza una parte de la copia solo con una respuesta correcta de la web para esa parte,
  incluida una lista vacía; una respuesta con código de error (no autorizado, fuera de la cartera, licencia, etc.)
  nunca la reemplaza, salvo lo que RF-11 borra.
- RF-34: CUANDO el médico cierra sesión, o entra en el dispositivo un médico distinto del dueño de la copia, EL
  SISTEMA borra toda la copia de enfermedades.

### Consultas cerradas o de solo lectura
- RF-35: MIENTRAS la consulta esté cerrada o se vea solo para leer (por ejemplo, de otro médico, spec 002 RF-25), EL
  SISTEMA muestra en la sección, con la leyenda "Estado actual del paciente", las enfermedades que el paciente tiene
  hoy (las marcadas de la grilla y las "Otras") en orden alfabético, sin casillas ni "Administrar enfermedades"; SI
  no tiene ninguna, ENTONCES muestra "Sin enfermedades registradas". Los motivos (RF-9 a RF-14) y la falta de
  conexión (RF-30, RF-31) se aplican igual.
- RF-36: EL SISTEMA no guarda qué tenía marcado el paciente en cada consulta (la web tampoco).

### Administrar la lista del médico
- RF-37: MIENTRAS la sección se vea en una consulta abierta propia y haya conexión, EL SISTEMA muestra el botón
  "Administrar enfermedades", esté o no el paciente vinculado o en la cartera, y aunque se muestre un motivo de
  RF-9 a RF-12.
- RF-38: CUANDO el médico toca "Administrar enfermedades", EL SISTEMA hace un pedido (estado de administrar) y abre
  una ventana con un buscador, su lista en orden alfabético con "Quitar" en cada una, y el bloque "Del catálogo"; la
  ventana y cada lista tienen desplazamiento propio, sin paginado.
- RF-39: CUANDO el médico escribe en el buscador, EL SISTEMA filtra en el dispositivo, sin pedidos: muestra de su
  lista las que contienen lo escrito y, en "Del catálogo", las activas del catálogo que no están en su lista y
  contienen lo escrito, cada una con "Agregar" (comparando por clave de nombre: la clave de lo escrito contenida en
  la clave del nombre); si el catálogo tiene varias con el mismo nombre, se ven todas. MIENTRAS el buscador esté
  vacío (o solo con espacios), "Del catálogo" muestra "Escribí para buscar en el catálogo".
- RF-40: CUANDO el médico toca "Agregar", EL SISTEMA suma esa enfermedad a su lista en la web y muestra el estado de
  administrar y, si el paciente está vinculado y en la cartera, el estado de la sección que trae la respuesta.
- RF-41: CUANDO el médico toca "Quitar", EL SISTEMA saca esa enfermedad de su lista en la web (la desasocia, como
  "Trabajo con esta enfermedad" de la web) sin desmarcarla de ningún paciente, y muestra lo que trae la respuesta
  como en RF-40: si el paciente la tenía, pasa a "Otras enfermedades del paciente"; si el médico la vuelve a
  agregar, aparece marcada.
- RF-42: MIENTRAS el nombre normalizado de lo escrito en el buscador no esté vacío y ninguna enfermedad de la lista
  ni del bloque "Del catálogo" tenga el mismo nombre, EL SISTEMA ofrece "Crear «<nombre normalizado>»".
- RF-43: SI el nombre normalizado está vacío, tiene más de 255 caracteres (contados como caracteres, no bytes) o
  contiene un carácter invisible o de control (categorías Unicode Cc, Cf, Zl, Zp, o un espacio Zs que no sea el
  espacio común), ENTONCES EL SISTEMA no lo envía y muestra "El nombre está vacío", "El nombre tiene más de 255
  caracteres: acortalo" o "El nombre tiene caracteres que no se ven: escribilo de nuevo".
- RF-44: CUANDO el médico toca "Crear", EL SISTEMA envía el nombre normalizado y el paciente de la consulta desde la
  que se abrió la ventana (salvo RF-58), y la web, como la pantalla "Enfermedad" de la web: SI no hay en el catálogo
  ninguna con el mismo nombre, ENTONCES la crea activa con ese nombre; SI hay, ENTONCES no crea otra y usa la de menor
  id entre las activas o, si ninguna está activa, la de menor id, que reactiva en el catálogo; en todos los casos la
  suma a la lista del médico (si ya estaba, no cambia) **y la marca al paciente de esa consulta** (si ya la tenía,
  sigue marcada; nunca se desmarca). EL SISTEMA muestra el estado de administrar y el estado completo de la sección
  que trae la respuesta (como D9, con el orden de respuestas de RF-23 y "Guardado" de RF-24), así que la grilla la
  muestra marcada; si se reutilizó una existente, la muestra con el nombre del catálogo y el aviso "Ya existía en el
  catálogo como «<nombre>»; se agregó a tu lista y se marcó al paciente" (o "«<nombre>» ya estaba en tu lista; se
  marcó al paciente" si ya lo estaba).
- RF-45: MIENTRAS un cambio de la lista esté en camino, EL SISTEMA deshabilita "Agregar", "Quitar" y "Crear" en toda
  la ventana.
- RF-46: SI la web rechaza o no confirma un cambio de la lista, ENTONCES EL SISTEMA no lo muestra como hecho, muestra
  el motivo (los de RF-25 a RF-28, con "Demasiados cambios seguidos, esperá un momento" para el 429) y conserva lo
  escrito en el buscador; sin conexión (RF-28) vuelve a pedir el estado de administrar y muestra ese.
- RF-47: EL SISTEMA no ofrece en la app renombrar ni dar de baja enfermedades del catálogo, ni la acción "eliminar"
  de la web (que además desmarca al paciente).
- RF-48: CUANDO llega la respuesta de cualquier cambio (casilla o lista), EL SISTEMA muestra el estado completo que
  trae, aunque la lista se haya cambiado desde la web o desde otro dispositivo; no hay aviso en tiempo real.

### API nueva de la HC general (`hclinica`)
- RF-49: EL SISTEMA (la API) atiende estos pedidos solo con el token de un médico vinculado: con otro token (el
  administrador incluido) responde 403 `sin_permiso`; los rechazos del middleware (`hc_no_habilitada`,
  `medico_no_vinculado`, `licencia_vencida`, sesión) son los de siempre. No mira `users.perfil` de la web ni el
  perfil de la app.
- RF-50: CUANDO se pide el estado de la sección de un paciente (id de la web), EL SISTEMA responde 200 con las
  marcables (id, nombre, tiene) y las "Otras" (id, nombre), aunque estén vacías; SI la ficha no existe o está dada
  de baja, ENTONCES 404 `paciente_no_encontrado`; SI el paciente no está en la cartera activa del médico, ENTONCES
  403 `fuera_de_cartera`.
- RF-51: CUANDO se pide marcar o desmarcar (paciente, enfermedad, tiene), EL SISTEMA verifica la ficha y la cartera
  (RF-50), que la enfermedad esté en la lista activa del médico (si no, 422 `no_en_tu_lista`) y activa en el
  catálogo (si no, 422 `enfermedad_dada_de_baja`); deja el registro del paciente en el estado pedido (pedir el
  estado que ya tiene no cambia nada) y responde 200 con el estado de la sección.
- RF-52: CUANDO se pide el estado de administrar, EL SISTEMA responde 200 con la lista activa del médico (id,
  nombre) y las activas del catálogo que no están en ella.
- RF-53: CUANDO se pide agregar o quitar una enfermedad de la lista del médico (con un paciente opcional), EL SISTEMA
  la asocia (solo si está activa en el catálogo; si no, 422 `enfermedad_dada_de_baja`) o la desasocia sin tocar
  ningún registro de pacientes, y responde 200 con el estado de administrar y, si se mandó un paciente vinculado y
  de la cartera, el estado de su sección.
- RF-54: CUANDO se pide crear una enfermedad, EL SISTEMA normaliza el nombre, lo valida como RF-43 (si no, 422
  `nombre_invalido` con el texto de RF-43) y aplica RF-44 comparando con la clave de nombre calculada en PHP;
  SI la base rechaza el alta por nombre repetido según su intercalación (otro médico lo creó a la vez, o MySQL los
  iguala y la clave no), ENTONCES EL SISTEMA usa la existente que encontró la base como si tuviera el mismo nombre.
  Recibe opcionalmente un paciente (id de la web): SI lo recibe, la ficha existe y no está dada de baja y el
  paciente está en la cartera activa del médico (RF-50), ENTONCES además deja marcada la enfermedad a ese paciente
  (si ya la tenía no cambia nada; nunca desmarca); SI no lo recibe, ENTONCES no marca; SI lo recibe pero la ficha no
  existe o está dada de baja o el paciente está fuera de la cartera, ENTONCES igual crea o reutiliza y la suma a la
  lista, no marca y lo indica (`paciente_no_encontrado` o `fuera_de_cartera`) en una respuesta 200. Responde con el
  estado de administrar, si se reutilizó, el nombre del catálogo, si ya estaba en la lista, si quedó marcada (o el
  motivo por el que no) y, si marcó, el estado completo de la sección de ese paciente.
- RF-55: EL SISTEMA (la API) nunca borra registros (solo cambia `activo`), nunca lee ni escribe `medico_infos` y
  recibe siempre los ids de paciente y enfermedad de forma explícita.

### Relación con otras secciones
- RF-56: EL SISTEMA no mezcla las enfermedades del paciente con la sección legada "Diagnósticos / enfermedades" de
  la app: no copia datos de una a otra y cada una se ve según sus reglas (los diagnósticos ya cargados, según la
  spec 001 RF-22 y RF-30).
- RF-57: EL SISTEMA no cambia nada de las historias clínicas que no son la HC general (pediatría y las demás), salvo
  el cierre de sesión de RF-59.

### Crear sin poder marcar
- RF-58: SI al tocar "Crear" el paciente de la consulta no está vinculado (sin vínculo, eligiendo ficha, sin ficha o
  con la ficha no disponible, RF-9, RF-10, RF-12), no está en la cartera (RF-11, o la web lo indica al crear según
  RF-54) o la consulta ya no se puede editar (cerrada o solo para leer, RF-35), ENTONCES EL SISTEMA igual crea o
  reutiliza la enfermedad y la suma a la lista del médico según RF-44, pero no la marca al paciente (en los dos
  primeros casos sin mandar el paciente, o con la indicación de la web; en el último, sin mandarlo), y muestra "Se
  agregó a tu lista; no se marcó al paciente porque <motivo>", con el motivo "el paciente no está vinculado a una
  ficha de la web", "el paciente no está en tu cartera" o "la consulta no se puede editar". SI se reutilizó una
  existente, ENTONCES el aviso empieza por "Ya existía en el catálogo como «<nombre>»." (o "«<nombre>» ya estaba en
  tu lista." si ya lo estaba). Si el paciente ya la tenía marcada, sigue marcada. *(Ajuste tras validación F2,
  2026-10-23.)* Cuando ya estaba en la lista y no se marcó, el aviso no dice "Se agregó a tu lista" (no se agregó) sino
  "«<nombre>» ya estaba en tu lista. No se marcó al paciente porque <motivo>"; si la web no da un motivo conocido, el aviso
  termina en "No se marcó al paciente." (sin "porque").

### Cierre de sesión (cambio del 2026-10-20)
- RF-59: CUANDO el usuario cierra sesión, EL SISTEMA revoca su token en turnos y le pide a cada HC con API que lo
  olvide; SI alguna no responde, ENTONCES igual cierra la sesión en el dispositivo.

## Requisitos no funcionales
- **Dueño del dato = la web**: el catálogo, las listas y las enfermedades de los pacientes viven en la base de la web;
  la copia del dispositivo es solo para mostrar (RF-32 a RF-34).
- Lo marcado en la app se ve en la web al recargarla, y lo marcado en la web se ve en la app al volver a abrir la
  sección o con la respuesta del próximo cambio (RF-48); no cambia en vivo con la pantalla abierta.
- La web aplica las reglas de marcable, cartera y baja lógica: la app no puede saltearlas.
- Pedidos (la API tiene `throttle:60,1` por IP): abrir la sección, uno (más la resolución del vínculo la primera vez,
  RF-8); abrir "Administrar enfermedades", uno; cada toque o cambio de la lista, uno; el buscador, ninguno. El
  catálogo es de cientos de enfermedades como máximo: no se pagina.
- Funciona en web, Android e iOS. Las reglas (estado, orden, motivos, nombre, copia) viven en `core/data`
  (commonMain), sin Compose ni red (constitución, principio 4).
- Ningún dato real (pacientes, sus enfermedades) en el repositorio, tampoco en tests ni docs.

## Excepción a la constitución, principio 2 (aprobada el 2026-10-19)
La constitución pide que todo lo clínico funcione sin conexión y se reconcilie después. La sección Enfermedades
**no permite escribir sin conexión**: solo muestra lo último recibido (RF-30, RF-31). Motivo: la dueña es la web,
el dato es del paciente (compartido entre médicos) y un marcado diferido podría pisar lo que otro médico cambió
mientras tanto. Aprobada por el usuario el 2026-10-19.

## Diferencias aceptadas con la web
- La app muestra las enfermedades dadas de baja en el catálogo que el paciente tenía, en "Otras" (RF-19); el
  resumen de la web no las muestra.
- La app muestra en "Otras" lo que marcaron otros médicos, igual que el resumen de la web.
- "Quitar" de la app solo desasocia (RF-41); la acción "eliminar" de la web, que además desmarca al paciente, no
  está en la app (RF-47).
- "Crear" en la app, como la pantalla "Enfermedad" de la web, suma al catálogo y a la lista del médico y marca al
  paciente de la consulta (RF-44); a diferencia de la web, si el paciente no está vinculado, está fuera de la
  cartera o la consulta no se puede editar, crea y suma a la lista sin marcar y lo avisa (RF-58).
- Mismo nombre por clave de nombre (RF-44, RF-54), no por la intercalación de MySQL; largo en caracteres, no en
  bytes.
- La API no exige `users.perfil == 3` (RF-49): para la app manda su configuración (spec 001).
- Los casos especiales de la web (perfil 9 que ve los pacientes del médico 6) no se replican, como en la spec 001.
- En telemedicina la sección aparece si el administrador la marcó para "Telemedicina" (spec 001 RF-8), aunque la web
  no la muestre ahí.

## Casos límite
- Enfermedad dada de baja en el catálogo que el paciente tenía: no se puede marcar ni desmarcar; sale en "Otras"
  (RF-19) y en el resumen de cerradas (RF-35). No aparece en "Administrar" (RF-39, RF-52).
- El médico quita de su lista una enfermedad que el paciente tiene: el paciente la sigue teniendo (RF-41).
- Dos médicos con listas distintas sobre el mismo paciente: cada uno ve en la grilla su lista y en "Otras" lo del
  otro; si los dos tienen la misma enfermedad, ven el mismo estado (es del paciente).
- Dos médicos (o la web y la app) cambian la misma enfermedad del mismo paciente casi a la vez: queda el último estado
  recibido por la web; como se envía el estado elegido y no "invertir" (RF-21, RF-51), no se deshacen entre sí.
- El médico toca rápido varias casillas: cada una va en su pedido; las respuestas que llegan desordenadas no pisan un
  estado más nuevo (RF-23).
- La respuesta no llega o llega tarde: RF-28; si llega después del nuevo pedido de estado, RF-23 la descarta.
- Mismo médico en dos dispositivos, o la lista cambiada desde la web con la ventana abierta: cada respuesta trae el
  estado completo (RF-48); un toque sobre una enfermedad que ya no está en la lista da RF-25.
- Nombres "Diabetes", "diabetes ", "DIABÉTES", "Diabetes  tipo 2" / "Diabetes tipo 2": mismo nombre, se reutiliza la
  existente (RF-44). El catálogo ya tiene duplicados por clave: se ven todos en el buscador y se reutiliza según
  RF-44.
- MySQL considera iguales dos nombres que la clave distingue (o al revés): RF-54.
- Nombre con espacios invisibles, de ancho cero o solo espacios: RF-43.
- Crear con el nombre de una dada de baja: se reactiva en el catálogo y vuelve a ser marcable para todos los médicos
  que la tienen en su lista, y queda marcada al paciente de la consulta (RF-44).
- Crear con el nombre de una que el paciente ya tiene (marcada por otro médico, en "Otras"): pasa a la grilla,
  marcada; nunca se desmarca (RF-44).
- Crear desde la ventana con el paciente sin vínculo, fuera de la cartera o la consulta ya no editable: se suma a la
  lista sin marcar, con aviso (RF-58).
- Paciente sin vínculo, con varias fichas posibles, sin ficha, con la ficha dada de baja o fuera de la cartera:
  RF-8 a RF-12. El vínculo guardado no se revalida (como en pediatría): corregirlo queda fuera de esta spec.
- Médico con "Enfermedades" en su perfil de la app pero otro perfil en la web: en la app usa la sección; en la web
  no la ve (la web la muestra solo al perfil 3), aunque sus datos quedan en la base de la web.
- Médico sin perfil, con la sección fuera del perfil o con la sección oculta: no ve la sección; ve lo del paciente
  en "Datos" si el dispositivo lo tiene (RF-6, RF-7). Con la sección oculta no tiene otro lugar para administrar su
  lista (caso aceptado; recomendación para después: ofrecer "Administrar enfermedades" también desde
  Configuración).
- "Enfermedades" en el perfil solo para "Consulta": en una telemedicina no se ve la sección, y tampoco en "Datos"
  (porque está en el perfil, RF-6).
- Licencia vencida, médico no vinculado, HC no habilitada: RF-13.
- Administrador o secretaria: no ven la sección (RF-4); la API les responde `sin_permiso` (RF-49).
- Demasiados pedidos seguidos (429): RF-26, RF-46.
- Cierre de sesión o cambio de médico en el dispositivo: se borra la copia (RF-34).

## Fuera de alcance
- Cambiar las pantallas de la web (incluida su pantalla "Enfermedad" y la sección en sus consultas).
- Renombrar o dar de baja enfermedades del catálogo, y "eliminar" de la web (RF-47); que el administrador administre
  el catálogo o las listas de los médicos.
- Historial de qué enfermedades tenía el paciente en cada consulta (RF-36).
- Marcar sin conexión y enviarlo después (excepción aprobada).
- Sincronizar el resto de la HC general con la web y el envío de consultas (spec 002). La resolución del vínculo y la
  cartera se usan de la spec 002 (RF-8 a RF-11 de esta spec); corregir un vínculo ya guardado, tampoco.
- Pasar los diagnósticos legados de la app a enfermedades del paciente.
- Las demás secciones propias de los perfiles 3 y 4 (antecedentes en tabla propia, vacunas, audio).
- Avisos en tiempo real de cambios hechos en otro lado.

## Criterios de finalización
- Tests en verde en `.\gradlew :core:data:jvmTest`, uno por cada RF de lógica:
  - `EnfermedadesSeccionTest`: grilla de marcables y su marca (RF-15), orden alfabético con desempate por id
    (RF-16), lista vacía con "Otras" y "Administrar" (RF-18), "Otras" (RF-19), cerrada/solo lectura con "Estado
    actual del paciente" y "Sin enfermedades registradas" (RF-35), "Datos" sin repetir (RF-6, RF-7).
  - `ColumnasEnfermedadesTest` (en `.\gradlew :core:ui:jvmTest`, sobre `columnasPara` de `core/ui`, PD4): 2 o 4
    columnas según el ancho disponible, borde en 600 (RF-17).
  - `MotivosEnfermedadesTest`: elegir ficha, sin ficha (los tres casos, con el texto de la 002), ficha no
    disponible, fuera de la cartera sin datos (RF-9 a RF-12), motivos generales (RF-13), error no previsto en el
    lugar de sin conexión (RF-14, RF-30) y su prioridad (RF-14).
  - `MarcadoEnfermedadesTest`: estado elegido y "Guardando…" (RF-21), casilla con pedido en camino bloqueada
    (RF-22), orden de respuestas y casillas en camino que conservan su estado (RF-23), aviso "Guardado" único que se
    renueva (RF-24), rechazos que piden el estado (RF-25), 429 (RF-26), rechazos que revierten (RF-27), sin respuesta
    en 10 s sin revertir a ciegas (RF-28), un motivo por vez (RF-29).
  - `CopiaEnfermedadesTest` (base en memoria): solo lectura sin conexión y "Reintentar" (RF-30), primera vez sin
    conexión (RF-31), nunca `dirty` (RF-32), un error nunca pisa la copia y una lista vacía sí (RF-33), borrado al
    cerrar sesión y al cambiar de médico (RF-34), borrado del paciente fuera de la cartera (RF-11).
  - `AdministrarEnfermedadesTest`: botón sin paciente vinculado (RF-37), filtro del buscador por clave con
    duplicados y buscador vacío (RF-39), agregar y quitar sin desmarcar con el estado de la respuesta (RF-40,
    RF-41), cuándo se ofrece "Crear" (RF-42), crear (nueva, reutilizada, reactivada, ya en la lista) manda el
    paciente y la grilla la muestra marcada con el estado de la respuesta, y una ya marcada sigue marcada (RF-44),
    crear sin vínculo, fuera de la cartera o con la consulta no editable: no manda el paciente o no marca, con el
    aviso y el motivo correspondientes (RF-58), un cambio por vez (RF-45), conservar lo escrito ante un rechazo
    (RF-46), estado completo de la respuesta (RF-48).
  - `NombreEnfermedadTest`: nombre normalizado, mismo nombre por clave, largo en caracteres (255 sí, 256 no, con
    emojis), invisibles y solo espacios (RF-43, RF-44).
- Los endpoints nuevos de `hclinica` probados sin tests de phpunit que escriban en la base (la local tiene datos
  reales, PD5): las reglas sin base en `tests/Unit/EnfermedadesReglasTest.php`, y el resto con el humo con curl
  sobre datos de prueba y el E2E de la app contra la copia local, con limpieza por SQL puntual: `sin_permiso`
  para el administrador y sin mirar `users.perfil` (RF-49), estado con listas vacías, 404 y `fuera_de_cartera`
  (RF-50), marcar idempotente, `no_en_tu_lista` y `enfermedad_dada_de_baja` (RF-51), estado de administrar (RF-52),
  quitar no desmarca a ningún paciente (RF-53), crear con reutilización por clave, menor id, reactivación, nombre
  inválido y choque con el `unique` (RF-54), crear con paciente de la cartera marca (también reutilizada, reactivada
  o ya en la lista) y devuelve el estado de la sección, ya marcada sigue marcada, sin paciente no marca, con paciente
  inexistente, dado de baja o fuera de la cartera crea y suma a la lista sin marcar con el motivo (RF-54), sin
  borrados ni `medico_infos` (RF-55).
- Cierre de sesión (RF-59): con conexión, después de cerrar sesión el token ya no entra a turnos ni a ninguna HC con
  API; con una HC caída o sin conexión, la sesión se cierra igual en el dispositivo.
- Verificación manual: RF-1 a RF-5, RF-17 (aspecto), RF-20 (teclado y lector de pantalla), RF-38, RF-56, RF-57.
- De punta a punta contra la copia local (nunca producción): el administrador agrega "Enfermedades" a un perfil; el
  médico de prueba administra su lista desde la sección, crea enfermedades con el prefijo "Prueba Salud360 …", marca
  y desmarca en la app y lo ve igual en la web, y al revés. Al terminar, la copia local queda como estaba: se
  limpia por SQL lo creado con el prefijo de prueba (catálogo, listas y marcas) y se restauran las marcas y listas
  tocadas.
- Sin conexión: la sección muestra lo último recibido, no deja cambiar y lo dice.

## Decisiones tomadas (2026-10-19)
- D1 "Quitar" = desasociar, no desmarca a nadie; "eliminar" de la web no está en la app (RF-41, RF-47, RF-53).
- D2 La API no exige `users.perfil == 3`; manda la configuración de la app (RF-49, casos límite).
- D3 El vínculo se resuelve al abrir la sección con la pieza de la 002; elegir ficha si hay varias (RF-8 a RF-10).
- D4 Prueba de punta a punta con prefijo "Prueba Salud360 …" y limpieza por SQL en la copia local (criterios).
- D5 Sin conexión, solo lectura: excepción a la constitución, principio 2 (RF-30, RF-31, sección de la excepción).
- D6 Copia en una tabla nueva, nunca `dirty`, borrada al cerrar sesión o cambiar de médico, solo reemplazada por
  respuestas correctas (RF-32 a RF-34, RF-50).
- D7 Mismo nombre por clave de nombre también en la API; nombre normalizado; 1 a 255 caracteres; duplicados del
  catálogo; invisibles (RF-42 a RF-44, RF-54).
- D8 Marcable = lista activa y catálogo activo; dadas de baja en "Otras" (RF-19, RF-39, diferencias).
- D9 Respuesta con el estado completo, orden de respuestas, casilla bloqueada, 10 s sin revertir a ciegas, 429,
  "Guardado" (RF-21 a RF-29).
- D10 Administrar no depende del paciente (RF-37); sección oculta sin otro lugar (casos límite).
- D11 Administrador y secretaria no ven la sección; `sin_permiso` en la API (RF-4, RF-49).
- D12 Sin perfil o fuera del perfil (para ningún tipo): sin sección, lo del paciente en "Datos" (RF-6, RF-7).
- D13 Lectura con cartera; fuera de la cartera, motivo y sin datos; "Otras" incluye lo de otros médicos (RF-11,
  RF-19, RF-50).
- D14 Un pedido al abrir y uno al administrar; buscador en el dispositivo; orden por clave; 2 o 4 columnas según
  el ancho disponible; sin paginado (RF-15 a RF-17, RF-38, RF-39).
- D15 Cerradas: estado actual con leyenda, sin repetir "Datos" (RF-7, RF-35).
- D16 Mismos motivos y textos que el resto de la HC general (RF-13, RF-14).
- D17 Casos especiales de la web no se replican (diferencias).
- D18 Cada respuesta trae el estado completo; sin tiempo real (RF-48).
- D19 2026-10-19: crear marca al paciente de la consulta, como la web (RF-44, RF-54, RF-58).

## Dudas abiertas
- (ninguna)

Revisada por QA el 2026-10-19 (53 hallazgos) y corregida.

Cambio aprobado el 2026-10-20 (al aprobar el plan, PD2, PD3 y PD7): RF-10 cubre los tres casos sin ficha posible
(documento inválido, ficha ya vinculada a otro paciente, ficha elegida no posible), en el lugar 7 de RF-14 y con el
texto de la 002 que dice qué hacer, en lugar de un texto fijo (el `resolver` de la 002 crea la ficha cuando no hay
ninguna, así que el caso original no ocurre); los errores no previstos al abrir se tratan como sin conexión, con el
texto de la web (RF-14, RF-30, RF-31); nuevo RF-59 (cierre de sesión revoca el token en turnos y en cada HC con API,
porque las HC guardan el token aceptado unos minutos y un token revocado seguía entrando), con RF-57 ajustado.

Ajuste tras validación F2 (2026-10-23, tarea F2.11): se reflejan dos desviaciones ya aceptadas y implementadas, sin
cambiar nada más: el aviso de «ya estaba en tu lista» cuando no se marcó al paciente, con su motivo (RF-58, E10 de
`tasks.md`), y qué pasa cuando la web rechaza el pedido del estado que sigue a un toque sin respuesta (RF-28).
