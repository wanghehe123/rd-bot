import type { AgentRuntimeEvent } from "@/services/executionTraceService";

export type AgentTraceState = {
  events: AgentRuntimeEvent[];
  seenSequences: number[];
};

export type NarrativeTraceStep = {
  kind: "NARRATIVE";
  id: string;
  firstSequence: number;
  lastSequence: number;
  startedAt: string;
  completedAt?: string;
  markdown: string;
  streaming: boolean;
};

export type ToolTraceStep = {
  kind: "TOOL";
  id: string;
  firstSequence: number;
  lastSequence: number;
  toolCallId: string;
  toolName: string;
  displaySummary: string;
  outputPreview?: string;
  status: "RUNNING" | "SUCCEEDED" | "FAILED" | "BLOCKED";
  startedAt: string;
  completedAt?: string;
};

export type SystemTraceStep = {
  kind: "SYSTEM";
  id: string;
  firstSequence: number;
  lastSequence: number;
  occurredAt: string;
  tone: "WARNING" | "ERROR";
  summary: string;
};

export type ResultTraceStep = {
  kind: "RESULT";
  id: string;
  firstSequence: number;
  lastSequence: number;
  occurredAt: string;
  status: "SUBMITTED" | "REJECTED";
  summary: string;
};

export type AgentTraceStep =
  | NarrativeTraceStep
  | ToolTraceStep
  | SystemTraceStep
  | ResultTraceStep;

export function appendAgentTraceEvents(
  current: AgentTraceState,
  incoming: AgentRuntimeEvent[]
): AgentTraceState {
  const known = new Set(current.seenSequences);
  const additions = incoming.filter((event) => {
    const sequence = eventSequence(event);
    if (sequence <= 0 || known.has(sequence)) return false;
    known.add(sequence);
    return true;
  });
  if (additions.length === 0) return current;

  const events = [...current.events, ...additions]
    .sort((left, right) => eventSequence(left) - eventSequence(right));
  return {
    events,
    seenSequences: events.map(eventSequence)
  };
}

export function buildAgentTraceSteps(state: AgentTraceState): AgentTraceStep[] {
  const steps: AgentTraceStep[] = [];
  const toolStepIndexByCallId = new Map<string, number>();
  const openToolCallIdByName = new Map<string, string>();
  let activeNarrativeIndex: number | null = null;

  for (const event of state.events) {
    const sequence = eventSequence(event);
    const occurredAt = event.occurredAt;
    switch (event.eventType) {
      case "ASSISTANT_TEXT_DELTA": {
        const delta = rawStringField(event.payload, "delta");
        if (!delta) break;
        const active = activeNarrativeIndex === null ? null : steps[activeNarrativeIndex];
        if (active?.kind === "NARRATIVE" && active.streaming) {
          active.markdown += delta;
          active.lastSequence = sequence;
        } else {
          steps.push({
            kind: "NARRATIVE",
            id: `narrative-${sequence}`,
            firstSequence: sequence,
            lastSequence: sequence,
            startedAt: occurredAt,
            markdown: delta,
            streaming: true
          });
          activeNarrativeIndex = steps.length - 1;
        }
        break;
      }
      case "ASSISTANT_TEXT_COMPLETED": {
        completeActiveNarrative(steps, activeNarrativeIndex, sequence, occurredAt);
        activeNarrativeIndex = null;
        break;
      }
      case "TOOL_STARTED": {
        completeActiveNarrative(steps, activeNarrativeIndex, sequence - 1, undefined);
        activeNarrativeIndex = null;
        const toolCallId = resolveToolCallId(event, openToolCallIdByName);
        const toolName = stringField(event.payload, "toolName") || "tool";
        const existingIndex = toolStepIndexByCallId.get(toolCallId);
        if (existingIndex === undefined) {
          steps.push(newToolStep(event, toolCallId, toolName));
          toolStepIndexByCallId.set(toolCallId, steps.length - 1);
        }
        openToolCallIdByName.set(toolName, toolCallId);
        break;
      }
      case "TOOL_PROGRESS":
      case "TOOL_COMPLETED":
      case "TOOL_BLOCKED": {
        completeActiveNarrative(steps, activeNarrativeIndex, sequence - 1, undefined);
        activeNarrativeIndex = null;
        const toolName = stringField(event.payload, "toolName") || "tool";
        const toolCallId = resolveToolCallId(event, openToolCallIdByName);
        let index = toolStepIndexByCallId.get(toolCallId);
        if (index === undefined) {
          steps.push(newToolStep(event, toolCallId, toolName));
          index = steps.length - 1;
          toolStepIndexByCallId.set(toolCallId, index);
        }
        const step = steps[index];
        if (step?.kind === "TOOL") {
          updateToolStep(step, event);
          if (event.eventType === "TOOL_COMPLETED" || event.eventType === "TOOL_BLOCKED") {
            openToolCallIdByName.delete(step.toolName);
          }
        }
        break;
      }
      case "PROTOCOL_ERROR":
      case "EXTENSION_FAILED": {
        completeActiveNarrative(steps, activeNarrativeIndex, sequence - 1, undefined);
        activeNarrativeIndex = null;
        steps.push({
          kind: "SYSTEM",
          id: `system-${sequence}`,
          firstSequence: sequence,
          lastSequence: sequence,
          occurredAt,
          tone: "ERROR",
          summary: eventSummary(event) || runtimeLabel(event.eventType)
        });
        break;
      }
      case "PROVIDER_RETRYING": {
        steps.push({
          kind: "SYSTEM",
          id: `system-${sequence}`,
          firstSequence: sequence,
          lastSequence: sequence,
          occurredAt,
          tone: "WARNING",
          summary: eventSummary(event) || "Provider retrying"
        });
        break;
      }
      case "RESULT_SUBMITTED":
      case "RESULT_REJECTED": {
        completeActiveNarrative(steps, activeNarrativeIndex, sequence - 1, undefined);
        activeNarrativeIndex = null;
        steps.push({
          kind: "RESULT",
          id: `result-${sequence}`,
          firstSequence: sequence,
          lastSequence: sequence,
          occurredAt,
          status: event.eventType === "RESULT_REJECTED" ? "REJECTED" : "SUBMITTED",
          summary: eventSummary(event) || runtimeLabel(event.eventType)
        });
        break;
      }
      default:
        break;
    }
  }

  return steps;
}

function newToolStep(event: AgentRuntimeEvent, toolCallId: string, toolName: string): ToolTraceStep {
  const sequence = eventSequence(event);
  return {
    kind: "TOOL",
    id: `tool-${toolCallId}`,
    firstSequence: sequence,
    lastSequence: sequence,
    toolCallId,
    toolName,
    displaySummary: stringField(event.payload, "displaySummary") || toolName,
    outputPreview: outputPreview(event.payload),
    status: event.eventType === "TOOL_BLOCKED" ? "BLOCKED" : "RUNNING",
    startedAt: event.occurredAt,
    completedAt: event.eventType === "TOOL_BLOCKED" ? event.occurredAt : undefined
  };
}

function updateToolStep(step: ToolTraceStep, event: AgentRuntimeEvent): void {
  step.lastSequence = eventSequence(event);
  const displaySummary = stringField(event.payload, "displaySummary");
  if (displaySummary) step.displaySummary = displaySummary;
  const preview = outputPreview(event.payload);
  if (preview) step.outputPreview = preview;

  if (event.eventType === "TOOL_COMPLETED") {
    step.status = Boolean(event.payload.isError) ? "FAILED" : "SUCCEEDED";
    step.completedAt = event.occurredAt;
  } else if (event.eventType === "TOOL_BLOCKED") {
    step.status = "BLOCKED";
    step.completedAt = event.occurredAt;
  }
}

function completeActiveNarrative(
  steps: AgentTraceStep[],
  index: number | null,
  lastSequence: number,
  completedAt: string | undefined
): void {
  const step = index === null ? null : steps[index];
  if (step?.kind !== "NARRATIVE" || !step.streaming) return;
  step.lastSequence = Math.max(step.lastSequence, lastSequence);
  step.completedAt = completedAt;
  step.streaming = false;
}

function resolveToolCallId(
  event: AgentRuntimeEvent,
  openToolCallIdByName: Map<string, string>
): string {
  const explicit = stringField(event.payload, "toolCallId");
  if (explicit) return explicit;
  const toolName = stringField(event.payload, "toolName");
  return (toolName && openToolCallIdByName.get(toolName)) || `${toolName || "tool"}-${eventSequence(event)}`;
}

function outputPreview(payload: Record<string, unknown>): string | undefined {
  return stringField(payload, "outputPreview") || undefined;
}

function eventSummary(event: AgentRuntimeEvent): string {
  for (const key of ["summary", "error", "reason", "status", "stopReason"]) {
    const value = stringField(event.payload, key);
    if (value) return value;
  }
  return "";
}

function stringField(payload: Record<string, unknown>, key: string): string {
  const value = payload[key];
  return typeof value === "string" ? value.trim() : "";
}

function rawStringField(payload: Record<string, unknown>, key: string): string {
  const value = payload[key];
  return typeof value === "string" ? value : "";
}

function eventSequence(event: AgentRuntimeEvent): number {
  return event.sequence || event.sourceSequence || 0;
}

function runtimeLabel(eventType: string): string {
  return eventType.replace(/_/g, " ").toLowerCase();
}
