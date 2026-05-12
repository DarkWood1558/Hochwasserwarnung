package de.hochwasser.ingest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Client fuer den Abruf von Niederschlagsdaten
 * vom Deutschen Wetterdienst (DWD) Open Data.
 *
 * HINWEIS: API-URL und Datenformat muessen an die echte
 * DWD-Schnittstelle angepasst werden.
 * https://opendata.dwd.de/
 */
public class DwdClient {

    private static final String BASE_URL =
        "https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/hourly/precipitation/recent/";

    private final HttpClient httpClient;

    public DwdClient() {
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Ruft Niederschlagsdaten fuer das Einzugsgebiet ab.
     * Gibt die Daten als Liste von Doubles (mm) zurueck.
     */
    public List<Double> fetchPrecipitation() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL))
            .header("Accept", "text/plain")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "DWD API Fehler: HTTP " + response.statusCode()
            );
        }

        // TODO: CSV/Verzeichnislisting parsen und Niederschlagswerte extrahieren
        return new ArrayList<>();
    }
}
