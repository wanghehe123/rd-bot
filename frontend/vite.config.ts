import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import type { IncomingMessage } from "node:http";
import { fileURLToPath, URL } from "node:url";

const backendTarget = "http://127.0.0.1:18080";

export function isHtmlNavigation(request: IncomingMessage): boolean {
  const acceptHeader = request.headers.accept;
  const accept = Array.isArray(acceptHeader) ? acceptHeader.join(",") : acceptHeader || "";
  return request.method === "GET" && accept.includes("text/html");
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
      "/admin/execution-traces": backendTarget,
      "/admin/operations": backendTarget,
      "/admin/rd-tasks": {
        target: backendTarget,
        bypass: (request) => isHtmlNavigation(request) ? request.url : undefined
      },
      "/admin/projects": {
        target: backendTarget,
        bypass: (request) => isHtmlNavigation(request) ? request.url : undefined
      },
      "/admin/rd-task-drafts": backendTarget,
      "/intent-tree": backendTarget,
      "/users": backendTarget,
      "/user": backendTarget,
      "/ingestion": backendTarget,
      "/mappings": backendTarget,
      "/sample-questions": backendTarget,
      "/rag": backendTarget,
      "/conversations": backendTarget,
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
          assetInfo.name?.endsWith(".css") ? "admin-knowledge.css" : "admin-[name][extname]"
      }
    }
  }
});
