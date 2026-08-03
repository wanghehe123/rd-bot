import { FormEvent, useState } from "react";
import { RefreshCw } from "lucide-react";

import { api } from "../api";
import { Badge, Button, Card, Empty, Field, Input, PageHeader, Select, Table } from "../components/Ui";
import { useAsyncData } from "../hooks";
import type { AnyRecord, ManagedUser } from "../types";
import { formatTime, recordsOf, runAction, totalOf } from "../utils";

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
