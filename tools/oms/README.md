# Tablas OMS de crecimiento

`generar_tablas.py` convierte las tablas LMS de la OMS en los objetos Kotlin de
`core/crecimiento/src/commonMain/kotlin/com/salud360/core/crecimiento/tablas/`. Se corre una sola vez
(o cuando la OMS publique una revisión); en el repo queda solo el Kotlin generado.

## Fuentes

Patrones OMS 2006 (0 a 5 años), tablas por mes con L, M, S. Publicadas en
https://www.who.int/tools/child-growth-standards/standards (peso/edad, longitud-talla/edad, perímetro cefálico/edad,
IMC/edad). Los mismos datos en JSON, más cómodos para el script, están en el proyecto pygrowup:

```
https://raw.githubusercontent.com/ewheeler/pygrowup/master/pygrowup/tables/<archivo>
  wfa_boys_0_5_zscores.json   wfa_girls_0_5_zscores.json
  lhfa_boys_0_5_zscores.json  lhfa_girls_0_5_zscores.json
  hcfa_boys_0_5_zscores.json  hcfa_girls_0_5_zscores.json
  bmifa_boys_0_2_zscores.json bmifa_boys_2_5_zscores.json
  bmifa_girls_0_2_zscores.json bmifa_girls_2_5_zscores.json
```

Referencia OMS 2007 (5 a 19 años), publicada en https://www.who.int/tools/growth-reference-data-for-5to19-years.
El paquete oficial R `anthroplus` de la OMS trae los mismos LMS en texto tabulado:

```
https://raw.githubusercontent.com/WorldHealthOrganization/anthroplus/main/data-raw/growthstandards/<archivo>
  wfawho2007.txt   (peso/edad, 61-120 meses)
  hfawho2007.txt   (talla/edad, 61-228 meses)
  bfawho2007.txt   (IMC/edad, 61-228 meses)
```

## Uso

```bash
mkdir -p /tmp/oms && cd /tmp/oms
# descargar los 13 archivos de arriba en esta carpeta
python3 tools/oms/generar_tablas.py /tmp/oms
./gradlew :core:crecimiento:jvmTest
```
