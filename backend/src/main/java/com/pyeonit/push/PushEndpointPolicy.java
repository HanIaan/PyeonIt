package com.pyeonit.push;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class PushEndpointPolicy {
    private final List<String> allowedSuffixes;

    PushEndpointPolicy(@Value("${pyeonit.push.allowed-endpoint-host-suffixes}") String suffixes) {
        allowedSuffixes = Arrays.stream(suffixes.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).distinct().toList();
        if (allowedSuffixes.stream().anyMatch(s -> !publicDnsName(s)))
            throw new IllegalArgumentException("Push endpoint suffixes must be public DNS names");
    }

    void validate(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (value.length() > 2048 || !"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawAuthority().endsWith(":")) throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT);
            if (!publicDnsName(host)) throw new IllegalArgumentException();
            String normalized = host;
            if (allowedSuffixes.stream().noneMatch(s -> normalized.equals(s) || normalized.endsWith("." + s)))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw PushValidation.invalid(); }
    }

    private static boolean publicDnsName(String host) {
        // DNS syntax only: no IP literals (including IPv6, abbreviated/decimal IPv4), local or internal names.
        if (host.length() > 253 || !host.contains(".") || host.matches("[0-9.]+")
                || !host.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"))
            return false;
        return Arrays.stream(new String[]{"localhost", "local", "internal", "lan", "home", "corp", "intranet"})
                .noneMatch(s -> host.equals(s) || host.endsWith("." + s));
    }
}
