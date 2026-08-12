package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPolicyRunMapper;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** PostgreSQL authoritative store for immutable policy-run snapshots and ledger-version CAS. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementPolicyRunStore implements RequirementPolicyRunStore {
    private final RequirementPolicyRunMapper mapper;
    public PostgresRequirementPolicyRunStore(RequirementPolicyRunMapper mapper) { this.mapper = mapper; }

    @Override @Transactional
    public RequirementPolicyRun createOrGet(RequirementPolicyRun run) {
        if (run == null) {
            throw new IllegalArgumentException("policy run must not be null");
        }
        RequirementPolicyRunRow existing = mapper.findByGeneration(
                PostgresPersistenceSupport.parseId(run.taskId()),
                run.sourceTaskVersion(), run.sourceFencingToken());
        if (existing != null) {
            RequirementPolicyRun effective = toModel(existing);
            if (!sameImmutableGeneration(effective, run)) {
                throw new IllegalStateException("conflicting policy generation insert: " + run.id());
            }
            return effective;
        }
        if (run.state() != RequirementPolicyRunState.PLAN_READY) {
            throw new IllegalStateException("new policy generation must start at PLAN_READY");
        }
        mapper.insertIfAbsent(toRow(run));
        RequirementPolicyRunRow row = mapper.findByGeneration(PostgresPersistenceSupport.parseId(run.taskId()), run.sourceTaskVersion(), run.sourceFencingToken());
        if (row == null) throw new IllegalStateException("policy generation insert did not persist: " + run.id());
        RequirementPolicyRun effective = toModel(row);
        if (!sameImmutableGeneration(effective, run)) {
            throw new IllegalStateException("conflicting policy generation insert: " + run.id());
        }
        return effective;
    }
    @Override public Optional<RequirementPolicyRun> findById(String id) { return Optional.ofNullable(mapper.findById(PostgresPersistenceSupport.parseId(id))).map(PostgresRequirementPolicyRunStore::toModel); }
    @Override public Optional<RequirementPolicyRun> findActiveByTask(String id) { return Optional.ofNullable(mapper.findActiveByTask(PostgresPersistenceSupport.parseId(id))).map(PostgresRequirementPolicyRunStore::toModel); }
    @Override @Transactional
    public RequirementPolicyRun supersedeForPolicyRetry(
            com.wish.rd.engine.requirement.policy.model.RequirementPolicyRetryContext context,
            long expectedLedgerVersion,
            long nowEpochMillis
    ) {
        if (context == null || context.isEmpty()) throw new IllegalArgumentException("policy retry context must be complete");
        RequirementPolicyRunRow row = mapper.lockForUpdate(PostgresPersistenceSupport.parseId(context.sourcePolicyRunId()));
        if (row == null) throw new IllegalStateException("policy retry source is missing: " + context.sourcePolicyRunId());
        RequirementPolicyRun source = toModel(row);
        if (source.ledgerVersion() != expectedLedgerVersion || !source.planDigest().equals(context.sourcePlanDigest())
                || !source.state().canBeSuperseded()) {
            throw new IllegalStateException("policy retry source cannot be superseded: " + context.sourcePolicyRunId());
        }
        RequirementPolicyRun superseded = source.superseded(nowEpochMillis);
        if (mapper.compareAndSet(toRow(superseded), source.state().name(), expectedLedgerVersion) != 1) {
            throw new IllegalStateException("policy retry supersession compare-and-set lost: " + source.id());
        }
        return superseded;
    }
    @Override @Transactional
    public RequirementPolicyRun compareAndSet(RequirementPolicyRun next, RequirementPolicyRunState expected, long version) {
        RequirementPolicyRunRow current = mapper.lockForUpdate(PostgresPersistenceSupport.parseId(next.id()));
        if (current == null) throw new IllegalStateException("policy run missing: " + next.id());
        RequirementPolicyRun previous = toModel(current);
        if (previous.state() != expected || previous.ledgerVersion() != version || next.ledgerVersion() != version + 1L
                || !previous.id().equals(next.id()) || !previous.taskId().equals(next.taskId())
                || previous.sourceTaskVersion() != next.sourceTaskVersion()
                || previous.sourceFencingToken() != next.sourceFencingToken()
                || !sameCanonicalJson(previous.planJson(), previous.planDigest(), next.planJson(), next.planDigest())
                || (!previous.policyJson().isBlank() && (!sameCanonicalJson(
                previous.policyJson(), previous.policyDigest(), next.policyJson(), next.policyDigest())
                || !previous.policyAction().equals(next.policyAction())))
                || (previous.approvalExpectedTaskVersion() != null
                && (!previous.approvalExpectedTaskVersion().equals(next.approvalExpectedTaskVersion())
                || !previous.approvalExpectedFencingToken().equals(next.approvalExpectedFencingToken())))
                || (!previous.approvalResumeCommandId().isBlank()
                && !previous.approvalResumeCommandId().equals(next.approvalResumeCommandId()))
                || (!previous.approvalRequestId().isBlank()
                && !previous.approvalRequestId().equals(next.approvalRequestId()))
                || (!previous.approvedBy().isBlank() && !previous.approvedBy().equals(next.approvedBy()))
                || (!previous.note().isBlank() && !previous.note().equals(next.note()))
                || (previous.approvedAtEpochMillis() > 0L
                && previous.approvedAtEpochMillis() != next.approvedAtEpochMillis())
                || (!previous.consumedByCommandId().isBlank()
                && !previous.consumedByCommandId().equals(next.consumedByCommandId()))
                || (previous.consumedAtEpochMillis() > 0L
                && previous.consumedAtEpochMillis() != next.consumedAtEpochMillis())
                || previous.createdAtEpochMillis() != next.createdAtEpochMillis()
                || !isAllowed(previous.state(), next.state())) {
            throw new IllegalStateException("policy ledger compare-and-set failed: " + next.id());
        }
        if (mapper.compareAndSet(toRow(next), expected.name(), version) != 1) throw new IllegalStateException("policy ledger compare-and-set lost: " + next.id());
        return next;
    }
    private static boolean isAllowed(RequirementPolicyRunState from, RequirementPolicyRunState to) {
        return switch (from) {
            case PLAN_READY -> to == RequirementPolicyRunState.POLICY_DECIDED;
            case POLICY_DECIDED -> to == RequirementPolicyRunState.WAITING_APPROVAL
                    || to == RequirementPolicyRunState.DENIED || to == RequirementPolicyRunState.APPLIED;
            case WAITING_APPROVAL -> to == RequirementPolicyRunState.APPROVED || to == RequirementPolicyRunState.DENIED;
            case APPROVED -> to == RequirementPolicyRunState.APPLIED;
            case APPLIED, DENIED, SUPERSEDED -> false;
        };
    }
    private static boolean sameImmutableGeneration(RequirementPolicyRun left, RequirementPolicyRun right) {
        return left.id().equals(right.id())
                && left.taskId().equals(right.taskId())
                && left.sourceTaskVersion() == right.sourceTaskVersion()
                && left.sourceFencingToken() == right.sourceFencingToken()
                && sameCanonicalJson(left.planJson(), left.planDigest(), right.planJson(), right.planDigest())
                && sameCanonicalJson(left.policyJson(), left.policyDigest(), right.policyJson(), right.policyDigest())
                && left.policyAction().equals(right.policyAction())
                && left.state() == right.state()
                && left.boundTaskVersion() == right.boundTaskVersion()
                && left.boundFencingToken() == right.boundFencingToken()
                && java.util.Objects.equals(left.approvalExpectedTaskVersion(), right.approvalExpectedTaskVersion())
                && java.util.Objects.equals(left.approvalExpectedFencingToken(), right.approvalExpectedFencingToken())
                && left.approvalRequestId().equals(right.approvalRequestId())
                && left.approvedBy().equals(right.approvedBy())
                && left.note().equals(right.note())
                && left.approvedAtEpochMillis() == right.approvedAtEpochMillis()
                && left.approvalResumeCommandId().equals(right.approvalResumeCommandId())
                && left.consumedByCommandId().equals(right.consumedByCommandId())
                && left.consumedAtEpochMillis() == right.consumedAtEpochMillis()
                && left.ledgerVersion() == right.ledgerVersion()
                && left.createdAtEpochMillis() == right.createdAtEpochMillis()
                && left.updatedAtEpochMillis() == right.updatedAtEpochMillis();
    }
    private static boolean sameCanonicalJson(String leftJson, String leftDigest, String rightJson, String rightDigest) {
        return leftDigest.equals(rightDigest)
                && (leftJson.isBlank() ? rightJson.isBlank()
                : RequirementPolicyRun.canonicalizeJson(leftJson).equals(RequirementPolicyRun.canonicalizeJson(rightJson)));
    }
    static RequirementPolicyRun toModel(RequirementPolicyRunRow r) { return new RequirementPolicyRun(
            PostgresPersistenceSupport.idString(r.id),PostgresPersistenceSupport.idString(r.taskId),n(r.sourceTaskVersion),n(r.sourceFence),s(r.planJson),s(r.planDigest),s(r.policyJson),s(r.policyDigest),s(r.policyAction),RequirementPolicyRunState.valueOf(s(r.state)),n(r.boundTaskVersion),n(r.boundFence),r.approvalExpectedTaskVersion,r.approvalExpectedFence,s(r.approvalRequestId),s(r.approvedBy),s(r.note),PostgresPersistenceSupport.toEpochMillis(r.approvedAt),r.approvalResumeCommandId==null?"":PostgresPersistenceSupport.idString(r.approvalResumeCommandId),r.consumedByCommandId==null?"":PostgresPersistenceSupport.idString(r.consumedByCommandId),PostgresPersistenceSupport.toEpochMillis(r.consumedAt),n(r.ledgerVersion),PostgresPersistenceSupport.toEpochMillis(r.createdAt),PostgresPersistenceSupport.toEpochMillis(r.updatedAt)); }
    static RequirementPolicyRunRow toRow(RequirementPolicyRun r) { RequirementPolicyRunRow x=new RequirementPolicyRunRow(); x.id=PostgresPersistenceSupport.parseId(r.id());x.taskId=PostgresPersistenceSupport.parseId(r.taskId());x.sourceTaskVersion=r.sourceTaskVersion();x.sourceFence=r.sourceFencingToken();x.planJson=r.planJson();x.planDigest=r.planDigest();x.policyJson=r.policyJson();x.policyDigest=r.policyDigest();x.policyAction=r.policyAction();x.state=r.state().name();x.boundTaskVersion=r.boundTaskVersion();x.boundFence=r.boundFencingToken();x.approvalExpectedTaskVersion=r.approvalExpectedTaskVersion();x.approvalExpectedFence=r.approvalExpectedFencingToken();x.approvalRequestId=r.approvalRequestId();x.approvedBy=r.approvedBy();x.note=r.note();x.approvedAt=r.approvedAtEpochMillis()==0?null:PostgresPersistenceSupport.toDateTime(r.approvedAtEpochMillis());x.approvalResumeCommandId=r.approvalResumeCommandId().isBlank()?null:PostgresPersistenceSupport.parseId(r.approvalResumeCommandId());x.consumedByCommandId=r.consumedByCommandId().isBlank()?null:PostgresPersistenceSupport.parseId(r.consumedByCommandId());x.consumedAt=r.consumedAtEpochMillis()==0?null:PostgresPersistenceSupport.toDateTime(r.consumedAtEpochMillis());x.ledgerVersion=r.ledgerVersion();x.createdAt=PostgresPersistenceSupport.toDateTime(r.createdAtEpochMillis());x.updatedAt=PostgresPersistenceSupport.toDateTime(r.updatedAtEpochMillis());return x; }
    private static String s(String v){return v==null?"":v;} private static long n(Long v){return v==null?0L:v;}
}
