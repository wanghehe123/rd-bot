package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PackageStructureTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void bootstrapRootPackageContainsOnlyApplicationEntrypoints() throws Exception {
        Set<String> allowed = Set.of("RdBotApplication.java");
        try (var files = Files.list(PROJECT_ROOT.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap"))) {
            assertThat(files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .toList())
                    .containsExactlyInAnyOrderElementsOf(allowed);
        }
    }

    @Test
    void engineRootPackageContainsOnlyLayerMarker() throws Exception {
        Set<String> allowed = Set.of("EngineLayer.java");
        try (var files = Files.list(PROJECT_ROOT.resolve("engine/src/main/java/com/wish/rd/engine"))) {
            assertThat(files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .toList())
                    .containsExactlyInAnyOrderElementsOf(allowed);
        }
    }

    @Test
    void userDomainFollowsMvcPackageShape() {
        Path userRoot = PROJECT_ROOT.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap/user");

        assertThat(userRoot.resolve("controller/UserAdminController.java")).exists();
        assertThat(userRoot.resolve("controller/request/UserCreateRequest.java")).exists();
        assertThat(userRoot.resolve("controller/request/UserUpdateRequest.java")).exists();
        assertThat(userRoot.resolve("controller/request/ChangePasswordRequest.java")).exists();
        assertThat(userRoot.resolve("controller/vo/UserVO.java")).exists();
        assertThat(userRoot.resolve("controller/vo/UserPageVO.java")).exists();
        assertThat(userRoot.resolve("controller/vo/DeleteVO.java")).exists();
        assertThat(userRoot.resolve("service/UserAdminService.java")).exists();
        assertThat(userRoot.resolve("service/impl/InMemoryUserAdminService.java")).exists();
        assertThat(userRoot.resolve("service/impl/PostgresUserAdminService.java")).exists();
        assertThat(userRoot.resolve("dao/entity/AdminUserDO.java")).exists();
        assertThat(userRoot.resolve("dao/mapper/AdminUserMapper.java")).exists();

        assertThat(PROJECT_ROOT.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/user/UserAdminController.java"))
                .doesNotExist();
        assertThat(PROJECT_ROOT.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap/user/UserAdminService.java"))
                .doesNotExist();
        assertThat(PROJECT_ROOT.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresUserAdminService.java"))
                .doesNotExist();
    }

    @Test
    void userServicesAreSpringManagedInsteadOfConfigurationConstructed() throws Exception {
        Path userRoot = PROJECT_ROOT.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap/user");
        String postgresService = Files.readString(userRoot.resolve("service/impl/PostgresUserAdminService.java"));
        String inMemoryService = Files.readString(userRoot.resolve("service/impl/InMemoryUserAdminService.java"));
        String configuration = Files.readString(PROJECT_ROOT.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap/config/RdBotRuntimeConfiguration.java"));

        assertThat(postgresService).contains("@Service");
        assertThat(inMemoryService).contains("@Service");
        assertThat(configuration).doesNotContain("userAdminService(");
        assertThat(configuration).doesNotContain("new PostgresUserAdminService");
        assertThat(configuration).doesNotContain("new InMemoryUserAdminService");
    }
}
