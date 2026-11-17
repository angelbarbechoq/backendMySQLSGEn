package com.example.backend.modelo;

import com.example.dataacquisition.RutaArchivosEnergia;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Los tipos de tabla que hoy graba la app principal en SQLite. Cada {@link Fila} lleva uno: es lo que va a decidir la
 * tabla de MySQL cuando esté lista. {@link #archivoSqlite} dice dónde la guardaría la app hoy, para comparar.
 */
public enum TipoTabla {
    /** Contador de energía (columna kwh). También los sensores del PLC3 (temperaturas y presiones), con el mismo esquema. */
    ENERGIA("Energía (kWh)"),
    /** Tensiones, corrientes, potencia, PF y kWh de retorno. */
    VIP("Tensión, corriente y potencia (VIP)"),
    /** Parámetros de calidad de energía de los medidores por pasarela. */
    CALIDAD("Calidad de energía"),
    /** Temperatura de los mezcladores (PV y SV del DTB48). */
    MEZCLADOR("Mezcladores"),
    /** Lectura de los controladores de los generadores (solo lectura). */
    GENERADOR("Generadores");

    private final String titulo;

    TipoTabla(String titulo) {
        this.titulo = titulo;
    }

    public String titulo() {
        return titulo;
    }

    /** Archivo SQLite mensual donde la app principal guarda esta fila (sin la carpeta base). */
    public String archivoSqlite(LocalDateTime fecha) {
        return archivosSqlite(fecha).getFirst();
    }

    /**
     * Todos los archivos donde la app guarda esta fila, sin la carpeta base: el mensual primero y, para energía, VIP y
     * mezcladores, también el diario ({dd}{mes}, {dd}{mes}VIP). Calidad y generador solo tienen mensual.
     */
    public List<String> archivosSqlite(LocalDateTime fecha) {
        String mes = RutaArchivosEnergia.getNombreMes(fecha.getMonthValue());
        String carpeta = fecha.getYear() + "\\" + mes + "\\";
        String dia = String.format("%02d", fecha.getDayOfMonth());
        return switch (this) {
            case ENERGIA, MEZCLADOR -> List.of(carpeta + mes, carpeta + dia + mes);
            case VIP -> List.of(carpeta + mes + "VIP", carpeta + dia + mes + "VIP");
            case CALIDAD -> List.of(carpeta + mes + "Calidad");
            case GENERADOR -> List.of(carpeta + mes + "Generador");
        };
    }
}
