#!/usr/bin/env bash
# Fast gate for the open-source supported path (openspec T09):
#   Docker application/compose/config policy, PostgreSQL store wiring,
#   RequirementDeliveryEngine / dispatch / retry / audit, DockerPiAgentExecutor /
#   credential relay / workspace / HOST_VERIFY, GitHub platform adapter,
#   admin controllers and QA evidence validation.
# This is a quick pre-commit gate; it does NOT replace the full `./mvnw test`.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[core] backend focused suite (rag/engine/exec/bootstrap)..."
env -u SPRING_CONFIG_ADDITIONAL_LOCATION ./mvnw -pl rag,engine,exec,bootstrap -am \
  -Dtest='DockerAssetPolicyTest,DockerApplicationImagePolicyTest,DockerComposeReleasePolicyTest,DockerExecutorConfigurationTest,DockerPiAgentExecutorTest,PiCredentialRelayServiceTest,RepairWorkspaceFactoryTest,AgentExecutionProfileServiceTest,RequirementDeliveryEngineTest,RequirementDeliveryDispatchServiceTest,RequirementDeliveryStageExecutionTest,RequirementStageCommandPersistencePolicyTest,MultiAgentOrchestrationSqlPolicyTest,PostgresRequirementStageCommandStoreTest,TaskRetryEngineTest,TaskRetryEngineCheckpointInitializationTest,TaskRetryPointResolverTest,TaskRetryRoutePlannerTest,HostVerificationCommandDetectorTest,GitHubCodePlatformAdapterTest,QaEvidenceBundleValidatorTest,AdminFrontendControllerTest,ApplicationSecureDefaultsTest,AgentRuntimeMutationAccessPolicyTest,SecurityPostureLoggerTest,ProjectMemoryMutationAccessWiringTest,InMemoryRequirementPolicyTransactionWiringTest,ImplementationPackageIsolationPolicyTest,ModelPackageIsolationPolicyTest,TransactionalProxyPolicyTest,RequirementCompletionWriterPolicyTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

echo "[core] frontend contract tests..."
(cd frontend && node --experimental-strip-types --test test/*.test.ts)

echo "[core] frontend typecheck + production build..."
(cd frontend && npm run typecheck && npm run build)

echo "[core] Pi bridge protocol tests..."
(cd bootstrap/src/main/resources/executor/pi && npm test)

echo "[core] OpenSpec contracts..."
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict

echo "[core] all gates passed"
