package de.hochwasser.ingest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.hochwasser.model.WaterLevel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client fuer den Abruf von Pegelstaenden vom
 * Hochwassernachrichtendienst (HWND) Sachsen.
 *
 * API-Endpunkt: https://www.umwelt.sachsen.de/umwelt/infosysteme/hwims/
 * Pegel Goerlitz/Neisse: Stations-ID muss ggf. angepasst werden.
 */
public class HwndSachsenClient {

    private static final String BASE_URL =
        "https://www.umwelt.sachsen.de/umwelt/infosysteme/hwims/portal/web/wasserstand-pegel-660160";

    private final HttpClient httpClient;
    private final int stationId;

    public HwndSachsenClient(int stationId) {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.stationId = stationId;
    }

    /**
     * Ruft aktuelle Pegelstaende ab.
     * Extrahiert die Daten aus der HTML-Tabelle.
     */
    public List<WaterLevel> fetchCurrentLevels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "HWND API Fehler: HTTP " + response.statusCode()
            );
        }

        return parseHtmlResponse(response.body());
    }

    private List<WaterLevel> parseHtmlResponse(String html) {
        List<WaterLevel> results = new ArrayList<>();
        
        // Regex zum Finden der Tabellenzeilen im HTML
        // Beispiel: <td headers="z">...</span>12.05.2026 09:30</td> <td headers="ws">141</td>
        String rowRegex = "<td headers=\"z\">.*?</span>(\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2})\\s*</td>\\s*<td headers=\"ws\">(\\d+)</td>";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(rowRegex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(html);

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(java.time.ZoneId.of("Europe/Berlin"));

        while (matcher.find()) {
            String timestampStr = matcher.group(1);
            String levelStr = matcher.group(2);

            try {
                Instant timestamp = Instant.from(formatter.parse(timestampStr));
                double level = Double.parseDouble(levelStr);

                results.add(new WaterLevel(stationId, timestamp, level, "HWND"));
            } catch (Exception e) {
                // Einzelne Zeile konnte nicht geparst werden -> ignorieren
            }
        }
        
        return results;
    }
}
