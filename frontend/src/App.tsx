import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";

import { AdminLayout } from "./components/AdminLayout";
import { ToastHost } from "./components/ToastHost";
import { DashboardPage } from "./pages/DashboardPage";
import { KnowledgeListPage } from "@/pages/admin/knowledge/KnowledgeListPage";
import { KnowledgeDocumentsPage } from "@/pages/admin/knowledge/KnowledgeDocumentsPage";
import { KnowledgeChunksPage } from "@/pages/admin/knowledge/KnowledgeChunksPage";
import { ProjectListPage } from "@/pages/admin/project/ProjectListPage";
import { RdTaskListPage } from "@/pages/admin/rdtask/RdTaskListPage";
import { RdTaskDetailPage } from "@/pages/admin/rdtask/RdTaskDetailPage";
import { IntentListPage, IntentTreePage } from "./pages/IntentPages";
import {
  IngestionPage,
  MappingPage,
  SampleQuestionPage,
  SettingsPage,
  TraceDetailPage,
  TracePage,
  UserListPage
} from "./pages/AdminPages";

export function App() {
  return (
    <BrowserRouter>
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
          <Route path="mappings" element={<MappingPage />} />
          <Route path="traces" element={<TracePage />} />
          <Route path="traces/:traceId" element={<TraceDetailPage />} />
          <Route path="sample-questions" element={<SampleQuestionPage />} />
          <Route path="settings" element={<SettingsPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/admin/dashboard" replace />} />
      </Routes>
      <ToastHost />
    </BrowserRouter>
  );
}
