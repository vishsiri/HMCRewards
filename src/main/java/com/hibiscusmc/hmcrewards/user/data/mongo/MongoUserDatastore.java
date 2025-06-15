package com.hibiscusmc.hmcrewards.user.data.mongo;

import com.hibiscusmc.hmcrewards.data.serialize.bson.BsonCodecAdapter;
import com.hibiscusmc.hmcrewards.reward.RewardProviderRegistry;
import com.hibiscusmc.hmcrewards.user.User;
import com.hibiscusmc.hmcrewards.user.data.UserDatastore;
import com.hibiscusmc.hmcrewards.user.data.serialize.UserCodec;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.codecs.Codec;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class MongoUserDatastore implements UserDatastore {
    private final MongoCollection<User> collection;

    public MongoUserDatastore(final @NotNull MongoDatabase database, final @NotNull RewardProviderRegistry rewardProviderRegistry) {
        this.collection = database
                .withCodecRegistry(CodecRegistries.fromRegistries(
                        database.getCodecRegistry(),
                        CodecRegistries.fromProviders(new CodecProvider() {
                            private final Codec<User> USER_CODEC = new BsonCodecAdapter<>(new UserCodec(rewardProviderRegistry));
                            @Override
                            public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
                                // if class is User or subclass of User, return the UserCodec
                                if (User.class.isAssignableFrom(clazz)) {
                                    //noinspection unchecked
                                    return (Codec<T>) USER_CODEC;
                                }
                                return null;
                            }
                        })
                )).getCollection("users", User.class);

        // สร้าง indexes เพื่อเพิ่มประสิทธิภาพ
        createIndexes();
    }

    private void createIndexes() {
        try {
            // Index สำหรับ uuid (unique)
            collection.createIndex(
                    Indexes.ascending("uuid"),
                    new IndexOptions().unique(true).name("uuid_1")
            );

            // Index สำหรับ name (ไม่ unique เพราะอาจมีชื่อซ้ำ)
            collection.createIndex(
                    Indexes.ascending("name"),
                    new IndexOptions().name("name_1")
            );

            // Compound index สำหรับ name + uuid เพื่อ case-insensitive search
            collection.createIndex(
                    Indexes.compoundIndex(
                            Indexes.ascending("name"),
                            Indexes.ascending("uuid")
                    ),
                    new IndexOptions().name("name_uuid_1")
            );

        } catch (Exception e) {
            // Log warning แต่ไม่ throw exception เพราะ index อาจมีอยู่แล้ว
            System.err.println("Warning: Could not create indexes: " + e.getMessage());
        }
    }

    @Override
    public @Nullable User findByUuid(final @NotNull UUID uuid) {
        try {
            return collection.find(Filters.eq("uuid", uuid.toString()))
                    .maxTime(5, TimeUnit.SECONDS) // Timeout หลังจาก 5 วินาที
                    .first();
        } catch (Exception e) {
            System.err.println("Error finding user by UUID " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public @Nullable User findByUsername(final @NotNull String username) {
        try {
            return collection.find(Filters.eq("name", username))
                    .maxTime(5, TimeUnit.SECONDS)
                    .first();
        } catch (Exception e) {
            System.err.println("Error finding user by username " + username + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public @Nullable User findByUsernameIgnoreCase(@NotNull String username) {
        try {
            return collection.find(Filters.regex("name", "^" + username + "$", "i"))
                    .maxTime(5, TimeUnit.SECONDS)
                    .first();
        } catch (Exception e) {
            System.err.println("Error finding user by username (ignore case) " + username + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public void save(final @NotNull User user) {
        try {
            collection.replaceOne(
                    Filters.eq("uuid", user.uuid().toString()),
                    user,
                    new ReplaceOptions().upsert(true)
            );
        } catch (Exception e) {
            System.err.println("Error saving user " + user.uuid() + ": " + e.getMessage());
            throw new RuntimeException("Failed to save user data", e);
        }
    }
}