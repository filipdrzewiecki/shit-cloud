package com.drzewiecki.shitcloud.security;

import java.util.List;

/**
 * Who is calling, in the platform's own terms — the same shape whether the caller came through the
 * identity provider or the guest password.
 *
 * @param id written to {@code owner} / {@code updatedBy}: the email for identity-provider users,
 *           {@code guest:<name>} for guests
 */
public record CurrentUser(
        String id,
        String displayName,
        List<String> groups,
        boolean builder,
        boolean admin,
        boolean guest) {
}
