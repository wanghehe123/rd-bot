import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  Activity,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Database,
  FlaskConical,
  Github,
  LayoutDashboard,
  ListChecks,
  Menu,
  MessageSquare,
  Plus,
  Settings,
  Sparkles,
  Users,
  Workflow
} from "lucide-react";

import { api } from "../api";
import { Badge, Button, Field, Input } from "./Ui";
import { cn, notify, runAction } from "../utils";

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
      { path: "/admin/projects", label: "项目管理", icon: Github },
      { path: "/admin/rd-tasks", label: "任务管理", icon: ListChecks },
      { path: "/admin/skills", label: "Skill Hub", icon: Sparkles },
      { path: "/admin/traces", label: "执行追踪", icon: Workflow },
      {
        id: "evaluations",
        path: "/admin/evaluations",
        label: "评测",
        icon: FlaskConical,
        children: [
          { path: "/admin/evaluations", label: "本地评测", icon: FlaskConical },
          { path: "/admin/evaluations/coding-benchmarks", label: "编码消融评测", icon: Activity }
        ]
      }
    ]
  },
  {
    title: "设置",
    items: [
      { path: "/admin/users", label: "用户管理", icon: Users },
      { path: "/admin/settings", label: "系统设置", icon: Settings }
    ]
  }
];

const breadcrumbMap: Record<string, string> = {
  dashboard: "Dashboard",
  knowledge: "知识库管理",
  projects: "项目管理",
  "rd-tasks": "任务管理",
  skills: "Skill Hub",
  traces: "执行追踪",
  evaluations: "评测",
  "coding-benchmarks": "编码消融评测",
  users: "用户管理",
  settings: "系统设置"
};

function AdminContentFallback() {
  return (
    <div className="admin-route-fallback" role="status">
      <span className="admin-route-fallback__signal" aria-hidden="true" />
      <span>加载页面...</span>
    </div>
  );
}

export function AdminLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [isMobileViewport, setIsMobileViewport] = useState(() => (
    typeof window !== "undefined" && window.matchMedia("(max-width: 860px)").matches
  ));
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>({ evaluations: true });
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordForm, setPasswordForm] = useState({ currentPassword: "", newPassword: "", confirmPassword: "" });
  const sidebarRef = useRef<HTMLElement>(null);
  const previousMobileSidebarOpenRef = useRef(false);
  const sidebarCompact = collapsed && !mobileSidebarOpen;
  const mobileSidebarHidden = isMobileViewport && !mobileSidebarOpen;
  const mobileSidebarInertProps = mobileSidebarHidden
    ? ({ inert: "" } as Record<string, string>)
    : {};

  const breadcrumbs = useMemo(() => {
    const parts = location.pathname.split("/").filter(Boolean);
    const items: Array<{ label: string; to?: string }> = [{ label: "首页", to: "/admin/dashboard" }];
    if (parts[0] !== "admin") return items;
    const section = parts[1];
    if (section) {
      if (section === "evaluations" && parts.length > 2) {
        items.push({ label: "评测", to: "/admin/evaluations" });
        items.push({ label: breadcrumbMap[parts[2]] || parts[2] });
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
    setMobileSidebarOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    const mobileViewport = window.matchMedia("(max-width: 860px)");
    const syncMobileViewport = () => {
      setIsMobileViewport(mobileViewport.matches);
      if (!mobileViewport.matches) setMobileSidebarOpen(false);
    };
    syncMobileViewport();
    mobileViewport.addEventListener("change", syncMobileViewport);
    return () => mobileViewport.removeEventListener("change", syncMobileViewport);
  }, []);

  useEffect(() => {
    const wasOpen = previousMobileSidebarOpenRef.current;
    previousMobileSidebarOpenRef.current = mobileSidebarOpen;
    if (!isMobileViewport) return;

    const frame = window.requestAnimationFrame(() => {
      if (mobileSidebarOpen) {
        sidebarRef.current
          ?.querySelector<HTMLElement>("a[href], button:not([disabled])")
          ?.focus();
      } else if (wasOpen) {
        document.querySelector<HTMLButtonElement>("[data-admin-mobile-toggle]")?.focus();
      }
    });
    return () => window.cancelAnimationFrame(frame);
  }, [isMobileViewport, mobileSidebarOpen]);

  useEffect(() => {
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        setMobileSidebarOpen(false);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);

  const isLeafActive = (path: string) => location.pathname === path || location.pathname.startsWith(`${path}/`);
  const isGroupActive = (item: MenuItem) => item.children?.some((child) => isLeafActive(child.path)) || false;

  return (
    <div className="admin-layout">
      {mobileSidebarOpen ? (
        <button
          type="button"
          className="admin-sidebar-backdrop"
          aria-label="关闭导航"
          onClick={() => setMobileSidebarOpen(false)}
        />
      ) : null}
      <aside
        ref={sidebarRef}
        id="admin-sidebar"
        aria-hidden={mobileSidebarHidden ? true : undefined}
        {...mobileSidebarInertProps}
        className={cn(
          "admin-sidebar",
          collapsed && "admin-sidebar--truncated",
          mobileSidebarOpen && "admin-sidebar--mobile-open"
        )}
      >
        <div className="admin-sidebar__brand">
          <div className={cn("admin-brand-row", sidebarCompact && "is-collapsed")}>
            <div className="admin-sidebar__logo">RD</div>
            {!sidebarCompact && (
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
              {!sidebarCompact && <p className="admin-sidebar__group-title">{group.title}</p>}
              <div className="admin-sidebar__items">
                {group.items.map((item) => {
                  const Icon = item.icon;
                  if (!item.children) {
                    const active = isLeafActive(item.path);
                    return (
                      <Link
                        key={item.path}
                        to={item.path}
                        title={sidebarCompact ? item.label : undefined}
                        className={cn("admin-sidebar__item", active && "admin-sidebar__item--active", sidebarCompact && "is-collapsed")}
                      >
                        <span className={cn("admin-sidebar__item-indicator", active && "is-active")} />
                        <Icon className="admin-sidebar__item-icon" />
                        {!sidebarCompact && <span>{item.label}</span>}
                      </Link>
                    );
                  }

                  const groupActive = isGroupActive(item);
                  const open = openGroups[item.id || item.label];
                  return (
                    <div key={item.label} className="admin-sidebar__tree">
                      <button
                        type="button"
                        className={cn("admin-sidebar__item", "admin-sidebar__item--group", groupActive && "admin-sidebar__item--group-active", sidebarCompact && "is-collapsed")}
                        onClick={() => setOpenGroups((current) => ({ ...current, [item.id || item.label]: !open }))}
                        title={sidebarCompact ? item.label : undefined}
                      >
                        <span className={cn("admin-sidebar__item-indicator", groupActive && "is-group-active")} />
                        <Icon className="admin-sidebar__item-icon" />
                        {!sidebarCompact && <span className="admin-sidebar__item-label">{item.label}</span>}
                        {!sidebarCompact && (open ? <ChevronDown className="admin-sidebar__chevron" /> : <ChevronRight className="admin-sidebar__chevron" />)}
                      </button>
                      {open && !sidebarCompact && (
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
          <button
            className="admin-sidebar__collapse"
            type="button"
            aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}
            onClick={() => setCollapsed((value) => !value)}
          >
            {collapsed ? <ChevronRight /> : <ChevronLeft />}
            {!collapsed && <span>收起侧边栏</span>}
          </button>
        </div>
      </aside>

      <main className="admin-main">
        <header className="admin-topbar">
          <div className="admin-topbar-inner">
            <div className="admin-topbar-left">
              <Button
                variant="ghost"
                className="admin-mobile-toggle"
                data-admin-mobile-toggle
                onClick={() => setMobileSidebarOpen((value) => !value)}
                aria-label="切换侧边栏"
                aria-controls="admin-sidebar"
                aria-expanded={mobileSidebarOpen}
              >
                <Menu size={18} />
              </Button>
              <div className="admin-topbar-context" aria-label="当前工作区">
                <span className="admin-topbar-context__eyebrow">交付控制台</span>
                <span className="admin-topbar-context__divider" aria-hidden="true" />
                <strong className="admin-topbar-context__page">{breadcrumbs[breadcrumbs.length - 1]?.label || "Dashboard"}</strong>
              </div>
            </div>

            <div className="admin-topbar-actions">
              <Button className="admin-topbar-create-task" onClick={() => navigate("/admin/rd-tasks?create=true")}>
                <Plus size={16} />
                新建任务
              </Button>
              <Button className="admin-topbar-secondary-action" variant="ghost" onClick={() => notify("聊天端尚未迁移到当前单服务后台", "info")}>
                <MessageSquare size={16} />
                返回聊天
              </Button>
              <button type="button" className="admin-star-link admin-topbar-secondary-action" onClick={() => navigate("/admin/projects")}>
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
          <Suspense fallback={<AdminContentFallback />}>
            <Outlet />
          </Suspense>
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
