package com.example.signer.signing;

/**
 * Application-level signing service.
 *
 * <p>Coordinates a signature operation on top of the generic token
 * abstraction. Independent of any concrete token vendor and of any specific
 * document format (raw bytes in, signature out; PDF handling is layered above
 * this service).
 */
public interface SigningService {

    SigningResult sign(SigningRequest request);
}