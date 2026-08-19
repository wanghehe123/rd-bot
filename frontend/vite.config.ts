import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import type { IncomingMessage } from "node:http";
import { fileURLToPath, URL } from "node:url";

const backendTarget = process.env.RD_BOT_BACKEND_TARGET || "http://127.0.0.1:18080";

export function isHtmlNavigation(request: IncomingMessage): boolean {
  const acceptHeader = request.headers.accept;
  const accept = Array.isArray(acceptHeader) ? acceptHeader.join(",") : acceptHeader || "";
  return request.method === "GET" && accept.includes("text/html");
}

export function isRdTaskSpaNavigation(request: IncomingMessage): boolean {
  if (!isHtmlNavigation(request)) return false;
  const pathname = new URL(request.url || "", "http://localhost").pathname;
  return /^\/admin\/rd-tasks(?:\/[0-9]+)?\/?$/.test(pathname);
}

export function isKnowledgeSpaNavigation(request: IncomingMessage): boolean {
  if (!isHtmlNavigation(request)) return false;
  const pathname = new URL(request.url || "", "http://localhost").pathname;
  return /^\/admin\/knowledge(?:\/[^/]+(?:\/docs\/[^/]+|\/openviking)?)?\/?$/.test(pathname);
}

export default defineConfig({
  base: "/admin/",
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      "/knowledge-base": backendTarget,
      "/admin/overview": backendTarget,
      "/admin/dashboard/overview": backendTarget,
      "/admin/observability/delivery": backendTarget,
      "/admin/model-provider-profiles": backendTarget,
      "/admin/execution-traces": backendTarget,
      "/admin/operations": backendTarget,
      "/admin/rd-tasks": {
        target: backendTarget,
        bypass: (request) => isRdTaskSpaNavigation(request) ? request.url : undefined
      },
      "/admin/projects": {
        target: backendTarget,
        bypass: (request) => isHtmlNavigation(request) ? request.url : undefined
      },
      "/admin/skills": {
        target: backendTarget,
        bypass: (request) => isHtmlNavigation(request) ? request.url : undefined
      },
      "/admin/rd-task-drafts": backendTarget,
      "/admin/rag-retrieval-runs": backendTarget,
      "/admin/ai-reviews": backendTarget,
      "/admin/evaluations": {
        target: backendTarget,
        bypass: (request) => isHtmlNavigation(request) ? request.url : undefined
      },
      "^/admin/knowledge(?:/|$)": {
        target: backendTarget,
        bypass: (request) => isKnowledgeSpaNavigation(request) ? request.url : undefined
      },
      "/admin/knowledge-base": backendTarget,
      "/users": backendTarget,
      "/user": backendTarget,
      "/ingestion": backendTarget,
      "/rag": backendTarget,
      "/test": backendTarget
    }
  },
  build: {
    outDir: "../bootstrap/src/main/resources/static/admin",
    emptyOutDir: true,
    sourcemap: false,
    rollupOptions: {
      output: {
        entryFileNames: "admin-knowledge.js",
        chunkFileNames: "admin-[name].js",
        assetFileNames: (assetInfo) =>
          assetInfo.name?.endsWith(".css") ? "admin-knowledge.css" : "admin-[name][extname]",
        manualChunks(id) {
          if (id.includes("node_modules")) {
            if (
              id.includes("react-markdown") ||
              id.includes("react-syntax-highlighter") ||
              id.includes("remark") ||
              id.includes("rehype") ||
              id.includes("prismjs")
            ) {
              return "vendor-markdown";
            }
            if (id.includes("lucide-react")) {
              return "vendor-icons";
            }
            if (
              id.includes("@radix-ui") ||
              id.includes("clsx") ||
              id.includes("tailwind-merge") ||
              id.includes("class-variance-authority") ||
              id.includes("sonner")
            ) {
              return "vendor-ui";
            }
            if (
              id.includes("react-router-dom") ||
              id.includes("react-router") ||
              id.includes("react") ||
              id.includes("react-dom") ||
              id.includes("scheduler")
            ) {
              return "vendor-react";
            }
          }
        }
      }
    }
  }
});
