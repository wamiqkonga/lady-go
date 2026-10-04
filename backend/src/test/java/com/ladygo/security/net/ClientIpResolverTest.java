package com.ladygo.security.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    @Test
    void untrustedPeerIgnoresForwardedHeaderEntirely() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void trustedPeerUsesTheFirstForwardedHop() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/8")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void trustedPeerWithNoForwardedHeaderFallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/8")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void peerJustOutsideTheTrustedRangeIsNotTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/24")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.1.5");
    }
}
