package me.general_breddok.blockdisplaycreator.world.guard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import lombok.experimental.UtilityClass;
import me.general_breddok.blockdisplaycreator.permission.DefaultPermissions;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;


@UtilityClass
public class WGRegionAccessChecker {

    /**
     * Whether a player may act on a custom block at a location, as far as WorldGuard regions are concerned.
     * <p>
     * Outside any region there is no restriction. Members of every region there and players with a bypass
     * permission may always act. Anyone else gets the value of the flag in those regions, the way WorldGuard
     * reads it (priorities, parents, region groups, the global region), and the default of the flag when no
     * region sets it: interact-cb is allowed by default, place-cb and break-cb are not.
     */
    public boolean checkRegionAccess(@NotNull Location location, StateFlag flag, Player player) {
        if (location.getWorld() == null || player == null)
            return true;

        if (flag == null)
            return true;

        ApplicableRegionSet regions = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .createQuery()
                .getApplicableRegions(BukkitAdapter.adapt(location));

        if (regions.size() == 0)
            return true;

        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);

        if (regions.isMemberOfAll(localPlayer))
            return true;

        if (player.hasPermission(DefaultPermissions.BDC.WG_BYPASS) || player.hasPermission(DefaultPermissions.WorldGuard.WORLD_REGION_BYPASS + location.getWorld().getName()))
            return true;

        StateFlag.State state = regions.queryState(localPlayer, flag);

        if (state == null)
            state = flag.getDefault();

        return state == StateFlag.State.ALLOW;
    }
}
