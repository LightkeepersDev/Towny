package com.palmergames.bukkit.towny.regen;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.event.actions.TownyExplodingBlocksEvent;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.bukkit.towny.regen.block.BlockLocation;
import com.palmergames.bukkit.towny.tasks.ProtectionRegenTask;
import com.palmergames.bukkit.util.ItemLists;

import com.palmergames.util.JavaUtil;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.type.Door;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * @author ElgarL
 * 
 */
public class TownyRegenAPI {

	// A list of worldCoords which are to be regenerated.
	private static final Set<WorldCoord> regenWorldCoordList = ConcurrentHashMap.newKeySet();
	
	// table containing snapshot data of active reversions.
	private static final Map<String, PlotBlockData> plotChunks = new ConcurrentHashMap<>();

	private static final Object regenQueueLock = new Object();

	private static final Map<WorldCoord, Object> regenerationRequests = new ConcurrentHashMap<>();
	
	// A holder for each protection regen task
	private static final Map<BlockLocation, ProtectionRegenTask> protectionRegenTasks = new ConcurrentHashMap<>();
	
	// List of protection blocks placed to prevent blockPhysics.
	private static final Set<Block> protectionPlaceholders = new HashSet<>();
	
	// https://jd.papermc.io/paper/1.20/org/bukkit/Chunk.html#getChunkSnapshot(boolean,boolean,boolean,boolean)
	private static final MethodHandle GET_CHUNK_SNAPSHOT = JavaUtil.getMethodHandle(Chunk.class, "getChunkSnapshot", boolean.class, boolean.class, boolean.class, boolean.class);

	/**
	 * Removes a TownyWorld from the various Revert-on-Unclaim feature Lists/Table.
	 * @param world TownyWorld to remove.
	 */
	public static void turnOffRevertOnUnclaimForWorld(TownyWorld world) {
		removeRegenQueueListOfWorld(world); // Remove any queued regenerations.
		removePlotChunksForWorld(world); // Stop any active reverts being done.
	}
	
	/**
	 * Called when a PlotBlockData's revert-on-unclaim has been finished. 
	 * @param plotChunk PlotBlockData which finished up.
	 */
	public static void finishPlotBlockData(PlotBlockData plotChunk) {
		synchronized (plotChunk) {
			if (plotChunk.isRegenerationCancelled())
				return;
		TownyMessaging.sendDebugMsg("Revert on unclaim complete for " + plotChunk.getWorldName() + " " + plotChunk.getX() +"," + plotChunk.getZ());
		removeFromRegenQueueList(plotChunk.getWorldCoord()); // Remove the WorldCoord from the queue.
		plotChunks.remove(getPlotKey(plotChunk), plotChunk);
		deletePlotChunkSnapshot(plotChunk); // Remove from the database.
		plotChunk.getWorldCoord().unloadChunks(); // Remove the PluginChunkTickets keeping the plotChunk loaded.
		}
	}

	/*
	 * Regeneration Queue.
	 */

	/**
	 * @return the list of WorldCoords which are waiting to be regenerated.
	 */
	public static Collection<WorldCoord> getRegenQueueList() {
		return regenWorldCoordList;
	}

	/**
	 * @return whether the regenQueue is empty.
	 */
	public static boolean regenQueueHasAvailable() {
		return !regenWorldCoordList.isEmpty();
	}

	/**
	 * Used when a world has the revert on unclaim feature turned off, to purge
	 * the list of queued regenerations of the world.
	 * @param world TownyWorld to remove from the queue.
	 */
	private static void removeRegenQueueListOfWorld(@NotNull TownyWorld world) {
		if (regenWorldCoordList.removeIf(wc -> world.equals(wc.getTownyWorld())))
			TownyUniverse.getInstance().getDataSource().saveRegenList();
	}

	/**
	 * Removes a WorldCoord from the queue of the revert on unclaim feature.
	 * @param wc WorldCoord to add to the queue.
	 */
	public static void removeFromRegenQueueList(WorldCoord wc) {
		boolean removed;
		synchronized (regenQueueLock) {
			removed = regenWorldCoordList.remove(wc);
			regenerationRequests.remove(wc);
		}
		if (removed)
			TownyUniverse.getInstance().getDataSource().saveRegenList();
	}

	/**
	 * 取消指定地块的排队和活动还原，并使已提交任务持有的实例停止后续写入。
	 * 返回前等待该实例当前写入结束；不持有队列锁等待实例，避免与完成路径死锁。
	 * 从排队列表移除后保存列表；地块快照和已经写入的方块保留。
	 *
	 * @param wc 需要取消还原的真实地块
	 * @return 是否移除了排队或活动还原
	 */
	public static boolean cancelRegeneration(WorldCoord wc) {
		PlotBlockData active;
		boolean removed;
		synchronized (regenQueueLock) {
			removed = regenWorldCoordList.remove(wc);
			regenerationRequests.remove(wc);
			active = plotChunks.get(getPlotKey(wc));
		}
		if (active != null) {
			active.cancelRegeneration();
			plotChunks.remove(getPlotKey(active), active);
			active.getWorldCoord().unloadChunks();
		}
		if (removed)
			TownyUniverse.getInstance().getDataSource().saveRegenList();
		return removed || active != null;
	}

	/**
	 * Adds a WorldCoord to the queue of the revert on unclaim feature.
	 * @param wc WorldCoord to remove from thequeue.
	 * @param save true to save the regenlist.
	 */
	public static void addToRegenQueueList(WorldCoord wc, boolean save) {
		boolean added;
		synchronized (regenQueueLock) {
			added = regenWorldCoordList.add(wc);
			if (added)
				regenerationRequests.put(wc, new Object());
		}
		if (added && save)
			TownyUniverse.getInstance().getDataSource().saveRegenList();
	}

	public static void getWorldCoordFromQueueForRegeneration() {
		for (WorldCoord wc : TownyRegenAPI.getRegenQueueList()) {
			// We have enough plot chunks regenerating, break out of the loop.
			if (getPlotChunks().size() >= 20)
				break;
			// We have already got this worldcoord regenerating.
			if (hasActiveRegeneration(wc))
				continue;
			
			Object request;
			synchronized (regenQueueLock) {
				if (!regenWorldCoordList.contains(wc))
					continue;
				request = regenerationRequests.computeIfAbsent(wc, key -> new Object());
			}
			// This worldCoord isn't actively regenerating, start the regeneration.
			PlotBlockData plotData = getPlotChunkSnapshot(new TownBlock(wc.getX(), wc.getZ(), wc.getTownyWorld()));
			if (plotData != null) {
				// 读取快照期间若已取消，旧请求不能在取消回执之后重新激活。
				synchronized (regenQueueLock) {
					if (regenerationRequests.get(wc) != request || !regenWorldCoordList.contains(wc)
							|| plotChunks.putIfAbsent(getPlotKey(plotData), plotData) != null)
						continue;
				}
				// Load the chunks.
				synchronized (plotData) {
					if (plotData.isRegenerationCancelled())
						continue;
					plotData.getWorldCoord().loadChunks();
				}
				TownyMessaging.sendDebugMsg("Revert on unclaim beginning for " + plotData.getWorldName() + " " + plotData.getX() +"," + plotData.getZ());
			} else {
				removeFromRegenQueueList(wc);
			}
		}
	}
	
	/*
	 * Active Revert-On-Unclaims.
	 */

	/**
	 * @return the plotChunks which are being processed
	 */
	public static Map<String, PlotBlockData> getPlotChunks() {

		return plotChunks;
	}

	public static Collection<PlotBlockData> getActivePlotBlockDatas() {
		return plotChunks.values();
	}

	/**
	 * @return true if there are any chunks being processed.
	 */
	public static boolean hasActiveRegenerations() {

		return !plotChunks.isEmpty();
	}

	/**
	 * @param wc WorldCoord to check for.
	 * @return true if this WorldCoord is actively being processed.
	 */
	public static boolean hasActiveRegeneration(WorldCoord wc) {
		return plotChunks.containsKey(getPlotKey(wc));
	}

	/**
	 * Removes all plotchunks currently in regeneration list for one world.
	 * 
	 * @param world - TownyWorld to have regeneration stop in.
	 */
	private static void removePlotChunksForWorld(TownyWorld world) {
		plotChunks.values().removeIf(data -> data.getWorldName().equals(world.getName()));
	}

	/**
	 * Removes a Plot Chunk from the regeneration map
	 * 
	 * @param plotChunk Chunk to remove (PlotBlockData)
	 */
	public static void removeFromActiveRegeneration(PlotBlockData plotChunk) {
		plotChunk.cancelRegeneration();
		plotChunks.remove(getPlotKey(plotChunk), plotChunk);
	}
	
	/**
	 * Adds a Plot Chunk to the regeneration map
	 * 
	 * @param plotChunk Chunk to add (PlotBlockData)
	 */
	public static void addToActiveRegeneration(PlotBlockData plotChunk) {
		plotChunks.putIfAbsent(getPlotKey(plotChunk), plotChunk);
	}

	/*
	 * PlotChunkSnapShot Methods. Creates PlotBlockDatas.
	 */

	/**
	 * Saves a Plot Chunk snapshot to the datasource
	 * 
	 * @param plotChunk - Chunk to take Snapshot (PlotBlockData)
	 */
	public static void addPlotChunkSnapshot(PlotBlockData plotChunk) {
		TownyUniverse townyUniverse = TownyUniverse.getInstance();
		
		final TownBlock townBlock = plotChunk.getWorldCoord().getTownBlockOrNull();
		if (townBlock == null || !townyUniverse.getDataSource().hasPlotData(townBlock)) {
			townyUniverse.getDataSource().savePlotData(plotChunk);
		}
	}

	/**
	 * Deletes a Plot Chunk snapshot from the datasource
	 * 
	 * @param plotChunk - Chunk to delete snapshot (PlotBlockData)
	 */
	private static void deletePlotChunkSnapshot(PlotBlockData plotChunk) {
		TownyUniverse.getInstance().getDataSource().deletePlotData(plotChunk);
	}

	/**
	 * Loads a Plot Chunk snapshot from the data source
	 * 
	 * @param townBlock - TownBlock to get
	 * @return loads the PlotData for the given townBlock or returns null.   
	 */
	public static PlotBlockData getPlotChunkSnapshot(TownBlock townBlock) {
		return TownyUniverse.getInstance().getDataSource().loadPlotData(townBlock);
	}

	/**
	 * Gets a Plot Chunk from the regeneration map
	 * 
	 * @param townBlock TownBlock to get
	 * @return PlotChunks or null   
	 */
	@Nullable
	public static PlotBlockData getPlotChunk(TownBlock townBlock) {
		return plotChunks.get(getPlotKey(townBlock));
	}

	private static String getPlotKey(PlotBlockData plotChunk) {

		return "[" + plotChunk.getWorldName() + "|" + plotChunk.getX() + "|" + plotChunk.getZ() + "]";
	}

	public static String getPlotKey(TownBlock townBlock) {

		return "[" + townBlock.getWorld().getName() + "|" + townBlock.getX() + "|" + townBlock.getZ() + "]";
	}
	
	public static String getPlotKey(WorldCoord wc) {
		return "[" + wc.getWorldName() + "|" + wc.getX() + "|" + wc.getZ() + "]";
	}

	/*
	 * Protection Regen follows
	 */
	
	/**
	 * Called from various explosion listeners.
	 * 
	 * @param block - {@link Block} which is being exploded.
	 * @param count - int for setting the delay to do one block at a time.
	 * @param world - {@link TownyWorld} for where the regen is being triggered.
	 * @param event - The Bukkit Event causing this explosion.
	 * 
	 * @return true if the protectiontask was begun successfully. 
	 */
	public static boolean beginProtectionRegenTask(Block block, int count, TownyWorld world, Event event) {
		// Don't interfere with an existing regen task
		if (!hasProtectionRegenTask(new BlockLocation(block.getLocation()))) {
			// Piston extensions which are broken by explosions ahead of the base block
			// cause baseblocks to drop as items and no base block to be regenerated.
			if (block.getType() == Material.PISTON_HEAD) {
				org.bukkit.block.data.type.PistonHead blockData = (org.bukkit.block.data.type.PistonHead) block.getBlockData();
				block = block.getRelative(blockData.getFacing().getOppositeFace());
			}
			ProtectionRegenTask task = new ProtectionRegenTask(Towny.getPlugin(), block);
			task.setTask(Towny.getPlugin().getScheduler().runLater(block.getLocation(), task, (world.getPlotManagementWildRevertDelay() + count) * 20));
			addProtectionRegenTask(task);

			// If this was a TownyExplodingBlocksEvent we want to get the bukkit event from it first.
			if (event instanceof TownyExplodingBlocksEvent)
				event = ((TownyExplodingBlocksEvent) event).getBukkitExplodeEvent();
			
			// Remove the drops from the explosion.
			if (event instanceof EntityExplodeEvent) 
				((EntityExplodeEvent) event).setYield(0);
			else if (event instanceof BlockExplodeEvent)
				((BlockExplodeEvent) event).setYield(0);

			// Set extra-special blocks to air so we're not duping items.
			handlePeskyBlocks(block);

			return true;
		}
		return false;
	}

	private static void handlePeskyBlocks(Block block) {
		if (ItemLists.EXPLODABLE_ATTACHABLES.contains(block.getType())) {
			if (!(block.getBlockData() instanceof Door door)) 
				// Not a Door, set the pesky block to AIR so an item doesn't drop.
				block.setType(Material.AIR);

			// Doors are double-tall and especially pesky. We parse over exploded blocks
			// from bottom to top, so if we don't handle doors backwards we regenerate doors
			// with only a bottom half.
			else if (door.getHalf().equals(Half.TOP)) {
				// Remove the bottom along with the top here.
				block.getRelative(BlockFace.DOWN).setType(Material.AIR);
				block.setType(Material.AIR);
			}
		}
	}

	/**
	 * Does a task for this block already exist?
	 * 
	 * @param blockLocation - Location of the block
	 * @return true if a task exists
	 */
	public static boolean hasProtectionRegenTask(BlockLocation blockLocation) {

		return protectionRegenTasks.containsKey(blockLocation);

	}

	/**
	 * Fetch the relevant regen task for this block
	 * 
	 * @param blockLocation - Location of the block.
	 * @return the stored task, or null if there is none.
	 */
	public static ProtectionRegenTask GetProtectionRegenTask(BlockLocation blockLocation) {
		return protectionRegenTasks.get(blockLocation);
	}

	/**
	 * Add this task to the protection regen queue.
	 * 
	 * @param task - ProtectionRegenTask to add to queue
	 */
	public static void addProtectionRegenTask(ProtectionRegenTask task) {

		protectionRegenTasks.put(task.getBlockLocation(), task);
	}

	/**
	 * Remove this task form the protection regen queue
	 * 
	 * @param task - ProtectionRegenTask to remove from queue
	 */
	public static void removeProtectionRegenTask(ProtectionRegenTask task) {

		protectionRegenTasks.remove(task.getBlockLocation());
		if (protectionRegenTasks.isEmpty())
			protectionPlaceholders.clear();
	}

	/**
	 * Cancel all regenerating tasks and clear all queues.
	 */
	public static void cancelProtectionRegenTasks() {
		boolean replaceProtections = true;

		try {
			if (Class.forName("org.spigotmc.WatchdogThread").isInstance(Thread.currentThread())) {
				Towny.getPlugin().getLogger().severe("Detected a watchdog crash, ongoing protection revert tasks will not be finished.");
				replaceProtections = false;
			}
		} catch (Throwable ignored) {}

		for (ProtectionRegenTask task : protectionRegenTasks.values()) {
			task.getTask().cancel();

			if (replaceProtections)
				task.replaceProtections();
		}
		protectionRegenTasks.clear();
		protectionPlaceholders.clear();
	}

	/**
	 * Is this a placholder block?
	 * 
	 * @param block - Block identifier
	 * @return true if it is a placeholder
	 */
	public static boolean isPlaceholder(Block block) {

		return protectionPlaceholders.contains(block);
	}

	/**
	 * Add this block as a placeholder (will be replaced when it's regeneration task occurs)
	 * 
	 * @param block - Block identifier
	 */
	public static void addPlaceholder(Block block) {

		protectionPlaceholders.add(block);
	}

	/**
	 * Remove this block from being tracked as a placeholder.
	 * 
	 * @param block - Block identifier
	 */
	public static void removePlaceholder(Block block) {

		protectionPlaceholders.remove(block);
	}

	/**
	 * Creates a new snapshot and handles saving it
	 * @param townBlock The townblock to take a snapshot of
	 */
	public static void handleNewSnapshot(final @NotNull TownBlock townBlock) {
		createPlotSnapshot(townBlock).thenAcceptAsync(data -> {
			if (data.getBlockList().isEmpty())
				return;

			addPlotChunkSnapshot(data);
		}).exceptionally(e -> {
			if (e.getCause() != null)
				e = e.getCause();

			Towny.getPlugin().getLogger().log(Level.WARNING, "An exception occurred while creating a plot snapshot for " + townBlock.getWorldCoord().toString(), e);
			return null;
		});
	}

	public static CompletableFuture<PlotBlockData> createPlotSnapshot(final @NotNull TownBlock townBlock) {
		final List<ChunkSnapshot> snapshots = new ArrayList<>();
		final Collection<CompletableFuture<Chunk>> futures = townBlock.getWorldCoord().getChunks();
		
		futures.forEach(future -> future.thenAccept(chunk -> {
			try {
				if (GET_CHUNK_SNAPSHOT != null) {
					snapshots.add((ChunkSnapshot) GET_CHUNK_SNAPSHOT.invoke(chunk, false, false, false, false));
				} else {
					snapshots.add(chunk.getChunkSnapshot(false, false, false));
				}
			} catch (Throwable throwable) {
				snapshots.add(chunk.getChunkSnapshot(false, false, false));
			}
		}));
		
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{})).thenApplyAsync(v -> {
			final PlotBlockData data = new PlotBlockData(townBlock);
			data.initialize(snapshots);
			
			return data;
		});
	}
}
