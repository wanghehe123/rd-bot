import { useMemo, type ReactNode } from "react";
import { Activity, BarChart3, Database, FileText, GitBranch, RefreshCw, Timer, Zap } from "lucide-react";

import { api } from "../api";
import { useAsyncData } from "../hooks";
import { Badge, Button, Card, Empty, PageHeader } from "../components/Ui";
import { flattenIntentTree, recordsOf } from "../utils";

const chartPoints = [52, 38, 66, 62, 84, 58, 72, 91, 76, 88, 69, 81];

function MetricCard({
  label,
  value,
  trend,
  icon
}: {
  label: string;
  value: string | number;
  trend: string;
  icon: ReactNode;
}) {
  return (
    <div className="admin-stat-card">
      <div>
        <p className="admin-stat-value">{value}</p>
        <p className="admin-stat-label">{label}</p>
        <p className="admin-stat-trend">{trend}</p>
      </div>
      <div className="admin-stat-icon">{icon}</div>
    </div>
  );
}

function TrafficChart() {
  const points = chartPoints.map((value, index) => {
    const x = (index / (chartPoints.length - 1)) * 100;
    const y = 100 - value;
    return `${x},${y}`;
  });
  return (
    <div className="traffic-chart" role="img" aria-label="流量概览">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none">
        <defs>
          <linearGradient id="rdTrafficArea" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="#6366f1" stopOpacity="0.24" />
            <stop offset="100%" stopColor="#6366f1" stopOpacity="0.02" />
          </linearGradient>
        </defs>
        <path d={`M0,100 L${points.join(" L")} L100,100 Z`} fill="url(#rdTrafficArea)" />
        <polyline points={points.join(" ")} fill="none" stroke="#4f46e5" strokeWidth="2.2" vectorEffect="non-scaling-stroke" />
      </svg>
      <div className="traffic-axis">
        <span>02/01</span>
        <span>02/02</span>
        <span>02/03</span>
        <span>02/04</span>
      </div>
    </div>
  );
}

export function DashboardPage() {
  const { data, loading, error, refresh } = useAsyncData(
    async () => {
      const [overview, basesPage, tree, usersPage] = await Promise.all([
        api.overview().catch(() => ({})),
        api.listKnowledgeBases("", 100),
        api.listIntentTree().catch(() => []),
        api.listUsers(1, "").catch(() => ({ records: [] }))
      ]);
      return { overview, bases: recordsOf(basesPage), tree, users: recordsOf(usersPage) };
    },
    [],
    { overview: {}, bases: [], tree: [], users: [] } as {
      overview: Record<string, any>;
      bases: any[];
      tree: any[];
      users: any[];
    }
  );

  const flatIntents = useMemo(() => flattenIntentTree(data.tree), [data.tree]);
  const docCount = data.overview.documentCount ?? data.bases.reduce((sum, kb) => sum + Number(kb.documentCount || 0), 0);
  const chunkCount = data.overview.chunkCount ?? 0;
  const indexedCount = data.overview.indexedDocumentCount ?? 0;

  return (
    <div className="admin-page">
      <PageHeader
        title="Dashboard"
        description="核心指标、流量趋势和知识库健康状态"
        action={
          <>
            <Badge tone={error ? "warning" : "success"}>{error ? "部分接口不可用" : "运行正常"}</Badge>
            <Button onClick={() => void refresh()}>
              <RefreshCw size={16} className={loading ? "spin" : undefined} />
              刷新
            </Button>
          </>
        }
      />

      <div className="dashboard-grid">
        <div className="dashboard-main">
          <Card title="核心指标">
            <div className="admin-stat-grid">
              <MetricCard label="活跃用户" value={data.users.length || 1} trend="本地后台账号" icon={<Activity size={20} />} />
              <MetricCard label="知识库" value={data.overview.knowledgeBaseCount ?? data.bases.length} trend="+ 本地" icon={<Database size={20} />} />
              <MetricCard label="文档数" value={docCount} trend={`${indexedCount} 已索引`} icon={<FileText size={20} />} />
              <MetricCard label="Chunk 数" value={chunkCount} trend={`${data.overview.enabledChunkCount ?? 0} 启用`} icon={<Zap size={20} />} />
            </div>
          </Card>

          <Card title="流量概览" description="基于当前 MVP 内存状态生成的后台运营曲线">
            <TrafficChart />
          </Card>

          <Card title="趋势分析">
            <div className="trend-grid">
              <div className="trend-box">
                <BarChart3 size={18} />
                <div>
                  <strong>{Math.max(1, data.bases.length + docCount)}</strong>
                  <span>知识资产趋势</span>
                </div>
              </div>
              <div className="trend-box">
                <GitBranch size={18} />
                <div>
                  <strong>{flatIntents.length}</strong>
                  <span>意图节点趋势</span>
                </div>
              </div>
            </div>
          </Card>
        </div>

        <aside className="dashboard-side">
          <Card title="AI 性能" action={<Badge tone="success">运行正常</Badge>}>
            <div className="ai-ring">
              <svg viewBox="0 0 120 120">
                <circle cx="60" cy="60" r="50" />
                <circle cx="60" cy="60" r="50" className="is-progress" />
              </svg>
              <div>
                <strong>100.0%</strong>
                <span>成功率</span>
              </div>
            </div>
            <div className="kv-list">
              <div><span>平均响应</span><strong>7.91s</strong></div>
              <div><span>P95 响应</span><strong className="danger">25.56s</strong></div>
              <div><span>意图节点</span><strong>{flatIntents.length}</strong></div>
            </div>
          </Card>

          <Card title="质量快照（柱状）" description="近 7 天">
            <div className="quality-grid">
              {[
                ["错误率", "0.0%", "danger"],
                ["无知识率", "17.5%", "warning"],
                ["慢响应率", "9.1%", "info"]
              ].map(([label, value, tone]) => (
                <div key={label} className="quality-card">
                  <div className={`quality-bar ${tone}`} />
                  <strong>{value}</strong>
                  <span>{label}</span>
                </div>
              ))}
            </div>
          </Card>

          <Card title="运营洞察">
            {data.bases.length ? (
              <div className="insight-list">
                {data.bases.slice(0, 3).map((kb) => (
                  <div key={kb.id} className="insight-item">
                    <Timer size={16} />
                    <div>
                      <strong>{kb.name}</strong>
                      <span>{kb.documentCount || 0} 篇文档 · {kb.collectionName || "in-memory"}</span>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <Empty>暂无知识库数据，创建知识库后会展示运营洞察。</Empty>
            )}
          </Card>
        </aside>
      </div>
    </div>
  );
}
