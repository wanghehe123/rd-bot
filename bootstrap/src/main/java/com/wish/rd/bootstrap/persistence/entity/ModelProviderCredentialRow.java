package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/** Row for a provider secret stored off the public profile. */
public class ModelProviderCredentialRow {
    public String providerId;
    public String secret;
    public OffsetDateTime updatedAt;
}
