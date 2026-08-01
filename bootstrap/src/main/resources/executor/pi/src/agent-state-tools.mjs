import { Type } from "typebox";
import { defineTool } from "@earendil-works/pi-coding-agent";

export const STATE_TOOL_NAMES = Object.freeze([
  "rd_todo_rewrite",
  "rd_todo_update_status",
  "rd_record_fact",
]);

const actionFields = {
  actionId: Type.String({ minLength: 1 }),
  expectedSequence: Type.Integer({ minimum: 0 }),
  clientSequence: Type.Integer({ minimum: 0 }),
};

function actionResponse(decision) {
  return {
    content: [{
      type: "text",
      text: JSON.stringify({
        actionId: decision.actionId,
        expectedSequence: decision.expectedSequence,
        clientSequence: decision.clientSequence,
        decision: decision.decision,
        reason: decision.reason,
        sequence: decision.sequence,
      }),
    }],
    details: decision,
  };
}

export function createAgentStateTools({ projector, sink }) {
  const tools = [
    defineTool({
      name: "rd_todo_rewrite",
      label: "Rewrite TODO list",
      description: "Propose a full replacement TODO list. Harness validates and accepts or rejects.",
      promptSnippet: "Rewrite the authoritative TODO list for this stage attempt.",
      parameters: Type.Object({
        ...actionFields,
        reason: Type.String({ minLength: 1 }),
        todos: Type.Array(Type.Object({
          id: Type.String({ minLength: 1 }),
          title: Type.String({ minLength: 1 }),
          status: Type.String({ minLength: 1 }),
          acceptanceRefs: Type.Optional(Type.Array(Type.String())),
        }), { minItems: 1 }),
      }),
      executionMode: "sequential",
      async execute(_toolCallId, params) {
        const decision = await projector.applyAction({
          tool: "rd_todo_rewrite",
          actionId: params.actionId,
          expectedSequence: params.expectedSequence,
          clientSequence: params.clientSequence,
          reason: params.reason,
          todos: params.todos,
        });
        await safeLifecycle(sink, decision);
        return actionResponse({
          actionId: params.actionId,
          expectedSequence: params.expectedSequence,
          clientSequence: params.clientSequence,
          decision: decision.decision,
          reason: decision.reason,
          sequence: projector.sequence,
        });
      },
    }),
    defineTool({
      name: "rd_todo_update_status",
      label: "Update TODO status",
      description: "Propose a single TODO status transition with evidence when marking DONE.",
      promptSnippet: "Update one TODO item status with evidence references.",
      parameters: Type.Object({
        ...actionFields,
        todoId: Type.String({ minLength: 1 }),
        targetStatus: Type.String({ minLength: 1 }),
        evidenceRefs: Type.Optional(Type.Array(Type.String())),
        blockerReason: Type.Optional(Type.String()),
      }),
      executionMode: "sequential",
      async execute(_toolCallId, params) {
        const decision = await projector.applyAction({
          tool: "rd_todo_update_status",
          actionId: params.actionId,
          expectedSequence: params.expectedSequence,
          clientSequence: params.clientSequence,
          todoId: params.todoId,
          targetStatus: params.targetStatus,
          evidenceRefs: params.evidenceRefs,
          blockerReason: params.blockerReason,
        });
        await safeLifecycle(sink, decision);
        return actionResponse({
          actionId: params.actionId,
          expectedSequence: params.expectedSequence,
          clientSequence: params.clientSequence,
          decision: decision.decision,
          reason: decision.reason,
          sequence: projector.sequence,
        });
      },
    }),
    defineTool({
      name: "rd_record_fact",
      label: "Record fact",
      description: "Propose a bounded fact. OBSERVED requires a source tool or artifact reference.",
      promptSnippet: "Record a fact with an explicit kind and source.",
      parameters: Type.Object({
        ...actionFields,
        statement: Type.String({ minLength: 1 }),
        expectedKind: Type.String({ minLength: 1 }),
        sourceTool: Type.Optional(Type.String()),
        sourceArtifactId: Type.Optional(Type.String()),
        freshnessPolicy: Type.Optional(Type.String()),
      }),
      executionMode: "sequential",
      async execute(_toolCallId, params) {
        const decision = await projector.applyAction({
          tool: "rd_record_fact",
          actionId: params.actionId,
          expectedSequence: params.expectedSequence,
          clientSequence: params.clientSequence,
          statement: params.statement,
          expectedKind: params.expectedKind,
          sourceTool: params.sourceTool,
          sourceArtifactId: params.sourceArtifactId,
          freshnessPolicy: params.freshnessPolicy,
        });
        await safeLifecycle(sink, decision);
        return actionResponse({
          actionId: params.actionId,
          expectedSequence: params.expectedSequence,
          clientSequence: params.clientSequence,
          decision: decision.decision,
          reason: decision.reason,
          sequence: projector.sequence,
        });
      },
    }),
  ];
  return tools;
}

async function safeLifecycle(sink, decision) {
  if (!sink?.lifecycle) return;
  try {
    await sink.lifecycle("STATE_ACTION_RECORDED", {
      decision: decision.decision,
      reason: decision.reason ?? "",
    });
  } catch {
    // observability must not break tool execution
  }
}
