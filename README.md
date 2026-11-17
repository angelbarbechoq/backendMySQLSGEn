# backendMySQLSGEn

Recolector de datos de LineaBaseX: lee PLC, pasarelas, mezcladores y generadores cada minuto (solo lectura) y guarda en
los mismos archivos SQLite que la app. Pagina en http://localhost:8081.

Banderas, carpetas, prueba y paso al servidor: `docs/GUIA-RECOLECTOR-PRUEBAS-Y-SERVIDOR.md` del repositorio de la app
(applineabase).

Resumen:
- `--colector.sqlite.carpeta=C:\LineaBaseX` escribe donde lee la app (la app con `--app.adquisicion.habilitada=false`).
  Sin la bandera escribe en `C:\LineaBaseX-prueba`.
- `--colector.lectura.habilitada=false` apaga la lectura.
- Empaquetar: `mvnw.cmd package` -> `target\dist\backendMySQLSGEn\` (con `backendMySQLSGEn.exe` y su propio Java).
- En el servidor: copiar esa carpeta y `servidor\iniciar-backend.bat` adentro, y abrir el .bat.
