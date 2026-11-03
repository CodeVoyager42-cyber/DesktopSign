package com.example.signer.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision rule applied to a Windows ACL <b>as read back</b> (inherited
 * entries included). Pure logic with synthetic principals, so it runs on
 * every platform. That the real Windows calls produce and return such ACLs
 * is NOT shown here: it needs a run on Windows.
 */
class WindowsAclPolicyTests {

    private record Principal(String name) implements UserPrincipal {
        @Override
        public String getName() {
            return name;
        }
    }

    private static final UserPrincipal ME = new Principal("S-1-5-21-1-2-3-1001");
    private static final UserPrincipal SYSTEM = new Principal("S-1-5-18");
    private static final UserPrincipal ADMINS = new Principal("S-1-5-32-544");
    private static final UserPrincipal OTHER = new Principal("S-1-5-21-1-2-3-1002");
    private static final UserPrincipal EVERYONE = new Principal("S-1-1-0");
    private static final Set<UserPrincipal> ALLOWED = Set.of(ME, SYSTEM);

    private static AclEntry entry(AclEntryType type, UserPrincipal who,
                                  Set<AclEntryPermission> perms) {
        return AclEntry.newBuilder().setType(type).setPrincipal(who).setPermissions(perms)
                .setFlags(EnumSet.of(AclEntryFlag.FILE_INHERIT)).build();
    }

    private static AclEntry allow(UserPrincipal who) {
        return entry(AclEntryType.ALLOW, who, EnumSet.allOf(AclEntryPermission.class));
    }

    @Test
    void onlyThisAccountAndSystemIsPrivate() {
        assertThat(SingleInstanceGuard.aclIsPrivate(List.of(allow(ME), allow(SYSTEM)), ME, ALLOWED))
                .isTrue();
        assertThat(SingleInstanceGuard.aclIsPrivate(List.of(allow(ME)), ME, ALLOWED)).isTrue();
    }

    @Test
    void anyOtherAllowedPrincipalIsRefusedIncludingInheritedOnes() {
        for (UserPrincipal intruder : List.of(OTHER, EVERYONE, ADMINS)) {
            assertThat(SingleInstanceGuard.aclIsPrivate(
                    List.of(allow(ME), allow(SYSTEM), allow(intruder)), ME, ALLOWED))
                    .as(intruder.getName()).isFalse();
        }
        // Even read-only access for someone else is too much for the key.
        assertThat(SingleInstanceGuard.aclIsPrivate(List.of(allow(ME),
                entry(AclEntryType.ALLOW, OTHER, EnumSet.of(AclEntryPermission.READ_DATA))),
                ME, ALLOWED)).isFalse();
    }

    @Test
    void denyAndAuditEntriesGrantNothing() {
        assertThat(SingleInstanceGuard.aclIsPrivate(List.of(allow(ME),
                entry(AclEntryType.DENY, EVERYONE, EnumSet.allOf(AclEntryPermission.class)),
                entry(AclEntryType.AUDIT, OTHER, EnumSet.of(AclEntryPermission.READ_DATA))),
                ME, ALLOWED)).isTrue();
    }

    @Test
    void theAccountMustBeAbleToUseItsOwnFiles() {
        assertThat(SingleInstanceGuard.aclIsPrivate(List.of(allow(SYSTEM)), ME, ALLOWED)).isFalse();
        assertThat(SingleInstanceGuard.aclIsPrivate(List.of(
                entry(AclEntryType.ALLOW, ME, EnumSet.of(AclEntryPermission.READ_DATA))),
                ME, ALLOWED)).isFalse();
        assertThat(SingleInstanceGuard.aclIsPrivate(List.of(), ME, ALLOWED)).isFalse();
    }
}
