package com.github.jikoo.regionerator;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Runnable for flagging chunks as visited.
 *
 * @author Jikoo
 */
public class FlaggingRunnable extends BukkitRunnable {

	private final Regionerator plugin;

	FlaggingRunnable(Regionerator plugin) {
		this.plugin = plugin;
	}

	@Override
	public void run() {
		List<ChunkId> flagged = new ArrayList<>();

		for (Player player : Bukkit.getOnlinePlayers()) {
			// Skip spectators - if you can't touch it, you can't really visit it.
			// Compatibility: check gamemode name instead of direct comparison
			if (player.getGameMode().name().equals("SPECTATOR")
					|| !plugin.config().isEnabled(player.getWorld().getName())) {
				continue;
			}

			flagged.add(new ChunkId(player.getLocation().getChunk()));
		}

		if (!flagged.isEmpty()) {
			plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
				for (ChunkId chunk : flagged) {
					plugin.getFlagger().flagChunksInRadius(chunk.worldName, chunk.chunkX, chunk.chunkZ);
				}
			});
		}

		plugin.attemptDeletionActivation();
	}

	private static class ChunkId {
		private final String worldName;
		private final int chunkX, chunkZ;

		private ChunkId(Chunk chunk) {
			this.worldName = chunk.getWorld().getName();
			this.chunkX = chunk.getX();
			this.chunkZ = chunk.getZ();
		}
	}

}
