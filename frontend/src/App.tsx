import { lazy, Suspense } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { Toaster } from "sonner";

import { AdminLayout } from "./components/AdminLayout";
import { ToastHost } from "./components/ToastHost";

const DashboardPage = lazy(() => import("./pages/DashboardPage").then((module) => ({ default: module.DashboardPage })));
const KnowledgeListPage = lazy(() => import("@/pages/admin/knowledge/KnowledgeListPage").then((module) => ({ default: module.KnowledgeListPage })));
const KnowledgeDocumentsPage = lazy(() => import("@/pages/admin/knowledge/KnowledgeDocumentsPage").then((module) => ({ default: module.KnowledgeDocumentsPage })));
const KnowledgeChunksPage = lazy(() => import("@/pages/admin/knowledge/KnowledgeChunksPage").then((module) => ({ default: module.KnowledgeChunksPage })));
const ProjectListPage = lazy(() => import("@/pages/admin/project/ProjectListPage").then((module) => ({ default: module.ProjectListPage })));
const RdTaskListPage = lazy(() => import("@/pages/admin/rdtask/RdTaskListPage").then((module) => ({ default: module.RdTaskListPage })));
const RdTaskDetailPage = lazy(() => import("@/pages/admin/rdtask/RdTaskDetailPage").then((module) => ({ default: module.RdTaskDetailPage })));
const IntentTreePage = lazy(() => import("./pages/IntentPages").then((module) => ({ default: module.IntentTreePage })));
const IntentListPage = lazy(() => import("./pages/IntentPages").then((module) => ({ default: module.IntentListPage })));
const UserListPage = lazy(() => import("./pages/AdminPages").then((module) => ({ default: module.UserListPage })));
const IngestionPage = lazy(() => import("./pages/AdminPages").then((module) => ({ default: module.IngestionPage })));
const RetrievalRulesPage = lazy(() => import("@/pages/admin/retrieval/RetrievalRulesPage").then((module) => ({ default: module.RetrievalRulesPage })));
const ExecutionTracePage = lazy(() => import("@/pages/admin/trace/ExecutionTracePage").then((module) => ({ default: module.ExecutionTracePage })));
const ExecutionTraceDetailPage = lazy(() => import("@/pages/admin/trace/ExecutionTracePage").then((module) => ({ default: module.ExecutionTraceDetailPage })));
const EvaluationPage = lazy(() => import("@/pages/admin/evaluation/EvaluationPage").then((module) => ({ default: module.EvaluationPage })));
const CodingBenchmarkPage = lazy(() => import("@/pages/admin/evaluation/CodingBenchmarkPage").then((module) => ({ default: module.CodingBenchmarkPage })));
const CodingBenchmarkTrialDetailPage = lazy(() => import("@/pages/admin/evaluation/CodingBenchmarkTrialDetailPage").then((module) => ({ default: module.CodingBenchmarkTrialDetailPage })));
const SettingsPage = lazy(() => import("./pages/AdminPages").then((module) => ({ default: module.SettingsPage })));

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
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Suspense fallback={<AdminRouteFallback />}>
        <Routes>
          <Route path="/" element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="/admin" element={<AdminLayout />}>
            <Route index element={<Navigate to="/admin/dashboard" replace />} />
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="knowledge" element={<KnowledgeListPage />} />
            <Route path="knowledge/:kbId" element={<KnowledgeDocumentsPage />} />
            <Route path="knowledge/:kbId/docs/:docId" element={<KnowledgeChunksPage />} />
            <Route path="projects" element={<ProjectListPage />} />
            <Route path="rd-tasks" element={<RdTaskListPage />} />
            <Route path="rd-tasks/:taskId" element={<RdTaskDetailPage />} />
            <Route path="intent-tree" element={<IntentTreePage />} />
            <Route path="intent-list" element={<IntentListPage />} />
            <Route path="users" element={<UserListPage />} />
            <Route path="ingestion" element={<IngestionPage />} />
            <Route path="mappings" element={<RetrievalRulesPage />} />
            <Route path="traces" element={<ExecutionTracePage />} />
            <Route path="traces/:taskId" element={<ExecutionTraceDetailPage />} />
            <Route path="evaluations" element={<EvaluationPage />} />
            <Route path="evaluations/coding-benchmarks" element={<CodingBenchmarkPage />} />
            <Route path="evaluations/coding-benchmarks/campaigns/:runId/trials/:trialId" element={<CodingBenchmarkTrialDetailPage />} />
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
