package de.hochwasser.ingest;

import de.hochwasser.model.WaterLevel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/**
 * Client für Niederschlagsdaten vom DWD Open Data Server.
 *
 * Datenquelle: opendata.dwd.de – stündliche Niederschlagsmessungen
 *   URL-Muster: .../hourly/precipitation/recent/stundenwerte_RR_{STATION_ID}_akt.zip
 *
 * CSV-Format innerhalb der ZIP-Datei (Semikolon-getrennt):
 *   STATIONS_ID;MESS_DATUM;  QN_8;  R1;RS_IND;WRTR;eor
 *   1684;2025040100;10;0.1;0;0;eor
 *   MESS_DATUM = YYYYMMDDHH  |  R1 = Niederschlag mm/h  |  -999 = fehlend
 *
 * Relevante Station:
 *   Görlitz  (ID: 01684) – direkt im Einzugsgebiet
 *   Zittau   (ID: 01691) – Alternativstation Oberlauf
 */
public class DwdClient {

    private static final String BASE_URL =
            "https://opendata.dwd.de/climate_environment/CDC/observations_germany/" +
                    "climate/hourly/precipitation/recent/";

    private static final ZoneId DWD_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter DWD_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final HttpClient http;
    private final int dwdStationId; // z.B. 1684 für Görlitz

    /**
     * @param dwdStationId DWD-Stations-ID (z.B. 1684 für Görlitz, 1691 für Zittau)
     */
    public DwdClient(int dwdStationId) {
        this.dwdStationId = dwdStationId;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── Datenabruf ────────────────────────────────────────────────────────────

    /**
     * Lädt aktuelle stündliche Niederschlagsdaten (letzte ~500 Tage).
     *
     * @param dbStationId Interne Stations-ID für die DB (aus stations-Tabelle)
     * @return Liste stündlicher Niederschlagswerte als WaterLevel-Records
     *         (levelCm enthält hier Niederschlag in mm – gleiche Tabelle, source="DWD")
     */
    public List<WaterLevel> fetchPrecipitation(int dbStationId) throws Exception {
        String filename = "stundenwerte_RR_%05d_akt.zip".formatted(dwdStationId);
        String url = BASE_URL + filename;

        System.out.printf("[DWD] Lade %s ...%n", filename);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Hochwasserwarnung-Projekt/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new RuntimeException("DWD HTTP " + response.statusCode() + " für " + url);
        }

        List<WaterLevel> result = parseZip(response.body(), dbStationId);
        System.out.printf("[DWD] %d stündliche Niederschlagswerte geladen%n", result.size());
        return result;
    }

    // ── ZIP + CSV Parsing ────────────────────────────────────────────────────

    /**
     * Entpackt die ZIP im Speicher und parst die enthaltene produkt_rr_*.txt Datei.
     * Die ZIP enthält mehrere Dateien; die Datenmesswerte sind in der Datei
     * mit dem Prefix "produkt_rr_stunde_".
     */
    private List<WaterLevel> parseZip(InputStream zipStream, int dbStationId) throws Exception {
        List<WaterLevel> result = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("produkt_rr_stunde_")) {
                    result = parseCsv(zis, dbStationId);
                }
                zis.closeEntry();
            }
        }
        return result;
    }

    /**
     * Parst DWD-CSV: STATIONS_ID;MESS_DATUM;QN_8;R1;RS_IND;WRTR;eor
     * Überspringt fehlende Werte (R1 == -999) und Kopfzeile.
     */
    private List<WaterLevel> parseCsv(InputStream csv, int dbStationId) throws Exception {
        List<WaterLevel> result = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(csv));
        String line;
        boolean firstLine = true;

        while ((line = reader.readLine()) != null) {
            if (firstLine) { firstLine = false; continue; } // Kopfzeile überspringen
            line = line.trim();
            if (line.isEmpty() || line.startsWith("STATIONS_ID")) continue;

            String[] parts = line.split(";");
            if (parts.length < 4) continue;

            try {
                String datumStr = parts[1].trim(); // YYYYMMDDHH
                String r1Str    = parts[3].trim(); // mm/h

                double precip = Double.parseDouble(r1Str);
                if (precip < -998) continue; // -999 = fehlend

                LocalDateTime ldt = LocalDateTime.parse(datumStr, DWD_FMT);
                Instant ts = ldt.atZone(DWD_ZONE).toInstant();

                result.add(new WaterLevel(dbStationId, ts, precip, "DWD"));
            } catch (Exception e) {
                // Einzelne defekte Zeile ignorieren
            }
        }
        return result;
    }

    // ── Hilfsmethode: Stations-ID aus Verzeichnislisting finden ──────────────

    /**
     * Durchsucht das DWD-Verzeichnislisting nach der ZIP-Datei für eine Station.
     * Nützlich wenn die genaue Stations-ID nicht bekannt ist.
     *
     * @param stationNameFragment Teil des Stationsnamens (z.B. "Görlitz")
     * @return DWD-Stations-ID oder -1 wenn nicht gefunden
     */
    public int findStationId(String stationNameFragment) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .GET()
                .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        // Links haben Form: stundenwerte_RR_01684_akt.zip
        Pattern p = Pattern.compile("stundenwerte_RR_(\\d{5})_akt\\.zip");
        Matcher m = p.matcher(response.body());
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            if (String.valueOf(id).contains(stationNameFragment.toLowerCase())) return id;
        }
        return -1;
    }
}