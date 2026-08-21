package com.wish.rd.exec.repair.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 鉴权环境变量解析器。默认先读当前进程环境变量，再兼容 macOS GUI 进程通过
 * {@code launchctl setenv} 写入的用户环境。
 *
 * <p>该接口由所有容器执行器共用（Pi / Docker），因此放在共享端口包下，
 * 不绑定任何具体执行器实现。</p>
 */
@FunctionalInterface
public interface AuthEnvironmentResolver {

    /**
     * 按变量名解析鉴权值。
     *
     * @param envName 环境变量名
     * @return 鉴权值，未找到返回空字符串
     */
    String resolve(String envName);

    /**
     * 返回系统默认解析器。
     *
     * @return 默认解析器
     */
    static AuthEnvironmentResolver system() {
        return envName -> firstNonBlank(System.getenv(envName), launchctlGetenv(envName));
    }

    private static String launchctlGetenv(String envName) {
        String normalizedEnvName = normalize(envName);
        if (!normalizedEnvName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "";
        }
        try {
            Process process = new ProcessBuilder("/bin/launchctl", "getenv", normalizedEnvName).start();
            boolean completed = process.waitFor(2, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }
}
