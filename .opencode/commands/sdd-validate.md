---
description: SDD · Valida la spec RF por RF (tests de Gradle + pasos manuales)
agent: build
---
Recorré specs/$1/spec.md requisito por requisito. Para cada RF indicá qué test lo cubre y el resultado de
correrlo con `.\gradlew :<módulo>:jvmTest`.

Los RF de interfaz que no se puedan testear, verificalos con `.\gradlew devWeb` si el entorno local está
levantado; si no, dejá la lista exacta de pasos para que los pruebe yo. Nunca valides escribiendo en producción.

Si algún RF no está cubierto o falla, decilo claramente. NO arregles nada todavía.
Después comprobá los criterios de finalización y la constitución, y dame un veredicto: ¿la spec está cumplida?
