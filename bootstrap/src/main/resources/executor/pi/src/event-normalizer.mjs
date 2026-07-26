import { EVENT_PROTOCOL, normalizePiEvent, redact } from "./protocol.mjs";

export class EventNormalizer {
  #sourceSequence = 0;

  next(rawEvent, context) {
    this.#sourceSequence += 1;
    return normalizePiEvent(rawEvent, context, this.#sourceSequence);
  }

  lifecycle(eventType, context, payload = {}) {
    this.#sourceSequence += 1;
    return {
      protocol: EVENT_PROTOCOL,
      eventType,
      sourceSequence: this.#sourceSequence,
      stageRunId: context.stageRunId,
      taskId: context.taskId,
      role: context.role,
      runtimeType: context.runtimeType ?? "PI",
      snapshotId: context.snapshotId ?? "",
      provider: context.provider ?? "",
      model: context.model ?? "",
      occurredAt: new Date().toISOString(),
      payload: redact(payload),
      redacted: true,
    };
  }

  sequence() {
    return this.#sourceSequence;
  }
}
