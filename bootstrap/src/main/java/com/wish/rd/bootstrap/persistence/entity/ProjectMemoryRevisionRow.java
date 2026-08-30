package com.wish.rd.bootstrap.persistence.entity;

public class ProjectMemoryRevisionRow {
    public Long id;
    public Long memoryId;
    public Long version;
    public String status;
    public String title;
    public String summary;
    public String contentJson;
    public String contentHash;
    public String schemaVersion;
    public Long supersedesRevisionId;
    public Long rowVersion;
}
