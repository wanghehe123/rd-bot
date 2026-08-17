import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  AlertTriangle,
  Check,
  CheckCircle2,
  Code2,
  FileText,
  Layers,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  UploadCloud,
  X
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { RelativeTime } from "@/components/RelativeTime";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import {
  approveSkill,
  listSkillCatalog,
  listSkillRoleBindings,
  replaceSkillRoleBindings,
  SKILL_AGENT_ROLES,
  updateSkill,
  uploadSkill,
  type SkillAgentRole,
  type SkillCatalogEntry,
  type SkillCatalogStatus,
  type SkillRiskLevel,
  type SkillRoleBinding
} from "@/services/skillHubService";

const ROLE_META: Record<
  SkillAgentRole,
  { label: string; icon: typeof FileText; color: string; badgeClass: string }
> = {
  REQUIREMENT_REVIEWER: {
    label: "需求评审",
    icon: FileText,
    color: "text-sky-600",
    badgeClass: "bg-sky-50 text-sky-700 border-sky-200"
  },
  SOLUTION_ARCHITECT: {
    label: "方案架构",
    icon: Layers,
    color: "text-indigo-600",
    badgeClass: "bg-indigo-50 text-indigo-700 border-indigo-200"
  },
  CODING_AGENT: {
    label: "编码 Agent",
    icon: Code2,
    color: "text-emerald-600",
    badgeClass: "bg-emerald-50 text-emerald-700 border-emerald-200"
  },
  QA_AGENT: {
    label: "QA Agent",
    icon: CheckCircle2,
    color: "text-amber-600",
    badgeClass: "bg-amber-50 text-amber-700 border-amber-200"
  }
};

const STATUS_CONFIG: Record<SkillCatalogStatus, { label: string; badgeClass: string }> = {
  ACTIVE: { label: "已启用", badgeClass: "border-emerald-200 bg-emerald-50 text-emerald-700" },
  WAITING_APPROVAL: { label: "待审批", badgeClass: "border-amber-300 bg-amber-50 text-amber-800" },
  DISABLED: { label: "已停用", badgeClass: "border-slate-200 bg-slate-50 text-slate-500" },
  REJECTED: { label: "已拒绝", badgeClass: "border-rose-200 bg-rose-50 text-rose-700" }
};

const RISK_CONFIG: Record<
  string,
  { label: string; badgeClass: string; icon: typeof ShieldCheck }
> = {
  LOW: { label: "低风险", badgeClass: "border-emerald-200 bg-emerald-50 text-emerald-700", icon: ShieldCheck },
  MEDIUM: { label: "中风险", badgeClass: "border-amber-200 bg-amber-50 text-amber-700", icon: AlertTriangle },
  HIGH: { label: "高风险", badgeClass: "border-rose-200 bg-rose-50 text-rose-700", icon: ShieldAlert },
  UNKNOWN: { label: "未知", badgeClass: "border-slate-200 bg-slate-50 text-slate-500", icon: AlertCircle }
};

type BindingDraft = {
  skillId: string;
  forceGuide: boolean;
};

function bindingsToDraft(bindings: SkillRoleBinding[] | undefined): BindingDraft[] {
  return (bindings || [])
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((item) => ({ skillId: item.skillId, forceGuide: Boolean(item.forceGuide) }));
}

export function SkillHubPage() {
  const [catalog, setCatalog] = useState<SkillCatalogEntry[]>([]);
  const [bindings, setBindings] = useState<Record<string, SkillRoleBinding[]>>({});
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState("catalog");

  // 筛选与搜索
  const [keyword, setKeyword] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("all");
  const [riskFilter, setRiskFilter] = useState<string>("all");

  // 弹窗状态
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadLoading, setUploadLoading] = useState(false);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadVersion, setUploadVersion] = useState("");
  const [uploadRisk, setUploadRisk] = useState<SkillRiskLevel>("LOW");
  const [uploadRoles, setUploadRoles] = useState<string[]>([...SKILL_AGENT_ROLES]);
  const [uploadGuidePrompt, setUploadGuidePrompt] = useState("");
  const [uploadForceGuide, setUploadForceGuide] = useState(false);

  const [editing, setEditing] = useState<SkillCatalogEntry | null>(null);
  const [editLoading, setEditLoading] = useState(false);
  const [editDescription, setEditDescription] = useState("");
  const [editGuidePrompt, setEditGuidePrompt] = useState("");
  const [editForceGuide, setEditForceGuide] = useState(false);
  const [editStatus, setEditStatus] = useState<SkillCatalogStatus>("ACTIVE");
  const [editRoles, setEditRoles] = useState<string[]>([]);

  // 角色绑定工作区
  const [activeRole, setActiveRole] = useState<SkillAgentRole>("REQUIREMENT_REVIEWER");
  const [bindingDrafts, setBindingDrafts] = useState<Record<string, BindingDraft[]>>({});
  const [savingBindings, setSavingBindings] = useState(false);

  const loadData = async () => {
    setLoading(true);
    try {
      const [nextCatalog, nextBindings] = await Promise.all([
        listSkillCatalog(),
        listSkillRoleBindings()
      ]);
      setCatalog(Array.isArray(nextCatalog) ? nextCatalog : []);
      setBindings(nextBindings || {});
      const nextDrafts: Record<string, BindingDraft[]> = {};
      for (const role of SKILL_AGENT_ROLES) {
        nextDrafts[role] = bindingsToDraft(nextBindings?.[role]);
      }
      setBindingDrafts(nextDrafts);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载 Skill Hub 数据失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadData();
  }, []);

  useEffect(() => {
    if (!editing) return;
    setEditDescription(editing.description || "");
    setEditGuidePrompt(editing.guidePrompt || "");
    setEditForceGuide(Boolean(editing.forceGuide));
    setEditStatus(editing.status);
    setEditRoles([...(editing.allowedRoles || [])]);
  }, [editing]);

  // 态势指标计算
  const metrics = useMemo(() => {
    const total = catalog.length;
    const active = catalog.filter((item) => item.status === "ACTIVE").length;
    const pending = catalog.filter((item) => item.status === "WAITING_APPROVAL").length;
    const highRisk = catalog.filter((item) => item.riskLevel === "HIGH" || item.riskLevel === "MEDIUM").length;
    let totalBindings = 0;
    Object.values(bindingDrafts).forEach((arr) => {
      totalBindings += arr.length;
    });
    return { total, active, pending, highRisk, totalBindings };
  }, [catalog, bindingDrafts]);

  // 过滤后的 Skill 列表
  const filteredCatalog = useMemo(() => {
    return catalog.filter((skill) => {
      if (statusFilter !== "all" && skill.status !== statusFilter) return false;
      if (riskFilter !== "all" && skill.riskLevel !== riskFilter) return false;
      if (keyword.trim()) {
        const query = keyword.toLowerCase();
        const matchId = skill.skillId?.toLowerCase().includes(query);
        const matchDesc = skill.description?.toLowerCase().includes(query);
        if (!matchId && !matchDesc) return false;
      }
      return true;
    });
  }, [catalog, statusFilter, riskFilter, keyword]);

  const activeSkills = useMemo(
    () => catalog.filter((item) => item.status === "ACTIVE"),
    [catalog]
  );

  const toggleRoleInArray = (list: string[], role: string, checked: boolean) =>
    checked ? [...new Set([...list, role])] : list.filter((r) => r !== role);

  const handleApprove = async (skillId: string) => {
    try {
      await approveSkill(skillId);
      toast.success(`Skill [${skillId}] 已审批通过`);
      await loadData();
    } catch (error) {
      toast.error(getErrorMessage(error, "审批失败"));
    }
  };

  const handleUploadSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!uploadFile) {
      toast.error("请选择要上传的 Skill 文件");
      return;
    }
    setUploadLoading(true);
    try {
      await uploadSkill(uploadFile, {
        version: uploadVersion,
        riskLevel: uploadRisk,
        allowedRoles: uploadRoles.join(","),
        guidePrompt: uploadGuidePrompt,
        forceGuide: uploadForceGuide
      });
      toast.success("Skill 上传成功");
      setUploadOpen(false);
      setUploadFile(null);
      setUploadVersion("");
      setUploadGuidePrompt("");
      setUploadForceGuide(false);
      setUploadRoles([...SKILL_AGENT_ROLES]);
      await loadData();
    } catch (error) {
      toast.error(getErrorMessage(error, "上传 Skill 失败"));
    } finally {
      setUploadLoading(false);
    }
  };

  const handleEditSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    setEditLoading(true);
    try {
      await updateSkill(editing.skillId, {
        description: editDescription,
        guidePrompt: editGuidePrompt,
        forceGuide: editForceGuide,
        allowedRoles: editRoles,
        status: editStatus
      });
      toast.success("Skill 更新成功");
      setEditing(null);
      await loadData();
    } catch (error) {
      toast.error(getErrorMessage(error, "更新 Skill 失败"));
    } finally {
      setEditLoading(false);
    }
  };

  const toggleBindingSkill = (role: SkillAgentRole, skillId: string, checked: boolean) => {
    setBindingDrafts((current) => {
      const existing = current[role] || [];
      if (checked) {
        if (existing.some((item) => item.skillId === skillId)) return current;
        return { ...current, [role]: [...existing, { skillId, forceGuide: false }] };
      }
      return { ...current, [role]: existing.filter((item) => item.skillId !== skillId) };
    });
  };

  const setBindingForceGuide = (role: SkillAgentRole, skillId: string, forceGuide: boolean) => {
    setBindingDrafts((current) => ({
      ...current,
      [role]: (current[role] || []).map((item) =>
        item.skillId === skillId ? { ...item, forceGuide } : item
      )
    }));
  };

  const handleSaveBindings = async (role: SkillAgentRole) => {
    const drafts = bindingDrafts[role] || [];
    setSavingBindings(true);
    try {
      await replaceSkillRoleBindings(
        role,
        drafts.map((item, index) => ({
          skillId: item.skillId,
          sortOrder: index,
          forceGuide: item.forceGuide
        }))
      );
      toast.success(`${ROLE_META[role].label} 角色技能绑定已保存`);
      await loadData();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存角色绑定失败"));
    } finally {
      setSavingBindings(false);
    }
  };

  const currentRoleBindings = bindingDrafts[activeRole] || [];

  return (
    <div className="admin-page space-y-4">
      {/* 顶部标题与主操作 */}
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title flex items-center gap-2.5">
            <Sparkles className="h-6 w-6 text-primary" />
            <span>Skill Hub / 技能中心</span>
          </h1>
          <p className="admin-page-subtitle">
            管理智能体 Skill 目录、安全审批与角色绑定。默认披露 Skill 描述，开启强制引导后将自动注入 guidePrompt
          </p>
        </div>
        <div className="admin-page-actions flex items-center gap-2">
          <Button variant="outline" onClick={() => void loadData()} disabled={loading}>
            <RefreshCw className={cn("mr-2 h-4 w-4", loading && "animate-spin")} />
            刷新
          </Button>
          <Button className="admin-primary-gradient gap-1.5 shadow-sm" onClick={() => setUploadOpen(true)}>
            <UploadCloud className="h-4 w-4" />
            <span>上传 Skill</span>
          </Button>
        </div>
      </div>

      {/* 态势看板卡片 */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div
          role="button"
          tabIndex={0}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-slate-200 bg-white p-3.5 shadow-sm transition hover:border-primary/40 hover:shadow"
          onClick={() => {
            setActiveTab("catalog");
            setStatusFilter("all");
          }}
          onKeyDown={(e) => e.key === "Enter" && setActiveTab("catalog")}
        >
          <div>
            <div className="text-xs text-muted-foreground">Skill 目录总数</div>
            <div className="text-lg font-bold text-slate-900">{metrics.total} <span className="text-xs font-normal text-slate-400">个</span></div>
          </div>
          <Sparkles className="h-5 w-5 text-slate-400" />
        </div>
        <div
          role="button"
          tabIndex={0}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-emerald-200 bg-emerald-50/40 p-3.5 shadow-sm transition hover:border-emerald-400 hover:shadow"
          onClick={() => {
            setActiveTab("catalog");
            setStatusFilter("ACTIVE");
          }}
          onKeyDown={(e) => e.key === "Enter" && setStatusFilter("ACTIVE")}
        >
          <div>
            <div className="text-xs font-medium text-emerald-800">已启用 (Active)</div>
            <div className="text-lg font-bold text-emerald-900">{metrics.active}</div>
          </div>
          <CheckCircle2 className="h-5 w-5 text-emerald-600" />
        </div>
        <div
          role="button"
          tabIndex={0}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-amber-200 bg-amber-50/40 p-3.5 shadow-sm transition hover:border-amber-400 hover:shadow"
          onClick={() => {
            setActiveTab("catalog");
            setStatusFilter("WAITING_APPROVAL");
          }}
          onKeyDown={(e) => e.key === "Enter" && setStatusFilter("WAITING_APPROVAL")}
        >
          <div>
            <div className="text-xs font-medium text-amber-800">待审批</div>
            <div className="text-lg font-bold text-amber-900">{metrics.pending}</div>
          </div>
          <AlertTriangle className="h-5 w-5 text-amber-600" />
        </div>
        <div
          role="button"
          tabIndex={0}
          className="flex cursor-pointer items-center justify-between rounded-lg border border-indigo-200 bg-indigo-50/40 p-3.5 shadow-sm transition hover:border-indigo-400 hover:shadow"
          onClick={() => setActiveTab("bindings")}
          onKeyDown={(e) => e.key === "Enter" && setActiveTab("bindings")}
        >
          <div>
            <div className="text-xs font-medium text-indigo-800">角色已绑总计</div>
            <div className="text-lg font-bold text-indigo-900">{metrics.totalBindings} <span className="text-xs font-normal text-slate-400">次绑定</span></div>
          </div>
          <Layers className="h-5 w-5 text-indigo-600" />
        </div>
      </div>

      {/* 主体 Tabs */}
      <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-4">
        <TabsList className="grid w-full grid-cols-2 sm:w-[320px]">
          <TabsTrigger value="catalog" className="text-xs font-medium">
            Skill 目录 ({catalog.length})
          </TabsTrigger>
          <TabsTrigger value="bindings" className="text-xs font-medium">
            角色技能绑定
          </TabsTrigger>
        </TabsList>

        {/* Tab 1: Skill 目录 */}
        <TabsContent value="catalog" className="space-y-4">
          <Card>
            <CardHeader className="pb-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <CardTitle className="text-sm font-semibold">Skill 目录列表</CardTitle>
                  <CardDescription className="text-xs">
                    检索已入库的全部 Skill，支持查看版本、风险评级与审批操作
                  </CardDescription>
                </div>
                {/* 搜索与筛选工具栏 */}
                <div className="flex flex-wrap items-center gap-2">
                  <div className="relative">
                    <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" />
                    <Input
                      value={keyword}
                      onChange={(e) => setKeyword(e.target.value)}
                      placeholder="搜索 Skill ID / 描述"
                      className="h-8 w-[200px] pl-8 text-xs"
                    />
                  </div>
                  <Select value={statusFilter} onValueChange={setStatusFilter}>
                    <SelectTrigger className="h-8 w-[120px] text-xs">
                      <SelectValue placeholder="状态" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">全部状态</SelectItem>
                      <SelectItem value="ACTIVE">已启用</SelectItem>
                      <SelectItem value="WAITING_APPROVAL">待审批</SelectItem>
                      <SelectItem value="DISABLED">已停用</SelectItem>
                      <SelectItem value="REJECTED">已拒绝</SelectItem>
                    </SelectContent>
                  </Select>
                  <Select value={riskFilter} onValueChange={setRiskFilter}>
                    <SelectTrigger className="h-8 w-[120px] text-xs">
                      <SelectValue placeholder="风险等级" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">全部风险</SelectItem>
                      <SelectItem value="LOW">低风险</SelectItem>
                      <SelectItem value="MEDIUM">中风险</SelectItem>
                      <SelectItem value="HIGH">高风险</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
            </CardHeader>
            <CardContent className="pt-0">
              {loading ? (
                <div className="py-12 text-center text-xs text-muted-foreground">加载中...</div>
              ) : filteredCatalog.length === 0 ? (
                <div className="py-12 text-center text-xs text-muted-foreground">
                  暂无匹配的 Skill。可点击右上角「上传 Skill」进行添加。
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <Table className="min-w-[960px] table-fixed">
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-[260px]">Skill ID 与描述</TableHead>
                        <TableHead className="w-[90px]">版本</TableHead>
                        <TableHead className="w-[100px]">风险等级</TableHead>
                        <TableHead className="w-[95px]">状态</TableHead>
                        <TableHead className="w-[95px]">强制引导</TableHead>
                        <TableHead className="w-[180px]">允许角色</TableHead>
                        <TableHead className="w-[140px] text-left">操作</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {filteredCatalog.map((skill) => {
                        const statusMeta = STATUS_CONFIG[skill.status] || STATUS_CONFIG.DISABLED;
                        const riskMeta = RISK_CONFIG[skill.riskLevel] || RISK_CONFIG.UNKNOWN;
                        const RiskIcon = riskMeta.icon;

                        return (
                          <TableRow key={skill.skillId} className="hover:bg-slate-50/80">
                            <TableCell className="font-medium">
                              <div className="min-w-0">
                                <div className="flex items-center gap-1.5">
                                  <code className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-xs font-semibold text-slate-900">
                                    {skill.skillId}
                                  </code>
                                </div>
                                <div className="mt-1 truncate text-xs text-muted-foreground" title={skill.description}>
                                  {skill.description || "暂无描述"}
                                </div>
                              </div>
                            </TableCell>
                            <TableCell>
                              <code className="font-mono text-xs text-slate-700">
                                {skill.version || "-"}
                              </code>
                            </TableCell>
                            <TableCell>
                              <Badge variant="outline" className={cn("gap-1 font-medium text-xs", riskMeta.badgeClass)}>
                                <RiskIcon className="h-3 w-3" />
                                <span>{riskMeta.label}</span>
                              </Badge>
                            </TableCell>
                            <TableCell>
                              <Badge variant="outline" className={cn("font-medium text-xs", statusMeta.badgeClass)}>
                                {statusMeta.label}
                              </Badge>
                            </TableCell>
                            <TableCell>
                              {skill.forceGuide ? (
                                <Badge variant="outline" className="border-amber-300 bg-amber-50 text-amber-800 text-[10px]">
                                  强制引导
                                </Badge>
                              ) : (
                                <span className="text-xs text-muted-foreground/60">默认</span>
                              )}
                            </TableCell>
                            <TableCell>
                              <div className="flex flex-wrap gap-1">
                                {(skill.allowedRoles || []).map((role) => {
                                  const rMeta = ROLE_META[role as SkillAgentRole];
                                  return (
                                    <span
                                      key={role}
                                      className={cn("inline-block rounded px-1.5 py-0.5 text-[10px] font-medium border", rMeta ? rMeta.badgeClass : "bg-slate-100 text-slate-600")}
                                    >
                                      {rMeta ? rMeta.label : role}
                                    </span>
                                  );
                                })}
                                {!skill.allowedRoles?.length ? <span className="text-xs text-muted-foreground">-</span> : null}
                              </div>
                            </TableCell>
                            <TableCell>
                              <div className="flex items-center gap-1.5">
                                <Button
                                  size="sm"
                                  variant="outline"
                                  className="h-7 gap-1 px-2 text-xs font-medium text-slate-700 hover:text-slate-950"
                                  onClick={() => setEditing(skill)}
                                >
                                  <Pencil className="h-3 w-3 text-slate-500" />
                                  <span>编辑</span>
                                </Button>
                                {skill.status === "WAITING_APPROVAL" ? (
                                  <Button
                                    size="sm"
                                    className="h-7 gap-1 px-2 text-xs bg-emerald-600 hover:bg-emerald-700 text-white"
                                    onClick={() => void handleApprove(skill.skillId)}
                                  >
                                    <Check className="h-3 w-3" />
                                    <span>审批</span>
                                  </Button>
                                ) : null}
                              </div>
                            </TableCell>
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Tab 2: 角色技能绑定 */}
        <TabsContent value="bindings" className="space-y-4">
          <Card>
            <CardHeader className="pb-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <CardTitle className="text-sm font-semibold">交付角色 Skill 绑定配置</CardTitle>
                  <CardDescription className="text-xs">
                    只有状态为 ACTIVE 的 Skill 可绑定到角色。开启【强制引导】后系统将额外把 guidePrompt 注入该角色的上下文提示词中。
                  </CardDescription>
                </div>
                <Button
                  className="admin-primary-gradient h-8 gap-1 px-3 text-xs shadow-sm"
                  onClick={() => void handleSaveBindings(activeRole)}
                  disabled={savingBindings}
                >
                  <Check className="h-3.5 w-3.5" />
                  <span>{savingBindings ? "保存中..." : `保存 ${ROLE_META[activeRole].label} 绑定`}</span>
                </Button>
              </div>

              {/* 角色切换 Segmented 栏 */}
              <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
                {SKILL_AGENT_ROLES.map((role) => {
                  const rMeta = ROLE_META[role];
                  const Icon = rMeta.icon;
                  const count = (bindingDrafts[role] || []).length;
                  const active = activeRole === role;

                  return (
                    <button
                      key={role}
                      type="button"
                      onClick={() => setActiveRole(role)}
                      className={cn(
                        "flex items-center justify-between rounded-lg border p-3 text-left transition-all",
                        active
                          ? "border-primary bg-primary/5 shadow-xs"
                          : "border-slate-200 bg-white hover:bg-slate-50/80"
                      )}
                    >
                      <div className="flex items-center gap-2">
                        <div className={cn("p-1.5 rounded-md bg-white border border-slate-200", rMeta.color)}>
                          <Icon className="h-4 w-4" />
                        </div>
                        <div>
                          <div className={cn("text-xs font-semibold", active ? "text-primary" : "text-slate-800")}>
                            {rMeta.label}
                          </div>
                          <div className="font-mono text-[10px] text-muted-foreground">{role}</div>
                        </div>
                      </div>
                      <Badge
                        variant="outline"
                        className={cn("font-mono text-xs font-bold", active ? "border-primary bg-white text-primary" : "border-slate-200 text-slate-600")}
                      >
                        {count}
                      </Badge>
                    </button>
                  );
                })}
              </div>
            </CardHeader>

            <CardContent className="space-y-3 pt-2">
              <div className="text-xs font-medium text-slate-700">
                可绑定的 Skill 列表（共 {activeSkills.length} 个 Active Skill）
              </div>

              {activeSkills.length === 0 ? (
                <div className="rounded-lg border border-dashed border-slate-200 py-8 text-center text-xs text-muted-foreground">
                  暂无 ACTIVE 状态的 Skill 可绑定。请先在目录中启用或审批 Skill。
                </div>
              ) : (
                <div className="grid gap-2.5 sm:grid-cols-2">
                  {activeSkills.map((skill) => {
                    const bound = currentRoleBindings.find((item) => item.skillId === skill.skillId);
                    const isAllowed = !skill.allowedRoles?.length || skill.allowedRoles.includes(activeRole);

                    return (
                      <div
                        key={skill.skillId}
                        className={cn(
                          "flex flex-col justify-between rounded-lg border p-3 transition-all",
                          bound
                            ? "border-primary/50 bg-primary/5 shadow-2xs"
                            : "border-slate-200 bg-white hover:border-slate-300",
                          !isAllowed && "opacity-50 bg-slate-50 cursor-not-allowed"
                        )}
                      >
                        <div className="flex items-start justify-between gap-2">
                          <label className="flex items-start gap-2.5 cursor-pointer min-w-0">
                            <input
                              type="checkbox"
                              checked={Boolean(bound)}
                              disabled={!isAllowed}
                              onChange={(e) => toggleBindingSkill(activeRole, skill.skillId, e.target.checked)}
                              className="mt-0.5 rounded border-slate-300 text-primary focus:ring-primary h-4 w-4"
                            />
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <code className="font-mono text-xs font-semibold text-slate-900">
                                  {skill.skillId}
                                </code>
                                <span className="font-mono text-[10px] text-muted-foreground">
                                  {skill.version || "v1.0"}
                                </span>
                              </div>
                              <div className="mt-1 text-xs text-slate-600 line-clamp-2">
                                {skill.description || "无描述"}
                              </div>
                              {!isAllowed ? (
                                <div className="mt-1 text-[10px] text-rose-600 font-medium">
                                  此 Skill 的允许角色中未包含【{ROLE_META[activeRole].label}】
                                </div>
                              ) : null}
                            </div>
                          </label>
                        </div>

                        {bound ? (
                          <div className="mt-2.5 pt-2 border-t border-primary/10 flex items-center justify-between">
                            <label className="flex items-center gap-1.5 text-xs text-slate-800 cursor-pointer">
                              <input
                                type="checkbox"
                                checked={bound.forceGuide}
                                onChange={(e) => setBindingForceGuide(activeRole, skill.skillId, e.target.checked)}
                                className="rounded border-slate-300 text-amber-600 focus:ring-amber-500 h-3.5 w-3.5"
                              />
                              <span className="font-medium text-amber-900">开启强制引导 (Inject guidePrompt)</span>
                            </label>
                          </div>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {/* 上传 Skill 弹窗 */}
      <Dialog open={uploadOpen} onOpenChange={setUploadOpen}>
        <DialogContent className="max-h-[calc(100vh-2rem)] overflow-y-auto sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <UploadCloud className="h-5 w-5 text-primary" />
              <span>上传 Skill 文件</span>
            </DialogTitle>
            <DialogDescription>
              支持上传 <code>.zip</code> 技能压缩包或单个 <code>SKILL.md</code> 描述文件
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleUploadSubmit} className="space-y-4 pt-2">
            <div>
              <label className="mb-1 block text-xs font-medium text-slate-700">
                文件 (zip / SKILL.md) <span className="text-rose-500">*</span>
              </label>
              <Input
                type="file"
                accept=".zip,.md,text/markdown,application/zip"
                required
                className="text-xs cursor-pointer"
                onChange={(e) => setUploadFile(e.target.files?.[0] || null)}
              />
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-slate-700">版本号（可选）</label>
                <Input
                  value={uploadVersion}
                  onChange={(e) => setUploadVersion(e.target.value)}
                  placeholder="如 1.0.0"
                  className="text-xs font-mono"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-slate-700">风险等级</label>
                <Select value={uploadRisk} onValueChange={(val) => setUploadRisk(val as SkillRiskLevel)}>
                  <SelectTrigger className="text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="LOW">低风险 (LOW)</SelectItem>
                    <SelectItem value="MEDIUM">中风险 (MEDIUM)</SelectItem>
                    <SelectItem value="HIGH">高风险 (HIGH)</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div>
              <label className="mb-1.5 block text-xs font-medium text-slate-700">允许绑定的交付角色</label>
              <div className="grid grid-cols-2 gap-2 rounded-lg border border-slate-200 p-2.5 bg-slate-50/50">
                {SKILL_AGENT_ROLES.map((role) => (
                  <label key={role} className="flex items-center gap-2 text-xs text-slate-800 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={uploadRoles.includes(role)}
                      onChange={(e) => setUploadRoles(toggleRoleInArray(uploadRoles, role, e.target.checked))}
                      className="rounded border-slate-300 text-primary focus:ring-primary h-3.5 w-3.5"
                    />
                    <span>{ROLE_META[role].label}</span>
                  </label>
                ))}
              </div>
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium text-slate-700">
                引导提示词 (guidePrompt) <span className="text-slate-400 font-normal">（选填）</span>
              </label>
              <Textarea
                value={uploadGuidePrompt}
                onChange={(e) => setUploadGuidePrompt(e.target.value)}
                rows={3}
                placeholder="开启强制引导后将自动拼接到 Agent System Prompt"
                className="text-xs"
              />
            </div>

            <label className="flex items-center gap-2 text-xs font-medium text-slate-800 cursor-pointer">
              <input
                type="checkbox"
                checked={uploadForceGuide}
                onChange={(e) => setUploadForceGuide(e.target.checked)}
                className="rounded border-slate-300 text-primary focus:ring-primary h-4 w-4"
              />
              <span>默认开启强制引导</span>
            </label>

            <DialogFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => setUploadOpen(false)}>
                取消
              </Button>
              <Button type="submit" className="admin-primary-gradient" disabled={uploadLoading || !uploadFile}>
                {uploadLoading ? "上传中..." : "确认上传"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* 编辑 Skill 弹窗 */}
      <Dialog open={!!editing} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent className="max-h-[calc(100vh-2rem)] overflow-y-auto sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Pencil className="h-5 w-5 text-primary" />
              <span>编辑 Skill：{editing?.skillId}</span>
            </DialogTitle>
            <DialogDescription>
              更新技能描述、审批状态、允许绑定角色及引导提示词
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleEditSubmit} className="space-y-4 pt-2">
            <div>
              <label className="mb-1 block text-xs font-medium text-slate-700">技能描述</label>
              <Textarea
                value={editDescription}
                onChange={(e) => setEditDescription(e.target.value)}
                rows={3}
                placeholder="简述该 Skill 的用途与触发场景"
                className="text-xs"
              />
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-slate-700">状态</label>
                <Select value={editStatus} onValueChange={(val) => setEditStatus(val as SkillCatalogStatus)}>
                  <SelectTrigger className="text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ACTIVE">已启用 (ACTIVE)</SelectItem>
                    <SelectItem value="WAITING_APPROVAL">待审批 (WAITING_APPROVAL)</SelectItem>
                    <SelectItem value="DISABLED">已停用 (DISABLED)</SelectItem>
                    <SelectItem value="REJECTED">已拒绝 (REJECTED)</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-slate-700">最近更新</label>
                <div className="h-9 flex items-center text-xs text-muted-foreground">
                  {editing?.updatedAt ? <RelativeTime value={editing.updatedAt} /> : "-"}
                </div>
              </div>
            </div>

            <div>
              <label className="mb-1.5 block text-xs font-medium text-slate-700">允许绑定的交付角色</label>
              <div className="grid grid-cols-2 gap-2 rounded-lg border border-slate-200 p-2.5 bg-slate-50/50">
                {SKILL_AGENT_ROLES.map((role) => (
                  <label key={role} className="flex items-center gap-2 text-xs text-slate-800 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={editRoles.includes(role)}
                      onChange={(e) => setEditRoles(toggleRoleInArray(editRoles, role, e.target.checked))}
                      className="rounded border-slate-300 text-primary focus:ring-primary h-3.5 w-3.5"
                    />
                    <span>{ROLE_META[role].label}</span>
                  </label>
                ))}
              </div>
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium text-slate-700">
                引导提示词 (guidePrompt)
              </label>
              <Textarea
                value={editGuidePrompt}
                onChange={(e) => setEditGuidePrompt(e.target.value)}
                rows={3}
                placeholder="开启强制引导后将自动注入"
                className="text-xs"
              />
            </div>

            <label className="flex items-center gap-2 text-xs font-medium text-slate-800 cursor-pointer">
              <input
                type="checkbox"
                checked={editForceGuide}
                onChange={(e) => setEditForceGuide(e.target.checked)}
                className="rounded border-slate-300 text-primary focus:ring-primary h-4 w-4"
              />
              <span>开启强制引导 (追加 guidePrompt，默认仅披露 description)</span>
            </label>

            <DialogFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => setEditing(null)}>
                取消
              </Button>
              <Button type="submit" className="admin-primary-gradient" disabled={editLoading}>
                {editLoading ? "保存中..." : "保存修改"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

