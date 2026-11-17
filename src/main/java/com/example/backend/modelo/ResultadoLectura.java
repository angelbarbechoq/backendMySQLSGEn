package com.example.backend.modelo;

import java.util.ArrayList;
import java.util.List;

/** Filas, estado de los equipos y comunicación de cada línea que deja la lectura de un grupo en un ciclo. */
public record ResultadoLectura(List<Fila> filas, List<EstadoDispositivo> estados, List<Conectividad> conectividad) {

    public static ResultadoLectura nuevo() {
        return new ResultadoLectura(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }
}
