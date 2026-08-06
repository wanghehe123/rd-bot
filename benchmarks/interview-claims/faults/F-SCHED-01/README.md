# F-SCHED-01

Fair recover skips project already at in-flight cap.

```bash
./mvnw -pl bootstrap -am -Dtest=FairScheduleSelectorTest,FairRequirementDeliveryClaimPlannerTest,RequirementDeliveryDispatchServiceTest#recoverSkipsProjectWhenInFlightAlreadyAtCap -Dsurefire.failIfNoSpecifiedTests=false test
```
