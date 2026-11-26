package me.general_breddok.blockdisplaycreator.file.config.value;

import me.general_breddok.blockdisplaycreator.data.yaml.YamlData;

public final class LongConfigValue implements ConstantConfigValue<Long> {

    public static long MAX_ENTITIES_PER_CHUNK;
    public static long ANIMATION_PARTICLES_MAX = 64;

    @Override
    public void initialize(YamlData<Long> configuration) {
        MAX_ENTITIES_PER_CHUNK = configuration.get("max-entities-per-chunk", -1L);
        ANIMATION_PARTICLES_MAX = configuration.get("animation-particles-max", 64L);
    }
}
