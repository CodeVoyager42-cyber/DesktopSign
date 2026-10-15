package com.example.signer.server.auth;

import com.example.signer.server.RemoteServerProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Accounts from configuration ({@code signature.remote-server.users}) — the
 * legacy/dev implementation, byte-identical behaviour to the previous
 * in-controller check. Active only when the database mode is off.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "false", matchIfMissing = true)
public class ConfiguredUserAccounts implements UserAccountService {

    private final RemoteServerProperties users;

    public ConfiguredUserAccounts(RemoteServerProperties users) {
        this.users = users;
    }

    @Override
    public boolean matches(String username, String password) {
        return users.matches(username, password);
    }

    @Override
    public boolean knowsUser(String username) {
        return users.knowsUser(username);
    }
}
