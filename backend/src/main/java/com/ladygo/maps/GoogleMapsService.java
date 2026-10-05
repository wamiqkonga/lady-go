package com.ladygo.maps;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Real Google Maps Platform implementation (ADR-011). Server-side only - this key is never shipped
 * to a mobile app (that one is a separate, more restricted key baked into the app builds for map
 * display only; see docs/api/maps-service.md section 3). No caching/metrics here - that's
 * {@link CachingMapsService}, which wraps this so the two concerns stay independently testable.
 *
 * <p>Not Spring-managed directly: wired up by {@link MapsConfig} only when a real provider is
 * actually configured, so it can never accidentally become the active bean in dev/test.
 */
public class GoogleMapsService implements MapsService {

    private static final String BASE_URL = "https://maps.googleapis.com/maps/api";
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final RestClient restClient;
    private final String apiKey;

    public GoogleMapsService(RestClient.Builder restClientBuilder, String apiKey) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    @Override
    public GeocodeResult geocode(String address) {
        JsonNode result = firstResult(get("/geocode/json", "address", address));
        return toGeocodeResult(result);
    }

    @Override
    public GeocodeResult reverseGeocode(double latitude, double longitude) {
        JsonNode result = firstResult(get("/geocode/json", "latlng", latitude + "," + longitude));
        return toGeocodeResult(result);
    }

    @Override
    public RouteResult calculateRoute(Coordinate origin, Coordinate destination) {
        JsonNode body = get("/directions/json",
                "origin", origin.latitude() + "," + origin.longitude(),
                "destination", destination.latitude() + "," + destination.longitude());
        JsonNode leg = firstResult(body).path("legs").path(0);
        int distanceMeters = leg.path("distance").path("value").asInt();
        int durationSeconds = leg.path("duration").path("value").asInt();
        String polyline = firstResult(body).path("overview_polyline").path("points").asText(null);
        return new RouteResult(distanceMeters, durationSeconds, polyline);
    }

    @Override
    public double calculateDistanceMeters(Coordinate a, Coordinate b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double deltaLat = Math.toRadians(b.latitude() - a.latitude());
        double deltaLon = Math.toRadians(b.longitude() - a.longitude());
        double sinLat = Math.sin(deltaLat / 2);
        double sinLon = Math.sin(deltaLon / 2);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
    }

    @Override
    public List<PlaceSuggestion> searchPlaces(String query, String sessionToken, Coordinate biasedNear) {
        JsonNode body = biasedNear == null
                ? get("/place/autocomplete/json", "input", query, "sessiontoken", sessionToken)
                : get("/place/autocomplete/json", "input", query, "sessiontoken", sessionToken,
                        "location", biasedNear.latitude() + "," + biasedNear.longitude(), "radius", "50000");
        List<PlaceSuggestion> suggestions = new ArrayList<>();
        for (JsonNode prediction : body.path("predictions")) {
            suggestions.add(new PlaceSuggestion(
                    prediction.path("place_id").asText(), prediction.path("description").asText()));
        }
        return suggestions;
    }

    @Override
    public PlaceDetails getPlaceDetails(String placeId, String sessionToken) {
        JsonNode result = get("/place/details/json", "place_id", placeId, "sessiontoken", sessionToken)
                .path("result");
        JsonNode location = result.path("geometry").path("location");
        return new PlaceDetails(
                new Coordinate(location.path("lat").asDouble(), location.path("lng").asDouble()),
                result.path("formatted_address").asText(null));
    }

    private JsonNode firstResult(JsonNode body) {
        JsonNode results = body.has("routes") ? body.path("routes") : body.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new MapsServiceException(new IllegalStateException("No results: " + body.path("status")));
        }
        return results.path(0);
    }

    private JsonNode get(String path, String... queryParams) {
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uriBuilder -> {
                var builder = uriBuilder.path(path).queryParam("key", apiKey);
                for (int i = 0; i < queryParams.length; i += 2) {
                    if (queryParams[i + 1] != null) {
                        builder = builder.queryParam(queryParams[i], queryParams[i + 1]);
                    }
                }
                return builder.build();
            });
            JsonNode body = request.retrieve().body(JsonNode.class);
            if (body == null) {
                throw new MapsServiceException(new IllegalStateException("Empty response from Google Maps"));
            }
            String status = body.path("status").asText("");
            if (!status.isEmpty() && !"OK".equals(status) && !"ZERO_RESULTS".equals(status)) {
                throw new MapsServiceException(new IllegalStateException("Google Maps status: " + status));
            }
            return body;
        } catch (RestClientException ex) {
            throw new MapsServiceException(ex);
        }
    }

    private GeocodeResult toGeocodeResult(JsonNode result) {
        JsonNode location = result.path("geometry").path("location");
        return new GeocodeResult(
                new Coordinate(location.path("lat").asDouble(), location.path("lng").asDouble()),
                result.path("formatted_address").asText(null));
    }
}
