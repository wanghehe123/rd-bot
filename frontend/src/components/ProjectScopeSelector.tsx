import { Building2 } from "lucide-react";

import {
  ALL_PROJECTS_SCOPE,
  type ProjectScopeProject
} from "@/hooks/useProjectScope";
import { cn } from "@/lib/utils";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";

export type ProjectScopeSelectorProps = {
  projects: readonly (ProjectScopeProject & { name: string })[];
  projectId: string;
  onProjectChange: (projectId: string) => void;
  allowAll?: boolean;
  loading?: boolean;
  unavailable?: boolean;
  className?: string;
};

/**
 * Presents the shared delivery-project scope without changing the page's layout while data is loading.
 */
export function ProjectScopeSelector({
  projects,
  projectId,
  onProjectChange,
  allowAll = false,
  loading = false,
  unavailable = false,
  className
}: ProjectScopeSelectorProps) {
  const enabledProjects = projects.filter((project) => project.enabled);
  const disabled = loading || unavailable || enabledProjects.length === 0;
  const placeholder = unavailable
    ? "项目服务不可用"
    : loading
      ? "正在加载项目..."
      : enabledProjects.length === 0
        ? "暂无启用项目"
        : "选择项目";

  return (
    <div className={cn("flex min-w-0 items-center gap-2", className)}>
      <Building2 className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <Select value={projectId} onValueChange={onProjectChange} disabled={disabled}>
        <SelectTrigger className="w-full min-w-[11rem] sm:w-[15rem]" aria-label="选择项目">
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent>
          {allowAll ? <SelectItem value={ALL_PROJECTS_SCOPE}>全部项目</SelectItem> : null}
          {enabledProjects.map((project) => (
            <SelectItem key={project.projectId} value={project.projectId}>
              {project.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
