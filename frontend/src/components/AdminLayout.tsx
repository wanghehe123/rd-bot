import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ClipboardList,
  Database,
  FileQuestion,
  GitBranch,
  Github,
  KeyRound,
  Layers,
  LayoutDashboard,
  ListChecks,
  Menu,
  MessageSquare,
  Search,
  Settings,
  Upload,
  Users,
  Workflow
} from "lucide-react";

import { api } from "../api";
import { Badge, Button, Field, Input } from "./Ui";
import { cn, notify, recordsOf, runAction } from "../utils";
import type { KnowledgeBase, KnowledgeDocument } from "../types";

type MenuChild = {
  path: string;
  label: string;
  icon: typeof LayoutDashboard;
  search?: string;
};

type MenuItem = {
  id?: string;
  path: string;
  label: string;
  icon: typeof LayoutDashboard;
  children?: MenuChild[];
};

const menuGroups: Array<{ title: string; items: MenuItem[] }> = [
  {
    title: "导航",
    items: [
      { path: "/admin/dashboard", label: "Dashboard", icon: LayoutDashboard },
      { path: "/admin/knowledge", label: "知识库管理", icon: Database },
      {
        id: "intent",
        path: "/admin/intent-tree",
        label: "意图管理",
        icon: Layers,
        children: [
          { path: "/admin/intent-tree", label: "意图树配置", icon: GitBranch },
          { path: "/admin/intent-list", label: "意图列表", icon: ClipboardList }
        ]
      },
      { path: "/admin/ingestion", label: "数据通道", icon: Upload },
      { path: "/admin/projects", label: "项目管理", icon: Github },
      { path: "/admin/rd-tasks", label: "任务管理", icon: ListChecks },
      { path: "/admin/mappings", label: "关键词映射", icon: KeyRound },
      { path: "/admin/traces", label: "链路追踪", icon: Workflow }
    ]
  },
  {
    title: "设置",
    items: [
      { path: "/admin/users", label: "用户管理", icon: Users },
      { path: "/admin/sample-questions", label: "示例问题", icon: FileQuestion },
      { path: "/admin/settings", label: "系统设置", icon: Settings }
    ]
  }
];

const breadcrumbMap: Record<string, string> = {
  dashboard: "Dashboard",
  knowledge: "知识库管理",
  "intent-tree": "意图树配置",
  "intent-list": "意图列表",
  ingestion: "数据通道",
  projects: "项目管理",
  "rd-tasks": "任务管理",
  mappings: "关键词映射",
  traces: "链路追踪",
  users: "用户管理",
  "sample-questions": "示例问题",
  settings: "系统设置"
};

export function AdminLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(false);
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>({ intent: true });
  const [query, setQuery] = useState("");
  const [focused, setFocused] = useState(false);
  const [kbOptions, setKbOptions] = useState<KnowledgeBase[]>([]);
  const [docOptions, setDocOptions] = useState<KnowledgeDocument[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordForm, setPasswordForm] = useState({ currentPassword: "", newPassword: "", confirmPassword: "" });
  const blurRef = useRef<number | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const breadcrumbs = useMemo(() => {
    const parts = location.pathname.split("/").filter(Boolean);
    const items: Array<{ label: string; to?: string }> = [{ label: "首页", to: "/admin/dashboard" }];
    if (parts[0] !== "admin") return items;
    const section = parts[1];
    if (section) {
      if (section === "intent-tree" || section === "intent-list") {
        items.push({ label: "意图管理", to: "/admin/intent-tree" });
        items.push({ label: breadcrumbMap[section] || section });
      } else {
        items.push({ label: breadcrumbMap[section] || section, to: `/admin/${section}` });
      }
    }
    if (section === "knowledge" && parts.length > 2) items.push({ label: "文档管理" });
    if (section === "knowledge" && parts.includes("docs")) items.push({ label: "分块管理" });
    if (section === "traces" && parts.length > 2) items.push({ label: "链路详情" });
    if (section === "rd-tasks" && parts.length > 2) items.push({ label: "任务详情" });
    return items;
  }, [location.pathname]);

  useEffect(() => {
    if (!focused || !query.trim()) {
      setKbOptions([]);
      setDocOptions([]);
      return;
    }
    let active = true;
    const handle = window.setTimeout(() => {
      setSearchLoading(true);
      Promise.all([api.listKnowledgeBases(query, 6), api.searchDocuments(query, 6)])
        .then(([bases, docs]) => {
          if (!active) return;
          setKbOptions(recordsOf(bases));
          setDocOptions(docs);
        })
        .catch(() => {
          if (!active) return;
          setKbOptions([]);
          setDocOptions([]);
        })
        .finally(() => active && setSearchLoading(false));
    }, 180);
    return () => {
      active = false;
      window.clearTimeout(handle);
    };
  }, [focused, query]);

  const handleSearchSelect = (path: string) => {
    inputRef.current?.blur();
    setFocused(false);
    setQuery("");
    navigate(path);
  };

  const handleSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      if (kbOptions[0]) return handleSearchSelect(`/admin/knowledge/${kbOptions[0].id}`);
      if (docOptions[0]) return handleSearchSelect(`/admin/knowledge/${docOptions[0].knowledgeBaseId}/docs/${docOptions[0].id}`);
      if (query.trim()) return handleSearchSelect(`/admin/knowledge?name=${encodeURIComponent(query.trim())}`);
    }
    if (event.key === "Escape") {
      inputRef.current?.blur();
      setFocused(false);
    }
  };

  const isLeafActive = (path: string) => location.pathname === path || location.pathname.startsWith(`${path}/`);
  const isGroupActive = (item: MenuItem) => item.children?.some((child) => isLeafActive(child.path)) || false;

  return (
    <div className="admin-layout">
      <aside className={cn("admin-sidebar", collapsed && "admin-sidebar--collapsed")}>
        <div className="admin-sidebar__brand">
          <div className={cn("admin-brand-row", collapsed && "is-collapsed")}>
            <div className="admin-sidebar__logo">RD</div>
            {!collapsed && (
              <div className="admin-brand-copy">
                <h1 className="admin-sidebar__title">RD-Bot 管理后台</h1>
                <p className="admin-sidebar__subtitle">Delivery Console</p>
              </div>
            )}
          </div>
        </div>

        <nav className="admin-sidebar__nav">
          {menuGroups.map((group) => (
            <div key={group.title} className="admin-sidebar__group">
              {!collapsed && <p className="admin-sidebar__group-title">{group.title}</p>}
              <div className="admin-sidebar__items">
                {group.items.map((item) => {
                  const Icon = item.icon;
                  if (!item.children) {
                    const active = isLeafActive(item.path);
                    return (
                      <Link
                        key={item.path}
                        to={item.path}
                        title={collapsed ? item.label : undefined}
                        className={cn("admin-sidebar__item", active && "admin-sidebar__item--active", collapsed && "is-collapsed")}
                      >
                        <span className={cn("admin-sidebar__item-indicator", active && "is-active")} />
                        <Icon className="admin-sidebar__item-icon" />
                        {!collapsed && <span>{item.label}</span>}
                      </Link>
                    );
                  }

                  const groupActive = isGroupActive(item);
                  const open = openGroups[item.id || item.label];
                  return (
                    <div key={item.label} className="admin-sidebar__tree">
                      <button
                        type="button"
                        className={cn("admin-sidebar__item", "admin-sidebar__item--group", groupActive && "admin-sidebar__item--group-active", collapsed && "is-collapsed")}
                        onClick={() => setOpenGroups((current) => ({ ...current, [item.id || item.label]: !open }))}
                        title={collapsed ? item.label : undefined}
                      >
                        <span className={cn("admin-sidebar__item-indicator", groupActive && "is-group-active")} />
                        <Icon className="admin-sidebar__item-icon" />
                        {!collapsed && <span className="admin-sidebar__item-label">{item.label}</span>}
                        {!collapsed && (open ? <ChevronDown className="admin-sidebar__chevron" /> : <ChevronRight className="admin-sidebar__chevron" />)}
                      </button>
                      {open && !collapsed && (
                        <div className="admin-sidebar__children">
                          {item.children.map((child) => {
                            const ChildIcon = child.icon;
                            const active = isLeafActive(child.path);
                            return (
                              <Link key={child.path} to={`${child.path}${child.search || ""}`} className={cn("admin-sidebar__item", "admin-sidebar__child", active && "admin-sidebar__item--active")}>
                                <span className={cn("admin-sidebar__item-indicator", active && "is-active")} />
                                <ChildIcon className="admin-sidebar__item-icon" />
                                <span>{child.label}</span>
                              </Link>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>

        <div className="admin-sidebar__footer">
          <button className="admin-sidebar__collapse" type="button" onClick={() => setCollapsed((value) => !value)}>
            {collapsed ? <ChevronRight /> : <ChevronLeft />}
            {!collapsed && <span>收起侧边栏</span>}
          </button>
        </div>
      </aside>

      <main className="admin-main">
        <header className="admin-topbar">
          <div className="admin-topbar-inner">
            <div className="admin-topbar-left">
              <Button variant="ghost" className="admin-mobile-toggle" onClick={() => setCollapsed((value) => !value)} aria-label="切换侧边栏">
                <Menu size={18} />
              </Button>
              <div className="admin-topbar-search">
                <Search className="admin-topbar-search-icon" />
                <Input
                  ref={inputRef}
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  onFocus={() => {
                    if (blurRef.current) window.clearTimeout(blurRef.current);
                    setFocused(true);
                  }}
                  onBlur={() => {
                    blurRef.current = window.setTimeout(() => setFocused(false), 160);
                  }}
                  onKeyDown={handleSearchKeyDown}
                  placeholder="搜索知识库 / 文档..."
                />
                <span className="admin-topbar-kbd">Ctrl K</span>
                {focused && query.trim() && (
                  <div className="admin-topbar-suggest" onMouseDown={(event) => event.preventDefault()}>
                    {searchLoading && <div className="admin-topbar-suggest-item muted">搜索中...</div>}
                    {kbOptions.length > 0 && <div className="admin-topbar-suggest-group">知识库</div>}
                    {kbOptions.map((kb) => (
                      <button key={kb.id} type="button" className="admin-topbar-suggest-item" onMouseDown={() => handleSearchSelect(`/admin/knowledge/${kb.id}`)}>
                        <span>{kb.name}</span>
                        <small>{kb.collectionName || "in-memory"}</small>
                      </button>
                    ))}
                    {docOptions.length > 0 && <div className="admin-topbar-suggest-group">文档</div>}
                    {docOptions.map((doc) => (
                      <button key={doc.id} type="button" className="admin-topbar-suggest-item" onMouseDown={() => handleSearchSelect(`/admin/knowledge/${doc.knowledgeBaseId}/docs/${doc.id}`)}>
                        <span>{doc.sourceName}</span>
                        <small>{doc.knowledgeType}</small>
                      </button>
                    ))}
                    {!searchLoading && kbOptions.length === 0 && docOptions.length === 0 && <div className="admin-topbar-suggest-item muted">暂无匹配结果</div>}
                  </div>
                )}
              </div>
            </div>

            <div className="admin-topbar-actions">
              <Button variant="ghost" onClick={() => notify("聊天端尚未迁移到当前单服务后台", "info")}>
                <MessageSquare size={16} />
                返回聊天
              </Button>
              <button type="button" className="admin-star-link" onClick={() => navigate("/admin/projects")}>
                <Github size={16} />
                <span>RD-Bot</span>
                <Badge>Ops</Badge>
              </button>
              <button type="button" className="admin-user-pill" onClick={() => setPasswordOpen(true)}>
                <span className="admin-avatar">RD</span>
                <span>admin</span>
                <ChevronDown size={15} />
              </button>
            </div>
          </div>
        </header>

        <div className="admin-content">
          <nav className="admin-breadcrumbs" aria-label="面包屑">
            {breadcrumbs.map((item, index) => {
              const last = index === breadcrumbs.length - 1;
              return (
                <span key={`${item.label}-${index}`} className="admin-breadcrumb-item">
                  {item.to && !last ? <Link to={item.to}>{item.label}</Link> : <span className={last ? "is-current" : undefined}>{item.label}</span>}
                  {!last && <span>/</span>}
                </span>
              );
            })}
          </nav>
          <Outlet />
        </div>
      </main>

      {passwordOpen && (
        <div className="dialog-backdrop" role="presentation">
          <div className="dialog" role="dialog" aria-modal="true" aria-label="修改密码">
            <div className="dialog-header">
              <h2>修改密码</h2>
              <p>请输入当前密码与新密码</p>
            </div>
            <div className="form-grid one-col">
              <Field label="当前密码">
                <Input type="password" value={passwordForm.currentPassword} onChange={(event) => setPasswordForm((current) => ({ ...current, currentPassword: event.target.value }))} />
              </Field>
              <Field label="新密码">
                <Input type="password" value={passwordForm.newPassword} onChange={(event) => setPasswordForm((current) => ({ ...current, newPassword: event.target.value }))} />
              </Field>
              <Field label="确认新密码">
                <Input type="password" value={passwordForm.confirmPassword} onChange={(event) => setPasswordForm((current) => ({ ...current, confirmPassword: event.target.value }))} />
              </Field>
            </div>
            <div className="dialog-footer">
              <Button variant="ghost" onClick={() => setPasswordOpen(false)}>取消</Button>
              <Button
                variant="primary"
                onClick={() => {
                  if (!passwordForm.currentPassword || !passwordForm.newPassword) return notify("请输入当前密码和新密码", "error");
                  if (passwordForm.newPassword !== passwordForm.confirmPassword) return notify("两次输入的新密码不一致", "error");
                  void runAction(async () => {
                    await api.changePassword(passwordForm);
                    setPasswordOpen(false);
                    setPasswordForm({ currentPassword: "", newPassword: "", confirmPassword: "" });
                  }, "密码已更新");
                }}
              >
                保存
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
