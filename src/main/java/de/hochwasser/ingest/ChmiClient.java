package de.hochwasser.ingest;

import de.hochwasser.model.WaterLevel;

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

/**
 * Client für Upstream-Pegelstände vom tschechischen
 * Hydrometeorologischen Institut (ČHMÚ / CHMI).
 *
 * Relevante Station: Hrádek nad Nisou (Lausitzer Neiße)
 *   Portal-URL: https://hydro.chmi.cz/hpps/popup_hpps_pr498.html?seq=153190010
 *   Station:    153190010 (Lužická Nisa / Lausitzer Neiße bei Hrádek)
 *
 * WARUM diese Station?
 *   Hrádek liegt ~40 km flussaufwärts von Görlitz. Steigende Pegel
 *   dort kündigen Hochwasser in Görlitz 4–12 Stunden vorher an.
 *   Das ist das zentrale Vorwarn-Feature des Projekts.
 *
 * HTML-Tabelle Format (CHMI Portal):
 *   <td>15.04.2025 06:00</td><td>143</td><td>...</td>
 *   Spalten: Datum/Zeit | Wasserstand (cm) | Abfluss (m³/s)
 */
public class ChmiClient {

    // Hrádek nad Nisou – Lausitzer Neiße
    private static final String HRADEK_URL =
            "https://hydro.chmi.cz/hpps/popup_hpps_pr498.html?seq=153190010";

    // Alternativ: Liberec (weiter upstream, ~80 km von Görlitz)
    private static final String LIBEREC_URL =
            "https://hydro.chmi.cz/hpps/popup_hpps_pr498.html?seq=151030000";

    private static final ZoneId PRAGUE_ZONE = ZoneId.of("Europe/Prague");

    // Zeitformat im CHMI-Portal: "15.04.2025 06:00"
    private static final DateTimeFormatter CHMI_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    // Regex für HTML-Tabellenzeilen: <td>datum</td><td>level</td>
    private static final Pattern ROW_PATTERN = Pattern.compile(
            "<td[^>]*>\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})\\s*</td>" +
                    "\\s*<td[^>]*>\\s*(\\d+)\\s*</td>",
            Pattern.CASE_INSENSITIVE
    );

    private final HttpClient http;
    private final int dbStationId;   // Interne DB-Stations-ID (z.B. 3 für Hrádek)
    private final String url;

    /**
     * @param dbStationId  Interne Stations-ID aus der stations-Tabelle
     * @param useHradek    true = Hrádek (empfohlen), false = Liberec
     */
    public ChmiClient(int dbStationId, boolean useHradek) {
        this.dbStationId = dbStationId;
        this.url = useHradek ? HRADEK_URL : LIBEREC_URL;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Standardkonstruktor: Hrádek nad Nisou. */
    public ChmiClient(int dbStationId) {
        this(dbStationId, true);
    }

    // ── Datenabruf ────────────────────────────────────────────────────────────

    /**
     * Ruft aktuelle Pegelstände ab (~letzte 48h, stündlich).
     *
     * @return Liste von WaterLevel-Records, source="CHMI"
     */
    public List<WaterLevel> fetchCurrentLevels() throws Exception {
        System.out.printf("[CHMI] Lade Hrádek nad Nisou (%s)...%n", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Hochwasserwarnung-Projekt/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("CHMI HTTP " + response.statusCode());
        }

        List<WaterLevel> result = parseHtml(response.body());
        System.out.printf("[CHMI] %d Messwerte von Hrádek geladen%n", result.size());
        return result;
    }

    // ── HTML Parsing ──────────────────────────────────────────────────────────

    /**
     * Parst die HTML-Tabelle des CHMI-Portals.
     *
     * Robustheit: Das Regex akzeptiert optionale Attribute in <td>-Tags
     * und ist unempfindlich gegenüber Whitespace-Änderungen im HTML.
     */
    private List<WaterLevel> parseHtml(String html) {
        List<WaterLevel> result = new ArrayList<>();
        Matcher m = ROW_PATTERN.matcher(html);

        while (m.find()) {
            try {
                String datetimeStr = m.group(1).trim();
                String levelStr    = m.group(2).trim();

                LocalDateTime ldt = LocalDateTime.parse(datetimeStr, CHMI_FMT);
                Instant ts = ldt.atZone(PRAGUE_ZONE).toInstant();
                double level = Double.parseDouble(levelStr);

                result.add(new WaterLevel(dbStationId, ts, level, "CHMI"));
            } catch (Exception e) {
                // Nicht-Datenzeilen (Header, Fußzeile) überspringen
            }
        }

        // Fallback: Wenn Regex nichts findet, alternative Tabellenstruktur versuchen
        if (result.isEmpty()) {
            result = parseHtmlFallback(html);
        }

        // Chronologisch sortieren (CHMI liefert neueste zuerst)
        result.sort((a, b) -> a.measuredAt().compareTo(b.measuredAt()));
        return result;
    }

    /**
     * Fallback-Parser für alternative CHMI-HTML-Struktur.
     * CHMI ändert das Portal gelegentlich; dieser Parser deckt eine
     * zweite bekannte Variante ab.
     */
    private List<WaterLevel> parseHtmlFallback(String html) {
        List<WaterLevel> result = new ArrayList<>();
        // Alternative: <span class="time">15.04.2025 06:00</span>
        //              <span class="value">143</span>
        Pattern altPattern = Pattern.compile(
                "class=[\"']time[\"'][^>]*>\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})" +
                        "[^<]*</span>[\\s\\S]{0,200}?" +
                        "class=[\"']value[\"'][^>]*>\\s*(\\d+)\\s*</span>",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m = altPattern.matcher(html);
        while (m.find()) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(m.group(1).trim(), CHMI_FMT);
                Instant ts = ldt.atZone(PRAGUE_ZONE).toInstant();
                result.add(new WaterLevel(dbStationId, ts,
                        Double.parseDouble(m.group(2).trim()), "CHMI"));
            } catch (Exception ignored) {}
        }
        return result;
    }
}