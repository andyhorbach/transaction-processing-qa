package io.github.andyhorbach.txnqa.common;

import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    /**
     * A syntactically invalid id in a path can never match an existing resource,
     * so it is reported the same way as an absent one (consistent with D-1: no
     * distinction that could leak information about what exists).
     */
    public static UUID parsePathId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound();
        }
    }

    /**
     * An invalid id inside a request body is a validation problem (400), unlike path ids.
     */
    public static UUID parseBodyId(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(field + " must be a valid UUID");
        }
    }
}
