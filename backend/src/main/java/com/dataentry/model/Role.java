package com.dataentry.model;

public enum Role {
    /**
     * Cross-team operator. Not scoped to a Team; can view, impersonate and manage every
     * team in the system. Only a handful of users should ever have this role.
     */
    SUPER_ADMIN,
    /** Team owner. Manages users, projects, departments, tickets — but only inside their own team. */
    ADMIN,
    /** Data-entry agent. Submits tickets and works inside a single team. */
    USER
}
