# Spec 005 — Agenda por médico: quién tiene turnos y quién se ve para pedirlos

Estado: aprobada por el usuario el 2026-11-04 (D1 a D3 resueltas); implementada (2026-11-05), probada a mano (los hallazgos 1 y 4 del reviewer —cierre por ruta de `CONFIG_AGENDA` y de las rutas de administración; `guardarAgenda` marca limpia la fila con ediciones locales pendientes— quedan como pendientes menores)

## Contexto y objetivo
El panel de administración tiene, por médico, dos interruptores: «Tiene agenda de turnos» y «Visible para pacientes en la web». Hoy solo cambian la copia del dispositivo del administrador: turnosonlinebb (dueño de la agenda) no se entera, y la próxima vez que la app trae el catálogo los pisa. Además, lo que la app considera «tiene agenda» se deduce de si el médico tiene horarios cargados, no de una decisión del administrador, y «visible para pacientes» ni siquiera se lee del servidor.

Consecuencia: el médico que solo usa historia clínica sigue viendo opciones de turnos (por ejemplo «Horarios de atención»), y el administrador no puede decidir de verdad quién maneja turnos ni quién se ofrece al paciente.

Objetivo: que los dos interruptores sean reales. Que queden guardados en turnosonlinebb, y que la app muestre u oculte todo lo relacionado con turnos según ellos.

## Usuarios
- **Administrador:** decide, médico por médico, si tiene agenda y si se ofrece a los pacientes.
- **Médico solo con historia clínica** (sin agenda): no ve nada de turnos.
- **Médico con agenda interna** (con agenda, no visible para pacientes): maneja su agenda y la de su consultorio, pero el paciente no puede pedirle turno por la web.
- **Médico con agenda pública** (con agenda y visible): igual que el anterior, y además el paciente puede pedirle turno.
- **Médico con historia clínica y agenda:** las dos cosas son independientes; ninguna afecta a la otra.
- **Secretaria:** trabaja con la agenda de los médicos que tienen agenda.
- **Paciente (web de turnos):** ve para pedir turno solo a los médicos visibles.

## Historias de usuario
- HU-1. Como administrador, quiero indicar si un médico tiene agenda de turnos, para que quien solo usa historia clínica no vea nada de turnos.
- HU-2. Como administrador, quiero indicar aparte si un médico es visible para los pacientes, para tener médicos con agenda interna que no se ofrecen en la web.
- HU-3. Como administrador, quiero que lo que marco quede guardado y no se pierda al sincronizar, para poder confiar en el panel.
- HU-4. Como médico con agenda, quiero ver todo lo de turnos (incluidos los horarios de atención) aunque todavía no haya cargado ninguno, para poder empezar a usarla.
- HU-5. Como médico solo con historia clínica, quiero que la app no me muestre opciones de turnos que no uso.

## Definiciones
- **Tiene agenda:** el médico maneja turnos. Habilita Agenda, Recetas, Horarios de atención y el resto de la configuración de turnos (obras sociales, reservas, cupo, módulos, mensajes). Es una decisión del administrador, no se deduce de los horarios cargados.
- **Visible para pacientes:** el médico aparece en las listas donde el paciente elige a quién pedirle turno. Solo tiene sentido si el médico tiene agenda.
- Combinaciones válidas: sin agenda; con agenda interna (no visible); con agenda pública (visible). «Sin agenda y visible» no es válida.

## Requisitos funcionales
- RF-1: EL SISTEMA guarda, por médico y en turnosonlinebb, dos datos independientes: «tiene agenda» y «visible para pacientes».
- RF-2: CUANDO el administrador cambia uno de los dos interruptores, EL SISTEMA lo guarda en turnosonlinebb y muestra el nuevo estado solo después de que turnosonlinebb lo confirma.
- RF-3: SI turnosonlinebb no confirma el cambio, ENTONCES EL SISTEMA deja el interruptor como estaba y le dice al administrador que no se pudo guardar y por qué.
- RF-4: EL SISTEMA permite cambiar estos dos datos solo al administrador.
- RF-5: CUANDO el administrador apaga «Tiene agenda» de un médico, EL SISTEMA lo deja también como no visible para pacientes (ver D1).
- RF-6: MIENTRAS un médico no tiene agenda, EL SISTEMA no permite marcarlo como visible para pacientes.
- RF-7: MIENTRAS un médico no tiene agenda, EL SISTEMA no le muestra Agenda, Recetas ni ninguna sección de configuración de turnos (incluido «Horarios de atención»), y sí le sigue mostrando la configuración de su historia clínica.
- RF-8: MIENTRAS un médico tiene agenda, EL SISTEMA le muestra todo lo de turnos, tenga o no horarios cargados.
- RF-9: MIENTRAS un médico no tiene agenda, EL SISTEMA no ofrece en el panel de administración los accesos a horarios y a la configuración de agenda de ese médico.
- RF-10: MIENTRAS un médico no tiene agenda, EL SISTEMA no le muestra su agenda ni sus turnos a la secretaria, ni le ofrece «Obras sociales» ni el botón de nuevo turno; MIENTRAS la tiene, se la muestra aunque no sea visible para pacientes.
- RF-11: EL SISTEMA (turnosonlinebb) ofrece a los pacientes, para pedir turno, solo a los médicos visibles.
- RF-12: CUANDO la app trae el catálogo o el perfil, EL SISTEMA toma de turnosonlinebb el estado real de los dos datos, y no una copia propia ni lo que deduce de los horarios.
- RF-13: SI turnosonlinebb todavía no informa «tiene agenda» (servidor sin actualizar), ENTONCES EL SISTEMA usa el criterio de hoy (tiene horarios cargados) y, al intentar guardar, avisa que turnosonlinebb todavía no lo permite.
- RF-14: CUANDO el administrador da de alta un médico, EL SISTEMA le deja elegir si tiene agenda y si es visible; por omisión, sin agenda y no visible.
- RF-15: CUANDO el administrador cambia estos datos, EL SISTEMA los refleja en la sesión del médico a más tardar en su próximo inicio de sesión.
- RF-16: EL SISTEMA no cambia nada de la historia clínica del médico por estos interruptores.
- RF-17: AL activarse la función, EL SISTEMA no cambia el estado de ningún médico: «tiene agenda» arranca igual a «tiene horarios cargados» y «visible» arranca igual a lo que hoy muestra la web.
- RF-18: CUANDO el administrador apaga «Tiene agenda» de un médico con turnos futuros, EL SISTEMA le informa cuántos son y no los cancela ni bloquea el cambio.

## Requisitos no funcionales
- Se asume conexión para cambiar los interruptores (decisión del usuario, 2026-11-04). Para leer, la app usa la última copia guardada, como el resto.
- Igual en web, Android e iOS.
- El dueño del dato es turnosonlinebb (agenda e identidad); la base del dispositivo es solo copia y estos datos no quedan pendientes de envío.
- Sin datos sensibles nuevos.

## Casos límite
- Apagar la agenda de un médico con horarios cargados: los horarios no se borran; al volver a prenderla reaparecen.
- Apagar la agenda de un médico con turnos futuros ya dados: RF-18 (se avisa la cantidad; los turnos siguen vigentes).
- Médico que la web antigua ya muestra a los pacientes pero no tiene horarios cargados («visible» sin agenda): se informa tal cual está, sin corregirlo solo; el administrador lo resuelve (ver el diagnóstico del brief del servidor).
- Médico con sesión abierta cuando el administrador cambia el interruptor: ve el cambio al próximo inicio de sesión (RF-15).
- Médico dado de baja: los interruptores no cambian su acceso a la historia clínica.
- Dos administradores cambian el mismo médico a la vez: queda el último guardado.
- Servidor de turnosonlinebb sin actualizar: RF-13.

## Fuera de alcance
- Los demás campos de la tarjeta del médico en el panel (consultorio, especialidad, teléfono, mail) también son locales hoy; quedan como pendiente aparte.
- Cambiar el significado de «Mostrar Medico» en la web antigua de turnos.
- Cancelar o reprogramar turnos existentes.
- Implementar el servidor: los cambios en la API Laravel de turnosonlinebb son una dependencia fuera de este repo (se listan en el plan, con orden de despliegue).

## Criterios de finalización
- Con servidor actualizado: el administrador apaga «Tiene agenda» de un médico solo con historia clínica y ese médico, al volver a entrar, no ve Agenda, Recetas ni nada de configuración de turnos (en particular, «Horarios de atención»).
- Con agenda prendida y sin horarios cargados, el médico ve todo lo de turnos.
- Un médico con agenda interna no aparece en la web para pedir turno; uno con agenda pública sí.
- Un cambio que turnosonlinebb rechaza no cambia el interruptor y avisa.
- Después de sincronizar, los interruptores siguen como los dejó el administrador.
- Los tests de las reglas pasan; los RF de interfaz se probaron a mano contra turnosonlinebb local, nunca contra producción.

## Decididas
- D0-a (usuario, 2026-11-04): los dos datos son independientes, porque hay médicos con agenda interna que no se ofrecen a los pacientes.
- D0-b (usuario, 2026-11-04): el estado se guarda en una columna propia de turnosonlinebb para «tiene agenda» (no se reutiliza la que hoy decide la visibilidad).
- D0-c (usuario, 2026-11-04): se asume conexión siempre para cambiar los interruptores.

- D1 (usuario, 2026-11-04): apagar «Tiene agenda» apaga también «Visible» (RF-5).
- D2 (usuario, 2026-11-04): al apagar la agenda de un médico con turnos futuros solo se avisa la cantidad; no se cancela nada ni se bloquea (RF-18).
- D3 (usuario, 2026-11-04): el servidor lo implementa el usuario en la otra PC, donde está el repo de turnosonlinebb, siguiendo `servidor-turnos.md` (esta misma carpeta). (Corrección documental 2026-11-05: la copia de MAMP de esta Mac hoy es un repo git sincronizado con producción, y fue la base de la implementación local.)
- Q1 (usuario, 2026-11-04): cambio menor de requisitos sobre RF-10. Cuando el médico elegido no tiene agenda, a la secretaria tampoco se le ofrece «Obras sociales» ni el botón de nuevo turno (además de no ver su agenda ni sus turnos).
- Q2 (usuario, 2026-11-04): turnos informa «tiene agenda» y «visible para pacientes» también para los médicos de la secretaria (no solo en el catálogo y en el perfil del médico), para que RF-10 y RF-12 valgan para ella. Va en `servidor-turnos.md`.

## Dudas abiertas
Ninguna.
