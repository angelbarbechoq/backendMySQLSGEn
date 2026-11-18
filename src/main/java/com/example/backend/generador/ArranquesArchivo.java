package com.example.backend.generador;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.generador.model.LecturaGenerador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * Arranques de los generadores en {@code {carpeta}\generador\arranques}: una tabla por generador, una fila por arranque.
 * Mismo archivo, tablas, columnas y cálculos que GeneradorAlmacen de applineabase (abrir, cerrar, borrar un intento
 * fallido, carga máxima y corrección del inicio con el horómetro), para que las pantallas de la app lo lean igual.
 */
public class ArranquesArchivo {

    private static final Logger logger = LoggerFactory.getLogger(ArranquesArchivo.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    /** Horas máximas entre la parada anterior y el arranque encontrado en marcha para corregir su inicio. */
    private static final double MAX_HORAS_CORRECCION = 24;

    private final Path ruta;

    public ArranquesArchivo(String carpeta) {
        this.ruta = Path.of(carpeta, "generador", "arranques");
    }

    /** Carga máxima del arranque en curso (se actualiza en cada lectura en marcha). */
    public void actualizarKwMax(String generador, Double kw) {
        if (kw == null) return;
        try (Connection c = abrir(generador);
             PreparedStatement ps = c.prepareStatement("UPDATE \"" + generador + "\" SET kw_max = max(coalesce(kw_max, 0), ?) "
                     + "WHERE fin IS NULL")) {
            ps.setDouble(1, kw);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error actualizando carga maxima de {}: {}", generador, e.getMessage());
        }
    }

    /** Abre un arranque (fin vacío). inicioEstimado = se empezó a leer con el generador ya en marcha. */
    public void abrir(String generador, LecturaGenerador l, boolean inicioEstimado) {
        Long id = null;
        try (Connection c = abrir(generador);
             PreparedStatement ps = c.prepareStatement("INSERT INTO \"" + generador + "\" (inicio, kwh_inicio, "
                     + "horas_inicio, arranques_contador, inicio_estimado) VALUES (?,?,?,?,?)")) {
            ps.setString(1, l.fecha().format(FECHA));
            ps.setObject(2, l.kwh());
            ps.setObject(3, l.horasMarcha());
            ps.setObject(4, l.arranques());
            ps.setInt(5, inicioEstimado ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet r = c.createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (r.next()) id = r.getLong(1);
            }
            logger.info("Generador {}: arranque registrado ({}{})", generador, l.fecha().format(FECHA),
                    inicioEstimado ? ", inicio estimado" : "");
        } catch (SQLException e) {
            logger.error("Error registrando arranque de {}: {}", generador, e.getMessage());
        }
        if (inicioEstimado && id != null) corregirInicioEstimado(generador, id);
    }

    /**
     * Arranque encontrado ya en marcha: se recupera su comienzo con el horómetro y el contador de energía guardados al
     * cerrar el arranque anterior (igual que GeneradorAlmacen.corregirInicioEstimado de la app).
     */
    private void corregirInicioEstimado(String generador, long id) {
        try (Connection c = abrir(generador)) {
            String inicioTxt, finTxt;
            Long kwhFin, contador;
            Double horasInicio;
            try (PreparedStatement ps = c.prepareStatement("SELECT inicio, fin, kwh_fin, horas_inicio, arranques_contador, "
                    + "inicio_estimado FROM \"" + generador + "\" WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet r = ps.executeQuery()) {
                    if (!r.next() || r.getInt(6) != 1) return;
                    inicioTxt = r.getString(1);
                    finTxt = r.getString(2);
                    kwhFin = r.getObject(3) == null ? null : r.getLong(3);
                    horasInicio = r.getObject(4) == null ? null : r.getDouble(4);
                    contador = r.getObject(5) == null ? null : r.getLong(5);
                }
            }
            String finPrevio;
            Long kwhPrevio, contadorPrevio;
            Double horasPrevio;
            try (PreparedStatement ps = c.prepareStatement("SELECT fin, kwh_fin, horas_fin, arranques_contador FROM \"" + generador
                    + "\" WHERE id < ? AND fin IS NOT NULL ORDER BY id DESC LIMIT 1")) {
                ps.setLong(1, id);
                try (ResultSet r = ps.executeQuery()) {
                    if (!r.next()) return; // el primer arranque registrado: no hay parada anterior con qué comparar
                    finPrevio = r.getString(1);
                    kwhPrevio = r.getObject(2) == null ? null : r.getLong(2);
                    horasPrevio = r.getObject(3) == null ? null : r.getDouble(3);
                    contadorPrevio = r.getObject(4) == null ? null : r.getLong(4);
                }
            }
            if (horasInicio == null || horasPrevio == null || kwhPrevio == null) return;
            if (contador != null && contadorPrevio != null && contador != contadorPrevio + 1) {
                logger.info("Generador {}: no se corrige el inicio del arranque {}: hubo {} arranques desde la ultima parada registrada",
                        generador, id, contador - contadorPrevio);
                return;
            }
            double horas = horasInicio - horasPrevio;
            if (horas < 0 || horas > MAX_HORAS_CORRECCION) return;
            LocalDateTime primeraLectura = LocalDateTime.parse(inicioTxt, FECHA);
            LocalDateTime inicio = primeraLectura.minusSeconds(Math.round(horas * 3600));
            LocalDateTime paradaAnterior = LocalDateTime.parse(finPrevio, FECHA);
            if (inicio.isBefore(paradaAnterior)) inicio = paradaAnterior;
            try (PreparedStatement ps = c.prepareStatement("UPDATE \"" + generador + "\" SET inicio = ?, kwh_inicio = ?, "
                    + "horas_inicio = ?, inicio_estimado = 0, inicio_corregido = 1, duracion_min = ?, kwh_generados = ? WHERE id = ?")) {
                ps.setString(1, inicio.format(FECHA));
                ps.setLong(2, kwhPrevio);
                ps.setDouble(3, horasPrevio);
                if (finTxt != null) ps.setDouble(4, Duration.between(inicio, LocalDateTime.parse(finTxt, FECHA)).toSeconds() / 60.0);
                else ps.setNull(4, Types.REAL);
                if (finTxt != null && kwhFin != null) ps.setLong(5, kwhFin - kwhPrevio);
                else ps.setNull(5, Types.INTEGER);
                ps.setLong(6, id);
                ps.executeUpdate();
            }
            logger.info("Generador {}: inicio del arranque {} corregido con el horometro: {} (primera lectura {}), energia desde {} kWh",
                    generador, id, inicio.format(FECHA), primeraLectura.format(FECHA), kwhPrevio);
        } catch (Exception e) {
            logger.error("Error corrigiendo el inicio del arranque {} de {}: {}", id, generador, e.getMessage());
        }
    }

    /** Cierra el arranque abierto (si hay) con la lectura de parada; devuelve su id (null si no había o falló). */
    public Long cerrar(String generador, LecturaGenerador l) {
        try (Connection c = abrir(generador)) {
            Long id = null, kwhInicio = null;
            LocalDateTime inicio = null;
            try (ResultSet r = c.createStatement().executeQuery("SELECT id, inicio, kwh_inicio FROM \"" + generador
                    + "\" WHERE fin IS NULL ORDER BY id DESC LIMIT 1")) {
                if (r.next()) {
                    id = r.getLong(1);
                    inicio = LocalDateTime.parse(r.getString(2), FECHA);
                    kwhInicio = r.getObject(3) == null ? null : r.getLong(3);
                }
            }
            if (id == null) return null;
            try (PreparedStatement ps = c.prepareStatement("UPDATE \"" + generador + "\" SET fin = ?, duracion_min = ?, "
                    + "kwh_fin = ?, kwh_generados = ?, horas_fin = ? WHERE id = ?")) {
                ps.setString(1, l.fecha().format(FECHA));
                ps.setDouble(2, Duration.between(inicio, l.fecha()).toSeconds() / 60.0);
                ps.setObject(3, l.kwh());
                if (l.kwh() != null && kwhInicio != null) ps.setLong(4, l.kwh() - kwhInicio);
                else ps.setNull(4, Types.INTEGER);
                ps.setObject(5, l.horasMarcha());
                ps.setLong(6, id);
                ps.executeUpdate();
            }
            logger.info("Generador {}: parada registrada ({})", generador, l.fecha().format(FECHA));
            return id;
        } catch (SQLException e) {
            logger.error("Error cerrando arranque de {}: {}", generador, e.getMessage());
            return null;
        }
    }

    /** Borra un arranque que resultó ser un intento de arranque fallido. */
    public void borrar(String generador, long id) {
        try (Connection c = abrir(generador);
             PreparedStatement ps = c.prepareStatement("DELETE FROM \"" + generador + "\" WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
            logger.info("Generador {}: el arranque {} fue un intento fallido (el controlador no conto el arranque), se borra", generador, id);
        } catch (SQLException e) {
            logger.error("Error borrando el arranque {} de {}: {}", id, generador, e.getMessage());
        }
    }

    /** true si hay un arranque abierto (para no duplicarlo si el backend se reinicia en marcha). */
    public boolean hayAbierto(String generador) {
        try (Connection c = abrir(generador);
             ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM \"" + generador + "\" WHERE fin IS NULL")) {
            return r.next() && r.getInt(1) > 0;
        } catch (SQLException e) {
            logger.error("Error consultando arranques de {}: {}", generador, e.getMessage());
            return false;
        }
    }

    private Connection abrir(String generador) throws SQLException {
        try {
            Files.createDirectories(ruta.getParent());
        } catch (Exception e) {
            throw new SQLException("No se pudo crear la carpeta de " + ruta, e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 10000;");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS \"" + generador + "\" (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "inicio TEXT NOT NULL, fin TEXT, duracion_min REAL, kwh_inicio INTEGER, kwh_fin INTEGER, "
                    + "kwh_generados INTEGER, horas_inicio REAL, horas_fin REAL, arranques_contador INTEGER, "
                    + "inicio_estimado INTEGER, kw_max REAL, inicio_corregido INTEGER)");
        }
        Set<String> existentes = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, generador, null)) {
            while (rs.next()) existentes.add(rs.getString("COLUMN_NAME").toLowerCase());
        }
        try (Statement st = c.createStatement()) {
            if (!existentes.contains("kw_max")) st.executeUpdate("ALTER TABLE \"" + generador + "\" ADD COLUMN kw_max REAL");
            if (!existentes.contains("inicio_corregido")) st.executeUpdate("ALTER TABLE \"" + generador + "\" ADD COLUMN inicio_corregido INTEGER");
        }
        return c;
    }
}
