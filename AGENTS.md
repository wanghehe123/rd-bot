## Premium model escalation

Before making a high-risk or difficult engineering decision, load the
`model-escalation` skill.

You must consider escalation when the task involves:

- authentication, authorization, payment, encryption or privacy;
- database schema, public API or protocol changes;
- concurrency, transactions, retries, idempotency or distributed consistency;
- destructive or difficult-to-reverse operations;
- conflicting evidence about the root cause;
- two failed implementation or debugging attempts;
- a large final change requiring architectural review.

Do not invoke the premium advisor for routine file edits, formatting,
straightforward CRUD work or obvious test fixes.
