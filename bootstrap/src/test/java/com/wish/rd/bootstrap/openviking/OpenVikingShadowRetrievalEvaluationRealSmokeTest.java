package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wish.rd.bootstrap.openviking.impl.JdkOpenVikingHttpExchange;
import com.wish.rd.bootstrap.openviking.impl.OpenVikingRestNavigatorAdapter;
import com.wish.rd.bootstrap.persistence.impl.PostgresKnowledgeDocumentStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresKnowledgeExternalIndexBindingStore;
import com.wish.rd.bootstrap.persistence.impl.PostgresVectorStore;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeDocumentMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeExternalIndexBindingMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeVectorMapper;
import com.wish.rd.bootstrap.rag.impl.ProjectScopedRequirementKnowledgeSearchAdapter;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.retrieval.navigator.KnowledgeEvidenceAllowlist;
import com.wish.rd.rag.retrieval.navigator.NavigatorTokenEstimator;
import com.wish.rd.rag.retrieval.navigator.ThreeTierNavigationEngine;
import com.wish.rd.rag.retrieval.navigator.model.EvidenceRejectionReason;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorCollectedEvidence;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorRunResult;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorSettings;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorStopReason;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * WP-7 Stage D 真机评测。默认跳过；加 {@code -Drd.openviking.smoke=true} 才跑。
 *
 * <p>不启动 Spring Boot：投影调度在 {@code rd.knowledge.projection.mode=ON} 时会写
 * outbox/binding，和「评测只读」冲突。本地路径用手装的
 * {@code ProjectScopedRequirementKnowledgeSearchAdapter} 双通道构造（与单元测试相同的
 * 两参构造，经验通道关闭），向量库是生产 {@code PostgresVectorStore} 的哈希词袋。
 * OpenViking 路径走 {@code ThreeTierNavigationEngine}，不过 Router——OPENVIKING 模式
 * 在 {@code NOTHING_ADMITTED} 时会降级本地，混进 LOCAL 命中会让切流数字说谎。
 */
@EnabledIfSystemProperty(named = "rd.openviking.smoke", matches = "true")
class OpenVikingShadowRetrievalEvaluationRealSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int DEFAULT_REPS = 5;
    private static final int WARMUP = 1;

    private static SqlSession sqlSession;
    private static ProjectScopedRequirementKnowledgeSearchAdapter localSearch;
    private static ThreeTierNavigationEngine navigationEngine;
    private static WaimaiGoldQuestionSet gold;

    @BeforeAll
    static void openReadOnlyHarness() {
        gold = WaimaiGoldQuestionSet.load();
        String apiKey = apiKey();
        SqlSessionFactory factory = ShadowRetrievalEvaluationPersistence.sqlSessionFactory(
                ShadowRetrievalEvaluationPersistence.dataSource());
        sqlSession = factory.openSession(true);
        ObjectMapper json = new ObjectMapper();
        PostgresVectorStore vectorStore = new PostgresVectorStore(
                sqlSession.getMapper(KnowledgeVectorMapper.class), json, 1536);
        PostgresKnowledgeDocumentStore documents = new PostgresKnowledgeDocumentStore(
                sqlSession.getMapper(KnowledgeDocumentMapper.class), json);
        PostgresKnowledgeExternalIndexBindingStore bindings =
                new PostgresKnowledgeExternalIndexBindingStore(
                        sqlSession.getMapper(KnowledgeExternalIndexBindingMapper.class));
        localSearch = new ProjectScopedRequirementKnowledgeSearchAdapter(
                vectorStore, mock(RdProjectService.class));
        String baseUrl = System.getProperty("rd.openviking.smoke.base-url", "http://127.0.0.1:1933");
        JdkOpenVikingHttpExchange exchange = new JdkOpenVikingHttpExchange(
                baseUrl, () -> apiKey, Duration.ofSeconds(3), Duration.ofSeconds(30));
        OpenVikingRestNavigatorAdapter navigator = new OpenVikingRestNavigatorAdapter(
                exchange, () -> apiKey, OpenVikingProjectionUris.OWNED_ROOT);
        assertTrue(navigator.ready(), "live OpenViking /ready must report embedding ok before evaluation");
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(
                new InMemoryRetrievalRunStore(), () -> "eval", System::currentTimeMillis);
        navigationEngine = new ThreeTierNavigationEngine(
                navigator,
                new KnowledgeEvidenceAllowlist(bindings, documents),
                NavigatorSettings.defaults(),
                lifecycle);
    }

    @AfterAll
    static void closeSession() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void shouldScoreLocalAndOpenVikingOnTheSameGoldQuestions() throws Exception {
        int reps = Math.max(1, Integer.parseInt(System.getProperty("rd.openviking.eval.reps",
                String.valueOf(DEFAULT_REPS))));
        RetrievalScope scope = new RetrievalScope(List.of(gold.corpusKnowledgeBaseId()), "", true, "");
        int k = gold.k();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("date", LocalDate.now().toString());
        report.put("embeddingProfile", "dashscope");
        report.put("embeddingModel", "text-embedding-v4");
        report.put("embeddingDimension", 1024);
        report.put("localVectorImplementation",
                "PostgresVectorStore hashed bag-of-words over pgvector, dimension 1536");
        report.put("knowledgeBaseId", gold.corpusKnowledgeBaseId());
        report.put("knowledgeBaseName", gold.corpusKnowledgeBaseName());
        report.put("documentCount", gold.corpusDocumentCount());
        report.put("k", k);
        report.put("warmupPassesExcluded", WARMUP);
        report.put("timedRepetitionsPerQuestion", reps);
        report.put("corpusChoice", gold.corpusChoice());

        List<Map<String, Object>> perQuestion = new ArrayList<>();
        EnumMap<NavigatorStopReason, Integer> stopReasons = emptyStops();
        EnumMap<EvidenceRejectionReason, Integer> rejections = emptyRejections();
        List<Long> localLatencies = new ArrayList<>();
        List<Long> ovLatencies = new ArrayList<>();
        List<Double> localRecall1 = new ArrayList<>();
        List<Double> localRecall5 = new ArrayList<>();
        List<Double> localRecallK = new ArrayList<>();
        List<Double> ovRecall1 = new ArrayList<>();
        List<Double> ovRecall5 = new ArrayList<>();
        List<Double> ovRecallK = new ArrayList<>();
        List<Double> localCitation = new ArrayList<>();
        List<Double> ovCitation = new ArrayList<>();
        int localFalsePositives = 0;
        int ovFalsePositives = 0;
        int unanswerable = 0;
        int localTokensSum = 0;
        int ovTokensSum = 0;
        int localTokenSamples = 0;
        int ovTokenSamples = 0;
        int crossKbAdmitted = 0;
        int rankedDisagreements = 0;

        for (WaimaiGoldQuestion question : gold.questions()) {
            System.out.println("evaluating " + question.id() + " " + question.type());
            Set<String> expected = Set.copyOf(question.expectedDocumentIds());
            if (expected.isEmpty()) {
                unanswerable++;
            }
            QuestionRun local = repeatLocal(question, scope, k, WARMUP, reps);
            QuestionRun ov = repeatOpenViking(question, k, WARMUP, reps);
            localLatencies.addAll(local.latenciesMillis);
            ovLatencies.addAll(ov.latenciesMillis);
            if (local.rankedDisagreed || ov.rankedDisagreed) {
                rankedDisagreements++;
            }
            localRecall1.add(ShadowRetrievalEvaluationMetrics.recallAtK(local.ranked, expected, 1));
            localRecall5.add(ShadowRetrievalEvaluationMetrics.recallAtK(local.ranked, expected, 5));
            localRecallK.add(ShadowRetrievalEvaluationMetrics.recallAtK(local.ranked, expected, k));
            ovRecall1.add(ShadowRetrievalEvaluationMetrics.recallAtK(ov.ranked, expected, 1));
            ovRecall5.add(ShadowRetrievalEvaluationMetrics.recallAtK(ov.ranked, expected, 5));
            ovRecallK.add(ShadowRetrievalEvaluationMetrics.recallAtK(ov.ranked, expected, k));
            localCitation.add(ShadowRetrievalEvaluationMetrics.citationValidity(local.ranked, expected));
            ovCitation.add(ShadowRetrievalEvaluationMetrics.citationValidity(ov.ranked, expected));
            if (ShadowRetrievalEvaluationMetrics.falsePositiveCitation(local.ranked, expected)) {
                localFalsePositives++;
            }
            if (ShadowRetrievalEvaluationMetrics.falsePositiveCitation(ov.ranked, expected)) {
                ovFalsePositives++;
            }
            localTokensSum += local.tokens;
            ovTokensSum += ov.tokens;
            localTokenSamples++;
            ovTokenSamples++;
            stopReasons.merge(ov.stopReason, 1, Integer::sum);
            ov.rejections.forEach((reason, count) -> rejections.merge(reason, count, Integer::sum));
            crossKbAdmitted += ov.crossKb;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", question.id());
            row.put("type", question.type());
            row.put("question", question.question());
            row.put("expectedDocumentIds", question.expectedDocumentIds());
            row.put("localRanked", local.ranked);
            row.put("openVikingRanked", ov.ranked);
            row.put("localRecallAt1", localRecall1.getLast());
            row.put("localRecallAt5", localRecall5.getLast());
            row.put("localRecallAtK", localRecallK.getLast());
            row.put("openVikingRecallAt1", ovRecall1.getLast());
            row.put("openVikingRecallAt5", ovRecall5.getLast());
            row.put("openVikingRecallAtK", ovRecallK.getLast());
            row.put("localCitationValidity", localCitation.getLast());
            row.put("openVikingCitationValidity", ovCitation.getLast());
            row.put("localTokens", local.tokens);
            row.put("openVikingTokens", ov.tokens);
            row.put("localLatencyMillis", local.latenciesMillis);
            row.put("openVikingLatencyMillis", ov.latenciesMillis);
            row.put("stopReason", ov.stopReason.name());
            row.put("rejections", toNamed(ov.rejections));
            row.put("remoteCalls", ov.remoteCalls);
            perQuestion.add(row);
        }

        Map<String, Object> localPath = pathSummary(
                "LOCAL",
                localRecall1, localRecall5, localRecallK, localCitation,
                localFalsePositives, unanswerable,
                localTokensSum, localTokenSamples,
                localLatencies);
        Map<String, Object> ovPath = pathSummary(
                "OPENVIKING",
                ovRecall1, ovRecall5, ovRecallK, ovCitation,
                ovFalsePositives, unanswerable,
                ovTokensSum, ovTokenSamples,
                ovLatencies);
        ovPath.put("stopReasons", toNamed(stopReasons));
        ovPath.put("allowlistRejections", toNamed(rejections));
        ovPath.put("crossKnowledgeBaseAdmitted", crossKbAdmitted);

        report.put("local", localPath);
        report.put("openViking", ovPath);
        report.put("rankedDisagreementsAcrossReps", rankedDisagreements);
        report.put("questions", perQuestion);

        Path output = Path.of(System.getProperty(
                "rd.openviking.eval.output",
                repoRoot().resolve("tmp/wp7-shadow-eval-results.json").toString()));
        Files.createDirectories(output.getParent());
        Files.writeString(output, MAPPER.writeValueAsString(report));
        System.out.println("WP-7 evaluation wrote " + output.toAbsolutePath());
        System.out.println(renderTable(localPath, ovPath, k, localLatencies.size(), ovLatencies.size()));

        assertEquals(20, perQuestion.size());
        assertEquals(0, crossKbAdmitted, "allowlist must keep cross-KB evidence at 0");
        assertEquals(gold.questions().size() * reps, localLatencies.size());
        assertEquals(gold.questions().size() * reps, ovLatencies.size());
        assertFalse(localLatencies.isEmpty());
        assertFalse(ovLatencies.isEmpty());
    }

    private static QuestionRun repeatLocal(
            WaimaiGoldQuestion question,
            RetrievalScope scope,
            int k,
            int warmup,
            int reps
    ) {
        List<Long> timed = new ArrayList<>();
        List<String> firstRanked = List.of();
        boolean disagreed = false;
        int tokens = 0;
        int total = warmup + reps;
        for (int i = 0; i < total; i++) {
            long started = System.nanoTime();
            SearchResult result = localSearch.search(null, null, question.question(), scope, k);
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            List<String> ranked = ShadowRetrievalEvaluationMetrics.uniqueInOrder(
                    result.candidates().stream()
                            .map(OpenVikingShadowRetrievalEvaluationRealSmokeTest::documentIdFromLocal)
                            .filter(id -> !id.isBlank())
                            .toList());
            int estimated = result.candidates().stream()
                    .mapToInt(candidate -> NavigatorTokenEstimator.estimate(candidate.summary()))
                    .sum();
            if (i < warmup) {
                continue;
            }
            timed.add(elapsed);
            if (firstRanked.isEmpty() && i == warmup) {
                firstRanked = ranked;
                tokens = estimated;
            } else if (!firstRanked.equals(ranked)) {
                disagreed = true;
            }
        }
        return new QuestionRun(firstRanked, timed, tokens, NavigatorStopReason.NEEDS_USER_INPUT,
                emptyRejections(), 0, 0, disagreed);
    }

    private static QuestionRun repeatOpenViking(WaimaiGoldQuestion question, int k, int warmup, int reps) {
        List<Long> timed = new ArrayList<>();
        List<String> firstRanked = List.of();
        boolean disagreed = false;
        int tokens = 0;
        int remoteCalls = 0;
        int crossKb = 0;
        NavigatorStopReason stop = NavigatorStopReason.NEEDS_USER_INPUT;
        EnumMap<EvidenceRejectionReason, Integer> rejection = emptyRejections();
        int total = warmup + reps;
        for (int i = 0; i < total; i++) {
            long started = System.nanoTime();
            NavigatorRunResult result = navigationEngine.navigate(
                    question.question(), List.of(gold.corpusKnowledgeBaseId()), "");
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            List<String> ranked = ShadowRetrievalEvaluationMetrics.uniqueInOrder(
                    result.evidence().stream()
                            .limit(k)
                            .map(item -> item.admitted().document().id())
                            .toList());
            int foreign = 0;
            for (NavigatorCollectedEvidence item : result.evidence()) {
                if (!gold.corpusKnowledgeBaseId().equals(item.admitted().document().knowledgeBaseId())) {
                    foreign++;
                }
            }
            if (i < warmup) {
                continue;
            }
            timed.add(elapsed);
            if (firstRanked.isEmpty() && i == warmup) {
                firstRanked = ranked;
                tokens = result.tokens();
                stop = result.stopReason();
                rejection = copyRejections(result.rejectionCounts());
                remoteCalls = result.remoteCalls();
                crossKb = foreign;
            } else if (!firstRanked.equals(ranked)) {
                disagreed = true;
            }
        }
        return new QuestionRun(firstRanked, timed, tokens, stop, rejection, remoteCalls, crossKb, disagreed);
    }

    private static String documentIdFromLocal(RoleContextEvidence evidence) {
        String uri = evidence.sourceUri();
        int scheme = uri.indexOf("://");
        String rest = scheme < 0 ? uri : uri.substring(scheme + 3);
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        int slash = rest.lastIndexOf('/');
        return slash < 0 ? rest : rest.substring(slash + 1);
    }

    private static String apiKey() {
        String fromProperty = System.getProperty("rd.openviking.smoke.api-key", "");
        if (!fromProperty.isBlank()) {
            return fromProperty.strip();
        }
        String envName = System.getProperty("rd.openviking.smoke.api-key-env", "OPENVIKING_API_KEY");
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        Path file = Path.of(System.getProperty(
                "rd.openviking.smoke.api-key-file",
                repoRoot().resolve("tmp/openviking-admin.key").toString()));
        try {
            String key = Files.readString(file).strip();
            if (key.isBlank()) {
                throw new IllegalStateException("OpenViking API key file is blank: " + file);
            }
            return key;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "missing OpenViking API key; set OPENVIKING_API_KEY or " + file, exception);
        }
    }

    /**
     * Surefire 的 user.dir 是模块目录。密钥和结果都相对仓库根，避免写进 bootstrap/tmp。
     */
    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("RULE.md"))) {
                return dir;
            }
        }
        return current;
    }

    private static Map<String, Object> pathSummary(
            String path,
            List<Double> recall1,
            List<Double> recall5,
            List<Double> recallK,
            List<Double> citation,
            int falsePositives,
            int unanswerable,
            int tokenSum,
            int tokenSamples,
            List<Long> latencies
    ) {
        List<Long> sorted = ShadowRetrievalEvaluationMetrics.sortedCopy(latencies);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("path", path);
        summary.put("recallAt1", ShadowRetrievalEvaluationMetrics.meanOfFinite(recall1));
        summary.put("recallAt5", ShadowRetrievalEvaluationMetrics.meanOfFinite(recall5));
        summary.put("recallAtK", ShadowRetrievalEvaluationMetrics.meanOfFinite(recallK));
        summary.put("citationValidity", ShadowRetrievalEvaluationMetrics.meanOfFinite(citation));
        summary.put("falsePositiveRate", unanswerable == 0 ? 0.0d : falsePositives / (double) unanswerable);
        summary.put("unanswerableQuestions", unanswerable);
        summary.put("falsePositiveQuestions", falsePositives);
        summary.put("meanContextTokens", tokenSamples == 0 ? 0.0d : tokenSum / (double) tokenSamples);
        summary.put("latencySamples", latencies.size());
        summary.put("latencyP50Millis", ShadowRetrievalEvaluationMetrics.percentile(sorted, 0.50d));
        summary.put("latencyP95Millis", ShadowRetrievalEvaluationMetrics.percentile(sorted, 0.95d));
        return summary;
    }

    private static String renderTable(
            Map<String, Object> local,
            Map<String, Object> ov,
            int k,
            int localSamples,
            int ovSamples
    ) {
        StringBuilder text = new StringBuilder();
        text.append('\n');
        text.append("| metric | LOCAL | OPENVIKING |\n");
        text.append("| --- | ---: | ---: |\n");
        row(text, "Recall@1", local.get("recallAt1"), ov.get("recallAt1"));
        row(text, "Recall@5", local.get("recallAt5"), ov.get("recallAt5"));
        row(text, "Recall@" + k, local.get("recallAtK"), ov.get("recallAtK"));
        row(text, "citation validity", local.get("citationValidity"), ov.get("citationValidity"));
        row(text, "unanswerable FP rate", local.get("falsePositiveRate"), ov.get("falsePositiveRate"));
        row(text, "mean context tokens", local.get("meanContextTokens"), ov.get("meanContextTokens"));
        row(text, "latency p50 ms (n=" + localSamples + "/" + ovSamples + ")",
                local.get("latencyP50Millis"), ov.get("latencyP50Millis"));
        row(text, "latency p95 ms", local.get("latencyP95Millis"), ov.get("latencyP95Millis"));
        text.append("stopReasons=").append(ov.get("stopReasons")).append('\n');
        text.append("allowlistRejections=").append(ov.get("allowlistRejections")).append('\n');
        return text.toString();
    }

    private static void row(StringBuilder text, String name, Object local, Object ov) {
        text.append("| ").append(name).append(" | ").append(fmt(local))
                .append(" | ").append(fmt(ov)).append(" |\n");
    }

    private static String fmt(Object value) {
        if (value instanceof Double number) {
            if (number.isNaN()) {
                return "NaN";
            }
            return String.format(java.util.Locale.ROOT, "%.4f", number);
        }
        return String.valueOf(value);
    }

    private static EnumMap<NavigatorStopReason, Integer> emptyStops() {
        EnumMap<NavigatorStopReason, Integer> counts = new EnumMap<>(NavigatorStopReason.class);
        for (NavigatorStopReason reason : NavigatorStopReason.values()) {
            counts.put(reason, 0);
        }
        return counts;
    }

    private static EnumMap<EvidenceRejectionReason, Integer> emptyRejections() {
        EnumMap<EvidenceRejectionReason, Integer> counts = new EnumMap<>(EvidenceRejectionReason.class);
        for (EvidenceRejectionReason reason : EvidenceRejectionReason.values()) {
            counts.put(reason, 0);
        }
        return counts;
    }

    private static EnumMap<EvidenceRejectionReason, Integer> copyRejections(
            Map<EvidenceRejectionReason, Integer> source
    ) {
        EnumMap<EvidenceRejectionReason, Integer> copy = emptyRejections();
        if (source != null) {
            source.forEach((reason, count) -> copy.put(reason, count == null ? 0 : count));
        }
        return copy;
    }

    private static <E extends Enum<E>> Map<String, Integer> toNamed(EnumMap<E, Integer> counts) {
        Map<String, Integer> named = new LinkedHashMap<>();
        counts.forEach((key, value) -> named.put(key.name(), value));
        return named;
    }

    private record QuestionRun(
            List<String> ranked,
            List<Long> latenciesMillis,
            int tokens,
            NavigatorStopReason stopReason,
            EnumMap<EvidenceRejectionReason, Integer> rejections,
            int remoteCalls,
            int crossKb,
            boolean rankedDisagreed
    ) {
    }
}
