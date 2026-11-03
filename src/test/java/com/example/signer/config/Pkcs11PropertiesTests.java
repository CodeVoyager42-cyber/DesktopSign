package com.example.signer.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class Pkcs11PropertiesTests {

    @Autowired
    private Pkcs11Properties properties;

    @Test
    void bindsDefaultsFromConfiguration() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getLibrary()).isEmpty();
        assertThat(properties.getSlotListIndex()).isZero();
    }
}