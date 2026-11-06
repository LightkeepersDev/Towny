package com.palmergames.bukkit.towny.event.town;

import com.palmergames.bukkit.towny.event.CancellableTownyEvent;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.WorldCoord;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 退领已经生效、地块快照还原尚未入队时触发。
 * 取消只阻止这个地块的还原，不取消退领，不清除或删除原快照。
 * 线程标记遵循实际调用线程，监听器不能在异步事件中直接访问世界或库存。
 */
public class TownBlockPreRevertEvent extends CancellableTownyEvent {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Town town;

	private final TownBlock townBlock;

	private final TownPreUnclaimEvent.Cause cause;

	/**
	 * 创建地块退领生效后、快照还原入队前的事件。
	 *
	 * @param town 退领前所属的城镇；原地块未关联城镇时为 {@code null}。
	 * @param townBlock 本次已完成退领、即将决定是否加入还原队列的地块。
	 * @param cause 本次退领的触发原因，用于区分玩家退领、城镇删除等入口。
	 */
	public TownBlockPreRevertEvent(@Nullable Town town, @NotNull TownBlock townBlock, @NotNull TownPreUnclaimEvent.Cause cause) {
		this.town = town;
		this.townBlock = townBlock;
		this.cause = cause;
	}

	/**
	 * 返回退领前保存的城镇引用。
	 *
	 * @return 退领前所属的城镇；原地块未关联城镇时为 {@code null}。
	 */
	@Nullable
	public Town getTown() {
		return town;
	}

	/**
	 * 返回本次退领的地块对象。
	 *
	 * @return 退领操作传入的地块实例，供监听器核对是否允许快照还原。
	 */
	@NotNull
	public TownBlock getTownBlock() {
		return townBlock;
	}

	/**
	 * 返回本次退领地块的位置。
	 *
	 * @return 包含世界和 Towny 地块坐标的位置；坐标单位为地块。
	 */
	@NotNull
	public WorldCoord getWorldCoord() {
		return townBlock.getWorldCoord();
	}

	/**
	 * 返回本次退领的触发原因。
	 *
	 * @return 退领入口提供的原因，供监听器按玩家退领、城镇删除等场景处理。
	 */
	@NotNull
	public TownPreUnclaimEvent.Cause getCause() {
		return cause;
	}

	/**
	 * 返回本事件使用的监听器列表。
	 *
	 * @return 本事件类型共用的 Bukkit 监听器列表。
	 */
	@NotNull
	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	/**
	 * 提供 Bukkit 注册本事件监听器所需的列表。
	 *
	 * @return 与事件实例共用的监听器列表。
	 */
	@NotNull
	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
