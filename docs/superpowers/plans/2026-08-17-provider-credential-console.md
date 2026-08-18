# Provider Credential Console — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admins can save a per-provider API key in PostgreSQL; requirement reviewers and Docker/Pi executors resolve that key before process env, and HTTP never returns the secret.

**Architecture:** Keep `ModelProviderProfile` metadata-only. New `ModelProviderCredentialStore` keyed by `providerId`. Composite `AuthEnvironmentResolver` (stored secret by env name → `System.getenv` → `launchctl`). Bootstrap admin API + `p16`. Same mutation token as the agent-strategy console.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis, PostgreSQL (`ragent` locally), JUnit 5.

**OpenSpec:** `openspec/changes/provider-credential-console/`. Read proposal/design/spec before coding. Do not edit `openspec/specs/` directly.

**Frontend:** Out of scope. Hand the other agent `docs/superpowers/plans/2026-08-17-provider-credential-console-frontend.md`. Do not add React pages here. Do not change Vite except if a backend test fixture already lives there (it already proxies `/admin/model-provider-profiles`).

**Locked decisions:**

- Separate credential table, not a column on `rd_model_provider_profiles`.
- GET never includes `apiKey`. Metadata PUT that contains `apiKey` is 400.
- No at-rest encryption in v1. No provider delete in v1.
- Not OpenViking / MinIO keys.
- Local apply: `POSTGRES_DB=ragent` (not compose default `rdbot`).

**Read first:** `RULE.md` §1, `ModelProviderProfile.java`, `ModelProviderProfileService.java`, `OpenAiChatCompletionsRepairExecutor.java`, `DockerClaudeCodeExecutor.AuthEnvironmentResolver`, `AgentExecutionProfileAdminController.java`, `AgentRuntimeMutationAccessPolicy.java`.

---

## File map

**Create (rag)**

- `rag/src/main/java/com/wish/rd/rag/project/agent/model/ModelProviderCredential.java`
- `rag/src/main/java/com/wish/rd/rag/project/agent/ModelProviderCredentialStore.java`
- `rag/src/main/java/com/wish/rd/rag/project/agent/ModelProviderCredentialService.java`
- `rag/src/main/java/com/wish/rd/rag/project/agent/impl/InMemoryModelProviderCredentialStore.java`
- `rag/src/test/java/com/wish/rd/rag/project/agent/ModelProviderCredentialServiceTest.java`

**Create (bootstrap)**

- `bootstrap/src/main/resources/sql/postgres/p16_model_provider_credentials.sql`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/ModelProviderCredentialRow.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/ModelProviderCredentialMapper.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresModelProviderCredentialStore.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/StoredThenSystemAuthEnvironmentResolver.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/StoredThenSystemAuthEnvironmentResolverTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/agent/ModelProviderAdminControllerTest.java`

**Modify**

- `rag/.../model/ModelProviderProfile.java` — do not add a secret field
- `bootstrap/.../AgentExecutionProfileAdminController.java` — list view + PUT metadata + PUT credential
- `bootstrap/.../OpenAiChatCompletionsExecutorConfiguration.java` — inject resolver
- `bootstrap/.../OpenAiChatCompletionsRepairExecutor.java` — fail message; use injected supplier
- `bootstrap/.../DockerExecutorConfiguration.java` and `AgentRuntimeExecutorConfiguration.java` — wire composite resolver
- `bootstrap/src/main/resources/sql/postgres/README.md` — row 16
- `RULE.md` — append the credential-store constraint
- existing `OpenAiChatCompletionsRepairExecutorTest.java`

---

### Task 1: Credential service (TDD, rag only)

**Files:**

- Create: `rag/src/test/java/com/wish/rd/rag/project/agent/ModelProviderCredentialServiceTest.java`
- Create: the rag types/store/service listed above

- [ ] **Step 1: Write the failing test**

```java
package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderCredentialStore;
import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderProfileStore;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProviderCredentialServiceTest {

    @Test
    void resolvesNewestSecretByEnvironmentVariableWithoutExposingItOnTheProfile() {
        InMemoryModelProviderProfileStore profiles = new InMemoryModelProviderProfileStore();
        profiles.save(new ModelProviderProfile(
                "opencode-go", "OpenCode Go", ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://opencode.ai/zen/go/v1", "deepseek-v4-flash",
                "OPENCODE_API_KEY", false, true, 1L
        ));
        ModelProviderCredentialService service = new ModelProviderCredentialService(
                new InMemoryModelProviderCredentialStore(), profiles
        );

        service.put("opencode-go", "sk-live-from-ui");

        assertEquals("sk-live-from-ui", service.resolveByEnvironmentVariable("OPENCODE_API_KEY"));
        assertTrue(service.status("opencode-go").configured());
        assertEquals("", profiles.find("opencode-go").orElseThrow().credentialValue());
        assertFalse(service.status("opencode-go").toString().contains("sk-live"));
    }

    @Test
    void blankApiKeyClearsTheStoredSecret() {
        InMemoryModelProviderProfileStore profiles = new InMemoryModelProviderProfileStore();
        profiles.save(new ModelProviderProfile(
                "opencode-go", "OpenCode Go", ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://opencode.ai/zen/go/v1", "deepseek-v4-flash",
                "OPENCODE_API_KEY", false, true, 1L
        ));
        ModelProviderCredentialService service = new ModelProviderCredentialService(
                new InMemoryModelProviderCredentialStore(), profiles
        );
        service.put("opencode-go", "sk-live-from-ui");
        service.put("opencode-go", "  ");
        assertEquals("", service.resolveByEnvironmentVariable("OPENCODE_API_KEY"));
        assertFalse(service.status("opencode-go").configured());
    }

    @Test
    void unknownProviderIsRejected() {
        ModelProviderCredentialService service = new ModelProviderCredentialService(
                new InMemoryModelProviderCredentialStore(),
                new InMemoryModelProviderProfileStore()
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.put("missing", "sk-x")
        );
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile / fail**

```bash
./mvnw -pl rag -Dtest=ModelProviderCredentialServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: cannot find `ModelProviderCredentialService`.

- [ ] **Step 3: Implement the minimal types**

`ModelProviderCredential` record: `providerId`, `secret` (service-internal only; never put on a controller DTO).

`ModelProviderCredentialStatus` record: `providerId`, `boolean configured`, `Long updatedAtEpochMillis` (nullable). `toString()` must not include the secret.

`ModelProviderCredentialStore`: `void save(String providerId, String secret, long updatedAtEpochMillis)`, `void delete(String providerId)`, `Optional<Stored>` find.

`InMemoryModelProviderCredentialStore`: `ConcurrentHashMap`.

`ModelProviderCredentialService`:

- `put`: strip secret; blank → delete; else require profile exists; save.
- `status`: configured if row exists and secret non-blank.
- `resolveByEnvironmentVariable(env)`: among profiles whose `credentialEnvironmentVariable` equals env (case-insensitive after uppercasing), pick the configured row with max `updatedAtEpochMillis`; else `""`.

Do not add a secret field to `ModelProviderProfile`.

- [ ] **Step 4: Re-run the test**

```bash
./mvnw -pl rag -Dtest=ModelProviderCredentialServiceTest,ModelProviderProfileServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. Existing profile test still asserts `credentialValue()` is empty.

- [ ] **Step 5: Commit**

```bash
git add rag/src/main/java/com/wish/rd/rag/project/agent rag/src/test/java/com/wish/rd/rag/project/agent
git commit -m "$(cat <<'EOF'
feat: store model provider API keys off the profile record

EOF
)"
```

---

### Task 2: Composite auth resolver

**Files:**

- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/StoredThenSystemAuthEnvironmentResolver.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/StoredThenSystemAuthEnvironmentResolverTest.java`

- [ ] **Step 1: Failing test**

```java
@Test
void prefersStoredSecretOverProcessEnvironment() {
    InMemoryModelProviderProfileStore profiles = new InMemoryModelProviderProfileStore();
    profiles.save(new ModelProviderProfile(
            "opencode-go", "OpenCode Go", ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://opencode.ai/zen/go/v1", "m", "OPENCODE_API_KEY", false, true, 1L
    ));
    ModelProviderCredentialService credentials = new ModelProviderCredentialService(
            new InMemoryModelProviderCredentialStore(), profiles
    );
    credentials.put("opencode-go", "from-ui");
    var resolver = new StoredThenSystemAuthEnvironmentResolver(
            credentials, env -> "from-process"
    );
    assertEquals("from-ui", resolver.resolve("OPENCODE_API_KEY"));
}

@Test
void fallsBackToDelegateWhenNothingStored() {
    var resolver = new StoredThenSystemAuthEnvironmentResolver(
            new ModelProviderCredentialService(
                    new InMemoryModelProviderCredentialStore(),
                    new InMemoryModelProviderProfileStore()
            ),
            env -> "from-process"
    );
    assertEquals("from-process", resolver.resolve("OPENCODE_API_KEY"));
}
```

- [ ] **Step 2: Implement**

```java
public final class StoredThenSystemAuthEnvironmentResolver
        implements DockerClaudeCodeExecutor.AuthEnvironmentResolver {
    public StoredThenSystemAuthEnvironmentResolver(
            ModelProviderCredentialService credentials,
            DockerClaudeCodeExecutor.AuthEnvironmentResolver fallback
    ) { ... }

    @Override
    public String resolve(String envName) {
        String stored = credentials.resolveByEnvironmentVariable(envName);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return fallback.resolve(envName);
    }
}
```

Fallback in production: `AuthEnvironmentResolver.system()`.

- [ ] **Step 3: Test**

```bash
./mvnw -pl bootstrap -am -Dtest=StoredThenSystemAuthEnvironmentResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 4: Commit** `feat: resolve provider credentials from the store before process env`

---

### Task 3: OpenAI chat executor uses the resolver

**Files:**

- Modify: `OpenAiChatCompletionsRepairExecutor.java` (constructor already takes `Supplier<String>`)
- Modify: `OpenAiChatCompletionsExecutorConfiguration.java`
- Modify: `OpenAiChatCompletionsRepairExecutorTest.java`

- [ ] **Step 1: Extend the existing executor test**

Add a case: supplier returns `"from-ui"`, configuration `apiKeyEnv=OPENCODE_API_KEY`, and the request is attempted (mock HttpClient or keep the existing failed-path style). The important assertion is the missing-key message **does not fire** when the supplier is non-blank, and **does fire** with a message that mentions both the env name and the admin page:

`missing API key for OPENCODE_API_KEY; set it under 供应商配置 or export the environment variable`

Do not include the secret in the message.

- [ ] **Step 2: Change the production constructor wiring**

In `OpenAiChatCompletionsExecutorConfiguration.openAiChatCompletionsRepairExecutor`:

```java
return new OpenAiChatCompletionsRepairExecutor(
        properties.toExecutorConfiguration(),
        HttpClient.newHttpClient(),
        () -> resolver.resolve(properties.getApiKeyEnv())
);
```

Inject `StoredThenSystemAuthEnvironmentResolver` (or the `AuthEnvironmentResolver` interface).

Update the default constructor used by tests that do not pass a supplier so it still uses `System.getenv` only when no resolver is provided — existing unit tests that inject a supplier stay valid.

- [ ] **Step 3: Run**

```bash
./mvnw -pl bootstrap -am -Dtest=OpenAiChatCompletionsRepairExecutorTest,OpenAiChatCompletionsExecutorConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 4: Commit** `fix: model-only executor reads UI-stored API keys`

---

### Task 4: Wire Docker Claude and Pi to the same resolver

**Files:**

- Modify: `bootstrap/.../DockerExecutorConfiguration.java` (search `AuthEnvironmentResolver.system()`)
- Modify: `bootstrap/.../AgentRuntimeExecutorConfiguration.java` (`dockerPiAgentExecutor` currently hard-codes `.system()`)

- [ ] **Step 1: Replace both `AuthEnvironmentResolver.system()` call sites** with the Spring bean `StoredThenSystemAuthEnvironmentResolver`.

If the Claude configuration class cannot see the credential service when `agent-runtime.enabled=false`, still register `StoredThenSystemAuthEnvironmentResolver` in `AgentRuntimeControlPlaneConfiguration` (that class already creates the profile service) as `@ConditionalOnMissingBean`. Claude/Pi configs then `ObjectProvider.getIfAvailable(AuthEnvironmentResolver::system)`.

- [ ] **Step 2: Run focused executor config tests that already exist** (Pi isolation / Docker config). Do not expand Docker image tests.

```bash
./mvnw -pl bootstrap -am -Dtest=AgentRuntimeExecutorConfigurationTest,DockerExecutorConfigurationTest,OpenAiChatCompletionsExecutorConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

If a named test class does not exist, run the closest existing configuration test rather than inventing one. At minimum the module must compile.

- [ ] **Step 3: Commit** `feat: share stored provider credentials with Docker and Pi resolvers`

---

### Task 5: PostgreSQL p16 + store

**Files:**

- Create: `bootstrap/src/main/resources/sql/postgres/p16_model_provider_credentials.sql`
- Modify: `bootstrap/src/main/resources/sql/postgres/README.md`
- Create mapper/entity/store as in the file map
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresHostVerificationStoreTest.java` is the pattern — add `PostgresModelProviderCredentialStoreTest` that reads the SQL file text (like host-verify) if a live Postgres test is too heavy. Prefer a file-content assertion plus an in-memory mapping test.

SQL:

```sql
CREATE TABLE IF NOT EXISTS rd_model_provider_credentials (
    provider_id VARCHAR(128) PRIMARY KEY
        REFERENCES rd_model_provider_profiles(provider_id) ON DELETE CASCADE,
    secret      TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_rd_model_provider_credentials_secret CHECK (btrim(secret) <> '')
);
```

Register the Postgres store as `@Component` when the mapper exists (same style as `PostgresModelProviderProfileStore`). Keep the in-memory store `@ConditionalOnMissingBean` in `AgentRuntimeControlPlaneConfiguration`.

Wire `ModelProviderCredentialService` as a `@Bean` next to `modelProviderProfileService`.

- [ ] **Step 1: Add SQL + README row `| 16 | p16_model_provider_credentials.sql |`**
- [ ] **Step 2: Implement mapper (`INSERT ... ON CONFLICT DO UPDATE`, `SELECT`, `DELETE`)**
- [ ] **Step 3: Test that SQL contains `rd_model_provider_credentials` and `REFERENCES rd_model_provider_profiles`**
- [ ] **Step 4: Commit** `feat: persist provider credentials in p16`

---

### Task 6: Admin HTTP

**Files:**

- Modify: `AgentExecutionProfileAdminController.java` **or** create `ModelProviderAdminController` if the existing controller is already large. Prefer a new `ModelProviderAdminController` in the same package so profile-binding methods stay untouched.
- Create: `ModelProviderAdminControllerTest.java`
- Modify: Vite is already proxying `/admin/model-provider-profiles` — no frontend page work.

Response DTO (record, JSON names as-is):

```java
public record ModelProviderView(
        String providerId,
        String displayName,
        ModelProviderProtocol protocol,
        String baseUrl,
        String modelId,
        String credentialEnvironmentVariable,
        boolean authHeader,
        boolean enabled,
        long version,
        boolean credentialConfigured,
        Long credentialUpdatedAt
) {}
```

Map from profile + `credentialService.status`. Never copy `secret`.

Endpoints:

- `GET /admin/model-provider-profiles` → `List<ModelProviderView>` (replace the current raw `ModelProviderProfile` list so the frontend can rely on `credentialConfigured`; existing Agent Strategy page only reads metadata fields — extra JSON fields are fine).
- `GET /admin/model-provider-profiles/{providerId}` → 404 if missing.
- `PUT /admin/model-provider-profiles/{providerId}` + mutation header → `ModelProviderProfileService.register(...)`. Request record **must not** have `apiKey`. If you bind a `Map`/`JsonNode`, reject when `apiKey` present (400).
- `PUT /admin/model-provider-profiles/{providerId}/credential` + mutation header + `{ "apiKey": "..." }`.

Tests (plain unit, like `AgentExecutionProfileAdminControllerTest`):

1. PUT metadata, GET list, `credentialConfigured=false`, JSON/toString has no secret.
2. PUT credential with good token, GET list `credentialConfigured=true`, view has no `sk-`.
3. PUT credential with wrong token throws 403 and store stays empty.
4. PUT metadata JSON conceptually containing apiKey — if using a typed request, add a controller-level guard test with `JsonNode` or a dedicated method `rejectSecretFields(JsonNode)`.

- [ ] **Step 1: Failing controller test**
- [ ] **Step 2: Implement controller**
- [ ] **Step 3: Run**

```bash
./mvnw -pl bootstrap -am -Dtest=ModelProviderAdminControllerTest,AgentExecutionProfileAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 4: Commit** `feat: admin API for provider metadata and write-only credentials`

---

### Task 7: RULE.md and local migrate note

**Files:**

- Modify: `RULE.md` (append, do not replace)
- Modify: `README.md` only if it documents `bootstrap-db` — add that local DB is often `ragent`

Append a short **【强制】** under the existing config/secrets area (near the OpenViking `api-key-env` rule):

> 供应商 LLM API key 只允许出现在 `rd_model_provider_credentials` 或进程环境变量。`ModelProviderProfile` 与所有 GET `/admin/model-provider-profiles*` 响应禁止包含密钥。解析顺序：库存（按 `credentialEnvironmentVariable`）→ `System.getenv` → `launchctl`。验证：`ModelProviderCredentialServiceTest`、`StoredThenSystemAuthEnvironmentResolverTest`、`ModelProviderAdminControllerTest`。

- [ ] **Step 1: Append the rule with real paths and commands**
- [ ] **Step 2: Commit** `docs: forbid provider API keys on profile GET responses`

---

### Task 8: Apply p16 on the running local `ragent` (operator, not a code commit)

```bash
docker exec -i postgres psql -U postgres -d ragent -v ON_ERROR_STOP=1 \
  < bootstrap/src/main/resources/sql/postgres/p16_model_provider_credentials.sql
```

Then:

```bash
curl -sS http://127.0.0.1:18080/admin/model-provider-profiles
```

Expected: 200 JSON array (possibly empty). After a metadata PUT + credential PUT with `X-RD-Agent-Runtime-Token: local-agent-runtime`, list shows `credentialConfigured: true` and no key material.

Restart is required after Java wiring changes.

---

## Out of scope reminders

- No React. No nav. No `HostVerification*` edits.
- Do not increase Pi raw event caps. Do not put secrets in snapshots.
- Do not run the full Maven suite unless a task says so.
