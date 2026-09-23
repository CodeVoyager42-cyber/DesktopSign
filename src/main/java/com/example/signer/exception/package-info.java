/**
 * Application-wide error codes and the single service exception type.
 *
 * <p>Services throw {@code TokenException}-style failures carrying a
 * {@link com.example.signer.exception.ErrorCode}; the UI maps codes to
 * user-safe sentences centrally ({@code com.example.signer.ui.ErrorMessages})
 * so raw technical detail never reaches a normal user.</p>
 */
package com.example.signer.exception;
