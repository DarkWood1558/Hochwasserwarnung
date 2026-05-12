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
 * Client fuer den Abruf von Upstream-Pegelstaenden
 * vom tschechischen Hydrometeorologischen Institut (CHMI).
 *
 * Die Upstream-Pegel sind entscheidend fuer die Vorhersage:
 * Hochwasser in Goerlitz kuendigt sich Stunden vorher
 * durch steigende Pegel in Tschechien an.
 *
 * HINWEIS: API-URL und JSON-Format muessen an die echte
 * CHMI-Schnittstelle angepasst werden.
 */
public class ChmiClient {

    private static final String BASE_URL =
        "https://hydro.chmi.cz/hpps/popup_hpps_pr498.html";

    private final HttpClient httpClient;
    private final int stationId;

    public ChmiClient(int stationId) {
        this.httpClient = HttpClient.newHttpClient();
        this.stationId = stationId;
    }

    public List<WaterLevel> fetchCurrentLevels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "CHMI API Fehler: HTTP " + response.statusCode()
            );
        }

        return parseResponse(response.body());
    }

    private List<WaterLevel> parseResponse(String json) {
        List<WaterLevel> results = new ArrayList<>();
        JsonArray data = JsonParser.parseString(json).getAsJsonArray();

        for (JsonElement element : data) {
            JsonObject obj = element.getAsJsonObject();
            Instant timestamp = Instant.parse(obj.get("timestamp").getAsString());
            double level = obj.get("value").getAsDouble();

            results.add(new WaterLevel(stationId, timestamp, level, "CHMI"));
        }
        return results;
    }
}
