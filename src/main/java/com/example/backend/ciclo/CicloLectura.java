package com.example.backend.ciclo;

import com.example.backend.almacen.AlmacenLecturas;
import com.example.backend.almacen.EstadoEquiposArchivo;
import com.example.backend.modelo.Conectividad;
import com.example.backend.lectura.LectorGeneradores;
import com.example.backend.lectura.LectorMezcladores;
import com.example.backend.lectura.LectorPLC;
import com.example.backend.lectura.LectorPasarelas;
import com.example.backend.modelo.EstadoDispositivo;
import com.example.backend.modelo.Fila;
import com.example.backend.modelo.ResultadoLectura;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Ciclo de lectura de un minuto, como DataAcquisitionTask de la app: PLC, pasarelas, mezcladores y generadores, uno
 * tras otro; después entrega todas las filas al almacén. Corre en el segundo {@code colector.ciclo.segundo} de cada
 * minuto (30 por defecto) y se apaga con {@code colector.lectura.habilitada=false}.
 *
 * No incluye lo que escribe en equipos (sirena, IDs del PLC) ni la captura al segundo: este backend solo lee.
 */
@Component
@ConditionalOnProperty(name = "colector.lectura.habilitada", havingValue = "true", matchIfMissing = true)
public class CicloLectura {

    private static final Logger logger = LoggerFactory.getLogger(CicloLectura.class);

    private final LectorPLC plc;
    private final LectorPasarelas pasarelas;
    private final LectorMezcladores mezcladores;
    private final LectorGeneradores generadores;
    private final AlmacenLecturas almacen;
    private final UltimoCiclo ultimo;
    private final EstadoEquiposArchivo estadoEquipos;
    private final AtomicBoolean enCurso = new AtomicBoolean(false);
    private final int segundoCiclo;

    public CicloLectura(LectorPLC plc, LectorPasarelas pasarelas, LectorMezcladores mezcladores,
                        LectorGeneradores generadores, AlmacenLecturas almacen, UltimoCiclo ultimo,
                        EstadoEquiposArchivo estadoEquipos,
                        @Value("${colector.ciclo.segundo:30}") int segundoCiclo) {
        this.segundoCiclo = segundoCiclo;
        this.estadoEquipos = estadoEquipos;
        this.plc = plc;
        this.pasarelas = pasarelas;
        this.mezcladores = mezcladores;
        this.generadores = generadores;
        this.almacen = almacen;
        this.ultimo = ultimo;
        logger.info("Ciclo de lectura habilitado; almacen: {}", almacen.nombre());
    }

    @Scheduled(cron = "${colector.ciclo.segundo:30} * * * * *")
    public void ciclo() {
        ejecutar(true);
    }

    /**
     * Al arrancar, para ver enseguida que los equipos contestan: si para la lectura del reloj (segundo
     * {@code colector.ciclo.segundo}) faltan 30 s o más, una lectura de PRUEBA que no guarda nada (ni datos ni el
     * estado de los equipos para la app), solo se ve en la página y en /api/estado. Si faltan menos de 30 s, se espera
     * la del reloj: así no quedan dos filas guardadas a pocos segundos una de otra.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void lecturaAlArrancar() {
        int falta = Math.floorMod(segundoCiclo - LocalDateTime.now().getSecond(), 60);
        if (falta < 30) {
            logger.info("Faltan {} s para la lectura del reloj: sin lectura de prueba al arrancar", falta);
            return;
        }
        logger.info("Faltan {} s para la lectura del reloj: lectura de prueba al arrancar (no se guarda)", falta);
        Thread.ofVirtual().name("lectura-prueba").start(() -> ejecutar(false));
    }

    /** @param guardar false = lectura de prueba: se lee y se muestra, no se guarda nada */
    private void ejecutar(boolean guardar) {
        if (!enCurso.compareAndSet(false, true)) {
            logger.warn("El ciclo anterior todavia no termino; se saltea este");
            return;
        }
        try {
            LocalDateTime inicio = LocalDateTime.now().withNano(0);
            long t0 = System.currentTimeMillis();
            List<Fila> filas = new ArrayList<>();
            List<EstadoDispositivo> estados = new ArrayList<>();
            List<Conectividad> conectividad = new ArrayList<>();
            leer("PLC", plc::leer, filas, estados, conectividad);
            leer("Pasarelas", pasarelas::leer, filas, estados, conectividad);
            leer("Mezcladores", mezcladores::leer, filas, estados, conectividad);
            leer("Generadores", generadores::leer, filas, estados, conectividad);

            AlmacenLecturas.ResultadoGuardado g = new AlmacenLecturas.ResultadoGuardado(0, null);
            if (guardar) {
                try {
                    g = almacen.guardar(filas);
                } catch (Exception e) {
                    logger.error("Error guardando el ciclo: {}", e.getMessage(), e);
                    g = new AlmacenLecturas.ResultadoGuardado(0, e.getMessage());
                }
                // Después de los datos: cuando la app ve el estado nuevo, las filas del ciclo ya están guardadas.
                estadoEquipos.guardar(inicio, conectividad);
            }
            long ms = System.currentTimeMillis() - t0;
            ultimo.registrar(new UltimoCiclo.Resumen(inicio, ms, filas.size(), g.guardadas(), g.error(), estados, !guardar), filas);
            if (guardar) {
                logger.info("Ciclo {}: {} filas, {} guardadas en {} ms", inicio, filas.size(), g.guardadas(), ms);
            } else {
                logger.info("Lectura de prueba {}: {} filas leidas en {} ms, sin guardar", inicio, filas.size(), ms);
            }
        } finally {
            enCurso.set(false);
        }
    }

    /** Un error en un grupo no corta el ciclo: queda como estado del grupo. */
    private static void leer(String grupo, Supplier<ResultadoLectura> lector, List<Fila> filas, List<EstadoDispositivo> estados,
                             List<Conectividad> conectividad) {
        try {
            ResultadoLectura r = lector.get();
            filas.addAll(r.filas());
            estados.addAll(r.estados());
            conectividad.addAll(r.conectividad());
        } catch (Exception e) {
            logger.error("Error leyendo {}: {}", grupo, e.getMessage(), e);
            estados.add(new EstadoDispositivo(grupo, "(todos)", "", 0, 0, "error: " + e.getMessage(), 0));
        }
    }
}
