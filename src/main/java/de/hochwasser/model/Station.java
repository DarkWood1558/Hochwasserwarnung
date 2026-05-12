package de.hochwasser.model;

public record Station(
    int stationId,
    String name,
    String country,
    String river,
    double latitude,
    double longitude,
    double kmToGoerlitz
) {}
