package com.example.alarmas.repository;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Tabla alarma_evento de la base H2 de la app (copia reducida de AlarmaEventoRepository de applineabase). */
public interface AlarmaEventoRepository extends JpaRepository<AlarmaEvento, Long> {
    /** Todas las activas de una máquina y tipo (un generador puede tener varias alarmas a la vez). */
    List<AlarmaEvento> findByLineaMaquinaAndTipoAlarmaAndActivaTrue(String lineaMaquina, TipoAlarma tipoAlarma);
}
