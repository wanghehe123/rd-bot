# F-CAS-01

Stale rd_tasks CAS write rejected.

```bash
./mvnw -pl rag,bootstrap -am -Dtest=InMemoryRdTaskStoreCasTest,RagStreamTaskRegistryCompleteCasTest,RagStreamTaskRegistryRejectCasTest,PostgresRdTaskStoreCasTest -Dsurefire.failIfNoSpecifiedTests=false test
```
