package com.salud360.especialidades.pediatria

import com.salud360.especialidades.pediatria.TipoHito.LENGUAJE
import com.salud360.especialidades.pediatria.TipoHito.MOTOR_FINO
import com.salud360.especialidades.pediatria.TipoHito.MOTOR_GRUESO
import com.salud360.especialidades.pediatria.TipoHito.PSICOSOCIAL

/**
 * Hito del desarrollo madurativo, espejo de una fila de la tabla `desarrollo_madurativos` de hc_pediatria
 * (`id`, `mes`, `tipo`, `descripcion`). Se guarda en `seccion_valor` de la consulta con la clave [clave]
 * ("1" logrado / "0" no logrado), que lleva el mismo id que usa la web en `desarrollo_madurativo_pacientes`.
 */
data class HitoDesarrollo(val id: Int, val mes: Int, val tipo: TipoHito, val descripcion: String) {
    val clave: String get() = "dm_$id"
}

/** Áreas del desarrollo (columnas de la tabla), con el valor de `desarrollo_madurativos.tipo` en la web. */
enum class TipoHito(val codigo: String, val etiqueta: String) {
    MOTOR_GRUESO("Motor Grueso", "Motor grueso"),
    MOTOR_FINO("Motor Fino", "Motor fino"),
    PSICOSOCIAL("Psicosocial", "Psicosocial"),
    LENGUAJE("Lenguaje", "Lenguaje"),
}

/**
 * Catálogo real de hitos (volcado de `desarrollo_madurativos` activos, sin la fila "Observacion"), organizado en
 * tramos de edad. El tramo que corresponde a una edad es el mayor `mes` menor o igual a la edad en meses, como
 * redondea la web (8 → 7, 10-11 → 9, 13-14 → 12, 16-17 → 15, 19-23 → 18, 24 en adelante → 24).
 *
 * Para regenerarlo: SELECT id, mes, tipo, descripcion FROM desarrollo_madurativos WHERE activo = 1 AND tipo <> 'Observacion'
 * ORDER BY mes, FIELD(tipo,'Motor Grueso','Motor Fino','Psicosocial','Lenguaje'), id;
 */
object HitosDesarrollo {
    private fun h(id: Int, mes: Int, tipo: TipoHito, descripcion: String) = HitoDesarrollo(id, mes, tipo, descripcion)

    val catalogo: List<HitoDesarrollo> = listOf(
        h(1, 0, MOTOR_GRUESO, "Reflejos de búsqueda y succión"),
        h(2, 0, MOTOR_GRUESO, "Moro"),
        h(3, 0, MOTOR_GRUESO, "Posicion esgrimista"),
        h(4, 0, MOTOR_GRUESO, "Reflejo de marcha"),
        h(5, 0, MOTOR_FINO, "Prension palmar refleja"),
        h(6, 0, PSICOSOCIAL, "Fijacion ocular (20 días)"),
        h(7, 0, PSICOSOCIAL, "Sonrisa refleja"),
        h(178, 0, PSICOSOCIAL, "Se calma al hablarle"),
        h(8, 0, LENGUAJE, "Responde al sonido"),
        h(9, 0, LENGUAJE, "Gira la cabeza"),
        h(10, 1, MOTOR_GRUESO, "Reflejos de búsqueda y succión"),
        h(11, 1, MOTOR_GRUESO, "Moro"),
        h(12, 1, MOTOR_GRUESO, "Posicion esgrimista no dominante"),
        h(13, 1, MOTOR_GRUESO, "Angulo popliteo de 90°"),
        h(14, 1, MOTOR_FINO, "Prension palmar refleja"),
        h(15, 1, PSICOSOCIAL, "Fijacion y seguimiento ocular"),
        h(16, 1, PSICOSOCIAL, "Sonrisa refleja"),
        h(17, 1, PSICOSOCIAL, "Se calma al hablarle y alzarlo"),
        h(18, 1, LENGUAJE, "Responde al sonido y a voces conocidas"),
        h(19, 1, LENGUAJE, "Gira la cabeza"),
        h(20, 2, MOTOR_GRUESO, "Reflejos de succión dormido"),
        h(21, 2, MOTOR_GRUESO, "Moro"),
        h(22, 2, MOTOR_GRUESO, "Posicion esgrimista no dominante"),
        h(23, 2, MOTOR_GRUESO, "Angulo popliteo de 90°"),
        h(24, 2, MOTOR_FINO, "Prension palmar refleja"),
        h(25, 2, MOTOR_FINO, "Manos abiertas en vigilia"),
        h(26, 2, PSICOSOCIAL, "Sonrisa social"),
        h(27, 2, PSICOSOCIAL, "Se calma al hablarle y alzarlo"),
        h(28, 2, LENGUAJE, "Responde al sonido y a voces conocidas"),
        h(29, 2, LENGUAJE, "Gira la cabeza"),
        h(30, 2, LENGUAJE, "Inicia vocalizaciones: hace eco al que habla"),
        h(32, 3, MOTOR_GRUESO, "Moro atenuado"),
        h(33, 3, MOTOR_GRUESO, "Cara en línea media"),
        h(34, 3, MOTOR_GRUESO, "Sostén cefálico en posición sentada"),
        h(35, 3, MOTOR_GRUESO, "Angulo poplíteo de 90°"),
        h(36, 3, MOTOR_FINO, "Manos abiertas en vigilia"),
        h(37, 3, MOTOR_FINO, "Puede sostener sonajero"),
        h(38, 3, PSICOSOCIAL, "Sonrisa social"),
        h(39, 3, PSICOSOCIAL, "Se calma al hablarle y alzarlo"),
        h(40, 3, PSICOSOCIAL, "Se comunican a través de movimientos corporales"),
        h(41, 3, PSICOSOCIAL, "Algunos ríen a carcajada"),
        h(42, 3, LENGUAJE, "Responde al sonido y a voces conocidas"),
        h(43, 3, LENGUAJE, "Con el displacer grita o llora"),
        h(44, 4, MOTOR_GRUESO, "Moro atenuado"),
        h(45, 4, MOTOR_GRUESO, "Cara y manos en línea media"),
        h(46, 4, MOTOR_GRUESO, "Sostiene firmemente la cabeza"),
        h(47, 4, MOTOR_GRUESO, "Balconeo"),
        h(48, 4, MOTOR_FINO, "Manos abiertas en vigilia"),
        h(49, 4, MOTOR_FINO, "Mira las manos y las lleva a la boca"),
        h(50, 4, MOTOR_FINO, "Puede sostener sonajero y mirarlo"),
        h(51, 4, MOTOR_FINO, "Prensión palmar voluntaria"),
        h(52, 4, PSICOSOCIAL, "Se ríe a carcajadas"),
        h(53, 4, PSICOSOCIAL, "Prefiere a sus padres"),
        h(54, 4, PSICOSOCIAL, "Elije compañía"),
        h(55, 4, LENGUAJE, "Detiene el llanto cuando se le habla"),
        h(56, 4, LENGUAJE, "Hace eco del que habla"),
        h(57, 4, LENGUAJE, "Vocaliza cuando el adulto se calla"),
        h(58, 5, MOTOR_GRUESO, "Trípode"),
        h(179, 5, MOTOR_GRUESO, "Balconeo"),
        h(59, 5, MOTOR_FINO, "Junta manos en la línea media y las lleva a la boca"),
        h(60, 5, MOTOR_FINO, "Aproximación al objeto usando la mano como un rastrillo"),
        h(61, 5, MOTOR_FINO, "Pasa objetos de una mano a la otra"),
        h(62, 5, PSICOSOCIAL, "Se ríe a carcajadas"),
        h(63, 5, PSICOSOCIAL, "Prefiere a sus padres"),
        h(64, 5, PSICOSOCIAL, "Elije compañía"),
        h(65, 5, PSICOSOCIAL, "Le gusta mirarse en el espejo"),
        h(66, 5, LENGUAJE, "Detiene el llanto cuando se le habla"),
        h(67, 5, LENGUAJE, "Da matices a su llanto y gorjeos comunicando sus emociones"),
        h(68, 5, LENGUAJE, "Vocaliza en respuesta"),
        h(69, 5, LENGUAJE, "Balbuceo"),
        h(70, 6, MOTOR_GRUESO, "Trípode"),
        h(71, 6, MOTOR_GRUESO, "Rola"),
        h(72, 6, MOTOR_GRUESO, "Sin reflejo de Moro"),
        h(73, 6, MOTOR_FINO, "Prensión dígito palmar"),
        h(74, 6, MOTOR_FINO, "Pasa objetos de una mano a la otra"),
        h(75, 6, PSICOSOCIAL, "Empieza ansiedad ante extraños"),
        h(76, 6, PSICOSOCIAL, "Se irrita si no se cumple su voluntad"),
        h(77, 6, PSICOSOCIAL, "Le gusta mirarse en el espejo"),
        h(78, 6, LENGUAJE, "Comienza a utilizar consonantes en sus sílabas"),
        h(79, 6, LENGUAJE, "Comienza a imitar sonidos del lenguaje"),
        h(80, 7, MOTOR_GRUESO, "Se sienta sin apoyo el 80% a los 8 meses"),
        h(81, 7, MOTOR_GRUESO, "Puede reptar o gatear"),
        h(82, 7, MOTOR_FINO, "Comienza pinza radial inferior"),
        h(83, 7, MOTOR_FINO, "Prensión en tijeras"),
        h(84, 7, PSICOSOCIAL, "Ansiedad de separación"),
        h(85, 7, PSICOSOCIAL, "Juega a las escondidas"),
        h(86, 7, PSICOSOCIAL, "Llora ante extraños"),
        h(87, 7, PSICOSOCIAL, "Se irrita si no se cumple su voluntad"),
        h(88, 7, PSICOSOCIAL, "Disfruta del espejo"),
        h(89, 7, LENGUAJE, "Silabeo no específico"),
        h(90, 7, LENGUAJE, "Entiende al no"),
        h(91, 7, LENGUAJE, "Comienza a reconocer su nombre"),
        h(92, 7, LENGUAJE, "Diferentes matices al llanto"),
        h(93, 9, MOTOR_GRUESO, "Gatea, camina con apoyo"),
        h(94, 9, MOTOR_GRUESO, "Se para solo"),
        h(95, 9, MOTOR_FINO, "Pinza digital inferior"),
        h(96, 9, PSICOSOCIAL, "Ansiedad de separación"),
        h(97, 9, PSICOSOCIAL, "Aplaude"),
        h(98, 9, PSICOSOCIAL, "Puede comer con la mano"),
        h(99, 9, PSICOSOCIAL, "Tira un juguete y espera que lo recojan"),
        h(100, 9, PSICOSOCIAL, "Saluda con la mano"),
        h(101, 9, LENGUAJE, "Mamá y Papá"),
        h(102, 9, LENGUAJE, "Respuesta al no"),
        h(103, 9, LENGUAJE, "Comprende preguntas y ordenes sencillas"),
        h(104, 9, LENGUAJE, "Primeras palabras con sentido"),
        h(105, 12, MOTOR_GRUESO, "Gatea, camina con apoyo o da pasos solo"),
        h(106, 12, MOTOR_GRUESO, "Se para solo"),
        h(107, 12, MOTOR_FINO, "Pinza digital superior"),
        h(108, 12, MOTOR_FINO, "Pinza fina"),
        h(109, 12, MOTOR_FINO, "Señala con el dedo"),
        h(110, 12, MOTOR_FINO, "75% entrega objeto"),
        h(111, 12, PSICOSOCIAL, "Imita acciones"),
        h(112, 12, PSICOSOCIAL, "Viene cuando se lo llama"),
        h(113, 12, PSICOSOCIAL, "Saluda con la mano"),
        h(114, 12, PSICOSOCIAL, "Busca objetos escondidos"),
        h(115, 12, LENGUAJE, "35% dice palabra frase"),
        h(116, 12, LENGUAJE, "Jerigonza inmadura"),
        h(117, 12, LENGUAJE, "Comprende ordenes simples"),
        h(118, 12, LENGUAJE, "Respuesta al no"),
        h(119, 12, LENGUAJE, "Comprende preguntas y órdenes sencillas"),
        h(120, 12, LENGUAJE, "Primeras palabras con sentido"),
        h(121, 15, MOTOR_GRUESO, "Camina solo, se agacha y se levanta sin sostén"),
        h(122, 15, MOTOR_GRUESO, "Patea la pelota"),
        h(123, 15, MOTOR_GRUESO, "Se sube a la silla sin ayuda"),
        h(124, 15, MOTOR_GRUESO, "Gatea escaleras arriba"),
        h(125, 15, MOTOR_FINO, "Primeros garabatos"),
        h(126, 15, MOTOR_FINO, "Bebe de la taza"),
        h(127, 15, MOTOR_FINO, "Come solo (no con cubiertos)"),
        h(128, 15, MOTOR_FINO, "Trasvasa líquidos"),
        h(129, 15, PSICOSOCIAL, "Comprende función de los objetos"),
        h(130, 15, PSICOSOCIAL, "Imita tareas del hogar"),
        h(131, 15, PSICOSOCIAL, "Tiene juego simbólico (75%)"),
        h(132, 15, PSICOSOCIAL, "Prueba límites de los padres"),
        h(133, 15, LENGUAJE, "Dice 4 a 6 palabras sueltas"),
        h(134, 15, LENGUAJE, "Palabra frase"),
        h(135, 15, LENGUAJE, "Jerga gestual"),
        h(136, 15, LENGUAJE, "Imita cantos"),
        h(137, 15, LENGUAJE, "Comprende órdenes simples"),
        h(139, 18, MOTOR_GRUESO, "Camina, trepa (90%)"),
        h(140, 18, MOTOR_GRUESO, "Sube escaleras con apoyo"),
        h(141, 18, MOTOR_GRUESO, "Patea pelota"),
        h(142, 18, MOTOR_GRUESO, "Arrastra juguete"),
        h(143, 18, MOTOR_FINO, "Garabato"),
        h(144, 18, MOTOR_FINO, "Imita trazo vertical"),
        h(145, 18, MOTOR_FINO, "Usa tenedor"),
        h(146, 18, MOTOR_FINO, "Usa taza"),
        h(147, 18, MOTOR_FINO, "Torre 2 cubos"),
        h(148, 18, PSICOSOCIAL, "Juego simbólico (80%)"),
        h(149, 18, PSICOSOCIAL, "Acude al llamado del otro (75%)"),
        h(150, 18, PSICOSOCIAL, "Imita a los padres en tareas habituales"),
        h(151, 18, LENGUAJE, "Dice 10 a 15 palabras"),
        h(152, 18, LENGUAJE, "Dice no"),
        h(153, 18, LENGUAJE, "Comprende órdenes simples"),
        h(154, 18, LENGUAJE, "Palabra frase-rudimentaria"),
        h(156, 24, MOTOR_GRUESO, "Corre"),
        h(157, 24, MOTOR_GRUESO, "Sube y baja escaleras con dos pies con ayuda"),
        h(158, 24, MOTOR_GRUESO, "Trepa"),
        h(159, 24, MOTOR_GRUESO, "Lanza pelota"),
        h(160, 24, MOTOR_FINO, "Torre de 6 cubos"),
        h(161, 24, MOTOR_FINO, "Usa cuchara y tenedor"),
        h(162, 24, MOTOR_FINO, "Sostiene taza"),
        h(163, 24, MOTOR_FINO, "Dibuja línea vertical y círculo"),
        h(164, 24, MOTOR_FINO, "Garabato"),
        h(165, 24, PSICOSOCIAL, "Controla esfínteres diurno (50%)"),
        h(166, 24, PSICOSOCIAL, "Reconoce su sexo"),
        h(167, 24, PSICOSOCIAL, "Colabora al vestirse y a cepillarse los dientes"),
        h(168, 24, PSICOSOCIAL, "Juego en paralelo"),
        h(169, 24, PSICOSOCIAL, "Juego simbólico hacia un muñeco"),
        h(170, 24, LENGUAJE, "Cumple órdenes de dos pasos"),
        h(171, 24, LENGUAJE, "Aparece el yo, mi y mío"),
        h(172, 24, LENGUAJE, "Frase de dos palabras (sujeto y acción)"),
        h(173, 24, LENGUAJE, "Arriba/abajo"),
        h(174, 24, LENGUAJE, "Delante/detrás"),
        h(175, 24, LENGUAJE, "Concepto de 2"),
        h(176, 24, LENGUAJE, "Dice su nombre y edad"),
        h(177, 24, LENGUAJE, "Habla mientras juega"),
    )

    /** Meses en los que empieza cada tramo, como en la web. */
    val tramos: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 12, 15, 18, 24)

    /** Tramo (mes de inicio) que corresponde a una edad en meses. */
    fun tramoPara(meses: Int): Int = tramos.lastOrNull { it <= meses } ?: tramos.first()

    fun hitosDeTramo(tramo: Int): List<HitoDesarrollo> = catalogo.filter { it.mes == tramo }

    /** Hitos de un tramo por área, en el orden de las columnas de la tabla. */
    fun columnasDeTramo(tramo: Int): List<Pair<TipoHito, List<HitoDesarrollo>>> =
        TipoHito.entries.map { area -> area to hitosDeTramo(tramo).filter { it.tipo == area } }

    fun tituloDeTramo(tramo: Int): String = when (tramo) {
        0 -> "Menor de 1 mes"
        1 -> "1 mes"
        7 -> "7 y 8 meses"
        9 -> "9, 10 y 11 meses"
        12 -> "12 a 14 meses"
        15 -> "15 a 17 meses"
        18 -> "18 a 23 meses"
        24 -> "24 meses en adelante"
        else -> "$tramo meses"
    }

    /** Etiqueta corta del tramo para los chips ("RN", "1 m", "7-8 m", "24 m+"). */
    fun etiquetaCorta(tramo: Int): String = when (tramo) {
        0 -> "RN"; 7 -> "7-8 m"; 9 -> "9-11 m"; 12 -> "12-14 m"; 15 -> "15-17 m"; 18 -> "18-23 m"; 24 -> "24 m+"
        else -> "$tramo m"
    }
}
