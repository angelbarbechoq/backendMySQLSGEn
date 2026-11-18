package com.example.backend.lectura;

import com.example.backend.generador.ArranquesArchivo;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.backend.generador.GeneradorAlarmasArchivo;
import com.example.backend.generador.HistorialEventosArchivo;
import com.example.backend.generador.ModoSelectorArchivo;
import com.example.backend.generador.RedControladorArchivo;
import com.example.backend.modelo.EstadoDispositivo;
import com.example.backend.modelo.Fila;
import com.example.backend.modelo.ResultadoLectura;
import com.example.backend.modelo.TipoTabla;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.service.GeneradoresConfigService;
import com.example.generador.service.LectorControlador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lectura de los controladores ComAp de los generadores. SOLO LECTURA (función 03, {@link LectorControlador} tal cual
 * de applineabase). Arma la fila de {@code {mes}Generador} con el mismo criterio que GeneradorReaderService y
 * GeneradorAlmacen.guardarLectura: en marcha cada ciclo, parado cada {@link #MINUTOS_PARADO} min; sin RPM o con el
 * mapa sin confirmar no hay fila.
 *
 * Además anota los hechos que solo se ven al leer, en los mismos archivos y con el mismo formato que la app
 * (GeneradorReaderService y HistorialEventosService): arranques y paradas en {@code generador\arranques} y el historial
 * de eventos en {@code generador\eventos} (arranque, parada, carga, red, alarmas del controlador, modo, comunicación).
 * Con las señales de red del modelo, también los eventos de red e interruptores y del selector de tres posiciones
 * (RedControladorService y ModoFuncionamientoService), y abre y cierra las alarmas de los generadores en la tabla de
 * alarmas de la app (GeneradorAlarmasService): así la app tiene todo al abrirse aunque haya estado apagada.
 * Sin {@code colector.sqlite.carpeta} (almacén en memoria) no anota nada.
 */
@Service
public class LectorGeneradores {

    private static final Logger logger = LoggerFactory.getLogger(LectorGeneradores.class);
    static final int MINUTOS_PARADO = 15;

    private final GeneradoresConfigService config;
    private final Map<String, LocalDateTime> ultimaFilaParado = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enMarchaAnterior = new ConcurrentHashMap<>();
    /** null = sin carpeta SQLite: no se anotan arranques ni eventos. */
    private final ArranquesArchivo arranques;
    private final HistorialEventosArchivo historial;
    private final GeneradorAlarmasArchivo alarmas;
    private final RedControladorArchivo red;
    private final ModoSelectorArchivo selector;
    /** Arranques: estado anterior (true = en marcha), contador de arranques parado / antes del arranque y si tomó carga. */
    private final Map<String, Boolean> marchaArranque = new ConcurrentHashMap<>();
    private final Map<String, Integer> contadorParado = new ConcurrentHashMap<>();
    private final Map<String, Integer> contadorAntes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> tomoCarga = new ConcurrentHashMap<>();

    public LectorGeneradores(GeneradoresConfigService config, AlarmaEventoRepository eventoRepository,
                             @Value("${colector.sqlite.carpeta:}") String carpeta) {
        this.config = config;
        boolean anotar = carpeta != null && !carpeta.isBlank();
        this.arranques = anotar ? new ArranquesArchivo(carpeta) : null;
        this.historial = anotar ? new HistorialEventosArchivo(carpeta) : null;
        this.alarmas = anotar ? new GeneradorAlarmasArchivo(eventoRepository, historial) : null;
        this.red = anotar ? new RedControladorArchivo(historial, eventoRepository) : null;
        this.selector = anotar ? new ModoSelectorArchivo(historial, eventoRepository) : null;
    }

    /** @param guardar false = lectura de prueba al arrancar: se lee y se muestra, no se anota nada ni cambia el estado */
    public ResultadoLectura leer(boolean guardar) {
        ResultadoLectura r = ResultadoLectura.nuevo();
        for (Generador g : config.generadores()) {
            long inicio = System.currentTimeMillis();
            Optional<ModeloControlador> modelo = config.modelo(g.modelo());
            if (modelo.isEmpty()) {
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 0, 1,
                        "el modelo '" + g.modelo() + "' no está en generador-modelos.json", 0));
                continue;
            }
            LecturaGenerador l;
            try {
                l = LectorControlador.leer(g.ip(), g.unitId(), modelo.get());
            } catch (Exception e) {
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 0, 1,
                        "sin comunicación: " + e.getMessage(), System.currentTimeMillis() - inicio));
                if (guardar && historial != null && modelo.get().confirmado()) {
                    historial.registrarSinComunicacion(g.nombre(), e.getMessage());
                }
                continue;
            }
            long ms = System.currentTimeMillis() - inicio;
            if (!modelo.get().confirmado()) {
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 0, 1,
                        "mapa " + g.modelo() + " sin confirmar: no se guarda", ms));
                continue;
            }
            if (l.rpm() == null) {
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 0, 1,
                        "lectura sin RPM: no se guarda (" + l.errores().get(ParametroGenerador.RPM) + ")", ms));
                continue;
            }
            String nota = l.errores().isEmpty() ? null : "sin respuesta en " + l.errores().keySet();
            if (guardar && arranques != null) {
                try {
                    anotarArranque(g.nombre(), l);
                } catch (Exception e) {
                    logger.error("Generador {}: error anotando el arranque: {}", g.nombre(), e.getMessage(), e);
                }
                try {
                    historial.registrarLectura(g.nombre(), l, modelo.get().modo(l), modelo.get().listoAmf(l));
                } catch (Exception e) {
                    logger.error("Generador {}: error en el historial de eventos: {}", g.nombre(), e.getMessage(), e);
                }
                try {
                    alarmas.sincronizar(g.nombre(), l.alarmas(), l.fecha());
                } catch (Exception e) {
                    logger.error("Generador {}: error pasando sus alarmas a Alarmas Activas: {}", g.nombre(), e.getMessage(), e);
                }
                try {
                    alarmas.sincronizarPotencia(g.nombre(), l, config.limitePotenciaKw(g.nombre()));
                } catch (Exception e) {
                    logger.error("Generador {}: error con la alarma de potencia: {}", g.nombre(), e.getMessage(), e);
                }
                if (modelo.get().red() != null) {
                    try {
                        red.procesar(g.nombre(), modelo.get().red(), l);
                    } catch (Exception e) {
                        logger.error("Generador {}: error con las senales de red del controlador: {}", g.nombre(), e.getMessage(), e);
                    }
                    try {
                        selector.procesar(g.nombre(), modelo.get().red(), l, modelo.get().listoAmf(l));
                    } catch (Exception e) {
                        logger.error("Generador {}: error verificando el modo del selector: {}", g.nombre(), e.getMessage(), e);
                    }
                }
            }
            if (!guardar || debeGuardar(g.nombre(), l)) {
                r.filas().add(fila(g.nombre(), l));
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 1, 1, nota, ms));
            } else {
                // Parado y ya guardado hace menos de 15 min: leído bien, sin fila este ciclo (igual que la app).
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 1, 1, nota, ms));
            }
        }
        return r;
    }

    /**
     * Arranque y parada, como GeneradorReaderService.procesar de la app: abre el arranque al ver el motor en marcha
     * (inicio estimado si el backend empezó con el generador ya en marcha), lo cierra al pararse y lo borra si fue un
     * intento fallido (el controlador no contó el arranque ni tomó carga); en marcha, la carga máxima.
     */
    private void anotarArranque(String nombre, LecturaGenerador l) {
        boolean enMarcha = l.enMarcha();
        Boolean antes = marchaArranque.put(nombre, enMarcha);
        if (enMarcha && !Boolean.TRUE.equals(antes)) {
            if (antes == null && arranques.hayAbierto(nombre)) {
                logger.info("Generador {}: sigue en marcha, se continua el arranque abierto", nombre);
            } else {
                arranques.abrir(nombre, l, antes == null);
            }
            Integer previo = antes == null ? null : contadorParado.get(nombre);
            if (previo == null) contadorAntes.remove(nombre);
            else contadorAntes.put(nombre, previo);
            tomoCarga.put(nombre, false);
        } else if (!enMarcha && Boolean.TRUE.equals(antes)) {
            Long id = arranques.cerrar(nombre, l);
            if (id != null && LecturaGenerador.intentoDeArranque(contadorAntes.get(nombre), l.arranques(),
                    Boolean.TRUE.equals(tomoCarga.get(nombre)))) {
                arranques.borrar(nombre, id);
            }
        } else if (!enMarcha && antes == null) {
            // Backend recién iniciado con el generador parado: cerrar un arranque que quedó abierto.
            arranques.cerrar(nombre, l);
        }
        if (enMarcha && l.kw() != null && l.kw() >= HistorialEventosArchivo.KW_VACIO) tomoCarga.put(nombre, true);
        if (!enMarcha && l.arranques() != null) contadorParado.put(nombre, l.arranques());
        if (enMarcha) arranques.actualizarKwMax(nombre, l.kw());
    }

    private boolean debeGuardar(String nombre, LecturaGenerador l) {
        boolean enMarcha = l.enMarcha();
        Boolean antes = enMarchaAnterior.put(nombre, enMarcha);
        if (enMarcha) return true;
        if (Boolean.TRUE.equals(antes)) ultimaFilaParado.remove(nombre); // recién paró: guardar la primera parado
        LocalDateTime ultima = ultimaFilaParado.get(nombre);
        if (ultima == null || Duration.between(ultima, l.fecha()).toMinutes() >= MINUTOS_PARADO) {
            ultimaFilaParado.put(nombre, l.fecha());
            return true;
        }
        return false;
    }

    /** Mismas columnas que GeneradorAlmacen.guardarLectura: estado y una por parámetro (enteros redondeados). */
    private static Fila fila(String nombre, LecturaGenerador l) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("estado", l.enMarcha() ? "MARCHA" : "PARADO");
        for (ParametroGenerador p : ParametroGenerador.values()) {
            Double v = l.valor(p);
            cols.put(p.columna(), v == null ? null : p.entero() ? (Object) Math.round(v) : v);
        }
        return new Fila(TipoTabla.GENERADOR, nombre, l.fecha().withNano(0), cols);
    }
}
