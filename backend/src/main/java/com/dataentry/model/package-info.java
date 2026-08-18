/**
 * JPA entities for the data-entry system.
 *
 * <p>The package-level {@link org.hibernate.annotations.FilterDef} declares a single
 * tenant-scope filter used by every entity annotated with
 * {@link org.hibernate.annotations.Filter @Filter(name = "teamFilter")}. The filter is
 * enabled per-transaction by {@code com.dataentry.security.TenantFilterAspect} using the
 * team id in {@code TenantContext}. SUPER_ADMIN skips the filter and sees all teams.
 */
@org.hibernate.annotations.FilterDef(
        name = "teamFilter",
        parameters = @org.hibernate.annotations.ParamDef(name = "teamId", type = Long.class)
)
package com.dataentry.model;
