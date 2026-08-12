package com.wish.rd.exec.repair.docker.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes an isolated task network and its trusted egress relay sidecar.
 *
 * <p>{@link ContainerRunRequest} uses this model when an untrusted workload must have
 * access only to a task-local sidecar while the sidecar alone also joins an egress network.
 */
public record ContainerNetworkPlan(
        String internalNetworkName,
        Sidecar sidecar
) {

    /**
     * Validates a task-local internal network plan.
     *
     * @param internalNetworkName Docker network joined by the untrusted workload and sidecar
     * @param sidecar trusted sidecar that is also attached to its bounded egress network
     */
    public ContainerNetworkPlan {
        internalNetworkName = requireDockerName(internalNetworkName, "internalNetworkName");
        if (sidecar == null) {
            throw new IllegalArgumentException("sidecar must not be null");
        }
        if (internalNetworkName.equals(sidecar.egressNetwork())) {
            throw new IllegalArgumentException("internalNetworkName must differ from the sidecar egress network");
        }
    }

    /**
     * Trusted sidecar startup contract used by the Docker CLI runner.
     *
     * @param containerName container name unique to one task/stage run
     * @param networkAlias fixed service alias visible to the untrusted workload on the internal network
     * @param image sidecar image
     * @param entrypoint optional Docker entrypoint executable; blank retains the image entrypoint
     * @param command sidecar process argv
     * @param env sidecar-only environment; it is never copied into the workload environment
     * @param egressNetwork Docker network that provides the Host/proxy route
     * @param healthCheckUrl loopback-only URL used by Docker startup verification
     * @param startupTimeoutMillis bounded time allowed for the health check
     * @param securityPolicy mandatory resource and privilege boundary for the sidecar
     */
    public record Sidecar(
            String containerName,
            String networkAlias,
            String image,
            String entrypoint,
            List<String> command,
            Map<String, String> env,
            String egressNetwork,
            String healthCheckUrl,
            long startupTimeoutMillis,
            ContainerSecurityPolicy securityPolicy
    ) {

        /**
         * Normalizes sidecar details before the Docker runner creates any resources.
         *
         * @param containerName container name unique to one task/stage run
         * @param networkAlias fixed service alias visible to the untrusted workload
         * @param image sidecar image
         * @param entrypoint optional Docker entrypoint executable
         * @param command sidecar process argv
         * @param env sidecar-only environment
         * @param egressNetwork Docker egress network
         * @param healthCheckUrl loopback-only health endpoint
         * @param startupTimeoutMillis bounded startup timeout
         * @param securityPolicy mandatory sidecar hardening policy
         */
        public Sidecar {
            containerName = requireDockerName(containerName, "containerName");
            networkAlias = requireDockerName(networkAlias, "networkAlias");
            image = requireText(image, "image");
            entrypoint = optionalEntrypoint(entrypoint);
            command = requireCommand(command);
            env = normalizeEnv(env);
            egressNetwork = requireDockerName(egressNetwork, "egressNetwork");
            healthCheckUrl = requireLoopbackHealthUrl(healthCheckUrl);
            if (startupTimeoutMillis <= 0L || startupTimeoutMillis > 30_000L) {
                throw new IllegalArgumentException("startupTimeoutMillis must be between 1 and 30000");
            }
            if (securityPolicy == null || !securityPolicy.enabled()) {
                throw new IllegalArgumentException("sidecar securityPolicy must be enabled");
            }
        }

        /**
         * Creates a sidecar that keeps the image-defined entrypoint.
         *
         * @param containerName container name unique to one task/stage run
         * @param networkAlias fixed service alias visible to the untrusted workload
         * @param image sidecar image
         * @param command sidecar process argv appended to the image entrypoint
         * @param env sidecar-only environment
         * @param egressNetwork Docker egress network
         * @param healthCheckUrl loopback-only health endpoint
         * @param startupTimeoutMillis bounded startup timeout
         * @param securityPolicy mandatory sidecar hardening policy
         */
        public Sidecar(
                String containerName,
                String networkAlias,
                String image,
                List<String> command,
                Map<String, String> env,
                String egressNetwork,
                String healthCheckUrl,
                long startupTimeoutMillis,
                ContainerSecurityPolicy securityPolicy
        ) {
            this(
                    containerName,
                    networkAlias,
                    image,
                    "",
                    command,
                    env,
                    egressNetwork,
                    healthCheckUrl,
                    startupTimeoutMillis,
                    securityPolicy
            );
        }

        private static String optionalEntrypoint(String value) {
            return value == null ? "" : value.strip();
        }

        private static String requireLoopbackHealthUrl(String value) {
            String normalized = requireText(value, "healthCheckUrl");
            URI uri;
            try {
                uri = URI.create(normalized);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("healthCheckUrl must be an absolute loopback HTTP URL", exception);
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    || (!"127.0.0.1".equals(host) && !"localhost".equals(host))
                    || uri.getPort() <= 0
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("healthCheckUrl must be an absolute loopback HTTP URL without credentials or query");
            }
            return uri.toString();
        }
    }

    private static String requireDockerName(String value, String field) {
        String normalized = requireText(value, field);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a valid Docker resource name");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static List<String> requireCommand(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            normalized.add(requireText(value, "command entry"));
        }
        return List.copyOf(normalized);
    }

    private static Map<String, String> normalizeEnv(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = requireText(key, "env key");
            String normalizedValue = value == null ? "" : value.strip();
            if (normalized.putIfAbsent(normalizedKey, normalizedValue) != null) {
                throw new IllegalArgumentException("env contains duplicate key after normalization: " + normalizedKey);
            }
        });
        return Map.copyOf(normalized);
    }
}
