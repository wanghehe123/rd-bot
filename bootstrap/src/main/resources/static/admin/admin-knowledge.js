(function () {
  const app = document.getElementById("app");
  const state = {
    keyword: "",
    bases: [],
    overview: null,
    kb: null,
    docs: [],
    doc: null,
    chunks: [],
    logs: [],
    preview: "",
    selectedChunks: new Set(),
    tree: [],
    selectedIntentId: null,
    intentParentCode: "",
    intentDraftMode: false,
    selectedIntentIds: new Set(),
    intentFilters: { keyword: "", level: "all", status: "all" },
    usersPage: { records: [], total: 0, size: 10, current: 1, pages: 0 },
    userKeyword: "",
    editingUserId: "",
    loading: false,
    error: ""
  };

  const escapeHtml = (value) => String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");

  const attr = escapeHtml;
  const text = (value) => escapeHtml(value ?? "");
  const boolEnabled = (value) => value === true || value === 1 || value === undefined || value === null;
  const truncate = (value, max = 100) => {
    const raw = String(value ?? "");
    return raw.length > max ? `${raw.slice(0, max)}...` : raw;
  };
  const nowText = () => new Date().toLocaleString("zh-CN", { hour12: false });

  async function request(path, options = {}) {
    const headers = options.body instanceof FormData ? {} : { "Content-Type": "application/json" };
    const response = await fetch(path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
    const contentType = response.headers.get("content-type") || "";
    const payload = contentType.includes("application/json") ? await response.json() : await response.text();
    if (!response.ok) {
      const message = typeof payload === "object" && payload?.message ? payload.message : response.statusText;
      throw new Error(message || "请求失败");
    }
    return payload;
  }

  const api = {
    overview: () => request("/admin/overview"),
    listBases: (name = "") => request(`/knowledge-base?current=1&size=100&name=${encodeURIComponent(name)}`),
    createBase: (payload) => request("/knowledge-base", { method: "POST", body: JSON.stringify(payload) }),
    getBase: (id) => request(`/knowledge-base/${encodeURIComponent(id)}`),
    updateBase: (id, payload) => request(`/knowledge-base/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(payload) }),
    deleteBase: (id) => request(`/knowledge-base/${encodeURIComponent(id)}`, { method: "DELETE" }),
    listDocs: (kbId) => request(`/knowledge-base/${encodeURIComponent(kbId)}/docs`),
    writeDoc: (kbId, payload) => request(`/knowledge-base/${encodeURIComponent(kbId)}/docs/write`, { method: "POST", body: JSON.stringify(payload) }),
    uploadDoc: (formData) => request("/ingestion/tasks/upload", { method: "POST", body: formData }),
    getDoc: (id) => request(`/knowledge-base/docs/${encodeURIComponent(id)}`),
    updateDoc: (id, payload) => request(`/knowledge-base/docs/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(payload) }),
    deleteDoc: (id) => request(`/knowledge-base/docs/${encodeURIComponent(id)}`, { method: "DELETE" }),
    setDocEnabled: (id, enabled) => request(`/knowledge-base/docs/${encodeURIComponent(id)}/enable?value=${enabled}`, { method: "PATCH", body: "null" }),
    previewDoc: (id) => request(`/knowledge-base/docs/${encodeURIComponent(id)}/preview`),
    listLogs: (id) => request(`/knowledge-base/docs/${encodeURIComponent(id)}/chunk-logs`),
    listChunks: (id) => request(`/knowledge-base/docs/${encodeURIComponent(id)}/chunks`),
    createChunk: (docId, payload) => request(`/knowledge-base/docs/${encodeURIComponent(docId)}/chunks`, { method: "POST", body: JSON.stringify(payload) }),
    updateChunk: (docId, chunkId, payload) => request(`/knowledge-base/docs/${encodeURIComponent(docId)}/chunks/${encodeURIComponent(chunkId)}`, { method: "PUT", body: JSON.stringify(payload) }),
    deleteChunk: (docId, chunkId) => request(`/knowledge-base/docs/${encodeURIComponent(docId)}/chunks/${encodeURIComponent(chunkId)}`, { method: "DELETE" }),
    setChunkEnabled: (docId, chunkId, enabled) => request(`/knowledge-base/docs/${encodeURIComponent(docId)}/chunks/${encodeURIComponent(chunkId)}/enable?value=${enabled}`, { method: "PATCH", body: "null" }),
    batchChunks: (docId, chunkIds, enabled) => request(`/knowledge-base/docs/${encodeURIComponent(docId)}/chunks/batch-enable?value=${enabled}`, { method: "PATCH", body: JSON.stringify({ chunkIds }) }),
    tree: () => request("/intent-tree/trees"),
    createIntent: (payload) => request("/intent-tree", { method: "POST", body: JSON.stringify(payload) }),
    updateIntent: (id, payload) => request(`/intent-tree/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(payload) }),
    deleteIntent: (id) => request(`/intent-tree/${encodeURIComponent(id)}`, { method: "DELETE" }),
    batchIntent: (action, ids) => request(`/intent-tree/batch/${action}`, { method: "POST", body: JSON.stringify({ ids }) }),
    users: (current = 1, keyword = "") => request(`/users?current=${current}&size=10&keyword=${encodeURIComponent(keyword)}`),
    createUser: (payload) => request("/users", { method: "POST", body: JSON.stringify(payload) }),
    updateUser: (id, payload) => request(`/users/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(payload) }),
    deleteUser: (id) => request(`/users/${encodeURIComponent(id)}`, { method: "DELETE" })
  };

  function route() {
    const parts = window.location.pathname.split("/").filter(Boolean);
    if (parts[0] !== "admin" || !parts[1]) return { name: "dashboard" };
    if (parts[1] === "dashboard") return { name: "dashboard" };
    if (parts[1] === "intent-tree") return { name: "intentTree" };
    if (parts[1] === "intent-list") return { name: "intentList" };
    if (parts[1] === "users") return { name: "users" };
    if (parts[1] === "knowledge") {
      if (parts.length >= 5 && parts[3] === "docs") return { name: "chunks", kbId: parts[2], docId: parts[4] };
      if (parts.length >= 3) return { name: "docs", kbId: parts[2] };
      return { name: "knowledge" };
    }
    return { name: "dashboard" };
  }

  function navigate(path) {
    window.history.pushState({}, "", path);
    load();
  }

  function pageMeta() {
    const current = route();
    if (current.name === "dashboard") return ["Dashboard", "核心指标、流量趋势和知识库健康状态"];
    if (current.name === "knowledge") return ["知识库管理", "管理知识库、文档上传、切分与检索素材"];
    if (current.name === "docs") return ["文档管理", `${state.kb?.name || current.kbId} · 写入、上传、预览和启停文档`];
    if (current.name === "chunks") return ["分块管理", `${state.doc?.sourceName || current.docId} · 手动维护 Chunk 与启停状态`];
    if (current.name === "intentTree") return ["意图树配置", "配置意图层级、父子关系、知识库绑定和示例问题"];
    if (current.name === "intentList") return ["意图列表", "筛选、批量启停和批量删除意图节点"];
    if (current.name === "users") return ["用户管理", "管理本地后台账号与角色权限"];
    return ["Dashboard", ""];
  }

  function active(name) {
    return route().name === name;
  }

  function activeIntentGroup() {
    return active("intentTree") || active("intentList");
  }

  function shell(content) {
    const [title, subtitle] = pageMeta();
    return `
      <aside class="sidebar">
        <div class="brand">
          <div class="brand-mark">R</div>
          <div>
            <div class="brand-title">Ragent 管理后台</div>
            <div class="brand-subtitle">Knowledge Console</div>
          </div>
        </div>
        <nav class="nav">
          <div class="nav-group">
            <p class="nav-title">导航</p>
            ${navLink("Dashboard", "/admin/dashboard", "dashboard", "▦")}
            ${navLink("知识库管理", "/admin/knowledge", "knowledge", "◉")}
            <button class="nav-parent ${activeIntentGroup() ? "active" : ""}" data-nav="/admin/intent-tree">
              <span class="nav-icon">▧</span><span>意图管理</span><span class="nav-caret">⌄</span>
            </button>
            <div class="nav-child-wrap">
              ${navChild("意图树配置", "/admin/intent-tree", "intentTree")}
              ${navChild("意图列表", "/admin/intent-list", "intentList")}
            </div>
          </div>
          <div class="nav-group">
            <p class="nav-title">设置</p>
            ${navLink("用户管理", "/admin/users", "users", "◎")}
            ${disabledNav("示例问题", "◌")}
            ${disabledNav("系统设置", "⚙")}
          </div>
        </nav>
        <div class="sidebar-footer">« 收起侧边栏</div>
      </aside>
      <main class="main">
        <header class="topbar">
          <div class="global-search">
            <span class="search-icon">⌕</span>
            <input id="global-search" placeholder="筛选知识库..." value="${attr(state.keyword)}" />
            <span class="shortcut">Ctrl K</span>
          </div>
          <div class="topbar-actions">
            <button class="top-pill" id="back-chat">□ 返回聊天</button>
            <button class="top-pill">Star <span class="badge">--</span></button>
            <button class="profile-pill"><span class="profile-avatar">R</span> admin⌄</button>
          </div>
        </header>
        <section class="page">
          <div class="crumbs">${breadcrumbs()}</div>
          <div class="page-title-row">
            <div>
              <h1 class="page-title">${text(title)}</h1>
              <p class="page-subtitle">${text(subtitle)}</p>
            </div>
            <div class="page-actions">
              <span class="badge ok">运行正常</span>
              <span class="badge">${text(nowText())}</span>
              <button class="btn compact" id="refresh-btn">刷新</button>
            </div>
          </div>
          ${content}
        </section>
      </main>
      ${state.error ? `<div class="toast error">${text(state.error)}</div>` : ""}
    `;
  }

  function navLink(label, path, name, icon) {
    return `<button class="nav-link ${active(name) ? "active" : ""}" data-nav="${attr(path)}"><span class="nav-icon">${icon}</span><span>${text(label)}</span><span></span></button>`;
  }

  function navChild(label, path, name) {
    return `<button class="nav-child ${active(name) ? "active" : ""}" data-nav="${attr(path)}">${text(label)}</button>`;
  }

  function disabledNav(label, icon) {
    return `<button class="nav-link" disabled><span class="nav-icon">${icon}</span><span>${text(label)}</span><span></span></button>`;
  }

  function breadcrumbs() {
    const current = route();
    if (current.name === "dashboard") return "首页 / Dashboard";
    if (current.name === "knowledge") return "首页 / 知识库管理";
    if (current.name === "docs") return `首页 / 知识库管理 / ${text(current.kbId)}`;
    if (current.name === "chunks") return `首页 / 知识库管理 / ${text(current.kbId)} / ${text(current.docId)}`;
    if (current.name === "intentTree") return "首页 / 意图管理 / 意图树配置";
    if (current.name === "intentList") return "首页 / 意图管理 / 意图列表";
    if (current.name === "users") return "首页 / 设置 / 用户管理";
    return "首页";
  }

  function metric(label, value, trend, icon, tone = "") {
    return `
      <div class="metric-card">
        <div>
          <div class="metric-value">${text(value)}</div>
          <div class="metric-label">${text(label)}</div>
          <div class="metric-trend">${text(trend || "--")}</div>
        </div>
        <div class="metric-icon ${tone}">${icon}</div>
      </div>
    `;
  }

  function renderDashboard() {
    const overview = state.overview || {};
    const bases = state.bases || [];
    const users = state.usersPage?.records || [];
    const flat = flattenTree(state.tree);
    const docCount = overview.documentCount ?? bases.reduce((sum, kb) => sum + Number(kb.documentCount || 0), 0);
    const chunkCount = overview.chunkCount ?? 0;
    return shell(`
      <div class="dashboard-grid">
        <div class="stack">
          <div class="card">
            <div class="card-head"><div><div class="card-title">核心指标</div></div></div>
            <div class="card-body">
              <div class="metric-grid">
                ${metric("活跃用户", users.length || 1, "--", "⌁", "violet")}
                ${metric("知识库", bases.length, "+ 本地", "□", "violet")}
                ${metric("文档数", docCount, "+ 索引", "↯", "amber")}
                ${metric("Chunk 数", chunkCount, "+ 向量", "▥", "green")}
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-head"><div><div class="card-title">流量概览</div><div class="card-subtitle">基于当前 MVP 内存状态生成的后台运营曲线</div></div></div>
            <div class="card-body">${lineChart()}</div>
          </div>
          <div class="card">
            <div class="card-head"><div><div class="card-title">趋势分析</div></div></div>
            <div class="card-body two-grid">
              ${miniTrend("会话趋势", [7, 3, 8, 8, 2, 3], "#2f6eff")}
              ${miniTrend("意图节点趋势", [flat.length || 1, 1, flat.length || 1, flat.length || 1, flat.length || 1], "#22b86f")}
            </div>
          </div>
        </div>
        <div class="stack">
          <div class="card">
            <div class="card-head"><div class="card-title">AI 性能</div><span class="status-ok">运行正常</span></div>
            <div class="card-body">
              <div class="ai-score"><div class="ring">100.0%<small>成功率</small></div></div>
              <div class="kv-list">
                <div class="kv"><span>平均响应</span><strong>7.91s</strong></div>
                <div class="kv"><span>P95 响应</span><strong style="color:var(--red)">25.56s</strong></div>
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-head"><div><div class="card-title">质量快照（柱状）</div><div class="card-subtitle">近 7 天</div></div></div>
            <div class="card-body">
              <div class="quality-grid">
                ${quality("错误率", "0.0%", "red")}
                ${quality("无知识率", "17.5%", "amber")}
                ${quality("慢响应率", "9.1%", "blue")}
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-head"><div class="card-title">运营效率</div><span class="card-subtitle">近 7 天</span></div>
            <div class="card-body kv-list">
              <div class="kv"><span>人均会话</span><strong>24.00 次/人</strong></div>
              <div class="kv"><span>单会话消息</span><strong>5.25 条/会话</strong></div>
              <div class="kv"><span>意图节点</span><strong>${flat.length} 个</strong></div>
            </div>
          </div>
        </div>
      </div>
    `);
  }

  function lineChart() {
    return `
      <svg class="chart" viewBox="0 0 900 260" role="img" aria-label="流量概览">
        <defs>
          <linearGradient id="area-blue" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="#2f6eff" stop-opacity="0.28" />
            <stop offset="100%" stop-color="#2f6eff" stop-opacity="0.02" />
          </linearGradient>
        </defs>
        <g stroke="#e8eef7" stroke-dasharray="3 5">
          <line x1="36" y1="35" x2="870" y2="35" />
          <line x1="36" y1="90" x2="870" y2="90" />
          <line x1="36" y1="145" x2="870" y2="145" />
          <line x1="36" y1="200" x2="870" y2="200" />
        </g>
        <path d="M36 135 C110 134 130 224 178 214 C236 202 232 30 298 34 C360 38 365 135 430 145 C510 157 552 185 638 136 C704 99 754 118 794 171 C820 206 852 178 870 166 L870 230 L36 230 Z" fill="url(#area-blue)" />
        <path d="M36 135 C110 134 130 224 178 214 C236 202 232 30 298 34 C360 38 365 135 430 145 C510 157 552 185 638 136 C704 99 754 118 794 171 C820 206 852 178 870 166" fill="none" stroke="#2f6eff" stroke-width="3" />
        <g fill="#94a3b8" font-size="12">
          <text x="22" y="39">44</text><text x="22" y="94">29</text><text x="22" y="149">15</text><text x="28" y="204">0</text>
          <text x="36" y="248">02/01</text><text x="250" y="248">02/02</text><text x="465" y="248">02/03</text><text x="680" y="248">02/04</text><text x="846" y="248">02/05</text>
        </g>
      </svg>
    `;
  }

  function miniTrend(title, values, color) {
    const points = values.map((v, index) => `${50 + index * 85},${110 - Math.min(85, Number(v) * 8)}`).join(" ");
    return `
      <div class="quality-card" style="text-align:left;padding:18px">
        <div class="card-title">${text(title)}</div>
        <div class="card-subtitle">单位：次</div>
        <svg viewBox="0 0 480 130" style="width:100%;height:150px;margin-top:10px">
          <polyline points="${points}" fill="none" stroke="${color}" stroke-width="4" stroke-linecap="round" />
          <polyline points="${points} 475,120 50,120" fill="${color}" opacity="0.09" />
        </svg>
      </div>
    `;
  }

  function quality(label, value, tone) {
    return `<div class="quality-card"><div class="bar-frame"><div class="bar ${tone}"></div></div><div style="margin-top:9px;font-weight:850;color:${tone === "red" ? "var(--red)" : tone === "amber" ? "var(--amber)" : "#2397db"}">${text(value)}</div><div class="card-subtitle">${text(label)}</div></div>`;
  }

  function renderKnowledgePage() {
    const bases = state.bases || [];
    const docCount = bases.reduce((sum, kb) => sum + Number(kb.documentCount || 0), 0);
    return shell(`
      <div class="card">
        <div class="card-head">
          <div><div class="card-title">知识库列表</div><div class="card-subtitle">共 ${bases.length} 个知识库，${docCount} 篇文档</div></div>
          <div class="page-actions">
            <input id="kb-search" style="width:220px" value="${attr(state.keyword)}" placeholder="搜索知识库名称" />
            <button class="btn" id="kb-search-btn">搜索</button>
          </div>
        </div>
        <div class="card-body">
          <div class="content-grid">
            <div class="table-wrap">${knowledgeTable(bases)}</div>
            <form id="create-kb-form" class="card-body" style="padding:0">
              <div class="form-grid">
                <div class="field full"><label>名称</label><input id="kb-name" required placeholder="支付系统" /></div>
                <div class="field full"><label>描述</label><textarea id="kb-desc" placeholder="支付接口、订单异常和修复经验"></textarea></div>
                <button class="btn primary" type="submit">创建知识库</button>
              </div>
            </form>
          </div>
        </div>
      </div>
    `);
  }

  function knowledgeTable(bases) {
    if (!bases.length) return `<div class="empty">暂无知识库，可以从右侧创建。</div>`;
    return `
      <table>
        <thead><tr><th>名称</th><th>描述</th><th>文档数</th><th>Collection</th><th>操作</th></tr></thead>
        <tbody>
          ${bases.map((kb) => `
            <tr>
              <td><button class="link-button" data-open-kb="${attr(kb.id)}">${text(kb.name)}</button></td>
              <td class="truncate">${text(kb.description || "-")}</td>
              <td>${Number(kb.documentCount || 0)}</td>
              <td><span class="badge">${text(kb.collectionName || "in-memory")}</span></td>
              <td><div class="inline-actions">
                <button class="btn compact" data-open-kb="${attr(kb.id)}">文档</button>
                <button class="btn compact" data-rename-kb="${attr(kb.id)}">重命名</button>
                <button class="btn compact danger" data-delete-kb="${attr(kb.id)}">删除</button>
              </div></td>
            </tr>
          `).join("")}
        </tbody>
      </table>
    `;
  }

  function renderDocsPage() {
    const docs = state.docs || [];
    return shell(`
      <div class="content-grid">
        <div class="card">
          <div class="card-head"><div><div class="card-title">文档列表</div><div class="card-subtitle">${docs.length} 篇文档</div></div><button class="btn" data-nav="/admin/knowledge">返回知识库</button></div>
          <div class="card-body table-wrap">${docsTable(docs)}</div>
        </div>
        <div class="stack">
          <div class="card">
            <div class="card-head"><div class="card-title">写入文本 / Markdown</div></div>
            <div class="card-body">
              <form id="write-doc-form" class="form-grid">
                <div class="field"><label>文档名</label><input id="doc-name" required value="payment-api.md" /></div>
                <div class="field"><label>知识类型</label><input id="doc-type" required value="api" /></div>
                <div class="field full"><label>内容</label><textarea id="doc-content" required># 支付 API\n\nOrderService.create 必须校验 orders.amount。</textarea></div>
                <button class="btn primary" type="submit">写入并切分</button>
              </form>
            </div>
          </div>
          <div class="card">
            <div class="card-head"><div class="card-title">文件上传到 RustFS</div></div>
            <div class="card-body">
              <form id="upload-doc-form" class="form-grid">
                <div class="field full"><label>Markdown / Text 文件</label><input id="doc-file" type="file" accept=".md,.txt,text/markdown,text/plain" /></div>
                <button class="btn" type="submit">上传并执行流水线</button>
              </form>
            </div>
          </div>
        </div>
      </div>
      ${state.preview ? `<div class="card" style="margin-top:18px"><div class="card-head"><div class="card-title">文档预览</div><button class="btn compact" id="close-preview-btn">关闭</button></div><div class="card-body"><pre class="preview-box">${text(state.preview)}</pre></div></div>` : ""}
    `);
  }

  function docsTable(docs) {
    if (!docs.length) return `<div class="empty">暂无文档，可以从右侧写入或上传。</div>`;
    return `
      <table>
        <thead><tr><th>文档</th><th>类型</th><th>状态</th><th>Chunk</th><th>启用</th><th>操作</th></tr></thead>
        <tbody>${docs.map((doc) => `
          <tr>
            <td><button class="link-button" data-open-doc="${attr(doc.id)}">${text(doc.sourceName || doc.docName || doc.id)}</button></td>
            <td><span class="badge">${text(doc.knowledgeType || "text")}</span></td>
            <td>${text(doc.status || "-")}</td>
            <td>${Number(doc.chunkCount || 0)}</td>
            <td><span class="badge ${doc.enabled !== false ? "ok" : "warn"}">${doc.enabled !== false ? "启用" : "禁用"}</span></td>
            <td><div class="inline-actions">
              <button class="btn compact" data-open-doc="${attr(doc.id)}">分块</button>
              <button class="btn compact" data-preview-doc="${attr(doc.id)}">预览</button>
              <button class="btn compact" data-rename-doc="${attr(doc.id)}">编辑</button>
              <button class="btn compact" data-toggle-doc="${attr(doc.id)}">${doc.enabled !== false ? "禁用" : "启用"}</button>
              <button class="btn compact danger" data-delete-doc="${attr(doc.id)}">删除</button>
            </div></td>
          </tr>`).join("")}</tbody>
      </table>
    `;
  }

  function renderChunksPage() {
    const chunks = state.chunks || [];
    return shell(`
      <div class="card">
        <div class="card-head">
          <div><div class="card-title">Chunk 列表</div><div class="card-subtitle">${chunks.length} 个分块，${chunks.filter(boolEnabled).length} 个启用</div></div>
          <div class="inline-actions">
            <button class="btn compact" id="batch-enable-btn" ${state.selectedChunks.size ? "" : "disabled"}>批量启用</button>
            <button class="btn compact" id="batch-disable-btn" ${state.selectedChunks.size ? "" : "disabled"}>批量禁用</button>
            <button class="btn" data-nav="/admin/knowledge/${attr(route().kbId)}">返回文档</button>
          </div>
        </div>
        <div class="card-body table-wrap">${chunksTable(chunks)}</div>
      </div>
      <div class="content-grid" style="margin-top:18px">
        <div class="card">
          <div class="card-head"><div class="card-title">新建 Chunk</div></div>
          <div class="card-body">
            <form id="create-chunk-form" class="form-grid">
              <div class="field"><label>序号</label><input id="chunk-index" type="number" min="0" value="${chunks.length}" /></div>
              <div class="field"><label>Chunk ID</label><input id="chunk-id" placeholder="留空自动生成" /></div>
              <div class="field full"><label>内容</label><textarea id="chunk-content" required></textarea></div>
              <button class="btn primary" type="submit">新增 Chunk</button>
            </form>
          </div>
        </div>
        <div class="card"><div class="card-head"><div class="card-title">切分日志</div></div><div class="card-body"><pre class="log-box">${text(JSON.stringify(state.logs || [], null, 2))}</pre></div></div>
      </div>
    `);
  }

  function chunksTable(chunks) {
    if (!chunks.length) return `<div class="empty">暂无分块，可以从下方手动新增。</div>`;
    const allSelected = state.selectedChunks.size === chunks.length;
    return `
      <table>
        <thead><tr><th><input type="checkbox" id="select-all-chunks" ${allSelected ? "checked" : ""} /></th><th>序号</th><th>内容</th><th>状态</th><th>字符</th><th>操作</th></tr></thead>
        <tbody>${chunks.map((chunk) => `
          <tr>
            <td><input type="checkbox" data-select-chunk="${attr(chunk.id)}" ${state.selectedChunks.has(String(chunk.id)) ? "checked" : ""} /></td>
            <td>${chunk.index ?? chunk.chunkIndex ?? "-"}</td>
            <td class="truncate" title="${attr(chunk.content || "")}">${text(truncate(chunk.content, 150))}</td>
            <td><span class="badge ${boolEnabled(chunk.enabled) ? "ok" : "warn"}">${boolEnabled(chunk.enabled) ? "启用" : "禁用"}</span></td>
            <td>${String(chunk.content || "").length}</td>
            <td><div class="inline-actions">
              <button class="btn compact" data-edit-chunk="${attr(chunk.id)}">编辑</button>
              <button class="btn compact" data-toggle-chunk="${attr(chunk.id)}">${boolEnabled(chunk.enabled) ? "禁用" : "启用"}</button>
              <button class="btn compact danger" data-delete-chunk="${attr(chunk.id)}">删除</button>
            </div></td>
          </tr>`).join("")}</tbody>
      </table>
    `;
  }

  function renderIntentTreePage() {
    const flat = flattenTree(state.tree);
    if (!state.intentDraftMode && !state.selectedIntentId && flat[0]) {
      state.selectedIntentId = flat[0].id;
    }
    const editing = !state.intentDraftMode && state.selectedIntentId
      ? flat.find((node) => node.id === state.selectedIntentId)
      : null;
    return shell(`
      <div class="content-grid">
        <div class="card">
          <div class="card-head"><div><div class="card-title">意图树结构</div><div class="card-subtitle">点击节点查看详情或编辑</div></div><button class="btn primary" id="intent-new-root">新建根节点</button></div>
          <div class="card-body"><div class="tree">${state.tree.length ? renderTreeRows(state.tree) : `<div class="empty">暂无节点，请先创建。</div>`}</div></div>
        </div>
        <div class="card">
          <div class="card-head"><div><div class="card-title">${editing ? "节点详情" : "新建节点"}</div><div class="card-subtitle">${editing ? text(editing.intentCode) : "创建新的意图节点"}</div></div></div>
          <div class="card-body">${intentForm(editing, flat)}</div>
        </div>
      </div>
    `);
  }

  function renderTreeRows(nodes, depth = 0) {
    return nodes.map((node) => {
      const isActive = node.id === state.selectedIntentId;
      return `
        <div class="tree-row ${isActive ? "active" : ""}" style="padding-left:${depth * 18 + 10}px">
          <button class="link-button tree-main" data-select-intent="${attr(node.id)}">
            <span class="tree-name">${text(node.name)}</span>
            <span class="badge">${levelLabel(node.level)}</span>
            <span class="tree-code">${text(node.intentCode)}</span>
          </button>
          <div class="inline-actions">
            <button class="btn compact" data-new-child="${attr(node.intentCode)}">子节点</button>
            <button class="btn compact danger" data-delete-intent="${attr(node.id)}">删除</button>
          </div>
        </div>
        ${node.children?.length ? renderTreeRows(node.children, depth + 1) : ""}
      `;
    }).join("");
  }

  function intentForm(node, flat) {
    const examples = Array.isArray(node?.examples) ? node.examples.join("\n") : "";
    const parentCode = node ? node.parentCode || "" : state.intentParentCode || "";
    return `
      <form id="intent-node-form" class="form-grid">
        <input type="hidden" id="intent-id" value="${attr(node?.id || "")}" />
        <div class="field"><label>节点名称</label><input id="intent-name" required value="${attr(node?.name || "")}" /></div>
        <div class="field"><label>意图标识</label><input id="intent-code" ${node ? "disabled" : "required"} value="${attr(node?.intentCode || "")}" placeholder="payment-system" /></div>
        <div class="field"><label>层级</label><select id="intent-level">${[0, 1, 2].map((level) => `<option value="${level}" ${(node?.level ?? 0) === level ? "selected" : ""}>${levelLabel(level)}</option>`).join("")}</select></div>
        <div class="field"><label>父节点</label><select id="intent-parent"><option value="">ROOT</option>${flat.filter((item) => item.id !== node?.id).map((item) => `<option value="${attr(item.intentCode)}" ${parentCode === item.intentCode ? "selected" : ""}>${text(item.pathText)}</option>`).join("")}</select></div>
        <div class="field"><label>知识库 ID</label><input id="intent-kb" value="${attr(node?.kbId || "")}" placeholder="可选，默认使用 intentCode" /></div>
        <div class="field"><label>启用状态</label><select id="intent-enabled"><option value="1" ${node?.enabled !== 0 ? "selected" : ""}>启用</option><option value="0" ${node?.enabled === 0 ? "selected" : ""}>停用</option></select></div>
        <div class="field full"><label>描述</label><textarea id="intent-desc">${text(node?.description || "")}</textarea></div>
        <div class="field full"><label>示例问题（每行一个）</label><textarea id="intent-examples">${text(examples)}</textarea></div>
        <div class="inline-actions">
          <button class="btn primary" type="submit">${node ? "保存节点" : "创建节点"}</button>
          <button class="btn" type="button" id="intent-clear-form">清空为新建</button>
          ${node ? `<button class="btn" type="button" data-new-child="${attr(node.intentCode)}">创建子节点</button>` : ""}
        </div>
      </form>
    `;
  }

  function renderIntentListPage() {
    const rows = flattenTree(state.tree);
    const filtered = rows.filter((row) => {
      const keyword = state.intentFilters.keyword.trim().toLowerCase();
      if (keyword && ![row.name, row.intentCode, row.pathText].join(" ").toLowerCase().includes(keyword)) return false;
      if (state.intentFilters.level !== "all" && String(row.level) !== state.intentFilters.level) return false;
      if (state.intentFilters.status === "enabled" && row.enabled === 0) return false;
      if (state.intentFilters.status === "disabled" && row.enabled !== 0) return false;
      return true;
    });
    return shell(`
      <div class="card">
        <div class="card-head">
          <div><div class="card-title">意图节点列表</div><div class="card-subtitle">${filtered.length} / ${rows.length} 个节点</div></div>
          <div class="page-actions">
            <input id="intent-filter-keyword" style="width:220px" placeholder="搜索名称/标识" value="${attr(state.intentFilters.keyword)}" />
            <select id="intent-filter-level"><option value="all">全部层级</option>${[0, 1, 2].map((level) => `<option value="${level}" ${state.intentFilters.level === String(level) ? "selected" : ""}>${levelLabel(level)}</option>`).join("")}</select>
            <select id="intent-filter-status"><option value="all">全部状态</option><option value="enabled" ${state.intentFilters.status === "enabled" ? "selected" : ""}>启用</option><option value="disabled" ${state.intentFilters.status === "disabled" ? "selected" : ""}>停用</option></select>
            <button class="btn" id="intent-apply-filter">筛选</button>
            <button class="btn compact" data-batch-intent="enable" ${state.selectedIntentIds.size ? "" : "disabled"}>批量启用</button>
            <button class="btn compact" data-batch-intent="disable" ${state.selectedIntentIds.size ? "" : "disabled"}>批量停用</button>
            <button class="btn compact danger" data-batch-intent="delete" ${state.selectedIntentIds.size ? "" : "disabled"}>批量删除</button>
          </div>
        </div>
        <div class="card-body table-wrap">${intentTable(filtered)}</div>
      </div>
    `);
  }

  function intentTable(rows) {
    if (!rows.length) return `<div class="empty">暂无匹配意图节点。</div>`;
    const allSelected = rows.length > 0 && rows.every((row) => state.selectedIntentIds.has(String(row.id)));
    return `
      <table>
        <thead><tr><th><input type="checkbox" id="select-all-intents" ${allSelected ? "checked" : ""} /></th><th>名称</th><th>路径</th><th>层级</th><th>状态</th><th>示例</th><th>操作</th></tr></thead>
        <tbody>${rows.map((row) => `
          <tr>
            <td><input type="checkbox" data-select-intent-row="${attr(row.id)}" ${state.selectedIntentIds.has(String(row.id)) ? "checked" : ""} /></td>
            <td><button class="link-button" data-nav="/admin/intent-tree?intentCode=${encodeURIComponent(row.intentCode)}">${text(row.name)}</button><div class="tree-code">${text(row.intentCode)}</div></td>
            <td class="truncate">${text(row.pathText)}</td>
            <td><span class="badge">${levelLabel(row.level)}</span></td>
            <td><span class="badge ${row.enabled === 0 ? "warn" : "ok"}">${row.enabled === 0 ? "停用" : "启用"}</span></td>
            <td>${row.exampleCount}</td>
            <td><div class="inline-actions"><button class="btn compact" data-nav="/admin/intent-tree?intentCode=${encodeURIComponent(row.intentCode)}">编辑</button><button class="btn compact danger" data-delete-intent="${attr(row.id)}">删除</button></div></td>
          </tr>`).join("")}</tbody>
      </table>
    `;
  }

  function renderUsersPage() {
    const page = state.usersPage || { records: [], total: 0, current: 1, pages: 0 };
    const editing = page.records.find((user) => user.id === state.editingUserId) || null;
    return shell(`
      <div class="content-grid">
        <div class="card">
          <div class="card-head">
            <div><div class="card-title">用户列表</div><div class="card-subtitle">共 ${page.total} 个账号</div></div>
            <div class="page-actions">
              <input id="user-keyword" style="width:220px" placeholder="搜索用户名或角色" value="${attr(state.userKeyword)}" />
              <button class="btn" id="user-search-btn">搜索</button>
            </div>
          </div>
          <div class="card-body table-wrap">${usersTable(page.records || [])}</div>
          <div class="card-body" style="padding-top:0"><div class="page-actions"><span class="card-subtitle">${page.current || 1} / ${page.pages || 1}</span><button class="btn compact" id="user-prev" ${(page.current || 1) <= 1 ? "disabled" : ""}>上一页</button><button class="btn compact" id="user-next" ${(page.current || 1) >= (page.pages || 1) ? "disabled" : ""}>下一页</button></div></div>
        </div>
        <div class="card">
          <div class="card-head"><div><div class="card-title">${editing ? "编辑用户" : "新增用户"}</div><div class="card-subtitle">${editing ? "密码留空则不修改" : "创建本地后台账号"}</div></div></div>
          <div class="card-body">
            <form id="user-form" class="form-grid">
              <input type="hidden" id="user-id" value="${attr(editing?.id || "")}" />
              <div class="field full"><label>用户名</label><input id="user-name" required value="${attr(editing?.username || "")}" /></div>
              <div class="field full"><label>密码</label><input id="user-password" type="password" ${editing ? "" : "required"} placeholder="${editing ? "留空则不修改" : "设置初始密码"}" /></div>
              <div class="field full"><label>角色</label><select id="user-role"><option value="user" ${editing?.role !== "admin" ? "selected" : ""}>成员</option><option value="admin" ${editing?.role === "admin" ? "selected" : ""}>管理员</option></select></div>
              <div class="field full"><label>头像 URL</label><input id="user-avatar" value="${attr(editing?.avatar || "")}" placeholder="可选" /></div>
              <div class="inline-actions"><button class="btn primary" type="submit">${editing ? "保存用户" : "创建用户"}</button><button class="btn" type="button" id="user-clear-form">清空</button></div>
            </form>
          </div>
        </div>
      </div>
    `);
  }

  function usersTable(users) {
    if (!users.length) return `<div class="empty">暂无用户。</div>`;
    return `
      <table>
        <thead><tr><th>用户</th><th>角色</th><th>创建时间</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>${users.map((user) => {
          const protectedAdmin = user.username === "admin";
          return `
            <tr>
              <td><div style="display:flex;align-items:center;gap:10px"><span class="profile-avatar">${text((user.username || "U").slice(0, 1).toUpperCase())}</span><div><strong>${text(user.username)}</strong>${protectedAdmin ? `<div class="tree-code">默认管理员</div>` : ""}</div></div></td>
              <td><span class="badge ${user.role === "admin" ? "ok" : ""}">${user.role === "admin" ? "管理员" : "成员"}</span></td>
              <td>${text(formatTime(user.createTime))}</td>
              <td>${text(formatTime(user.updateTime))}</td>
              <td><div class="inline-actions"><button class="btn compact" data-edit-user="${attr(user.id)}" ${protectedAdmin ? "disabled" : ""}>编辑</button><button class="btn compact danger" data-delete-user="${attr(user.id)}" ${protectedAdmin ? "disabled" : ""}>删除</button></div></td>
            </tr>
          `;
        }).join("")}</tbody>
      </table>
    `;
  }

  function flattenTree(nodes, parents = []) {
    const result = [];
    (nodes || []).forEach((node) => {
      const path = [...parents, node.name || node.intentCode];
      result.push({
        ...node,
        pathText: path.join(" > "),
        exampleCount: Array.isArray(node.examples) ? node.examples.length : 0
      });
      result.push(...flattenTree(node.children || [], path));
    });
    return result;
  }

  function levelLabel(level) {
    if (Number(level) === 0) return "DOMAIN";
    if (Number(level) === 1) return "CATEGORY";
    return "TOPIC";
  }

  function formatTime(value) {
    if (!value) return "-";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString("zh-CN", { hour12: false });
  }

  async function load() {
    state.error = "";
    try {
      const current = route();
      if (current.name === "dashboard") {
        const [overview, basesPage, tree, usersPage] = await Promise.all([
          api.overview().catch(() => ({})),
          api.listBases(""),
          api.tree().catch(() => []),
          api.users(1, "").catch(() => ({ records: [], total: 0, current: 1, pages: 0 }))
        ]);
        state.overview = overview;
        state.bases = basesPage.records || [];
        state.tree = tree || [];
        state.usersPage = usersPage;
        render(renderDashboard());
      } else if (current.name === "knowledge") {
        const searchParam = new URLSearchParams(window.location.search).get("name");
        if (searchParam !== null) state.keyword = searchParam;
        await loadBases();
      } else if (current.name === "docs") {
        const [kb, docs] = await Promise.all([api.getBase(current.kbId), api.listDocs(current.kbId)]);
        state.kb = kb;
        state.docs = docs || [];
        render(renderDocsPage());
      } else if (current.name === "chunks") {
        const [kb, doc, chunks, logs] = await Promise.all([
          api.getBase(current.kbId),
          api.getDoc(current.docId),
          api.listChunks(current.docId),
          api.listLogs(current.docId).catch(() => [])
        ]);
        state.kb = kb;
        state.doc = doc;
        state.chunks = chunks || [];
        state.logs = logs || [];
        render(renderChunksPage());
      } else if (current.name === "intentTree") {
        state.tree = await api.tree();
        const focus = new URLSearchParams(window.location.search).get("intentCode");
        if (focus) {
          const focused = flattenTree(state.tree).find((node) => node.intentCode === focus);
          if (focused) {
            state.intentDraftMode = false;
            state.selectedIntentId = focused.id;
          }
        }
        render(renderIntentTreePage());
      } else if (current.name === "intentList") {
        state.tree = await api.tree();
        render(renderIntentListPage());
      } else if (current.name === "users") {
        await loadUsers();
      }
    } catch (error) {
      state.error = error.message || "页面加载失败";
      render(shell(`<div class="error-box">${text(state.error)}</div>`));
    }
  }

  async function loadBases() {
    const data = await api.listBases(state.keyword || "");
    state.bases = data.records || [];
    render(renderKnowledgePage());
  }

  async function loadUsers(page = state.usersPage?.current || 1) {
    state.usersPage = await api.users(page, state.userKeyword || "");
    render(renderUsersPage());
  }

  function render(html) {
    app.innerHTML = html;
  }

  async function runAction(action, success) {
    try {
      await action();
      if (success) showToast(success);
    } catch (error) {
      showToast(error.message || "操作失败", true);
    }
  }

  function showToast(message, error = false) {
    const node = document.createElement("div");
    node.className = `toast${error ? " error" : ""}`;
    node.textContent = message;
    document.body.appendChild(node);
    window.setTimeout(() => node.remove(), 2600);
  }

  document.addEventListener("click", async (event) => {
    const target = event.target.closest("button");
    if (!target) return;
    if (target.dataset.nav) {
      navigate(target.dataset.nav);
      return;
    }
    if (target.id === "back-chat") {
      showToast("聊天端尚未迁移到当前单服务后台");
      return;
    }
    if (target.id === "refresh-btn") {
      load();
      return;
    }
    if (target.id === "kb-search-btn") {
      state.keyword = document.getElementById("kb-search")?.value || "";
      navigate(`/admin/knowledge?name=${encodeURIComponent(state.keyword)}`);
      return;
    }
    if (target.dataset.openKb) {
      navigate(`/admin/knowledge/${target.dataset.openKb}`);
      return;
    }
    if (target.dataset.renameKb) {
      const kb = state.bases.find((item) => item.id === target.dataset.renameKb);
      const name = window.prompt("新的知识库名称", kb?.name || "");
      if (!name?.trim()) return;
      await runAction(async () => {
        await api.updateBase(target.dataset.renameKb, { name: name.trim() });
        await loadBases();
      }, "知识库已重命名");
      return;
    }
    if (target.dataset.deleteKb) {
      if (!window.confirm("确认删除该知识库及其文档？")) return;
      await runAction(async () => {
        await api.deleteBase(target.dataset.deleteKb);
        await loadBases();
      }, "知识库已删除");
      return;
    }
    if (target.dataset.openDoc) {
      navigate(`/admin/knowledge/${route().kbId}/docs/${target.dataset.openDoc}`);
      return;
    }
    if (target.dataset.previewDoc) {
      await runAction(async () => {
        const preview = await api.previewDoc(target.dataset.previewDoc);
        state.preview = preview.content || "";
        render(renderDocsPage());
      });
      return;
    }
    if (target.id === "close-preview-btn") {
      state.preview = "";
      render(renderDocsPage());
      return;
    }
    if (target.dataset.renameDoc) {
      const doc = state.docs.find((item) => item.id === target.dataset.renameDoc);
      const name = window.prompt("新的文档名", doc?.sourceName || doc?.docName || "");
      if (!name?.trim()) return;
      await runAction(async () => {
        await api.updateDoc(target.dataset.renameDoc, { docName: name.trim(), knowledgeType: doc?.knowledgeType || "text" });
        state.docs = await api.listDocs(route().kbId);
        render(renderDocsPage());
      }, "文档已更新");
      return;
    }
    if (target.dataset.toggleDoc) {
      const doc = state.docs.find((item) => item.id === target.dataset.toggleDoc);
      await runAction(async () => {
        await api.setDocEnabled(target.dataset.toggleDoc, !(doc?.enabled !== false));
        state.docs = await api.listDocs(route().kbId);
        render(renderDocsPage());
      }, "文档状态已更新");
      return;
    }
    if (target.dataset.deleteDoc) {
      if (!window.confirm("确认删除该文档？")) return;
      await runAction(async () => {
        await api.deleteDoc(target.dataset.deleteDoc);
        state.docs = await api.listDocs(route().kbId);
        render(renderDocsPage());
      }, "文档已删除");
      return;
    }
    if (target.id === "batch-enable-btn" || target.id === "batch-disable-btn") {
      const enabled = target.id === "batch-enable-btn";
      await runAction(async () => {
        await api.batchChunks(route().docId, Array.from(state.selectedChunks), enabled);
        state.selectedChunks.clear();
        state.chunks = await api.listChunks(route().docId);
        render(renderChunksPage());
      }, "Chunk 状态已批量更新");
      return;
    }
    if (target.dataset.editChunk) {
      const chunk = state.chunks.find((item) => item.id === target.dataset.editChunk);
      const content = window.prompt("更新 Chunk 内容", chunk?.content || "");
      if (!content?.trim()) return;
      await runAction(async () => {
        await api.updateChunk(route().docId, target.dataset.editChunk, { content });
        state.chunks = await api.listChunks(route().docId);
        render(renderChunksPage());
      }, "Chunk 已更新");
      return;
    }
    if (target.dataset.toggleChunk) {
      const chunk = state.chunks.find((item) => item.id === target.dataset.toggleChunk);
      await runAction(async () => {
        await api.setChunkEnabled(route().docId, target.dataset.toggleChunk, !boolEnabled(chunk?.enabled));
        state.chunks = await api.listChunks(route().docId);
        render(renderChunksPage());
      }, "Chunk 状态已更新");
      return;
    }
    if (target.dataset.deleteChunk) {
      if (!window.confirm("确认删除该 Chunk？")) return;
      await runAction(async () => {
        await api.deleteChunk(route().docId, target.dataset.deleteChunk);
        state.chunks = await api.listChunks(route().docId);
        render(renderChunksPage());
      }, "Chunk 已删除");
      return;
    }
    if (target.dataset.selectIntent) {
      state.intentDraftMode = false;
      state.selectedIntentId = target.dataset.selectIntent;
      state.intentParentCode = "";
      render(renderIntentTreePage());
      return;
    }
    if (target.id === "intent-new-root" || target.id === "intent-clear-form") {
      state.intentDraftMode = true;
      state.selectedIntentId = null;
      state.intentParentCode = "";
      render(renderIntentTreePage());
      return;
    }
    if (target.dataset.newChild) {
      state.intentDraftMode = true;
      state.selectedIntentId = null;
      state.intentParentCode = target.dataset.newChild;
      render(renderIntentTreePage());
      return;
    }
    if (target.dataset.deleteIntent) {
      if (!window.confirm("确认删除该意图节点及其子节点？")) return;
      await runAction(async () => {
        await api.deleteIntent(target.dataset.deleteIntent);
        state.selectedIntentId = null;
        state.tree = await api.tree();
        render(active("intentList") ? renderIntentListPage() : renderIntentTreePage());
      }, "意图节点已删除");
      return;
    }
    if (target.id === "intent-apply-filter") {
      state.intentFilters.keyword = document.getElementById("intent-filter-keyword")?.value || "";
      state.intentFilters.level = document.getElementById("intent-filter-level")?.value || "all";
      state.intentFilters.status = document.getElementById("intent-filter-status")?.value || "all";
      render(renderIntentListPage());
      return;
    }
    if (target.dataset.batchIntent) {
      const ids = Array.from(state.selectedIntentIds);
      if (!ids.length) return;
      if (target.dataset.batchIntent === "delete" && !window.confirm("确认批量删除选中意图？")) return;
      await runAction(async () => {
        await api.batchIntent(target.dataset.batchIntent, ids);
        state.selectedIntentIds.clear();
        state.tree = await api.tree();
        render(renderIntentListPage());
      }, "意图批量操作完成");
      return;
    }
    if (target.id === "user-search-btn") {
      state.userKeyword = document.getElementById("user-keyword")?.value || "";
      await loadUsers(1);
      return;
    }
    if (target.id === "user-prev") {
      await loadUsers(Math.max(1, Number(state.usersPage.current || 1) - 1));
      return;
    }
    if (target.id === "user-next") {
      await loadUsers(Math.min(Number(state.usersPage.pages || 1), Number(state.usersPage.current || 1) + 1));
      return;
    }
    if (target.id === "user-clear-form") {
      state.editingUserId = "";
      render(renderUsersPage());
      return;
    }
    if (target.dataset.editUser) {
      state.editingUserId = target.dataset.editUser;
      render(renderUsersPage());
      return;
    }
    if (target.dataset.deleteUser) {
      if (!window.confirm("确认删除该用户？")) return;
      await runAction(async () => {
        await api.deleteUser(target.dataset.deleteUser);
        state.editingUserId = "";
        await loadUsers(1);
      }, "用户已删除");
    }
  });

  document.addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.target;
    if (form.id === "create-kb-form") {
      const name = document.getElementById("kb-name").value.trim();
      const description = document.getElementById("kb-desc").value.trim();
      await runAction(async () => {
        await api.createBase({ name, description });
        await loadBases();
      }, "知识库创建成功");
    }
    if (form.id === "write-doc-form") {
      await runAction(async () => {
        await api.writeDoc(route().kbId, {
          sourceName: document.getElementById("doc-name").value.trim(),
          knowledgeType: document.getElementById("doc-type").value.trim(),
          mimeType: "text/markdown",
          content: document.getElementById("doc-content").value,
          chunkingMode: "STRUCTURE_AWARE",
          chunkSize: 72,
          overlapSize: 8
        });
        state.docs = await api.listDocs(route().kbId);
        render(renderDocsPage());
      }, "文档已写入并切分");
    }
    if (form.id === "upload-doc-form") {
      const file = document.getElementById("doc-file").files?.[0];
      if (!file) return showToast("请选择文件", true);
      const formData = new FormData();
      formData.append("knowledgeBaseId", route().kbId);
      formData.append("knowledgeType", "api");
      formData.append("file", file);
      await runAction(async () => {
        await api.uploadDoc(formData);
        state.docs = await api.listDocs(route().kbId);
        render(renderDocsPage());
      }, "文件已上传并执行流水线");
    }
    if (form.id === "create-chunk-form") {
      await runAction(async () => {
        await api.createChunk(route().docId, {
          chunkId: document.getElementById("chunk-id").value.trim() || null,
          index: Number(document.getElementById("chunk-index").value || 0),
          content: document.getElementById("chunk-content").value
        });
        state.chunks = await api.listChunks(route().docId);
        render(renderChunksPage());
      }, "Chunk 已新增");
    }
    if (form.id === "intent-node-form") {
      const id = document.getElementById("intent-id").value;
      const payload = {
        intentCode: document.getElementById("intent-code").value.trim(),
        name: document.getElementById("intent-name").value.trim(),
        level: Number(document.getElementById("intent-level").value),
        parentCode: document.getElementById("intent-parent").value || null,
        description: document.getElementById("intent-desc").value.trim(),
        kbId: document.getElementById("intent-kb").value.trim() || null,
        examples: document.getElementById("intent-examples").value.split("\n").map((item) => item.trim()).filter(Boolean),
        enabled: Number(document.getElementById("intent-enabled").value),
        sortOrder: 0
      };
      await runAction(async () => {
        if (id) {
          await api.updateIntent(id, payload);
        } else {
          await api.createIntent(payload);
        }
        state.tree = await api.tree();
        state.intentParentCode = "";
        state.intentDraftMode = false;
        render(renderIntentTreePage());
      }, id ? "意图节点已保存" : "意图节点已创建");
    }
    if (form.id === "user-form") {
      const id = document.getElementById("user-id").value;
      const payload = {
        username: document.getElementById("user-name").value.trim(),
        password: document.getElementById("user-password").value.trim() || undefined,
        role: document.getElementById("user-role").value,
        avatar: document.getElementById("user-avatar").value.trim() || undefined
      };
      await runAction(async () => {
        if (id) {
          await api.updateUser(id, payload);
        } else {
          await api.createUser(payload);
        }
        state.editingUserId = "";
        await loadUsers(id ? state.usersPage.current : 1);
      }, id ? "用户已保存" : "用户已创建");
    }
  });

  document.addEventListener("change", (event) => {
    const node = event.target;
    if (node.id === "select-all-chunks") {
      state.selectedChunks = node.checked ? new Set(state.chunks.map((chunk) => String(chunk.id))) : new Set();
      render(renderChunksPage());
    }
    if (node.dataset?.selectChunk) {
      if (node.checked) state.selectedChunks.add(String(node.dataset.selectChunk));
      else state.selectedChunks.delete(String(node.dataset.selectChunk));
      render(renderChunksPage());
    }
    if (node.id === "select-all-intents") {
      const rows = flattenTree(state.tree);
      state.selectedIntentIds = node.checked ? new Set(rows.map((row) => String(row.id))) : new Set();
      render(renderIntentListPage());
    }
    if (node.dataset?.selectIntentRow) {
      if (node.checked) state.selectedIntentIds.add(String(node.dataset.selectIntentRow));
      else state.selectedIntentIds.delete(String(node.dataset.selectIntentRow));
      render(renderIntentListPage());
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && event.target?.id === "global-search") {
      state.keyword = event.target.value || "";
      navigate(`/admin/knowledge?name=${encodeURIComponent(state.keyword)}`);
    }
  });

  window.addEventListener("popstate", load);
  load();
})();
