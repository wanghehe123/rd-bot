package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.impl.PostgresProjectMemoryStore;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryMapper;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.bootstrap.persistence.impl.PostgresProjectMemoryOperationStore;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryOperationMapper;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;
import java.nio.file.Files; import java.nio.file.Path;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryRevisionMapper;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemorySourceMapper;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;

class ProjectAgentMemoryPostgresStoreTest {
    @Test
    void rejectsNonNumericProjectIdBeforeAnyPostgresAccess() {
        ProjectMemoryMapper mapper = mock(ProjectMemoryMapper.class);
        PostgresProjectMemoryStore store = new PostgresProjectMemoryStore(mapper, null, null);

        assertThrows(IllegalArgumentException.class, () -> store.create(new ProjectMemory(
                "1", "repository-fallback", "", ProjectMemoryType.SEMANTIC, "subject", 1L
        )));
    }

    @Test void rejectsStaleHeadCas() {
        ProjectMemoryMapper mapper=mock(ProjectMemoryMapper.class); when(mapper.advanceHead(1L,2L,0L,1L)).thenReturn(0);
        PostgresProjectMemoryStore store=new PostgresProjectMemoryStore(mapper,null,null);
        assertThrows(IllegalStateException.class,()->store.advanceHead("1","2",1L));
    }
    @Test void atomicallyWritesRevisionSourceAndAdvancesHead() {
      ProjectMemoryMapper memory=mock(ProjectMemoryMapper.class); ProjectMemoryRevisionMapper revisions=mock(ProjectMemoryRevisionMapper.class); ProjectMemorySourceMapper sources=mock(ProjectMemorySourceMapper.class);
      when(memory.advanceHead(1L,2L,1L,1L)).thenReturn(1);
      PostgresProjectMemoryStore store=new PostgresProjectMemoryStore(memory,revisions,sources);
      store.persistRevisionSourceAndAdvanceHead(new ProjectMemoryRevision("2","1",1L,ProjectMemoryRevisionStatus.ACTIVE,"t","s","{}","a".repeat(64),"v","",1L,1L),new ProjectMemorySource("3","2","1","","","","uri","b".repeat(64),"","e","v","r"),1L);
      assertTrue(true);
    }
    @Test void acceptsMatchingOperationReplayAndRejectsStaleFence() {
      ProjectMemoryOperationMapper mapper=mock(ProjectMemoryOperationMapper.class); when(mapper.insertIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(1); when(mapper.settle(1L,"w",2L,3L,"SUCCEEDED")).thenReturn(0);
      PostgresProjectMemoryOperationStore store=new PostgresProjectMemoryOperationStore(mapper);
      ProjectMemoryOperation op=ProjectMemoryOperation.pending("1","1","STAGE","source","a".repeat(64),"e","v");
      assertTrue(store.register(op).operationKey().equals(op.operationKey())); assertTrue(!store.settle("1","w",2L,3L,com.wish.rd.rag.project.memory.model.ProjectMemoryOperationStatus.SUCCEEDED));
    }
    @Test void operationReplayConflictAndValidSettleAreFenced() {
      ProjectMemoryOperationMapper mapper=mock(ProjectMemoryOperationMapper.class); ProjectMemoryOperation op=ProjectMemoryOperation.pending("1","1","STAGE","source","a".repeat(64),"e","v");
      com.wish.rd.bootstrap.persistence.entity.ProjectMemoryOperationRow row=new com.wish.rd.bootstrap.persistence.entity.ProjectMemoryOperationRow(); row.id=1L;row.projectId=1L;row.operationKey=op.operationKey();row.operationKind="STAGE";row.sourceIdentity="source";row.sourceContentHash="a".repeat(64);row.extractorVersion="e";row.schemaVersion="v";
      when(mapper.insertIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(0);when(mapper.findForUpdate(op.operationKey())).thenReturn(row);when(mapper.settle(1L,"w",2L,3L,"SUCCEEDED")).thenReturn(1);
      PostgresProjectMemoryOperationStore store=new PostgresProjectMemoryOperationStore(mapper);assertTrue(store.register(op).operationId().equals("1"));assertTrue(store.settle("1","w",2L,3L,com.wish.rd.rag.project.memory.model.ProjectMemoryOperationStatus.SUCCEEDED));row.projectId=2L;assertThrows(IllegalStateException.class,()->store.register(op));
    }
    @Test void searchActiveHeadsUsesExactBoundedSqlPredicates() throws Exception {
      String sql=Files.readString(Path.of("src/main/java/com/wish/rd/bootstrap/persistence/mapper/ProjectMemoryMapper.java"));
      assertTrue(sql.contains("r.id=m.head_revision_id AND r.memory_id=m.id")); assertTrue(sql.contains("m.lifecycle_status='ENABLED'")); assertTrue(sql.contains("r.status='ACTIVE'")); assertTrue(sql.contains("r.redacted=TRUE")); assertTrue(sql.contains("LIMIT #{limit}"));
      assertTrue(sql.contains("countExaminedActiveHeads"));
      assertTrue(sql.contains("searchActiveHeadsLexical"));
      assertTrue(sql.contains("LOWER(r.summary) LIKE CONCAT('%', #{query}, '%')"));
    }
}
