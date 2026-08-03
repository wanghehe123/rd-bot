import { FormEvent, useEffect, useMemo, useState } from "react";
import { RefreshCw } from "lucide-react";

import { Badge, Button, Card, Empty, Field, Input, PageHeader, Select, Table, Textarea } from "../../../components/Ui";
import { useAsyncData } from "../../../hooks";
import {
  approveSkill,
  listSkillCatalog,
  listSkillRoleBindings,
  replaceSkillRoleBindings,
  SKILL_AGENT_ROLES,
  updateSkill,
  uploadSkill,
  type SkillAgentRole,
  type SkillCatalogEntry,
  type SkillCatalogStatus,
  type SkillRiskLevel,
  type SkillRoleBinding
} from "@/services/skillHubService";
import { formatTime, runAction } from "../../../utils";

const ROLE_LABELS: Record<SkillAgentRole, string> = {
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案架构",
  CODING_AGENT: "编码 Agent",
  QA_AGENT: "QA Agent"
};

const STATUS_LABELS: Record<SkillCatalogStatus, string> = {
  ACTIVE: "已启用",
  WAITING_APPROVAL: "待审批",
  DISABLED: "已停用",
  REJECTED: "已拒绝"
};

const RISK_LABELS: Record<string, string> = {
  LOW: "低",
  MEDIUM: "中",
  HIGH: "高",
  UNKNOWN: "未知"
};

function statusTone(status: SkillCatalogStatus): "success" | "warning" | "danger" | "neutral" {
  if (status === "ACTIVE") return "success";
  if (status === "WAITING_APPROVAL") return "warning";
  if (status === "REJECTED") return "danger";
  return "neutral";
}

function riskTone(risk: string): "success" | "warning" | "danger" | "neutral" {
  if (risk === "LOW") return "success";
  if (risk === "MEDIUM") return "warning";
  if (risk === "HIGH") return "danger";
  return "neutral";
}

function roleLabel(role: string): string {
  return ROLE_LABELS[role as SkillAgentRole] || role;
}

type BindingDraft = {
  skillId: string;
  forceGuide: boolean;
};

function bindingsToDraft(bindings: SkillRoleBinding[] | undefined): BindingDraft[] {
  return (bindings || [])
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((item) => ({ skillId: item.skillId, forceGuide: Boolean(item.forceGuide) }));
}

export function SkillHubPage() {
  const catalogState = useAsyncData(() => listSkillCatalog(), [], [] as SkillCatalogEntry[]);
  const bindingsState = useAsyncData(() => listSkillRoleBindings(), [], {} as Record<string, SkillRoleBinding[]>);

  const [editing, setEditing] = useState<SkillCatalogEntry | null>(null);
  const [editDescription, setEditDescription] = useState("");
  const [editGuidePrompt, setEditGuidePrompt] = useState("");
  const [editForceGuide, setEditForceGuide] = useState(false);
  const [editStatus, setEditStatus] = useState<SkillCatalogStatus>("ACTIVE");
  const [editRoles, setEditRoles] = useState<string[]>([]);

  const [uploadVersion, setUploadVersion] = useState("");
  const [uploadRisk, setUploadRisk] = useState<SkillRiskLevel | "LOW">("LOW");
  const [uploadRoles, setUploadRoles] = useState<string[]>([...SKILL_AGENT_ROLES]);
  const [uploadGuidePrompt, setUploadGuidePrompt] = useState("");
  const [uploadForceGuide, setUploadForceGuide] = useState(false);
  const [uploadFile, setUploadFile] = useState<File | null>(null);

  const [activeRole, setActiveRole] = useState<SkillAgentRole>("REQUIREMENT_REVIEWER");
  const [bindingDrafts, setBindingDrafts] = useState<Record<string, BindingDraft[]>>({});

  useEffect(() => {
    const next: Record<string, BindingDraft[]> = {};
    for (const role of SKILL_AGENT_ROLES) {
      next[role] = bindingsToDraft(bindingsState.data[role]);
    }
    setBindingDrafts(next);
  }, [bindingsState.data]);

  useEffect(() => {
    if (!editing) return;
    setEditDescription(editing.description || "");
    setEditGuidePrompt(editing.guidePrompt || "");
    setEditForceGuide(Boolean(editing.forceGuide));
    setEditStatus(editing.status);
    setEditRoles([...(editing.allowedRoles || [])]);
  }, [editing]);

  const activeSkills = useMemo(
    () => catalogState.data.filter((item) => item.status === "ACTIVE"),
    [catalogState.data]
  );

  const refreshAll = async () => {
    await Promise.all([catalogState.refresh(), bindingsState.refresh()]);
  };

  const toggleRole = (roles: string[], role: string, checked: boolean) =>
    checked ? [...new Set([...roles, role])] : roles.filter((item) => item !== role);

  const startEdit = (skill: SkillCatalogEntry) => setEditing(skill);

  const clearEdit = () => setEditing(null);

  const submitUpload = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!uploadFile) return;
    await runAction(async () => {
      await uploadSkill(uploadFile, {
        version: uploadVersion,
        riskLevel: uploadRisk,
        allowedRoles: uploadRoles.join(","),
        guidePrompt: uploadGuidePrompt,
        forceGuide: uploadForceGuide
      });
      setUploadFile(null);
      setUploadVersion("");
      setUploadGuidePrompt("");
      setUploadForceGuide(false);
      setUploadRoles([...SKILL_AGENT_ROLES]);
      event.currentTarget.reset();
      await catalogState.refresh();
    }, "Skill 已上传");
  };

  const submitEdit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editing) return;
    await runAction(async () => {
      await updateSkill(editing.skillId, {
        description: editDescription,
        guidePrompt: editGuidePrompt,
        forceGuide: editForceGuide,
        allowedRoles: editRoles,
        status: editStatus
      });
      setEditing(null);
      await catalogState.refresh();
    }, "Skill 已保存");
  };

  const onApprove = (skillId: string) => {
    void runAction(async () => {
      await approveSkill(skillId);
      await catalogState.refresh();
    }, "Skill 已审批通过");
  };

  const toggleBindingSkill = (role: SkillAgentRole, skillId: string, checked: boolean) => {
    setBindingDrafts((current) => {
      const existing = current[role] || [];
      if (checked) {
        if (existing.some((item) => item.skillId === skillId)) return current;
        return { ...current, [role]: [...existing, { skillId, forceGuide: false }] };
      }
      return { ...current, [role]: existing.filter((item) => item.skillId !== skillId) };
    });
  };

  const setBindingForceGuide = (role: SkillAgentRole, skillId: string, forceGuide: boolean) => {
    setBindingDrafts((current) => ({
      ...current,
      [role]: (current[role] || []).map((item) =>
        item.skillId === skillId ? { ...item, forceGuide } : item
      )
    }));
  };

  const saveBindings = (role: SkillAgentRole) => {
    const drafts = bindingDrafts[role] || [];
    void runAction(async () => {
      await replaceSkillRoleBindings(
        role,
        drafts.map((item, index) => ({
          skillId: item.skillId,
          sortOrder: index,
          forceGuide: item.forceGuide
        }))
      );
      await bindingsState.refresh();
    }, `${roleLabel(role)} 绑定已保存`);
  };

  const currentBindings = bindingDrafts[activeRole] || [];

  return (
    <div className="admin-page">
      <PageHeader
        title="Skill Hub / 技能中心"
        description="管理 Skill 目录、审批与角色绑定。默认由 Pi 系统提示披露 description；开启强制引导后追加 guidePrompt。"
        action={
          <Button type="button" onClick={() => void refreshAll()}>
            <RefreshCw size={16} />
            刷新
          </Button>
        }
      />

      <div className="split-grid wide-left">
        <Card title="Skill 目录" description={`共 ${catalogState.data.length} 个 Skill`}>
          {catalogState.data.length ? (
            <Table headers={["Skill ID", "版本", "风险", "状态", "强制引导", "角色", "操作"]} minWidth={920}>
              {catalogState.data.map((skill) => (
                <tr key={skill.skillId}>
                  <td>
                    <div className="table-title">
                      <div>
                        <strong>{skill.skillId}</strong>
                        <span>{skill.description || "无描述"}</span>
                      </div>
                    </div>
                  </td>
                  <td>{skill.version || "-"}</td>
                  <td>
                    <Badge tone={riskTone(skill.riskLevel)}>
                      {RISK_LABELS[skill.riskLevel] || skill.riskLevel}
                    </Badge>
                  </td>
                  <td>
                    <Badge tone={statusTone(skill.status)}>
                      {STATUS_LABELS[skill.status] || skill.status}
                    </Badge>
                  </td>
                  <td>{skill.forceGuide ? "是" : "否"}</td>
                  <td>{(skill.allowedRoles || []).map(roleLabel).join("、") || "-"}</td>
                  <td>
                    <div className="row-actions">
                      <Button type="button" onClick={() => startEdit(skill)}>
                        编辑
                      </Button>
                      {skill.status === "WAITING_APPROVAL" ? (
                        <Button type="button" variant="primary" onClick={() => onApprove(skill.skillId)}>
                          审批
                        </Button>
                      ) : null}
                    </div>
                  </td>
                </tr>
              ))}
            </Table>
          ) : (
            <Empty>{catalogState.loading ? "加载中..." : "暂无 Skill。"}</Empty>
          )}
        </Card>

        <Card title={editing ? `编辑 ${editing.skillId}` : "上传 Skill"}>
          {editing ? (
            <form className="form-grid one-col" onSubmit={(event) => void submitEdit(event)}>
              <Field label="描述">
                <Textarea value={editDescription} onChange={(event) => setEditDescription(event.target.value)} rows={3} />
              </Field>
              <Field label="引导提示词 (guidePrompt)">
                <Textarea value={editGuidePrompt} onChange={(event) => setEditGuidePrompt(event.target.value)} rows={4} />
              </Field>
              <Field label="状态">
                <Select value={editStatus} onChange={(event) => setEditStatus(event.target.value as SkillCatalogStatus)}>
                  <option value="ACTIVE">已启用</option>
                  <option value="WAITING_APPROVAL">待审批</option>
                  <option value="DISABLED">已停用</option>
                  <option value="REJECTED">已拒绝</option>
                </Select>
              </Field>
              <Field label="允许角色">
                <div className="form-grid one-col">
                  {SKILL_AGENT_ROLES.map((role) => (
                    <label key={role} className="evaluation-check">
                      <input
                        type="checkbox"
                        checked={editRoles.includes(role)}
                        onChange={(event) => setEditRoles(toggleRole(editRoles, role, event.target.checked))}
                      />
                      <span>{ROLE_LABELS[role]}</span>
                    </label>
                  ))}
                </div>
              </Field>
              <label className="evaluation-check">
                <input
                  type="checkbox"
                  checked={editForceGuide}
                  onChange={(event) => setEditForceGuide(event.target.checked)}
                />
                <span>
                  <strong>强制引导</strong>
                  <small>开启后追加 guidePrompt；默认仅披露 description</small>
                </span>
              </label>
              <div className="kv-list">
                <div>
                  <span>更新时间</span>
                  <strong>{formatTime(editing.updatedAt)}</strong>
                </div>
              </div>
              <div className="row-actions">
                <Button variant="primary" type="submit">
                  保存
                </Button>
                <Button type="button" onClick={clearEdit}>
                  取消
                </Button>
              </div>
            </form>
          ) : (
            <form className="form-grid one-col" onSubmit={(event) => void submitUpload(event)}>
              <Field label="文件 (zip / SKILL.md)">
                <Input
                  type="file"
                  accept=".zip,.md,text/markdown,application/zip"
                  required
                  onChange={(event) => setUploadFile(event.target.files?.[0] || null)}
                />
              </Field>
              <Field label="版本">
                <Input value={uploadVersion} onChange={(event) => setUploadVersion(event.target.value)} placeholder="可选，如 1.0.0" />
              </Field>
              <Field label="风险等级">
                <Select value={uploadRisk} onChange={(event) => setUploadRisk(event.target.value as SkillRiskLevel)}>
                  <option value="LOW">低</option>
                  <option value="MEDIUM">中</option>
                  <option value="HIGH">高</option>
                </Select>
              </Field>
              <Field label="允许角色">
                <div className="form-grid one-col">
                  {SKILL_AGENT_ROLES.map((role) => (
                    <label key={role} className="evaluation-check">
                      <input
                        type="checkbox"
                        checked={uploadRoles.includes(role)}
                        onChange={(event) => setUploadRoles(toggleRole(uploadRoles, role, event.target.checked))}
                      />
                      <span>{ROLE_LABELS[role]}</span>
                    </label>
                  ))}
                </div>
              </Field>
              <Field label="引导提示词 (guidePrompt)">
                <Textarea
                  value={uploadGuidePrompt}
                  onChange={(event) => setUploadGuidePrompt(event.target.value)}
                  rows={4}
                  placeholder="可选"
                />
              </Field>
              <label className="evaluation-check">
                <input
                  type="checkbox"
                  checked={uploadForceGuide}
                  onChange={(event) => setUploadForceGuide(event.target.checked)}
                />
                <span>
                  <strong>强制引导</strong>
                  <small>开启后追加 guidePrompt；默认仅披露 description</small>
                </span>
              </label>
              <div className="row-actions">
                <Button variant="primary" type="submit" disabled={!uploadFile || uploadRoles.length === 0}>
                  上传
                </Button>
              </div>
            </form>
          )}
        </Card>
      </div>

      <Card
        title="角色绑定"
        description="仅 ACTIVE Skill 可绑定到角色；保存后按当前勾选顺序物化。"
      >
        <div className="row-actions" style={{ marginBottom: 16, flexWrap: "wrap" }}>
          {SKILL_AGENT_ROLES.map((role) => (
            <Button
              key={role}
              type="button"
              variant={activeRole === role ? "primary" : "default"}
              onClick={() => setActiveRole(role)}
            >
              {ROLE_LABELS[role]}
              <Badge tone="neutral">{(bindingDrafts[role] || []).length}</Badge>
            </Button>
          ))}
        </div>

        {activeSkills.length ? (
          <div className="form-grid one-col">
            {activeSkills.map((skill) => {
              const bound = currentBindings.find((item) => item.skillId === skill.skillId);
              const allowed = !skill.allowedRoles?.length || skill.allowedRoles.includes(activeRole);
              return (
                <div key={skill.skillId} className="kv-list" style={{ padding: "8px 0" }}>
                  <label className="evaluation-check">
                    <input
                      type="checkbox"
                      checked={Boolean(bound)}
                      disabled={!allowed}
                      onChange={(event) => toggleBindingSkill(activeRole, skill.skillId, event.target.checked)}
                    />
                    <span>
                      <strong>{skill.skillId}</strong>
                      <small>
                        {skill.version || "-"} · {skill.description || "无描述"}
                        {!allowed ? " · 未允许该角色" : ""}
                      </small>
                    </span>
                  </label>
                  {bound ? (
                    <label className="evaluation-check">
                      <input
                        type="checkbox"
                        checked={bound.forceGuide}
                        onChange={(event) => setBindingForceGuide(activeRole, skill.skillId, event.target.checked)}
                      />
                      <span>强制引导</span>
                    </label>
                  ) : null}
                </div>
              );
            })}
            <div className="row-actions">
              <Button type="button" variant="primary" onClick={() => saveBindings(activeRole)}>
                保存 {ROLE_LABELS[activeRole]} 绑定
              </Button>
            </div>
          </div>
        ) : (
          <Empty>暂无 ACTIVE Skill 可绑定。</Empty>
        )}
      </Card>
    </div>
  );
}
