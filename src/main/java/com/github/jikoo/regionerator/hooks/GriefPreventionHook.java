package com.github.jikoo.regionerator.hooks;

import org.bukkit.World;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;

/**
 * PluginHook for <a href=http://dev.bukkit.org/bukkit-plugins/grief-prevention/>GriefPrevention</a>.
 *
 * @author Jikoo
 */
public class GriefPreventionHook extends PluginHook {

	public GriefPreventionHook() {
		super("GriefPrevention");
	}

	@Override
	public boolean isChunkProtected(World world, int chunkX, int chunkZ) {
		for(int i=-4; i<5; i++){
			for(int j=-4; j<5; j++){
				for (Claim claim : GriefPrevention.instance.dataStore.getClaims(chunkX+i, chunkZ+j)) {
					if (claim.getGreaterBoundaryCorner().getWorld().equals(world)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	@Override
	public boolean isAsyncCapable() {
		return true;
	}

}
