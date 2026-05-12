# Hochwasser-Frühwarnung Neiße Görlitz

Dieses Projekt ist ein Frühwarnsystem für Hochwasser an der Lausitzer Neiße, speziell fokussiert auf die Station Görlitz. Es sammelt Pegelstände und Wetterdaten aus verschiedenen Quellen (Deutschland und Tschechien), speichert diese in einer PostgreSQL/TimescaleDB und bietet Analysefunktionen zur Risikobewertung.

## Features

- **Daten-Ingest**: Automatisches Abrufen von Pegelständen von:
  - HWND Sachsen (Sächsisches Landesamt für Umwelt, Landwirtschaft und Geologie)
  - CHMI (Czech Hydrometeorological Institute)
  - DWD (Deutscher Wetterdienst) - Niederschlagsdaten
- **Datenbankspeicherung**: Zeitreihenoptimierte Speicherung mittels TimescaleDB-Partitionierung.
- **Analyse**:
  - Berechnung gleitender Mittelwerte.
  - Überwachung von Anstiegsraten pro Stunde.
  - Risiko-Vorhersage basierend auf aktuellen Pegelständen und Trends.
- **Historische Daten**: Unterstützung für die Speicherung und den Vergleich mit historischen Hochwasserereignissen.

## Voraussetzungen

- **Java 17** oder höher
- **Maven** 3.x
- **Docker** und **Docker Compose** (für die Datenbank)

## Projektstruktur

```text
.
├── docker-compose.yml       # Datenbank-Setup (TimescaleDB)
├── pom.xml                  # Maven Projektkonfiguration
├── sql/
│   └── schema.sql           # Datenbank-Schema und Initialdaten
├── src/main/java/de/hochwasser/
│   ├── analysis/            # Logik für Vorhersagen und Risikoanalyse
│   ├── db/                  # Datenbankzugriff (JDBC)
│   ├── ingest/              # API-Clients für HWND, CHMI, DWD
│   ├── model/               # Datenmodelle (POJOs/Records)
│   └── Main.java            # Haupteinstiegspunkt
└── src/main/resources/
    └── db.properties        # Datenbank-Verbindungseinstellungen
```

## Erste Schritte

### 1. Datenbank starten

Das Projekt nutzt TimescaleDB (eine Erweiterung von PostgreSQL) für die effiziente Verwaltung von Zeitreihendaten. Starten Sie die Datenbank einfach über Docker Compose:

```bash
docker-compose up -d
```

Dies startet einen Container namens `hochwasser_db` auf Port `5432`. Das Schema unter `sql/schema.sql` wird beim ersten Start automatisch importiert.

### 2. Konfiguration prüfen

Stellen Sie sicher, dass die Datenbank-Zugangsdaten in `src/main/resources/db.properties` korrekt sind:

```properties
db.url=jdbc:postgresql://localhost:5432/hochwasser
db.user=postgres
db.password=password
```

### 3. Anwendung bauen und starten

Verwenden Sie Maven, um das Projekt zu bauen:

```bash
mvn clean install
```

Starten Sie die Anwendung über die Profile in der `pom.xml`:

**Daten-Ingest (Aktuelle Pegel abrufen):**
```bash
mvn exec:java -Pingest
```

**Training des FloodPredictors (Inkl. Seed der historischen Daten):**
```bash
mvn exec:java -Ptrain
```

## Datenbank-Schema Highlights

- **`stations`**: Enthält Metadaten zu Pegelstationen entlang der Neiße (Görlitz, Zittau, Hrádek nad Nisou, Liberec).
- **`water_levels`**: Partitionierte Tabelle für Pegelstände (nach Jahr).
- **`precipitation`**: Partitionierte Tabelle für Niederschlagsdaten.
- **View `water_level_analysis`**: Berechnet automatisch den gleitenden 7-Stunden-Durchschnitt und die stündliche Anstiegsrate.

## Technologien

- **Sprache**: Java 17
- **Build-System**: Maven
- **Datenbank**: PostgreSQL 16 mit TimescaleDB Erweiterung
- **Bibliotheken**:
  - JDBC (PostgreSQL Driver)
  - Gson (JSON Parsing)
