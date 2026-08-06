# F-ORACLE-01

Host-owned assertion false-pass scenario.

Wave 1 verification:

```bash
./mvnw -pl bootstrap -am \
  -Dtest=EngineRequirementExecutorAdapterTest,HostOwnedAssertionGateTest,HostAssertionOracleWiringTest,HttpJsonPathAssertionRunnerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The adapter test `shouldFailGreenQaWhenInjectedHttpJsonPathAssertionFails` supplies a
QA result whose acceptance commands have `exitCode=0`; the injected Host gate
still rejects the result when `$.ok` is false. The runner also rejects HTTP
targets outside the evaluation base URL host.

Status: verified for the Spring-wired runner and adapter path.

Remaining gap: `hostAssertionBundle.specs` is still an agent-returned,
hash-checked bundle. This slice does not claim Host compilation or pre-frozen
spec replacement; clean-workspace replay and broader assertion types remain
future work.
