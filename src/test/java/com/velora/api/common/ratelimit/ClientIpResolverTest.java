package com.velora.api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behind a proxy (nginx, or the ngrok agent on a laptop) every visitor's TCP peer is the
 * proxy. The forwarded header is honoured only from a proxy we would plausibly run, and
 * read from the right — the left of the list is whatever the client chose to claim.
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("A direct connection is the client — no header involved")
    void directConnection() {
        assertThat(ClientIpResolver.resolve("203.0.113.9", null)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("A public peer cannot choose its own IP by sending X-Forwarded-For")
    void publicPeerCannotSpoof() {
        assertThat(ClientIpResolver.resolve("203.0.113.9", "1.2.3.4")).isEqualTo("203.0.113.9");
        assertThat(ClientIpResolver.resolve("203.0.113.9", "1.2.3.4, 5.6.7.8"))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("Through a local proxy (ngrok agent on loopback) the forwarded client is used")
    void loopbackProxyForwardsTheClient() {
        assertThat(ClientIpResolver.resolve("127.0.0.1", "198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(ClientIpResolver.resolve("0:0:0:0:0:0:0:1", "198.51.100.7"))
                .isEqualTo("198.51.100.7");
        assertThat(ClientIpResolver.resolve("::1", "198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("Two visitors behind the same proxy are two clients")
    void distinctVisitorsStayDistinct() {
        assertThat(ClientIpResolver.resolve("127.0.0.1", "198.51.100.7"))
                .isNotEqualTo(ClientIpResolver.resolve("127.0.0.1", "198.51.100.8"));
    }

    @Test
    @DisplayName("Entries the client prepended on the left are ignored; the right one wins")
    void clientCannotSpoofThroughATrustedProxy() {
        // The proxy appended the address it really saw (198.51.100.7); the client claimed 9.9.9.9.
        assertThat(ClientIpResolver.resolve("127.0.0.1", "9.9.9.9, 198.51.100.7"))
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("A chain of our own proxies is skipped to reach the client")
    void chainOfInternalProxies() {
        assertThat(ClientIpResolver.resolve("10.0.0.5", "198.51.100.7, 10.0.0.9, 192.168.1.4"))
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("Private and link-local peers count as proxies; unique-local IPv6 too")
    void privateRangesAreProxies() {
        assertThat(ClientIpResolver.resolve("192.168.1.10", "198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(ClientIpResolver.resolve("172.16.0.1", "198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(ClientIpResolver.resolve("169.254.1.1", "198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(ClientIpResolver.resolve("fd00::1", "198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("An IPv6 client behind a proxy is recognised")
    void ipv6Client() {
        assertThat(ClientIpResolver.resolve("127.0.0.1", "2001:db8::1"))
                .isEqualTo(ClientIpResolver.resolve("127.0.0.1", "2001:0db8:0000:0000:0000:0000:0000:0001"));
    }

    @Test
    @DisplayName("Garbage in the header is not guessed at — fall back to the peer")
    void malformedHeaderFallsBackToThePeer() {
        assertThat(ClientIpResolver.resolve("127.0.0.1", "not-an-ip")).isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.resolve("127.0.0.1", "198.51.100.7, evil.example.com"))
                .isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.resolve("127.0.0.1", "198.51.100.7:8080")).isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.resolve("127.0.0.1", "999.1.1.1")).isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.resolve("127.0.0.1", "")).isEqualTo("127.0.0.1");
        assertThat(ClientIpResolver.resolve("127.0.0.1", "   ")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("A missing peer address is passed through, not a crash")
    void nullPeer() {
        assertThat(ClientIpResolver.resolve(null, "198.51.100.7")).isNull();
    }
}
