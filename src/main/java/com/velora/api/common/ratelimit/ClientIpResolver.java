package com.velora.api.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The caller's IP address, for rate limiting.
 *
 * <p>Behind a reverse proxy — nginx, or the ngrok agent used to demo from a laptop —
 * {@code getRemoteAddr()} is the proxy, so every visitor shares one address and a
 * "10 per hour per IP" limit becomes "10 per hour for the whole site". The real address
 * arrives in {@code X-Forwarded-For}.
 *
 * <p>But that header is just text the sender wrote. Believing it unconditionally lets
 * anyone dodge a rate limit by sending a different value each time. So it is honoured
 * only when the TCP peer is itself a proxy we would plausibly run — loopback or a
 * private / link-local address — and even then it is read from the RIGHT: each proxy
 * appends the address it saw, so the entries on the left are whatever the client chose
 * to claim. The first entry from the right that is not itself a trusted proxy is the
 * client. A peer on a public address is the client, whatever it sends.
 *
 * <p>A malformed entry makes the whole header untrusted rather than guessed at.
 */
@Component
public class ClientIpResolver {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    public String resolve(HttpServletRequest request) {
        return resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
    }

    /** Pure, so the trust rules can be tested without a servlet request. */
    public static String resolve(String remoteAddr, String forwardedFor) {
        if (remoteAddr == null || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }

        String[] entries = forwardedFor.split(",");
        for (int i = entries.length - 1; i >= 0; i--) {
            String candidate = entries[i].trim();
            InetAddress address = parseLiteral(candidate);
            if (address == null) {
                return remoteAddr;
            }
            if (!isTrustedProxy(address)) {
                return address.getHostAddress();
            }
        }
        // Every hop was one of our own proxies.
        return remoteAddr;
    }

    private static boolean isTrustedProxy(String ip) {
        InetAddress address = parseLiteral(ip);
        return address != null && isTrustedProxy(address);
    }

    private static boolean isTrustedProxy(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) {
            return true;
        }
        // IPv6 unique-local fc00::/7, which Java does not call site-local.
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * Parses an IP literal. Anything that is not obviously a literal is rejected first,
     * because {@link InetAddress#getByName} would otherwise try to resolve it as a host
     * name — a DNS lookup driven by a request header.
     */
    private static InetAddress parseLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        boolean looksLikeIp = IPV4.matcher(value).matches()
                || (value.indexOf(':') >= 0 && IPV6.matcher(value).matches());
        if (!looksLikeIp) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}
