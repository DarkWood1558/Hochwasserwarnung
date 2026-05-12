package de.hochwasser.model;

import java.time.Instant;

public record WaterLevel(
    int stationId,
    Instant measuredAt,
    double levelCm,
    String source
) {}
