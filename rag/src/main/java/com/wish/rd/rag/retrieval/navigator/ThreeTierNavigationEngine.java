package com.wish.rd.rag.retrieval.navigator;

import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.retrieval.navigator.model.AdmittedKnowledgeEvidence;
import com.wish.rd.rag.retrieval.navigator.model.EvidenceRejectionReason;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorContent;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorDocument;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorQuery;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorSearch;
import com.wish.rd.rag.retrieval.navigator.model.KnowledgeEvidenceAllowlistResult;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorCollectedEvidence;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorRoundRecord;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorRunResult;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorSettings;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorStopReason;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.text.TextAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.LongSupplier;

/**
 * 只读三层导航：L0 检索 → allowlist → 可选 L1/L2 → 证据门 → 必要时改写再进入下一轮。
 * 预算在发远端请求之前检查，避免次数上限出现 off-by-one。墙钟走注入时钟，便于单测时间旅行。
 */
@Component
public final class ThreeTierNavigationEngine {

    static final String ARTIFACT_ROUND = "NAVIGATOR_ROUND";
    static final String ARTIFACT_ALLOWLIST = "NAVIGATOR_ALLOWLIST";
    static final String ARTIFACT_STOP = "NAVIGATOR_STOP";

    private static final int INNER_TEXT_LIMIT = 1_800;

    /**
     * 默认证据门的原子术语覆盖率阈值。
     *
     * <p>0.4 不是调参调出来的，是分词方式决定的上限：中文按相邻 2-gram 全切，
     * 「商品保存时库存怎么校验」会切出 商品/品保/保存/存时/时库/库存/存怎/怎么/么校/校验，
     * 其中跨词边界的 品保、存时、时库、存怎、么校 在任何正文里都不会出现——哪怕正文正是
     * 「管理员可以保存商品并校验库存」这种标准答案，也只能覆盖 4/10。所以真正答上的文档
     * 覆盖率天花板就在 0.5 附近，阈值定到 0.6 等于永不触发。另外提问还会带「怎么」「有哪些」
     * 这类不落在正文里的疑问词，进一步压低分子。
     *
     * <p>反例侧的间隔是够的：换成无关正文「骑手接单后按调度顺序取货」时覆盖率为 0。
     */
    private static final double GATE_COVERAGE_RATIO = 0.4d;

    /** 改写时保留的未覆盖术语个数，太多会把查询稀释回原样。 */
    private static final int REFINE_TERM_COUNT = 6;

    /** ASCII 原子术语：标识符、路径、字段名等，整体参与覆盖率统计。 */
    private static final Pattern ASCII_ATOMIC_TERM = Pattern.compile("[a-z0-9_./-]+");

    private final ExternalKnowledgeNavigatorPort navigator;
    private final KnowledgeEvidenceAllowlist allowlist;
    private final NavigatorSettings settings;
    private final RetrievalRunLifecycle lifecycle;
    private final LongSupplier clock;
    private final NavigatorEvidenceGate evidenceGate;
    private final NavigatorQueryRefiner queryRefiner;

    /**
     * 生产构造：系统时钟、默认证据门与改写器。
     *
     * @param navigator 只读导航端口
     * @param allowlist 本地证据准入
     * @param settings  预算
     * @param lifecycle 检索 artifact 落点，run 不存在时跳过写入
     */
    @Autowired
    public ThreeTierNavigationEngine(
            ExternalKnowledgeNavigatorPort navigator,
            KnowledgeEvidenceAllowlist allowlist,
            NavigatorSettings settings,
            RetrievalRunLifecycle lifecycle
    ) {
        this(
                navigator,
                allowlist,
                settings,
                lifecycle,
                System::currentTimeMillis,
                ThreeTierNavigationEngine::defaultGate,
                ThreeTierNavigationEngine::defaultRefine
        );
    }

    /**
     * 可注入时钟与门的构造，供单测钉住预算与停止原因。
     *
     * @param navigator    只读导航端口
     * @param allowlist    本地证据准入
     * @param settings     预算
     * @param lifecycle    artifact 落点
     * @param clock        墙钟，单位毫秒
     * @param evidenceGate 证据门
     * @param queryRefiner 查询改写
     */
    public ThreeTierNavigationEngine(
            ExternalKnowledgeNavigatorPort navigator,
            KnowledgeEvidenceAllowlist allowlist,
            NavigatorSettings settings,
            RetrievalRunLifecycle lifecycle,
            LongSupplier clock,
            NavigatorEvidenceGate evidenceGate,
            NavigatorQueryRefiner queryRefiner
    ) {
        this.navigator = Objects.requireNonNull(navigator, "navigator must not be null");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.evidenceGate = Objects.requireNonNull(evidenceGate, "evidenceGate must not be null");
        this.queryRefiner = Objects.requireNonNull(queryRefiner, "queryRefiner must not be null");
    }

    /**
     * 完整 L0→L1→L2 循环。失败与预算耗尽都返回已有部分证据，不抛异常。
     *
     * @param query            查询
     * @param knowledgeBaseIds 允许的知识库
     * @param runId            已有 retrieval run；空白则不写 artifact
     * @return 带唯一 stopReason 的结果
     */
    public NavigatorRunResult navigate(String query, List<String> knowledgeBaseIds, String runId) {
        String safeQuery = query == null ? "" : query.strip();
        List<String> scope = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        String safeRunId = runId == null ? "" : runId.strip();
        Session session = new Session(safeRunId, scope);
        if (safeQuery.isBlank()) {
            return session.finish(NavigatorStopReason.NEEDS_USER_INPUT, safeQuery, 0, 0, 0, 0, emptyTally());
        }
        if (!navigator.ready()) {
            return session.finish(NavigatorStopReason.REMOTE_UNAVAILABLE, safeQuery, 0, 0, 0, 0, emptyTally());
        }
        String currentQuery = safeQuery;
        for (int round = 1; round <= settings.maxRounds(); round++) {
            Optional<NavigatorStopReason> blocked = session.beforeRemoteCall();
            if (blocked.isPresent()) {
                return session.finish(blocked.get(), currentQuery, 0, 0, 0, 0, emptyTally());
            }
            ExternalNavigatorSearch search;
            try {
                search = navigator.searchAbstracts(ExternalNavigatorQuery.of(
                        currentQuery, scope, settings.l0Candidates()));
            } catch (RuntimeException ex) {
                return session.finish(NavigatorStopReason.REMOTE_UNAVAILABLE, currentQuery, 0, 0, 0, 0, emptyTally());
            }
            session.remoteCalls++;
            int l0Calls = 1;
            if (search.failureClass() != ExternalIndexFailureClass.NONE) {
                return session.finish(
                        NavigatorStopReason.REMOTE_UNAVAILABLE, currentQuery, l0Calls, 0, 0, 0, emptyTally());
            }
            List<ExternalNavigatorSearch.Hit> hits = search.hits().stream()
                    .limit(settings.l0Candidates())
                    .toList();
            if (round > 1 && !hits.isEmpty() && hits.stream().allMatch(hit -> session.seenUris.contains(hit.uri()))) {
                return session.finish(
                        NavigatorStopReason.DUPLICATE_CANDIDATES, currentQuery, l0Calls, 0, 0, 0, emptyTally());
            }
            KnowledgeEvidenceAllowlistResult admitted = allowlist.admit(hits, Set.copyOf(scope));
            session.mergeRejections(admitted.rejectionCounts());
            int newDocuments = 0;
            for (AdmittedKnowledgeEvidence item : admitted.admitted()) {
                session.seenUris.add(item.hit().uri());
                if (session.remember(item, item.hit().abstractText(), "L0", item.hit().uri())) {
                    newDocuments++;
                }
            }
            if (admitted.admitted().isEmpty() && session.evidence.isEmpty()) {
                if (!hits.isEmpty()) {
                    return session.finish(
                            NavigatorStopReason.NOTHING_ADMITTED,
                            currentQuery, l0Calls, 0, 0, 0, admitted.rejectionCounts());
                }
                if (round >= settings.maxRounds()) {
                    return session.finish(
                            NavigatorStopReason.NOTHING_ADMITTED,
                            currentQuery, l0Calls, 0, 0, 0, admitted.rejectionCounts());
                }
                NavigatorRoundRecord emptyRound = session.recordRound(
                        round, currentQuery, l0Calls, 0, 0, 0, admitted.rejectionCounts(), Optional.empty());
                String nextQuery = queryRefiner.refine(currentQuery, emptyRound, session.evidenceList());
                if (nextQuery == null || nextQuery.isBlank()) {
                    return session.finish(
                            NavigatorStopReason.NEEDS_USER_INPUT,
                            currentQuery, l0Calls, 0, 0, 0, admitted.rejectionCounts());
                }
                currentQuery = nextQuery.strip();
                continue;
            }
            int l1Calls = expandL1(session, admitted.admitted());
            Optional<NavigatorStopReason> afterL1Budget = session.pendingStop;
            if (afterL1Budget.isPresent()) {
                return session.finish(
                        afterL1Budget.get(), currentQuery, l0Calls, l1Calls, 0,
                        admitted.admitted().size(), admitted.rejectionCounts());
            }
            if (evidenceGate.isSufficient(currentQuery, session.evidenceList())) {
                return session.finish(
                        NavigatorStopReason.EVIDENCE_SUFFICIENT, currentQuery, l0Calls, l1Calls, 0,
                        admitted.admitted().size(), admitted.rejectionCounts());
            }
            int l2Calls = expandL2(session, admitted.admitted());
            Optional<NavigatorStopReason> afterL2Budget = session.pendingStop;
            if (afterL2Budget.isPresent()) {
                return session.finish(
                        afterL2Budget.get(), currentQuery, l0Calls, l1Calls, l2Calls,
                        admitted.admitted().size(), admitted.rejectionCounts());
            }
            if (evidenceGate.isSufficient(currentQuery, session.evidenceList())) {
                return session.finish(
                        NavigatorStopReason.EVIDENCE_SUFFICIENT, currentQuery, l0Calls, l1Calls, l2Calls,
                        admitted.admitted().size(), admitted.rejectionCounts());
            }
            if (newDocuments == 0 && !session.evidence.isEmpty()) {
                return session.finish(
                        NavigatorStopReason.LOW_INFORMATION_GAIN, currentQuery, l0Calls, l1Calls, l2Calls,
                        admitted.admitted().size(), admitted.rejectionCounts());
            }
            if (round >= settings.maxRounds()) {
                return session.finish(
                        NavigatorStopReason.ROUND_BUDGET_EXHAUSTED, currentQuery, l0Calls, l1Calls, l2Calls,
                        admitted.admitted().size(), admitted.rejectionCounts());
            }
            NavigatorRoundRecord continued = session.recordRound(
                    round, currentQuery, l0Calls, l1Calls, l2Calls,
                    admitted.admitted().size(), admitted.rejectionCounts(), Optional.empty());
            String nextQuery = queryRefiner.refine(currentQuery, continued, session.evidenceList());
            if (nextQuery == null || nextQuery.isBlank()) {
                return session.finish(
                        NavigatorStopReason.NEEDS_USER_INPUT, currentQuery, l0Calls, l1Calls, l2Calls,
                        admitted.admitted().size(), admitted.rejectionCounts());
            }
            currentQuery = nextQuery.strip();
        }
        // 轮次 for 的上界就是 maxRounds，正常路径会在轮内 return；这里只是让编译器与评测都能看到「必有原因」。
        return session.finish(NavigatorStopReason.ROUND_BUDGET_EXHAUSTED, currentQuery, 0, 0, 0, 0, emptyTally());
    }

    /**
     * SHADOW 旁路：只打 L0 并过 allowlist，不展开 L1/L2，也不得阻塞调用方。
     *
     * @param query            查询
     * @param knowledgeBaseIds 知识库范围
     * @param limit            希望的 L0 条数，再与设置取较小值
     * @param runId            已有 retrieval run
     * @return 探测结果
     */
    public NavigatorRunResult probeL0(String query, List<String> knowledgeBaseIds, int limit, String runId) {
        String safeQuery = query == null ? "" : query.strip();
        List<String> scope = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        String safeRunId = runId == null ? "" : runId.strip();
        Session session = new Session(safeRunId, scope);
        if (safeQuery.isBlank()) {
            return session.finish(NavigatorStopReason.NEEDS_USER_INPUT, safeQuery, 0, 0, 0, 0, emptyTally());
        }
        if (!navigator.ready()) {
            return session.finish(NavigatorStopReason.REMOTE_UNAVAILABLE, safeQuery, 0, 0, 0, 0, emptyTally());
        }
        Optional<NavigatorStopReason> blocked = session.beforeRemoteCall();
        if (blocked.isPresent()) {
            return session.finish(blocked.get(), safeQuery, 0, 0, 0, 0, emptyTally());
        }
        int cappedLimit = Math.max(1, Math.min(Math.max(1, limit), settings.l0Candidates()));
        ExternalNavigatorSearch search;
        try {
            search = navigator.searchAbstracts(ExternalNavigatorQuery.of(safeQuery, scope, cappedLimit));
        } catch (RuntimeException ex) {
            return session.finish(NavigatorStopReason.REMOTE_UNAVAILABLE, safeQuery, 0, 0, 0, 0, emptyTally());
        }
        session.remoteCalls++;
        if (search.failureClass() != ExternalIndexFailureClass.NONE) {
            return session.finish(NavigatorStopReason.REMOTE_UNAVAILABLE, safeQuery, 1, 0, 0, 0, emptyTally());
        }
        List<ExternalNavigatorSearch.Hit> hits = search.hits().stream().limit(cappedLimit).toList();
        KnowledgeEvidenceAllowlistResult admitted = allowlist.admit(hits, Set.copyOf(scope));
        session.mergeRejections(admitted.rejectionCounts());
        for (AdmittedKnowledgeEvidence item : admitted.admitted()) {
            session.seenUris.add(item.hit().uri());
            session.remember(item, item.hit().abstractText(), "L0", item.hit().uri());
        }
        NavigatorStopReason stop = admitted.admitted().isEmpty()
                ? NavigatorStopReason.NOTHING_ADMITTED
                : NavigatorStopReason.EVIDENCE_SUFFICIENT;
        return session.finish(stop, safeQuery, 1, 0, 0, admitted.admitted().size(), admitted.rejectionCounts());
    }

    private int expandL1(Session session, List<AdmittedKnowledgeEvidence> admitted) {
        int issued = 0;
        List<AdmittedKnowledgeEvidence> selected = admitted.stream()
                .sorted(Comparator.comparingDouble((AdmittedKnowledgeEvidence item) -> item.hit().score()).reversed())
                .limit(settings.l1Expansions())
                .toList();
        for (AdmittedKnowledgeEvidence item : selected) {
            Optional<NavigatorStopReason> blocked = session.beforeRemoteCall();
            if (blocked.isPresent()) {
                session.pendingStop = blocked;
                return issued;
            }
            String uri = item.binding().remoteUri();
            ExternalNavigatorDocument overview;
            try {
                overview = navigator.readOverview(uri);
            } catch (RuntimeException ex) {
                session.pendingStop = Optional.of(NavigatorStopReason.REMOTE_UNAVAILABLE);
                return issued;
            }
            session.remoteCalls++;
            issued++;
            if (overview.failureClass() != ExternalIndexFailureClass.NONE) {
                continue;
            }
            if (!overview.exists() || overview.overview().isBlank()) {
                continue;
            }
            session.remember(item, overview.overview(), "L1", uri);
        }
        return issued;
    }

    private int expandL2(Session session, List<AdmittedKnowledgeEvidence> admitted) {
        int issued = 0;
        List<AdmittedKnowledgeEvidence> selected = admitted.stream()
                .sorted(Comparator.comparingDouble((AdmittedKnowledgeEvidence item) -> item.hit().score()).reversed())
                .limit(settings.l2Expansions())
                .toList();
        for (AdmittedKnowledgeEvidence item : selected) {
            Optional<NavigatorStopReason> blocked = session.beforeRemoteCall();
            if (blocked.isPresent()) {
                session.pendingStop = blocked;
                return issued;
            }
            String uri = l2Uri(item.binding());
            ExternalNavigatorContent content;
            try {
                content = navigator.readContent(uri, 0, 0);
            } catch (RuntimeException ex) {
                session.pendingStop = Optional.of(NavigatorStopReason.REMOTE_UNAVAILABLE);
                return issued;
            }
            session.remoteCalls++;
            issued++;
            if (content.failureClass() != ExternalIndexFailureClass.NONE) {
                continue;
            }
            if (!content.exists() || content.content().isBlank()) {
                continue;
            }
            session.remember(item, content.content(), "L2", uri);
        }
        return issued;
    }

    private static String l2Uri(KnowledgeExternalIndexBinding binding) {
        try {
            return OpenVikingProjectionUris.documentSourceUri(binding.knowledgeBaseId(), binding.documentId());
        } catch (RuntimeException ex) {
            return binding.remoteUri();
        }
    }

    /**
     * 按原子术语覆盖率判定证据是否够，覆盖率 = 已被 L1/L2 正文覆盖的查询原子术语 / 全部。
     *
     * <p>不能用「整条查询作为子串出现」：真实提问几乎不会逐字出现在正文里，那种写法让
     * {@link NavigatorStopReason#EVIDENCE_SUFFICIENT} 永远不触发，循环只能烧到预算上限
     * （真机 20 题实测 0 次触发，10 次 DUPLICATE_CANDIDATES、10 次远端调用预算耗尽）。
     *
     * <p>只统计原子术语——ASCII 术语与中文 2-gram，见 {@link #atomicTerms}。
     * {@link TextAnalyzer#terms} 会同时产出 2~5 元 gram，长 gram 只有在正文出现同样的
     * 连续长片段时才命中，直接拿全量术语算覆盖率等于把「逐字出现」换个写法又请回来：
     * 上面那对同义句 35 个术语里只有 4 个能对上。
     *
     * <p>术语切分复用 {@link TextAnalyzer}——本地检索用的就是它，两侧共用同一套词汇表，
     * 也避免仓库里出现第二套分词。覆盖率按多条证据取并集：跨文档问题本来就要拼几篇才够。
     *
     * <p>这是没有裁判模型时的粗粒度词面启发式，{@code EVIDENCE_SUFFICIENT} 触发不等于
     * 「语义上答上了」。真正的判定要靠注入 {@link NavigatorEvidenceGate}，
     * 不是调这里的阈值。
     */
    static boolean defaultGate(String query, List<NavigatorCollectedEvidence> evidence) {
        if (query == null || query.isBlank() || evidence == null || evidence.isEmpty()) {
            return false;
        }
        Set<String> queryTerms = atomicTerms(query);
        if (queryTerms.isEmpty()) {
            return false;
        }
        Set<String> covered = coveredQueryTerms(queryTerms, evidence);
        return (double) covered.size() / queryTerms.size() >= GATE_COVERAGE_RATIO;
    }

    /**
     * 把查询改写到「还没被覆盖」的那部分上。
     *
     * <p>原先是给查询尾部追加 {@code 补充N}：对嵌入向量几乎没有扰动，第二轮会召回同一批
     * 命中，于是要么判成 {@link NavigatorStopReason#DUPLICATE_CANDIDATES}，要么白烧远端
     * 调用预算——真机 20 题里这两种结局各占一半。
     *
     * <p>改成保留未覆盖术语中最长的若干个：长术语在 {@link TextAnalyzer} 的权重体系里
     * 信号最强，且换掉的是查询主体而不是加一个无意义尾巴，向量才真的移动。全部已覆盖时
     * gate 会先触发，轮不到这里；一个都没覆盖时保留原查询，避免改出空串。
     */
    static String defaultRefine(
            String currentQuery,
            NavigatorRoundRecord round,
            List<NavigatorCollectedEvidence> collected
    ) {
        String query = currentQuery == null ? "" : currentQuery.strip();
        if (query.isBlank()) {
            return "";
        }
        Set<String> queryTerms = atomicTerms(query);
        Set<String> covered = coveredQueryTerms(queryTerms, collected);
        List<String> uncovered = queryTerms.stream()
                .filter(term -> !covered.contains(term))
                .sorted(Comparator.comparingInt(String::length).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(REFINE_TERM_COUNT)
                .toList();
        if (uncovered.isEmpty()) {
            return query;
        }
        return String.join(" ", uncovered);
    }

    /**
     * 只保留原子术语：ASCII 术语，以及长度为 2 的中文 gram。
     *
     * <p>{@link TextAnalyzer#terms} 的 3~5 元 gram 与整串保留项都要求正文出现同样的连续
     * 长片段，留着算覆盖率会让同义改写恒判不足。
     */
    private static Set<String> atomicTerms(String text) {
        Set<String> all = TextAnalyzer.terms(text);
        Set<String> atomic = new LinkedHashSet<>();
        for (String term : all) {
            if (term.length() == 2 || ASCII_ATOMIC_TERM.matcher(term).matches()) {
                atomic.add(term);
            }
        }
        return atomic;
    }

    /** 取查询术语中已出现在 L1/L2 正文里的部分；L0 只有标题摘要，不足以算覆盖。 */
    private static Set<String> coveredQueryTerms(
            Set<String> queryTerms,
            List<NavigatorCollectedEvidence> evidence
    ) {
        if (queryTerms.isEmpty() || evidence == null || evidence.isEmpty()) {
            return Set.of();
        }
        Set<String> covered = new LinkedHashSet<>();
        for (NavigatorCollectedEvidence item : evidence) {
            if (item == null || (!"L1".equals(item.tier()) && !"L2".equals(item.tier()))) {
                continue;
            }
            String inner = UntrustedKnowledgeFence.innerEscaped(item.fencedText());
            if (inner.isBlank()) {
                continue;
            }
            Set<String> contentTerms = TextAnalyzer.terms(inner);
            for (String term : queryTerms) {
                if (contentTerms.contains(term)) {
                    covered.add(term);
                }
            }
        }
        return covered;
    }

    private static EnumMap<EvidenceRejectionReason, Integer> emptyTally() {
        EnumMap<EvidenceRejectionReason, Integer> counts = new EnumMap<>(EvidenceRejectionReason.class);
        for (EvidenceRejectionReason reason : EvidenceRejectionReason.values()) {
            counts.put(reason, 0);
        }
        return counts;
    }

    private final class Session {
        private final String runId;
        private final List<String> scope;
        private final long startedAt;
        private final LinkedHashMap<String, NavigatorCollectedEvidence> evidence = new LinkedHashMap<>();
        private final LinkedHashSet<String> seenUris = new LinkedHashSet<>();
        private final EnumMap<EvidenceRejectionReason, Integer> rejections = emptyTally();
        private final List<NavigatorRoundRecord> rounds = new ArrayList<>();
        private int remoteCalls;
        private int tokens;
        private Optional<NavigatorStopReason> pendingStop = Optional.empty();

        private Session(String runId, List<String> scope) {
            this.runId = runId;
            this.scope = scope;
            this.startedAt = clock.getAsLong();
        }

        private Optional<NavigatorStopReason> beforeRemoteCall() {
            if (clock.getAsLong() - startedAt >= settings.timeBudget().toMillis()) {
                return Optional.of(NavigatorStopReason.TIME_BUDGET_EXHAUSTED);
            }
            if (remoteCalls >= settings.maxRemoteCalls()) {
                return Optional.of(NavigatorStopReason.REMOTE_CALL_BUDGET_EXHAUSTED);
            }
            if (tokens >= settings.tokenBudget()) {
                return Optional.of(NavigatorStopReason.TOKEN_BUDGET_EXHAUSTED);
            }
            return Optional.empty();
        }

        private boolean remember(
                AdmittedKnowledgeEvidence item,
                String rawText,
                String tier,
                String resourceUri
        ) {
            String documentId = item.document().id();
            boolean isNew = !evidence.containsKey(documentId);
            String bounded = rawText == null ? "" : rawText;
            if (bounded.length() > INNER_TEXT_LIMIT) {
                bounded = bounded.substring(0, INNER_TEXT_LIMIT);
            }
            String fenced = UntrustedKnowledgeFence.wrap(bounded);
            NavigatorCollectedEvidence previous = evidence.get(documentId);
            if (previous != null && tierRank(previous.tier()) > tierRank(tier)) {
                return false;
            }
            if (previous == null || !previous.fencedText().equals(fenced) || !previous.tier().equals(tier)) {
                int previousTokens = previous == null ? 0 : NavigatorTokenEstimator.estimate(previous.fencedText());
                tokens = Math.max(0, tokens - previousTokens) + NavigatorTokenEstimator.estimate(fenced);
            }
            evidence.put(documentId, new NavigatorCollectedEvidence(item, fenced, tier, resourceUri));
            return isNew;
        }

        private List<NavigatorCollectedEvidence> evidenceList() {
            return List.copyOf(evidence.values());
        }

        private void mergeRejections(Map<EvidenceRejectionReason, Integer> roundCounts) {
            if (roundCounts == null) {
                return;
            }
            for (EvidenceRejectionReason reason : EvidenceRejectionReason.values()) {
                rejections.merge(reason, roundCounts.getOrDefault(reason, 0), Integer::sum);
            }
        }

        private NavigatorRoundRecord recordRound(
                int roundIndex,
                String query,
                int l0Calls,
                int l1Calls,
                int l2Calls,
                int admittedCount,
                Map<EvidenceRejectionReason, Integer> roundRejections,
                Optional<NavigatorStopReason> terminalStop
        ) {
            NavigatorRoundRecord record = new NavigatorRoundRecord(
                    roundIndex,
                    query,
                    scope,
                    l0Calls,
                    l1Calls,
                    l2Calls,
                    admittedCount,
                    roundRejections,
                    terminalStop,
                    Math.max(0L, clock.getAsLong() - startedAt),
                    tokens
            );
            rounds.add(record);
            persistRound(record);
            return record;
        }

        private NavigatorRunResult finish(
                NavigatorStopReason stopReason,
                String query,
                int l0Calls,
                int l1Calls,
                int l2Calls,
                int admittedCount,
                Map<EvidenceRejectionReason, Integer> roundRejections
        ) {
            recordRound(
                    Math.max(1, rounds.size() + 1),
                    query,
                    l0Calls,
                    l1Calls,
                    l2Calls,
                    admittedCount,
                    roundRejections,
                    Optional.of(stopReason)
            );
            persistAllowlistAndStop(stopReason);
            return new NavigatorRunResult(
                    stopReason,
                    evidenceList(),
                    List.copyOf(rounds),
                    rejections,
                    remoteCalls,
                    tokens,
                    Math.max(0L, clock.getAsLong() - startedAt)
            );
        }

        private void persistRound(NavigatorRoundRecord record) {
            if (runId.isBlank()) {
                return;
            }
            String preview = "round=" + record.roundIndex()
                    + "; query=" + bounded(record.query(), 240)
                    + "; scope=" + String.join(",", record.knowledgeBaseIds())
                    + "; l0Calls=" + record.l0Calls()
                    + "; l1Calls=" + record.l1Calls()
                    + "; l2Calls=" + record.l2Calls()
                    + "; admitted=" + record.admittedCount()
                    + "; rejected=" + tallyPreview(record.rejectionCounts())
                    + "; stopReason=" + record.terminalStop().map(Enum::name).orElse("")
                    + "; elapsedMs=" + record.elapsedMillis()
                    + "; tokens=" + record.tokens();
            lifecycle.appendArtifact(
                    runId,
                    ARTIFACT_ROUND,
                    "rag://navigator/round/" + record.roundIndex(),
                    bounded(preview, 2_000),
                    sha256(preview)
            );
        }

        private void persistAllowlistAndStop(NavigatorStopReason stopReason) {
            if (runId.isBlank()) {
                return;
            }
            String allowlistPreview = "rejectedTotal=" + rejections.values().stream().mapToInt(Integer::intValue).sum()
                    + "; " + tallyPreview(rejections);
            lifecycle.appendArtifact(
                    runId,
                    ARTIFACT_ALLOWLIST,
                    "rag://navigator/allowlist",
                    bounded(allowlistPreview, 2_000),
                    sha256(allowlistPreview)
            );
            String stopPreview = "stopReason=" + stopReason.name()
                    + "; rounds=" + rounds.size()
                    + "; remoteCalls=" + remoteCalls
                    + "; tokens=" + tokens
                    + "; elapsedMs=" + Math.max(0L, clock.getAsLong() - startedAt)
                    + "; admitted=" + evidence.size()
                    + "; rejected=" + tallyPreview(rejections);
            lifecycle.appendArtifact(
                    runId,
                    ARTIFACT_STOP,
                    "rag://navigator/stop",
                    bounded(stopPreview, 2_000),
                    sha256(stopPreview)
            );
        }
    }

    private static int tierRank(String tier) {
        return switch (tier == null ? "" : tier) {
            case "L2" -> 2;
            case "L1" -> 1;
            default -> 0;
        };
    }

    private static String tallyPreview(Map<EvidenceRejectionReason, Integer> counts) {
        StringBuilder builder = new StringBuilder();
        for (EvidenceRejectionReason reason : EvidenceRejectionReason.values()) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(reason.name()).append('=').append(counts.getOrDefault(reason, 0));
        }
        return builder.toString();
    }

    private static String bounded(String value, int maxChars) {
        String text = value == null ? "" : value;
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
