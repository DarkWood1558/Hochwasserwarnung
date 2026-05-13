@echo off
setlocal EnableDelayedExpansion
title Hochwasser-Fruehwarnung Startup

echo.
echo  *** Hochwasser-Fruehwarnung Neisse Goerlitz ***
echo.

:: Voraussetzungen pruefen
where docker >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [FEHLER] Docker nicht gefunden. Bitte Docker Desktop starten.
    pause & exit /b 1
)

where mvn >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [FEHLER] Maven nicht im PATH.
    pause & exit /b 1
)

:: Schritt 1: Nur Datenbank starten (kein Streamlit in Docker)
echo [1/5] Starte PostgreSQL...
docker-compose up -d db
if %ERRORLEVEL% NEQ 0 (
    echo [FEHLER] docker-compose fehlgeschlagen. Laeuft Docker Desktop?
    pause & exit /b 1
)

:: Schritt 2: Warten bis DB bereit
echo [2/5] Warte auf Datenbank...
set /a TRIES=0
:WAIT_LOOP
    set /a TRIES+=1
    if !TRIES! GTR 30 (
        echo [FEHLER] Datenbank nach 150s nicht erreichbar.
        pause & exit /b 1
    )
    docker exec hochwasser_db pg_isready -U postgres -d hochwasser >nul 2>&1
    if %ERRORLEVEL% EQU 0 goto DB_READY
    echo    Versuch !TRIES!/30 - warte 5 Sekunden...
    timeout /t 5 /nobreak >nul
    goto WAIT_LOOP

:DB_READY
echo    OK: Datenbank bereit.

:: Schritt 3: Java bauen
echo.
echo [3/5] Baue Java-Projekt...
call mvn -q package -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo [FEHLER] Maven-Build fehlgeschlagen.
    pause & exit /b 1
)
echo    OK: Build fertig.

:: Schritt 4a: Training
echo.
echo [4/5] ML-Training (PredictorTrainingRunner)...
call mvn -q exec:java -Dexec.mainClass="de.hochwasser.PredictorTrainingRunner"
if %ERRORLEVEL% NEQ 0 (
    echo    Warnung: Training mit Fehlern beendet.
) else (
    echo    OK: Training abgeschlossen.
)

:: Schritt 4b: Pegeldaten
echo [4/5] Pegeldaten abrufen (HWND + CHMI + DWD)...
call mvn -q exec:java -Dexec.mainClass="de.hochwasser.Main"
if %ERRORLEVEL% NEQ 0 (
    echo    Warnung: Datenabruf mit Fehlern.
) else (
    echo    OK: Pegeldaten gespeichert.
)

:: Schritt 5: Streamlit lokal starten
echo.
echo [5/5] Starte Streamlit-Dashboard lokal...
where streamlit >nul 2>&1

   py -m streamlit run dashboard.py


echo    Starte Dashboard in neuem Fenster...
start "Streamlit Dashboard" cmd /k "cd dashboard && streamlit run dashboard.py"

:: Kurz warten damit Streamlit hochfahren kann
timeout /t 4 /nobreak >nul

echo.
echo  Alles gestartet!
echo  Dashboard: http://localhost:8501
echo  Datenbank: localhost:5432
echo  Stoppen:   docker-compose down  (DB)
echo             Streamlit-Fenster schliessen (Dashboard)
echo.
set /p OPEN="Dashboard im Browser oeffnen? (J/N): "
if /i "%OPEN%"=="J" start http://localhost:8501

pause
