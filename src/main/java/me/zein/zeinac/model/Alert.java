package me.zein.zeinac.model;

import java.util.UUID;

/**
 * One evidence record. Contains only what an investigation needs: who, when, where, why, how sure.
 * No IP addresses, chat or inventories are ever stored.
 */
public record Alert(long ts, UUID uuid, String name, String module, String type, Confidence confidence,
                    double score, String world, int x, int y, int z, String detail) {
}
