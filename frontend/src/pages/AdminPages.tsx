import { FormEvent, useState } from "react";
import { Eye, RefreshCw, Upload } from "lucide-react";

import { api } from "../api";
import { Badge, Button, Card, Empty, Field, Input, PageHeader, Select, Table, Textarea } from "../components/Ui";
import { useAsyncData } from "../hooks";
import type { AnyRecord, ManagedUser } from "../types";
import { formatTime, recordsOf, runAction, totalOf, truncate } from "../utils";

export function UserListPage() {
  const [keyword, setKeyword] = useState("");
  const [editing, setEditing] = useState<ManagedUser | null>(null);
  const { data, refresh } = useAsyncData(() => api.listUsers(1, keyword), [keyword], { records: [] });
  const users = recordsOf(data);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const payload = {
      username: String(form.get("username") || "").trim(),
      password: String(form.get("password") || "").trim() || undefined,
      role: String(form.get("role") || "user"),
      avatar: String(form.get("avatar") || "").trim() || undefined
    };
    await runAction(async () => {
      if (editing) await api.updateUser(editing.id, payload);
      else await api.createUser(payload);
      setEditing(null);
      event.currentTarget.reset();
      await refresh();
    }, editing ? "用户已保存" : "用户已创建");
  };

  return (
    <div className="admin-page">
      <PageHeader title="用户管理" description="管理本地后台账号与角色权限" action={<Button onClick={() => void refresh()}><RefreshCw size={16} />刷新</Button>} />
      <div className="split-grid wide-left">
        <Card
          title="用户列表"
          description={`共 ${totalOf(data)} 个用户`}
          action={<Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索用户名或角色" />}
        >
          {users.length ? (
            <Table headers={["用户", "角色", "创建时间", "更新时间", "操作"]}>
              {users.map((user) => {
                const protectedAdmin = user.username === "admin";
                return (
                  <tr key={user.id}>
                    <td><div className="table-title"><span className="admin-avatar small">{user.username.slice(0, 1).toUpperCase()}</span><div><strong>{user.username}</strong>{protectedAdmin && <span>默认管理员</span>}</div></div></td>
                    <td><Badge tone={user.role === "admin" ? "success" : "neutral"}>{user.role === "admin" ? "管理员" : "成员"}</Badge></td>
                    <td>{formatTime(user.createTime)}</td>
                    <td>{formatTime(user.updateTime)}</td>
                    <td>
                      <div className="row-actions">
                        <Button disabled={protectedAdmin} onClick={() => setEditing(user)}>编辑</Button>
                        <Button disabled={protectedAdmin} variant="danger" onClick={() => {
                          if (!window.confirm("确认删除该用户？")) return;
                          void runAction(async () => { await api.deleteUser(user.id); await refresh(); }, "用户已删除");
                        }}>删除</Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </Table>
          ) : (
            <Empty>暂无用户。</Empty>
          )}
        </Card>

        <Card title={editing ? "编辑用户" : "创建用户"}>
          <form key={editing?.id || "new"} className="form-grid one-col" onSubmit={(event) => void submit(event)}>
            <Field label="用户名"><Input name="username" required defaultValue={editing?.username || ""} /></Field>
            <Field label="密码"><Input name="password" type="password" required={!editing} placeholder={editing ? "留空则不修改" : "设置初始密码"} /></Field>
            <Field label="角色">
              <Select name="role" defaultValue={editing?.role || "user"}>
                <option value="user">成员</option>
                <option value="admin">管理员</option>
              </Select>
            </Field>
            <Field label="头像 URL"><Input name="avatar" defaultValue={editing?.avatar || ""} placeholder="可选" /></Field>
            <div className="row-actions">
              <Button variant="primary" type="submit">{editing ? "保存用户" : "创建用户"}</Button>
              <Button type="button" onClick={() => setEditing(null)}>清空</Button>
            </div>
          </form>
        </Card>
      </div>
    </div>
  );
}

function defaultPipelineNodes() {
  return [
    { nodeId: "fetcher", nodeType: "FETCHER", nextNodeId: "parser" },
    { nodeId: "parser", nodeType: "PARSER", nextNodeId: "chunker" },
    { nodeId: "chunker", nodeType: "CHUNKER", nextNodeId: "indexer" },
    { nodeId: "indexer", nodeType: "INDEXER" }
  ];
}

export function IngestionPage() {
  const [tab, setTab] = useState<"pipelines" | "tasks">("pipelines");
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [taskNodes, setTaskNodes] = useState<AnyRecord[]>([]);
  const pipelines = useAsyncData(() => api.listPipelines(keyword), [keyword], { records: [] });
  const tasks = useAsyncData(() => api.listTasks(status), [status], { records: [] });

  const createPipeline = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await runAction(async () => {
      await api.createPipeline({
        name: String(form.get("name") || "").trim(),
        description: String(form.get("description") || "").trim(),
        nodes: defaultPipelineNodes()
      });
      event.currentTarget.reset();
      await pipelines.refresh();
    }, "摄取管道已创建");
  };

  const createTask = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await runAction(async () => {
      await api.createTask({
        pipelineId: String(form.get("pipelineId") || "").trim(),
        knowledgeBaseId: String(form.get("knowledgeBaseId") || "").trim(),
        knowledgeType: String(form.get("knowledgeType") || "api"),
        mimeType: "text/markdown",
        source: {
          type: "inline",
          location: "inline://frontend-task.md",
          fileName: String(form.get("fileName") || "frontend-task.md"),
          content: String(form.get("content") || "")
        },
        chunkingMode: "STRUCTURE_AWARE",
        chunkSize: 72,
        overlapSize: 8,
        metadata: { operator: "frontend" }
      });
      event.currentTarget.reset();
      await tasks.refresh();
    }, "摄取任务已完成");
  };

  return (
    <div className="admin-page">
      <PageHeader title="数据通道" description="管理文档摄取流水线、上传任务和节点执行记录" action={<Button onClick={() => tab === "pipelines" ? void pipelines.refresh() : void tasks.refresh()}><RefreshCw size={16} />刷新</Button>} />
      <div className="segmented">
        <button className={tab === "pipelines" ? "is-active" : ""} onClick={() => setTab("pipelines")}>流水线管理</button>
        <button className={tab === "tasks" ? "is-active" : ""} onClick={() => setTab("tasks")}>流水线任务</button>
      </div>

      {tab === "pipelines" ? (
        <div className="split-grid wide-left">
          <Card title="摄取管道" description={`共 ${totalOf(pipelines.data)} 条`} action={<Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索管道" />}>
            {recordsOf(pipelines.data).length ? (
              <Table headers={["名称", "描述", "节点", "创建人", "更新时间", "操作"]}>
                {recordsOf(pipelines.data).map((item: AnyRecord) => (
                  <tr key={item.id}>
                    <td><strong>{item.name}</strong><div className="muted-text">{item.id}</div></td>
                    <td>{truncate(item.description || "-", 70)}</td>
                    <td>{item.nodes?.length || 0}</td>
                    <td>{item.createdBy || "local"}</td>
                    <td>{formatTime(item.updateTimeEpochMillis)}</td>
                    <td>
                      <div className="row-actions">
                        <Button onClick={() => {
                          const name = window.prompt("新的管道名称", item.name);
                          if (!name?.trim()) return;
                          void runAction(async () => {
                            await api.updatePipeline(item.id, { name, description: item.description, nodes: item.nodes || defaultPipelineNodes() });
                            await pipelines.refresh();
                          }, "管道已更新");
                        }}>重命名</Button>
                        <Button variant="danger" onClick={() => {
                          if (!window.confirm("确认删除该管道？")) return;
                          void runAction(async () => { await api.deletePipeline(item.id); await pipelines.refresh(); }, "管道已删除");
                        }}>删除</Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </Table>
            ) : (
              <Empty>暂无摄取管道。</Empty>
            )}
          </Card>
          <Card title="创建默认四节点管道">
            <form className="form-grid one-col" onSubmit={(event) => void createPipeline(event)}>
              <Field label="名称"><Input name="name" required placeholder="支付文档摄取" /></Field>
              <Field label="描述"><Textarea name="description" placeholder="Fetcher / Parser / Chunker / Indexer" /></Field>
              <Button variant="primary" type="submit"><Upload size={16} />创建管道</Button>
            </form>
          </Card>
        </div>
      ) : (
        <div className="split-grid wide-left">
          <Card title="摄取任务" description={`共 ${totalOf(tasks.data)} 条`} action={
            <Select value={status} onChange={(event) => setStatus(event.target.value)}>
              <option value="">全部状态</option>
              <option value="COMPLETED">COMPLETED</option>
              <option value="FAILED">FAILED</option>
            </Select>
          }>
            {recordsOf(tasks.data).length ? (
              <Table headers={["任务", "知识库", "状态", "文档", "Chunk", "操作"]}>
                {recordsOf(tasks.data).map((task: AnyRecord) => (
                  <tr key={task.id}>
                    <td><strong>{task.sourceFileName || task.id}</strong><div className="muted-text">{task.id}</div></td>
                    <td>{task.knowledgeBaseId}</td>
                    <td><Badge tone={task.status === "COMPLETED" ? "success" : "warning"}>{task.status}</Badge></td>
                    <td>{task.documentId || "-"}</td>
                    <td>{task.chunkCount || 0}</td>
                    <td><Button onClick={() => void runAction(async () => { setTaskNodes(await api.listTaskNodes(task.id)); }, "任务节点已加载")}><Eye size={15} />节点</Button></td>
                  </tr>
                ))}
              </Table>
            ) : (
              <Empty>暂无摄取任务。</Empty>
            )}
            {taskNodes.length ? <pre className="code-panel margin-top">{JSON.stringify(taskNodes, null, 2)}</pre> : null}
          </Card>
          <Card title="创建内联摄取任务">
            <form className="form-grid one-col" onSubmit={(event) => void createTask(event)}>
              <Field label="管道 ID"><Input name="pipelineId" required placeholder="default-document-pipeline" /></Field>
              <Field label="知识库 ID"><Input name="knowledgeBaseId" required placeholder="kb-1" /></Field>
              <Field label="知识类型"><Input name="knowledgeType" defaultValue="api" /></Field>
              <Field label="文件名"><Input name="fileName" defaultValue="frontend-task.md" /></Field>
              <Field label="内容"><Textarea name="content" required className="textarea-tall" /></Field>
              <Button variant="primary" type="submit">执行任务</Button>
            </form>
          </Card>
        </div>
      )}
    </div>
  );
}

export function SettingsPage() {
  const { data, refresh } = useAsyncData(() => api.settings(), [], {} as AnyRecord);
  return (
    <div className="admin-page">
      <PageHeader title="系统设置" description="只读查看 RAG、上传限制、模型与 provider 配置" action={<Button onClick={() => void refresh()}><RefreshCw size={16} />刷新</Button>} />
      <div className="settings-grid">
        <Card title="上传限制">
          <div className="kv-list">
            <div><span>单文件</span><strong>{data.upload?.maxFileSize ?? "-"} bytes</strong></div>
            <div><span>单请求</span><strong>{data.upload?.maxRequestSize ?? "-"} bytes</strong></div>
          </div>
        </Card>
        <Card title="向量配置">
          <div className="kv-list">
            <div><span>Collection</span><strong>{data.rag?.default?.collectionName || "-"}</strong></div>
            <div><span>维度</span><strong>{data.rag?.default?.dimension || "-"}</strong></div>
            <div><span>Metric</span><strong>{data.rag?.default?.metricType || "-"}</strong></div>
          </div>
        </Card>
        <Card title="AI Provider">
          <pre className="code-panel">{JSON.stringify(data.ai || {}, null, 2)}</pre>
        </Card>
      </div>
    </div>
  );
}
