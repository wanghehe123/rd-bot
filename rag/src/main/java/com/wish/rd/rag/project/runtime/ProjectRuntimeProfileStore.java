package com.wish.rd.rag.project.runtime;

import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;

import java.util.List;
import java.util.Optional;

/** Persistence port for verified project-role execution runtimes. */
public interface ProjectRuntimeProfileStore {

    ProjectRuntimeProfile save(ProjectRuntimeProfile profile);

    Optional<ProjectRuntimeProfile> find(String projectId, String role);

    List<ProjectRuntimeProfile> list(String projectId);

    boolean delete(String projectId, String role);
}
