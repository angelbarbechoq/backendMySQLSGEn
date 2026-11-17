package com.example.backend.modelo;

/**
 * Si una línea/máquina se pudo leer en el ciclo, como el DispositivoConectividadEvent de la app (alarma "dispositivo no
 * disponible"). La app la toma del archivo de estado cuando su propia lectura está apagada.
 *
 * @param linea     nombre de la línea/máquina (lineaMaquina)
 * @param conectado true si se leyó
 * @param motivo    por qué no se leyó (null si se leyó)
 */
public record Conectividad(String linea, boolean conectado, String motivo) {
}
