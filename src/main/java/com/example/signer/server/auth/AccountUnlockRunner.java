package com.example.signer.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The operator unlock (security Phase 2 risk L3), as a command-line option
 * rather than a route:
 *
 * <pre>
 * java -jar signflow.jar \
 *     --spring.profiles.active=server \
 *     --spring.main.web-application-type=none \
 *     --signer.unlock-account=alice
 * </pre>
 *
 * <p>It clears the account-wide lock and every address's lock for one
 * account, reports what it cleared, and — with
 * {@code web-application-type=none} above — exits, because a context with no
 * server to run closes once its runners have finished.
 *
 * <h2>Why not an admin route</h2>
 *
 * <p>Because the phase documents say there are none, and that is a decision
 * worth keeping rather than quietly reversing for one operation. An endpoint
 * would have needed an operator credential the deployment does not have:
 * either a role system, which this server deliberately does not have either,
 * or a static shared token with no rotation and no attribution — a new
 * high-value secret on the network whose holder could clear their own lock
 * repeatedly and defeat the control this exists to relieve.
 *
 * <p>This path needs shell access to a host that can reach the database,
 * which an operator has and an attacker should not. It is also safer than
 * the alternative it replaces, which is support staff running SQL by hand
 * against production: {@code DELETE FROM login_failures} is one forgotten
 * {@code WHERE} away from unlocking everyone, and it would leave no record.
 *
 * <h2>What it is not</h2>
 *
 * <p>Unlocking is not authentication. It removes refusals and nothing else —
 * the next attempt still has to present the right password — so it cannot be
 * used to get into an account, only to stop one being held shut.
 */
@Component
@ConditionalOnProperty(name = "signer.unlock-account")
public class AccountUnlockRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountUnlockRunner.class);

    private final UserAccountService accounts;
    private final ApplicationContext context;
    private final String username;

    public AccountUnlockRunner(UserAccountService accounts, ApplicationContext context,
                               @Value("${signer.unlock-account}") String username) {
        this.accounts = accounts;
        this.context = context;
        this.username = username;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (this.context instanceof org.springframework.web.context.WebApplicationContext) {
            // Running as a server AND asked to unlock: the option has been
            // left in a deployment's configuration instead of being passed
            // once on a command line, so this would unlock on every restart.
            // Do it, and say so, rather than refusing a support action or
            // shutting a server down for a misconfiguration.
            log.warn("--signer.unlock-account is set in a process that is also serving"
                    + " requests. It is meant to be passed once, to a process started for"
                    + " it; left in configuration it unlocks this account on every start.");
        }
        String name = this.username == null ? "" : this.username.trim();
        if (name.isEmpty()) {
            log.error("--signer.unlock-account needs an account name");
            return;
        }

        UserAccountService.UnlockResult result = this.accounts.unlock(name);

        if (!result.accountKnown()) {
            // Said plainly: a mistyped name must not look like a success.
            log.error("No account '{}' — nothing was unlocked. Check the spelling; this"
                    + " takes the account's username, not its e-mail address.", name);
            return;
        }
        if (!result.clearedAnything()) {
            log.info("Account '{}' was not locked; nothing to clear.", name);
            return;
        }
        log.info("Unlocked account '{}':", name);
        if (result.accountLockCleared()) {
            log.info("  account lock cleared (would have held until {})",
                    result.lockedUntilBefore() == null
                            ? "no lock, failure count only" : result.lockedUntilBefore());
        }
        log.info("  {} address lock(s) forgotten", result.addressLocksForgotten());
        log.info("  the password itself is unchanged — the next attempt still has to be"
                + " right");
    }
}
