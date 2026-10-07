package dev.basehider;

import java.util.UUID;

/** Minimal per-player record of an entity the client was told about. */
final class TrackedEntity {
    final int id;
    final UUID uuid;
    /** True while this entity is suppressed for the owning player. */
    volatile boolean hidden;

    TrackedEntity(int id, UUID uuid) {
        this.id = id;
        this.uuid = uuid;
    }
}
