package me.general_breddok.blockdisplaycreator.placeholder.universal;

import me.general_breddok.blockdisplaycreator.custom.block.CustomBlock;
import org.bukkit.Location;

public record CustomBlockPlaceholder(CustomBlock context) implements UniversalPlaceholder<CustomBlock> {
    @Override
    public String apply(String template) {
        if (this.context == null) {
            return template;
        }

        if (template.isEmpty()) {
            return template;
        }

        Location location = context.getLocation();

        // {x} {y} {z} {world}: the block the custom block was placed in (whole numbers), not where a gizmo moved its model
        String result = template
                .replace("{x}", String.valueOf(location.getBlockX()))
                .replace("{y}", String.valueOf(location.getBlockY()))
                .replace("{z}", String.valueOf(location.getBlockZ()));
        if (location.getWorld() != null) {
            result = result.replace("{world}", location.getWorld().getName());
        }

        return result
                .replace("%customblock_x%", String.valueOf(location.getX()))
                .replace("%customblock_y%", String.valueOf(location.getY()))
                .replace("%customblock_z%", String.valueOf(location.getZ()))
                .replace("%customblock_name%", context.getName());
    }
}
