package com.wish.rd.exec.repair.pi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Materializes role-bound Skill Hub snapshots into a Pi attempt input directory.
 *
 * <p>Called by {@code DockerPiAgentExecutor} alongside resource materialization.
 * Writes {@code skills/} trees and {@code skill-manifest.json}; the Pi bridge
 * loads {@code skillPaths} via {@code additionalSkillPaths} while keeping
 * {@code noSkills: true}.
 */
@FunctionalInterface
public interface PiSkillMaterializerPort {

    /**
     * Materializes ACTIVE skills bound to {@code role} under {@code inputDirectory/skills}.
     *
     * @param role           Agent 角色
     * @param inputDirectory 当前 attempt 的 input 根目录
     * @throws IOException 物化或写清单失败
     */
    void materialize(String role, Path inputDirectory) throws IOException;

    /**
     * Writes an empty skills directory and empty manifest when no catalog is wired.
     *
     * @return no-op materializer that still creates the expected files
     */
    static PiSkillMaterializerPort emptyOnly() {
        return (role, inputDirectory) -> {
            Path inputRoot = (inputDirectory == null ? Path.of(".") : inputDirectory).toAbsolutePath().normalize();
            Path skillsRoot = inputRoot.resolve("skills").normalize();
            if (!skillsRoot.startsWith(inputRoot)) {
                throw new IOException("skills directory escapes input directory");
            }
            Files.createDirectories(skillsRoot);
            Path manifest = inputRoot.resolve("skill-manifest.json").normalize();
            if (!manifest.startsWith(inputRoot)) {
                throw new IOException("skill manifest path escapes input directory");
            }
            String safeRole = role == null ? "" : role.strip();
            String json = """
                    {
                      "protocol": "rd-skill-manifest/v1",
                      "role": "%s",
                      "skills": [],
                      "skillPaths": []
                    }
                    """.formatted(jsonEscape(safeRole));
            Files.writeString(manifest, json.strip() + "\n", StandardCharsets.UTF_8);
        };
    }

    private static String jsonEscape(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
