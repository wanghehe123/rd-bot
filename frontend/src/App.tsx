import { lazy, Suspense } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { Toaster } from "sonner";

import { AdminLayout } from "./components/AdminLayout";
import { ToastHost } from "./components/ToastHost";

const DashboardPage = lazy(() => import("./pages/DashboardPage").then((module) => ({ default: module.DashboardPage })));
const KnowledgeListPage = lazy(() => import("@/pages/admin/knowledge/KnowledgeListPage").then((module) => ({ default: module.KnowledgeListPage })));
const KnowledgeDocumentsPage = lazy(() => import("@/pages/admin/knowledge/KnowledgeDocumentsPage").then((module) => ({ default: module.KnowledgeDocumentsPage })));
const KnowledgeChunksPage = lazy(() => import("@/pages/admin/knowledge/KnowledgeChunksPage").then((module) => ({ default: module.KnowledgeChunksPage })));
const OpenVikingKnowledgePage = lazy(() => import("@/pages/admin/knowledge/OpenVikingKnowledgePage").then((module) => ({ default: module.OpenVikingKnowledgePage })));
const ProjectListPage = lazy(() => import("@/pages/admin/project/ProjectListPage").then((module) => ({ default: module.ProjectListPage })));
const AgentStrategyPage = lazy(() => import("@/pages/admin/project/AgentStrategyPage").then((module) => ({ default: module.AgentStrategyPage })));
const ProjectMemoryPage = lazy(() => import("@/pages/admin/project/ProjectMemoryPage").then((module) => ({ default: module.ProjectMemoryPage })));
const RdTaskListPage = lazy(() => import("@/pages/admin/rdtask/RdTaskListPage").then((module) => ({ default: module.RdTaskListPage })));
const RdTaskDetailPage = lazy(() => import("@/pages/admin/rdtask/RdTaskDetailPage").then((module) => ({ default: module.RdTaskDetailPage })));
const UserListPage = lazy(() => import("./pages/AdminPages").then((module) => ({ default: module.UserListPage })));
const SkillHubPage = lazy(() => import("@/pages/admin/skill/SkillHubPage").then((module) => ({ default: module.SkillHubPage })));
const ExecutionTracePage = lazy(() => import("@/pages/admin/trace/ExecutionTracePage").then((module) => ({ default: module.ExecutionTracePage })));
const ExecutionTraceDetailPage = lazy(() => import("@/pages/admin/trace/ExecutionTracePage").then((module) => ({ default: module.ExecutionTraceDetailPage })));
const SettingsPage = lazy(() => import("./pages/AdminPages").then((module) => ({ default: module.SettingsPage })));
const DeliveryObservabilityPage = lazy(() => import("@/pages/admin/observability/DeliveryObservabilityPage").then((module) => ({ default: module.DeliveryObservabilityPage })));
const ModelProviderPage = lazy(() => import("@/pages/admin/provider/ModelProviderPage").then((module) => ({ default: module.ModelProviderPage })));

function AdminRouteFallback() {
  return (
    <div className="admin-route-fallback" role="status">
      <span className="admin-route-fallback__signal" aria-hidden="true" />
      <span>加载页面...</span>
    </div>
  );
}

export function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<AdminRouteFallback />}>
        <Routes>
          <Route path="/" element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="/admin" element={<AdminLayout />}>
            <Route index element={<Navigate to="/admin/dashboard" replace />} />
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="knowledge" element={<KnowledgeListPage />} />
            <Route path="knowledge/:kbId" element={<KnowledgeDocumentsPage />} />
            <Route path="knowledge/:kbId/openviking" element={<OpenVikingKnowledgePage />} />
            <Route path="knowledge/:kbId/docs/:docId" element={<KnowledgeChunksPage />} />
            <Route path="projects" element={<ProjectListPage />} />
            <Route path="projects/:projectId/agent-strategy" element={<AgentStrategyPage />} />
            <Route path="projects/:projectId/memories" element={<ProjectMemoryPage />} />
            <Route path="rd-tasks" element={<RdTaskListPage />} />
            <Route path="rd-tasks/:taskId" element={<RdTaskDetailPage />} />
            <Route path="users" element={<UserListPage />} />
            <Route path="skills" element={<SkillHubPage />} />
            <Route path="traces" element={<ExecutionTracePage />} />
            <Route path="traces/:taskId" element={<ExecutionTraceDetailPage />} />
            <Route path="observability" element={<DeliveryObservabilityPage />} />
            <Route path="model-providers" element={<ModelProviderPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/admin/dashboard" replace />} />
        </Routes>
      </Suspense>
      <Toaster richColors position="top-right" closeButton />
      <ToastHost />
    </BrowserRouter>
  );
}
