package com.wish.rd.rag.project;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.project.model.RdProjectCommand;
import com.wish.rd.rag.project.model.RdProjectPage;
import com.wish.rd.rag.project.model.RdProjectQuery;

/**
 * {@link RdProjectService} 项目管理领域服务单测。
 */
class RdProjectServiceTest {

    @Test
    void shouldCreateProjectAndParseGitHubRepositoryParts() {
        RdProjectService service = new RdProjectService(generator(), new FakeRdProjectStore());

        RdProject project = service.create(new RdProjectCommand(
                "waimai",
                "外卖系统",
                "外卖订单验收仓库",
                "https://github.com/example/waimai.git",
                "",
                "",
                "main",
                true
        ));

        assertEquals("waimai", project.projectKey());
        assertEquals("外卖系统", project.name());
        assertEquals("https://github.com/example/waimai.git", project.repositoryUrl());
        assertEquals("example", project.repoOwner());
        assertEquals("waimai", project.repoName());
        assertEquals("main", project.defaultBranch());
        assertTrue(project.enabled());
        assertFalse(project.deleted());
    }

    @Test
    void shouldRejectDuplicatedActiveProjectKey() {
        RdProjectService service = new RdProjectService(generator(), new FakeRdProjectStore());
        RdProjectCommand command = command("waimai");

        service.create(command);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.create(command));
        assertTrue(exception.getMessage().contains("projectKey already exists"));
    }

    @Test
    void shouldHideDeletedProjectFromQuery() {
        RdProjectService service = new RdProjectService(generator(), new FakeRdProjectStore());
        RdProject project = service.create(command("waimai"));

        service.delete(project.projectId());

        RdProjectPage page = service.query(new RdProjectQuery("", null, 1, 10));
        assertEquals(0, page.total());
        assertThrows(NoSuchElementException.class, () -> service.get(project.projectId()));
    }

    @Test
    void shouldFilterEnabledProjectsForSelector() {
        RdProjectService service = new RdProjectService(generator(), new FakeRdProjectStore());
        service.create(command("waimai"));
        service.create(new RdProjectCommand(
                "payment",
                "支付系统",
                "",
                "git@github.com:example/payment.git",
                "",
                "",
                "main",
                false
        ));

        RdProjectPage page = service.query(new RdProjectQuery("", true, 1, 10));

        assertEquals(1, page.total());
        assertEquals("waimai", page.records().getFirst().projectKey());
    }

    private static RdProjectCommand command(String projectKey) {
        return new RdProjectCommand(
                projectKey,
                "外卖系统",
                "",
                "https://github.com/example/waimai.git",
                "",
                "",
                "main",
                true
        );
    }

    private static SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_783_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    private static final class FakeRdProjectStore implements RdProjectStore {

        private final LinkedHashMap<String, RdProject> projects = new LinkedHashMap<>();

        @Override
        public RdProject save(RdProject project) {
            projects.put(project.projectId(), project);
            return project;
        }

        @Override
        public Optional<RdProject> findById(String projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public Optional<RdProject> findByKey(String projectKey) {
            return projects.values().stream()
                    .filter(project -> project.projectKey().equals(projectKey))
                    .findFirst();
        }

        @Override
        public List<RdProject> list() {
            return List.copyOf(projects.values());
        }
    }
}
