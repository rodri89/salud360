---
description: SDD · Entrevista y genera la spec (uso - /sdd-spec 002-nombre idea inicial)
agent: plan
---
NO escribas código en ningún momento. Leé docs/constitution.md, MEMORY.md y specs/README.md, y usá la skill sdd.

Carpeta de la spec: specs/$1/
Idea inicial (el texto que va después del nombre de la carpeta): $ARGUMENTS

Tu trabajo:
1. Haceme preguntas de UNA en UNA para eliminar ambigüedades (roles, dueño del dato, sin conexión,
   especialidades, errores, qué queda fuera de esta versión). Máximo 5 preguntas.
2. Con mis respuestas, generá specs/$1/spec.md siguiendo la plantilla de la skill sdd, con los requisitos en
   EARS y "Estado: borrador", y agregala a la tabla de specs/README.md.
3. Solo el QUÉ y el POR QUÉ: nada de stack, módulos ni nombres de archivos.
