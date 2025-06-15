package com.hibiscusmc.hmcrewards.user;

import com.hibiscusmc.hmcrewards.user.data.UserDatastore;
import com.hibiscusmc.hmcrewards.util.YamlFileConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.unnamed.inject.Inject;
import team.unnamed.inject.Named;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class UserManagerImpl implements UserManager {
    private final Map<UUID, User> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUpdated = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Inject private Plugin plugin;
    @Inject private UserDatastore datastore;
    @Inject @Named("config.yml") private YamlFileConfiguration config;

    public UserManagerImpl() {
        // Cache cleanup task ทุก 5 นาที
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long maxAge = config.getLong("data.cache-ttl-minutes", 10) * 60 * 1000;

            lastUpdated.entrySet().removeIf(entry -> {
                if (now - entry.getValue() > maxAge) {
                    cache.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        }, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public @Nullable User getCached(final @NotNull UUID id) {
        boolean smartCaching = config.getBoolean("data.smart-caching", true);
        long cacheRefreshInterval = config.getLong("data.cache-refresh-seconds", 30) * 1000;

        if (!smartCaching) {
            // Realtime mode - อ่านจาก database ทุกครั้ง
            return datastore.findByUuid(id);
        }

        // Smart caching mode
        User cachedUser = cache.get(id);
        Long lastUpdate = lastUpdated.get(id);
        long now = System.currentTimeMillis();

        // ถ้าไม่มีใน cache หรือ cache หมดอายุ
        if (cachedUser == null || lastUpdate == null || (now - lastUpdate) > cacheRefreshInterval) {
            // อ่านจาก database แบบ async เพื่อไม่ให้ block
            CompletableFuture.supplyAsync(() -> {
                try {
                    User freshUser = datastore.findByUuid(id);
                    if (freshUser != null) {
                        cache.put(id, freshUser);
                        lastUpdated.put(id, now);
                    }
                    return freshUser;
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to refresh cache for user " + id + ": " + e.getMessage());
                    return cachedUser; // fallback to cached version
                }
            });

            // ส่งคืน cached version ในขณะที่รอ refresh
            return cachedUser;
        }

        return cachedUser;
    }

    /**
     * เมธอดใหม่สำหรับการอ่านแบบ realtime เมื่อจำเป็น (เช่น reward queue)
     */
    public @Nullable User getRealtimeData(final @NotNull UUID id) {
        User freshUser = datastore.findByUuid(id);
        if (freshUser != null) {
            // อัพเดท cache ด้วย
            cache.put(id, freshUser);
            lastUpdated.put(id, System.currentTimeMillis());
        }
        return freshUser;
    }

    @Override
    public void removeCached(final @NotNull User user) {
        cache.remove(user.uuid());
        lastUpdated.remove(user.uuid());
    }

    @Override
    public void update(final @NotNull User user) {
        cache.put(user.uuid(), user);
        lastUpdated.put(user.uuid(), System.currentTimeMillis());

        boolean autoSave = config.getBoolean("data.auto-save-on-update", false);
        if (autoSave) {
            // บันทึกแบบ async
            saveAsync(user);
        }
    }

    @Override
    public @NotNull Collection<User> cached() {
        return cache.values();
    }

    @Override
    public void clearCache() {
        cache.clear();
        lastUpdated.clear();
    }

    @Override
    public @NotNull CompletableFuture<Void> saveAsync(final @NotNull User user) {
        return CompletableFuture.runAsync(() -> {
            try {
                datastore.save(user);
                // อัพเดท cache timestamp
                lastUpdated.put(user.uuid(), System.currentTimeMillis());
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to save user " + user.uuid() + ": " + e.getMessage());
                throw new RuntimeException(e);
            }
        }, scheduler);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}