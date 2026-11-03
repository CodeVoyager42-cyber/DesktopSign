package com.example.signer;

import com.example.signer.config.LoginProperties;
import com.example.signer.config.Pkcs11Properties;
import com.example.signer.server.RemoteServerProperties;
import com.example.signer.ui.SignerFxApplication;
import javafx.application.Application;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Main entry point of the PDF digital-signer desktop application.
 *
 * <p>The Java Virtual Machine is launched by JavaFX ({@link SignerFxApplication})
 * which then bootstraps the Spring application context so that dependency
 * injection, configuration and lifecycle management can be used by the
 * desktop UI.
 */
@SpringBootApplication
@EnableConfigurationProperties({Pkcs11Properties.class, LoginProperties.class,
        RemoteServerProperties.class})
public class SignerApplication {

    public static void main(String[] args) {
        Application.launch(SignerFxApplication.class, args);
    }
}