package com.wish.rd.bootstrap.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Local, server-owned configuration for the Web evaluation process adapter. */
@Component
@ConfigurationProperties(prefix = "rd.evaluation")
public class EvaluationProperties {
    private boolean enabled = true;
    private Path repositoryRoot = Path.of(".").toAbsolutePath().normalize();
    private Path outputRoot = Path.of("qa-runs/evaluation");
    private Path datasetRoot = Path.of("scripts/evaluation/datasets");
    private Path ragLogRoot = Path.of("logs");
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
}
