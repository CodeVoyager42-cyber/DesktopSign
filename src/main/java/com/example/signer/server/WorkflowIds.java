package com.example.signer.server;

import java.util.regex.Pattern;

/**
 * Workflow ids are used as directory names under the server storage, so
 * any id that reaches the filesystem must be a plain token: letters,
 * digits, '-' and '_' only (no separators, no "..").
 */
public final class WorkflowIds {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private WorkflowIds() {
    }

    /** Whether {@code workflowId} is safe to use as a storage directory name. */
    public static boolean isSafe(String workflowId) {
        return workflowId != null && SAFE.matcher(workflowId).matches();
    }

    /** @throws IllegalArgumentException when the id is not a safe token */
    public static String requireSafe(String workflowId) {
        if (!isSafe(workflowId)) {
            throw new IllegalArgumentException("Invalid workflow id");
        }
        return workflowId;
    }
}
