/*
 * Copyright (c) 2015-2021 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator.util.yaml;

import com.github.jikoo.regionerator.DebugLevel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

public class Config extends ConfigYamlData {

	/** Constant representing the default flag timestamp. */
	public static final long FLAG_DEFAULT = -1;
	/** Constant representing a flag that will never expire. */
	public static final long FLAG_ETERNAL = Long.MAX_VALUE - 1;
	/** Constant representing a failure to load data. */
	public static final long FLAG_OH_NO = Long.MAX_VALUE - 2;

	private final Object lock = new Object();
	private DebugLevel debugLevel;
	private Map<String, Long> worlds;
	private final AtomicLong ticksPerFlag = new AtomicLong(),
			millisBetweenCycles = new AtomicLong(), deletionRecovery = new AtomicLong();
	private final AtomicInteger flaggingRadius = new AtomicInteger(), deletionChunkCount = new AtomicInteger();
	private final AtomicBoolean rememberCycleDelay = new AtomicBoolean(), deleteFreshChunks = new AtomicBoolean();
	private long cacheExpirationFrequency;
	private long cacheRetention;
	private int cacheBatchMax;
	private long cacheBatchDelay;
	private int cacheMaxSize;

	public Config(Plugin plugin) {
		super(plugin);
		reload();
	}

	@Override
	public void reload() {
		super.reload();

		ConfigUpdater.doUpdates(this);

		ConfigurationSection worldsSection = raw().getConfigurationSection("worlds");
		Map<String, Long> worldFlagDurations = new HashMap<>();
		if (worldsSection != null) {

			List<String> activeWorlds = Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());

			for (String key : worldsSection.getKeys(false)) {
				String validCase;
				// Attempt to correct case for quick getting later.
				if (activeWorlds.contains(key)) {
					validCase = key;
				} else {
					// World may still be active, attempt to correct case.
					Optional<String> match = activeWorlds.stream().filter(key::equalsIgnoreCase).findFirst();
					validCase = match.orElse(key);
				}

				ConfigurationSection worldSection = worldsSection.getConfigurationSection(key);

				if (worldSection == null) {
					continue;
				}

				int days = worldSection.getInt("days-till-flag-expires", worldsSection.getInt("default.days-till-flag-expires", -1));
				worldFlagDurations.put(validCase, days < 0 ? -1 : TimeUnit.MILLISECONDS.convert(days, TimeUnit.DAYS));
			}
		}

		synchronized (lock) {
			// Immutable, this should not be changed during run.
			this.worlds = ImmutableMap.copyOf(worldFlagDurations);

			debugLevel = DebugLevel.of(getString("debug-level"));
		}

		deleteFreshChunks.set(!getBoolean("flagging.flag-generated-chunks-until-visited"));
		flaggingRadius.set(Math.max(0, getInt("flagging.chunk-flag-radius")));

		int secondsPerFlag = getInt("flagging.seconds-per-flag");
		if (secondsPerFlag < 1) {
			ticksPerFlag.set(10);
		} else {
			ticksPerFlag.set(20L * secondsPerFlag);
		}

		deletionRecovery.set(Math.max(0, getLong("deletion.recovery-time")));
		deletionChunkCount.set(Math.max(1, getInt("deletion.expensive-checks-between-recovery")));
		millisBetweenCycles.set(TimeUnit.HOURS.toMillis(Math.max(0, getInt("deletion.hours-between-cycles"))));
		rememberCycleDelay.set(getBoolean("deletion.remember-next-cycle-time"));

		cacheExpirationFrequency = TimeUnit.MILLISECONDS.convert(20, TimeUnit.SECONDS);
		cacheRetention = TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES);
		cacheBatchMax = 1024;
		cacheBatchDelay = 500;
		cacheMaxSize = 80000;

	}

	public DebugLevel getDebugLevel() {
		synchronized (lock) {
			return debugLevel;
		}
	}

	public int getDeletionChunkCount() {
		return deletionChunkCount.get();
	}

	public long getDeletionRecoveryMillis() {
		return deletionRecovery.get();
	}

	@Deprecated
	public long getMillisBetweenCycles() {
		return millisBetweenCycles.get();
	}

	public long getCycleDelayMillis() {
		return millisBetweenCycles.get();
	}

	public boolean isRememberCycleDelay() {
		return rememberCycleDelay.get();
	}

	@Deprecated
	public boolean isDeleteFreshChunks() {
		return deleteFreshChunks.get() && getFlagDuration() > 0;
	}

	public boolean isDeleteFreshChunks(World world) {
		return deleteFreshChunks.get() && getFlagDuration(world) > 0;
	}

	@Deprecated
	public long getFlagDuration() {
		synchronized (lock) {
			return worlds.getOrDefault("default", -1L);
		}
	}

	public long getFlagDuration(World world) {
		return getFlagDuration(world.getName());
	}

	public long getFlagDuration(String worldName) {
		synchronized (lock) {
			return worlds.getOrDefault(worldName, worlds.getOrDefault("default", -1L));
		}
	}

	@Deprecated
	public long getFlagGenerated() {
		return isDeleteFreshChunks() ? getFlagVisit() : Long.MAX_VALUE;
	}

	public long getFlagGenerated(World world) {
		return isDeleteFreshChunks(world) ? getFlagVisit(world) : Long.MAX_VALUE;
	}

	@Deprecated
	public long getFlagVisit() {
		return System.currentTimeMillis() + getFlagDuration();
	}

	public long getFlagVisit(World world) {
		return getFlagVisit(world.getName());
	}

	public long getFlagVisit(String worldName) {
		return System.currentTimeMillis() + getFlagDuration(worldName);
	}

	@Deprecated
	public static long getFlagEternal() {
		return FLAG_ETERNAL;
	}

	@Deprecated
	public static long getFlagDefault() {
		return FLAG_DEFAULT;
	}

	public long getFlaggingInterval() {
		return ticksPerFlag.get();
	}

	public int getFlaggingRadius() {
		return flaggingRadius.get();
	}

	public boolean isEnabled(String worldName) {
		return getFlagDuration(worldName) >= 0;
	}

	public Collection<String> enabledWorlds() {
		return Collections.unmodifiableSet(plugin.getServer().getWorlds().stream().map(World::getName).filter(this::isEnabled).collect(Collectors.toSet()));
	}

	@Deprecated
	public Collection<String> getWorlds() {
		return ImmutableList.copyOf(enabledWorlds());
	}

	public long getCacheExpirationFrequency() {
		return cacheExpirationFrequency;
	}

	public long getCacheRetention() {
		return cacheRetention;
	}

	public int getCacheBatchMax() {
		return cacheBatchMax;
	}

	public long getCacheBatchDelay() {
		return cacheBatchDelay;
	}

	public int getCacheMaxSize() {
		return cacheMaxSize;
	}

}
