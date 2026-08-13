package com.wish.rd.bootstrap.persistence.entity;

import java.time.OffsetDateTime;

/**
 * 重复身份组成员行。
 *
 * <p>{@code identity} 是生效身份：已落库的 {@code source_identity_key}，或存量行按
 * {@code knowledge_source_identity_key} 现算出来的那个。分组必须用它，不能用可能还是
 * NULL 的列，否则不同来源的潜在重复会被并成同一组。
 */
public class InventoryDuplicateMemberRow {

    public Long id;
    public String identity;
    public OffsetDateTime lastSyncedAt;
    public OffsetDateTime createdAt;
    public Long rowVersion;
}
