package me.zein.zeinac.hide.impl;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import me.zein.zeinac.hide.HideSnapshot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty-thread packet rewriting. Reads only immutable {@link HideSnapshot}s, never touches the Bukkit API.
 * Hidden regions are filled with the dimension's ordinary stone so nothing underneath (ores, caves, bases) is sent.
 */
final class HideListener extends PacketListenerAbstract {
    private static final int STONE = 0;
    private static final int DEEPSLATE = 1;
    private static final int NETHERRACK = 2;
    private static final int END_STONE = 3;

    private final Map<UUID, HideSnapshot> snapshots;
    private final Map<ClientVersion, int[]> idCache = new ConcurrentHashMap<>();

    HideListener(Map<UUID, HideSnapshot> snapshots) {
        super(PacketListenerPriority.NORMAL);
        this.snapshots = snapshots;
    }

    private int[] ids(ClientVersion v) {
        return idCache.computeIfAbsent(v, k -> new int[] {
                StateTypes.STONE.createBlockState(k).getGlobalId(),
                StateTypes.DEEPSLATE.createBlockState(k).getGlobalId(),
                StateTypes.NETHERRACK.createBlockState(k).getGlobalId(),
                StateTypes.END_STONE.createBlockState(k).getGlobalId()});
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }
        Object type = event.getPacketType();
        if (type != PacketType.Play.Server.CHUNK_DATA
                && type != PacketType.Play.Server.BLOCK_CHANGE
                && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            return;
        }
        HideSnapshot snap = snapshots.get(event.getUser().getUUID());
        if (snap == null) {
            return;
        }
        try {
            if (type == PacketType.Play.Server.CHUNK_DATA) {
                rewriteChunk(event, snap);
            } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
                WrapperPlayServerBlockChange w = new WrapperPlayServerBlockChange(event);
                Vector3i pos = w.getBlockPosition();
                if (pos.getY() < snap.hideBelowY() && snap.hidden(pos.getX() >> 4, pos.getZ() >> 4)) {
                    event.setCancelled(true);
                }
            } else {
                WrapperPlayServerMultiBlockChange w = new WrapperPlayServerMultiBlockChange(event);
                WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = w.getBlocks();
                if (blocks.length > 0 && blocks[0].getY() < snap.hideBelowY()
                        && snap.hidden(blocks[0].getX() >> 4, blocks[0].getZ() >> 4)) {
                    event.setCancelled(true); // a multi-block change always lies inside one 16x16x16 section
                }
            }
        } catch (RuntimeException ex) {
            // never break the connection because of a rewrite problem; the packet goes out unmodified
        }
    }

    private void rewriteChunk(PacketSendEvent event, HideSnapshot snap) {
        WrapperPlayServerChunkData w = new WrapperPlayServerChunkData(event);
        Column column = w.getColumn();
        if (!snap.hidden(column.getX(), column.getZ())) {
            return;
        }
        int[] ids = ids(event.getUser().getClientVersion());
        BaseChunk[] sections = column.getChunks();
        boolean changed = false;
        for (int i = 0; i < sections.length; i++) {
            int baseY = snap.minY() + i * 16;
            if (baseY >= snap.hideBelowY()) {
                break;
            }
            BaseChunk section = sections[i];
            if (section == null) {
                continue;
            }
            int top = Math.min(16, snap.hideBelowY() - baseY);
            for (int y = 0; y < top; y++) {
                int id = fake(snap.env(), baseY + y, ids);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        section.set(x, y, z, id);
                    }
                }
            }
            changed = true;
        }
        if (changed) {
            event.markForReEncode(true);
        }
    }

    private static int fake(int env, int y, int[] ids) {
        return switch (env) {
            case HideSnapshot.ENV_NETHER -> ids[NETHERRACK];
            case HideSnapshot.ENV_END -> ids[END_STONE];
            default -> y < 0 ? ids[DEEPSLATE] : ids[STONE];
        };
    }
}
