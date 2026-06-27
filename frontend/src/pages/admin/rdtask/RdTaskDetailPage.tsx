import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ChevronLeft, Pause, Play } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { RelativeTime } from "@/components/RelativeTime";
import { getErrorMessage } from "@/utils/error";
import { cn } from "@/lib/utils";

import {
  getRdTask,
  getRdTaskTimeline,
  pauseRdTask,
  resumeRdTask,
  STATUS_BADGE_CLASS,
  type RdTask,
  type RdTaskStatusEvent
} from "@/services/rdTaskService";

const formatDuration = (ms?: number) => {
  if (!ms || ms <= 0) return "首步";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${(ms / 60_000).toFixed(1)}min`;
  return `${(ms / 3_600_000).toFixed(1)}h`;
};

const EVENT_DOT_TONE: Record<string, string> = {
  CREATED: "bg-blue-500",
  SEARCHING: "bg-amber-500",
  EXECUTING: "bg-indigo-500",
  COMMITTED: "bg-emerald-500",
  MERGED: "bg-green-600",
  REJECTED: "bg-red-500",
  PAUSED: "bg-slate-400",
  RESUMED: "bg-cyan-500",
  DELETED: "bg-slate-400"
};

export function RdTaskDetailPage() {
  const { taskId = "" } = useParams();
  const navigate = useNavigate();
  const [task, setTask] = useState<RdTask | null>(null);
  const [events, setEvents] = useState<RdTaskStatusEvent[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const [detail, timeline] = await Promise.all([
        getRdTask(taskId),
        getRdTaskTimeline(taskId)
      ]);
      setTask(detail);
      setEvents(timeline || []);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载任务详情失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [taskId]);

  const handleTogglePause = async () => {
    if (!task) return;
    try {
      const updated = task.paused
        ? await resumeRdTask(task.taskId)
        : await pauseRdTask(task.taskId);
      setTask(updated);
      toast.success(updated.paused ? "已暂停" : "已恢复");
      const timeline = await getRdTaskTimeline(task.taskId);
      setEvents(timeline || []);
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    }
  };

  if (loading) {
    return (
      <div className="admin-page">
        <div className="py-12 text-center text-muted-foreground">加载中...</div>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="admin-page">
        <div className="py-12 text-center text-muted-foreground">任务不存在</div>
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-page-header">
          <div>
            <h1 className="admin-page-title">任务详情</h1>
            <p className="admin-page-subtitle">{task.title || task.taskId}</p>
          </div>
          <div className="admin-page-actions">
            <Button variant="outline" onClick={() => navigate("/admin/rd-tasks")}>
              <ChevronLeft className="mr-2 h-4 w-4" />
              返回列表
            </Button>
            <Button variant="outline" onClick={handleTogglePause}>
              {task.paused ? (
                <>
                  <Play className="mr-2 h-4 w-4" />
                  恢复
                </>
              ) : (
                <>
                  <Pause className="mr-2 h-4 w-4" />
                  暂停
                </>
              )}
            </Button>
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>基本信息</CardTitle>
            <CardDescription>任务元数据与当前状态</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-4 text-sm md:grid-cols-3">
              <InfoField label="任务 ID" value={task.taskId} mono />
              <InfoField label="任务类型" value={task.taskType} />
              <InfoField label="优先级" value={task.priority} />
              <div>
                <div className="mb-1 text-xs text-muted-foreground">状态</div>
                <div className="flex items-center gap-2">
                  <Badge variant="outline" className={STATUS_BADGE_CLASS[task.status] || ""}>{task.status}</Badge>
                  {task.paused ? (
                    <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-700">已暂停</Badge>
                  ) : null}
                </div>
              </div>
              <InfoField label="工单 ID" value={task.ticketId || "-"} />
              <InfoField label="工单标题" value={task.ticketTitle || "-"} />
              <div>
                <div className="mb-1 text-xs text-muted-foreground">创建时间</div>
                <RelativeTime value={new Date(task.createTimeEpochMillis).toISOString()} />
              </div>
              <div>
                <div className="mb-1 text-xs text-muted-foreground">更新时间</div>
                <RelativeTime value={new Date(task.updateTimeEpochMillis).toISOString()} />
              </div>
              <InfoField
                label="PR 链接"
                value={
                  task.pullRequestUrl ? (
                    <a
                      href={task.pullRequestUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="text-primary underline"
                    >
                      {task.pullRequestUrl}
                    </a>
                  ) : (
                    "-"
                  )
                }
              />
            </div>
            {task.errorMessage ? (
              <div className="mt-4 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                <div className="mb-1 font-medium">错误 / 打回原因</div>
                {task.errorMessage}
              </div>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>全链路时间线</CardTitle>
            <CardDescription>任务经历的状态与每步耗时</CardDescription>
          </CardHeader>
          <CardContent>
            {events.length === 0 ? (
              <div className="py-8 text-center text-muted-foreground">暂无状态事件</div>
            ) : (
              <ol className="relative space-y-6 border-l border-slate-200 pl-6">
                {events.map((event, index) => (
                  <li key={`${event.id}-${index}`} className="relative">
                    <span
                      className={cn(
                        "absolute -left-[31px] top-1 h-3 w-3 rounded-full ring-4 ring-white",
                        EVENT_DOT_TONE[event.status] || "bg-slate-300"
                      )}
                    />
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant="outline" className={STATUS_BADGE_CLASS[event.status] || ""}>
                        {event.status}
                      </Badge>
                      <span className="text-xs text-muted-foreground">
                        {event.trigger} · {formatDuration(event.durationMillis)}
                      </span>
                    </div>
                    <div className="mt-1 text-sm text-slate-700">
                      {event.title || event.message || "无说明"}
                    </div>
                    {event.message ? (
                      <div className="mt-0.5 text-xs text-muted-foreground">{event.message}</div>
                    ) : null}
                    <div className="mt-1 text-xs text-muted-foreground">
                      <RelativeTime value={new Date(event.enteredAtEpochMillis).toISOString()} />
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </CardContent>
        </Card>

        {task.promptSnapshot ? (
          <Card>
            <CardHeader>
              <CardTitle>Prompt 快照</CardTitle>
            </CardHeader>
            <CardContent>
              <pre className="overflow-auto rounded-lg bg-slate-50 p-4 text-xs leading-relaxed text-slate-600">
                {task.promptSnapshot}
              </pre>
            </CardContent>
          </Card>
        ) : null}
      </div>
  );
}

function InfoField({
  label,
  value,
  mono
}: {
  label: string;
  value: React.ReactNode;
  mono?: boolean;
}) {
  return (
    <div>
      <div className="mb-1 text-xs text-muted-foreground">{label}</div>
      <div className={cn("text-sm text-slate-800", mono && "font-mono break-all")}>{value}</div>
    </div>
  );
}
