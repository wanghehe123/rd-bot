import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Code2,
  Cpu,
  Eye,
  EyeOff,
  FileCode,
  FileText,
  FolderOpen,
  Info,
  KeyRound,
  Layers,
  Plus,
  RefreshCw,
  Save,
  SlidersHorizontal,
  Sparkles,
  UploadCloud,
  Wand2
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import { agentRuntimeMutationError, firstEnabledProviderId } from "./agentExecutionProfileForm";
import {
  DELIVERY_ROLES,
  ROLE_LABELS,
  RUNTIME_OPTIONS,
  agentStrategySaveError,
  defaultStrategyDraft,
  emptySlot,
  localDefaultImageLabel,
  type DeliveryRole,
  type StrategyDraft,
  type StrategyRuntimeType,
  type StrategySlotDraft
} from "./agentStrategyForm";
import {
  bindProjectAgentStrategy,
  createAgentStrategy,
  getAgentStrategies,
  getAgentStrategy,
  getModelProviderProfiles,
  getProject,
  updateAgentStrategy,
  uploadAgentStrategyRoleImage,
  type AgentStrategyConsole,
  type AgentStrategyProfile,
  type AgentStrategyRoleSlot,
  type ModelProviderProfile,
  type RdProject
} from "@/services/projectService";

const LEGACY_ID = "legacy-current";

const ROLE_CONFIGS: Record<
  DeliveryRole,
  {
    icon: typeof FileText;
    desc: string;
    badgeClass: string;
    accentBorder: string;
    iconColor: string;
  }
> = {
  REQUIREMENT_REVIEWER: {
    icon: FileText,
    desc: "分析 PRD / Issue 需求与验收标准，生成初步交付计划",
    badgeClass: "bg-sky-50 text-sky-700 border-sky-200",
    accentBorder: "hover:border-sky-300",
    iconColor: "text-sky-600"
  },
  SOLUTION_ARCHITECT: {
    icon: Layers,
    desc: "负责技术方案架构设计、代码检索分析与实现步骤规划",
    badgeClass: "bg-indigo-50 text-indigo-700 border-indigo-200",
    accentBorder: "hover:border-indigo-300",
    iconColor: "text-indigo-600"
  },
  CODING_AGENT: {
    icon: Code2,
    desc: "负责自动化编码、修改代码文件并运行测试与编译",
    badgeClass: "bg-emerald-50 text-emerald-700 border-emerald-200",
    accentBorder: "hover:border-emerald-300",
    iconColor: "text-emerald-600"
  },
  QA_AGENT: {
    icon: CheckCircle2,
    desc: "负责浏览器 QA、端到端测试与质量验收证据收集",
    badgeClass: "bg-amber-50 text-amber-700 border-amber-200",
    accentBorder: "hover:border-amber-300",
    iconColor: "text-amber-600"
  }
};

function slotFromProfile(slot: AgentStrategyRoleSlot, fallbackProvider: string): StrategySlotDraft {
  return {
    role: slot.role,
    runtimeType: slot.runtimeType,
    providerProfileId: slot.providerProfileId || fallbackProvider,
    modelOverride: slot.modelOverride || "",
    extensionSetId: slot.extensionSetId || "",
    extensionSetVersion: slot.extensionSetVersion || 0,
    toolPolicyId: slot.toolPolicyId || "legacy-host-bound",
    toolPolicyVersion: slot.toolPolicyVersion || 1,
    imageMode: slot.runtimeType === "MODEL_ONLY" ? "LOCAL_DEFAULT" : slot.imageMode,
    image: slot.image || "",
    dockerfileName: slot.dockerfileName || "",
    dockerfileSha256: slot.dockerfileSha256 || "",
    dockerfileArtifactUri: slot.dockerfileArtifactUri || "",
    dockerfileText: slot.dockerfileText || "",
    pendingDockerfile: null
  };
}

function draftFromProfile(profile: AgentStrategyProfile, fallbackProvider: string, bindAsDefault: boolean): StrategyDraft {
  const byRole = new Map(profile.roles.map((slot) => [slot.role, slot]));
  return {
    strategyId: profile.strategyId === LEGACY_ID ? "default" : profile.strategyId,
    name: profile.strategyId === LEGACY_ID ? "默认策略" : profile.name,
    enabled: profile.enabled,
    version: profile.version,
    bindAsDefault,
    mutationToken: "",
    roles: DELIVERY_ROLES.map((role) => {
      const existing = byRole.get(role);
      return existing ? slotFromProfile(existing, fallbackProvider) : emptySlot(role, fallbackProvider);
    })
  };
}

function toPayload(projectId: string, draft: StrategyDraft): AgentStrategyProfile {
  return {
    strategyId: draft.strategyId.trim(),
    projectId,
    name: draft.name.trim(),
    enabled: draft.enabled,
    version: draft.version,
    roles: draft.roles.map((slot) => ({
      role: slot.role,
      runtimeType: slot.runtimeType,
      providerProfileId: slot.providerProfileId,
      modelOverride: slot.modelOverride,
      extensionSetId: slot.extensionSetId,
      extensionSetVersion: slot.extensionSetVersion,
      toolPolicyId: slot.toolPolicyId,
      toolPolicyVersion: slot.toolPolicyVersion,
      imageMode: slot.runtimeType === "MODEL_ONLY" ? "LOCAL_DEFAULT" : slot.imageMode,
      image: slot.runtimeType !== "MODEL_ONLY" && slot.imageMode === "CUSTOM" ? slot.image : "",
      dockerfileName: slot.runtimeType !== "MODEL_ONLY" && slot.imageMode === "CUSTOM" ? slot.dockerfileName : "",
      dockerfileSha256: slot.runtimeType !== "MODEL_ONLY" && slot.imageMode === "CUSTOM" ? slot.dockerfileSha256 : "",
      dockerfileArtifactUri: slot.runtimeType !== "MODEL_ONLY" && slot.imageMode === "CUSTOM" ? slot.dockerfileArtifactUri : "",
      dockerfileText: slot.runtimeType !== "MODEL_ONLY" && slot.imageMode === "CUSTOM" ? slot.dockerfileText : ""
    }))
  };
}

export function AgentStrategyPage() {
  const { projectId = "" } = useParams();
  const navigate = useNavigate();
  const [project, setProject] = useState<RdProject | null>(null);
  const [consoleView, setConsoleView] = useState<AgentStrategyConsole | null>(null);
  const [providers, setProviders] = useState<ModelProviderProfile[]>([]);
  const [draft, setDraft] = useState<StrategyDraft>(defaultStrategyDraft());
  const [selectedId, setSelectedId] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [roleErrors, setRoleErrors] = useState<Partial<Record<DeliveryRole, string>>>({});
  const [isNew, setIsNew] = useState(true);
  const [showToken, setShowToken] = useState(false);
  const [expandedAdvanced, setExpandedAdvanced] = useState<Record<string, boolean>>({});

  const providerId = useMemo(() => firstEnabledProviderId(providers, draft.roles[0]?.providerProfileId || ""), [providers, draft.roles]);
  const images = {
    defaultPiImage: consoleView?.defaultPiImage || "rd-bot/pi-agent:local",
    defaultPiQaImage: consoleView?.defaultPiQaImage || "rd-bot/pi-agent-qa:local",
    defaultClaudeImage: consoleView?.defaultClaudeImage || "rd-bot/claude-code:local"
  };

  const load = async (preferId = "") => {
    if (!projectId) return;
    setLoading(true);
    try {
      const [nextProject, nextConsole, nextProviders] = await Promise.all([
        getProject(projectId),
        getAgentStrategies(projectId),
        getModelProviderProfiles()
      ]);
      const enabledProviders = Array.isArray(nextProviders) ? nextProviders : [];
      const fallback = firstEnabledProviderId(enabledProviders);
      setProject(nextProject);
      setConsoleView(nextConsole);
      setProviders(enabledProviders);
      const strategies = nextConsole.strategies || [];
      const target = strategies.find((item) => item.strategyId === preferId)
        || strategies.find((item) => item.strategyId === nextConsole.defaultStrategyId)
        || strategies[0];
      if (!target) {
        setIsNew(true);
        setSelectedId("");
        setDraft(defaultStrategyDraft(fallback));
        return;
      }
      const detail = target.strategyId === LEGACY_ID
        ? target
        : await getAgentStrategy(projectId, target.strategyId).catch(() => target);
      setIsNew(target.strategyId === LEGACY_ID);
      setSelectedId(target.strategyId);
      setDraft(draftFromProfile(detail, fallback, target.strategyId === nextConsole.defaultStrategyId || target.strategyId === LEGACY_ID));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载 Agent 执行策略失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [projectId]);

  const updateSlot = (role: DeliveryRole, patch: Partial<StrategySlotDraft>) => {
    setDraft((current) => ({
      ...current,
      roles: current.roles.map((slot) => slot.role === role ? { ...slot, ...patch } : slot)
    }));
  };

  const startNew = () => {
    setIsNew(true);
    setSelectedId("");
    setRoleErrors({});
    setDraft({
      ...defaultStrategyDraft(providerId),
      mutationToken: draft.mutationToken
    });
  };

  const selectStrategy = async (strategyId: string) => {
    if (!projectId || !consoleView) return;
    const summary = consoleView.strategies.find((item) => item.strategyId === strategyId);
    if (!summary) return;
    setLoading(true);
    try {
      const detail = strategyId === LEGACY_ID
        ? summary
        : await getAgentStrategy(projectId, strategyId);
      setIsNew(strategyId === LEGACY_ID);
      setSelectedId(strategyId);
      setRoleErrors({});
      setDraft({
        ...draftFromProfile(detail, providerId, strategyId === consoleView.defaultStrategyId || strategyId === LEGACY_ID),
        mutationToken: draft.mutationToken
      });
    } catch (error) {
      toast.error(getErrorMessage(error, "加载策略失败"));
    } finally {
      setLoading(false);
    }
  };

  const applyProviderToAll = (targetProvider: string) => {
    if (!targetProvider) return;
    setDraft((current) => ({
      ...current,
      roles: current.roles.map((slot) => ({ ...slot, providerProfileId: targetProvider }))
    }));
    toast.success("已将该 Provider 应用到全部 4 个交付角色");
  };

  const applyRuntimeToAll = (runtimeType: StrategyRuntimeType) => {
    setDraft((current) => ({
      ...current,
      roles: current.roles.map((slot) => ({
        ...slot,
        runtimeType,
        imageMode: runtimeType === "MODEL_ONLY" ? "LOCAL_DEFAULT" : slot.imageMode
      }))
    }));
    const label = runtimeType === "PI" ? "Pi Agent" : runtimeType === "CLAUDE_CODE" ? "Claude Code" : "仅模型";
    toast.success(`已将全部角色切换为 ${label}`);
  };

  const toggleAdvanced = (role: string) => {
    setExpandedAdvanced((current) => ({
      ...current,
      [role]: !current[role]
    }));
  };

  const save = async () => {
    if (!projectId) return;
    const errors = agentStrategySaveError(draft);
    if (errors) {
      setRoleErrors(errors.roles);
      if (errors.form) toast.error(errors.form);
      else {
        const firstRoleError = Object.values(errors.roles)[0];
        if (firstRoleError) toast.error(firstRoleError);
      }
      return;
    }
    setRoleErrors({});
    setSaving(true);
    try {
      const payload = toPayload(projectId, draft);
      const saved = isNew || selectedId === LEGACY_ID
        ? await createAgentStrategy(projectId, payload, draft.mutationToken)
        : await updateAgentStrategy(projectId, draft.strategyId, payload, draft.mutationToken);
      if (draft.bindAsDefault) {
        await bindProjectAgentStrategy(projectId, saved.strategyId, draft.mutationToken);
      }
      for (const slot of draft.roles) {
        if (slot.runtimeType !== "MODEL_ONLY" && slot.imageMode === "CUSTOM" && slot.pendingDockerfile) {
          await uploadAgentStrategyRoleImage(
            projectId,
            saved.strategyId,
            slot.role,
            slot.pendingDockerfile,
            draft.mutationToken
          );
        }
      }
      toast.success("Agent 执行策略已保存");
      await load(saved.strategyId);
    } catch (error) {
      toast.error(agentRuntimeMutationError(error) || getErrorMessage(error, "保存 Agent 执行策略失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="admin-page space-y-4">
      {/* 顶部导航与操作栏 */}
      <div className="admin-page-header">
        <div>
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <button
              type="button"
              className="inline-flex items-center gap-1 hover:text-foreground"
              onClick={() => navigate("/admin/projects")}
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              <span>项目管理</span>
            </button>
            <span>/</span>
            <span className="font-medium text-foreground">{project?.name || projectId}</span>
          </div>
          <h1 className="admin-page-title mt-1 flex items-center gap-2.5">
            <SlidersHorizontal className="h-6 w-6 text-primary" />
            <span>Agent 执行策略</span>
          </h1>
          <p className="admin-page-subtitle">
            为项目 <code className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-xs text-slate-800">{project?.projectKey || projectId}</code> 配置四个关键交付角色的执行器、Provider 模型与运行镜像
          </p>
        </div>
        <div className="admin-page-actions flex flex-wrap items-center gap-2">
          <Button variant="outline" onClick={() => navigate("/admin/projects")}>
            返回项目管理
          </Button>
          <Button
            className="admin-primary-gradient gap-1.5 shadow-sm"
            onClick={() => void save()}
            disabled={loading || saving}
          >
            <Save className="h-4 w-4" />
            <span>{saving ? "保存中..." : "保存策略"}</span>
          </Button>
        </div>
      </div>

      {/* 状态与开关提示横幅 */}
      {consoleView && !consoleView.agentRuntimeEnabled ? (
        <div className="flex items-start gap-3 rounded-lg border border-amber-300 bg-amber-50/90 p-3.5 text-sm text-amber-900 shadow-sm">
          <AlertTriangle className="h-5 w-5 shrink-0 text-amber-600 mt-0.5" />
          <div className="min-w-0">
            <div className="font-semibold text-amber-950">Agent Runtime 执行开关未开启</div>
            <div className="mt-0.5 text-xs leading-relaxed text-amber-800">
              当前后端未打开配置项 <code>rd.executor.agent-runtime.enabled</code>。保存 Pi 策略不会切换实际执行器，任务仍走原 Claude / 仅模型路径。
            </div>
          </div>
        </div>
      ) : null}

      {consoleView?.synthesizedFromLegacy ? (
        <div className="flex items-start gap-3 rounded-lg border border-slate-200 bg-slate-50 p-3.5 text-sm text-slate-700 shadow-sm">
          <Info className="h-5 w-5 shrink-0 text-slate-500 mt-0.5" />
          <div className="min-w-0">
            <span className="font-medium text-slate-900">未落库的历史策略：</span>
            当前显示的是尚未落库的「当前默认」，来自旧的按角色绑定。保存后会写成一份完整策略。
          </div>
        </div>
      ) : null}

      {/* 操作令牌统一输入条 */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 bg-white p-3 shadow-sm sm:p-4">
        <div className="flex items-center gap-2.5">
          <div className="flex h-8 w-8 items-center justify-center rounded-md bg-amber-50 border border-amber-200 text-amber-700">
            <KeyRound className="h-4 w-4" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-slate-800">操作令牌</span>
              <code className="text-[11px] font-mono text-slate-500">X-RD-Agent-Runtime-Token</code>
            </div>
            <div className="text-[11px] text-muted-foreground">保存策略和上传镜像均需要此令牌验证</div>
          </div>
        </div>
        <div className="flex flex-1 items-center justify-end gap-2 sm:max-w-md">
          <div className="relative flex-1">
            <Input
              type={showToken ? "text" : "password"}
              value={draft.mutationToken}
              onChange={(event) => setDraft((current) => ({ ...current, mutationToken: event.target.value }))}
              placeholder="请输入操作令牌"
              className="h-8 pr-8 font-mono text-xs"
              autoComplete="off"
            />
            <button
              type="button"
              className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-700"
              onClick={() => setShowToken(!showToken)}
              title={showToken ? "隐藏令牌" : "显示令牌"}
            >
              {showToken ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
            </button>
          </div>
        </div>
      </div>

      {/* 主体两栏布局：左侧策略列表，右侧策略编辑与角色矩阵 */}
      <div className="grid gap-4 lg:grid-cols-[260px_minmax(0,1fr)]">
        {/* 左侧策略列表卡片 */}
        <Card className="h-fit">
          <CardHeader className="flex flex-row items-center justify-between pb-3">
            <div>
              <CardTitle className="text-sm font-semibold">项目策略列表</CardTitle>
              <CardDescription className="text-xs">共 {(consoleView?.strategies || []).length} 份策略</CardDescription>
            </div>
            <Button size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" onClick={startNew}>
              <Plus className="h-3.5 w-3.5" />
              <span>新建</span>
            </Button>
          </CardHeader>
          <CardContent className="space-y-1.5 pt-0">
            {(consoleView?.strategies || []).map((item) => {
              const active = selectedId === item.strategyId;
              const isDefault = consoleView?.defaultStrategyId === item.strategyId;
              return (
                <button
                  key={item.strategyId}
                  type="button"
                  className={cn(
                    "flex w-full items-center justify-between rounded-md border p-2.5 text-left transition-all",
                    active
                      ? "border-primary bg-primary/5 text-slate-900 shadow-sm"
                      : "border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50/80 text-slate-700"
                  )}
                  onClick={() => void selectStrategy(item.strategyId)}
                >
                  <div className="min-w-0 pr-2">
                    <div className="flex items-center gap-1.5">
                      <span className={cn("truncate text-xs font-semibold", active ? "text-primary" : "text-slate-800")}>
                        {item.name}
                      </span>
                    </div>
                    <div className="mt-0.5 font-mono text-[10px] text-muted-foreground truncate">
                      {item.strategyId}
                    </div>
                  </div>
                  {isDefault ? (
                    <Badge variant="default" className="shrink-0 bg-primary/90 text-[10px] px-1.5 py-0 h-4">
                      默认
                    </Badge>
                  ) : null}
                </button>
              );
            })}
            {(consoleView?.strategies || []).length === 0 ? (
              <div className="py-6 text-center text-xs text-muted-foreground">暂无策略，点击上方新建</div>
            ) : null}
          </CardContent>
        </Card>

        {/* 右侧主配置区域 */}
        <div className="space-y-4 min-w-0">
          {/* 基本信息与快捷预设卡片 */}
          <Card>
            <CardHeader className="pb-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <CardTitle className="text-sm font-semibold">基本信息</CardTitle>
                  <CardDescription className="text-xs">配置策略标识与启用属性</CardDescription>
                </div>
                {/* 快捷批量操作工具栏 */}
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="text-xs text-slate-400">快速设置:</span>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    className="h-7 text-xs gap-1 text-slate-600 hover:text-slate-900"
                    onClick={() => applyRuntimeToAll("PI")}
                  >
                    <Sparkles className="h-3 w-3 text-primary" />
                    <span>全部设为 Pi</span>
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    className="h-7 text-xs gap-1 text-slate-600 hover:text-slate-900"
                    onClick={() => applyProviderToAll(draft.roles[0]?.providerProfileId || providerId)}
                  >
                    <Wand2 className="h-3 w-3 text-indigo-600" />
                    <span>统一下发 Provider</span>
                  </Button>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label className="mb-1 block text-xs font-medium text-slate-700">
                    策略 ID <span className="text-slate-400 font-normal">（保存后不可更改）</span>
                  </label>
                  <Input
                    value={draft.strategyId}
                    disabled={!isNew}
                    maxLength={64}
                    onChange={(event) => setDraft((current) => ({ ...current, strategyId: event.target.value }))}
                    placeholder="如 default / fast-dev"
                    className="font-mono text-xs"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-slate-700">
                    策略名称
                  </label>
                  <Input
                    value={draft.name}
                    onChange={(event) => setDraft((current) => ({ ...current, name: event.target.value }))}
                    placeholder="如 默认执行策略 / 全流程 Pi 策略"
                    className="text-xs"
                  />
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-6 pt-1 border-t border-slate-100">
                <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-slate-800">
                  <input
                    type="checkbox"
                    checked={draft.enabled}
                    onChange={(event) => setDraft((current) => ({ ...current, enabled: event.target.checked }))}
                    className="rounded border-slate-300 text-primary focus:ring-primary h-4 w-4"
                  />
                  <span>启用此策略</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer text-xs font-medium text-slate-800">
                  <input
                    type="checkbox"
                    checked={draft.bindAsDefault}
                    onChange={(event) => setDraft((current) => ({ ...current, bindAsDefault: event.target.checked }))}
                    className="rounded border-slate-300 text-primary focus:ring-primary h-4 w-4"
                  />
                  <span>设为项目默认策略</span>
                </label>
              </div>
            </CardContent>
          </Card>

          {/* 四大交付角色卡片矩阵 */}
          <div className="grid gap-4 xl:grid-cols-2">
            {draft.roles.map((slot) => {
              const roleMeta = ROLE_CONFIGS[slot.role];
              const RoleIcon = roleMeta.icon;
              const hasError = !!roleErrors[slot.role];
              const isAdvancedOpen = !!expandedAdvanced[slot.role];

              return (
                <Card
                  key={slot.role}
                  className={cn(
                    "transition-all border",
                    hasError ? "border-rose-300 bg-rose-50/20" : "border-slate-200",
                    roleMeta.accentBorder
                  )}
                >
                  <CardHeader className="pb-3">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <div className={cn("p-1.5 rounded-md bg-white border border-slate-200 shadow-2xs", roleMeta.iconColor)}>
                          <RoleIcon className="h-4 w-4" />
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <CardTitle className="text-sm font-semibold text-slate-900">
                              {ROLE_LABELS[slot.role]}
                            </CardTitle>
                            <Badge variant="outline" className={cn("font-mono text-[10px] px-1.5 py-0", roleMeta.badgeClass)}>
                              {slot.role}
                            </Badge>
                          </div>
                          <p className="text-[11px] text-muted-foreground mt-0.5">{roleMeta.desc}</p>
                        </div>
                      </div>
                    </div>
                    {hasError ? (
                      <div className="mt-2 flex items-center gap-1.5 text-xs text-rose-700 bg-rose-50 border border-rose-200 rounded px-2.5 py-1">
                        <AlertCircle className="h-3.5 w-3.5 shrink-0" />
                        <span>{roleErrors[slot.role]}</span>
                      </div>
                    ) : null}
                  </CardHeader>
                  <CardContent className="space-y-3.5 text-xs">
                    {/* 执行器与 Provider 网格 */}
                    <div className="grid gap-3 sm:grid-cols-2">
                      <div>
                        <label className="mb-1 block font-medium text-slate-700">执行器 (Runtime)</label>
                        <Select
                          value={slot.runtimeType}
                          onValueChange={(value) =>
                            updateSlot(slot.role, {
                              runtimeType: value as StrategySlotDraft["runtimeType"],
                              imageMode: value === "MODEL_ONLY" ? "LOCAL_DEFAULT" : slot.imageMode
                            })
                          }
                        >
                          <SelectTrigger className="h-8 text-xs">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {RUNTIME_OPTIONS.map((option) => (
                              <SelectItem key={option.value} value={option.value}>
                                {option.label}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                      <div>
                        <label className="mb-1 block font-medium text-slate-700">Model Provider</label>
                        <Select
                          value={slot.providerProfileId || undefined}
                          onValueChange={(value) => updateSlot(slot.role, { providerProfileId: value })}
                        >
                          <SelectTrigger className="h-8 text-xs">
                            <SelectValue placeholder="选择 Provider" />
                          </SelectTrigger>
                          <SelectContent>
                            {providers
                              .filter((item) => item.enabled)
                              .map((item) => (
                                <SelectItem key={item.providerId} value={item.providerId}>
                                  {item.displayName || item.providerId}
                                </SelectItem>
                              ))}
                          </SelectContent>
                        </Select>
                      </div>
                    </div>

                    {/* 模型覆盖 */}
                    <div>
                      <label className="mb-1 block font-medium text-slate-700">
                        模型覆盖 <span className="text-slate-400 font-normal">（选填，留空使用 Provider 默认）</span>
                      </label>
                      <Input
                        value={slot.modelOverride}
                        onChange={(event) => updateSlot(slot.role, { modelOverride: event.target.value })}
                        placeholder="如 deepseek-chat / claude-3-7-sonnet"
                        className="h-8 text-xs font-mono"
                      />
                    </div>

                    {/* 运行时镜像选择 */}
                    {slot.runtimeType !== "MODEL_ONLY" ? (
                      <div className="rounded-lg border border-slate-100 bg-slate-50/70 p-2.5 space-y-2">
                        <span className="block font-medium text-slate-700">运行时镜像</span>
                        <div className="flex flex-wrap gap-3">
                          <label className="flex items-center gap-1.5 cursor-pointer text-xs text-slate-800">
                            <input
                              type="radio"
                              name={`image-mode-${slot.role}`}
                              checked={slot.imageMode === "LOCAL_DEFAULT"}
                              onChange={() =>
                                updateSlot(slot.role, {
                                  imageMode: "LOCAL_DEFAULT",
                                  pendingDockerfile: null,
                                  image: "",
                                  dockerfileName: "",
                                  dockerfileSha256: "",
                                  dockerfileArtifactUri: "",
                                  dockerfileText: ""
                                })
                              }
                              className="text-primary focus:ring-primary h-3.5 w-3.5"
                            />
                            <span>
                              本地默认 <code className="text-[10px] text-slate-500 font-mono">({localDefaultImageLabel(slot.runtimeType, slot.role, images)})</code>
                            </span>
                          </label>
                          <label className="flex items-center gap-1.5 cursor-pointer text-xs text-slate-800">
                            <input
                              type="radio"
                              name={`image-mode-${slot.role}`}
                              checked={slot.imageMode === "CUSTOM"}
                              onChange={() => updateSlot(slot.role, { imageMode: "CUSTOM" })}
                              className="text-primary focus:ring-primary h-3.5 w-3.5"
                            />
                            <span>上传 Dockerfile</span>
                          </label>
                        </div>

                        {slot.imageMode === "CUSTOM" ? (
                          <div className="mt-2 space-y-1.5 rounded border border-dashed border-slate-300 bg-white p-2.5">
                            <Input
                              type="file"
                              className="h-8 text-xs cursor-pointer"
                              onChange={(event) =>
                                updateSlot(slot.role, { pendingDockerfile: event.target.files?.[0] || null })
                              }
                            />
                            {slot.dockerfileName ? (
                              <p className="text-[11px] text-emerald-700 flex items-center gap-1">
                                <Check className="h-3 w-3" />
                                <span>已保存：{slot.dockerfileName}</span>
                              </p>
                            ) : null}
                            {slot.runtimeType === "PI" ? (
                              <p className="text-[11px] text-muted-foreground leading-relaxed">
                                Pi 自定义镜像本轮只落库，执行仍使用本地 Pi 镜像，后续再接线。
                              </p>
                            ) : null}
                          </div>
                        ) : null}
                      </div>
                    ) : null}

                    {/* 高级配置可折叠面板 */}
                    <div className="rounded-lg border border-slate-200 overflow-hidden">
                      <button
                        type="button"
                        className="flex w-full items-center justify-between bg-slate-50/80 px-3 py-1.5 text-left text-xs font-medium text-slate-700 hover:bg-slate-100 transition-colors"
                        onClick={() => toggleAdvanced(slot.role)}
                      >
                        <span className="flex items-center gap-1.5">
                          <span>高级配置 (Tool Policy / Extensions)</span>
                        </span>
                        {isAdvancedOpen ? <ChevronDown className="h-3.5 w-3.5 text-slate-400" /> : <ChevronRight className="h-3.5 w-3.5 text-slate-400" />}
                      </button>

                      {isAdvancedOpen ? (
                        <div className="space-y-2.5 border-t border-slate-200 bg-white p-3">
                          <div className="grid gap-2 sm:grid-cols-2">
                            <div>
                              <label className="mb-1 block font-medium text-slate-700">Tool Policy ID</label>
                              <Input
                                value={slot.toolPolicyId}
                                onChange={(event) => updateSlot(slot.role, { toolPolicyId: event.target.value })}
                                className="h-7 text-xs font-mono"
                              />
                            </div>
                            <div>
                              <label className="mb-1 block font-medium text-slate-700">Tool Policy 版本</label>
                              <Input
                                type="number"
                                min={1}
                                value={slot.toolPolicyVersion}
                                onChange={(event) => updateSlot(slot.role, { toolPolicyVersion: Number(event.target.value) || 1 })}
                                className="h-7 text-xs font-mono"
                              />
                            </div>
                          </div>
                          <div className="grid gap-2 sm:grid-cols-2">
                            <div>
                              <label className="mb-1 block font-medium text-slate-700">Extension Set ID</label>
                              <Input
                                value={slot.extensionSetId}
                                onChange={(event) => updateSlot(slot.role, { extensionSetId: event.target.value })}
                                className="h-7 text-xs font-mono"
                              />
                            </div>
                            <div>
                              <label className="mb-1 block font-medium text-slate-700">Extension Set 版本</label>
                              <Input
                                type="number"
                                min={0}
                                value={slot.extensionSetVersion}
                                onChange={(event) => updateSlot(slot.role, { extensionSetVersion: Number(event.target.value) || 0 })}
                                className="h-7 text-xs font-mono"
                              />
                            </div>
                          </div>
                        </div>
                      ) : null}
                    </div>
                  </CardContent>
                </Card>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
