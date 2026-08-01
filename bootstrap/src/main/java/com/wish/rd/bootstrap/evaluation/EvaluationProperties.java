package com.wish.rd.bootstrap.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Local, server-owned configuration for the Web evaluation process adapter. */
@Component
@ConfigurationProperties(prefix = "rd.evaluation")
public class EvaluationProperties {
    /** Container-backed executor used by production coding benchmark campaigns. */
    public static final String CODING_BENCHMARK_EXECUTOR_DOCKER = "docker";

    private final CodingBenchmark codingBenchmark = new CodingBenchmark();
    private boolean enabled = true;
    private Path repositoryRoot = Path.of(".").toAbsolutePath().normalize();
    private Path outputRoot = Path.of("qa-runs/evaluation");
    private Path datasetRoot = Path.of("scripts/evaluation/datasets");
    private Path ragLogRoot = Path.of("logs");
    private Path codingBenchmarkRoot = Path.of("scripts/evaluation/coding-benchmark-v2");
    private Path codingBenchmarkPrepRoot = Path.of("/Volumes/WishDisk/codes/swe/public-prep");
    private String pythonExecutable = "python3";
    private String ragasPythonExecutable = "";
    private String defaultBaseUrl = "http://127.0.0.1:18080";
    private int parallelism = 2;
    private int maxLogChars = 200_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getRepositoryRoot() {
        return repositoryRoot;
    }

    public void setRepositoryRoot(Path repositoryRoot) {
        this.repositoryRoot = discoverRepositoryRoot(normalized(repositoryRoot, Path.of(".")));
    }

    public Path getOutputRoot() {
        return outputRoot;
    }

    public void setOutputRoot(Path outputRoot) {
        this.outputRoot = outputRoot == null ? Path.of("qa-runs/evaluation") : outputRoot.normalize();
    }

    public Path getDatasetRoot() {
        return datasetRoot;
    }

    public void setDatasetRoot(Path datasetRoot) {
        this.datasetRoot = datasetRoot == null ? Path.of("scripts/evaluation/datasets") : datasetRoot.normalize();
    }

    public Path getRagLogRoot() {
        return ragLogRoot;
    }

    public void setRagLogRoot(Path ragLogRoot) {
        this.ragLogRoot = ragLogRoot == null ? Path.of("logs") : ragLogRoot.normalize();
    }

    public Path getCodingBenchmarkRoot() {
        return codingBenchmarkRoot;
    }

    /** Configures the server-owned root containing immutable coding benchmark snapshots. */
    public void setCodingBenchmarkRoot(Path codingBenchmarkRoot) {
        this.codingBenchmarkRoot = codingBenchmarkRoot == null
                ? Path.of("scripts/evaluation/coding-benchmark-v2") : codingBenchmarkRoot.normalize();
    }

    /** @return the nested switches bound under {@code rd.evaluation.coding-benchmark} */
    public CodingBenchmark getCodingBenchmark() {
        return codingBenchmark;
    }

    /**
     * @return the selected coding benchmark executor implementation, {@code docker} by default and
     *         {@code fake} for the local, container-free control-plane rehearsal
     */
    public String codingBenchmarkExecutor() {
        return codingBenchmark.getExecutor();
    }

    public Path getCodingBenchmarkPrepRoot() {
        return codingBenchmarkPrepRoot;
    }

    /** Configures the server-owned root for coding benchmark case assets (prepared repos, caches, bundles). */
    public void setCodingBenchmarkPrepRoot(Path codingBenchmarkPrepRoot) {
        this.codingBenchmarkPrepRoot = codingBenchmarkPrepRoot == null
                ? Path.of("/Volumes/WishDisk/codes/swe/public-prep") : codingBenchmarkPrepRoot.toAbsolutePath().normalize();
    }

    /** @return absolute normalized root for coding benchmark case runtime assets */
    public Path resolvedCodingBenchmarkPrepRoot() {
        return resolveAgainstRepository(codingBenchmarkPrepRoot);
    }

    public String getPythonExecutable() {
        return pythonExecutable;
    }

    public void setPythonExecutable(String pythonExecutable) {
        this.pythonExecutable = defaultWhenBlank(pythonExecutable, "python3");
    }

    public String getRagasPythonExecutable() {
        return ragasPythonExecutable;
    }

    public void setRagasPythonExecutable(String ragasPythonExecutable) {
        this.ragasPythonExecutable = ragasPythonExecutable == null ? "" : ragasPythonExecutable.trim();
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public void setDefaultBaseUrl(String defaultBaseUrl) {
        this.defaultBaseUrl = defaultWhenBlank(defaultBaseUrl, "http://127.0.0.1:18080");
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = Math.max(1, Math.min(8, parallelism));
    }

    public int getMaxLogChars() {
        return maxLogChars;
    }

    public void setMaxLogChars(int maxLogChars) {
        this.maxLogChars = Math.max(10_000, Math.min(2_000_000, maxLogChars));
    }

    /** @return absolute normalized output root */
    public Path resolvedOutputRoot() {
        return resolveAgainstRepository(outputRoot);
    }

    /** @return absolute normalized dataset root */
    public Path resolvedDatasetRoot() {
        return resolveAgainstRepository(datasetRoot);
    }

    /** @return absolute normalized RAG log root */
    public Path resolvedRagLogRoot() {
        return resolveAgainstRepository(ragLogRoot);
    }

    /** @return absolute normalized root for trusted coding benchmark snapshot discovery */
    public Path resolvedCodingBenchmarkRoot() {
        return resolveAgainstRepository(codingBenchmarkRoot);
    }

    private Path resolveAgainstRepository(Path value) {
        Path path = value == null ? Path.of("") : value;
        return (path.isAbsolute() ? path : repositoryRoot.resolve(path)).toAbsolutePath().normalize();
    }

    private static Path normalized(Path value, Path fallback) {
        Path selected = value == null ? fallback : value;
        return selected.toAbsolutePath().normalize();
    }

    private static Path discoverRepositoryRoot(Path configured) {
        Path current = configured;
        for (int index = 0; index < 5 && current != null; index++) {
            if (java.nio.file.Files.isRegularFile(current.resolve("scripts/evaluation/rd_eval_run.py"))) {
                return current;
            }
            current = current.getParent();
        }
        return configured;
    }

    private static String defaultWhenBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Coding benchmark control-plane switches bound under {@code rd.evaluation.coding-benchmark}.
     *
     * <p>The executor key must stay a nested property because {@code @ConditionalOnProperty} on
     * {@code DockerCodingBenchmarkExecutor} and the Fake executor bean resolves the literal key
     * {@code rd.evaluation.coding-benchmark.executor} from the Environment.</p>
     */
    public static class CodingBenchmark {
        private static final String DEFAULT_RELAY_IMAGE = "rd-bot/coding-eval-relay:local";

        private String executor = CODING_BENCHMARK_EXECUTOR_DOCKER;
        private String oracleImage = "";
        private final ModelProvider modelProvider = new ModelProvider();

        public String getExecutor() {
            return executor;
        }

        /** Selects the trial executor; a blank value keeps the Docker executor default. */
        public void setExecutor(String executor) {
            this.executor = defaultWhenBlank(executor, CODING_BENCHMARK_EXECUTOR_DOCKER).toLowerCase(Locale.ROOT);
        }

        public String getOracleImage() {
            return oracleImage;
        }

        /** Fallback oracle image when the frozen environment manifest does not list one. */
        public void setOracleImage(String oracleImage) {
            this.oracleImage = oracleImage == null ? "" : oracleImage.strip();
        }

        public ModelProvider getModelProvider() {
            return modelProvider;
        }

        /** @return configured relay image or the local probe default */
        public String resolvedRelayImage() {
            return modelProvider.resolvedRelayImage();
        }

        /** @return oracle image digest reference when explicitly configured */
        public String resolvedOracleImage() {
            return oracleImage;
        }

        /**
         * Model upstream settings for coding-benchmark probe runs.
         *
         * <p>Bound from {@code rd.evaluation.coding-benchmark.model-provider.*}.</p>
         */
        public static class ModelProvider {
            private String name = "opencode-go";
            private String baseUrl = "https://opencode.ai/zen/go/v1";
            private String model = "deepseek-v4-flash";
            private String apiKeyEnv = "OPENCODE_API_KEY";
            private String protocol = "openai-chat-completions";
            private String relayImage = DEFAULT_RELAY_IMAGE;
            private List<String> allowedPaths = defaultAllowedPaths();
            /** Optional secondary upstream used by the relay when the primary quota/auth fails. */
            private final FallbackProvider fallback = new FallbackProvider();

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = defaultWhenBlank(name, "opencode-go");
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = defaultWhenBlank(baseUrl, "https://opencode.ai/zen/go/v1");
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = defaultWhenBlank(model, "deepseek-v4-flash");
            }

            public String getApiKeyEnv() {
                return apiKeyEnv;
            }

            public void setApiKeyEnv(String apiKeyEnv) {
                this.apiKeyEnv = defaultWhenBlank(apiKeyEnv, "OPENCODE_API_KEY");
            }

            public String getProtocol() {
                return protocol;
            }

            public void setProtocol(String protocol) {
                this.protocol = defaultWhenBlank(protocol, "openai-chat-completions");
            }

            public String getRelayImage() {
                return relayImage;
            }

            public void setRelayImage(String relayImage) {
                this.relayImage = defaultWhenBlank(relayImage, DEFAULT_RELAY_IMAGE);
            }

            public List<String> getAllowedPaths() {
                return allowedPaths;
            }

            public void setAllowedPaths(List<String> allowedPaths) {
                this.allowedPaths = allowedPaths == null || allowedPaths.isEmpty()
                        ? defaultAllowedPaths()
                        : List.copyOf(allowedPaths);
            }

            public FallbackProvider getFallback() {
                return fallback;
            }

            public String resolvedRelayImage() {
                return defaultWhenBlank(relayImage, DEFAULT_RELAY_IMAGE);
            }

            /** @return true when a usable secondary upstream is configured */
            public boolean hasFallback() {
                return !fallback.getName().isBlank()
                        && !fallback.getBaseUrl().isBlank()
                        && !fallback.getModel().isBlank()
                        && !fallback.getApiKeyEnv().isBlank();
            }

            /**
             * Secondary model upstream for coding-benchmark relay quota/auth fallback.
             *
             * <p>Bound from {@code rd.evaluation.coding-benchmark.model-provider.fallback.*}.</p>
             */
            public static class FallbackProvider {
                private String name = "";
                private String baseUrl = "";
                private String model = "";
                private String apiKeyEnv = "";

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name == null ? "" : name.strip();
                }

                public String getBaseUrl() {
                    return baseUrl;
                }

                public void setBaseUrl(String baseUrl) {
                    this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
                }

                public String getModel() {
                    return model;
                }

                public void setModel(String model) {
                    this.model = model == null ? "" : model.strip();
                }

                public String getApiKeyEnv() {
                    return apiKeyEnv;
                }

                public void setApiKeyEnv(String apiKeyEnv) {
                    this.apiKeyEnv = apiKeyEnv == null ? "" : apiKeyEnv.strip();
                }
            }

            /** Maps configured protocol strings to Pi bridge API identifiers. */
            public String piApi() {
                String normalized = protocol.toLowerCase(Locale.ROOT).replace('_', '-');
                return switch (normalized) {
                    case "anthropic-compatible", "anthropic-messages" -> "anthropic-messages";
                    case "openai-chat-completions", "openai-completions" -> "openai-completions";
                    case "openai-responses" -> "openai-responses";
                    case "google-generative-ai" -> "google-generative-ai";
                    default -> throw new IllegalArgumentException("unsupported coding benchmark model protocol: " + protocol);
                };
            }

            private static List<String> defaultAllowedPaths() {
                return List.of(
                        "/chat/completions",
                        "/v1/chat/completions",
                        "/models",
                        "/v1/models");
            }
        }
    }
}
