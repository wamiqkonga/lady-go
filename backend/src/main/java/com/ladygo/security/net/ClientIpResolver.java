package com.ladygo.security.net;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves the real client IP for rate limiting. X-Forwarded-For is attacker-controlled unless the
 * immediate TCP peer is a proxy we operate, so it is only trusted when the request actually arrived
 * from a configured trusted proxy (docs/security/auth-design.md section 8); otherwise the servlet
 * container's remote address is used directly and any forwarded header is ignored.
 */
@Component
@EnableConfigurationProperties(TrustedProxyProperties.class)
public class ClientIpResolver {

    private final TrustedProxyProperties properties;

    public ClientIpResolver(TrustedProxyProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }
        return forwardedFor.split(",")[0].trim();
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return properties.trustedProxies().stream().anyMatch(cidr -> matches(cidr, remoteAddr));
    }

    private boolean matches(String cidr, String address) {
        try {
            String[] parts = cidr.split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            InetAddress candidate = InetAddress.getByName(address);
            if (network.getAddress().length != candidate.getAddress().length) {
                return false;
            }
            int prefixLength = parts.length > 1
                    ? Integer.parseInt(parts[1])
                    : network.getAddress().length * 8;
            BigInteger networkBits = new BigInteger(1, network.getAddress());
            BigInteger candidateBits = new BigInteger(1, candidate.getAddress());
            int totalBits = network.getAddress().length * 8;
            BigInteger mask = BigInteger.valueOf(-1).shiftLeft(totalBits - prefixLength)
                    .and(BigInteger.valueOf(2).pow(totalBits).subtract(BigInteger.ONE));
            return networkBits.and(mask).equals(candidateBits.and(mask));
        } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException ex) {
            return false;
        }
    }
}
