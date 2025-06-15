package com.hibiscusmc.hmcrewards.user.data;

import com.hibiscusmc.hmcrewards.HMCRewardsPlugin;
import com.hibiscusmc.hmcrewards.reward.RewardProviderRegistry;
import com.hibiscusmc.hmcrewards.user.data.mongo.MongoUserDatastore;
import com.hibiscusmc.hmcrewards.user.data.json.JsonUserDatastore;
import com.hibiscusmc.hmcrewards.util.YamlFileConfiguration;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bukkit.configuration.ConfigurationSection;
import team.unnamed.inject.Inject;
import team.unnamed.inject.Named;
import team.unnamed.inject.Provider;

import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

public final class UserDatastoreProvider implements Provider<UserDatastore> {
    @Inject private HMCRewardsPlugin plugin;
    @Inject private RewardProviderRegistry rewardProviderRegistry;
    @Inject @Named("config.yml") private YamlFileConfiguration config;

    @Override
    public UserDatastore get() {
        final ConfigurationSection section = config.getConfigurationSection("data");
        if (section == null) {
            // warn and fallback to memory user datastore
            plugin.getLogger().warning("No 'data' section found in config.yml, no persistent" +
                    " storage will be used. (using an in-memory user datastore, data will be lost on restart)");
            return new MemoryUserDatastore();
        }

        final String type = section.getString("store", "memory");

        switch (type.toLowerCase()) {
            case "memory":
                plugin.getLogger().warning("Using an in-memory user datastore, data will be lost on restart");
                return new MemoryUserDatastore();
            case "mongo":
            case "mongodb": {
                final String uri = requireNonNull(section.getString("mongodb.uri"), "'uri' not specified for mongodb datastore.");

                // อ่านการตั้งค่า connection pool จาก config
                final int maxPoolSize = section.getInt("mongodb.connection-pool.max-pool-size", 50);
                final int minPoolSize = section.getInt("mongodb.connection-pool.min-pool-size", 5);
                final int maxWaitTimeMs = section.getInt("mongodb.connection-pool.max-wait-time-ms", 10000);
                final int maxConnectionIdleTimeMs = section.getInt("mongodb.connection-pool.max-connection-idle-time-ms", 300000);
                final int maxConnectionLifeTimeMs = section.getInt("mongodb.connection-pool.max-connection-life-time-ms", 1800000); // 30 นาที

                // สร้าง MongoClientSettings พร้อม connection pool configuration
                final MongoClientSettings settings = MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(uri))
                        .applyToConnectionPoolSettings(builder ->
                                builder.maxSize(maxPoolSize)
                                        .minSize(minPoolSize)
                                        .maxWaitTime(maxWaitTimeMs, TimeUnit.MILLISECONDS)
                                        .maxConnectionIdleTime(maxConnectionIdleTimeMs, TimeUnit.MILLISECONDS)
                                        .maxConnectionLifeTime(maxConnectionLifeTimeMs, TimeUnit.MILLISECONDS))
                        .applyToSocketSettings(builder ->
                                builder.connectTimeout(5000, TimeUnit.MILLISECONDS)
                                        .readTimeout(10000, TimeUnit.MILLISECONDS))
                        .applyToServerSettings(builder ->
                                builder.heartbeatFrequency(10000, TimeUnit.MILLISECONDS)
                                        .minHeartbeatFrequency(500, TimeUnit.MILLISECONDS))
                        .build();

                final MongoClient client = MongoClients.create(settings);

                // defer client.close() to be called when the plugin is disabled
                plugin.deferResourceCloseOnPluginDisable(client);

                final MongoDatabase database = client.getDatabase(requireNonNull(section.getString("mongodb.database"), "'database' not specified for mongodb datastore."));

                // Log connection pool settings
                plugin.getLogger().info("MongoDB Connection Pool configured:");
                plugin.getLogger().info("  Max Pool Size: " + maxPoolSize);
                plugin.getLogger().info("  Min Pool Size: " + minPoolSize);
                plugin.getLogger().info("  Max Wait Time: " + maxWaitTimeMs + "ms");
                plugin.getLogger().info("  Max Idle Time: " + maxConnectionIdleTimeMs + "ms");

                return new MongoUserDatastore(database, rewardProviderRegistry);
            }
            case "json": {
                return new JsonUserDatastore(plugin.getDataFolder().toPath().resolve("userdata"), rewardProviderRegistry);
            }
            default: {
                throw new IllegalArgumentException("Unsupported datastore type: " + type);
            }
        }
    }
}