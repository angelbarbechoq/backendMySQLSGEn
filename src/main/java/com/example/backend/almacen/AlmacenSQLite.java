package com.example.backend.almacen;

import com.example.backend.lectura.LectorPasarelas;
import com.example.backend.modelo.Fila;
import com.example.backend.modelo.TipoTabla;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.generador.model.ParametroGenerador;
import com.example.medidores.model.ParametroMedidor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda las filas en los mismos archivos SQLite que hoy escribe la app principal, con el mismo esquema, para que la
 * app los pueda leer sin cambios:
 * <ul>
 *   <li>Energía y mezcladores: {@code {año}\{mes}\{mes}} y el diario {@code {dd}{mes}}; tablas como
 *       DatabaseInitializationService (fecha TEXT PK, columnas FLOAT NOT NULL), en WAL.</li>
 *   <li>VIP: {@code {mes}VIP} y {@code {dd}{mes}VIP}, igual.</li>
 *   <li>Calidad: {@code {mes}Calidad}, como CalidadEnergiaAlmacen (columnas REAL nulables), en WAL.</li>
 *   <li>Generador: {@code {mes}Generador}, como GeneradorAlmacen.guardarLectura (sin WAL, igual que la app).</li>
 * </ul>
 * Al tocar por primera vez un archivo de energía o VIP se crean las tablas de todas las líneas (y de los mezcladores en
 * los de energía), como hace la app al empezar el día. Diferencias a propósito: el archivo se elige por la fecha de la
 * fila (la app usa el día en que empezó el ciclo) y un error en un archivo no descarta lo guardado en los otros.
 *
 * La carpeta se elige con {@code colector.sqlite.carpeta}. Mientras la app siga leyendo los equipos tiene que ser una
 * carpeta de prueba: dos programas escribiendo los mismos archivos se pisan.
 */
@Component
@ConditionalOnProperty(name = "colector.almacen", havingValue = "sqlite")
public class AlmacenSQLite implements AlmacenLecturas {

    private static final Logger logger = LoggerFactory.getLogger(AlmacenSQLite.class);

    private final ConfigLoaderService config;
    private final String carpeta;
    /** Archivo -> tablas ya verificadas en esta ejecución (evita el CREATE/ALTER cada minuto). */
    private final Map<String, Set<String>> tablasListas = new ConcurrentHashMap<>();

    public AlmacenSQLite(ConfigLoaderService config, @Value("${colector.sqlite.carpeta}") String carpeta) {
        this.config = config;
        this.carpeta = carpeta;
        logger.info("Guardado en SQLite en {}", carpeta);
    }

    public String carpeta() {
        return carpeta;
    }

    @Override
    public String nombre() {
        return "SQLite en " + carpeta;
    }

    @Override
    public ResultadoGuardado guardar(List<Fila> filas) {
        // Archivo -> filas que van ahí (una fila de energía va al mensual y al diario).
        Map<String, List<Fila>> porArchivo = new LinkedHashMap<>();
        Map<String, TipoTabla> tipoArchivo = new LinkedHashMap<>();
        for (Fila f : filas) {
            for (String relativo : f.tipo().archivosSqlite(f.fecha())) {
                String ruta = Path.of(carpeta, relativo).toString();
                porArchivo.computeIfAbsent(ruta, k -> new ArrayList<>()).add(f);
                tipoArchivo.putIfAbsent(ruta, f.tipo());
            }
        }
        int guardadas = 0;
        List<String> errores = new ArrayList<>();
        for (Map.Entry<String, List<Fila>> e : porArchivo.entrySet()) {
            try {
                guardarArchivo(e.getKey(), tipoArchivo.get(e.getKey()), e.getValue());
                // Se cuentan las filas del archivo mensual (el diario repite las mismas).
                if (!esDiario(e.getKey())) guardadas += e.getValue().size();
            } catch (Exception ex) {
                tablasListas.remove(e.getKey());
                logger.error("Error guardando en {}: {}", e.getKey(), ex.getMessage());
                errores.add(Path.of(e.getKey()).getFileName() + ": " + ex.getMessage());
            }
        }
        return new ResultadoGuardado(guardadas, errores.isEmpty() ? null : String.join(" | ", errores));
    }

    /** Los diarios empiezan con el número de día ({dd}{mes}); los mensuales con el nombre del mes. */
    private static boolean esDiario(String ruta) {
        String nombre = Path.of(ruta).getFileName().toString();
        return !nombre.isEmpty() && Character.isDigit(nombre.charAt(0));
    }

    private void guardarArchivo(String ruta, TipoTabla tipo, List<Fila> filas) throws Exception {
        Files.createDirectories(Path.of(ruta).getParent());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta)) {
            try (Statement st = c.createStatement()) {
                if (tipo != TipoTabla.GENERADOR) st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA busy_timeout = 10000;");
            }
            c.setAutoCommit(false);
            try {
                Set<String> listas = tablasListas.computeIfAbsent(ruta, k -> ConcurrentHashMap.newKeySet());
                if (listas.isEmpty() && (tipo == TipoTabla.ENERGIA || tipo == TipoTabla.MEZCLADOR || tipo == TipoTabla.VIP)) {
                    crearTablasDelDia(c, ruta, tipo == TipoTabla.VIP, listas);
                }
                for (Fila f : filas) {
                    if (!listas.contains(f.tabla())) {
                        asegurarTabla(c, f.tipo(), f.tabla());
                        listas.add(f.tabla());
                    }
                    insertar(c, f);
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Como DatabaseInitializationService.createDatabases: todas las líneas, y los mezcladores en el de energía. */
    private void crearTablasDelDia(Connection c, String ruta, boolean vip, Set<String> listas) throws SQLException {
        for (String linea : config.listarLineas()) {
            crearTablaEnergia(c, linea, vip ? RutaArchivosEnergia.CAMPOS_VIP : RutaArchivosEnergia.CAMPOS_NORMAL);
            listas.add(linea);
        }
        if (!vip) {
            for (String tabla : config.listarNombresTablasMezcladores()) {
                crearTablaEnergia(c, tabla, ConfigLoaderService.CAMPOS_MEZCLADOR);
                listas.add(tabla);
            }
        }
        logger.info("Tablas creadas en {}", ruta);
    }

    private void asegurarTabla(Connection c, TipoTabla tipo, String tabla) throws SQLException {
        switch (tipo) {
            case ENERGIA -> crearTablaEnergia(c, tabla, RutaArchivosEnergia.CAMPOS_NORMAL);
            case VIP -> crearTablaEnergia(c, tabla, RutaArchivosEnergia.CAMPOS_VIP);
            case MEZCLADOR -> crearTablaEnergia(c, tabla, ConfigLoaderService.CAMPOS_MEZCLADOR);
            case CALIDAD -> crearTablaCalidad(c, tabla);
            case GENERADOR -> crearTablaGenerador(c, tabla);
        }
    }

    /** DatabaseInitializationService.creaTabla: crea la tabla y agrega las columnas que falten. */
    private static void crearTablaEnergia(Connection c, String tabla, String[] campos) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(RutaArchivosEnergia.construirSqlCrearTabla(tabla, campos));
            Set<String> existentes = columnas(c, tabla, false);
            for (String campo : campos) {
                if (!existentes.contains(campo)) {
                    st.executeUpdate("ALTER TABLE " + tabla + " ADD COLUMN " + campo + " FLOAT NOT NULL DEFAULT 0");
                }
            }
        }
    }

    /** CalidadEnergiaAlmacen.asegurarTabla. */
    private static void crearTablaCalidad(Connection c, String tabla) throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS \"").append(tabla)
                .append("\" (fecha TEXT PRIMARY KEY NOT NULL");
        for (ParametroMedidor p : LectorPasarelas.PARAMETROS_CALIDAD) {
            sql.append(", \"").append(p.name()).append("\" REAL"); // entre comillas: IN es palabra reservada
        }
        sql.append(", ").append(LectorPasarelas.COL_DESB_I_CALC).append(" INTEGER, ")
                .append(LectorPasarelas.COL_DESB_V_CALC).append(" INTEGER)");
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql.toString());
            Set<String> existentes = columnas(c, tabla, true);
            for (ParametroMedidor p : LectorPasarelas.PARAMETROS_CALIDAD) {
                if (!existentes.contains(p.name())) {
                    st.executeUpdate("ALTER TABLE \"" + tabla + "\" ADD COLUMN \"" + p.name() + "\" REAL");
                }
            }
        }
    }

    /** GeneradorAlmacen.guardarLectura: fecha y estado, más una columna por parámetro (INTEGER o REAL). */
    private static void crearTablaGenerador(Connection c, String tabla) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS \"" + tabla + "\" (fecha TEXT PRIMARY KEY NOT NULL, estado TEXT)");
            Set<String> existentes = new HashSet<>();
            for (String col : columnas(c, tabla, false)) existentes.add(col.toLowerCase());
            for (ParametroGenerador p : ParametroGenerador.values()) {
                if (!existentes.contains(p.columna())) {
                    st.executeUpdate("ALTER TABLE \"" + tabla + "\" ADD COLUMN " + p.columna() + (p.entero() ? " INTEGER" : " REAL"));
                }
            }
        }
    }

    private static Set<String> columnas(Connection c, String tabla, boolean mayusculas) throws SQLException {
        Set<String> existentes = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, tabla, null)) {
            while (rs.next()) {
                String n = rs.getString("COLUMN_NAME");
                existentes.add(mayusculas ? n.toUpperCase() : n);
            }
        }
        return existentes;
    }

    /** INSERT OR REPLACE con las columnas de la fila; los valores se pasan como los pasa la app (setObject). */
    private static void insertar(Connection c, Fila f) throws SQLException {
        StringBuilder cols = new StringBuilder("fecha");
        StringBuilder marcas = new StringBuilder("?");
        for (String col : f.columnas().keySet()) {
            cols.append(", \"").append(col).append("\"");
            marcas.append(", ?");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO \"" + f.tabla() + "\" (" + cols
                + ") VALUES (" + marcas + ")")) {
            ps.setString(1, f.fechaTexto());
            int i = 2;
            for (Object v : f.columnas().values()) {
                ps.setObject(i++, v);
            }
            ps.executeUpdate();
        }
    }
}
