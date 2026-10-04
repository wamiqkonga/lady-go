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
    void trustedPeerSkipsTrustedHopsAndReturnsTheClientIp() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/8")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    /**
     * Adversarial review finding (HIGH): a real proxy APPENDS its observed peer to whatever
     * X-Forwarded-For the client already sent; it does not replace it. So if the resolver ever took
     * the left-most entry, an attacker sending their own fabricated X-Forwarded-For would have that
     * fabricated value trusted verbatim once a legitimate proxy appended the attacker's real IP after
     * it. The resolver must return the right-most entry that isn't itself a trusted proxy.
     */
    @Test
    void spoofedClientSuppliedHopIsIgnoredWhenTheProxyAppendsTheRealIp() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/8")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // the actual TCP peer: our trusted load balancer
        // Attacker-fabricated claim "9.9.9.9", with the proxy appending the attacker's real IP after it.
        request.addHeader("X-Forwarded-For", "9.9.9.9, 55.55.55.55");

        assertThat(resolver.resolve(request)).isEqualTo("55.55.55.55");
        assertThat(resolver.resolve(request)).isNotEqualTo("9.9.9.9");
    }

    @Test
    void multiHopChainSkipsEveryTrustedProxyInTheChain() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/8")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2"); // second (innermost) trusted proxy
        // First (outer) trusted proxy appended its own address after the real client's.
        request.addHeader("X-Forwarded-For", "203.0.113.50, 10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.50");
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
