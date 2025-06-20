package com.hibiscusmc.hmcrewards.user.data;

import com.hibiscusmc.hmcrewards.user.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface UserDatastore {
    @Nullable User findByUuid(final @NotNull UUID uuid);

    @Nullable User findByUsername(final @NotNull String username);

    @Nullable User findByUsernameIgnoreCase(@NotNull String username);

    void save(final @NotNull User user);
}
