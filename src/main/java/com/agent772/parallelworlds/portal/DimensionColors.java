package com.agent772.parallelworlds.portal;

import com.agent772.parallelworlds.config.PWConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps base dimension types to display colors and names for portal visuals.
 * Parses config entries like "minecraft:overworld=#33CC4D".
 */
public final class DimensionColors {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Vector3f FALLBACK_COLOR = parseHex("#33AAEE");
    private static final Map<ResourceLocation, Vector3f> colorCache = new HashMap<>();

    // Friendly names for vanilla dimensions (exploration targets)
    private static final Map<ResourceLocation, String> VANILLA_NAMES = Map.of(
            ResourceLocation.withDefaultNamespace("overworld"), "Parallel Overworld",
            ResourceLocation.withDefaultNamespace("the_nether"), "Parallel Nether",
            ResourceLocation.withDefaultNamespace("the_end"), "Parallel End"
    );

    // Friendly names for vanilla dimensions when used as a return/home destination
    private static final Map<ResourceLocation, String> VANILLA_HOME_NAMES = Map.of(
            ResourceLocation.withDefaultNamespace("overworld"), "Overworld",
            ResourceLocation.withDefaultNamespace("the_nether"), "The Nether",
            ResourceLocation.withDefaultNamespace("the_end"), "The End"
    );

    // Chat colors for vanilla dimensions
    private static final Map<ResourceLocation, ChatFormatting> VANILLA_CHAT_COLORS = Map.of(
            ResourceLocation.withDefaultNamespace("overworld"), ChatFormatting.GREEN,
            ResourceLocation.withDefaultNamespace("the_nether"), ChatFormatting.RED,
            ResourceLocation.withDefaultNamespace("the_end"), ChatFormatting.DARK_PURPLE
    );

    private DimensionColors() {}

    /**
     * Re-parse color config. Called from PWConfig.refresh().
     */
    public static void refresh() {
        colorCache.clear();
        List<String> entries = PWConfig.getDimensionParticleColors();
        if (entries == null) return;

        for (String entry : entries) {
            int eqIdx = entry.indexOf('=');
            if (eqIdx < 0) {
                LOGGER.warn("Invalid dimension color entry (no '='): {}", entry);
                continue;
            }
            String dimStr = entry.substring(0, eqIdx).trim();
            String hexStr = entry.substring(eqIdx + 1).trim();

            ResourceLocation dim = ResourceLocation.tryParse(dimStr);
            if (dim == null) {
                LOGGER.warn("Invalid dimension ResourceLocation in color config: {}", dimStr);
                continue;
            }

            try {
                Vector3f color = parseHex(hexStr);
                colorCache.put(dim, color);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid hex color '{}' for dimension {}: {}", hexStr, dimStr, e.getMessage());
            }
        }
        LOGGER.debug("Loaded {} dimension particle colors", colorCache.size());
    }

    /**
     * Get the particle color (RGB 0-1 floats) for a base dimension.
     */
    public static Vector3f getParticleColor(ResourceLocation baseDimension) {
        Vector3f cached = colorCache.get(baseDimension);
        return cached != null ? cached : FALLBACK_COLOR;
    }

    /**
     * Get a formatted display name component for a base dimension.
     */
    public static Component getDisplayName(ResourceLocation baseDimension) {
        String friendly = VANILLA_NAMES.get(baseDimension);
        ChatFormatting color = VANILLA_CHAT_COLORS.getOrDefault(baseDimension, ChatFormatting.AQUA);

        if (friendly == null) {
            // Modded dimension: capitalize the path
            friendly = "Parallel " + capitalizePath(baseDimension.getPath());
        }

        return Component.literal("[" + friendly + "]").withStyle(color);
    }

    /**
     * Get a formatted display name for a dimension that is the *destination* when returning
     * through a portal (i.e. the real base dimension, not an exploration copy).
     */
    public static Component getHomeDimensionName(ResourceLocation dimension) {
        String friendly = VANILLA_HOME_NAMES.get(dimension);
        ChatFormatting color = VANILLA_CHAT_COLORS.getOrDefault(dimension, ChatFormatting.AQUA);
        if (friendly == null) {
            friendly = capitalizePath(dimension.getPath());
        }
        return Component.literal("[" + friendly + "]").withStyle(color);
    }

    /**
     * Get display name as plain string (without formatting).
     */
    public static String getDisplayNameString(ResourceLocation baseDimension) {
        String friendly = VANILLA_NAMES.get(baseDimension);
        if (friendly == null) {
            friendly = "Parallel " + capitalizePath(baseDimension.getPath());
        }
        return friendly;
    }

    private static String capitalizePath(String path) {
        // "my_cool_dimension" -> "My Cool Dimension"
        String[] parts = path.replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                if (parts[i].length() > 1) {
                    sb.append(parts[i].substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return sb.toString();
    }

    private static Vector3f parseHex(String hex) {
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) {
            throw new IllegalArgumentException("Hex color must be 6 characters: " + hex);
        }
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return new Vector3f(r / 255f, g / 255f, b / 255f);
    }
}
