package com.example.signer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for the external {@code signature.pkcs11.*} configuration block.
 *
 * <p>Values are provided through {@code application.yml} and can be overridden
 * with environment variables (for example {@code PKCS11_LIBRARY}). No PIN or
 * other secret is ever part of this configuration.
 */
@ConfigurationProperties(prefix = "signature.pkcs11")
public class Pkcs11Properties {

    private boolean enabled;
    private String library = "";
    private int slotListIndex;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLibrary() {
        return library;
    }

    public void setLibrary(String library) {
        this.library = library;
    }

    public int getSlotListIndex() {
        return slotListIndex;
    }

    public void setSlotListIndex(int slotListIndex) {
        this.slotListIndex = slotListIndex;
    }
}