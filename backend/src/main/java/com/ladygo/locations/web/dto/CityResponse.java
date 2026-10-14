package com.ladygo.locations.web.dto;

import java.util.UUID;

public record CityResponse(UUID id, String name, String country) {
}
