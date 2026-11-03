package com.example.signer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
    /**
     * Where to look for the Gemalto/eBarid driver when {@link #library} is
     * not configured (see {@code Pkcs11LibraryResolver}).
     */
    private List<String> autoDetectDirs = List.of(Pkcs11LibraryResolver.SYSTEM32);

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

    public List<String> getAutoDetectDirs() {
        return autoDetectDirs;
    }

    public void setAutoDetectDirs(List<String> autoDetectDirs) {
        this.autoDetectDirs = autoDetectDirs;
    }
}