import { Badge } from "@/components/ui/badge";
import {
  operationBadgeClass,
  operationStatusLabel,
  projectionBadgeClass,
  projectionStatusLabel
} from "@/services/openVikingKnowledgePresentation";

export function OpenVikingStatusBadge({
  status,
  kind = "projection"
}: {
  status?: string | null;
  kind?: "projection" | "operation";
}) {
  const className = kind === "operation" ? operationBadgeClass(status) : projectionBadgeClass(status);
  const label = kind === "operation" ? operationStatusLabel(status) : projectionStatusLabel(status);
  return (
    <Badge variant="outline" className={className}>
      {label}
    </Badge>
  );
}
