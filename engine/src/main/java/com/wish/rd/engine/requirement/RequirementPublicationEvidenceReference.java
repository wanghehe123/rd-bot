package com.wish.rd.engine.requirement;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.OptionalLong;

/** Shared validation for evidence references that may be exposed in a public PR. */
final class RequirementPublicationEvidenceReference {

    private static final long MAX_IPV4 = 0xffff_ffffL;

    private RequirementPublicationEvidenceReference() {
    }

    static URI parseHttpUri(String value) {
        try {
            URI uri = new URI(safe(value));
            String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme))
                    && !host(uri).isBlank() ? uri : null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    static boolean isPersistentReference(String value) {
        String normalized = safe(value);
        if (normalized.isBlank()) return false;
        URI uri = parseHttpUri(normalized);
        if (uri != null) return !isLoopbackHost(host(uri));
        String lower = normalized.toLowerCase(Locale.ROOT);
        return !lower.startsWith("http://") && !lower.startsWith("https://");
    }

    static boolean isLoopbackHost(String host) {
        String normalized = safe(host).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        int zoneIndex = normalized.indexOf('%');
        if (zoneIndex >= 0) normalized = normalized.substring(0, zoneIndex);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost")) return true;
        OptionalLong ipv4 = parseWhatwgIpv4(normalized);
        if (ipv4.isPresent()) {
            long address = ipv4.getAsLong();
            if (address == 0L || ((address >>> 24) & 0xffL) == 127L) return true;
        }
        return isLoopbackIpv6Literal(normalized);
    }

    static String host(URI uri) {
        if (uri == null) return "";
        if (uri.getHost() != null) return uri.getHost();
        String authority = safe(uri.getRawAuthority());
        int userInfo = authority.lastIndexOf('@');
        if (userInfo >= 0) authority = authority.substring(userInfo + 1);
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close > 0 ? authority.substring(0, close + 1) : "";
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(':') == colon) {
            authority = authority.substring(0, colon);
        }
        return authority;
    }

    private static OptionalLong parseWhatwgIpv4(String host) {
        String[] rawParts = host.split("\\.", -1);
        int partCount = rawParts.length;
        if (partCount > 1 && rawParts[partCount - 1].isEmpty()) partCount--;
        if (partCount < 1 || partCount > 4) return OptionalLong.empty();
        long[] parts = new long[partCount];
        for (int index = 0; index < partCount; index++) {
            String part = rawParts[index];
            int radix = 10;
            if (part.startsWith("0x") || part.startsWith("0X")) {
                radix = 16;
                part = part.substring(2);
            } else if (part.length() > 1 && part.startsWith("0")) {
                radix = 8;
                part = part.substring(1);
            }
            if (part.isEmpty()) part = "0";
            try {
                parts[index] = Long.parseLong(part, radix);
            } catch (NumberFormatException exception) {
                return OptionalLong.empty();
            }
            if (parts[index] < 0 || parts[index] > MAX_IPV4) return OptionalLong.empty();
        }
        for (int index = 0; index < partCount - 1; index++) {
            if (parts[index] > 255L) return OptionalLong.empty();
        }
        long lastMax = (1L << (8 * (5 - partCount))) - 1L;
        if (parts[partCount - 1] > lastMax) return OptionalLong.empty();
        long address = parts[partCount - 1];
        for (int index = 0; index < partCount - 1; index++) {
            address += parts[index] << (8 * (3 - index));
        }
        return address <= MAX_IPV4 ? OptionalLong.of(address) : OptionalLong.empty();
    }

    private static boolean isLoopbackIpv6Literal(String host) {
        if (!host.contains(":") || !host.matches("[0-9a-f:.]+")) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()) return true;
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) return (bytes[0] & 0xff) == 127;
            if (bytes.length != 16) return false;
            for (int index = 0; index < 10; index++) {
                if (bytes[index] != 0) return false;
            }
            return (bytes[10] & 0xff) == 0xff
                    && (bytes[11] & 0xff) == 0xff
                    && (bytes[12] & 0xff) == 127;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
