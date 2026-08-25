package com.wish.rd.engine.requirement;

import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView;
import com.wish.rd.engine.requirement.model.RequirementDeliveryPublicationView.AcceptanceResult;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从已批准的统一发布视图确定性生成 PR Markdown 正文。
 */
@Component
public class RequirementPullRequestBodyRenderer {

    private static final Pattern HTTP_URL_PATTERN = Pattern.compile(
            "https?://[^\\s<>)]+", Pattern.CASE_INSENSITIVE);

    /**
     * 生成固定章节、稳定排序且不含本机证据链接的 PR 正文。
     *
     * @param taskId     RD 任务 ID
     * @param operationId publication operation ID
     * @param title       需求标题
     * @param view        已批准的统一发布视图
     * @return 最终 PR Markdown 正文
     */
    public String render(
            String taskId,
            String operationId,
            String title,
            RequirementDeliveryPublicationView view
    ) {
        if (view == null || !view.deliveryReview().approved()) {
            throw new IllegalArgumentException("publication view must contain an approved delivery review");
        }
        if (!safe(taskId).equals(view.deliveryReview().taskId())) {
            throw new IllegalArgumentException("delivery review taskId must match publication taskId");
        }
        if (!"DELIVERY_REVIEWER".equals(view.deliveryReview().reviewer())) {
            throw new IllegalArgumentException("delivery review reviewer must be DELIVERY_REVIEWER");
        }
        if (!view.publicationFactsHash().equals(view.deliveryReview().factsHash())) {
            throw new IllegalArgumentException("delivery review factsHash must match publication facts");
        }
        StringBuilder body = new StringBuilder();
        appendHeading(body, "Summary");
        if (!safe(title).isBlank()) {
            body.append("- Title: ").append(markdown(title)).append('\n');
        }
        if (!view.summary().isBlank()) {
            body.append("- Delivery: ").append(markdown(view.summary())).append('\n');
        }
        if (!view.agentNarrative().isBlank() && !view.agentNarrative().equals(view.summary())) {
            body.append("- Agent narrative: ").append(markdown(view.agentNarrative())).append('\n');
        }

        appendHeading(body, "Changes");
        sortedDistinct(view.coding().changedFiles()).forEach(file ->
                body.append("- ").append(code(file)).append('\n'));

        appendHeading(body, "Verification");
        body.append("- Test status: ").append(code(view.coding().testStatus())).append('\n');
        body.append("- Risk level: ").append(code(view.coding().riskLevel())).append('\n');
        body.append("- Commands:\n");
        sortedDistinct(view.coding().testCommands()).forEach(command ->
                body.append("  - ").append(code(command)).append('\n'));

        appendHeading(body, "Acceptance");
        body.append("| # | Scope | Criteria | Status | Command | Exit code | Duration ms | Evidence |\n");
        body.append("|---:|---|---|---|---|---:|---:|---|\n");
        for (int index = 0; index < view.qa().acceptanceResults().size(); index++) {
            AcceptanceResult acceptance = view.qa().acceptanceResults().get(index);
            List<String> references = new ArrayList<>();
            addRenderableReference(references, acceptance.logArtifactId());
            acceptance.evidenceArtifactIds().forEach(value -> addRenderableReference(references, value));
            body.append("| ").append(index + 1)
                    .append(" | ").append(table(acceptance.scope()))
                    .append(" | ").append(table(acceptance.criteria()))
                    .append(" | ").append(table(acceptance.status()))
                    .append(" | ").append(tableCode(acceptance.command()))
                    .append(" | ").append(acceptance.exitCode() == null ? "" : acceptance.exitCode())
                    .append(" | ").append(acceptance.durationMillis() == null ? "" : acceptance.durationMillis())
                    .append(" | ").append(String.join("<br>", references))
                    .append(" |\n");
        }

        appendHeading(body, "Delivery Review");
        body.append("- Approved: **yes**\n");
        body.append("- Reviewer: ").append(code(view.deliveryReview().reviewer())).append('\n');
        String reviewReason = view.deliveryReview().reason().isBlank()
                ? "deterministic delivery review passed"
                : view.deliveryReview().reason();
        body.append("- Reason: ").append(markdown(reviewReason)).append('\n');

        appendHeading(body, "Evidence");
        sortedDistinct(view.evidenceReferences()).stream()
                .map(this::renderReference)
                .filter(reference -> !reference.isBlank())
                .forEach(reference -> body.append("- ").append(reference).append('\n'));

        appendHeading(body, "RD-Bot Provenance");
        body.append("- schemaVersion: ").append(code(Integer.toString(view.schemaVersion()))).append('\n');
        body.append("- taskId: ").append(code(taskId)).append('\n');
        body.append("- operationId: ").append(code(operationId)).append('\n');
        return body.toString().strip();
    }

    private void appendHeading(StringBuilder body, String heading) {
        if (!body.isEmpty()) body.append('\n');
        body.append("## ").append(heading).append("\n\n");
    }

    private List<String> sortedDistinct(Collection<String> values) {
        TreeSet<String> sorted = new TreeSet<>();
        if (values != null) {
            values.stream().map(RequirementPullRequestBodyRenderer::safe)
                    .filter(value -> !value.isBlank()).forEach(sorted::add);
        }
        return List.copyOf(sorted);
    }

    private void addRenderableReference(List<String> target, String value) {
        String rendered = renderReference(value);
        if (!rendered.isBlank() && !target.contains(rendered)) {
            target.add(rendered);
        }
    }

    private String renderReference(String value) {
        String normalized = safe(value);
        if (normalized.isBlank()) return "";
        URI uri = RequirementPublicationEvidenceReference.parseHttpUri(normalized);
        if (uri == null) return code(normalized);
        if (RequirementPublicationEvidenceReference.isLoopbackHost(
                RequirementPublicationEvidenceReference.host(uri))) return "";
        String escaped = normalized.replace(")", "%29").replace("(", "%28");
        return "[" + markdown(normalized) + "](" + escaped + ")";
    }

    private String table(String value) {
        return markdown(value).replace("\n", "<br>");
    }

    private String tableCode(String value) {
        return code(value).replace("|", "\\|");
    }

    private String markdown(String value) {
        return redactLoopbackUrls(safe(value))
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\\", "\\\\")
                .replace("#", "\\#")
                .replace("`", "&#96;")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("|", "\\|")
                .replace("\r\n", "<br>")
                .replace("\r", "<br>")
                .replace("\n", "<br>");
    }

    private String redactLoopbackUrls(String value) {
        Matcher matcher = HTTP_URL_PATTERN.matcher(safe(value));
        StringBuilder sanitized = new StringBuilder();
        while (matcher.find()) {
            URI uri = RequirementPublicationEvidenceReference.parseHttpUri(matcher.group());
            String replacement = uri != null
                    && RequirementPublicationEvidenceReference.isLoopbackHost(
                            RequirementPublicationEvidenceReference.host(uri))
                    ? "[loopback URL omitted]"
                    : matcher.group();
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    private String code(String value) {
        return "`" + safe(value).replace("`", "&#96;").replace("\r", " ").replace("\n", " ") + "`";
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
