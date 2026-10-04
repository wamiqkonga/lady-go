package com.ladygo.security.net;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
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

    // Deliberately permissive (not a full RFC validator): only needs to reject anything that would
    // make InetAddress.getByName() fall through to a real DNS lookup (see isIpLiteral below).
    private static final Pattern IPV4 = Pattern.compile("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+$");

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
        // A real proxy APPENDS its observed peer to any X-Forwarded-For the client already sent; it
        // does not replace it. So the left-most entry is always attacker-suppliable (adversarial
        // review finding, HIGH). Walk from the right, skipping any hop that is itself a trusted proxy
        // (handles proxy chains), and return the first hop that isn't - the actual client.
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = hops[i].trim();
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return properties.trustedProxies().stream().anyMatch(cidr -> matches(cidr, remoteAddr));
    }

    private boolean matches(String cidr, String address) {
        // DoS/side-channel fix: InetAddress.getByName() only avoids a real network DNS lookup when
        // given an IP literal. `address` comes from X-Forwarded-For - attacker-controlled - so a
        // non-literal value (a hostname) would otherwise make this request thread perform a blocking
        // outbound DNS query chosen by the attacker. Reject anything that isn't an IP literal first,
        // with no network call at all.
        if (!isIpLiteral(address)) {
            return false;
        }
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

    private boolean isIpLiteral(String address) {
        return IPV4.matcher(address).matches() || IPV6.matcher(address).matches();
    }
}
