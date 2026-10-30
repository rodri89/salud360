package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta

/**
 * Prefijo de los ids locales de todo lo que se trae de la web de la HC general (spec 002, "Identificadores locales"):
 * consulta `hcc-c<id>`, médico de otro `hcc-m<id>`, carga de laboratorio `hcc-l…`, fila de estudio o lista `hcc-r…`,
 * pendiente `hcc-p<id>`. Disjunto de `hcp-c…` (pediatría) y `hc-clinica-p…` (paciente de clínica).
 */
const val PREFIJO_ID_WEB = "hcc-"

/**
 * La consulta vino de la web de la HC general (`remoto_id` no vacío o id `hcc-…`): la web es su dueña y la app no la
 * edita, no la cierra ni la retoma como propia. Solo tiene sentido frente a un backend de solo lectura: en pediatría una
 * consulta creada en el dispositivo también tiene `remoto_id` una vez enviada. La consulta SQL `consultaAbiertaLocal`
 * aplica el mismo criterio (con [PREFIJO_ID_WEB] como parámetro) y un test las compara.
 */
fun Consulta.esDeLaWeb(): Boolean = remotoId.isNotBlank() || id.startsWith(PREFIJO_ID_WEB)
