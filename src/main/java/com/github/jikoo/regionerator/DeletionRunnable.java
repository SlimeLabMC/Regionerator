package com.github.jikoo.regionerator;

import com.github.jikoo.regionerator.world.ChunkInfo;
import com.github.jikoo.regionerator.world.RegionInfo;
import com.github.jikoo.regionerator.world.WorldInfo;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.World;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Runnable for checking and deleting chunks and regions.
 *
 * @author Jikoo
 */
public class DeletionRunnable extends BukkitRunnable {

	private static final String STATS_FORMAT = "%s: checked %s, deleted %s regions & %s chunks";

	private final Regionerator plugin;
	private final Phaser phaser;
	private final WorldInfo world;
	private final AtomicLong nextRun = new AtomicLong(Long.MAX_VALUE);
	private final AtomicInteger regionCount = new AtomicInteger(), chunkCount = new AtomicInteger(),
			regionsDeleted = new AtomicInteger(), chunksDeleted = new AtomicInteger();

	DeletionRunnable(Regionerator plugin, World world) {
		this.plugin = plugin;
		this.phaser = new Phaser(1);
		this.world = plugin.getWorldManager().getWorld(world);
	}

	@Override
	public void run() {
		world.getRegions().forEach(this::handleRegion);
		plugin.getLogger().info("Regeneration cycle complete for " + getRunStats());
		nextRun.set(System.currentTimeMillis() + plugin.config().getMillisBetweenCycles());
		if (plugin.config().isRememberCycleDelay()) {
			try {
				plugin.getServer().getScheduler().runTask(plugin, () -> plugin.finishCycle(this));
			} catch (IllegalPluginAccessException e) {
				// Plugin disabling, odds are on that we were mid-cycle. Don't update finish time.
			}
		}
		phaser.arriveAndDeregister();
	}

	private void handleRegion(RegionInfo region) {
		if (isCancelled()) {
			return;
		}

		phaser.arriveAndAwaitAdvance();

		regionCount.incrementAndGet();
		plugin.debug(DebugLevel.HIGH, () -> String.format("Checking %s:%s (%s)",
				world.getWorld().getName(), region.getIdentifier(), regionCount.get()));

		try {
			region.read();
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING, "Unable to read region!", e);
			return;
		}

		// Collect potentially eligible chunks
		List<ChunkInfo> chunks = region.getChunks().filter(this::isDeleteEligible).collect(Collectors.toList());

		if (chunks.size() != 1024) {
			// If entire region is not being deleted, filter out chunks that are already orphaned or freshly generated
			chunks.removeIf(chunk -> {
				if (isCancelled()) {
					return true;
				}
				VisitStatus visitStatus = chunk.getVisitStatus();
				return visitStatus == VisitStatus.ORPHANED || !plugin.config().isDeleteFreshChunks() && visitStatus == VisitStatus.GENERATED;
			});

			// Orphan chunks - N.B. this is called here and not outside of the block because AnvilRegion deletes regions on AnvilRegion#write
			chunks.forEach(ChunkInfo::setOrphaned);
		}

		try {
			region.write();
			chunks.forEach(chunk -> plugin.getFlagger().unflagChunk(chunk.getWorld().getName(), chunk.getChunkX(), chunk.getChunkZ()));
			if (chunks.size() == 1024) {
				regionsDeleted.incrementAndGet();
			} else {
				chunksDeleted.addAndGet(chunks.size());
			}
		} catch (IOException e) {
			plugin.debug(() -> String.format(
					"Caught an IOException attempting to populate chunk data: %s", e.getMessage()), e);
		}

		if (regionCount.get() % 20 == 0) {
			plugin.debug(DebugLevel.LOW, this::getRunStats);
		}

		try {
			Thread.sleep(plugin.config().getDeletionRecoveryMillis());
			// Reset chunk count after sleep
			chunkCount.set(0);
		} catch (InterruptedException ignored) {}
	}

	private boolean isDeleteEligible(ChunkInfo chunkInfo) {
		if (isCancelled()) {
			// If task is cancelled, report all chunks ineligible for deletion
			plugin.debug(DebugLevel.HIGH, () -> "Deletion task is cancelled, chunks are ineligible for delete.");
			return false;
		}

		if (chunkInfo.isOrphaned()) {
			// Chunk already deleted
			plugin.debug(DebugLevel.HIGH, () -> String.format("%s: %s, %s is already orphaned.",
					chunkInfo.getRegionInfo().getIdentifier(), chunkInfo.getChunkX(), chunkInfo.getChunkX()));
			return true;
		}

		long now = System.currentTimeMillis();
		long lastVisit = chunkInfo.getLastVisit();
		boolean isFresh = !plugin.config().isDeleteFreshChunks() && lastVisit == plugin.config().getFlagGenerated();

		if (!isFresh && now <= lastVisit) {
			// Chunk is visited
			plugin.debug(DebugLevel.HIGH, () -> String.format("%s: %s, %s is visited until %s",
					chunkInfo.getRegionInfo().getIdentifier(), chunkInfo.getChunkX(), chunkInfo.getChunkZ(), lastVisit));
			return false;
		}

		if (!isFresh && now - plugin.config().getFlagDuration() <= chunkInfo.getLastModified()) {
			plugin.debug(DebugLevel.HIGH, () -> String.format("%s: %s, %s is modified until %s",
					chunkInfo.getRegionInfo().getIdentifier(), chunkInfo.getChunkX(), chunkInfo.getChunkZ(), chunkInfo.getLastModified()));
			return false;
		}

		// Only count heavy checks towards total chunk count for recovery
		if (chunkCount.incrementAndGet() % plugin.config().getDeletionChunkCount() == 0) {
			try {
				Thread.sleep(plugin.config().getDeletionRecoveryMillis());
			} catch (InterruptedException ignored) {}
		}

		if (plugin.debug(DebugLevel.HIGH)) {
			plugin.getDebugListener().monitorChunk(chunkInfo.getChunkX(), chunkInfo.getChunkZ());
		}

		VisitStatus visitStatus;
		try {
			// Calculate VisitStatus including protection hooks.
			visitStatus = chunkInfo.getVisitStatus();
		} catch (RuntimeException e) {
			// Interruption is not due to plugin shutdown, log.
			if (!this.isCancelled() && plugin.isEnabled()) {
				plugin.debug(() -> String.format("Caught an exception getting VisitStatus: %s", e.getMessage()), e);
			}

			// If an exception occurred, do not delete chunk.
			visitStatus = VisitStatus.UNKNOWN;
		}

		if (plugin.debug(DebugLevel.HIGH)) {
			plugin.getDebugListener().ignoreChunk(chunkInfo.getChunkX(), chunkInfo.getChunkZ());
		}

		return visitStatus.ordinal() < VisitStatus.VISITED.ordinal();

	}

	public String getRunStats() {
		return String.format(STATS_FORMAT, world.getWorld().getName(), regionCount.get(), regionsDeleted, chunksDeleted);
	}

	public String getWorld() {
		return world.getWorld().getName();
	}

	public long getNextRun() {
		return nextRun.get();
	}

	Phaser getPhaser() {
		return phaser;
	}

}
