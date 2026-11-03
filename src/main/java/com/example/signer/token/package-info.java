/**
 * Generic, hardware-independent token abstraction.
 *
 * <p>Defines the boundary the rest of the application depends on. Concrete
 * tokens (PKCS#11 based, or a mock implementation for development/testing)
 * implement this contract. Nothing in this package may depend on a specific
 * hardware vendor.
 */
package com.example.signer.token;