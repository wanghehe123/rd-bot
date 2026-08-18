package com.wish.rd.exec.repair.security;

/**
 * Request-time catalog of repositories already registered as RD-Bot projects.
 * Static Docker allowlist placeholders must not reject those repos.
 */
@FunctionalInterface
public interface RegisteredRepositoryCatalog {

    boolean contains(String repositoryUrl, String ownerAndName);

    static RegisteredRepositoryCatalog none() {
        return (repositoryUrl, ownerAndName) -> false;
    }
}
