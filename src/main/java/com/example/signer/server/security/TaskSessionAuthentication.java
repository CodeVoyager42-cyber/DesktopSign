package com.example.signer.server.security;

import com.example.signer.server.auth.JwtService.TaskAuthentication;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Phase B: a desktop task session opened with a website launch code. Carries
 * {@code ROLE_TASK} only — never {@code ROLE_USER} — so it reaches nothing but
 * the single-task routes of its one workflow.
 */
public final class TaskSessionAuthentication extends JwtAuthenticationToken {

    private final TaskAuthentication task;

    public TaskSessionAuthentication(Jwt jwt, TaskAuthentication task) {
        super(jwt, AuthorityUtils.createAuthorityList(SecurityConfig.ROLE_TASK), task.principal());
        this.task = task;
    }

    public TaskAuthentication task() {
        return task;
    }
}
