package com.example.backend.almacen;

import com.example.backend.modelo.Conectividad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Archivo SQLite {@code {carpeta}\estado\equipos}, tabla {@code conectividad}: una fila por línea/máquina con si se leyó
 * en el último ciclo, el motivo y la fecha del ciclo. Lo lee la app cuando su lectura está apagada, para seguir con la
 * alarma "dispositivo no disponible". Es estado, no historial: cada ciclo pisa la fila de cada línea.
 *
 * La fecha va en formato ISO (yyyy-MM-dd HH:mm:ss), que se ordena igual que el tiempo; este archivo es nuevo, así que no
 * arrastra el formato dd-MM-yyyy de los archivos de energía. Sin carpeta SQLite configurada no escribe nada.
 */
@Component
public class EstadoEquiposArchivo {

    private static final Logger logger = LoggerFactory.getLogger(EstadoEquiposArchivo.class);
    public static final DateTimeFormatter FECHA_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String carpeta;

    public EstadoEquiposArchivo(@Value("${colector.sqlite.carpeta:}") String carpeta) {
        this.carpeta = carpeta;
    }

    public void guardar(LocalDateTime ciclo, List<Conectividad> lineas) {
        if (carpeta.isBlank() || lineas.isEmpty()) return;
        Path ruta = Path.of(carpeta, "estado", "equipos");
        try {
            Files.createDirectories(ruta.getParent());
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta)) {
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL;");
                    st.execute("PRAGMA busy_timeout = 10000;");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS conectividad (linea TEXT PRIMARY KEY NOT NULL, "
                            + "conectado INTEGER NOT NULL, motivo TEXT, fecha TEXT NOT NULL)");
                }
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR REPLACE INTO conectividad (linea, conectado, motivo, fecha) VALUES (?, ?, ?, ?)")) {
                    String fecha = ciclo.format(FECHA_ISO);
                    for (Conectividad l : lineas) {
                        ps.setString(1, l.linea());
                        ps.setInt(2, l.conectado() ? 1 : 0);
                        ps.setString(3, l.motivo());
                        ps.setString(4, fecha);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            }
        } catch (Exception e) {
            logger.error("Error guardando el estado de los equipos en {}: {}", ruta, e.getMessage());
        }
    }
}
