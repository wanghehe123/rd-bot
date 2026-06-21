import { FormEvent, useMemo, useState } from "react";
import { GitBranch, Plus, RefreshCw } from "lucide-react";

import { api } from "../api";
import { Badge, Button, Card, Empty, Field, Input, PageHeader, Select, Table, Textarea } from "../components/Ui";
import { useAsyncData } from "../hooks";
import type { IntentNode } from "../types";
import { enabledLabel, flattenIntentTree, levelLabel, runAction, truncate } from "../utils";

function nodeEnabled(node?: IntentNode | null) {
  return node?.enabled === false || node?.enabled === 0 ? 0 : 1;
}

function IntentTreeRows({
  nodes,
  selectedId,
  onSelect,
  onNewChild
}: {
  nodes: IntentNode[];
  selectedId: string | null;
  onSelect: (node: IntentNode) => void;
  onNewChild: (node: IntentNode) => void;
}) {
  if (!nodes.length) return <Empty>暂无意图节点。</Empty>;
  return (
    <div className="intent-tree">
      {nodes.map((node) => (
        <div key={node.id} className="intent-tree-node">
          <button
            type="button"
            className={`intent-tree-row ${selectedId === node.id ? "is-active" : ""}`}
            onClick={() => onSelect(node)}
          >
            <div>
              <strong>{node.name}</strong>
              <span>{node.intentCode} · {levelLabel(node.level)}</span>
            </div>
            <Badge tone={nodeEnabled(node) ? "success" : "warning"}>{enabledLabel(node.enabled)}</Badge>
          </button>
          <div className="intent-tree-actions">
            <Button onClick={() => onNewChild(node)}><Plus size={14} />子节点</Button>
          </div>
          {node.children?.length ? (
            <div className="intent-tree-children">
              <IntentTreeRows nodes={node.children} selectedId={selectedId} onSelect={onSelect} onNewChild={onNewChild} />
            </div>
          ) : null}
        </div>
      ))}
    </div>
  );
}

function buildPayload(form: HTMLFormElement) {
  const data = new FormData(form);
  return {
    intentCode: String(data.get("intentCode") || "").trim(),
    name: String(data.get("name") || "").trim(),
    level: Number(data.get("level") || 0),
    parentCode: String(data.get("parentCode") || "").trim() || null,
    description: String(data.get("description") || "").trim(),
    kbId: String(data.get("kbId") || "").trim() || null,
    examples: String(data.get("examples") || "")
      .split("\n")
      .map((item) => item.trim())
      .filter(Boolean),
    enabled: Number(data.get("enabled") || 1),
    sortOrder: 0
  };
}

export function IntentTreePage() {
  const [selected, setSelected] = useState<IntentNode | null>(null);
  const [draftParent, setDraftParent] = useState("");
  const { data, refresh } = useAsyncData(() => api.listIntentTree(), [], [] as IntentNode[]);

  const editing = selected && !draftParent ? selected : null;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const payload = buildPayload(event.currentTarget);
    await runAction(async () => {
      if (editing?.id) await api.updateIntent(editing.id, payload);
      else await api.createIntent(payload);
      setSelected(null);
      setDraftParent("");
      await refresh();
    }, editing ? "意图节点已保存" : "意图节点已创建");
  };

  return (
    <div className="admin-page">
      <PageHeader
        title="意图树配置"
        description="配置意图层级、父子关系、知识库绑定和示例问题"
        action={<Button onClick={() => { setSelected(null); setDraftParent(""); }}><Plus size={16} />新建根节点</Button>}
      />

      <div className="split-grid wide-left">
        <Card title="意图树" description="点击节点后可在右侧编辑">
          <IntentTreeRows
            nodes={data}
            selectedId={selected?.id || null}
            onSelect={(node) => {
              setSelected(node);
              setDraftParent("");
            }}
            onNewChild={(node) => {
              setSelected(null);
              setDraftParent(node.intentCode);
            }}
          />
        </Card>

        <Card title={editing ? "编辑意图节点" : "新建意图节点"} action={<Button onClick={() => void refresh()}><RefreshCw size={15} />刷新</Button>}>
          <form key={`${editing?.id || "new"}-${draftParent}`} className="form-grid one-col" onSubmit={(event) => void submit(event)}>
            <Field label="意图编码"><Input name="intentCode" required defaultValue={editing?.intentCode || ""} placeholder="payment.order.create" /></Field>
            <Field label="名称"><Input name="name" required defaultValue={editing?.name || ""} placeholder="创建订单失败" /></Field>
            <Field label="层级">
              <Select name="level" defaultValue={String(editing?.level ?? (draftParent ? 2 : 0))}>
                <option value="0">DOMAIN</option>
                <option value="1">CATEGORY</option>
                <option value="2">TOPIC</option>
              </Select>
            </Field>
            <Field label="父级编码"><Input name="parentCode" defaultValue={editing?.parentCode || draftParent} placeholder="可为空" /></Field>
            <Field label="知识库 ID"><Input name="kbId" defaultValue={editing?.kbId || ""} placeholder="可选" /></Field>
            <Field label="状态">
              <Select name="enabled" defaultValue={String(nodeEnabled(editing))}>
                <option value="1">启用</option>
                <option value="0">停用</option>
              </Select>
            </Field>
            <Field label="描述"><Textarea name="description" defaultValue={editing?.description || ""} /></Field>
            <Field label="示例问题"><Textarea name="examples" defaultValue={(editing?.examples || []).join("\n")} placeholder="每行一个示例问题" /></Field>
            <div className="row-actions">
              <Button variant="primary" type="submit">{editing ? "保存节点" : "创建节点"}</Button>
              {editing && (
                <Button variant="danger" type="button" onClick={() => {
                  if (!window.confirm("确认删除该意图节点及其子节点？")) return;
                  void runAction(async () => {
                    await api.deleteIntent(editing.id);
                    setSelected(null);
                    await refresh();
                  }, "意图节点已删除");
                }}>删除节点</Button>
              )}
            </div>
          </form>
        </Card>
      </div>
    </div>
  );
}

export function IntentListPage() {
  const [filters, setFilters] = useState({ keyword: "", level: "all", status: "all" });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const { data, refresh } = useAsyncData(() => api.listIntentTree(), [], [] as IntentNode[]);
  const rows = useMemo(() => {
    const keyword = filters.keyword.trim().toLowerCase();
    return flattenIntentTree(data).filter((node) => {
      const matchesKeyword = !keyword || `${node.name} ${node.intentCode} ${node.description || ""}`.toLowerCase().includes(keyword);
      const matchesLevel = filters.level === "all" || String(node.level ?? 2) === filters.level;
      const enabled = nodeEnabled(node);
      const matchesStatus = filters.status === "all" || (filters.status === "enabled" ? enabled === 1 : enabled === 0);
      return matchesKeyword && matchesLevel && matchesStatus;
    });
  }, [data, filters]);
  const selectedIds = Array.from(selected);

  const batch = (action: "enable" | "disable" | "delete") => {
    if (!selectedIds.length) return;
    if (action === "delete" && !window.confirm("确认批量删除选中意图？")) return;
    void runAction(async () => {
      await api.batchIntent(action, selectedIds);
      setSelected(new Set());
      await refresh();
    }, "意图批量操作完成");
  };

  return (
    <div className="admin-page">
      <PageHeader title="意图列表" description="筛选、批量启停和批量删除意图节点" action={<Button onClick={() => void refresh()}><RefreshCw size={16} />刷新</Button>} />

      <Card title="筛选条件">
        <div className="filter-grid">
          <Input value={filters.keyword} onChange={(event) => setFilters((current) => ({ ...current, keyword: event.target.value }))} placeholder="关键词 / 编码 / 描述" />
          <Select value={filters.level} onChange={(event) => setFilters((current) => ({ ...current, level: event.target.value }))}>
            <option value="all">全部层级</option>
            <option value="0">DOMAIN</option>
            <option value="1">CATEGORY</option>
            <option value="2">TOPIC</option>
          </Select>
          <Select value={filters.status} onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value }))}>
            <option value="all">全部状态</option>
            <option value="enabled">启用</option>
            <option value="disabled">停用</option>
          </Select>
          <div className="row-actions">
            <Button disabled={!selectedIds.length} onClick={() => batch("enable")}>批量启用</Button>
            <Button disabled={!selectedIds.length} onClick={() => batch("disable")}>批量停用</Button>
            <Button disabled={!selectedIds.length} variant="danger" onClick={() => batch("delete")}>批量删除</Button>
          </div>
        </div>
      </Card>

      <Card title="意图节点" description={`${rows.length} 个结果`}>
        {rows.length ? (
          <Table headers={["选择", "节点", "层级", "路径", "状态", "描述"]} minWidth={920}>
            {rows.map((node) => (
              <tr key={node.id}>
                <td><input type="checkbox" checked={selected.has(node.id)} onChange={(event) => setSelected((current) => {
                  const next = new Set(current);
                  if (event.target.checked) next.add(node.id);
                  else next.delete(node.id);
                  return next;
                })} /></td>
                <td>
                  <div className="table-title">
                    <GitBranch size={15} />
                    <div><strong>{node.name}</strong><span>{node.intentCode}</span></div>
                  </div>
                </td>
                <td><Badge>{levelLabel(node.level)}</Badge></td>
                <td>{node.pathText || "-"}</td>
                <td><Badge tone={nodeEnabled(node) ? "success" : "warning"}>{enabledLabel(node.enabled)}</Badge></td>
                <td>{truncate(node.description || "-", 70)}</td>
              </tr>
            ))}
          </Table>
        ) : (
          <Empty>没有符合条件的意图节点。</Empty>
        )}
      </Card>
    </div>
  );
}
