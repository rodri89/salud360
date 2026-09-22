#!/usr/bin/env python3
"""
Genera las tablas LMS de la OMS (curvas de crecimiento) como código Kotlin para `core/crecimiento`.

Uso:
    python3 tools/oms/generar_tablas.py <carpeta_con_fuentes> [carpeta_destino_kotlin]

Fuentes esperadas en la carpeta (ver README.md de esta carpeta para los links de descarga):
  - Patrones OMS 2006 (0 a 60 meses), JSON con filas {Month, L, M, S}:
      wfa_boys_0_5_zscores.json, wfa_girls_0_5_zscores.json          peso / edad
      lhfa_boys_0_5_zscores.json, lhfa_girls_0_5_zscores.json        longitud-talla / edad
      hcfa_boys_0_5_zscores.json, hcfa_girls_0_5_zscores.json        perímetro cefálico / edad
      bmifa_boys_0_2_zscores.json, bmifa_boys_2_5_zscores.json       IMC / edad (0-24 acostado, 24-60 de pie)
      bmifa_girls_0_2_zscores.json, bmifa_girls_2_5_zscores.json
  - Referencia OMS 2007 (61 a 228 meses), texto tabulado con columnas sex(1 varón / 2 mujer), age(meses), l, m, s:
      wfawho2007.txt (peso, hasta 120 meses), hfawho2007.txt (talla), bfawho2007.txt (IMC)

Solo se versiona el Kotlin generado; los archivos fuente no van al repo.
"""
import json
import os
import sys

PAQUETE = "com.salud360.core.crecimiento.tablas"


def leer_json(carpeta, nombre, desde=0, hasta=60):
    with open(os.path.join(carpeta, nombre), encoding="utf-8") as f:
        filas = json.load(f)
    out = {}
    for fila in filas:
        mes = int(float(fila["Month"]))
        if desde <= mes <= hasta:
            out[mes] = (float(fila["L"]), float(fila["M"]), float(fila["S"]))
    return out


def leer_txt(carpeta, nombre, sexo, desde, hasta):
    out = {}
    with open(os.path.join(carpeta, nombre), encoding="utf-8") as f:
        encabezado = f.readline().strip().split("\t")
        idx = {c.strip().lower(): i for i, c in enumerate(encabezado)}
        for linea in f:
            partes = linea.strip().split("\t")
            if len(partes) < 5:
                continue
            if int(partes[idx["sex"]]) != sexo:
                continue
            mes = int(float(partes[idx["age"]]))
            if desde <= mes <= hasta:
                out[mes] = (float(partes[idx["l"]]), float(partes[idx["m"]]), float(partes[idx["s"]]))
    return out


def tabla(nombre_objeto, descripcion, filas):
    meses = sorted(filas)
    assert meses == list(range(meses[0], meses[-1] + 1)), "%s: faltan meses" % nombre_objeto
    fmt = lambda xs: ", ".join(repr(x) for x in xs)
    return (
        "/** %s (generado por tools/oms/generar_tablas.py, no editar a mano). */\n"
        "internal object %s {\n"
        "    val tabla = TablaLms(\n"
        "        meses = intArrayOf(%s),\n"
        "        l = doubleArrayOf(%s),\n"
        "        m = doubleArrayOf(%s),\n"
        "        s = doubleArrayOf(%s),\n"
        "    )\n"
        "}\n"
    ) % (
        descripcion, nombre_objeto,
        ", ".join(str(m) for m in meses),
        fmt(filas[m][0] for m in meses),
        fmt(filas[m][1] for m in meses),
        fmt(filas[m][2] for m in meses),
    )


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    fuentes = sys.argv[1]
    destino = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(__file__), "..", "..", "core", "crecimiento", "src", "commonMain", "kotlin",
        "com", "salud360", "core", "crecimiento", "tablas",
    )
    os.makedirs(destino, exist_ok=True)

    sexos = [("Varones", "boys", 1), ("Mujeres", "girls", 2)]
    archivos = []
    for etiqueta, sufijo, codigo in sexos:
        peso = leer_json(fuentes, "wfa_%s_0_5_zscores.json" % sufijo)
        peso.update(leer_txt(fuentes, "wfawho2007.txt", codigo, 61, 120))
        talla = leer_json(fuentes, "lhfa_%s_0_5_zscores.json" % sufijo)
        talla.update(leer_txt(fuentes, "hfawho2007.txt", codigo, 61, 228))
        pc = leer_json(fuentes, "hcfa_%s_0_5_zscores.json" % sufijo)
        imc = leer_json(fuentes, "bmifa_%s_0_2_zscores.json" % sufijo, 0, 23)
        imc.update(leer_json(fuentes, "bmifa_%s_2_5_zscores.json" % sufijo, 24, 60))
        imc.update(leer_txt(fuentes, "bfawho2007.txt", codigo, 61, 228))
        archivos += [
            ("PesoEdad%s" % etiqueta, "Peso para la edad, %s: OMS 2006 (0-60 m) + OMS 2007 (61-120 m), kg" % etiqueta.lower(), peso),
            ("TallaEdad%s" % etiqueta, "Longitud/talla para la edad, %s: OMS 2006 (0-60 m) + OMS 2007 (61-228 m), cm" % etiqueta.lower(), talla),
            ("PcEdad%s" % etiqueta, "Perímetro cefálico para la edad, %s: OMS 2006 (0-60 m), cm" % etiqueta.lower(), pc),
            ("ImcEdad%s" % etiqueta, "IMC para la edad, %s: OMS 2006 (0-60 m) + OMS 2007 (61-228 m), kg/m²" % etiqueta.lower(), imc),
        ]

    for nombre, descripcion, filas in archivos:
        ruta = os.path.join(destino, nombre + ".kt")
        with open(ruta, "w", encoding="utf-8") as f:
            f.write("package %s\n\n" % PAQUETE)
            f.write(tabla(nombre, descripcion, filas))
        print("%s: %d filas (%d-%d meses)" % (nombre, len(filas), min(filas), max(filas)))


if __name__ == "__main__":
    main()
