@echo off
rem Inicia el backend recolector escribiendo en C:\LineaBaseX (forma B de la guia).
rem Va en la misma carpeta que backendMySQLSGEn.exe.
rem La app debe estar con --app.adquisicion.habilitada=false: nunca los dos escribiendo los mismos archivos.
title Backend recolector (8081)
cd /d "%~dp0"
backendMySQLSGEn.exe --colector.sqlite.carpeta=C:\LineaBaseX
pause
