-- Hard Gate 1: introduce read-only default-qa@2 and bind pi-qa-nextjs-kbr to it.
-- Does not rewrite rd_agent_execution_profile_snapshots (historical snapshots stay frozen).

INSERT INTO rd_agent_tool_policies (policy_id, version, policy_json, policy_hash, enabled)
VALUES (
    'default-qa',
    2,
    '{"hostAllow":["bash","rd_submit_result","read"],"allow":["bash","rd_submit_result","read"],"deny":["edit","write"],"effectiveAllow":["bash","rd_submit_result","read"],"enabled":true}',
    '36de4f978dc75618ad977ad9f87f80150f2e11da10dc94aedd7460af3bcd44de',
    TRUE
)
ON CONFLICT (policy_id, version) DO NOTHING;

UPDATE rd_agent_execution_profiles
SET tool_policy_version = 2,
    version = version + 1,
    updated_at = now()
WHERE profile_id = 'pi-qa-nextjs-kbr'
  AND tool_policy_id = 'default-qa';
