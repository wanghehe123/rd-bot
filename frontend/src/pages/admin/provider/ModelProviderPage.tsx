import { useEffect, useMemo, useState } from "react";
import {
  AlertCircle,
  AlertTriangle,
  Check,
  CheckCircle2,
  Cpu,
  Eye,
  EyeOff,
  Globe,
  KeyRound,
  Pencil,
  Plus,
  RefreshCw,
  Server,
  ShieldCheck,
  Trash2,
  XCircle
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import { agentRuntimeMutationError } from "../project/agentExecutionProfileForm";
import {
  findDuplicateEnvVars,
  formatEpochMillis,
  metadataError,
  MODEL_PROVIDER_PROTOCOLS,
  PROTOCOL_LABELS
} from "./modelProviderForm";
import {
  listModelProviders,
  putModelProviderCredential,
  upsertModelProvider,
  type ModelProviderMetadataInput,
  type ModelProviderProfile,
  type ModelProviderProtocol
} from "@/services/modelProviderService";

const DEFAULT_MUTATION_TOKEN = "local-agent-runtime";

const INITIAL_DRAFT: ModelProviderMetadataInput & { providerId: string } = {
  providerId: "",
  displayName: "",
  protocol: "OPENAI_CHAT_COMPLETIONS",
  baseUrl: "",
  modelId: "",
  credentialEnvironmentVariable: "OPENCODE_API_KEY",
  authHeader: false,
  enabled: true,
  version: 1
};

export function ModelProviderPage() {
  const [providers, setProviders] = useState<ModelProviderProfile[]>([]);
  const [loading, setLoading] = useState(false);
  const [mutationToken, setMutationToken] = useState("");
  const [showToken, setShowToken] = useState(false);

  // 元数据弹窗状态
  const [metaDialogOpen, setMetaDialogOpen] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [metaSubmitting, setMetaSubmitting] = useState(false);
  const [metaDraft, setMetaDraft] = useState<ModelProviderMetadataInput & { providerId: string }>(INITIAL_DRAFT);
  const [metaFormError, setMetaFormError] = useState("");

  // 密钥弹窗状态
  const [credDialogOpen, setCredDialogOpen] = useState(false);
  const [credSubmitting, setCredSubmitting] = useState(false);
  const [targetProvider, setTargetProvider] = useState<ModelProviderProfile | null>(null);
  const [apiKeyInput, setApiKeyInput] = useState("");
  const [showApiKey, setShowApiKey] = useState(false);

  const duplicateEnvVars = useMemo(() => findDuplicateEnvVars(providers), [providers]);

  const loadData = async () => {
    setLoading(true);
    try {
      const data = await listModelProviders();
      setProviders(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载模型供应商列表失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadData();
  }, []);

  const openCreateDialog = () => {
    setIsEditing(false);
    setMetaDraft({
      ...INITIAL_DRAFT,
      providerId: "",
      displayName: "",
      baseUrl: "",
      modelId: "",
      credentialEnvironmentVariable: "OPENCODE_API_KEY",
      version: 1
    });
    setMetaFormError("");
    setMetaDialogOpen(true);
  };

  const openEditDialog = (provider: ModelProviderProfile) => {
    setIsEditing(true);
    setMetaDraft({
      providerId: provider.providerId,
      displayName: provider.displayName,
      protocol: provider.protocol,
      baseUrl: provider.baseUrl,
      modelId: provider.modelId,
      credentialEnvironmentVariable: provider.credentialEnvironmentVariable,
      authHeader: Boolean(provider.authHeader),
      enabled: provider.enabled,
      version: provider.version
    });
    setMetaFormError("");
    setMetaDialogOpen(true);
  };

  const handleSaveMetadata = async () => {
    if (!mutationToken.trim()) {
      toast.error("请先在上方填入操作令牌（X-RD-Agent-Runtime-Token）");
      return;
    }
    const err = metadataError(metaDraft);
    if (err) {
      setMetaFormError(err);
      toast.error(err);
      return;
    }
    setMetaFormError("");
    setMetaSubmitting(true);
    try {
      await upsertModelProvider(
        metaDraft.providerId,
        {
          displayName: metaDraft.displayName.trim(),
          protocol: metaDraft.protocol,
          baseUrl: metaDraft.baseUrl.trim(),
          modelId: metaDraft.modelId.trim(),
          credentialEnvironmentVariable: metaDraft.credentialEnvironmentVariable.trim(),
          authHeader: metaDraft.authHeader,
          enabled: metaDraft.enabled,
          version: metaDraft.version
        },
        mutationToken.trim()
      );
      toast.success(isEditing ? "供应商元数据已更新" : "供应商已创建");
      setMetaDialogOpen(false);
      await loadData();
    } catch (error) {
      toast.error(agentRuntimeMutationError(error) || getErrorMessage(error, "保存供应商元数据失败"));
    } finally {
      setMetaSubmitting(false);
    }
  };

  const openCredentialDialog = (provider: ModelProviderProfile) => {
    setTargetProvider(provider);
    setApiKeyInput("");
    setShowApiKey(false);
    setCredDialogOpen(true);
  };

  const closeCredentialDialog = () => {
    setApiKeyInput("");
    setShowApiKey(false);
    setTargetProvider(null);
    setCredDialogOpen(false);
  };

  const handleSaveCredential = async (isClearing = false) => {
    if (!targetProvider) return;
    if (!mutationToken.trim()) {
      toast.error("请先在上方填入操作令牌（X-RD-Agent-Runtime-Token）");
      return;
    }

    const keyToSend = isClearing ? "" : apiKeyInput.trim();
    if (!isClearing && !keyToSend) {
      toast.error("请输入 API Key，或点击清除已保存密钥");
      return;
    }

    setCredSubmitting(true);
    try {
      await putModelProviderCredential(targetProvider.providerId, keyToSend, mutationToken.trim());
      toast.success(isClearing ? `已清除 ${targetProvider.displayName || targetProvider.providerId} 的密钥` : `已更新 ${targetProvider.displayName || targetProvider.providerId} 的密钥`);
      closeCredentialDialog();
      await loadData();
    } catch (error) {
      toast.error(agentRuntimeMutationError(error) || getErrorMessage(error, "设置供应商密钥失败"));
    } finally {
      setCredSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* 头部标题与操作 */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-slate-900 sm:text-2xl">供应商配置</h1>
          <p className="mt-1 text-sm text-slate-500">
            按供应商维护模型路由元数据与 API Key 凭据，执行时优先使用已保存密钥，密码永不回传明文。
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => void loadData()}
            disabled={loading}
            className="h-9 gap-1.5 text-xs text-slate-700"
          >
            <RefreshCw className={cn("h-3.5 w-3.5", loading && "animate-spin")} />
            <span>刷新</span>
          </Button>
          <Button
            size="sm"
            onClick={openCreateDialog}
            className="admin-primary-gradient h-9 gap-1.5 text-xs shadow-sm"
          >
            <Plus className="h-4 w-4" />
            <span>新建供应商</span>
          </Button>
        </div>
      </div>

      {/* 操作令牌输入栏 */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 bg-white p-3.5 shadow-sm sm:p-4">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-amber-50 border border-amber-200 text-amber-700">
            <KeyRound className="h-4 w-4" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-slate-800">操作令牌</span>
              <code className="text-[11px] font-mono text-slate-500">X-RD-Agent-Runtime-Token</code>
            </div>
            <div className="text-[11px] text-muted-foreground">创建、修改元数据及写入密钥均需要此令牌鉴权</div>
          </div>
        </div>
        <div className="flex flex-1 items-center justify-end gap-2 sm:max-w-md">
          <div className="relative flex-1">
            <Input
              type={showToken ? "text" : "password"}
              value={mutationToken}
              onChange={(e) => setMutationToken(e.target.value)}
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
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-8 shrink-0 text-xs text-slate-600 hover:text-slate-900"
            onClick={() => setMutationToken(DEFAULT_MUTATION_TOKEN)}
            title="填入本地默认令牌"
          >
            本地默认
          </Button>
        </div>
      </div>

      {/* 环境变量名重名提示横幅 */}
      {duplicateEnvVars.size > 0 && (
        <div className="flex items-start gap-3 rounded-lg border border-amber-200 bg-amber-50/90 p-3.5 text-xs text-amber-900 shadow-sm">
          <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600 mt-0.5" />
          <div className="space-y-0.5 leading-relaxed">
            <span className="font-semibold text-amber-950">存在共用环境变量名的供应商：</span>
            <span>
              环境变量 <code>{[...duplicateEnvVars].join(", ")}</code> 被多个供应商共用。任务执行解析时，将使用对应环境变量名最近一次保存的密钥。
            </span>
          </div>
        </div>
      )}

      {/* 供应商列表表格 */}
      <Card className="shadow-sm">
        <CardHeader className="p-4 sm:p-5 border-b border-slate-100">
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="text-base font-semibold text-slate-900">供应商列表</CardTitle>
              <CardDescription className="text-xs text-slate-500 mt-0.5">
                共 {providers.length} 个模型供应商配置
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {providers.length === 0 && !loading ? (
            <div className="flex flex-col items-center justify-center py-16 px-4 text-center">
              <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-slate-100 text-slate-400">
                <Cpu className="h-6 w-6" />
              </div>
              <h3 className="mt-4 text-sm font-semibold text-slate-800">还没有供应商配置</h3>
              <p className="mt-1.5 max-w-sm text-xs text-slate-500 leading-relaxed">
                还没有供应商。新建一条（例如 providerId=<code>opencode-go</code>，环境变量=<code>OPENCODE_API_KEY</code>），再设置密钥。
              </p>
              <Button size="sm" onClick={openCreateDialog} className="mt-5 gap-1.5 text-xs">
                <Plus className="h-3.5 w-3.5" />
                <span>新建首个供应商</span>
              </Button>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent bg-slate-50/70 border-slate-100">
                    <TableHead className="w-[180px] text-xs font-semibold text-slate-600">显示名 / 标识</TableHead>
                    <TableHead className="w-[160px] text-xs font-semibold text-slate-600">协议</TableHead>
                    <TableHead className="text-xs font-semibold text-slate-600">模型 / Base URL</TableHead>
                    <TableHead className="text-xs font-semibold text-slate-600">环境变量名</TableHead>
                    <TableHead className="w-[110px] text-xs font-semibold text-slate-600">密钥状态</TableHead>
                    <TableHead className="w-[80px] text-xs font-semibold text-slate-600">状态</TableHead>
                    <TableHead className="w-[160px] text-right text-xs font-semibold text-slate-600">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {providers.map((p) => {
                    const isDuplicateEnv = duplicateEnvVars.has(p.credentialEnvironmentVariable.trim());
                    return (
                      <TableRow key={p.providerId} className="hover:bg-slate-50/60 border-slate-100">
                        <TableCell className="align-top py-3.5">
                          <div className="font-medium text-slate-900 text-xs">{p.displayName || p.providerId}</div>
                          <div className="mt-0.5">
                            <code className="text-[11px] font-mono text-slate-500 bg-slate-100 px-1 py-0.5 rounded">
                              {p.providerId}
                            </code>
                          </div>
                        </TableCell>
                        <TableCell className="align-top py-3.5">
                          <Badge variant="outline" className="text-[11px] font-normal text-slate-700 bg-white">
                            {PROTOCOL_LABELS[p.protocol] || p.protocol}
                          </Badge>
                        </TableCell>
                        <TableCell className="align-top py-3.5">
                          <div className="font-mono text-xs text-slate-800 font-medium">{p.modelId}</div>
                          <div className="text-[11px] text-slate-400 truncate max-w-xs mt-0.5" title={p.baseUrl}>
                            {p.baseUrl}
                          </div>
                        </TableCell>
                        <TableCell className="align-top py-3.5">
                          <div className="flex flex-col gap-1">
                            <code className="text-[11px] font-mono text-slate-700 font-semibold">
                              {p.credentialEnvironmentVariable}
                            </code>
                            {isDuplicateEnv && (
                              <span className="inline-flex items-center gap-1 text-[10px] text-amber-700 bg-amber-50 border border-amber-200/80 px-1.5 py-0.5 rounded w-fit">
                                <AlertCircle className="h-3 w-3" />
                                共用环境变量
                              </span>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="align-top py-3.5">
                          {p.credentialConfigured ? (
                            <div className="flex flex-col gap-0.5">
                              <Badge className="bg-emerald-50 text-emerald-700 border-emerald-200 hover:bg-emerald-100 gap-1 w-fit text-[11px]">
                                <Check className="h-3 w-3" />
                                已配置
                              </Badge>
                              {p.credentialUpdatedAt ? (
                                <span className="text-[10px] text-slate-400" title={`更新于 ${formatEpochMillis(p.credentialUpdatedAt)}`}>
                                  {formatEpochMillis(p.credentialUpdatedAt)}
                                </span>
                              ) : null}
                            </div>
                          ) : (
                            <Badge variant="outline" className="bg-amber-50 text-amber-700 border-amber-200 gap-1 w-fit text-[11px]">
                              <XCircle className="h-3 w-3 text-amber-600" />
                              未配置
                            </Badge>
                          )}
                        </TableCell>
                        <TableCell className="align-top py-3.5">
                          {p.enabled ? (
                            <span className="inline-flex items-center gap-1 text-xs font-medium text-emerald-700">
                              <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                              启用
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 text-xs text-slate-400">
                              <span className="h-1.5 w-1.5 rounded-full bg-slate-300" />
                              停用
                            </span>
                          )}
                        </TableCell>
                        <TableCell className="align-top text-right py-3.5">
                          <div className="flex items-center justify-end gap-1.5">
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => openEditDialog(p)}
                              className="h-7 px-2 text-xs text-slate-600 hover:text-slate-900"
                              title="编辑元数据"
                            >
                              <Pencil className="h-3.5 w-3.5 mr-1 text-slate-400" />
                              编辑
                            </Button>
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => openCredentialDialog(p)}
                              className="h-7 px-2 text-xs text-slate-700 hover:bg-slate-50 border-slate-200"
                              title="设置 API Key 密钥"
                            >
                              <KeyRound className="h-3.5 w-3.5 mr-1 text-amber-600" />
                              设置密钥
                            </Button>
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

      {/* 新建 / 编辑元数据 Dialog */}
      <Dialog open={metaDialogOpen} onOpenChange={setMetaDialogOpen}>
        <DialogContent className="sm:max-w-[540px]">
          <DialogHeader>
            <DialogTitle>{isEditing ? "编辑供应商元数据" : "新建供应商"}</DialogTitle>
            <DialogDescription className="text-xs text-slate-500">
              配置供应商的协议、模型标识及对应的环境变量名。注意：元数据接口严禁包含密钥原文。
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-2 text-xs">
            {metaFormError && (
              <div className="flex items-center gap-2 rounded-md border border-rose-200 bg-rose-50 p-2.5 text-xs text-rose-700">
                <AlertCircle className="h-4 w-4 shrink-0" />
                <span>{metaFormError}</span>
              </div>
            )}

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="providerId" className="text-xs font-semibold text-slate-700">
                  供应商标识 (providerId) <span className="text-rose-500">*</span>
                </Label>
                <Input
                  id="providerId"
                  value={metaDraft.providerId}
                  onChange={(e) => setMetaDraft((cur) => ({ ...cur, providerId: e.target.value }))}
                  placeholder="如: opencode-go"
                  disabled={isEditing}
                  className={cn("h-8 text-xs font-mono", isEditing && "bg-slate-50 text-slate-500")}
                />
                <p className="text-[10px] text-muted-foreground">全局唯一标识，保存后不可修改</p>
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="displayName" className="text-xs font-semibold text-slate-700">
                  显示名称 <span className="text-rose-500">*</span>
                </Label>
                <Input
                  id="displayName"
                  value={metaDraft.displayName}
                  onChange={(e) => setMetaDraft((cur) => ({ ...cur, displayName: e.target.value }))}
                  placeholder="如: OpenCode Go"
                  className="h-8 text-xs"
                />
                <p className="text-[10px] text-muted-foreground">用于管理台展示的友好名称</p>
              </div>
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs font-semibold text-slate-700">
                协议类型 <span className="text-rose-500">*</span>
              </Label>
              <Select
                value={metaDraft.protocol}
                onValueChange={(val) => setMetaDraft((cur) => ({ ...cur, protocol: val as ModelProviderProtocol }))}
              >
                <SelectTrigger className="h-8 text-xs">
                  <SelectValue placeholder="选择协议" />
                </SelectTrigger>
                <SelectContent>
                  {MODEL_PROVIDER_PROTOCOLS.map((proto) => (
                    <SelectItem key={proto} value={proto} className="text-xs">
                      {PROTOCOL_LABELS[proto]} ({proto})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="baseUrl" className="text-xs font-semibold text-slate-700">
                Base URL <span className="text-rose-500">*</span>
              </Label>
              <Input
                id="baseUrl"
                value={metaDraft.baseUrl}
                onChange={(e) => setMetaDraft((cur) => ({ ...cur, baseUrl: e.target.value }))}
                placeholder="如: https://opencode.ai/zen/go/v1"
                className="h-8 text-xs font-mono"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="modelId" className="text-xs font-semibold text-slate-700">
                  默认模型 ID <span className="text-rose-500">*</span>
                </Label>
                <Input
                  id="modelId"
                  value={metaDraft.modelId}
                  onChange={(e) => setMetaDraft((cur) => ({ ...cur, modelId: e.target.value }))}
                  placeholder="如: deepseek-v4-flash"
                  className="h-8 text-xs font-mono"
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="credentialEnvironmentVariable" className="text-xs font-semibold text-slate-700">
                  环境变量名 <span className="text-rose-500">*</span>
                </Label>
                <Input
                  id="credentialEnvironmentVariable"
                  value={metaDraft.credentialEnvironmentVariable}
                  onChange={(e) => setMetaDraft((cur) => ({ ...cur, credentialEnvironmentVariable: e.target.value }))}
                  placeholder="如: OPENCODE_API_KEY"
                  className="h-8 text-xs font-mono"
                />
                <p className="text-[10px] text-muted-foreground">须为大写字母、数字和下划线，切勿填入密钥本身</p>
              </div>
            </div>

            <div className="pt-2 flex flex-col gap-2.5 border-t border-slate-100">
              <div className="flex items-center space-x-2">
                <Checkbox
                  id="authHeader"
                  checked={metaDraft.authHeader}
                  onCheckedChange={(checked) => setMetaDraft((cur) => ({ ...cur, authHeader: Boolean(checked) }))}
                />
                <Label htmlFor="authHeader" className="text-xs font-normal text-slate-700 cursor-pointer">
                  使用自定义 Authorization 请求头
                </Label>
              </div>

              <div className="flex items-center space-x-2">
                <Checkbox
                  id="enabled"
                  checked={metaDraft.enabled}
                  onCheckedChange={(checked) => setMetaDraft((cur) => ({ ...cur, enabled: Boolean(checked) }))}
                />
                <Label htmlFor="enabled" className="text-xs font-normal text-slate-700 cursor-pointer">
                  启用该供应商（供 Agent 策略与交付角色选用）
                </Label>
              </div>
            </div>
          </div>

          <DialogFooter className="gap-2 sm:gap-0">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => setMetaDialogOpen(false)}
              disabled={metaSubmitting}
              className="text-xs"
            >
              取消
            </Button>
            <Button
              type="button"
              size="sm"
              onClick={() => void handleSaveMetadata()}
              disabled={metaSubmitting}
              className="admin-primary-gradient text-xs"
            >
              {metaSubmitting ? "保存中..." : "保存元数据"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 设置密钥 Dialog */}
      <Dialog open={credDialogOpen} onOpenChange={(open) => { if (!open) closeCredentialDialog(); }}>
        <DialogContent className="sm:max-w-[480px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <KeyRound className="h-4 w-4 text-amber-600" />
              <span>设置密钥 — {targetProvider?.displayName || targetProvider?.providerId}</span>
            </DialogTitle>
            <DialogDescription className="text-xs text-slate-500">
              为该供应商写入 API Key。密钥在后端隔离存储并持久化，永不向浏览器回显明文。
            </DialogDescription>
          </DialogHeader>

          {targetProvider && (
            <div className="space-y-4 py-2 text-xs">
              <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 space-y-1.5">
                <div className="flex justify-between text-slate-600">
                  <span>供应商标识:</span>
                  <code className="font-mono text-slate-900 font-semibold">{targetProvider.providerId}</code>
                </div>
                <div className="flex justify-between text-slate-600">
                  <span>对应环境变量名:</span>
                  <code className="font-mono text-slate-900 font-semibold">{targetProvider.credentialEnvironmentVariable}</code>
                </div>
                <div className="flex justify-between items-center text-slate-600 pt-1 border-t border-slate-200/60">
                  <span>当前状态:</span>
                  {targetProvider.credentialConfigured ? (
                    <span className="text-emerald-700 font-semibold flex items-center gap-1">
                      <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600" />
                      已配置密钥 {targetProvider.credentialUpdatedAt ? `(${formatEpochMillis(targetProvider.credentialUpdatedAt)})` : ""}
                    </span>
                  ) : (
                    <span className="text-amber-700 font-semibold flex items-center gap-1">
                      <AlertCircle className="h-3.5 w-3.5 text-amber-600" />
                      未配置密钥
                    </span>
                  )}
                </div>
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="apiKeyInput" className="text-xs font-semibold text-slate-700">
                  API Key 密钥原文 <span className="text-rose-500">*</span>
                </Label>
                <div className="relative">
                  <Input
                    id="apiKeyInput"
                    type={showApiKey ? "text" : "password"}
                    value={apiKeyInput}
                    onChange={(e) => setApiKeyInput(e.target.value)}
                    placeholder="请输入供应商 API Key（如 sk-...）"
                    className="h-9 pr-9 font-mono text-xs"
                    autoComplete="off"
                  />
                  <button
                    type="button"
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-700"
                    onClick={() => setShowApiKey(!showApiKey)}
                    title={showApiKey ? "隐藏密钥" : "显示明文"}
                  >
                    {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
                <p className="text-[11px] text-muted-foreground leading-relaxed">
                  提交后输入框将自动清空，页面仅展示是否已配置状态。
                </p>
              </div>
            </div>
          )}

          <DialogFooter className="flex flex-col-reverse sm:flex-row sm:justify-between sm:space-x-2 gap-2 sm:gap-0">
            <div>
              {targetProvider?.credentialConfigured ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => void handleSaveCredential(true)}
                  disabled={credSubmitting}
                  className="text-xs text-rose-600 hover:text-rose-700 hover:bg-rose-50 border-rose-200"
                >
                  <Trash2 className="h-3.5 w-3.5 mr-1" />
                  清除已保存密钥
                </Button>
              ) : null}
            </div>

            <div className="flex items-center justify-end gap-2">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={closeCredentialDialog}
                disabled={credSubmitting}
                className="text-xs"
              >
                取消
              </Button>
              <Button
                type="button"
                size="sm"
                onClick={() => void handleSaveCredential(false)}
                disabled={credSubmitting}
                className="admin-primary-gradient text-xs"
              >
                {credSubmitting ? "提交中..." : "保存密钥"}
              </Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
