package dev.basehider;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.HeightmapType;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.*;

import java.util.Map;
import java.util.UUID;

/**
 * Filters clientbound packets per player. Runs on netty threads, so it only touches
 * thread-safe state ({@link PlayerState}) and never calls Bukkit world APIs.
 *
 * <p>Only the packet types in {@link #RELEVANT} are looked at; every other packet costs one
 * array lookup.
 */
final class PacketHandler extends PacketListenerAbstract {

    private static final int NO_HIDE = PlayerState.NO_HIDE;
    private static final boolean[] RELEVANT = new boolean[PacketType.Play.Server.values().length];

    static {
        PacketType.Play.Server[] relevant = {
                // blocks
                PacketType.Play.Server.CHUNK_DATA, PacketType.Play.Server.UNLOAD_CHUNK,
                PacketType.Play.Server.BLOCK_CHANGE, PacketType.Play.Server.MULTI_BLOCK_CHANGE,
                PacketType.Play.Server.BLOCK_ENTITY_DATA, PacketType.Play.Server.BLOCK_ACTION,
                PacketType.Play.Server.BLOCK_BREAK_ANIMATION,
                // effects
                PacketType.Play.Server.EFFECT, PacketType.Play.Server.SOUND_EFFECT, PacketType.Play.Server.PARTICLE,
                // world reset
                PacketType.Play.Server.RESPAWN, PacketType.Play.Server.JOIN_GAME,
                // entities
                PacketType.Play.Server.SPAWN_ENTITY, PacketType.Play.Server.DESTROY_ENTITIES,
                PacketType.Play.Server.ENTITY_METADATA, PacketType.Play.Server.ENTITY_EQUIPMENT,
                PacketType.Play.Server.ENTITY_VELOCITY, PacketType.Play.Server.ENTITY_RELATIVE_MOVE,
                PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION, PacketType.Play.Server.ENTITY_ROTATION,
                PacketType.Play.Server.ENTITY_HEAD_LOOK, PacketType.Play.Server.ENTITY_TELEPORT,
                PacketType.Play.Server.ENTITY_POSITION_SYNC, PacketType.Play.Server.ENTITY_MOVEMENT,
                PacketType.Play.Server.ENTITY_STATUS, PacketType.Play.Server.ENTITY_ANIMATION,
                PacketType.Play.Server.ENTITY_EFFECT, PacketType.Play.Server.REMOVE_ENTITY_EFFECT,
                PacketType.Play.Server.UPDATE_ATTRIBUTES, PacketType.Play.Server.SET_PASSENGERS,
                PacketType.Play.Server.DAMAGE_EVENT, PacketType.Play.Server.HURT_ANIMATION,
                PacketType.Play.Server.ENTITY_SOUND_EFFECT, PacketType.Play.Server.MOVE_MINECART,
                PacketType.Play.Server.COLLECT_ITEM
        };
        for (PacketType.Play.Server t : relevant) {
            RELEVANT[t.ordinal()] = true;
        }
    }

    private final VisibilityManager vm;

    PacketHandler(VisibilityManager vm) {
        this.vm = vm;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacketType() instanceof PacketType.Play.Server type) || !RELEVANT[type.ordinal()]) {
            return;
        }
        User user = event.getUser();
        PlayerState st = user == null ? null : vm.state(user.getUUID());
        if (st == null) {
            return;
        }
        try {
            handle(event, type, st, user);
        } catch (Throwable t) {
            // Fail open: a broken filter must never break the connection or the server.
            vm.debug("packet " + type + " not filtered: " + t);
        }
    }

    private void handle(PacketSendEvent event, PacketType.Play.Server type, PlayerState st, User user) {
        switch (type) {
            case RESPAWN, JOIN_GAME -> st.clearWorldData();
            case UNLOAD_CHUNK -> {
                WrapperPlayServerUnloadChunk w = new WrapperPlayServerUnloadChunk(event);
                st.unloadChunk(PlayerState.chunkKey(w.getChunkX(), w.getChunkZ()));
            }
            case CHUNK_DATA -> {
                if (st.enabled) {
                    onChunkData(event, st, user);
                }
            }
            case BLOCK_CHANGE -> {
                if (st.enabled && st.ceiling != NO_HIDE) {
                    int y = new WrapperPlayServerBlockChange(event).getBlockPosition().getY();
                    cancelIfHidden(event, st, y);
                }
            }
            case MULTI_BLOCK_CHANGE -> {
                if (st.enabled && st.ceiling != NO_HIDE) {
                    // chunk position of this packet is (chunkX, SECTION Y, chunkZ)
                    int sectionY = new WrapperPlayServerMultiBlockChange(event).getChunkPosition().getY();
                    int h = st.ceiling;
                    if (h != NO_HIDE && sectionY <= h) {
                        cancel(event);
                    }
                }
            }
            case BLOCK_ENTITY_DATA -> {
                if (st.enabled && st.ceiling != NO_HIDE) {
                    cancelIfHidden(event, st, new WrapperPlayServerBlockEntityData(event).getPosition().getY());
                }
            }
            case BLOCK_ACTION -> {
                if (st.enabled && st.ceiling != NO_HIDE) {
                    cancelIfHidden(event, st, new WrapperPlayServerBlockAction(event).getBlockPosition().getY());
                }
            }
            case BLOCK_BREAK_ANIMATION -> {
                if (st.enabled && st.ceiling != NO_HIDE) {
                    cancelIfHidden(event, st, new WrapperPlayServerBlockBreakAnimation(event).getBlockPosition().getY());
                }
            }
            case EFFECT -> {
                if (st.enabled && st.ceiling != NO_HIDE && vm.config().filterEffects) {
                    Vector3i pos = new WrapperPlayServerEffect(event).getPosition();
                    if (pos != null) {
                        cancelIfHidden(event, st, pos.getY());
                    }
                }
            }
            case SOUND_EFFECT -> {
                if (st.enabled && st.ceiling != NO_HIDE && vm.config().filterEffects) {
                    Vector3d pos = new WrapperPlayServerSoundEffect(event).getPosition();
                    if (pos != null) {
                        cancelIfHidden(event, st, (int) Math.floor(pos.getY()));
                    }
                }
            }
            case PARTICLE -> {
                if (st.enabled && st.ceiling != NO_HIDE && vm.config().filterEffects) {
                    Vector3d pos = new WrapperPlayServerParticle(event).getPosition();
                    if (pos != null) {
                        cancelIfHidden(event, st, (int) Math.floor(pos.getY()));
                    }
                }
            }
            case SPAWN_ENTITY -> {
                if (st.enabled && vm.config().hideEntities) {
                    onEntitySpawn(event, st);
                }
            }
            case DESTROY_ENTITIES -> {
                if (!st.entities.isEmpty()) {
                    for (int id : new WrapperPlayServerDestroyEntities(event).getEntityIds()) {
                        TrackedEntity te = st.entities.remove(id);
                        if (te != null) {
                            st.setHidden(te, false);
                        }
                    }
                }
            }
            default -> onEntityPacket(event, type, st);
        }
    }

    // ------------------------------------------------------------------ chunks

    private void onChunkData(PacketSendEvent event, PlayerState st, User user) {
        WrapperPlayServerChunkData w = new WrapperPlayServerChunkData(event);
        Column col = w.getColumn();
        BaseChunk[] sections = col.getChunks();
        int minSection = user.getMinWorldHeight() >> 4;

        // Atomically record which ceiling this chunk is sent with, so later transitions
        // know exactly what the client holds.
        int ceiling = st.registerChunk(PlayerState.chunkKey(col.getX(), col.getZ()));
        vm.chunkPackets.increment();
        if (ceiling == NO_HIDE) {
            return;
        }

        ClientVersion version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        boolean changed = false;
        int top = Math.min(ceiling - minSection, sections.length - 1);
        for (int i = 0; i <= top; i++) {
            if (sections[i] instanceof Chunk_v1_18 sec && !sec.isEmpty()) {
                // Same biomes, but a single-value (AIR) block palette: tiny, and nothing to leak.
                sections[i] = new Chunk_v1_18(version, 0, 0,
                        new DataPalette(new SingletonPalette(0), null, PaletteType.CHUNK),
                        sec.getBiomeData());
                vm.sectionsBlanked.increment();
                changed = true;
            }
        }

        // Block entities inside hidden sections must not be announced either.
        TileEntity[] tiles = col.getTileEntities();
        int keep = 0;
        for (TileEntity te : tiles) {
            if (Math.floorDiv((int) te.getYShort(), 16) > ceiling) {
                keep++;
            }
        }
        Column out = col;
        if (keep != tiles.length) {
            TileEntity[] kept = new TileEntity[keep];
            int n = 0;
            for (TileEntity te : tiles) {
                if (Math.floorDiv((int) te.getYShort(), 16) > ceiling) {
                    kept[n++] = te;
                }
            }
            Map<HeightmapType, long[]> heightmaps = col.getHeightmaps();
            out = heightmaps != null
                    ? new Column(col.getX(), col.getZ(), col.isFullChunk(), sections, kept, heightmaps)
                    : new Column(col.getX(), col.getZ(), col.isFullChunk(), sections, kept);
            changed = true;
        }

        if (changed) {
            w.setColumn(out);
            event.markForReEncode(true);
        }
    }

    // ------------------------------------------------------------------ entities

    private void onEntitySpawn(PacketSendEvent event, PlayerState st) {
        WrapperPlayServerSpawnEntity w = new WrapperPlayServerSpawnEntity(event);
        UUID uuid = w.getUUID().orElse(null);
        if (uuid == null) {
            return;
        }
        TrackedEntity te = new TrackedEntity(w.getEntityId(), uuid);
        double py = st.playerY;
        boolean hide = !Double.isNaN(py) && w.getPosition().getY() <= py - vm.config().hideDistanceY;
        if (hide) {
            st.setHidden(te, true); // flag first, then publish, so no later packet can slip through
        }
        TrackedEntity old = st.entities.put(te.id, te);
        if (old != null) {
            st.setHidden(old, false);
        }
        if (hide) {
            cancel(event);
            vm.entitiesHidden.increment();
        }
    }

    private void onEntityPacket(PacketSendEvent event, PacketType.Play.Server type, PlayerState st) {
        if (st.hiddenEntities.get() <= 0) {
            return;
        }
        int id = entityId(event, type);
        if (id < 0) {
            return;
        }
        TrackedEntity te = st.entities.get(id);
        if (te != null && te.hidden) {
            cancel(event);
        }
    }

    /** Id of the entity a packet is about, or -1 if the packet type carries none we filter. */
    private static int entityId(PacketSendEvent e, PacketType.Play.Server type) {
        return switch (type) {
            case ENTITY_METADATA -> new WrapperPlayServerEntityMetadata(e).getEntityId();
            case ENTITY_EQUIPMENT -> new WrapperPlayServerEntityEquipment(e).getEntityId();
            case ENTITY_VELOCITY -> new WrapperPlayServerEntityVelocity(e).getEntityId();
            case ENTITY_RELATIVE_MOVE -> new WrapperPlayServerEntityRelativeMove(e).getEntityId();
            case ENTITY_RELATIVE_MOVE_AND_ROTATION -> new WrapperPlayServerEntityRelativeMoveAndRotation(e).getEntityId();
            case ENTITY_ROTATION -> new WrapperPlayServerEntityRotation(e).getEntityId();
            case ENTITY_HEAD_LOOK -> new WrapperPlayServerEntityHeadLook(e).getEntityId();
            case ENTITY_TELEPORT -> new WrapperPlayServerEntityTeleport(e).getEntityId();
            case ENTITY_POSITION_SYNC -> new WrapperPlayServerEntityPositionSync(e).getId();
            case ENTITY_MOVEMENT -> new WrapperPlayServerEntityMovement(e).getEntityId();
            case ENTITY_STATUS -> new WrapperPlayServerEntityStatus(e).getEntityId();
            case ENTITY_ANIMATION -> new WrapperPlayServerEntityAnimation(e).getEntityId();
            case ENTITY_EFFECT -> new WrapperPlayServerEntityEffect(e).getEntityId();
            case REMOVE_ENTITY_EFFECT -> new WrapperPlayServerRemoveEntityEffect(e).getEntityId();
            case UPDATE_ATTRIBUTES -> new WrapperPlayServerUpdateAttributes(e).getEntityId();
            case SET_PASSENGERS -> new WrapperPlayServerSetPassengers(e).getEntityId();
            case DAMAGE_EVENT -> new WrapperPlayServerDamageEvent(e).getEntityId();
            case HURT_ANIMATION -> new WrapperPlayServerHurtAnimation(e).getEntityId();
            case ENTITY_SOUND_EFFECT -> new WrapperPlayServerEntitySoundEffect(e).getEntityId();
            case MOVE_MINECART -> new WrapperPlayServerMoveMinecart(e).getEntityId();
            case COLLECT_ITEM -> new WrapperPlayServerCollectItem(e).getCollectedEntityId();
            default -> -1;
        };
    }

    // ------------------------------------------------------------------ helpers

    private void cancelIfHidden(PacketSendEvent event, PlayerState st, int blockY) {
        int h = st.ceiling;
        if (h != NO_HIDE && (blockY >> 4) <= h) {
            cancel(event);
        }
    }

    private void cancel(PacketSendEvent event) {
        event.setCancelled(true);
        vm.packetsCancelled.increment();
    }
}
