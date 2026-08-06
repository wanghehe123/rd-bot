# Interview claims experiment package

Reproducible home for resume/interview numeric claims. Numbers without a matching
`raw/<run-id>/` artifact and `reports/<run-id>.html` must be labeled **待复现**,
not “已具备”.

## Layout

```text
datasets/<version>/cases.jsonl   # scenario cases (Q1–Q12 linkage)
faults/<fault-id>/               # fault fixtures + raw attachments
runners/                         # verification / replay scripts
raw/<run-id>/                    # immutable run dumps (gitignored when large)
reports/<run-id>.html            # human-readable run report
metrics.json                     # declared metrics + confidence rules
```

## Claim rules

- `0/4 -> 4/4` may only be called a **4-sample pilot**.
- `9/15 -> 15/15`, miss-rate, cache/latency gains: delete or mark **待复现** until
  a raw experiment exists under this package.
- Every number must declare: sample size, baseline, model/temperature, budget,
  run count, success definition, and confidence interval / variance.
- Architecture facts (not metrics): Redis Stream replaced RocketMQ; requirement
  delivery uses PostgreSQL jobs; PRs are created after QA and Delivery Review.

## Verification

```bash
bash benchmarks/interview-claims/runners/verify-package.sh
```

Linked fault IDs must also appear in
`docs/superpowers/qa/fault-injection-matrix.md`.
