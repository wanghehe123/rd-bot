-- Repair p8 seed hashes. The Java store verifies SHA-256(policy_json);
-- the original INSERT used placeholder labels like 'legacy-host-bound-v1',
-- which fail every Pi profile resolution for default strategies.

UPDATE rd_agent_tool_policies
SET policy_hash = '99dd24ef00db28ffc2629f60952c600f1ad7a69df7c666de92dc728864369c1b',
    updated_at = now()
WHERE policy_id = 'legacy-host-bound'
  AND version = 1
  AND policy_hash = 'legacy-host-bound-v1';

UPDATE rd_agent_tool_policies
SET policy_hash = '9236e40eaf19be79cd7da2650e423e3f596f576d853da4afd30e85576b5efd37',
    updated_at = now()
WHERE policy_id = 'default-qa'
  AND version = 1
  AND policy_hash = 'default-qa-v1';

UPDATE rd_agent_tool_policies
SET policy_hash = '9236e40eaf19be79cd7da2650e423e3f596f576d853da4afd30e85576b5efd37',
    updated_at = now()
WHERE policy_id = 'default-qa'
  AND version = 2
  AND policy_hash = 'default-qa-v2';
