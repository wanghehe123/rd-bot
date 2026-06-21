import { FormEvent, useMemo, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { ChevronLeft, FileText, RefreshCw, Upload } from "lucide-react";

import { api } from "../api";
import { Badge, Button, Card, Empty, Field, Input, PageHeader, Table, Textarea } from "../components/Ui";
import { useAsyncData } from "../hooks";
import type { KnowledgeChunk, KnowledgeDocument } from "../types";
import { enabledLabel, formatTime, recordsOf, runAction, totalOf, truncate } from "../utils";

export function KnowledgeListPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState(searchParams.get("name") || "");
  const { data, loading, refresh } = useAsyncData(() => api.listKnowledgeBases(keyword, 100), [keyword], { records: [] });
  const bases = recordsOf(data);

  const createBase = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await runAction(async () => {
      await api.createKnowledgeBase({
        name: String(form.get("name") || "").trim(),
        description: String(form.get("description") || "").trim()
      });
      event.currentTarget.reset();
      await refresh();
    }, "知识库创建成功");
  };

  return (
    <div className="admin-page">
      <PageHeader
        title="知识库管理"
        description="管理知识库、文档上传、切分与检索素材"
        action={<Button onClick={() => void refresh()}><RefreshCw size={16} className={loading ? "spin" : undefined} />刷新</Button>}
      />

      <div className="split-grid">
        <Card
          title="知识库列表"
          description={`共 ${totalOf(data)} 个知识库`}
          action={
            <form className="inline-form" onSubmit={(event) => { event.preventDefault(); void refresh(); }}>
              <Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索知识库名称" />
              <Button type="submit">搜索</Button>
            </form>
          }
        >
          {bases.length ? (
            <Table headers={["名称", "描述", "文档数", "Collection", "操作"]}>
              {bases.map((kb) => (
                <tr key={kb.id}>
                  <td><button className="admin-link" onClick={() => navigate(`/admin/knowledge/${kb.id}`)}>{kb.name}</button></td>
                  <td>{truncate(kb.description || "-", 54)}</td>
                  <td>{kb.documentCount || 0}</td>
                  <td><Badge>{kb.collectionName || "in-memory"}</Badge></td>
                  <td>
                    <div className="row-actions">
                      <Button onClick={() => navigate(`/admin/knowledge/${kb.id}`)}>文档</Button>
                      <Button onClick={() => {
                        const name = window.prompt("新的知识库名称", kb.name);
                        if (!name?.trim()) return;
                        void runAction(async () => {
                          await api.updateKnowledgeBase(kb.id, { name: name.trim() });
                          await refresh();
                        }, "知识库已重命名");
                      }}>重命名</Button>
                      <Button variant="danger" onClick={() => {
                        if (!window.confirm("确认删除该知识库及其文档？")) return;
                        void runAction(async () => {
                          await api.deleteKnowledgeBase(kb.id);
                          await refresh();
                        }, "知识库已删除");
                      }}>删除</Button>
                    </div>
                  </td>
                </tr>
              ))}
            </Table>
          ) : (
            <Empty>暂无知识库，可以从右侧创建。</Empty>
          )}
        </Card>

        <Card title="创建知识库" description="新增本地知识空间">
          <form className="form-grid one-col" onSubmit={(event) => void createBase(event)}>
            <Field label="名称">
              <Input name="name" required placeholder="支付系统" />
            </Field>
            <Field label="描述">
              <Textarea name="description" placeholder="支付接口、订单异常和修复经验" />
            </Field>
            <Button variant="primary" type="submit">创建知识库</Button>
          </form>
        </Card>
      </div>
    </div>
  );
}

export function KnowledgeDocumentsPage() {
  const { kbId = "" } = useParams();
  const navigate = useNavigate();
  const [preview, setPreview] = useState("");
  const { data, refresh } = useAsyncData(async () => {
    const [kb, docs] = await Promise.all([api.getKnowledgeBase(kbId), api.listDocuments(kbId)]);
    return { kb, docs };
  }, [kbId], { kb: null as any, docs: [] as KnowledgeDocument[] });

  const writeDoc = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await runAction(async () => {
      await api.writeDocument(kbId, {
        sourceName: String(form.get("sourceName") || "").trim(),
        knowledgeType: String(form.get("knowledgeType") || "api").trim(),
        mimeType: "text/markdown",
        content: String(form.get("content") || ""),
        chunkingMode: "STRUCTURE_AWARE",
        chunkSize: 72,
        overlapSize: 8
      });
      event.currentTarget.reset();
      await refresh();
    }, "文档已写入并切分");
  };

  const uploadDoc = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const file = (event.currentTarget.elements.namedItem("file") as HTMLInputElement).files?.[0];
    if (!file) return;
    await runAction(async () => {
      await api.uploadDocument(kbId, file);
      event.currentTarget.reset();
      await refresh();
    }, "文件已上传并执行流水线");
  };

  return (
    <div className="admin-page">
      <PageHeader
        title="文档管理"
        description={`${data.kb?.name || kbId} · 写入、上传、预览和启停文档`}
        action={<Button onClick={() => navigate("/admin/knowledge")}><ChevronLeft size={16} />返回知识库</Button>}
      />

      <Card title="文档列表" description={`共 ${data.docs.length} 篇文档`}>
        {data.docs.length ? (
          <Table headers={["文档", "类型", "状态", "Chunk", "创建时间", "操作"]}>
            {data.docs.map((doc) => (
              <tr key={doc.id}>
                <td><button className="admin-link" onClick={() => navigate(`/admin/knowledge/${kbId}/docs/${doc.id}`)}>{doc.sourceName}</button></td>
                <td><Badge>{doc.knowledgeType}</Badge></td>
                <td><Badge tone={doc.enabled === false ? "warning" : "success"}>{enabledLabel(doc.enabled)}</Badge></td>
                <td>{doc.chunkCount || 0}</td>
                <td>{formatTime(doc.createdAtEpochMillis)}</td>
                <td>
                  <div className="row-actions">
                    <Button onClick={() => navigate(`/admin/knowledge/${kbId}/docs/${doc.id}`)}>Chunk</Button>
                    <Button onClick={() => void runAction(async () => {
                      const result = await api.previewDocument(doc.id);
                      setPreview(result.content || "");
                    }, "预览已加载")}>预览</Button>
                    <Button onClick={() => void runAction(async () => {
                      await api.setDocumentEnabled(doc.id, !(doc.enabled !== false));
                      await refresh();
                    }, "文档状态已更新")}>{doc.enabled === false ? "启用" : "停用"}</Button>
                    <Button variant="danger" onClick={() => {
                      if (!window.confirm("确认删除该文档？")) return;
                      void runAction(async () => {
                        await api.deleteDocument(doc.id);
                        await refresh();
                      }, "文档已删除");
                    }}>删除</Button>
                  </div>
                </td>
              </tr>
            ))}
          </Table>
        ) : (
          <Empty>暂无文档，可以使用下方表单写入或上传。</Empty>
        )}
      </Card>

      {preview && (
        <Card title="文档预览" action={<Button onClick={() => setPreview("")}>关闭</Button>}>
          <pre className="code-panel">{preview}</pre>
        </Card>
      )}

      <div className="split-grid">
        <Card title="手工写入文档" description="适合 API 文档、排障经验和 Markdown 片段">
          <form className="form-grid one-col" onSubmit={(event) => void writeDoc(event)}>
            <Field label="文档名"><Input name="sourceName" required placeholder="payment-api.md" /></Field>
            <Field label="知识类型"><Input name="knowledgeType" defaultValue="api" /></Field>
            <Field label="正文"><Textarea name="content" required className="textarea-tall" placeholder="# 支付 API&#10;OrderService.create 必须校验 orders.amount。" /></Field>
            <Button variant="primary" type="submit"><FileText size={16} />写入并切分</Button>
          </form>
        </Card>
        <Card title="上传文件" description="使用默认文档摄取流水线">
          <form className="form-grid one-col" onSubmit={(event) => void uploadDoc(event)}>
            <Field label="文件"><Input name="file" type="file" required /></Field>
            <Button variant="primary" type="submit"><Upload size={16} />上传并摄取</Button>
          </form>
        </Card>
      </div>
    </div>
  );
}

export function KnowledgeChunksPage() {
  const { kbId = "", docId = "" } = useParams();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const { data, refresh } = useAsyncData(async () => {
    const [kb, doc, chunks, logs] = await Promise.all([
      api.getKnowledgeBase(kbId),
      api.getDocument(docId),
      api.listChunks(docId),
      api.listChunkLogs(docId).catch(() => [])
    ]);
    return { kb, doc, chunks, logs };
  }, [kbId, docId], { kb: null as any, doc: null as KnowledgeDocument | null, chunks: [] as KnowledgeChunk[], logs: [] as any[] });

  const createChunk = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await runAction(async () => {
      await api.createChunk(docId, {
        chunkId: String(form.get("chunkId") || "").trim() || null,
        index: Number(form.get("index") || 0),
        content: String(form.get("content") || "")
      });
      event.currentTarget.reset();
      await refresh();
    }, "Chunk 已新增");
  };

  const selectedIds = useMemo(() => Array.from(selected), [selected]);

  return (
    <div className="admin-page">
      <PageHeader
        title="分块管理"
        description={`${data.doc?.sourceName || docId} · 手动维护 Chunk 与启停状态`}
        action={<Link className="ui-button ui-button--ghost" to={`/admin/knowledge/${kbId}`}><ChevronLeft size={16} />返回文档</Link>}
      />

      <Card
        title="Chunk 列表"
        description={`共 ${data.chunks.length} 个 Chunk`}
        action={
          <>
            <Button disabled={!selectedIds.length} onClick={() => void runAction(async () => { await api.batchChunks(docId, selectedIds, true); setSelected(new Set()); await refresh(); }, "Chunk 已批量启用")}>批量启用</Button>
            <Button disabled={!selectedIds.length} onClick={() => void runAction(async () => { await api.batchChunks(docId, selectedIds, false); setSelected(new Set()); await refresh(); }, "Chunk 已批量停用")}>批量停用</Button>
          </>
        }
      >
        {data.chunks.length ? (
          <Table headers={["选择", "Index", "内容", "类型", "状态", "操作"]} minWidth={920}>
            {data.chunks.map((chunk) => (
              <tr key={chunk.id}>
                <td>
                  <input
                    type="checkbox"
                    checked={selected.has(chunk.id)}
                    onChange={(event) => setSelected((current) => {
                      const next = new Set(current);
                      if (event.target.checked) next.add(chunk.id);
                      else next.delete(chunk.id);
                      return next;
                    })}
                  />
                </td>
                <td>{chunk.index}</td>
                <td>{truncate(chunk.content, 120)}</td>
                <td><Badge>{chunk.knowledgeType}</Badge></td>
                <td><Badge tone={chunk.enabled === false ? "warning" : "success"}>{enabledLabel(chunk.enabled)}</Badge></td>
                <td>
                  <div className="row-actions">
                    <Button onClick={() => {
                      const content = window.prompt("更新 Chunk 内容", chunk.content);
                      if (!content?.trim()) return;
                      void runAction(async () => {
                        await api.updateChunk(docId, chunk.id, { content });
                        await refresh();
                      }, "Chunk 已更新");
                    }}>编辑</Button>
                    <Button onClick={() => void runAction(async () => {
                      await api.setChunkEnabled(docId, chunk.id, !(chunk.enabled !== false));
                      await refresh();
                    }, "Chunk 状态已更新")}>{chunk.enabled === false ? "启用" : "停用"}</Button>
                    <Button variant="danger" onClick={() => {
                      if (!window.confirm("确认删除该 Chunk？")) return;
                      void runAction(async () => {
                        await api.deleteChunk(docId, chunk.id);
                        await refresh();
                      }, "Chunk 已删除");
                    }}>删除</Button>
                  </div>
                </td>
              </tr>
            ))}
          </Table>
        ) : (
          <Empty>暂无 Chunk。</Empty>
        )}
      </Card>

      <div className="split-grid">
        <Card title="新增手工 Chunk">
          <form className="form-grid one-col" onSubmit={(event) => void createChunk(event)}>
            <Field label="Chunk ID"><Input name="chunkId" placeholder="可选，不填自动生成" /></Field>
            <Field label="Index"><Input name="index" type="number" defaultValue={0} /></Field>
            <Field label="内容"><Textarea name="content" required className="textarea-tall" /></Field>
            <Button variant="primary" type="submit">新增 Chunk</Button>
          </form>
        </Card>
        <Card title="切分日志" description="摄取节点执行记录">
          {data.logs.length ? <pre className="code-panel">{JSON.stringify(data.logs, null, 2)}</pre> : <Empty>暂无切分日志。</Empty>}
        </Card>
      </div>
    </div>
  );
}
