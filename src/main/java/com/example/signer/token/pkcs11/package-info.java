/**
 * PKCS#11 implementation of the generic token abstraction.
 *
 * <p>Uses the Java standard SunPKCS11 provider. Vendor-specific details are
 * confined to this package so that swapping the underlying hardware (SoftHSM,
 * a production USB token, etc.) does not affect the rest of the application.
 */
package com.example.signer.token.pkcs11;