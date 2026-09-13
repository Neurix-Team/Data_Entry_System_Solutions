package com.dataentry.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Only explicitly configured peers may supply a forwarding chain. No DNS lookups. */
@Component
public class ClientAddressResolver {
    private final Set<String> trustedPeers;

    public ClientAddressResolver(@Value("${app.security.trusted-proxies:}") String peers) {
        trustedPeers = Arrays.stream(peers.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String chain = request.getHeader("X-Forwarded-For");
        if (!trustedPeers.contains(peer) || chain == null || chain.length() > 2048) return peer;
        String[] hops = chain.split(",", -1);
        // Strip known proxies from the right, never trust an attacker-controlled first hop.
        for (int i = hops.length - 1; i >= 0 && trustedPeers.contains(peer); i--) {
            String hop = hops[i].trim();
            if (!hop.matches("[0-9a-fA-F:.]{3,45}")) return request.getRemoteAddr();
            peer = hop;
        }
        return peer;
    }
}
