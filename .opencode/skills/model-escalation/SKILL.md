---
name: model-escalation
description: Decide when a coding task requires review by the premium-advisor subagent, construct a compact decision packet, invoke the advisor, and integrate its recommendation while controlling cost.
compatibility: OpenCode
metadata:
  category: orchestration
  purpose: premium-model-escalation
---

# Model Escalation Strategy

## When to Escalate (Mandatory)

Escalate to `premium-advisor` if ANY of the following applies:

### Security & Auth
- Authentication or authorization changes
- Token, key, or credential management
- Payment or financial logic
- Encryption
- User privacy
- Command/code injection via external input

### Data & Consistency
- Database schema change
- Data migration
- Data deletion or overwrite
- Distributed transactions
- Duplicate message delivery
- Idempotency
- Cache consistency
- Retries and compensation
- Multi-instance concurrent updates

### Architecture & Compatibility
- Public API change
- Serialization format change
- Network protocol change
- Message/event structure change
- Changes spanning 3+ modules
- New core dependency introduced
- Choice between multiple architecture options
- Changes that are hard to rollback

### Debugging & Uncertainty
- 2+ plausible root cause candidates
- Conflicting evidence (logs, code, tests contradict each other)
- Main agent confidence below 70
- Two failed implementation/debugging attempts
- Regression after fixing one issue
- Cannot explain why the current fix addresses the root cause

### Final Review
Call advisor once before completing if ANY:
- >5 files modified
- Core logic diff >300 lines
- Touches critical path
- Production incident fix
- User explicitly requests strict review
- Important PR imminent

## When NOT to Escalate

- Copy/text changes
- Code formatting
- Variable renaming
- Simple CRUD
- Obvious null-pointer fix
- Adding ordinary unit tests
- Locating files
- Explaining existing code
- Running existing tests
- Issues directly detectable by compiler or tests
- Same question already answered with no new evidence

## Decision Packet Format

Before calling the advisor, build a compact Decision Packet. DO NOT send full chat history.

```json
{
  "task_goal": "What the user ultimately wants to achieve",
  "current_state": "What has been completed so far",
  "decision": "The single core decision the advisor must judge",
  "constraints": ["Must-satisfy constraints"],
  "evidence": [
    {
      "source": "src/example.ts:40-78",
      "fact": "Fact derived from code or logs"
    }
  ],
  "assumptions": ["Unverified assumptions"],
  "candidate_options": [
    {
      "name": "Option A",
      "advantages": ["Pros"],
      "risks": ["Risks"]
    }
  ],
  "current_preference": "Main agent's current preference and why",
  "question": "One clear question for the advisor"
}
```

**Packet constraints:**
- One core decision only
- No full source file pastes; use file paths and key line ranges
- Distinguish facts from assumptions
- List attempted solutions and failure symptoms
- State business constraints
- Target ~2000–5000 tokens
- Never send vague questions like "is this okay?"

## Invocation Flow

1. **Judge escalation**: output internal decision (`Escalation required: yes / Trigger: ...`)
2. **Do cheap exploration first**: locate files, collect logs, trace calls, find tests, list options, identify unknowns
3. **Build Decision Packet**: keep only decision-relevant info
4. **Call via Task tool** with target `premium-advisor`:
   ```
   Review the following Decision Packet.
   Do not implement the change.
   Return only the required JSON review object.

   <decision_packet>
   ...
   </decision_packet>
   ```
5. **Validate response**: if malformed, extract conclusion manually; only retry once if completely unreadable
6. **Integrate**: check against code facts, test/compile results; adopt, partially adopt, or reject; never blindly copy advisor code; record reasons

## Call Budget (Session-Level)

| Task Risk Level | Max Premium Calls |
|-----------------|-------------------|
| Routine | 0 |
| Medium risk | 1 |
| High risk | 2 |
| Production incident / critical migration | 3 |

Rules:
- No repeat calls for the same decision without new evidence
- Second call allowed only if: insufficient_evidence now resolved, advisor suggestion failed in tests, new critical fact emerged, two advisor opinions need arbitration, or user explicitly requests re-review

## Failure Fallback

### Advisor unavailable (timeout, rate limit, no balance, provider error)

1. Auto-retry once max
2. Inform user premium review is unavailable
3. Mark task as "pending premium review"
4. Pause irreversible changes for high-risk tasks
5. Continue with most conservative option for routine tasks

### Advisor returns `insufficient_evidence`

- Only gather the specific missing evidence listed
- Do not restart full-repository search

### Advisor advice conflicts with test results

Credibility priority:
```
Repeatable test results
> Compiler / type system / static analysis
> Clear code and log evidence
> Advisor recommendation
> Main model intuition
```

## Security

- Never send `.env`, secrets, tokens to the advisor
- Do not transmit unrelated user data
- Advisor is read-only and cannot call other agents or expand workspace
- Log metadata only (no full prompts or source code):
  ```json
  {
    "timestamp": "ISO-8601",
    "task_id": "...",
    "trigger": "security | consistency | repeated_failure | final_review",
    "main_model": "provider/model",
    "advisor_model": "provider/model",
    "input_size": 0,
    "output_size": 0,
    "latency_ms": 0,
    "verdict": "approve | revise | reject | insufficient_evidence",
    "accepted": true,
    "task_success": true
  }
  ```
