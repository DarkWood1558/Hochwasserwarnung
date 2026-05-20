#!/bin/bash

# Farben für die Ausgabe
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== Hochwasser-Frühwarnung Start-Skript ===${NC}"

# Überprüfe Voraussetzungen
check_command() {
    if ! command -v $1 &> /dev/null; then
        # Spezialfall für docker-compose v2 (wird oft als 'docker compose' aufgerufen)
        if [ "$1" == "docker-compose" ]; then
            if docker compose version &> /dev/null; then
                return 0
            fi
        fi
        echo -e "${RED}Fehler: $1 ist nicht installiert.${NC}"
        exit 1
    fi
}

check_command docker
check_command docker-compose
check_command java
check_command mvn

# 1. Datenbank starten
echo -e "${BLUE}[1/4] Starte Datenbank (Docker Compose)...${NC}"
if command -v docker-compose &> /dev/null; then
    docker-compose up -d
else
    docker compose up -d
fi

if [ $? -ne 0 ]; then
    echo -e "${RED}Fehler beim Starten der Docker-Container.${NC}"
    exit 1
fi

# 2. Projekt bauen
echo -e "${BLUE}[2/4] Baue Projekt mit Maven...${NC}"
mvn clean install -DskipTests

if [ $? -ne 0 ]; then
    echo -e "${RED}Fehler beim Bauen des Projekts.${NC}"
    exit 1
fi

# 3. Anwendung starten
echo -e "${BLUE}[3/4] Anwendung starten...${NC}"

# 3a. Daten-Ingest
echo -e "${GREEN}Schritt A: Starte Ingest (Aktuelle Pegel abrufen)...${NC}"
mvn exec:java -Pingest
if [ $? -ne 0 ]; then
    echo -e "${RED}Fehler beim Daten-Ingest.${NC}"
    exit 1
fi

# 3b. Modell-Training
echo -e "${GREEN}Schritt B: Starte Training (Historische Daten analysieren)...${NC}"
mvn exec:java -Ptrain
if [ $? -ne 0 ]; then
    echo -e "${RED}Fehler beim Modell-Training.${NC}"
    exit 1
fi

# 4. Dashboard starten
echo -e "${BLUE}[4/4] Starte Dashboard (Streamlit)...${NC}"

# Überprüfe Python-Voraussetzungen für das Dashboard
check_command python3

# Nutze ein Virtual Environment, um "externally-managed-environment" Fehler zu vermeiden
VENV_DIR=".venv"

if [ ! -d "$VENV_DIR" ]; then
    echo -e "${BLUE}Erstelle Python Virtual Environment...${NC}"
    python3 -m venv "$VENV_DIR"
    if [ $? -ne 0 ]; then
        echo -e "${RED}Fehler: 'python3-venv' ist vermutlich nicht installiert.${NC}"
        echo -e "${RED}Bitte installieren Sie es (z.B. sudo apt install python3-venv).${NC}"
        exit 1
    fi
fi

# Installiere/Update Abhängigkeiten im Venv
echo -e "${BLUE}Überprüfe/Installiere Python-Abhängigkeiten...${NC}"
"$VENV_DIR/bin/pip" install --upgrade pip
"$VENV_DIR/bin/pip" install streamlit pandas plotly psycopg2-binary

echo -e "${GREEN}Das Dashboard wird in einem neuen Tab oder Fenster geöffnet.${NC}"
echo -e "${GREEN}Drücken Sie STRG+C in diesem Terminal, um das Dashboard zu beenden.${NC}"

"$VENV_DIR/bin/python3" -m streamlit run dashboard.py
