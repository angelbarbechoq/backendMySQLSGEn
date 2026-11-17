package com.example.backend.web;

import com.example.backend.almacen.AlmacenLecturas;
import com.example.backend.ciclo.UltimoCiclo;
import com.example.backend.comparar.ComparadorArchivos;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import com.example.backend.modelo.Fila;
import com.example.backend.modelo.TipoTabla;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Datos para la pantalla: último ciclo, estado de los equipos y la última fila de cada tabla, agrupadas por tipo. */
@RestController
public class LecturasController {

    private final UltimoCiclo ultimo;
    private final AlmacenLecturas almacen;
    private final boolean lecturaHabilitada;
    private final int segundoCiclo;

    private final ComparadorArchivos comparador;

    public LecturasController(UltimoCiclo ultimo, AlmacenLecturas almacen, ComparadorArchivos comparador,
                              @Value("${colector.lectura.habilitada:true}") boolean lecturaHabilitada,
                              @Value("${colector.ciclo.segundo:30}") int segundoCiclo) {
        this.comparador = comparador;
        this.ultimo = ultimo;
        this.almacen = almacen;
        this.lecturaHabilitada = lecturaHabilitada;
        this.segundoCiclo = segundoCiclo;
    }

    @GetMapping("/api/lecturas")
    public Map<String, Object> lecturas() {
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("lecturaHabilitada", lecturaHabilitada);
        salida.put("segundoCiclo", segundoCiclo);
        salida.put("almacen", almacen.nombre());
        salida.put("ciclo", ultimo.resumen());

        List<Map<String, Object>> grupos = new ArrayList<>();
        List<Fila> filas = ultimo.ultimasFilas();
        for (TipoTabla tipo : TipoTabla.values()) {
            List<Fila> suyas = filas.stream().filter(f -> f.tipo() == tipo)
                    .sorted(Comparator.comparing(Fila::tabla, String.CASE_INSENSITIVE_ORDER)).toList();
            if (suyas.isEmpty()) continue;
            LinkedHashSet<String> columnas = new LinkedHashSet<>();
            suyas.forEach(f -> columnas.addAll(f.columnas().keySet()));
            List<Map<String, Object>> filasJson = new ArrayList<>();
            for (Fila f : suyas) {
                Map<String, Object> fj = new LinkedHashMap<>();
                fj.put("tabla", f.tabla());
                fj.put("fecha", f.fechaTexto());
                fj.put("archivo", f.archivoSqlite());
                fj.put("valores", f.columnas());
                filasJson.add(fj);
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("tipo", tipo.name());
            g.put("titulo", tipo.titulo());
            g.put("columnas", columnas);
            g.put("filas", filasJson);
            grupos.add(g);
        }
        salida.put("grupos", grupos);
        return salida;
    }

    /** Compara los archivos SQLite del backend con los de la app para un día (por defecto hoy). Solo lee. */
    @GetMapping("/api/comparar")
    public Map<String, Object> comparar(@RequestParam(required = false)
                                        @org.springframework.format.annotation.DateTimeFormat(
                                                iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dia) {
        return comparador.comparar(dia == null ? LocalDate.now() : dia);
    }
}
