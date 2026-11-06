package com.palmergames.bukkit.towny.regen;

import com.palmergames.bukkit.config.ConfigNodes;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.db.TownyDataSource;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.bukkit.towny.test.TownyConfigExtension;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 用受控执行器检验已提交任务、取消回执与实际写入的先后，不依赖时间碰运气。 */
@ExtendWith(TownyConfigExtension.class)
class PlotRevertCancellationTests {

	private MockedStatic<TownyUniverse> singleton;

	private TownyDataSource dataSource;

	private Fixture fixture;

	@BeforeEach
	void prepare() {
		TownySettings.getConfig().set(ConfigNodes.PLUGIN_COREPROTECT_SUPPORT.getRoot(), false);
		TownyUniverse universe = mock(TownyUniverse.class);
		dataSource = mock(TownyDataSource.class);
		when(universe.getDataSource()).thenReturn(dataSource);
		singleton = mockStatic(TownyUniverse.class);
		singleton.when(TownyUniverse::getInstance).thenReturn(universe);
		fixture = new Fixture(13, 10);
	}

	@AfterEach
	void cleanup() {
		TownyRegenAPI.cancelRegeneration(fixture.coordinate);
		singleton.close();
	}

	@Test
	void cancelledBeforeStartDoesNotReadWorldOrWriteBlock() {
		assertTrue(fixture.data.cancelRegeneration());
		assertFalse(fixture.data.restoreNextBlock());
		verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
		verifyNoInteractions(fixture.block);
		assertEquals(List.of("minecraft:stone"), fixture.data.getBlockList());
	}

	@Test
	void capturedTaskSkipsWriteWhenCancellationCompletesFirst() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch captured = new CountDownLatch(1);
		CountDownLatch execute = new CountDownLatch(1);
		try {
			PlotBlockData capturedData = fixture.data;
			TownyRegenAPI.addToActiveRegeneration(capturedData);
			Future<Boolean> task = executor.submit(() -> {
				captured.countDown();
				assertTrue(execute.await(5, TimeUnit.SECONDS));
				return capturedData.restoreNextBlock();
			});
			assertTrue(captured.await(5, TimeUnit.SECONDS));
			assertTrue(TownyRegenAPI.cancelRegeneration(fixture.coordinate));
			execute.countDown();
			assertFalse(task.get(5, TimeUnit.SECONDS));
			verify(fixture.block, never()).setType(any(Material.class), anyBoolean());
			verify(fixture.block, never()).setBlockData(any(BlockData.class));
			assertTrue(capturedData.isRegenerationCancelled());
		} finally {
			execute.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void cancellationWaitsForCurrentWriteAndPreventsEveryLaterWrite() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch write = new CountDownLatch(1);
		CountDownLatch cancelling = new CountDownLatch(1);
		when(fixture.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
			entered.countDown();
			assertTrue(write.await(5, TimeUnit.SECONDS));
			return fixture.block;
		});
		try {
			Future<Boolean> first = executor.submit(fixture.data::restoreNextBlock);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			Future<Boolean> cancellation = executor.submit(() -> {
				cancelling.countDown();
				return fixture.data.cancelRegeneration();
			});
			assertTrue(cancelling.await(5, TimeUnit.SECONDS));
			assertThrows(TimeoutException.class, () -> cancellation.get(100, TimeUnit.MILLISECONDS));
			write.countDown();
			assertTrue(first.get(5, TimeUnit.SECONDS));
			assertTrue(cancellation.get(5, TimeUnit.SECONDS));
			assertFalse(fixture.data.restoreNextBlock());
			verify(fixture.block, times(1)).setType(Material.STONE, false);
			verify(fixture.block, times(1)).setBlockData(fixture.blockData);
		} finally {
			write.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void cancelledTaskDoesNotRunSnapshotDeletionCompletionPath() {
		fixture.data.cancelRegeneration();
		assertFalse(fixture.data.restoreNextBlock());
		TownyRegenAPI.finishPlotBlockData(fixture.data);
		verify(dataSource, never()).deletePlotData(any());
		verify(fixture.coordinate, never()).unloadChunks();
		assertEquals(List.of("minecraft:stone"), fixture.data.getBlockList());
	}

	@Test
	void queueCancellationPersistsOnlyTheQueueAndIsIdempotent() {
		TownyRegenAPI.addToRegenQueueList(fixture.coordinate, false);
		assertTrue(TownyRegenAPI.cancelRegeneration(fixture.coordinate));
		assertFalse(TownyRegenAPI.cancelRegeneration(fixture.coordinate));
		assertFalse(TownyRegenAPI.getRegenQueueList().contains(fixture.coordinate));
		verify(dataSource, times(1)).saveRegenList();
		verify(dataSource, never()).deletePlotData(any());
	}

	@Test
	void removingActiveDataAlsoStopsAnAlreadyCapturedInstance() {
		TownyRegenAPI.addToActiveRegeneration(fixture.data);
		TownyRegenAPI.removeFromActiveRegeneration(fixture.data);
		assertTrue(fixture.data.isRegenerationCancelled());
		assertFalse(fixture.data.restoreNextBlock());
		assertFalse(TownyRegenAPI.hasActiveRegeneration(fixture.coordinate));
		verify(fixture.block, never()).setType(any(Material.class), anyBoolean());
	}

	@Test
	void cancellingOnePlotDoesNotCancelOtherPlots() {
		Fixture other = new Fixture(14, 10);
		try {
			TownyRegenAPI.addToRegenQueueList(fixture.coordinate, false);
			TownyRegenAPI.addToRegenQueueList(other.coordinate, false);
			TownyRegenAPI.addToActiveRegeneration(other.data);
			assertTrue(TownyRegenAPI.cancelRegeneration(fixture.coordinate));
			assertTrue(TownyRegenAPI.getRegenQueueList().contains(other.coordinate));
			assertTrue(TownyRegenAPI.hasActiveRegeneration(other.coordinate));
			assertFalse(other.data.isRegenerationCancelled());
		} finally {
			TownyRegenAPI.cancelRegeneration(other.coordinate);
		}
	}

	private static final class Fixture {

		private final WorldCoord coordinate = mock(WorldCoord.class);

		private final World world = mock(World.class);

		private final Block block = mock(Block.class);

		private final BlockData blockData = mock(BlockData.class);

		private final PlotBlockData data;

		private Fixture(int x, int z) {
			TownBlock townBlock = mock(TownBlock.class);
			TownyWorld townyWorld = mock(TownyWorld.class);
			when(townBlock.getX()).thenReturn(x);
			when(townBlock.getZ()).thenReturn(z);
			when(townBlock.getWorld()).thenReturn(townyWorld);
			when(townBlock.getWorldCoord()).thenReturn(coordinate);
			when(townyWorld.getName()).thenReturn("cancel-test");
			when(townyWorld.isUnclaimedBlockAllowedToRevert(Material.STONE)).thenReturn(true);
			when(coordinate.getWorldName()).thenReturn("cancel-test");
			when(coordinate.getX()).thenReturn(x);
			when(coordinate.getZ()).thenReturn(z);
			when(coordinate.getBukkitWorld()).thenReturn(world);
			when(world.getMaxHeight()).thenReturn(2);
			when(world.getMinHeight()).thenReturn(0);
			when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
			when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
			when(block.getType()).thenReturn(Material.AIR);
			when(blockData.getMaterial()).thenReturn(Material.STONE);
			when(Bukkit.getServer().createBlockData("minecraft:stone")).thenReturn(blockData);
			data = new PlotBlockData(townBlock);
			data.setSize(1);
			data.setBlockList(List.of("minecraft:stone"));
		}
	}
}
