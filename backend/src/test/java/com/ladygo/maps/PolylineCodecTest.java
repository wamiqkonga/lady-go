package com.ladygo.maps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolylineCodecTest {

    /** The worked example in Google's polyline algorithm documentation. */
    @Test
    void encodesTheDocumentedExample() {
        String encoded = PolylineCodec.encode(List.of(
                new Coordinate(38.5, -120.2), new Coordinate(40.7, -120.95), new Coordinate(43.252, -126.453)));

        assertThat(encoded).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
    }

    @Test
    void anEmptyRouteEncodesToAnEmptyString() {
        assertThat(PolylineCodec.encode(List.of())).isEmpty();
    }

    @Test
    void theMockRouteIsInTheSameFormatAsTheRealProviders() {
        RouteResult route = new MockMapsService()
                .calculateRoute(new Coordinate(34.08, 74.80), new Coordinate(34.10, 74.83));

        assertThat(route.polyline()).isNotBlank();
        // Five points of two deltas each, all ASCII in the range the format allows.
        assertThat(route.polyline()).matches("[\\x3f-\\x7e]+");
    }
}
