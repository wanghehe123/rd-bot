package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryOperationRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageFinalizationRow;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageRunRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskStatusEventRow;
import com.wish.rd.bootstrap.persistence.entity.TaskFailureProvenanceRow;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryAttemptBindingRow;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryCheckpointRow;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryOperationMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementDeliveryJobMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPolicyRunMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageFinalizationMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPublicationMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageRunMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskStatusEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.TaskFailureProvenanceMapper;
import com.wish.rd.bootstrap.persistence.mapper.TaskRetryAttemptBindingMapper;
import com.wish.rd.bootstrap.persistence.mapper.TaskRetryCheckpointMapper;
import com.wish.rd.engine.requirement.audit.AuditCompletion;
import com.wish.rd.engine.requirement.audit.AuditIntegrity;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedStateMutation;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.ContractAuditVerdict;
import com.wish.rd.engine.requirement.HostVerifyRemediationPackageBuilder;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationKey;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.fasterxml.jackson.databind.MapperFeature;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Focused transaction-boundary tests for PostgreSQL stage finalization. */
class PostgresRequirementStageFinalizationAdapterTest {

    @Test
    void agentRoleFailureFinalizationWritesAnExactRetryProvenanceSnapshot() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RequirementDeliveryJobMapper jobs = mock(RequirementDeliveryJobMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        RequirementPublicationMapper publications = mock(RequirementPublicationMapper.class);
        RequirementPolicyRunMapper policies = mock(RequirementPolicyRunMapper.class);
        RdAgentStageRunMapper stages = mock(RdAgentStageRunMapper.class);
        TaskFailureProvenanceMapper provenance = mock(TaskFailureProvenanceMapper.class);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "701", "700", 4L, 9L,
                "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER",
                0, 3, 60_000L, ScheduleResourceClass.PROVIDER,
                java.util.Set.of(ScheduleResourceClass.PROVIDER), "project", "provider", "P1", "101", 1L)
                .claimed("worker", 60_000L, 2L);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.EXECUTING,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.EXECUTING, RdTaskStatus.FAILED_NEEDS_HUMAN,
                        "", "{\"status\":\"NEEDS_HUMAN\"}", "", "review requires input", "review failed")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "failed review";
        when(tasks.selectById(700L)).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);
        com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow policy =
                new com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow();
        policy.id = 101L;
        policy.taskId = 700L;
        policy.planDigest = "sha256:plan";
        when(policies.findById(101L)).thenReturn(policy);
        RdAgentStageRunRow failedStage = new RdAgentStageRunRow();
        failedStage.id = 901L;
        failedStage.taskId = 700L;
        failedStage.role = "REQUIREMENT_REVIEWER";
        failedStage.status = "FAILED_NEEDS_HUMAN";
        failedStage.attemptNo = 1;
        failedStage.errorCategory = "REQUIREMENT_REVIEW_NEEDS_HUMAN";
        failedStage.updatedAt = OffsetDateTime.now();
        when(stages.selectList(any())).thenReturn(List.of(failedStage));
        when(provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(1);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("retry-provenance-test", Map.of("rd.knowledge.store", "postgres")));
            context.registerBean(RequirementStageFinalizationMapper.class, () -> markers);
            context.registerBean(RequirementStageCommandMapper.class, () -> commands);
            context.registerBean(RequirementDeliveryJobMapper.class, () -> jobs);
            context.registerBean(RdTaskMapper.class, () -> tasks);
            context.registerBean(RdTaskStatusEventMapper.class, () -> events);
            context.registerBean(RequirementPublicationMapper.class, () -> publications);
            context.registerBean(RequirementPolicyRunMapper.class, () -> policies);
            context.registerBean(RdAgentStageRunMapper.class, () -> stages);
            context.registerBean(TaskFailureProvenanceMapper.class, () -> provenance);
            context.registerBean(com.wish.rd.framework.id.SnowflakeIdGenerator.class,
                    com.wish.rd.framework.id.SnowflakeIdGenerator::defaultGenerator);
            context.registerBean(PostgresRequirementStageFinalizationAdapter.class);
            context.refresh();

            context.getBean(PostgresRequirementStageFinalizationAdapter.class).finalize(
                    new RequirementStageFinalizationPort.FinalizationCommand(
                            marker, command, "worker", plan, null, null,
                            RequirementStageFinalizationPort.JobDisposition.NONE,
                            RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));
        }

        ArgumentCaptor<TaskFailureProvenanceRow> captured = ArgumentCaptor.forClass(TaskFailureProvenanceRow.class);
        verify(provenance).insertIfAbsent(captured.capture());
        assertEquals(700L, captured.getValue().taskId);
        assertEquals(701L, captured.getValue().failedStageCommandId);
        assertEquals(1, captured.getValue().failedCommandAttemptNo);
        assertEquals("ROLE_EXECUTION:REQUIREMENT_REVIEWER", captured.getValue().failedStage);
        assertEquals("AGENT_ROLE", captured.getValue().failurePhase);
        assertEquals("FAILED_NEEDS_HUMAN", captured.getValue().outcomeStatus);
        assertEquals(5L, captured.getValue().failedTaskVersion);
        assertEquals(10L, captured.getValue().failedTaskFencingToken);
        assertEquals(901L, captured.getValue().failedStageRunId);
        assertEquals(101L, captured.getValue().sourcePolicyRunId);
        assertEquals("sha256:plan", captured.getValue().sourcePlanDigest);
        assertEquals("REQUIREMENT_REVIEW_NEEDS_HUMAN", captured.getValue().failureKind);
    }

    @Test
    void publicationFailureFinalizationWritesCanonicalProvenanceWithoutCommittingReceipt() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RequirementDeliveryJobMapper jobs = mock(RequirementDeliveryJobMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        RequirementPublicationMapper publications = mock(RequirementPublicationMapper.class);
        RequirementPolicyRunMapper policies = mock(RequirementPolicyRunMapper.class);
        RdAgentStageRunMapper stages = mock(RdAgentStageRunMapper.class);
        TaskFailureProvenanceMapper provenance = mock(TaskFailureProvenanceMapper.class);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "801", "800", 12L, 13L,
                "REQUIREMENT_DELIVERY", "PUBLICATION",
                2, 3, 60_000L, ScheduleResourceClass.GENERIC,
                java.util.Set.of(ScheduleResourceClass.GENERIC), "project", "", "P1", "101", 1L)
                .claimed("worker", 60_000L, 2L);
        String operationId = "sha256:" + "d".repeat(64);
        ExternalEffectReceipt receipt = new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION, operationId, "UNKNOWN_REMOTE_RESULT",
                "{\"taskId\":\"800\",\"operationId\":\"" + operationId + "\"}");
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.VALIDATING,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.VALIDATING, RdTaskStatus.FAILED_RETRYABLE,
                        "", "{\"status\":\"FAILED\"}", "", "waiting for remote reconciliation", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                receipt);
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "failed publication";
        when(tasks.selectById(800L)).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.failAttempt(anyLong(), anyInt(), anyString(), anyString(), any(), any()))
                .thenReturn(commandRow(command.failed("waiting for remote reconciliation", 20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);
        com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow policy =
                new com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow();
        policy.id = 101L;
        policy.taskId = 800L;
        policy.planDigest = "sha256:plan";
        when(policies.findById(101L)).thenReturn(policy);
        com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow publicationRow =
                new com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow();
        publicationRow.operationId = operationId;
        publicationRow.taskId = 800L;
        publicationRow.status = "UNKNOWN_REMOTE_RESULT";
        when(publications.selectByOperationIdForUpdate(operationId)).thenReturn(publicationRow);
        when(provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(1);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("retry-provenance-test", Map.of("rd.knowledge.store", "postgres")));
            context.registerBean(RequirementStageFinalizationMapper.class, () -> markers);
            context.registerBean(RequirementStageCommandMapper.class, () -> commands);
            context.registerBean(RequirementDeliveryJobMapper.class, () -> jobs);
            context.registerBean(RdTaskMapper.class, () -> tasks);
            context.registerBean(RdTaskStatusEventMapper.class, () -> events);
            context.registerBean(RequirementPublicationMapper.class, () -> publications);
            context.registerBean(RequirementPolicyRunMapper.class, () -> policies);
            context.registerBean(RdAgentStageRunMapper.class, () -> stages);
            context.registerBean(TaskFailureProvenanceMapper.class, () -> provenance);
            context.registerBean(com.wish.rd.framework.id.SnowflakeIdGenerator.class,
                    com.wish.rd.framework.id.SnowflakeIdGenerator::defaultGenerator);
            context.registerBean(PostgresRequirementStageFinalizationAdapter.class);
            context.refresh();

            context.getBean(PostgresRequirementStageFinalizationAdapter.class).finalize(
                    new RequirementStageFinalizationPort.FinalizationCommand(
                            marker, command, "worker", plan, null, null,
                            RequirementStageFinalizationPort.JobDisposition.NONE,
                            RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));
        }

        ArgumentCaptor<TaskFailureProvenanceRow> captured = ArgumentCaptor.forClass(TaskFailureProvenanceRow.class);
        verify(provenance).insertIfAbsent(captured.capture());
        assertEquals("PUBLICATION:" + operationId, captured.getValue().failedStage);
        assertEquals("PR_PUBLICATION", captured.getValue().failurePhase);
        assertEquals(operationId, captured.getValue().publicationOperationId);
        assertEquals(13L, captured.getValue().failedTaskVersion);
        assertEquals(14L, captured.getValue().failedTaskFencingToken);
        verify(publications, never()).commitConfirmedReceipt(anyString(), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    void publicationFailureWithoutPolicyRunSkipsProvenanceInsteadOfRollingBack() {
        // 2026-08-23 真机回归：既没有 command.policyRunId、也没有 checkpoint.sourcePolicyRunId 时，
        // 终态发布失败的溯源记录不得抛异常让 finalize 回滚。缺失关联时必须跳过溯源而不是阻塞交付收尾。
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RequirementDeliveryJobMapper jobs = mock(RequirementDeliveryJobMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        RequirementPublicationMapper publications = mock(RequirementPublicationMapper.class);
        RdAgentStageRunMapper stages = mock(RdAgentStageRunMapper.class);
        TaskFailureProvenanceMapper provenance = mock(TaskFailureProvenanceMapper.class);
        RequirementPolicyRunMapper policies = mock(RequirementPolicyRunMapper.class);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "811", "810", 12L, 13L,
                "REQUIREMENT_DELIVERY", "PUBLICATION",
                2, 3, 60_000L, ScheduleResourceClass.GENERIC,
                java.util.Set.of(ScheduleResourceClass.GENERIC), "project", "", "P1", "", 1L)
                .claimed("worker", 60_000L, 2L);
        String operationId = "sha256:" + "e".repeat(64);
        ExternalEffectReceipt receipt = new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION, operationId, "UNKNOWN_REMOTE_RESULT",
                "{\"taskId\":\"810\",\"operationId\":\"" + operationId + "\"}");
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.VALIDATING,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.VALIDATING, RdTaskStatus.FAILED_RETRYABLE,
                        "", "{\"status\":\"FAILED\"}", "", "waiting for remote reconciliation", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                receipt);
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "failed publication without policy";
        when(tasks.selectById(810L)).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.failAttempt(anyLong(), anyInt(), anyString(), anyString(), any(), any()))
                .thenReturn(commandRow(command.failed("waiting for remote reconciliation", 20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);
        com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow publicationRow =
                new com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow();
        publicationRow.operationId = operationId;
        publicationRow.taskId = 810L;
        publicationRow.status = "UNKNOWN_REMOTE_RESULT";
        when(publications.selectByOperationIdForUpdate(operationId)).thenReturn(publicationRow);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("retry-provenance-blank-test", Map.of("rd.knowledge.store", "postgres")));
            context.registerBean(RequirementStageFinalizationMapper.class, () -> markers);
            context.registerBean(RequirementStageCommandMapper.class, () -> commands);
            context.registerBean(RequirementDeliveryJobMapper.class, () -> jobs);
            context.registerBean(RdTaskMapper.class, () -> tasks);
            context.registerBean(RdTaskStatusEventMapper.class, () -> events);
            context.registerBean(RequirementPublicationMapper.class, () -> publications);
            context.registerBean(RequirementPolicyRunMapper.class, () -> policies);
            context.registerBean(RdAgentStageRunMapper.class, () -> stages);
            context.registerBean(TaskFailureProvenanceMapper.class, () -> provenance);
            context.registerBean(com.wish.rd.framework.id.SnowflakeIdGenerator.class,
                    com.wish.rd.framework.id.SnowflakeIdGenerator::defaultGenerator);
            context.registerBean(PostgresRequirementStageFinalizationAdapter.class);
            context.refresh();

            context.getBean(PostgresRequirementStageFinalizationAdapter.class).finalize(
                    new RequirementStageFinalizationPort.FinalizationCommand(
                            marker, command, "worker", plan, null, null,
                            RequirementStageFinalizationPort.JobDisposition.NONE,
                            RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));
        }

        verify(provenance, never()).insertIfAbsent(any());
        verify(markers).finalizePrepared(any());
    }

    @Test
    void publicationFailureUsesCheckpointSourcePolicyWhenCommandPolicyIsBlank() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RequirementDeliveryJobMapper jobs = mock(RequirementDeliveryJobMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        RequirementPublicationMapper publications = mock(RequirementPublicationMapper.class);
        RdAgentStageRunMapper stages = mock(RdAgentStageRunMapper.class);
        TaskFailureProvenanceMapper provenance = mock(TaskFailureProvenanceMapper.class);
        RequirementPolicyRunMapper policies = mock(RequirementPolicyRunMapper.class);
        TaskRetryCheckpointMapper checkpoints = mock(TaskRetryCheckpointMapper.class);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "821", "820", 12L, 13L,
                "REQUIREMENT_DELIVERY", "PUBLICATION:sha256:" + "f".repeat(64),
                3, 3, 60_000L, ScheduleResourceClass.GENERIC,
                java.util.Set.of(ScheduleResourceClass.GENERIC), "project", "", "P1", "",
                "9001", 9001L, "", 1L)
                .claimed("worker", 60_000L, 2L);
        String operationId = "sha256:" + "f".repeat(64);
        ExternalEffectReceipt receipt = new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION, operationId, "UNKNOWN_REMOTE_RESULT",
                "{\"taskId\":\"820\",\"operationId\":\"" + operationId + "\"}");
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.VALIDATING,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.VALIDATING, RdTaskStatus.FAILED_RETRYABLE,
                        "", "{\"status\":\"FAILED\"}", "", "waiting for remote reconciliation", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                receipt);
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "failed publication with checkpoint policy";
        when(tasks.selectById(820L)).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.failAttempt(anyLong(), anyInt(), anyString(), anyString(), any(), any()))
                .thenReturn(commandRow(command.failed("waiting for remote reconciliation", 20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);
        TaskRetryCheckpointRow checkpoint = new TaskRetryCheckpointRow();
        checkpoint.id = 9001L;
        checkpoint.taskId = 820L;
        checkpoint.sourcePolicyRunId = 101L;
        when(checkpoints.lockByIdForUpdate(9001L)).thenReturn(checkpoint);
        com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow policy =
                new com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow();
        policy.id = 101L;
        policy.taskId = 820L;
        policy.planDigest = "sha256:plan";
        when(policies.findById(101L)).thenReturn(policy);
        com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow publicationRow =
                new com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow();
        publicationRow.operationId = operationId;
        publicationRow.taskId = 820L;
        publicationRow.status = "UNKNOWN_REMOTE_RESULT";
        when(publications.selectByOperationIdForUpdate(operationId)).thenReturn(publicationRow);
        when(provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(1);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("retry-provenance-checkpoint-test", Map.of("rd.knowledge.store", "postgres")));
            context.registerBean(RequirementStageFinalizationMapper.class, () -> markers);
            context.registerBean(RequirementStageCommandMapper.class, () -> commands);
            context.registerBean(RequirementDeliveryJobMapper.class, () -> jobs);
            context.registerBean(RdTaskMapper.class, () -> tasks);
            context.registerBean(RdTaskStatusEventMapper.class, () -> events);
            context.registerBean(RequirementPublicationMapper.class, () -> publications);
            context.registerBean(RequirementPolicyRunMapper.class, () -> policies);
            context.registerBean(RdAgentStageRunMapper.class, () -> stages);
            context.registerBean(TaskFailureProvenanceMapper.class, () -> provenance);
            context.registerBean(TaskRetryCheckpointMapper.class, () -> checkpoints);
            context.registerBean(com.wish.rd.framework.id.SnowflakeIdGenerator.class,
                    com.wish.rd.framework.id.SnowflakeIdGenerator::defaultGenerator);
            context.registerBean(PostgresRequirementStageFinalizationAdapter.class);
            context.refresh();

            context.getBean(PostgresRequirementStageFinalizationAdapter.class).finalize(
                    new RequirementStageFinalizationPort.FinalizationCommand(
                            marker, command, "worker", plan, null, null,
                            RequirementStageFinalizationPort.JobDisposition.NONE,
                            RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));
        }

        ArgumentCaptor<TaskFailureProvenanceRow> captured = ArgumentCaptor.forClass(TaskFailureProvenanceRow.class);
        verify(provenance).insertIfAbsent(captured.capture());
        assertEquals("PUBLICATION:" + operationId, captured.getValue().failedStage);
        assertEquals("PR_PUBLICATION", captured.getValue().failurePhase);
        assertEquals(101L, captured.getValue().sourcePolicyRunId);
        assertEquals("sha256:plan", captured.getValue().sourcePlanDigest);
        assertEquals(operationId, captured.getValue().publicationOperationId);
    }

    @Test
    void outcomePlanIsRecordedBeforeAnyTaskCommandOrContinuationWrite() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        when(markers.recordOutcomePrepared(any())).thenReturn(1);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.CREATED,
                java.util.List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                new com.wish.rd.engine.requirement.job.model.ContinuationSpec(
                        "REQUIREMENT_DELIVERY", "MATERIAL_READY"),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());

        RequirementStageFinalization recorded = adapter.recordOutcome(marker, command, "worker", plan, 20L);

        assertEquals(RequirementStageFinalization.State.OUTCOME_RECORDED, recorded.state());
        verify(markers).recordOutcomePrepared(any(RequirementStageFinalizationRow.class));
        org.mockito.Mockito.verifyNoInteractions(tasks, events);
    }

    @Test
    void reclaimedAttemptRecordsThePreparedMarkerWithoutReexecutingItsIdentity() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), mock(RdTaskMapper.class),
                mock(RdTaskStatusEventMapper.class));
        RequirementStageCommand first = claimedCommand();
        RequirementStageCommand reclaimed = first.claimed("worker", 60_000L, 11L);
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                first, RdTaskStatus.CREATED, 10L);
        RequirementStageExecutionPlan plan = singleMutationPlan(reclaimed);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(reclaimed));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        when(markers.recordOutcomePrepared(any())).thenReturn(1);

        RequirementStageFinalization recorded = adapter.recordOutcome(marker, reclaimed, "worker", plan, 20L);

        assertEquals(RequirementStageFinalization.State.OUTCOME_RECORDED, recorded.state());
    }

    @Test
    void finalizationCommitsTheMatchingPrConfirmedPublicationReceipt() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RequirementPublicationMapper publications = mock(RequirementPublicationMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events,
                com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(), publications);
        RequirementStageCommand command = claimedCommand();
        RequirementStageExecutionPlan plan = publicationPlan(command, "publication-op", "PR_CONFIRMED");
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        when(publications.selectByOperationIdForUpdate("publication-op"))
                .thenReturn(publicationRow("publication-op", 700L, "PR_CONFIRMED", 4));
        RdTaskRow task = new RdTaskRow();
        task.title = "publication stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(publications.commitConfirmedReceipt(anyString(), anyString(), anyLong(), anyInt(), any()))
                .thenReturn(1);
        when(markers.finalizePrepared(any())).thenReturn(1);

        adapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                marker, command, "worker", plan, null, null,
                RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        verify(publications).commitConfirmedReceipt(eq("publication-row"), eq("publication-op"), eq(700L), eq(4), any());
    }

    @Test
    void finalizationRejectsPublicationReceiptForAnotherPullRequest() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RequirementPublicationMapper publications = mock(RequirementPublicationMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks,
                mock(RdTaskStatusEventMapper.class), com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(),
                publications);
        RequirementStageCommand command = claimedCommand();
        RequirementStageExecutionPlan plan = publicationPlan(command, "publication-op", "PR_CONFIRMED");
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        var wrongPullRequest = publicationRow("publication-op", 700L, "PR_CONFIRMED", 4);
        wrongPullRequest.pullRequestUrl = "https://example.test/pr/8";
        when(publications.selectByOperationIdForUpdate("publication-op")).thenReturn(wrongPullRequest);
        RdTaskRow task = new RdTaskRow();
        task.title = "publication stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        assertThrows(IllegalStateException.class, () -> adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, command, "worker", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L)));

        verify(tasks).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(commands, never()).completeAttempt(anyLong(), anyInt(), anyString(), any());
        verify(markers, never()).finalizePrepared(any());
    }

    @Test
    void decodeOutcomePlanRejectsARehashedPlanWhosePostStatusDisagreesWithTheMarker() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, mock(RequirementStageCommandMapper.class), mock(RequirementDeliveryJobMapper.class),
                mock(RdTaskMapper.class), mock(RdTaskStatusEventMapper.class));
        RequirementStageCommand command = claimedCommand();
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L);
        RequirementStageExecutionPlan tamperedPlan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.CREATED,
                java.util.List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.REJECTED, "", "{}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        String tamperedPlanJson = RequirementPolicyRun.canonicalizeJson(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                        .valueToTree(tamperedPlan)
                        .toString());
        RequirementStageFinalization tamperedMarker = marker.outcomeRecorded(
                RdTaskStatus.MATERIAL_COLLECTING,
                tamperedPlanJson,
                RequirementPolicyRun.canonicalJsonDigest(tamperedPlanJson),
                20L);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> adapter.decodeOutcomePlan(tamperedMarker));
        assertEquals("stage outcome plan post-status conflicts with marker: " + command.commandId(),
                exception.getMessage());
    }

    @Test
    void finalizingOutcomeRecordedMarkerRetainsItsCanonicalPlanAndDigest() throws Exception {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageExecutionPlan plan = singleMutationPlan(command);
        String canonicalPlanJson = canonicalPlanJson(plan);
        RequirementStageFinalization recorded = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L).outcomeRecorded(
                plan.postStatus(), canonicalPlanJson, RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson), 20L);
        RequirementStageFinalizationRow jsonbNormalized = markerRow(recorded);
        jsonbNormalized.outcomePlanJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(canonicalPlanJson)
                .toPrettyString();
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(jsonbNormalized);
        RdTaskRow task = new RdTaskRow();
        task.title = "atomic stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);

        RequirementStageFinalizationPort.FinalizationResult result = adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        recorded, command, "worker", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        assertEquals(RequirementStageFinalization.State.FINALIZED, result.finalization().state());
        assertEquals(canonicalPlanJson, result.finalization().outcomePlanJson());
        assertEquals(RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson),
                result.finalization().outcomePlanDigest());
        ArgumentCaptor<RequirementStageFinalizationRow> persisted = ArgumentCaptor.forClass(
                RequirementStageFinalizationRow.class);
        verify(markers).finalizePrepared(persisted.capture());
        assertEquals(canonicalPlanJson, persisted.getValue().outcomePlanJson);
        assertEquals(RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson),
                persisted.getValue().outcomePlanDigest);
    }

    @Test
    void twoMutationPlanWritesSequentialFencedSnapshotsAndEventsWithEachPayload() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageExecutionPlan plan = twoMutationPlan(command);
        String canonicalPlanJson = canonicalPlanJson(plan);
        RequirementStageFinalization recorded = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L).outcomeRecorded(
                plan.postStatus(), canonicalPlanJson, RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson), 20L);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(recorded));
        RdTaskRow task = new RdTaskRow();
        task.title = "two-step stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);

        adapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                recorded, command, "worker", plan, null, null,
                RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        InOrder ordered = inOrder(tasks, events, commands, markers);
        ordered.verify(tasks).advanceStatusWithExpectedVersionFenced(
                eq(700L), eq(4L), eq(9L), eq("CREATED"), eq("MATERIAL_COLLECTING"),
                eq(""), eq("{\"first\":true}"), isNull(), eq("prompt-first"), any());
        ordered.verify(events).insert(any(RdTaskStatusEventRow.class));
        ordered.verify(tasks).advanceStatusWithExpectedVersionFenced(
                eq(700L), eq(5L), eq(10L), eq("MATERIAL_COLLECTING"), eq("MATERIAL_READY"),
                eq("second-error"), isNull(), eq("https://example.test/pr/701"), isNull(), any());
        ordered.verify(events).insert(any(RdTaskStatusEventRow.class));
        ordered.verify(commands).completeAttempt(anyLong(), anyInt(), anyString(), any());
        ordered.verify(markers).finalizePrepared(any());
        ArgumentCaptor<RdTaskStatusEventRow> eventsCaptor = ArgumentCaptor.forClass(RdTaskStatusEventRow.class);
        verify(events, org.mockito.Mockito.times(2)).insert(eventsCaptor.capture());
        assertEquals(RdTaskStatus.MATERIAL_COLLECTING.name(), eventsCaptor.getAllValues().getFirst().status);
        assertEquals("first event", eventsCaptor.getAllValues().getFirst().message);
        assertEquals(RdTaskStatus.MATERIAL_READY.name(), eventsCaptor.getAllValues().get(1).status);
        assertEquals("second event", eventsCaptor.getAllValues().get(1).message);
    }

    @Test
    void auditedWritebackRunsBeforeTaskCasAndRollsBackWhenCasFails() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        AuditedStateFinalizationWriter writer = mock(AuditedStateFinalizationWriter.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events,
                com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(),
                null, null, null, null, null, null, null, null, null, null, writer);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        AuditedStateMutation mutation = auditedMutation(command.taskId(), command.commandId(), "AC-001");
        RequirementStageExecutionPlan plan = singleMutationPlan(command).withAuditedStateMutation(mutation);
        String canonicalPlanJson = canonicalPlanJson(plan);
        RequirementStageFinalization recorded = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L).outcomeRecorded(
                plan.postStatus(), canonicalPlanJson, RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson), 20L);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(recorded));
        RdTaskRow task = new RdTaskRow();
        task.title = "audit writeback";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);

        adapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                recorded, command, "worker", plan, null, null,
                RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        InOrder ordered = inOrder(writer, tasks);
        ordered.verify(writer).apply(mutation);
        ordered.verify(tasks).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());

        org.mockito.Mockito.reset(writer, tasks, markers);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(recorded));
        org.mockito.Mockito.doThrow(new IllegalStateException("same version has a different hash"))
                .when(writer).apply(mutation);

        assertThrows(IllegalStateException.class, () -> adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        recorded, command, "worker", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 21L)));
        verify(tasks, never()).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(markers, never()).finalizePrepared(any());
    }

    @Test
    void completedPlanWithoutBindingIsRejectedBeforeTaskCas() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        AuditedStateFinalizationWriter writer = mock(AuditedStateFinalizationWriter.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks,
                mock(RdTaskStatusEventMapper.class),
                com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(),
                null, null, null, null, null, null, null, null, null, null, writer);
        RequirementStageCommand command = RequirementStageCommand.pending(
                "701", "700", 4L, 9L, "REQUIREMENT_DELIVERY", "COMPLETION",
                0, 3, 60_000L, ScheduleResourceClass.GENERIC, "project", "", "P1", 1L)
                .claimed("worker", 60_000L, 2L);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.REPORTING,
                java.util.List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.REPORTING, RdTaskStatus.COMPLETED, "", "{}", "", "", "done")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        String canonicalPlanJson = canonicalPlanJson(plan);
        RequirementStageFinalization recorded = RequirementStageFinalization.prepared(
                command, RdTaskStatus.REPORTING, 10L).outcomeRecorded(
                plan.postStatus(), canonicalPlanJson, RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson), 20L);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(recorded));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        recorded, command, "worker", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L)));
        assertTrue(thrown.getMessage().toLowerCase().contains("binding"));
        verify(writer, never()).apply(any());
        verify(tasks, never()).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void zeroFenceMarkerIsRejectedBeforeAnyPrepareMapperWrite() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, mock(RequirementStageCommandMapper.class), mock(RequirementDeliveryJobMapper.class),
                mock(RdTaskMapper.class), mock(RdTaskStatusEventMapper.class));
        RequirementStageCommand legacy = new RequirementStageCommand(
                "701", "700", 4L, 0L, "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 1, 3,
                60_000L, ScheduleResourceClass.GENERIC, "project", "", 1,
                RequirementStageCommand.Status.RUNNING, "worker", 60_000L, 1L, "", 1L, 1L);

        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare(legacy, "worker", RdTaskStatus.CREATED, 10L));

        org.mockito.Mockito.verifyNoInteractions(markers);
    }

    @Test
    void expiredLeaseIsRejectedBeforeFinalizationMutatesTheTask() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks,
                mock(RdTaskStatusEventMapper.class));
        RequirementStageCommand command = claimedCommand();
        RequirementStageCommandRow expired = commandRow(command);
        expired.leaseUntil = OffsetDateTime.parse("1970-01-01T00:00:00Z");
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(expired);
        RequirementStageExecutionPlan plan = singleMutationPlan(command);
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);

        assertThrows(IllegalStateException.class, () -> adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, command, "worker", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L)));

        org.mockito.Mockito.verifyNoInteractions(tasks, markers);
    }

    @Test
    void sameOwnerCannotFinalizeAnEarlierAttemptAfterReclaim() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks,
                mock(RdTaskStatusEventMapper.class));
        RequirementStageCommand first = claimedCommand();
        RequirementStageCommand reclaimed = first.claimed("worker", 60_000L, 11L);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(reclaimed));
        RequirementStageExecutionPlan plan = singleMutationPlan(first);
        RequirementStageFinalization marker = outcomeRecordedMarker(first, plan);

        assertThrows(IllegalStateException.class, () -> adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, first, "worker", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L)));

        org.mockito.Mockito.verifyNoInteractions(tasks, markers);
    }

    @Test
    void completionCasLossPreventsContinuationAndClosesNothing() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageExecutionPlan plan = singleMutationPlan(command);
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "atomic stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> adapter.finalize(finalization(marker, command)));

        org.mockito.Mockito.verify(commands, never()).enqueue(any());
        org.mockito.Mockito.verify(markers, never()).finalizePrepared(any());
    }

    @Test
    void retryableDispositionDefersItsRecordedFailureMutationBeforeTheFinalAttempt() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        RequirementStageExecutionPlan retryable = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.CREATED,
                java.util.List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.FAILED_RETRYABLE, "", "{\"status\":\"FAILED\"}",
                        "", "provider unavailable", "provider unavailable")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = outcomeRecordedMarker(command, retryable);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        when(commands.failAttempt(anyLong(), anyInt(), anyString(), anyString(),
                any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(commandRow(command.failed("provider unavailable", 20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);

        RequirementStageFinalizationPort.FinalizationResult finalized = adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, command, "worker", retryable, null, null,
                        RequirementStageFinalizationPort.JobDisposition.FAIL_RETRYABLE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE, finalized.completedCommand().status());
        verify(commands).failAttempt(anyLong(), anyInt(), anyString(), anyString(),
                any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(commands, never()).completeAttempt(anyLong(), anyInt(), anyString(), any());
        verify(markers, never()).finalizePrepared(any());
        org.mockito.Mockito.verifyNoInteractions(tasks, events);
    }

    @Test
    void replayedRetryablePlanAppliesItsRecordedFailureMutationAtTheFinalAttempt() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand first = RequirementStageCommand.pending(
                "701", "700", 4L, 9L, "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING",
                0, 2, 60_000L, ScheduleResourceClass.GENERIC, "project", "", "P1", 1L)
                .claimed("worker-1", 60_000L, 2L);
        RequirementStageCommand finalAttempt = first.failed("provider unavailable", 10L)
                .claimed("worker-2", 60_000L, 11L);
        RequirementStageExecutionPlan retryable = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                first.taskId(), first.taskVersion(), first.fencingToken(), RdTaskStatus.CREATED,
                java.util.List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.FAILED_RETRYABLE, "", "{\"status\":\"FAILED\"}",
                        "", "provider unavailable", "provider unavailable")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = outcomeRecordedMarker(first, retryable);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(finalAttempt));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "retryable task";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.failAttempt(anyLong(), anyInt(), anyString(), anyString(),
                any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(commandRow(finalAttempt.failed("provider unavailable", 20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);

        RequirementStageFinalizationPort.FinalizationResult finalized = adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, finalAttempt, "worker-2", retryable, null, null,
                        RequirementStageFinalizationPort.JobDisposition.FAIL_RETRYABLE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, finalized.completedCommand().status());
        verify(tasks).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(events).insert(any(RdTaskStatusEventRow.class));
        verify(commands).failAttempt(anyLong(), anyInt(), anyString(), anyString(),
                any(OffsetDateTime.class), any(OffsetDateTime.class));
    }

    @Test
    void taskCasEventCompletionAndMarkerCloseFollowOneOrderedBoundary() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageFinalization marker = outcomeRecordedMarker(command, singleMutationPlan(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "atomic stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);

        adapter.finalize(finalization(marker, command));

        InOrder ordered = inOrder(tasks, events, commands, markers);
        ordered.verify(tasks).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
        ordered.verify(events).insert(any(RdTaskStatusEventRow.class));
        ordered.verify(commands).completeAttempt(anyLong(), anyInt(), anyString(), any());
        ordered.verify(markers).finalizePrepared(any());
    }

    @Test
    void zeroFenceContinuationIsRejectedBeforeItsMapperInsertOrMarkerClose() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "atomic stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.complete(anyLong(), anyString(), any())).thenReturn(commandRow(command.succeeded(20L)));
        RequirementStageCommand zeroFenceContinuation = new RequirementStageCommand(
                "702", command.taskId(), command.taskVersion(), 0L, "REQUIREMENT_DELIVERY", "MATERIAL_READY",
                0, 3, 60_000L, ScheduleResourceClass.GENERIC, java.util.Set.of(ScheduleResourceClass.GENERIC),
                "project", "", 1, RequirementStageCommand.Status.PENDING, "", 0L, 20L, "", 20L, 20L);

        assertThrows(IllegalArgumentException.class, () -> adapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                marker, command, "worker", new RequirementDeliveryResult(
                command.taskId(), RdTaskStatus.MATERIAL_COLLECTING, "", "{}", ""), zeroFenceContinuation,
                null, RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L)));

        verify(commands, never()).enqueue(any());
        verify(markers, never()).finalizePrepared(any());
    }

    @Test
    void conflictingContinuationPolicyAuthorizationFailsBeforeMarkerClose() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageExecutionPlan plan = singleMutationPlan(command);
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "atomic stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        RequirementStageCommand requested = RequirementStageCommand.pending(
                "702", command.taskId(), 5L, 10L, "REQUIREMENT_REVIEWER",
                "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3, 60_000L,
                ScheduleResourceClass.PROVIDER, java.util.Set.of(ScheduleResourceClass.PROVIDER),
                "project", "provider", "P1", "801", 20L);
        RequirementStageCommand conflicting = RequirementStageCommand.pending(
                "703", command.taskId(), 5L, 10L, "REQUIREMENT_REVIEWER",
                "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3, 60_000L,
                ScheduleResourceClass.PROVIDER, java.util.Set.of(ScheduleResourceClass.PROVIDER),
                "project", "provider", "P1", "802", 20L);
        when(commands.findByIdentity(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(commandRow(conflicting));

        assertThrows(IllegalStateException.class, () -> adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, command, "worker", plan, requested,
                        null, RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L)));

        verify(commands).enqueue(any(RequirementStageCommandRow.class));
        verify(markers, never()).finalizePrepared(any());
    }

    @Test
    void hostVerifyFixCodingContinuationLooksUpRemediationIdentityNotThePriorHostVerify() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        HostVerifyRemediationPackageBuilder.Package frozen = new HostVerifyRemediationPackageBuilder()
                .build("verify-9", "705", 1, "PRODUCT_DEFECT", "BUILD exit 1: cannot find symbol Foo");
        RequirementStageCommand command = RequirementStageCommand.remediationPending(
                "701", "700", 4L, 9L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 3, 60_000L,
                ScheduleResourceClass.PROVIDER, java.util.Set.of(ScheduleResourceClass.PROVIDER),
                "project", "provider", "P1", "801", "9001", AgentRemediationKind.HOST_VERIFY_FIX, 1,
                "705", frozen.attachment().content(), frozen.requestHash(), 1L)
                .claimed("worker", 60_000L, 2L);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.EXECUTING,
                java.util.List.of(RequirementTaskMutation.snapshotUpdate(
                        RdTaskStatus.EXECUTING, "", "{}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                new com.wish.rd.engine.requirement.job.model.ContinuationSpec(
                        "REQUIREMENT_DELIVERY", "HOST_VERIFY"),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "host verify fix";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        RequirementStageCommand requested = RequirementStageCommand.remediationPending(
                "702", command.taskId(), plan.postVersion(), plan.postFencingToken(),
                "REQUIREMENT_DELIVERY", "HOST_VERIFY", 3, 60_000L,
                ScheduleResourceClass.DOCKER, java.util.Set.of(ScheduleResourceClass.DOCKER),
                "project", "provider", "P1", "801", "9001", AgentRemediationKind.HOST_VERIFY_FIX, 1,
                "705", frozen.attachment().content(), frozen.requestHash(), 20L);
        RequirementStageCommand priorHostVerify = RequirementStageCommand.pending(
                "688", command.taskId(), 4L, 9L, "REQUIREMENT_DELIVERY", "HOST_VERIFY",
                0, 3, 60_000L, ScheduleResourceClass.DOCKER, java.util.Set.of(ScheduleResourceClass.DOCKER),
                "project", "provider", "P1", "801", 1L);
        when(commands.findByIdentity(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(commandRow(priorHostVerify));
        RequirementStageCommandRow persisted = commandRow(requested);
        persisted.deadlineAt = com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport.toDateTime(
                requested.deadlineEpochMillis());
        persisted.remediationRoundId = 9001L;
        persisted.remediationKind = AgentRemediationKind.HOST_VERIFY_FIX.name();
        persisted.remediationNo = 1;
        persisted.remediationSourceStageRunId = 705L;
        persisted.remediationRequestJson = frozen.attachment().content();
        persisted.remediationRequestHash = frozen.requestHash();
        when(commands.findByRemediationIdentity(eq(700L), eq("REQUIREMENT_DELIVERY"), eq("HOST_VERIFY"), eq(9001L)))
                .thenReturn(persisted);
        when(markers.finalizePrepared(any())).thenReturn(1);

        adapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                marker, command, "worker", plan, requested,
                null, RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        verify(commands).findByRemediationIdentity(700L, "REQUIREMENT_DELIVERY", "HOST_VERIFY", 9001L);
        verify(commands, never()).findByIdentity(anyLong(), anyString(), anyString(), anyString());
        verify(markers).finalizePrepared(any());
    }

    @Test
    void policyEvaluateContinuationPersistsPlanReadyLedgerBeforeFkBoundCommand() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RequirementPolicyRunMapper policyRuns = mock(RequirementPolicyRunMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events,
                com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(), null, policyRuns);
        RequirementStageCommand command = claimedPlanGeneratingCommand();
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.PLAN_GENERATING,
                java.util.List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.PLAN_GENERATING, RdTaskStatus.PLAN_GENERATED,
                        "", "{\"taskId\":\"700\"}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                new com.wish.rd.engine.requirement.job.model.ContinuationSpec(
                        "REQUIREMENT_DELIVERY", "POLICY_EVALUATE"),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        RequirementStageCommand continuation = RequirementStageCommand.pending(
                "702", command.taskId(), plan.postVersion(), plan.postFencingToken(),
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", 0, 3, 60_000L,
                ScheduleResourceClass.GENERIC, java.util.Set.of(ScheduleResourceClass.GENERIC),
                "project", "", "P1", "702", 20L);
        RequirementPolicyRun requestedRun = new RequirementPolicyRun(
                "702", command.taskId(), plan.postVersion(), plan.postFencingToken(),
                "{\"taskId\":\"700\"}", RequirementPolicyRun.canonicalJsonDigest("{\"taskId\":\"700\"}"),
                "", "", "", com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState.PLAN_READY,
                plan.postVersion(), plan.postFencingToken(), null, null, "", "", "", 0L,
                "", "", 0L, 0L, 20L, 20L);
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "generated plan";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(policyRuns.findById(702L)).thenReturn(null, PostgresRequirementPolicyRunStore.toRow(requestedRun));
        RequirementStageCommandRow persistedContinuation = commandRow(continuation);
        persistedContinuation.deadlineAt = com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport.toDateTime(
                continuation.deadlineEpochMillis());
        when(commands.findByIdentity(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(persistedContinuation);
        when(markers.finalizePrepared(any())).thenReturn(1);

        RequirementStageFinalizationPort.FinalizationResult result = adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, command, "worker", plan, continuation, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        assertEquals(continuation.commandId(), result.nextCommand().commandId());
        ArgumentCaptor<com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow> ledger =
                ArgumentCaptor.forClass(com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow.class);
        InOrder ordered = inOrder(policyRuns, commands);
        ordered.verify(policyRuns).insertIfAbsent(ledger.capture());
        ArgumentCaptor<RequirementStageCommandRow> continuationRow = ArgumentCaptor.forClass(
                RequirementStageCommandRow.class);
        ordered.verify(commands).enqueue(continuationRow.capture());
        assertEquals(702L, ledger.getValue().id);
        assertEquals(700L, ledger.getValue().taskId);
        assertEquals(5L, ledger.getValue().sourceTaskVersion);
        assertEquals(10L, ledger.getValue().sourceFence);
        assertEquals(requestedRun.planDigest(), ledger.getValue().planDigest);
        assertNull(continuationRow.getValue().retryCheckpointId);
        assertEquals(0L, continuationRow.getValue().businessGeneration);
        assertNull(continuationRow.getValue().targetRetryBindingId);
    }

    @Test
    void checkpointContinuationRoundTripsItsExactCheckpointIdentity() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        RequirementStageExecutionPlan plan = singleMutationPlan(command);
        RequirementStageFinalization marker = outcomeRecordedMarker(command, plan);
        RequirementStageCommand continuation = RequirementStageCommand.pending(
                "702", command.taskId(), 5L, 10L, "REQUIREMENT_REVIEWER",
                "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3, 60_000L,
                ScheduleResourceClass.PROVIDER, java.util.Set.of(ScheduleResourceClass.PROVIDER),
                "project", "provider", "P1", "", "801", 801L, "802", 20L);
        RequirementStageCommandRow persistedContinuation = commandRow(continuation);
        persistedContinuation.deadlineAt = com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport.toDateTime(
                continuation.deadlineEpochMillis());
        persistedContinuation.retryCheckpointId = 801L;
        persistedContinuation.businessGeneration = 801L;
        persistedContinuation.targetRetryBindingId = 802L;
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "checkpoint continuation";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(commands.findByIdentity(700L, "REQUIREMENT_REVIEWER",
                "ROLE_EXECUTION:REQUIREMENT_REVIEWER", "801"))
                .thenReturn(persistedContinuation);
        when(markers.finalizePrepared(any())).thenReturn(1);

        RequirementStageFinalizationPort.FinalizationResult result = adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, command, "worker", plan, continuation, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L));

        assertEquals("801", result.nextCommand().retryCheckpointId());
        assertEquals(801L, result.nextCommand().businessGeneration());
        assertEquals("802", result.nextCommand().targetRetryBindingId());
        ArgumentCaptor<RequirementStageCommandRow> continuationRow = ArgumentCaptor.forClass(
                RequirementStageCommandRow.class);
        verify(commands).enqueue(continuationRow.capture());
        assertEquals(801L, continuationRow.getValue().retryCheckpointId);
        assertEquals(801L, continuationRow.getValue().businessGeneration);
        assertEquals(802L, continuationRow.getValue().targetRetryBindingId);
    }

    @Test
    void legacyNullFenceCompletionRemainsZeroAndCannotBecomeTheNextContinuation() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageFinalization marker = outcomeRecordedMarker(command, singleMutationPlan(command));
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(marker));
        RdTaskRow task = new RdTaskRow();
        task.title = "atomic stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        RequirementStageCommandRow legacyCompletion = commandRow(command.succeeded(20L));
        legacyCompletion.fencingToken = null;
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any())).thenReturn(legacyCompletion);
        when(markers.finalizePrepared(any())).thenReturn(1);

        RequirementStageCommand legacyRead = adapter.finalize(finalization(marker, command)).completedCommand();

        assertEquals(0L, legacyRead.fencingToken());
        assertThrows(IllegalArgumentException.class, () -> adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, command, "worker", new RequirementDeliveryResult(
                        command.taskId(), RdTaskStatus.MATERIAL_COLLECTING, "", "{}", ""), legacyRead,
                        null, RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L)));
        verify(commands, never()).enqueue(any());
    }

    private static RequirementStageFinalizationPort.FinalizationCommand finalization(
            RequirementStageFinalization marker,
            RequirementStageCommand command
    ) {
        return new RequirementStageFinalizationPort.FinalizationCommand(
                marker, command, "worker", singleMutationPlan(command), null,
                null, RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L);
    }

    private static RequirementStageFinalization outcomeRecordedMarker(
            RequirementStageCommand command,
            RequirementStageExecutionPlan plan
    ) {
        String planJson = canonicalPlanJson(plan);
        return RequirementStageFinalization.prepared(command, plan.expectedStatus(), 10L).outcomeRecorded(
                plan.postStatus(), planJson, RequirementPolicyRun.canonicalJsonDigest(planJson), 20L);
    }

    @Test
    void exhaustCommandWritesCommandTerminalTaskFailedProvenanceAndSettlesOnlyItsCheckpoint() {
        ExhaustionMocks mocks = ExhaustionMocks.checkpointBound();
        RequirementStageCommand claimed = mocks.claimed;
        when(mocks.commands.lockByIdForUpdate(801L)).thenReturn(commandRow(claimed));
        when(mocks.commands.failAttempt(eq(801L), eq(3), eq("worker"), anyString(), any(), any()))
                .thenReturn(commandRow(claimed.failed("exhausted", 20L)));
        RdTaskRow before = requirementTaskRow(800L, RdTaskStatus.RECOVERING, 12L, 13L);
        RdTaskRow after = requirementTaskRow(800L, RdTaskStatus.FAILED_RETRYABLE, 13L, 14L);
        when(mocks.tasks.lockByIdForUpdate(800L)).thenReturn(before);
        when(mocks.tasks.advanceStatusWithExpectedVersionFenced(
                eq(800L), eq(12L), eq(13L), eq("RECOVERING"), eq("FAILED_RETRYABLE"),
                anyString(), anyString(), isNull(), isNull(), any()))
                .thenReturn(1);
        when(mocks.tasks.selectById(800L)).thenReturn(after);
        when(mocks.events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(mocks.provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(1);
        when(mocks.checkpoints.lockByIdForUpdate(900L)).thenReturn(checkpointRow(
                900L, 800L, "DISPATCHED", "AGENT_ROLE", "ROLE_EXECUTION:CODING_AGENT"));
        when(mocks.checkpoints.transition(any(TaskRetryCheckpointRow.class))).thenReturn(1);
        TaskRetryAttemptBindingRow binding = new TaskRetryAttemptBindingRow();
        binding.id = 910L;
        binding.checkpointId = 900L;
        binding.bindingKind = "AGENT_STAGE";
        binding.role = "CODING_AGENT";
        binding.stageRunId = 920L;
        binding.attemptNo = 2;
        binding.ordinal = 0;
        when(mocks.bindings.listByCheckpoint(900L)).thenReturn(List.of(binding));
        RdAgentStageRunRow pending = new RdAgentStageRunRow();
        pending.id = 920L;
        pending.status = "PENDING";
        pending.role = "CODING_AGENT";
        pending.taskId = 800L;
        pending.attemptNo = 2;
        when(mocks.stages.selectById(920L)).thenReturn(pending);
        when(mocks.stages.updateStageRunWhenStatusMatches(any(RdAgentStageRunRow.class), eq("PENDING")))
                .thenReturn(1);

        RequirementStageFinalizationPort.ExhaustionResult result = mocks.adapter.exhaustCommand(mocks.command());

        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, result.completedCommand().status());
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, result.failedTask().status());
        assertEquals(13L, result.failedTask().version());
        assertEquals(14L, result.failedTask().fencingToken());
        assertEquals(13L, result.provenance().failedTaskVersion());
        assertEquals(14L, result.provenance().failedTaskFencingToken());
        assertEquals(TaskFailurePhase.AGENT_ROLE, result.provenance().failurePhase());
        assertEquals(900L, Long.parseLong(result.settledCheckpoint().checkpointId()));
        ArgumentCaptor<TaskFailureProvenanceRow> provenance = ArgumentCaptor.forClass(TaskFailureProvenanceRow.class);
        verify(mocks.provenance).insertIfAbsent(provenance.capture());
        assertEquals(13L, provenance.getValue().failedTaskVersion);
        assertEquals(14L, provenance.getValue().failedTaskFencingToken);
        assertEquals("FAILED_RETRYABLE", provenance.getValue().outcomeStatus);
        ArgumentCaptor<TaskRetryCheckpointRow> checkpoint = ArgumentCaptor.forClass(TaskRetryCheckpointRow.class);
        verify(mocks.checkpoints).transition(checkpoint.capture());
        assertEquals(900L, checkpoint.getValue().id);
        assertEquals("FAILED_RETRYABLE", checkpoint.getValue().status);
        assertEquals("DISPATCHED", checkpoint.getValue().expectedStatus);
        verify(mocks.stages).updateStageRunWhenStatusMatches(any(RdAgentStageRunRow.class), eq("PENDING"));
    }

    @Test
    void exhaustCommandRollsBackWhenProvenanceInsertConflicts() {
        ExhaustionMocks mocks = ExhaustionMocks.checkpointBound();
        when(mocks.commands.lockByIdForUpdate(801L)).thenReturn(commandRow(mocks.claimed));
        when(mocks.commands.failAttempt(eq(801L), eq(3), eq("worker"), anyString(), any(), any()))
                .thenReturn(commandRow(mocks.claimed.failed("exhausted", 20L)));
        when(mocks.tasks.lockByIdForUpdate(800L))
                .thenReturn(requirementTaskRow(800L, RdTaskStatus.RECOVERING, 12L, 13L));
        when(mocks.tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mocks.tasks.selectById(800L))
                .thenReturn(requirementTaskRow(800L, RdTaskStatus.FAILED_RETRYABLE, 13L, 14L));
        when(mocks.events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(mocks.provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> mocks.adapter.exhaustCommand(mocks.command()));

        assertTrue(failure.getMessage().contains("provenance"));
        verify(mocks.checkpoints, never()).transition(any());
        verify(mocks.stages, never()).updateStageRunWhenStatusMatches(any(), anyString());
    }

    @Test
    void exhaustCommandCancelsUnusedPendingAttemptsBoundToThatCheckpoint() {
        ExhaustionMocks mocks = ExhaustionMocks.checkpointBound();
        when(mocks.commands.lockByIdForUpdate(801L)).thenReturn(commandRow(mocks.claimed));
        when(mocks.commands.failAttempt(eq(801L), eq(3), eq("worker"), anyString(), any(), any()))
                .thenReturn(commandRow(mocks.claimed.failed("exhausted", 20L)));
        when(mocks.tasks.lockByIdForUpdate(800L))
                .thenReturn(requirementTaskRow(800L, RdTaskStatus.RECOVERING, 12L, 13L));
        when(mocks.tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mocks.tasks.selectById(800L))
                .thenReturn(requirementTaskRow(800L, RdTaskStatus.FAILED_RETRYABLE, 13L, 14L));
        when(mocks.events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(mocks.provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(1);
        when(mocks.checkpoints.lockByIdForUpdate(900L)).thenReturn(checkpointRow(
                900L, 800L, "DISPATCHED", "AGENT_ROLE", "ROLE_EXECUTION:CODING_AGENT"));
        when(mocks.checkpoints.transition(any(TaskRetryCheckpointRow.class))).thenReturn(1);
        TaskRetryAttemptBindingRow coding = bindingRow(910L, 900L, 920L, "CODING_AGENT", 0);
        TaskRetryAttemptBindingRow qa = bindingRow(911L, 900L, 921L, "QA_AGENT", 1);
        when(mocks.bindings.listByCheckpoint(900L)).thenReturn(List.of(coding, qa));
        when(mocks.stages.selectById(920L)).thenReturn(pendingStage(920L, "CODING_AGENT"));
        when(mocks.stages.selectById(921L)).thenReturn(pendingStage(921L, "QA_AGENT"));
        when(mocks.stages.updateStageRunWhenStatusMatches(any(RdAgentStageRunRow.class), eq("PENDING")))
                .thenReturn(1);

        mocks.adapter.exhaustCommand(mocks.command());

        verify(mocks.stages, org.mockito.Mockito.times(2))
                .updateStageRunWhenStatusMatches(any(RdAgentStageRunRow.class), eq("PENDING"));
    }

    @Test
    void exhaustCommandWithBlankRetryCheckpointIdSettlesNoCheckpointEvenWhenOneIsActive() {
        ExhaustionMocks mocks = ExhaustionMocks.unbound();
        when(mocks.commands.lockByIdForUpdate(801L)).thenReturn(commandRow(mocks.claimed));
        when(mocks.commands.failAttempt(eq(801L), eq(3), eq("worker"), anyString(), any(), any()))
                .thenReturn(commandRow(mocks.claimed.failed("exhausted", 20L)));
        when(mocks.tasks.lockByIdForUpdate(800L))
                .thenReturn(requirementTaskRow(800L, RdTaskStatus.RECOVERING, 12L, 13L));
        when(mocks.tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mocks.tasks.selectById(800L))
                .thenReturn(requirementTaskRow(800L, RdTaskStatus.FAILED_RETRYABLE, 13L, 14L));
        when(mocks.events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(mocks.provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(1);

        RequirementStageFinalizationPort.ExhaustionResult result = mocks.adapter.exhaustCommand(mocks.command());

        assertNull(result.settledCheckpoint());
        verify(mocks.checkpoints, never()).lockByIdForUpdate(anyLong());
        verify(mocks.checkpoints, never()).transition(any());
        verify(mocks.bindings, never()).listByCheckpoint(anyLong());
    }

    @Test
    void exhaustionResumesAnAlreadyTerminalCommandAndStillWritesItsProvenance() {
        ExhaustionMocks mocks = ExhaustionMocks.checkpointBound();
        RequirementStageCommand alreadyDead = mocks.claimed.failed("stage command deadline exceeded", 20L);
        when(mocks.commands.lockByIdForUpdate(801L)).thenReturn(commandRow(alreadyDead));
        RdTaskRow before = requirementTaskRow(800L, RdTaskStatus.RECOVERING, 12L, 13L);
        RdTaskRow after = requirementTaskRow(800L, RdTaskStatus.FAILED_RETRYABLE, 13L, 14L);
        when(mocks.tasks.lockByIdForUpdate(800L)).thenReturn(before);
        when(mocks.tasks.advanceStatusWithExpectedVersionFenced(
                eq(800L), eq(12L), eq(13L), eq("RECOVERING"), eq("FAILED_RETRYABLE"),
                anyString(), anyString(), isNull(), isNull(), any()))
                .thenReturn(1);
        when(mocks.tasks.selectById(800L)).thenReturn(after);
        when(mocks.events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(mocks.provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(1);
        when(mocks.checkpoints.lockByIdForUpdate(900L)).thenReturn(checkpointRow(
                900L, 800L, "DISPATCHED", "AGENT_ROLE", "ROLE_EXECUTION:CODING_AGENT"));
        when(mocks.checkpoints.transition(any(TaskRetryCheckpointRow.class))).thenReturn(1);
        when(mocks.bindings.listByCheckpoint(900L)).thenReturn(List.of());

        RequirementStageFinalizationPort.ExhaustionResult result = mocks.adapter.exhaustCommand(
                new RequirementStageFinalizationPort.ExhaustionCommand(
                        alreadyDead, "worker", RdTaskStatus.RECOVERING, 12L, 13L,
                        RdTaskStatus.FAILED_RETRYABLE, TaskRetryCheckpointStatus.FAILED_RETRYABLE,
                        mocks.command().provenanceDraft(),
                        "stage command deadline exceeded", "{\"failurePhase\":\"AGENT_ROLE\"}", 20L));

        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, result.completedCommand().status());
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, result.failedTask().status());
        assertEquals(TaskFailurePhase.AGENT_ROLE, result.provenance().failurePhase());
        verify(mocks.commands, never()).failAttempt(anyLong(), anyInt(), anyString(), anyString(), any(), any());
        verify(mocks.provenance).insertIfAbsent(any(TaskFailureProvenanceRow.class));
        verify(mocks.checkpoints).transition(any(TaskRetryCheckpointRow.class));
    }

    @Test
    void exhaustionIsANoOpWhenTheTaskFailureAndProvenanceAreAlreadyDurable() {
        ExhaustionMocks mocks = ExhaustionMocks.checkpointBound();
        RequirementStageCommand alreadyDead = mocks.claimed.failed("exhausted", 20L);
        when(mocks.commands.lockByIdForUpdate(801L)).thenReturn(commandRow(alreadyDead));
        RdTaskRow terminal = requirementTaskRow(800L, RdTaskStatus.FAILED_RETRYABLE, 13L, 14L);
        when(mocks.tasks.lockByIdForUpdate(800L)).thenReturn(terminal);
        TaskFailureProvenanceRow existing = new TaskFailureProvenanceRow();
        existing.id = 950L;
        existing.taskId = 800L;
        existing.failedStageCommandId = 801L;
        existing.failedCommandAttemptNo = alreadyDead.attemptNo();
        existing.failedStage = "ROLE_EXECUTION:CODING_AGENT";
        existing.failurePhase = "AGENT_ROLE";
        existing.outcomeStatus = "FAILED_RETRYABLE";
        existing.failedTaskVersion = 13L;
        existing.failedTaskFencingToken = 14L;
        existing.failureKind = "TECHNICAL_EXHAUSTED";
        existing.recordedAt = OffsetDateTime.now();
        when(mocks.provenance.findByCommandAttempt(801L, alreadyDead.attemptNo())).thenReturn(existing);

        RequirementStageFinalizationPort.ExhaustionResult result = mocks.adapter.exhaustCommand(
                new RequirementStageFinalizationPort.ExhaustionCommand(
                        alreadyDead, "worker", RdTaskStatus.RECOVERING, 12L, 13L,
                        RdTaskStatus.FAILED_RETRYABLE, TaskRetryCheckpointStatus.FAILED_RETRYABLE,
                        mocks.command().provenanceDraft(),
                        "exhausted", "{\"failurePhase\":\"AGENT_ROLE\"}", 20L));

        assertEquals(13L, result.failedTask().version());
        assertEquals(14L, result.failedTask().fencingToken());
        assertEquals("950", result.provenance().provenanceId());
        verify(mocks.commands, never()).failAttempt(anyLong(), anyInt(), anyString(), anyString(), any(), any());
        verify(mocks.tasks, never()).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(mocks.provenance, never()).insertIfAbsent(any());
        verify(mocks.checkpoints, never()).transition(any());
    }

    @Test
    void exhaustCommandEnsuresPreparedMarkerForDeadLetteredAttemptBeforeWritingProvenance() {
        ExhaustionMocks mocks = ExhaustionMocks.checkpointBound();
        when(mocks.commands.lockByIdForUpdate(801L)).thenReturn(commandRow(mocks.claimed));
        when(mocks.commands.failAttempt(eq(801L), eq(3), eq("worker"), anyString(), any(), any()))
                .thenReturn(commandRow(mocks.claimed.failed("exhausted", 20L)));
        when(mocks.tasks.lockByIdForUpdate(800L))
                .thenReturn(requirementTaskRow(800L, RdTaskStatus.RECOVERING, 12L, 13L));
        when(mocks.tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mocks.tasks.selectById(800L))
                .thenReturn(requirementTaskRow(800L, RdTaskStatus.FAILED_RETRYABLE, 13L, 14L));
        when(mocks.events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(mocks.provenance.insertIfAbsent(any(TaskFailureProvenanceRow.class))).thenReturn(1);
        when(mocks.checkpoints.lockByIdForUpdate(900L)).thenReturn(checkpointRow(
                900L, 800L, "DISPATCHED", "AGENT_ROLE", "ROLE_EXECUTION:CODING_AGENT"));
        when(mocks.checkpoints.transition(any(TaskRetryCheckpointRow.class))).thenReturn(1);
        when(mocks.bindings.listByCheckpoint(900L)).thenReturn(List.of());
        when(mocks.finalizations.insertPreparedForExhaustedAttempt(
                eq(801L), eq(3), eq("RECOVERING"), any())).thenReturn(1);

        mocks.adapter.exhaustCommand(mocks.command());

        InOrder order = inOrder(mocks.finalizations, mocks.provenance);
        order.verify(mocks.finalizations).insertPreparedForExhaustedAttempt(
                eq(801L), eq(3), eq("RECOVERING"), any());
        order.verify(mocks.provenance).insertIfAbsent(any(TaskFailureProvenanceRow.class));
    }

    @Test
    void finalizationRegistersProjectMemoryOperationBeforeTaskMutation() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        ProjectMemoryOperationMapper memoryMapper = mock(ProjectMemoryOperationMapper.class);
        PostgresProjectMemoryOperationStore memoryStore = new PostgresProjectMemoryOperationStore(memoryMapper);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events,
                com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(), null, null, null, null,
                null, null, null, null, null, memoryStore);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageExecutionPlan plan = singleMutationPlan(command);
        String canonicalPlanJson = canonicalPlanJson(plan);
        RequirementStageFinalization recorded = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L).outcomeRecorded(
                plan.postStatus(), canonicalPlanJson, RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson), 20L);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(recorded));
        RdTaskRow task = new RdTaskRow();
        task.title = "atomic stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);
        when(memoryMapper.insertIfAbsent(any(ProjectMemoryOperationRow.class))).thenReturn(1);
        String contentHash = "c".repeat(64);
        RequirementStageFinalizationPort.ProjectMemoryOperationDraft draft =
                RequirementStageFinalizationPort.ProjectMemoryOperationDraft.stageCapture(
                        "901", "101", "stage-run-1", contentHash, "extractor-1", "schema-1");

        adapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                recorded, command, "worker", plan, null, null,
                RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L, draft));

        InOrder order = inOrder(memoryMapper, tasks, commands);
        order.verify(memoryMapper).insertIfAbsent(any(ProjectMemoryOperationRow.class));
        order.verify(tasks).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
        order.verify(commands).completeAttempt(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void finalizationRejectsConflictingProjectMemoryOperationBeforeMutatingTaskState() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        ProjectMemoryOperationMapper memoryMapper = mock(ProjectMemoryOperationMapper.class);
        PostgresProjectMemoryOperationStore memoryStore = new PostgresProjectMemoryOperationStore(memoryMapper);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks,
                mock(RdTaskStatusEventMapper.class),
                com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(), null, null, null, null,
                null, null, null, null, null, memoryStore);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageExecutionPlan plan = singleMutationPlan(command);
        String canonicalPlanJson = canonicalPlanJson(plan);
        RequirementStageFinalization recorded = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L).outcomeRecorded(
                plan.postStatus(), canonicalPlanJson, RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson), 20L);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(recorded));
        String contentHash = "d".repeat(64);
        String operationKey = ProjectMemoryOperationKey.sha256(
                "101", "STAGE_FINALIZATION", "stage-run-2", contentHash, "extractor-1", "schema-1");
        ProjectMemoryOperationRow conflicting = new ProjectMemoryOperationRow();
        conflicting.id = 1L;
        conflicting.projectId = 102L;
        conflicting.operationKind = "STAGE_FINALIZATION";
        conflicting.sourceIdentity = "stage-run-2";
        conflicting.sourceContentHash = contentHash;
        conflicting.extractorVersion = "extractor-1";
        conflicting.schemaVersion = "schema-1";
        conflicting.operationKey = operationKey;
        when(memoryMapper.insertIfAbsent(any(ProjectMemoryOperationRow.class))).thenReturn(0);
        when(memoryMapper.findForUpdate(operationKey)).thenReturn(conflicting);
        RequirementStageFinalizationPort.ProjectMemoryOperationDraft draft =
                RequirementStageFinalizationPort.ProjectMemoryOperationDraft.stageCapture(
                        "902", "101", "stage-run-2", contentHash, "extractor-1", "schema-1");

        assertThrows(IllegalStateException.class, () -> adapter.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        recorded, command, "worker", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L, draft)));

        verify(tasks, never()).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(commands, never()).completeAttempt(anyLong(), anyInt(), anyString(), any());
        verify(markers, never()).finalizePrepared(any());
    }

    @Test
    void finalizationRegistersSkippedByPolicyMarkerInTheSameBoundary() {
        RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        RdTaskMapper tasks = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        ProjectMemoryOperationMapper memoryMapper = mock(ProjectMemoryOperationMapper.class);
        PostgresProjectMemoryOperationStore memoryStore = new PostgresProjectMemoryOperationStore(memoryMapper);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), tasks, events,
                com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(), null, null, null, null,
                null, null, null, null, null, memoryStore);
        RequirementStageCommand command = claimedCommand();
        when(commands.lockByIdForUpdate(anyLong())).thenReturn(commandRow(command));
        RequirementStageExecutionPlan plan = singleMutationPlan(command);
        String canonicalPlanJson = canonicalPlanJson(plan);
        RequirementStageFinalization recorded = RequirementStageFinalization.prepared(
                command, RdTaskStatus.CREATED, 10L).outcomeRecorded(
                plan.postStatus(), canonicalPlanJson, RequirementPolicyRun.canonicalJsonDigest(canonicalPlanJson), 20L);
        when(markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(recorded));
        RdTaskRow task = new RdTaskRow();
        task.title = "atomic stage";
        when(tasks.selectById(anyLong())).thenReturn(task);
        when(tasks.advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(events.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(commands.completeAttempt(anyLong(), anyInt(), anyString(), any()))
                .thenReturn(commandRow(command.succeeded(20L)));
        when(markers.finalizePrepared(any())).thenReturn(1);
        when(memoryMapper.insertIfAbsent(any(ProjectMemoryOperationRow.class))).thenReturn(1);
        String contentHash = "f".repeat(64);
        RequirementStageFinalizationPort.ProjectMemoryOperationDraft draft =
                RequirementStageFinalizationPort.ProjectMemoryOperationDraft.skippedByPolicy(
                        "903", "101", "stage-run-4", contentHash, "schema-1");

        adapter.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                recorded, command, "worker", plan, null, null,
                RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, 20L, draft));

        ArgumentCaptor<ProjectMemoryOperationRow> rowCaptor = ArgumentCaptor.forClass(ProjectMemoryOperationRow.class);
        verify(memoryMapper).insertIfAbsent(rowCaptor.capture());
        assertEquals(RequirementStageFinalizationPort.ProjectMemoryOperationDraft.KIND_SKIPPED_BY_POLICY,
                rowCaptor.getValue().operationKind);
        assertEquals("NONE", rowCaptor.getValue().extractorVersion);
    }

    private static final class ExhaustionMocks {
        private final RequirementStageFinalizationMapper finalizations =
                mock(RequirementStageFinalizationMapper.class);
        private final RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        private final RdTaskMapper tasks = mock(RdTaskMapper.class);
        private final RdTaskStatusEventMapper events = mock(RdTaskStatusEventMapper.class);
        private final TaskFailureProvenanceMapper provenance = mock(TaskFailureProvenanceMapper.class);
        private final TaskRetryCheckpointMapper checkpoints = mock(TaskRetryCheckpointMapper.class);
        private final TaskRetryAttemptBindingMapper bindings = mock(TaskRetryAttemptBindingMapper.class);
        private final RdAgentStageRunMapper stages = mock(RdAgentStageRunMapper.class);
        private final RequirementPolicyRunMapper policies = mock(RequirementPolicyRunMapper.class);
        private final PostgresRequirementStageFinalizationAdapter adapter;
        private final RequirementStageCommand claimed;

        private ExhaustionMocks(RequirementStageCommand claimed) {
            this.claimed = claimed;
            this.adapter = new PostgresRequirementStageFinalizationAdapter(
                    finalizations,
                    commands,
                    mock(RequirementDeliveryJobMapper.class),
                    tasks,
                    events,
                    com.wish.rd.framework.id.SnowflakeIdGenerator.defaultGenerator(),
                    mock(RequirementPublicationMapper.class),
                    policies,
                    stages,
                    provenance,
                    checkpoints,
                    bindings);
        }

        private static ExhaustionMocks checkpointBound() {
            RequirementStageCommand pending = RequirementStageCommand.pending(
                    "801", "800", 12L, 13L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT",
                    2, 3, 60_000L, ScheduleResourceClass.PROVIDER,
                    java.util.Set.of(ScheduleResourceClass.PROVIDER), "project", "provider", "P1",
                    "101", "900", 900L, "910", 1L);
            return new ExhaustionMocks(pending.claimed("worker", 60_000L, 2L));
        }

        private static ExhaustionMocks unbound() {
            RequirementStageCommand pending = RequirementStageCommand.pending(
                    "801", "800", 12L, 13L, "REQUIREMENT_DELIVERY", "CONTEXT_BUILDING",
                    2, 3, 60_000L, ScheduleResourceClass.GENERIC,
                    java.util.Set.of(ScheduleResourceClass.GENERIC), "project", "", "P1", 1L);
            return new ExhaustionMocks(pending.claimed("worker", 60_000L, 2L));
        }

        private RequirementStageFinalizationPort.ExhaustionCommand command() {
            return new RequirementStageFinalizationPort.ExhaustionCommand(
                    claimed, "worker", RdTaskStatus.RECOVERING, 12L, 13L,
                    RdTaskStatus.FAILED_RETRYABLE, TaskRetryCheckpointStatus.FAILED_RETRYABLE,
                    new RequirementStageFinalizationPort.ExhaustionProvenanceDraft(
                            "950", claimed.stage(), TaskFailurePhase.AGENT_ROLE,
                            "920", "", "", "101", "sha256:plan", "", "TECHNICAL_EXHAUSTED"),
                    "role command task is not executing", "{\"failurePhase\":\"AGENT_ROLE\"}", 20L);
        }
    }

    private static RdTaskRow requirementTaskRow(long id, RdTaskStatus status, long version, long fence) {
        RdTaskRow row = new RdTaskRow();
        row.id = id;
        row.taskType = "REQUIREMENT";
        row.status = status.name();
        row.title = "exhaustion task";
        row.version = version;
        row.fencingToken = fence;
        row.executionResultJson = "{}";
        row.errorMessage = "";
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = OffsetDateTime.now();
        return row;
    }

    private static TaskRetryCheckpointRow checkpointRow(
            long id, long taskId, String status, String phase, String failedStage
    ) {
        TaskRetryCheckpointRow row = new TaskRetryCheckpointRow();
        row.id = id;
        row.taskId = taskId;
        row.status = status;
        row.failurePhase = phase;
        row.failedStage = failedStage;
        row.failedStageCommandId = 799L;
        row.sourceTaskStatus = "FAILED_NEEDS_HUMAN";
        row.sourceTaskVersion = 11L;
        row.sourceFencingToken = 12L;
        row.attemptNo = 1;
        row.idempotencyKey = "cp-" + id;
        row.businessGeneration = id;
        row.dispatchTaskVersion = 12L;
        row.dispatchFencingToken = 13L;
        row.dispatchCommandId = 801L;
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = OffsetDateTime.now();
        return row;
    }

    private static TaskRetryAttemptBindingRow bindingRow(
            long id, long checkpointId, long stageRunId, String role, int ordinal
    ) {
        TaskRetryAttemptBindingRow row = new TaskRetryAttemptBindingRow();
        row.id = id;
        row.checkpointId = checkpointId;
        row.bindingKind = "AGENT_STAGE";
        row.role = role;
        row.stageRunId = stageRunId;
        row.attemptNo = 2;
        row.ordinal = ordinal;
        return row;
    }

    private static RdAgentStageRunRow pendingStage(long id, String role) {
        RdAgentStageRunRow row = new RdAgentStageRunRow();
        row.id = id;
        row.taskId = 800L;
        row.role = role;
        row.status = "PENDING";
        row.attemptNo = 2;
        return row;
    }

    private static RequirementStageExecutionPlan singleMutationPlan(RequirementStageCommand command) {
        return new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.CREATED,
                java.util.List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
    }

    private static RequirementStageExecutionPlan publicationPlan(
            RequirementStageCommand command, String operationId, String durableState
    ) {
        return new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.CREATED,
                java.util.List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "{}",
                        "https://example.test/pr/7", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                new com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt(
                        com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.Kind.PUBLICATION,
                        operationId, durableState,
                        "{\"operationId\":\"" + operationId + "\",\"taskId\":\"" + command.taskId()
                                + "\",\"pullRequestUrl\":\"https://example.test/pr/7\",\"pullRequestNumber\":7}"));
    }

    private static RequirementStageExecutionPlan twoMutationPlan(RequirementStageCommand command) {
        return new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.CREATED,
                java.util.List.of(
                        RequirementTaskMutation.statusTransition(
                                RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING,
                                "prompt-first", "{\"first\":true}", "", "", "first event"),
                        RequirementTaskMutation.statusTransition(
                                RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY,
                                "", "", "https://example.test/pr/701", "second-error", "second event")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
    }

    private static String canonicalPlanJson(RequirementStageExecutionPlan plan) {
        return RequirementPolicyRun.canonicalizeJson(new com.fasterxml.jackson.databind.ObjectMapper()
                .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                .valueToTree(plan)
                .toString());
    }

    private static RequirementStageCommand claimedCommand() {
        return RequirementStageCommand.pending(
                "701", "700", 4L, 9L, "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING",
                0, 3, 60_000L, ScheduleResourceClass.GENERIC, "project", "", "P1", 1L)
                .claimed("worker", 60_000L, 2L);
    }

    private static AuditedStateMutation auditedMutation(String taskId, String commandId, String recordId) {
        AuditedTaskState next = new AuditedTaskStateCodec().seal(new AuditedTaskState(
                taskId,
                1L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 1L, 1L),
                List.of(new AuditedRecord(
                        recordId,
                        AuditedRecordKind.REQUIREMENT,
                        true,
                        "criterion " + recordId,
                        AuditedRecordStatus.PENDING,
                        List.of(),
                        "",
                        "")),
                "audit-" + commandId));
        AuditRun run = new AuditRun(
                "audit-" + commandId,
                taskId,
                "stage-1",
                "HOST_VERIFY",
                commandId,
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                List.of(recordId),
                List.of(),
                List.of(),
                List.of(),
                1_700_000_000_000L);
        return new AuditedStateMutation(run, next, next.stateVersion());
    }

    private static RequirementStageCommand claimedPlanGeneratingCommand() {
        return RequirementStageCommand.pending(
                "701", "700", 4L, 9L, "REQUIREMENT_DELIVERY", "PLAN_GENERATING",
                0, 3, 60_000L, ScheduleResourceClass.GENERIC, "project", "", "P1", 1L)
                .claimed("worker", 60_000L, 2L);
    }

    private static RequirementStageFinalizationRow markerRow(RequirementStageFinalization marker) {
        RequirementStageFinalizationRow row = new RequirementStageFinalizationRow();
        row.commandId = Long.valueOf(marker.commandId());
        row.attemptNo = marker.attemptNo();
        row.taskId = Long.valueOf(marker.taskId());
        row.expectedTaskVersion = marker.expectedTaskVersion();
        row.expectedFencingToken = marker.expectedFencingToken();
        row.expectedTaskStatus = marker.expectedTaskStatus().name();
        row.stage = marker.stage();
        row.state = marker.state().name();
        row.outcomeStatus = marker.outcomeStatus() == null ? "" : marker.outcomeStatus().name();
        row.resultJson = marker.resultJson();
        row.errorMessage = marker.errorMessage();
        row.outcomePlanJson = marker.outcomePlanJson();
        row.outcomePlanDigest = marker.outcomePlanDigest();
        row.preparedAt = OffsetDateTime.now();
        return row;
    }

    private static RequirementStageCommandRow commandRow(RequirementStageCommand command) {
        RequirementStageCommandRow row = new RequirementStageCommandRow();
        row.id = Long.valueOf(command.commandId());
        row.taskId = Long.valueOf(command.taskId());
        row.taskVersion = command.taskVersion();
        row.fencingToken = command.fencingToken();
        row.role = command.role();
        row.stage = command.stage();
        row.policyRunId = command.policyRunId().isBlank() ? null : Long.valueOf(command.policyRunId());
        row.retryCheckpointId = command.retryCheckpointId().isBlank()
                ? null : Long.valueOf(command.retryCheckpointId());
        row.businessGeneration = command.businessGeneration();
        row.targetRetryBindingId = command.targetRetryBindingId().isBlank()
                ? null : Long.valueOf(command.targetRetryBindingId());
        row.attemptNo = command.attemptNo();
        row.maxAttempts = command.maxAttempts();
        row.resourceClass = command.resourceClass().name();
        row.resourceRequirements = command.encodedResourceRequirements();
        row.projectId = command.projectId();
        row.providerId = command.providerId();
        row.priorityRank = command.priorityRank();
        row.status = command.status().name();
        row.leaseOwner = command.leaseOwner();
        row.leaseUntil = OffsetDateTime.now().plusMinutes(1);
        row.nextVisibleAt = OffsetDateTime.now();
        row.lastError = command.lastError();
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = OffsetDateTime.now();
        return row;
    }

    private static com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow publicationRow(
            String operationId, long taskId, String status, int version
    ) {
        var row = new com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow();
        row.id = "publication-row";
        row.operationId = operationId;
        row.taskId = taskId;
        row.status = status;
        row.version = version;
        row.pullRequestUrl = "https://example.test/pr/7";
        row.pullRequestNumber = 7;
        return row;
    }
}
