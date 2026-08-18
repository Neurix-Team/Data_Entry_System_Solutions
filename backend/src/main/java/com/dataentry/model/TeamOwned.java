package com.dataentry.model;

/**
 * Marker for entities that live under a {@link Team}. Implementing this lets
 * {@link TenantEntityListener} auto-stamp the owning team on insert without needing an
 * annotation switch inside the listener for every entity type.
 */
public interface TeamOwned {
    Team getTeam();
    void setTeam(Team team);
}
