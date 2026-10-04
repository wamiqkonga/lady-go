package com.ladygo.security.net;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CIDR ranges (IPv4 or IPv6, e.g. "10.0.0.0/8") of proxies/load balancers allowed to set
 * X-Forwarded-For. Empty by default: nothing is trusted until the real deployment topology is
 * configured (docs/security/auth-design.md section 8).
 */
@ConfigurationProperties(prefix = "ladygo.security")
public record TrustedProxyProperties(List<String> trustedProxies) {

    public TrustedProxyProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }
}
