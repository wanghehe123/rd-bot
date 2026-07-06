package com.wish.rd.engine.requirement;

import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.wish.rd.engine.requirement.model.RequirementContextPackage;

/**
 * 需求上下文构建器。
 *
 * <p>当前版本只做确定性摘要，避免把外部模型调用混入控制面；后续可接入 RAG 检索增强。
 */
public final class RequirementContextBuilder {

    /**
     * 构建需求上下文包。
     *
     * @param task      需求任务
     * @param materials 任务材料
     * @return 上下文包
     */
    public RequirementContextPackage build(RdRequirementTask task, List<TaskMaterial> materials) {
        List<TaskMaterial> safeMaterials = materials == null ? List.of() : materials;
        String summary = summarize(task, safeMaterials);
        List<String> acceptanceCriteria = parseJsonArray(task == null ? "" : task.acceptanceCriteriaJson());
        List<String> suggestedCommands = suggestedValidationCommands(task, safeMaterials, acceptanceCriteria);
        List<String> materialIds = safeMaterials.stream()
                .map(TaskMaterial::materialId)
                .filter(value -> !value.isBlank())
                .toList();
        return new RequirementContextPackage(
                task == null ? "" : task.taskId(),
                summary,
                acceptanceCriteria,
                List.of("必须通过 PR 交付", "不得直接修改生产环境", "不得在日志、prompt 或 PR 中泄露密钥"),
                suggestedCommands,
                materialIds,
                "requirement-context-" + (task == null ? "" : task.taskId())
        );
    }

    private String summarize(RdRequirementTask task, List<TaskMaterial> materials) {
        String expected = task == null ? "" : task.expectedResult();
        String materialPreview = materials.stream()
                .map(TaskMaterial::contentPreview)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        String source = materialPreview.isBlank() ? expected : materialPreview;
        if (source.isBlank()) {
            source = task == null ? "" : task.title();
        }
        return preview(source, 500);
    }

    private List<String> suggestedValidationCommands(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            List<String> acceptanceCriteria
    ) {
        String corpus = ((task == null ? "" : task.title() + " " + task.expectedResult())
                + " "
                + materials.stream().map(TaskMaterial::contentPreview).reduce("", (left, right) -> left + " " + right)
                + " "
                + String.join(" ", acceptanceCriteria)).toLowerCase(Locale.ROOT);
        List<String> commands = new ArrayList<>();
        if (corpus.contains("前端") || corpus.contains("frontend") || corpus.contains("tsx") || corpus.contains("react")) {
            commands.add("npm run build");
        }
        if (corpus.contains("接口") || corpus.contains("后端") || corpus.contains("api") || corpus.contains("spring")) {
            commands.add("./mvnw test");
        }
        if (commands.isEmpty()) {
            commands.add("运行与改动范围匹配的构建或测试命令，并在结果中说明");
        }
        return List.copyOf(commands);
    }

    static List<String> parseJsonArray(String value) {
        String safe = value == null ? "" : value.strip();
        if (safe.length() < 2 || safe.charAt(0) != '[' || safe.charAt(safe.length() - 1) != ']') {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaping = false;
        for (int i = 1; i < safe.length() - 1; i++) {
            char ch = safe.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                if (inString) {
                    String item = current.toString().strip();
                    if (!item.isBlank()) {
                        values.add(item);
                    }
                    current.setLength(0);
                }
                inString = !inString;
            } else if (inString) {
                current.append(ch);
            }
        }
        return List.copyOf(values);
    }

    private static String preview(String value, int maxChars) {
        String safe = value == null ? "" : value.strip();
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars) + "...";
    }
}
