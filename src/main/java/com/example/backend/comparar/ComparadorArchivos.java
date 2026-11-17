package com.example.backend.comparar;

import com.example.backend.modelo.TipoTabla;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compara, para un día, los archivos SQLite que escribe el backend (carpeta de prueba) con los de la app principal.
 * Solo lee los dos lados (los de la app en modo solo lectura). Las lecturas no caen en el mismo segundo (cada programa
 * lee cuando le toca), así que se emparejan por minuto: lo que se espera es que cada tabla tenga filas en los dos lados
 * en los mismos minutos y que el contador de energía casi no difiera entre las dos lecturas de un mismo minuto.
 */
@Service
public class ComparadorArchivos {

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final String carpetaBackend;
    private final String carpetaApp;

    public ComparadorArchivos(@Value("${colector.sqlite.carpeta:}") String carpetaBackend,
                              @Value("${colector.comparar.carpeta:C:\\LineaBaseX}") String carpetaApp) {
        this.carpetaBackend = carpetaBackend;
        this.carpetaApp = carpetaApp;
    }

    /** Una tabla comparada. columna = la que se compara minuto a minuto (null si ninguna). */
    public record Tabla(String tabla, int filasApp, int filasBackend, int minutosEnComun, String columna,
                        Double diferenciaMaxima) {
    }

    public record Archivo(String archivo, String titulo, boolean existeApp, boolean existeBackend, List<Tabla> tablas) {
    }

    public Map<String, Object> comparar(LocalDate dia) {
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("dia", dia.toString());
        salida.put("carpetaBackend", carpetaBackend);
        salida.put("carpetaApp", carpetaApp);
        if (carpetaBackend.isBlank()) {
            salida.put("error", "El backend no está guardando en SQLite (colector.almacen=sqlite).");
            return salida;
        }
        var momento = dia.atStartOfDay();
        List<String> energia = TipoTabla.ENERGIA.archivosSqlite(momento);
        List<String> vip = TipoTabla.VIP.archivosSqlite(momento);
        List<Archivo> archivos = new ArrayList<>();
        try {
            // Los diarios: tienen solo ese día.
            archivos.add(comparar(energia.get(1), "Energía y mezcladores (diario)", dia, "kwh"));
            archivos.add(comparar(vip.get(1), "VIP (diario)", dia, "PW"));
            // Los mensuales: se filtra el día.
            archivos.add(comparar(energia.get(0), "Energía y mezcladores (mensual)", dia, "kwh"));
            archivos.add(comparar(vip.get(0), "VIP (mensual)", dia, "PW"));
            archivos.add(comparar(TipoTabla.CALIDAD.archivoSqlite(momento), "Calidad de energía", dia, null));
            archivos.add(comparar(TipoTabla.GENERADOR.archivoSqlite(momento), "Generadores", dia, "kw"));
        } catch (SQLException e) {
            salida.put("error", e.getMessage());
        }
        salida.put("archivos", archivos);
        return salida;
    }

    private Archivo comparar(String relativo, String titulo, LocalDate dia, String columna) throws SQLException {
        Path app = Path.of(carpetaApp, relativo);
        Path backend = Path.of(carpetaBackend, relativo);
        String prefijo = dia.format(DIA);
        Map<String, Map<String, Double>> ladoBackend = Files.exists(backend) ? leer(backend, prefijo, columna) : Map.of();
        Map<String, Map<String, Double>> ladoApp = Files.exists(app) ? leer(app, prefijo, columna) : Map.of();

        // De la app solo cuenta desde la primera fila del backend: antes el backend no estaba corriendo.
        String desde = ladoBackend.values().stream().flatMap(m -> m.keySet().stream()).min(String::compareTo).orElse(null);

        TreeSet<String> tablas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        tablas.addAll(ladoBackend.keySet());
        ladoApp.forEach((t, filas) -> {
            if (desde != null && filas.keySet().stream().anyMatch(m -> m.compareTo(desde) >= 0)) tablas.add(t);
        });

        List<Tabla> resultado = new ArrayList<>();
        for (String t : tablas) {
            Map<String, Double> b = ladoBackend.getOrDefault(t, Map.of());
            Map<String, Double> a = new TreeMap<>(ladoApp.getOrDefault(t, Map.of()));
            if (desde != null) a.keySet().removeIf(m -> m.compareTo(desde) < 0);
            int comunes = 0;
            Double difMax = null;
            for (Map.Entry<String, Double> e : b.entrySet()) {
                if (!a.containsKey(e.getKey())) continue;
                comunes++;
                Double va = a.get(e.getKey()), vb = e.getValue();
                if (va != null && vb != null) {
                    double d = Math.abs(va - vb);
                    difMax = difMax == null ? d : Math.max(difMax, d);
                }
            }
            resultado.add(new Tabla(t, a.size(), b.size(), comunes, columna, difMax));
        }
        return new Archivo(relativo, titulo, Files.exists(app), Files.exists(backend), resultado);
    }

    /** Tabla -> minuto (dd-MM-yyyy HH:mm) -> valor de la columna comparada (null si no hay columna o no la tiene). */
    private static Map<String, Map<String, Double>> leer(Path archivo, String prefijo, String columna) throws SQLException {
        Map<String, Map<String, Double>> salida = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String url = "jdbc:sqlite:file:" + archivo.toString().replace('\\', '/') + "?mode=ro";
        try (Connection c = DriverManager.getConnection(url)) {
            List<String> tablas = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) tablas.add(rs.getString("TABLE_NAME"));
            }
            for (String t : tablas) {
                boolean conColumna = columna != null && tieneColumna(c, t, columna);
                String sql = "SELECT fecha" + (conColumna ? ", \"" + columna + "\"" : "") + " FROM \"" + t
                        + "\" WHERE fecha >= ? AND fecha < ?"; // rango sobre la clave: usa el índice, LIKE no
                Map<String, Double> filas = new TreeMap<>();
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, prefijo);
                    ps.setString(2, prefijo + "\u007f");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String minuto = rs.getString(1).substring(0, 16);
                            Double v = null;
                            if (conColumna) {
                                double d = rs.getDouble(2);
                                v = rs.wasNull() ? null : d;
                            }
                            filas.put(minuto, v);
                        }
                    }
                }
                if (!filas.isEmpty()) salida.put(t, filas);
            }
        }
        return salida;
    }

    private static boolean tieneColumna(Connection c, String tabla, String columna) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(null, null, tabla, null)) {
            while (rs.next()) {
                if (rs.getString("COLUMN_NAME").equalsIgnoreCase(columna)) return true;
            }
        }
        return false;
    }
}
