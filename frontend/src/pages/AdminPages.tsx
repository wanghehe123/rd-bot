import { FormEvent, useMemo, useState } from "react";
import {
  AlertCircle,
  Check,
  Cpu,
  Database,
  Eye,
  EyeOff,
  FolderOpen,
  KeyRound,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Settings,
  Shield,
  ShieldAlert,
  ShieldCheck,
  Trash2,
  UploadCloud,
  UserCheck,
  UserPlus,
  Users
} from "lucide-react";
import { toast } from "sonner";

import { api } from "../api";
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
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
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
import { RelativeTime } from "@/components/RelativeTime";
import { useAsyncData } from "../hooks";
import type { AnyRecord, ManagedUser } from "../types";
import { formatTime, recordsOf, runAction, totalOf } from "../utils";
import { cn } from "@/lib/utils";

export function UserListPage() {
  const [keyword, setKeyword] = useState("");
  const [editing, setEditing] = useState<ManagedUser | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ManagedUser | null>(null);
  const [showPassword, setShowPassword] = useState(false);

  // 表单状态
  const [formUsername, setFormUsername] = useState("");
  const [formPassword, setFormPassword] = useState("");
  const [formRole, setFormRole] = useState("user");
  const [formAvatar, setFormAvatar] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const { data, refresh, loading } = useAsyncData(() => api.listUsers(1, keyword), [keyword], { records: [] });
  const users = recordsOf(data);

  const openCreateDialog = () => {
    setEditing(null);
    setFormUsername("");
    setFormPassword("");
    setFormRole("user");
    setFormAvatar("");
    setShowPassword(false);
    setDialogOpen(true);
  };

  const openEditDialog = (user: ManagedUser) => {
    setEditing(user);
    setFormUsername(user.username);
    setFormPassword("");
    setFormRole(user.role || "user");
    setFormAvatar(user.avatar || "");
    setShowPassword(false);
    setDialogOpen(true);
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!formUsername.trim()) {
      toast.error("请输入用户名");
      return;
    }
    if (!editing && !formPassword.trim()) {
      toast.error("创建用户必须设置初始密码");
      return;
    }
    setSubmitting(true);
    const payload = {
      username: formUsername.trim(),
      password: formPassword.trim() || undefined,
      role: formRole,
      avatar: formAvatar.trim() || undefined
    };
    try {
      if (editing) {
        await api.updateUser(editing.id, payload);
        toast.success(`用户 [${editing.username}] 已保存`);
      } else {
        await api.createUser(payload);
        toast.success("用户创建成功");
      }
      setDialogOpen(false);
      await refresh();
    } catch (error) {
      toast.error("操作失败");
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await api.deleteUser(deleteTarget.id);
      toast.success(`用户 [${deleteTarget.username}] 已删除`);
      setDeleteTarget(null);
      await refresh();
    } catch (error) {
      toast.error("删除失败");
    }
  };

  const metrics = useMemo(() => {
    const total = totalOf(data);
    const adminCount = users.filter((u) => u.role === "admin").length;
    const memberCount = total - adminCount;
    return { total, adminCount, memberCount };
  }, [data, users]);

  return (
    <div className="admin-page space-y-4">
      {/* 顶部标题与操作栏 */}
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title flex items-center gap-2.5">
            <Users className="h-6 w-6 text-primary" />
            <span>用户管理</span>
          </h1>
          <p className="admin-page-subtitle">管理系统账号、访问身份与管理权限分配</p>
        </div>
        <div className="admin-page-actions flex items-center gap-2">
          <Button variant="outline" onClick={() => void refresh()} disabled={loading}>
            <RefreshCw className={cn("mr-2 h-4 w-4", loading && "animate-spin")} />
            刷新
          </Button>
          <Button className="admin-primary-gradient gap-1.5 shadow-sm" onClick={openCreateDialog}>
            <UserPlus className="h-4 w-4" />
            <span>新建用户</span>
          </Button>
        </div>
      </div>

      {/* 态势看板卡片 */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <div className="flex items-center justify-between rounded-lg border border-slate-200 bg-white p-3.5 shadow-sm">
          <div>
            <div className="text-xs text-muted-foreground">用户总数</div>
            <div className="text-lg font-bold text-slate-900">{metrics.total} <span className="text-xs font-normal text-slate-400">人</span></div>
          </div>
          <Users className="h-5 w-5 text-slate-400" />
        </div>
        <div className="flex items-center justify-between rounded-lg border border-emerald-200 bg-emerald-50/40 p-3.5 shadow-sm">
          <div>
            <div className="text-xs font-medium text-emerald-800">管理员账号</div>
            <div className="text-lg font-bold text-emerald-900">{metrics.adminCount}</div>
          </div>
          <ShieldCheck className="h-5 w-5 text-emerald-600" />
        </div>
        <div className="flex items-center justify-between rounded-lg border border-indigo-200 bg-indigo-50/40 p-3.5 shadow-sm">
          <div>
            <div className="text-xs font-medium text-indigo-800">普通成员</div>
            <div className="text-lg font-bold text-indigo-900">{metrics.memberCount}</div>
          </div>
          <UserCheck className="h-5 w-5 text-indigo-600" />
        </div>
      </div>

      {/* 用户列表卡片 */}
      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <CardTitle className="text-sm font-semibold">用户账号列表</CardTitle>
              <CardDescription className="text-xs">查看系统用户基本信息、角色与最后更新时间</CardDescription>
            </div>
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" />
              <Input
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="搜索用户名或角色"
                className="h-8 w-[220px] pl-8 text-xs"
              />
            </div>
          </div>
        </CardHeader>
        <CardContent className="pt-0">
          {loading ? (
            <div className="py-12 text-center text-xs text-muted-foreground">加载中...</div>
          ) : users.length === 0 ? (
            <div className="py-12 text-center text-xs text-muted-foreground">暂无匹配的用户。</div>
          ) : (
            <div className="overflow-x-auto">
              <Table className="min-w-[800px] table-fixed">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[240px]">用户账号</TableHead>
                    <TableHead className="w-[120px]">角色权限</TableHead>
                    <TableHead className="w-[160px]">创建时间</TableHead>
                    <TableHead className="w-[160px]">更新时间</TableHead>
                    <TableHead className="w-[140px] text-left">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {users.map((user) => {
                    const protectedAdmin = user.username === "admin";
                    const isAdmin = user.role === "admin";

                    return (
                      <TableRow key={user.id} className="hover:bg-slate-50/80">
                        <TableCell className="font-medium">
                          <div className="flex items-center gap-2.5">
                            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-100 font-semibold text-slate-700 border border-slate-200 text-xs">
                              {user.username.slice(0, 1).toUpperCase()}
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <span className="font-semibold text-slate-900 text-xs">{user.username}</span>
                                {protectedAdmin ? (
                                  <Badge variant="outline" className="border-amber-200 bg-amber-50 text-[10px] text-amber-700 px-1 py-0">
                                    默认超管
                                  </Badge>
                                ) : null}
                              </div>
                              {user.avatar ? (
                                <div className="text-[10px] text-muted-foreground truncate max-w-[160px]">{user.avatar}</div>
                              ) : null}
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>
                          <Badge
                            variant="outline"
                            className={cn(
                              "font-medium text-xs gap-1",
                              isAdmin
                                ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                                : "border-slate-200 bg-slate-50 text-slate-600"
                            )}
                          >
                            {isAdmin ? <ShieldCheck className="h-3 w-3" /> : <UserCheck className="h-3 w-3" />}
                            <span>{isAdmin ? "管理员" : "成员"}</span>
                          </Badge>
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {formatTime(user.createTime)}
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {formatTime(user.updateTime)}
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-1.5">
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-7 gap-1 px-2 text-xs font-medium text-slate-700 hover:text-slate-950"
                              disabled={protectedAdmin}
                              onClick={() => openEditDialog(user)}
                            >
                              <Pencil className="h-3 w-3 text-slate-500" />
                              <span>编辑</span>
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              className="h-7 gap-1 px-2 text-xs text-destructive hover:bg-destructive/10 hover:text-destructive"
                              disabled={protectedAdmin}
                              onClick={() => setDeleteTarget(user)}
                            >
                              <Trash2 className="h-3 w-3" />
                              <span>删除</span>
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

      {/* 创建 / 编辑用户弹窗 */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-[460px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              {editing ? <Pencil className="h-5 w-5 text-primary" /> : <UserPlus className="h-5 w-5 text-primary" />}
              <span>{editing ? `编辑用户：${editing.username}` : "新建用户"}</span>
            </DialogTitle>
            <DialogDescription>
              {editing ? "修改用户角色权限或重置登录密码" : "填写账号基本信息与分配访问角色"}
            </DialogDescription>
          </DialogHeader>

          <form onSubmit={handleSubmit} className="space-y-3.5 pt-2">
            <div>
              <label className="mb-1 block text-xs font-medium text-slate-700">
                用户名 <span className="text-rose-500">*</span>
              </label>
              <Input
                value={formUsername}
                onChange={(e) => setFormUsername(e.target.value)}
                placeholder="请输入用户名"
                required
                className="text-xs"
              />
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium text-slate-700">
                密码 {editing ? <span className="text-slate-400 font-normal">（留空则保持原密码）</span> : <span className="text-rose-500">*</span>}
              </label>
              <div className="relative">
                <Input
                  type={showPassword ? "text" : "password"}
                  value={formPassword}
                  onChange={(e) => setFormPassword(e.target.value)}
                  placeholder={editing ? "输入新密码或留空" : "请输入初始密码"}
                  required={!editing}
                  className="pr-8 text-xs font-mono"
                />
                <button
                  type="button"
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-700"
                  onClick={() => setShowPassword(!showPassword)}
                  title={showPassword ? "隐藏密码" : "显示密码"}
                >
                  {showPassword ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                </button>
              </div>
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium text-slate-700">角色权限</label>
              <Select value={formRole} onValueChange={setFormRole}>
                <SelectTrigger className="text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="user">普通成员 (Member)</SelectItem>
                  <SelectItem value="admin">系统管理员 (Admin)</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium text-slate-700">
                头像 URL <span className="text-slate-400 font-normal">（可选）</span>
              </label>
              <Input
                value={formAvatar}
                onChange={(e) => setFormAvatar(e.target.value)}
                placeholder="https://..."
                className="text-xs"
              />
            </div>

            <DialogFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
                取消
              </Button>
              <Button type="submit" className="admin-primary-gradient" disabled={submitting}>
                {submitting ? "提交中..." : editing ? "保存修改" : "确认创建"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* 删除确认 AlertDialog */}
      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除用户？</AlertDialogTitle>
            <AlertDialogDescription>
              确定要删除用户 [{deleteTarget?.username}] 吗？此操作无法撤销。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground">
              确认删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

export function SettingsPage() {
  const { data, refresh, loading } = useAsyncData(() => api.settings(), [], {} as AnyRecord);

  const formatBytes = (bytes?: number) => {
    if (!bytes && bytes !== 0) return "-";
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
  };

  return (
    <div className="admin-page space-y-4">
      {/* 顶部标题栏 */}
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title flex items-center gap-2.5">
            <Settings className="h-6 w-6 text-primary" />
            <span>系统设置</span>
          </h1>
          <p className="admin-page-subtitle">查看系统 RAG 引擎、上传尺寸限制、向量存储与 AI Provider 配置概览</p>
        </div>
        <div className="admin-page-actions flex items-center gap-2">
          <Button variant="outline" onClick={() => void refresh()} disabled={loading}>
            <RefreshCw className={cn("mr-2 h-4 w-4", loading && "animate-spin")} />
            刷新
          </Button>
        </div>
      </div>

      {/* 设置内容网格 */}
      <div className="grid gap-4 md:grid-cols-2">
        {/* 上传限制 */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold flex items-center gap-2">
              <UploadCloud className="h-4 w-4 text-primary" />
              <span>文件与请求上传限制</span>
            </CardTitle>
            <CardDescription className="text-xs">Spring Boot 多部分上传参数限制</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 pt-0 text-xs">
            <div className="flex items-center justify-between rounded-md border border-slate-100 bg-slate-50 p-2.5">
              <span className="text-slate-600">单文件最大限制 (maxFileSize)</span>
              <code className="font-mono font-semibold text-slate-900">{formatBytes(data.upload?.maxFileSize)} ({data.upload?.maxFileSize ?? "-"} B)</code>
            </div>
            <div className="flex items-center justify-between rounded-md border border-slate-100 bg-slate-50 p-2.5">
              <span className="text-slate-600">单请求最大限制 (maxRequestSize)</span>
              <code className="font-mono font-semibold text-slate-900">{formatBytes(data.upload?.maxRequestSize)} ({data.upload?.maxRequestSize ?? "-"} B)</code>
            </div>
          </CardContent>
        </Card>

        {/* 向量与 RAG 配置 */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold flex items-center gap-2">
              <Database className="h-4 w-4 text-indigo-600" />
              <span>默认向量库配置</span>
            </CardTitle>
            <CardDescription className="text-xs">RAG 索引集合与向量维度参数</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 pt-0 text-xs">
            <div className="flex items-center justify-between rounded-md border border-slate-100 bg-slate-50 p-2.5">
              <span className="text-slate-600">集合名称 (Collection)</span>
              <code className="font-mono font-semibold text-slate-900">{data.rag?.default?.collectionName || "-"}</code>
            </div>
            <div className="flex items-center justify-between rounded-md border border-slate-100 bg-slate-50 p-2.5">
              <span className="text-slate-600">向量维度 (Dimension)</span>
              <code className="font-mono font-semibold text-slate-900">{data.rag?.default?.dimension || "-"}</code>
            </div>
            <div className="flex items-center justify-between rounded-md border border-slate-100 bg-slate-50 p-2.5">
              <span className="text-slate-600">相似度度量 (Metric Type)</span>
              <code className="font-mono font-semibold text-slate-900">{data.rag?.default?.metricType || "-"}</code>
            </div>
          </CardContent>
        </Card>

        {/* AI Provider 配置详情 */}
        <Card className="md:col-span-2">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold flex items-center gap-2">
              <Cpu className="h-4 w-4 text-emerald-600" />
              <span>AI Provider 配置概览</span>
            </CardTitle>
            <CardDescription className="text-xs">当前系统已载入的模型 Provider 状态元数据</CardDescription>
          </CardHeader>
          <CardContent className="pt-0">
            <pre className="rounded-lg border border-slate-200 bg-slate-950 p-4 font-mono text-xs text-slate-100 overflow-x-auto">
              {JSON.stringify(data.ai || {}, null, 2)}
            </pre>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

