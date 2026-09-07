import type {
  CodingMeaResponse,
  CommandReference,
  DecisionReference,
  HostVerificationReference,
  MeaLink,
  RemediationReference,
  StageReference
} from "@/services/codingMeaService.ts";

export interface MeaManageColumn {
  decisionHash: string;
  roundNo: number;
  roundLabel: string; // "决策第 X 轮"
  route: string;
  routeLabel: string;
  targetRecordIds: string[];
  boundedContractPreview: string;
  boundedContractTruncated: boolean;
  rationale: string;
  commandCreatedAtEpochMillis: number | null;
  stateVersion: number;
}

export interface MeaExecuteColumn {
  stageRunId: string;
  attemptNo: number;
  attemptLabel: string; // "Coding Attempt Z"
  status: string;
  commandId?: string;
  remediationKind?: string;
  remediationRoundLabel?: string; // "修复第 Y 轮"
}

export interface MeaAuditColumn {
  hostVerify?: {
    runId: string;
    status: string;
    passed: boolean;
    docsOnly: boolean;
    summary: string;
  };
  qaStage?: {
    stageRunId: string;
    attemptNo: number;
    status: string;
    linkRole: "QA_AGENT";
  };
  auditConclusion?: {
    completedRecordsCount: number;
    pendingRecordsCount: number;
    summary: string;
  };
}

export interface MeaRoundView {
  roundKey: string;
  roundTitle: string;
  manage: MeaManageColumn | null;
  /** manage 为空时的如实说明；不猜测关联。 */
  manageNote: string | null;
  execute: MeaExecuteColumn | null;
  audit: MeaAuditColumn | null;
  isCurrent: boolean;
}

export interface CodingMeaView {
  available: boolean;
  unavailableReason: string | null;
  currentHeadline: string;
  currentRound: MeaRoundView | null;
  historyRounds: MeaRoundView[];
  isFirstCodingWithoutManager: boolean;
  managerDoneReviewNote: string | null;
  hasPartialHistory: boolean;
  pageHasMore: boolean;
}

/** 无法用权威身份字段建立关联时的如实说明，绝不回退猜测。 */
export const MEA_UNRELATABLE_NOTE = "当前记录暂无法关联到具体 Manager 决策";

/**
 * 用响应中的权威身份字段建立当前 Coding Attempt 的 MEA 三栏关联：
 * - command 只认 stageRunId 精确绑定（commandAttemptNo 与 stage attemptNo 不同源）；
 * - remediation 只认 targetCodingStageRunId / remediationRoundId；
 * - decision 只认 links 的 EXECUTES 边或 sourceCommandId → remediation 身份两级解析；
 * - QA 只认 remediation.targetQaStageRunId，或「唯一 Coding 轮 + 唯一 QA stage」的无歧义特例。
 * 关联不上就显示不可用，不做位置/序号/第一条回退。
 */
export function buildCodingMeaView(
  response: CodingMeaResponse | null | undefined,
  selectedStageRunId: string
): CodingMeaView {
  if (!response || !response.available) {
    return {
      available: false,
      unavailableReason: response?.unavailableReason || "协作追踪暂不可用",
      currentHeadline: "",
      currentRound: null,
      historyRounds: [],
      isFirstCodingWithoutManager: false,
      managerDoneReviewNote: null,
      hasPartialHistory: false,
      pageHasMore: false
    };
  }

  const {
    codingStages = [],
    qaStages = [],
    commands = [],
    decisions = [],
    remediations = [],
    hostVerifications = [],
    links = [],
    head,
    page
  } = response;

  const currentCodingStage = codingStages.find((s) => s.stageRunId === selectedStageRunId);

  if (!currentCodingStage) {
    return {
      available: true,
      unavailableReason: "未找到当前选中的 Coding 执行记录",
      currentHeadline: "",
      currentRound: null,
      historyRounds: [],
      isFirstCodingWithoutManager: false,
      managerDoneReviewNote: null,
      hasPartialHistory: Boolean(page?.hasMore),
      pageHasMore: Boolean(page?.hasMore)
    };
  }

  const commandById = new Map<string, CommandReference>(
    commands.map((c) => [c.commandId, c])
  );

  // 关联当前 Coding stageRunId 的 command：仅精确 stage 绑定，不做 role+attempt 回退
  const codingCommand = commands.find((c) => c.stageRunId === currentCodingStage.stageRunId);

  // 关联 remediation round：targetCodingStageRunId 是权威身份，remediationRoundId 次之
  const currentRemediation = remediations.find((r) => (
    r.targetCodingStageRunId === currentCodingStage.stageRunId
    || (codingCommand?.remediationRoundId && r.roundId === codingCommand.remediationRoundId)
  ));

  // 唯一 Coding 轮且至多一个 QA stage 时，QA 的归属不存在歧义，可直接采用
  const singleCodingRound = codingStages.length === 1;
  const unambiguousQaStage = singleCodingRound && qaStages.length === 1 ? qaStages[0] : undefined;

  const currentDecision = findGoverningDecision({
    decisions,
    remediation: currentRemediation,
    links,
    commandById,
    qaStages,
    allowSingleRound: singleCodingRound
  });

  // 查找关联的 Host Verification（codingStageRunId 为权威绑定）
  const currentHv = hostVerifications.find((hv) => hv.codingStageRunId === currentCodingStage.stageRunId);

  // 查找关联的 QA stage：仅 remediation 身份或唯一轮无歧义特例
  let currentQaStage: StageReference | undefined;
  if (currentRemediation?.targetQaStageRunId) {
    currentQaStage = qaStages.find((q) => q.stageRunId === currentRemediation.targetQaStageRunId);
  } else {
    currentQaStage = unambiguousQaStage;
  }

  const isFirstCodingWithoutManager = currentCodingStage.attemptNo === 1 && !currentDecision;

  // 构造三栏
  const manageColumn = currentDecision ? toManageColumn(currentDecision) : null;

  const manageNote = manageColumn
    ? null
    : (isFirstCodingWithoutManager ? null : MEA_UNRELATABLE_NOTE);

  const remediationRoundLabel = currentRemediation
    ? `修复第 ${currentRemediation.remediationNo} 轮`
    : (codingCommand?.remediationNo ? `修复第 ${codingCommand.remediationNo} 轮` : undefined);

  const executeColumn: MeaExecuteColumn = {
    stageRunId: currentCodingStage.stageRunId,
    attemptNo: currentCodingStage.attemptNo,
    attemptLabel: `Coding Attempt ${currentCodingStage.attemptNo}`,
    status: currentCodingStage.status,
    commandId: codingCommand?.commandId,
    remediationKind: currentRemediation?.kind || codingCommand?.remediationKind || undefined,
    remediationRoundLabel
  };

  let auditColumn: MeaAuditColumn | null = null;
  if (currentHv || currentQaStage || head) {
    const completedCount = (head?.records || []).filter((r) => r.status === "COMPLETED").length;
    const pendingCount = (head?.records || []).filter((r) => r.blocking && r.status === "PENDING").length;

    auditColumn = {
      hostVerify: currentHv ? {
        runId: currentHv.runId,
        status: currentHv.status,
        passed: currentHv.status === "SUCCEEDED",
        docsOnly: Boolean(currentHv.docsOnly),
        summary: currentHv.status === "SUCCEEDED"
          ? (currentHv.docsOnly ? "文档模式跳过" : "构建与静态检查通过")
          : `宿主验证未通过 (${currentHv.failureCategory || currentHv.status})`
      } : undefined,
      qaStage: currentQaStage ? {
        stageRunId: currentQaStage.stageRunId,
        attemptNo: currentQaStage.attemptNo,
        status: currentQaStage.status,
        linkRole: "QA_AGENT"
      } : undefined,
      auditConclusion: head ? {
        completedRecordsCount: completedCount,
        pendingRecordsCount: pendingCount,
        summary: pendingCount === 0 ? "全部验收项已审计通过" : `尚有 ${pendingCount} 项阻断验收待闭环`
      } : undefined
    };
  }

  const currentRound: MeaRoundView = {
    roundKey: `round-${currentCodingStage.stageRunId}`,
    roundTitle: remediationRoundLabel
      ? `${remediationRoundLabel} (Coding Attempt ${currentCodingStage.attemptNo})`
      : `首轮执行 (Coding Attempt ${currentCodingStage.attemptNo})`,
    manage: manageColumn,
    manageNote,
    execute: executeColumn,
    audit: auditColumn,
    isCurrent: true
  };

  // 生成当前轮顶部简要结论文案
  let currentHeadline = "";
  let managerDoneReviewNote: string | null = null;

  if (isFirstCodingWithoutManager) {
    currentHeadline = "首次执行尚无前置 Manager 决策";
  } else if (currentDecision?.route === "DONE") {
    currentHeadline = "验收已闭环，交由交付复核";
    managerDoneReviewNote = "交由交付复核";
  } else if (currentDecision?.targetRecordIds && currentDecision.targetRecordIds.length > 0) {
    const targets = currentDecision.targetRecordIds.join("、");
    if (currentQaStage?.status === "RUNNING") {
      currentHeadline = `修复 ${targets}，等待 QA 审计`;
    } else if (currentCodingStage.status === "RUNNING") {
      currentHeadline = `正在修复 ${targets}`;
    } else {
      currentHeadline = `修复目标：${targets}`;
    }
  } else if (manageColumn) {
    currentHeadline = `${manageColumn.roundLabel}：${manageColumn.routeLabel}`;
  } else {
    currentHeadline = `Coding Attempt ${currentCodingStage.attemptNo}`;
  }

  // 历史轮次（除当前 Coding Attempt 之外的其它 Coding 轮次）；
  // 历史轮的 decision / QA 只能通过 remediation 身份关联，唯一轮特例不适用于历史轮。
  const historyRounds: MeaRoundView[] = codingStages
    .filter((s) => s.stageRunId !== currentCodingStage.stageRunId)
    .sort((a, b) => b.attemptNo - a.attemptNo)
    .map((historicalCoding) => {
      const hCommand = commands.find((c) => c.stageRunId === historicalCoding.stageRunId);
      const hRemediation = remediations.find((r) => r.targetCodingStageRunId === historicalCoding.stageRunId);
      const hDecision = hRemediation
        ? findGoverningDecision({
            decisions,
            remediation: hRemediation,
            links,
            commandById,
            qaStages,
            allowSingleRound: false
          })
        : undefined;
      const hHv = hostVerifications.find((hv) => hv.codingStageRunId === historicalCoding.stageRunId);
      const hQa = hRemediation?.targetQaStageRunId
        ? qaStages.find((q) => q.stageRunId === hRemediation.targetQaStageRunId)
        : undefined;

      const hRoundLabel = hRemediation
        ? `修复第 ${hRemediation.remediationNo} 轮`
        : `Coding Attempt ${historicalCoding.attemptNo}`;

      return {
        roundKey: `round-${historicalCoding.stageRunId}`,
        roundTitle: `${hRoundLabel} (历史轮次)`,
        manage: hDecision ? toManageColumn(hDecision) : null,
        manageNote: hDecision ? null : MEA_UNRELATABLE_NOTE,
        execute: {
          stageRunId: historicalCoding.stageRunId,
          attemptNo: historicalCoding.attemptNo,
          attemptLabel: `Coding Attempt ${historicalCoding.attemptNo}`,
          status: historicalCoding.status,
          commandId: hCommand?.commandId,
          remediationKind: hRemediation?.kind || hCommand?.remediationKind || undefined,
          remediationRoundLabel: hRoundLabel
        },
        audit: {
          hostVerify: hHv ? {
            runId: hHv.runId,
            status: hHv.status,
            passed: hHv.status === "SUCCEEDED",
            docsOnly: Boolean(hHv.docsOnly),
            summary: hHv.status === "SUCCEEDED" ? "构建通过" : "未通过"
          } : undefined,
          qaStage: hQa ? {
            stageRunId: hQa.stageRunId,
            attemptNo: hQa.attemptNo,
            status: hQa.status,
            linkRole: "QA_AGENT" as const
          } : undefined
        },
        isCurrent: false
      };
    });

  return {
    available: true,
    unavailableReason: null,
    currentHeadline,
    currentRound,
    historyRounds,
    isFirstCodingWithoutManager,
    managerDoneReviewNote,
    hasPartialHistory: Boolean(page?.hasMore),
    pageHasMore: Boolean(page?.hasMore)
  };
}

interface GoverningDecisionInput {
  decisions: DecisionReference[];
  remediation: RemediationReference | undefined;
  links: MeaLink[];
  commandById: Map<string, CommandReference>;
  qaStages: StageReference[];
  allowSingleRound: boolean;
}

/**
 * 解析「治理本轮 Coding 的 Manager decision」。全部走权威身份：
 * (a) links 的 DECISION --EXECUTES--> COMMAND 且目标 command 属于该 remediation round；
 * (b) decision.sourceCommandId 的 command 绑定在 remediation.sourceStageRunId（触发修复的来源阶段）；
 * (c) decision.sourceCommandId 的 command 绑定在 remediation.targetQaStageRunId（本轮 QA 后的裁决）；
 * (d) 无 remediation 的唯一轮：sourceCommandId 绑定唯一 QA stage 或 HOST_VERIFY 命令。
 * 多个命中时取 roundNo 最高的决策（最新裁决）。
 */
function findGoverningDecision(input: GoverningDecisionInput): DecisionReference | undefined {
  const { decisions, remediation, links, commandById, qaStages, allowSingleRound } = input;

  const matches = decisions.filter((d) => {
    if (remediation) {
      if (remediation.roundId) {
        const executesTarget = links.find((l) => (
          l.relation === "EXECUTES"
          && l.fromType === "DECISION"
          && l.fromId === d.decisionHash
          && l.available
          && l.toId != null
        ))?.toId;
        if (executesTarget && commandById.get(executesTarget)?.remediationRoundId === remediation.roundId) {
          return true;
        }
      }
      const source = d.sourceCommandId ? commandById.get(d.sourceCommandId) : undefined;
      if (source) {
        if (remediation.sourceStageRunId && source.stageRunId === remediation.sourceStageRunId) {
          return true;
        }
        if (remediation.targetQaStageRunId && source.stageRunId === remediation.targetQaStageRunId) {
          return true;
        }
      }
      return false;
    }
    if (!allowSingleRound) {
      return false;
    }
    const source = d.sourceCommandId ? commandById.get(d.sourceCommandId) : undefined;
    if (!source) {
      return false;
    }
    if (source.stage === "HOST_VERIFY") {
      return true;
    }
    return source.stageRunId != null && qaStages.some((q) => q.stageRunId === source.stageRunId);
  });

  if (matches.length === 0) {
    return undefined;
  }
  return matches.reduce((latest, d) => (d.roundNo > latest.roundNo ? d : latest));
}

function toManageColumn(decision: DecisionReference): MeaManageColumn {
  return {
    decisionHash: decision.decisionHash,
    roundNo: decision.roundNo,
    roundLabel: `决策第 ${decision.roundNo} 轮`,
    route: decision.route,
    routeLabel: formatRouteLabel(decision.route),
    targetRecordIds: decision.targetRecordIds || [],
    boundedContractPreview: decision.boundedContractPreview || "",
    boundedContractTruncated: Boolean(decision.boundedContractTruncated),
    rationale: decision.rationale || "",
    commandCreatedAtEpochMillis: decision.commandCreatedAtEpochMillis,
    stateVersion: decision.stateVersion
  };
}

function formatRouteLabel(route: string): string {
  switch (route.toUpperCase()) {
    case "EXECUTE":
      return "有界修复";
    case "DONE":
      return "交由交付复核";
    case "BLOCKED":
      return "已阻断";
    case "ASK":
      return "等待用户输入";
    case "REPLAN":
      return "重新规划";
    default:
      return route;
  }
}
