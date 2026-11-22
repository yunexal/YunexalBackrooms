package com.yunexal.backrooms;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Dry, silent void biome across Level 0 so vanilla rain and cave noises never clash with fluorescent hum.
 */
public final class Level0BiomeProvider extends BiomeProvider {
    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return Biome.THE_VOID;
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return List.of(Biome.THE_VOID);
    }
}
