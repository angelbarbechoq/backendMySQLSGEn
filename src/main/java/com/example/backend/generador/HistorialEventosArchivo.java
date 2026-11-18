package com.example.backend.generador;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.generador.model.EventoGenerador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.TipoEventoGenerador;
import com.example.generador.service.AlarmaComap;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.generador.model.TipoEventoGenerador.*;

/**
 * Historial de eventos de los generadores en {@code {carpeta}\generador\eventos}, una tabla por generador. Misma
 * detección, textos, tabla y columnas que HistorialEventosService de applineabase (registrarLectura y
 * registrarSinComunicacion): arranque, parada, intento fallido, arranque breve, carga, red, alarmas del controlador,
 * modo, listo para AMF y comunicación. El estado anterior vive en memoria: al iniciar se anota "Inicio de lectura".
 * Los eventos de red e isla (RedControladorService, IslaService) siguen en la app por ahora.
 */
public class HistorialEventosArchivo {

    private static final Logger logger = LoggerFactory.getLogger(HistorialEventosArchivo.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    /** Mismos valores que GeneradorAnalisisService de la app. */
    public static final double KW_VACIO = 10.0;
    static final double V_RED_PRESENTE = 100.0;

    /** Último estado visto de cada generador. */
    private static final class Estado {
        Boolean comunicando;
        boolean marcha;
        Boolean carga, red;
        Integer arranques;
        Set<String> alarmas;
        String modo;
        Boolean listoAmf;
        int noListoSeguidas;
        Integer arranquesAntes;
        LocalDateTime inicioMarcha;
        boolean huboCarga;
        double rpmMax;
    }

    private final Path ruta;
    private final Map<String, Estado> estados = new ConcurrentHashMap<>();

    public HistorialEventosArchivo(String carpeta) {
        this.ruta = Path.of(carpeta, "generador", "eventos");
    }

    /**
     * Compara la lectura con el estado anterior y guarda los eventos que correspondan.
     *
     * @param modo     modo del controlador (OFF, MAN, AUTO, TEST); null si el modelo no lo da
     * @param listoAmf si entraría solo al cortarse la red (Ready To AMF); null si el modelo no lo da
     */
    public void registrarLectura(String generador, LecturaGenerador l, String modo, Boolean listoAmf) {
        List<EventoGenerador> nuevos = new ArrayList<>();
        LocalDateTime t = l.fecha();
        boolean marcha = l.enMarcha();
        Boolean carga = !marcha ? Boolean.FALSE : l.kw() == null ? null : l.kw() >= KW_VACIO;
        Boolean red = redPresente(l);
        Estado e = estados.get(generador);
        if (e == null) {
            e = new Estado();
            estados.put(generador, e);
            nuevos.add(EventoGenerador.de(t, INICIO_LECTURA, "El backend empezó a leer el controlador; el generador estaba "
                    + (marcha ? "en marcha" + (Boolean.TRUE.equals(carga) ? " con carga" : " en vacío") : "parado")
                    + (red == null ? "" : red ? ", con red" : ", sin red")
                    + (modo == null ? "" : ", modo " + modo)
                    + (listoAmf == null ? "" : listoAmf ? ", listo para AMF" : ", no listo para AMF")
                    + (l.desfaseReloj() == null ? "" : "; reloj del controlador " + LecturaGenerador.textoDesfase(l.desfaseReloj())), l));
            if (l.alarmas() != null) {
                for (String a : l.alarmas()) {
                    nuevos.add(EventoGenerador.de(t, ALARMA, AlarmaComap.nombre(a) + ": " + AlarmaComap.estado(a) + " (ya estaba al iniciar la lectura)", l));
                }
            }
        } else {
            if (Boolean.FALSE.equals(e.comunicando)) nuevos.add(EventoGenerador.de(t, COMUNICACION_RECUPERADA, "El controlador volvió a responder", l));
            if (marcha && !e.marcha) {
                nuevos.add(EventoGenerador.de(t, ARRANQUE, "Motor en marcha" + (l.arranques() == null ? "" : " (arranque Nro " + l.arranques() + ")"), l));
                e.arranquesAntes = e.arranques;
                e.inicioMarcha = t;
                e.huboCarga = false;
                e.rpmMax = 0;
            } else if (!marcha && e.marcha) {
                if (e.inicioMarcha != null && LecturaGenerador.intentoDeArranque(e.arranquesAntes, l.arranques(), e.huboCarga)) {
                    borrar(generador, ARRANQUE, e.inicioMarcha);
                    nuevos.add(EventoGenerador.de(t, INTENTO_ARRANQUE, String.format(Locale.ROOT,
                            "El motor giró desde las %s (hasta %.0f rpm) y se detuvo sin arrancar: el controlador no contó el arranque (sigue en %d)",
                            e.inicioMarcha.format(DateTimeFormatter.ofPattern("HH:mm:ss")), e.rpmMax, l.arranques()), l));
                } else {
                    nuevos.add(EventoGenerador.de(t, PARADA, "Motor parado", l));
                }
                e.inicioMarcha = null;
            } else if (!marcha && e.arranques != null && l.arranques() != null && l.arranques() > e.arranques) {
                nuevos.add(EventoGenerador.de(t, ARRANQUE_BREVE, "El contador de arranques del controlador subió de " + e.arranques
                        + " a " + l.arranques() + " sin que se viera el motor en marcha: arranque de menos de 1 minuto", l));
            }
            if (marcha && carga != null) {
                boolean antes = e.marcha && Boolean.TRUE.equals(e.carga);
                if (carga && !antes) {
                    nuevos.add(EventoGenerador.de(t, TOMA_CARGA, String.format(Locale.ROOT, "El generador toma carga (%.0f kW)", l.kw()), l));
                } else if (!carga && antes) {
                    nuevos.add(EventoGenerador.de(t, SIN_CARGA, String.format(Locale.ROOT, "El generador queda en vacío (%.0f kW)", l.kw()), l));
                }
            }
            if (red != null && e.red != null && !red.equals(e.red)) {
                nuevos.add(EventoGenerador.de(t, red ? RETORNO_RED : FALLA_RED, red ? "Volvió la tensión de red" : "Sin tensión de red", l));
            }
            if (l.alarmas() != null && e.alarmas != null) {
                nuevos.addAll(cambiosAlarmas(t, e.alarmas, l.alarmas(), l));
            }
            if (modo != null && e.modo != null && !modo.equals(e.modo)) {
                nuevos.add("AUTO".equals(modo)
                        ? EventoGenerador.de(t, MODO_AUTO, "El controlador vuelve a AUTO (estaba en " + e.modo + ")", l)
                        : EventoGenerador.de(t, CAMBIO_MODO, "El controlador pasa de " + e.modo + " a " + modo, l));
            }
            if (listoAmf != null && e.listoAmf != null) {
                if (listoAmf) {
                    if (!e.listoAmf) nuevos.add(EventoGenerador.de(t, LISTO_AMF, "Si se corta la red, el generador vuelve a entrar solo", l));
                } else if (e.listoAmf && e.noListoSeguidas + 1 >= 2) {
                    nuevos.add(EventoGenerador.de(t, NO_LISTO_AMF, "Si se corta la red, el generador no entraría solo (desde la lectura anterior"
                            + (modo == null ? "" : ", modo " + modo) + ")", l));
                }
            }
        }
        if (modo != null) e.modo = modo;
        if (listoAmf != null) {
            e.noListoSeguidas = listoAmf ? 0 : e.noListoSeguidas + 1;
            if (listoAmf || e.listoAmf == null || e.noListoSeguidas >= 2) e.listoAmf = listoAmf;
        }
        if (marcha) {
            if (Boolean.TRUE.equals(carga)) e.huboCarga = true;
            if (l.rpm() != null) e.rpmMax = Math.max(e.rpmMax, l.rpm());
        }
        e.comunicando = true;
        e.marcha = marcha;
        e.carga = carga;
        if (red != null) e.red = red;
        if (l.arranques() != null) e.arranques = l.arranques();
        if (l.alarmas() != null) e.alarmas = new LinkedHashSet<>(l.alarmas());
        guardar(generador, nuevos);
    }

    /** El controlador no respondió: un evento cuando se pierde la comunicación (no uno por minuto). */
    public void registrarSinComunicacion(String generador, String motivo) {
        Estado e = estados.get(generador);
        if (e == null) {
            e = new Estado();
            estados.put(generador, e);
            guardar(generador, List.of(EventoGenerador.de(LocalDateTime.now().withNano(0), SIN_COMUNICACION,
                    "Sin comunicación al iniciar la lectura: " + motivo, null)));
        } else if (!Boolean.FALSE.equals(e.comunicando)) {
            guardar(generador, List.of(EventoGenerador.de(LocalDateTime.now().withNano(0), SIN_COMUNICACION, motivo, null)));
        }
        e.comunicando = false;
    }

    /** Eventos por los cambios de la lista de alarmas entre dos lecturas, siguiendo cada alarma por su nombre. */
    private static List<EventoGenerador> cambiosAlarmas(LocalDateTime t, Set<String> antes, List<String> ahora, LecturaGenerador l) {
        Map<String, String> previas = new LinkedHashMap<>(), actuales = new LinkedHashMap<>();
        for (String a : antes) previas.put(AlarmaComap.nombre(a), a);
        for (String a : ahora) actuales.put(AlarmaComap.nombre(a), a);
        List<EventoGenerador> eventos = new ArrayList<>();
        for (Map.Entry<String, String> x : actuales.entrySet()) {
            String nombre = x.getKey(), texto = x.getValue(), previo = previas.get(nombre);
            if (previo == null) {
                eventos.add(EventoGenerador.de(t, ALARMA, nombre + ": " + AlarmaComap.estado(texto), l));
            } else if (AlarmaComap.activa(texto) && !AlarmaComap.activa(previo)) {
                eventos.add(EventoGenerador.de(t, ALARMA, nombre + ": activa de nuevo", l));
            } else if (!AlarmaComap.activa(texto) && AlarmaComap.activa(previo)) {
                eventos.add(EventoGenerador.de(t, ALARMA_INACTIVA, nombre + ": " + AlarmaComap.estado(texto), l));
            } else if (!AlarmaComap.sinConfirmar(texto) && AlarmaComap.sinConfirmar(previo)) {
                eventos.add(EventoGenerador.de(t, ALARMA_CONFIRMADA, nombre + ": confirmada en el controlador"
                        + (AlarmaComap.activa(texto) ? ", sigue activa" : ""), l));
            }
        }
        for (String nombre : previas.keySet()) {
            if (!actuales.containsKey(nombre)) {
                eventos.add(EventoGenerador.de(t, ALARMA_RESUELTA, nombre + ": salió de la lista del controlador (reseteada)", l));
            }
        }
        return eventos;
    }

    private static Boolean redPresente(LecturaGenerador l) {
        Double a = l.redVL1L2(), b = l.redVL2L3(), c = l.redVL3L1();
        if (a == null || b == null || c == null) return null;
        return (a + b + c) / 3.0 >= V_RED_PRESENTE;
    }

    /** Borra el evento de ese tipo y fecha (el "arranque" de un intento de arranque fallido). */
    private void borrar(String generador, TipoEventoGenerador tipo, LocalDateTime fecha) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta);
             PreparedStatement ps = c.prepareStatement("DELETE FROM \"" + generador + "\" WHERE tipo = ? AND fecha = ?")) {
            ps.setString(1, tipo.name());
            ps.setString(2, fecha.format(FECHA));
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("No se pudo borrar el evento {} de {} del {}: {}", tipo, generador, fecha, e.getMessage());
        }
    }

    /**
     * Desde cuándo está la alarma en su estado actual (como HistorialEventosService.aparicion de la app): el último
     * evento ALARMA o ALARMA_INACTIVA con ese nombre, sin una ALARMA_RESUELTA posterior. {@code inicioLectura} = true si
     * ya estaba al empezar a leer. Null si no está en el historial. Se recorre por id, del más nuevo al más viejo.
     */
    public Aparicion aparicion(String generador, String alarma) {
        String nombre = AlarmaComap.nombre(alarma);
        Aparicion alIniciar = null;
        LocalDateTime resueltaEn = null;
        if (!Files.exists(ruta)) return null;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta);
             PreparedStatement ps = c.prepareStatement("SELECT fecha, tipo, descripcion FROM \"" + generador + "\" WHERE tipo IN (?,?,?) "
                     + "ORDER BY id DESC LIMIT 5000")) {
            ps.setString(1, ALARMA.name());
            ps.setString(2, ALARMA_INACTIVA.name());
            ps.setString(3, ALARMA_RESUELTA.name());
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    LocalDateTime fecha = LocalDateTime.parse(r.getString(1), FECHA);
                    TipoEventoGenerador tipo = TipoEventoGenerador.valueOf(r.getString(2));
                    String descripcion = r.getString(3);
                    String d = AlarmaComap.nombre(descripcion == null ? "" : descripcion);
                    if (!(d.equals(nombre) || d.startsWith(nombre + ":") || d.startsWith(nombre + " ("))) continue;
                    if (tipo == ALARMA_RESUELTA) {
                        if (resueltaEn == null) resueltaEn = fecha;
                        continue;
                    }
                    // Una "resuelta" en el mismo segundo que la alarma fue solo un cambio de signo; una posterior corta.
                    if (resueltaEn != null && !resueltaEn.equals(fecha)) break;
                    if (!d.contains("al iniciar la lectura")) return new Aparicion(fecha, false);
                    if (alIniciar == null) alIniciar = new Aparicion(fecha, true);
                }
            }
        } catch (Exception e) {
            logger.error("No se pudo buscar en el historial la alarma {} de {}: {}", nombre, generador, e.getMessage());
        }
        return alIniciar;
    }

    public record Aparicion(LocalDateTime fecha, boolean inicioLectura) {
    }

    /** Agrega eventos ya armados (red e interruptores, selector), como HistorialEventosService.registrar de la app. */
    public void registrar(String generador, List<EventoGenerador> eventos) {
        guardar(generador, eventos);
    }

    private void guardar(String generador, List<EventoGenerador> eventos) {
        if (eventos.isEmpty()) return;
        try {
            Files.createDirectories(ruta.getParent());
        } catch (Exception ex) {
            logger.error("No se pudo crear la carpeta del historial de eventos: {}", ex.getMessage());
            return;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta)) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout = 10000;");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS \"" + generador + "\" (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "fecha TEXT NOT NULL, tipo TEXT NOT NULL, descripcion TEXT, rpm REAL, kw REAL, pf REAL, frecuencia REAL, "
                        + "v_gen REAL, i_gen REAL, red_frecuencia REAL, v_red REAL, bateria REAL, temp_refrigerante REAL, "
                        + "horas_marcha REAL, kwh INTEGER, arranques INTEGER)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO \"" + generador + "\" (fecha, tipo, descripcion, rpm, kw, pf, "
                    + "frecuencia, v_gen, i_gen, red_frecuencia, v_red, bateria, temp_refrigerante, horas_marcha, kwh, arranques) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (EventoGenerador ev : eventos) {
                    ps.setString(1, ev.fecha().format(FECHA));
                    ps.setString(2, ev.tipo().name());
                    ps.setString(3, ev.descripcion());
                    Object[] v = {ev.rpm(), ev.kw(), ev.pf(), ev.frecuencia(), ev.vGen(), ev.iGen(), ev.redFrecuencia(), ev.vRed(),
                            ev.bateria(), ev.tempRefrigerante(), ev.horasMarcha(), ev.kwh(), ev.arranques()};
                    for (int i = 0; i < v.length; i++) {
                        if (v[i] == null) ps.setNull(i + 4, Types.REAL);
                        else ps.setObject(i + 4, v[i]);
                    }
                    ps.addBatch();
                    logger.info("Generador {}: evento {} - {}", generador, ev.tipo().etiqueta(), ev.descripcion());
                }
                ps.executeBatch();
            }
        } catch (SQLException ex) {
            logger.error("Error guardando eventos de {}: {}", generador, ex.getMessage());
        }
    }
}
