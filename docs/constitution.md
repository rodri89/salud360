# Constitución — Salud 360

Principios innegociables. Toda spec, plan y tarea debe cumplirlos; si alguno choca con un pedido, se para y se pregunta.

1. **La spec manda**: nada se implementa si no está en la spec activa (`specs/NNN-*/`). Si falta una decisión, se para y se pregunta.
2. **El dueño del dato manda**: identidad y agenda son de turnosonlinebb; historia clínica y licencias, de la base de cada
   especialidad. La base del dispositivo es caché y modo sin señal: todo lo clínico funciona sin conexión y se reconcilia después.
3. **Los datos clínicos son sagrados**: nunca se pisa un dato con vacíos ni se inventa una respuesta que el médico no dio;
   la base local cambia solo con migraciones compatibles. Repo público: ni datos reales, ni tokens, ni secretos.
4. **Lógica separada de interfaz**: las reglas viven en `core/*` (commonMain), como funciones sin Compose ni red que
   reciben "hoy" o el reloj como parámetro. Las pantallas (`features/*`, `especialidades/*`) solo muestran y delegan.
5. **Tests como puerta**: cada RF de lógica tiene su test en `commonTest`/`jvmTest` y se corre con `.\gradlew :<módulo>:jvmTest`.
   Prohibido avanzar con tests en rojo. Las pruebas de punta a punta nunca contra producción sin pedido explícito.
6. **Idioma**: código, interfaz, docs, specs y commits en español, como el resto del proyecto.
