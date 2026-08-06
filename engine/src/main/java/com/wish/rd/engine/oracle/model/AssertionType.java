package com.wish.rd.engine.oracle.model;

/**
 * Host-owned assertion kinds for business-semantic verification.
 * Screenshots are evidence only and are not a standalone assertion type.
 */
public enum AssertionType {
    HTTP_STATUS,
    HTTP_JSONPATH,
    HTTP_HEADER,
    HTTP_SCHEMA,
    HTTP_POLL,
    SQL_ROW_EXISTS,
    SQL_FIELD_VALUE,
    SQL_ROW_COUNT,
    SQL_INVARIANT,
    FILE_EXISTS,
    FILE_HASH,
    FILE_TEXT,
    FILE_FORBIDDEN,
    LOG_MUST_MATCH,
    LOG_MUST_NOT_MATCH,
    LOG_SECRET_SCAN,
    BROWSER_DOM,
    BROWSER_ARIA,
    BROWSER_VISIBLE,
    BROWSER_ROUTE
}
