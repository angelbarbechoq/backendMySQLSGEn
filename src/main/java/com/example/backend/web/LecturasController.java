package com.example.backend.web;

import com.example.backend.almacen.AlmacenLecturas;
import com.example.backend.ciclo.CicloLectura;
import com.example.backend.ciclo.UltimoCiclo;
import org.springframework.beans.factory.ObjectProvider;
import com.example.backend.comparar.ComparadorArchivos;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private static final Logger logger = LoggerFactory.getLogger(LecturasController.class);
    private final ApplicationContext contexto;
    /** No existe con colector.lectura.habilitada=false. */
    private final ObjectProvider<CicloLectura> cicloLectura;

    public LecturasController(UltimoCiclo ultimo, AlmacenLecturas almacen, ComparadorArchivos comparador,
                              ApplicationContext contexto, ObjectProvider<CicloLectura> cicloLectura,
                              @Value("${colector.lectura.habilitada:true}") boolean lecturaHabilitada,
                              @Value("${colector.ciclo.segundo:30}") int segundoCiclo) {
        this.contexto = contexto;
        this.cicloLectura = cicloLectura;
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

    /**
     * Estado para la app (applineabase): si la lectura está prendida y cuándo terminó el último ciclo. La app lo pregunta
     * cada 15 s; si no hay respuesta, el backend está fuera de línea. Liviano: sin filas.
     */
    @GetMapping("/api/estado")
    public Map<String, Object> estado() {
        Map<String, Object> salida = new LinkedHashMap<>();
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        salida.put("ahora", ahora.toString());
        salida.put("iniciado", INICIADO.toString());
        salida.put("lecturaHabilitada", lecturaHabilitada);
        salida.put("almacen", almacen.nombre());
        // Para la cuenta regresiva de la app: cuánto falta para la próxima lectura y si está leyendo ahora.
        CicloLectura ciclo = cicloLectura.getIfAvailable();
        if (ciclo != null) {
            salida.put("segundosParaProximaLectura", ciclo.segundosParaProximaLectura());
            salida.put("leyendo", ciclo.leyendo());
        }
        UltimoCiclo.Resumen r = ultimo.resumen();
        if (r != null) {
            salida.put("ultimoCiclo", r.inicio().toString());
            salida.put("segundosDesdeUltimoCiclo", Duration.between(r.inicio(), ahora).toSeconds());
            salida.put("filas", r.filas());
            salida.put("guardadas", r.guardadas());
            salida.put("errorGuardado", r.errorGuardado());
            salida.put("prueba", r.prueba());
        }
        return salida;
    }

    private static final LocalDateTime INICIADO = LocalDateTime.now().withNano(0);

    /**
     * La app (applineabase) lo pide cuando ella misma lee los equipos y ve que este backend escribe en su misma carpeta:
     * dos programas escribiendo los mismos archivos se pisan. Solo se acepta desde esta PC. Contesta y se cierra.
     */
    @PostMapping("/api/cerrar")
    public ResponseEntity<String> cerrar(@RequestParam(defaultValue = "sin motivo") String motivo, HttpServletRequest pedido)
            throws java.net.UnknownHostException {
        String desde = pedido.getRemoteAddr();
        // getRemoteAddr da una IP literal: getByName no consulta DNS.
        if (!InetAddress.getByName(desde).isLoopbackAddress()) {
            logger.warn("Pedido de cierre rechazado: llegó desde {}, solo se acepta desde esta PC", desde);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Solo se acepta desde esta PC");
        }
        logger.warn("Cierre pedido por la app: {}", motivo);
        Thread.ofPlatform().name("cierre").start(() -> {
            try {
                Thread.sleep(1000); // que la respuesta llegue antes de cerrar
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(contexto, () -> 0));
        });
        return ResponseEntity.ok("Cerrando");
    }

    /** Compara los archivos SQLite del backend con los de la app para un día (por defecto hoy). Solo lee. */
    @GetMapping("/api/comparar")
    public Map<String, Object> comparar(@RequestParam(required = false)
                                        @org.springframework.format.annotation.DateTimeFormat(
                                                iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dia) {
        return comparador.comparar(dia == null ? LocalDate.now() : dia);
    }
}
