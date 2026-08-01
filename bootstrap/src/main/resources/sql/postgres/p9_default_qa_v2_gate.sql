-- P3 hard gate #1: bind read-only default-qa@2 to pi-qa-nextjs-kbr.
-- Re-runnable: INSERT is idempotent; UPDATE only bumps profiles still on v1.
-- Does not touch historical execution-profile snapshots or stage artifacts.

INSERT INTO rd_agent_tool_policies (policy_id, version, policy_json, policy_hash, enabled)
VALUES (
    'default-qa',
    2,
    '{"hostAllow":["bash","rd_submit_result","read"],"allow":["bash","rd_submit_result","read"],"deny":["edit","write"]}',
    'default-qa-v2',
    TRUE
)
ON CONFLICT (policy_id, version) DO NOTHING;

UPDATE rd_agent_execution_profiles
SET tool_policy_version = 2,
    version = version + 1,
    updated_at = now()
WHERE profile_id = 'pi-qa-nextjs-kbr'
  AND project_id = 7487468535443230720
  AND tool_policy_version < 2;
