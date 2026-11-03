package com.example.signer.client;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;

/**
 * A coordination-server answer that was an error: keeps the HTTP status and
 * the server's stable error code (e.g. {@code INVITATION_EXPIRED}) so the UI
 * can show the right state instead of one generic message. Still a
 * {@link SignerException}, so existing callers are unaffected.
 */
public class RemoteApiException extends SignerException {

    private final int httpStatus;
    private final String serverCode;

    public RemoteApiException(ErrorCode errorCode, String message,
                              int httpStatus, String serverCode) {
        super(errorCode, message);
        this.httpStatus = httpStatus;
        this.serverCode = serverCode;
    }

    /** HTTP status of the server's answer. */
    public int httpStatus() {
        return httpStatus;
    }

    /** The server's {@code ApiError.code}, or {@code null} when the body had none. */
    public String serverCode() {
        return serverCode;
    }
}
