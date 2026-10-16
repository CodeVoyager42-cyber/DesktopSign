package com.example.signer.server.db.repository;

import com.example.signer.server.db.entity.UserEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** User accounts (login, invitation binding, dev seeding). */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByUsername(String username);

    /**
     * Whether a username is taken ignoring letter case — the rule the V5
     * {@code uq_users_username_ci} index enforces. Use it before creating an
     * account so a case-only clash is a clean "taken", not a constraint error.
     */
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmail(String email);
}
