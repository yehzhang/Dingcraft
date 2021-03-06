package com.dingcraft.ding.entitylighting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class EntityLighting
{
	private Minecraft mcInstance;
	private List<LightSourceEntity> trackedEntities;
	protected short[] lightUpdateBlockQueue;
	protected int[] lightUpdateBlockFlag;
	public static List<Function<Entity, LightSourceEntity>> entityJoinWorldChecker = new ArrayList<Function<Entity, LightSourceEntity>>();
	public static List<Function<Entity, LightSourceEntity>> entityUpdateChecker = new ArrayList<Function<Entity, LightSourceEntity>>();
	private static boolean registered = false;
	
	public EntityLighting()
	{
		this.mcInstance = FMLClientHandler.instance().getClient();
		this.trackedEntities = new ArrayList<LightSourceEntity>();
		this.lightUpdateBlockFlag = new int[1024];
		this.lightUpdateBlockQueue = new short[32768];
		if(!registered)
		{
			LightSourceArrowTorch.register();
			LightSourceBurningCreature.register();
			LightSourceBurningItem.register();
			LightSourceDroppedItem.register();
			LightSourceItemFrame.register();
			LightSourceMinecartFurnace.register();
			LightSourcePlayer.register();
			registered = true;
		}
	}
	
	/**
	 * The interface for light source entity registry.</br>
	 * 
	 * @param entry The light source entity to be registered.
	 * @return True if added to list, and false if the entry already exists or the entity's world does not match current client world.
	 */
	public boolean addEntity(LightSourceEntity entry)
	{
		if(this.trackedEntities.contains(entry) || entry.entity.worldObj != this.mcInstance.theWorld) return false;
		this.trackedEntities.add(entry);
		this.checkLight(entry.getBlockPos());
		return true;
	}
	
	/**
	 * The interface to remove an entity.</br>
	 * Note that returning true in method <i>onUpdate</i> can also remove an entity.
	 * @param entry The light source entity to be removed.
	 * @return True if successfully removed, and false if the entry does not exist.
	 */
	public boolean removeEntity(LightSourceEntity entry)
	{
		if(entry.entity.worldObj != this.mcInstance.theWorld || !this.trackedEntities.contains(entry)) return false;
		this.trackedEntities.remove(entry);
		this.checkLight(entry.getBlockPos());
		return true;
	}

	@SubscribeEvent
	public void onEntityJoinWorld(EntityJoinWorldEvent event)
	{
		if(event.world == this.mcInstance.theWorld)
		{
			for(Function<Entity, LightSourceEntity> checker : entityJoinWorldChecker)
			{
				LightSourceEntity lightSource = checker.apply(event.entity);
				if(lightSource != null)
				{
					this.addEntity(lightSource);
					break;
				}
			}
		}
	}
	
	@SubscribeEvent
	public void tick(TickEvent.ClientTickEvent event)
	{
		if(event.phase == Phase.START || this.mcInstance.theWorld == null) return;
		LightSourceEntity lightEntity = null;
		
		//remove entities
		int i = 0;
		while(i < this.trackedEntities.size())
		{
			lightEntity = this.trackedEntities.get(i);
			if(lightEntity.entity.worldObj != this.mcInstance.theWorld)
				this.trackedEntities.remove(i);
			else if(lightEntity.onUpdate())
			{
				this.trackedEntities.remove(i);
				this.checkLight(lightEntity.getBlockPos());
			}
			else
				i++;
		}
		int size;
		
		//add entities
		for(Object entity : this.mcInstance.theWorld.loadedEntityList)
		{
			size = this.trackedEntities.size();
			for(i = 0; i < size; i++)
				if(this.trackedEntities.get(i).entity == entity) break;
			if(i == size)
			{
				for(Function<Entity, LightSourceEntity> checker : entityUpdateChecker)
				{
					LightSourceEntity lightSource = checker.apply((Entity)entity);
					if(lightSource != null)
					{
						this.addEntity(lightSource);
						break;
					}
				}
			}
		}
		
		//relight
		size = this.trackedEntities.size();
		for(i = 0; i < size; i++)
		{
			lightEntity = this.trackedEntities.get(i);
			BlockPos blockPosOld = lightEntity.hasMoved();
			if(blockPosOld != null)
			{
				this.checkLight(lightEntity.getBlockPos());
				this.checkLight(blockPosOld);
			}
			else if(this.mcInstance.theWorld.getLightFor(EnumSkyBlock.BLOCK, lightEntity.getBlockPos()) != lightEntity.getLightLevel())
				this.checkLight(lightEntity.getBlockPos());
		}
	}
	
//	//Client side, no need to delete light source. Only need to remove tracked entities to free memory.
//	@SubscribeEvent
//	public void unload(WorldEvent.Unload event)
//	{
//		if(event.world == this.mcInstance.theWorld)
//			this.trackedEntities.clear();
//	}
	
	private void checkLight(BlockPos pos)
	{
		BlockPos blockPosCurr;
		BlockPos blockPosNext;

		int listCurr = 0;
		int listEnd = 0;
		int i, j;
		short compressedBlockPos;

		this.lightUpdateBlockQueue[listEnd++] = this.compressBlockPos(pos, pos);
		while(listCurr < listEnd)
		{
			compressedBlockPos = this.lightUpdateBlockQueue[listCurr++];
			blockPosCurr = this.decompressBlockPos(compressedBlockPos, pos);
			this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] &= (-2 << (compressedBlockPos & 31));
			if(this.checkNeighborLight(blockPosCurr))
			{
				blockPosNext = blockPosCurr.offset(EnumFacing.DOWN);
				if(this.inRange(blockPosNext, pos))
				{
					compressedBlockPos = this.compressBlockPos(blockPosNext, pos);
					if(((this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] >> (compressedBlockPos & 31)) & 1) == 0)
					{
						this.lightUpdateBlockQueue[listEnd++] = compressedBlockPos;
						this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] |= (1 << (compressedBlockPos & 31));
					}
				}
				
				blockPosNext = blockPosCurr.offset(EnumFacing.UP);
				if(this.inRange(blockPosNext, pos))
				{
					compressedBlockPos = this.compressBlockPos(blockPosNext, pos);
					if(((this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] >> (compressedBlockPos & 31)) & 1) == 0)
					{
						this.lightUpdateBlockQueue[listEnd++] = compressedBlockPos;
						this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] |= (1 << (compressedBlockPos & 31));
					}
				}
				
				blockPosNext = blockPosCurr.offset(EnumFacing.NORTH);
				if(this.inRange(blockPosNext, pos))
				{
					compressedBlockPos = this.compressBlockPos(blockPosNext, pos);
					if(((this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] >> (compressedBlockPos & 31)) & 1) == 0)
					{
						this.lightUpdateBlockQueue[listEnd++] = compressedBlockPos;
						this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] |= (1 << (compressedBlockPos & 31));
					}
				}
				
				blockPosNext = blockPosCurr.offset(EnumFacing.SOUTH);
				if(this.inRange(blockPosNext, pos))
				{
					compressedBlockPos = this.compressBlockPos(blockPosNext, pos);
					if(((this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] >> (compressedBlockPos & 31)) & 1) == 0)
					{
						this.lightUpdateBlockQueue[listEnd++] = compressedBlockPos;
						this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] |= (1 << (compressedBlockPos & 31));
					}
				}
				
				blockPosNext = blockPosCurr.offset(EnumFacing.WEST);
				if(this.inRange(blockPosNext, pos))
				{
					compressedBlockPos = this.compressBlockPos(blockPosNext, pos);
					if(((this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] >> (compressedBlockPos & 31)) & 1) == 0)
					{
						this.lightUpdateBlockQueue[listEnd++] = compressedBlockPos;
						this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] |= (1 << (compressedBlockPos & 31));
					}
				}
				
				blockPosNext = blockPosCurr.offset(EnumFacing.EAST);
				if(this.inRange(blockPosNext, pos))
				{
					compressedBlockPos = this.compressBlockPos(blockPosNext, pos);
					if(((this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] >> (compressedBlockPos & 31)) & 1) == 0)
					{
						this.lightUpdateBlockQueue[listEnd++] = compressedBlockPos;
						this.lightUpdateBlockFlag[(compressedBlockPos >> 5)] |= (1 << (compressedBlockPos & 31));
					}
				}
			}
		}
	}
	
	private boolean inRange(BlockPos pos, BlockPos reference)
	{
		return Math.abs(pos.getX() - reference.getX()) + Math.abs(pos.getY() - reference.getY()) + Math.abs(pos.getZ() - reference.getZ()) < 16;
	}

	private BlockPos decompressBlockPos(short compressedBlockPos, BlockPos reference)
	{
		int x = (compressedBlockPos & 31) - 16;
		int y = ((compressedBlockPos >> 5) & 31) - 16;
		int z = ((compressedBlockPos >> 10) & 31) - 16;
		return reference.add(x, y, z);
	}

	private short compressBlockPos(BlockPos pos, BlockPos reference)
	{
		int x = pos.getX() - reference.getX() + 16;
		int y = pos.getY() - reference.getY() + 16;
		int z = pos.getZ() - reference.getZ() + 16;
		return (short)((x & 31) | ((y & 31) << 5) | ((z & 31) << 10));
	}

	public int getBlockLightEmitLvl(BlockPos pos)
	{
		int lightLvl = 0;
		for(LightSourceEntity entry : this.trackedEntities)
		{
			if(entry.getBlockPos().equals(pos) && entry.entity.worldObj == this.mcInstance.theWorld)
				lightLvl = Math.max(lightLvl, entry.getLightLevel());
		}
		return Math.max(lightLvl, this.mcInstance.theWorld.getBlockState(pos).getBlock().getLightValue());
	}

	private boolean checkNeighborLight(BlockPos pos)
	{
		int lightLvlCurr = this.mcInstance.theWorld.getLightFor(EnumSkyBlock.BLOCK, pos);
		int lightLvlNew = 0;
		int l;
		BlockPos nextPos;
		
		lightLvlNew = Math.max(lightLvlNew, this.mcInstance.theWorld.getLightFor(EnumSkyBlock.BLOCK, pos.offset(EnumFacing.DOWN)));
		lightLvlNew = Math.max(lightLvlNew, this.mcInstance.theWorld.getLightFor(EnumSkyBlock.BLOCK, pos.offset(EnumFacing.UP)));
		lightLvlNew = Math.max(lightLvlNew, this.mcInstance.theWorld.getLightFor(EnumSkyBlock.BLOCK, pos.offset(EnumFacing.NORTH)));
		lightLvlNew = Math.max(lightLvlNew, this.mcInstance.theWorld.getLightFor(EnumSkyBlock.BLOCK, pos.offset(EnumFacing.SOUTH)));
		lightLvlNew = Math.max(lightLvlNew, this.mcInstance.theWorld.getLightFor(EnumSkyBlock.BLOCK, pos.offset(EnumFacing.WEST)));
		lightLvlNew = Math.max(lightLvlNew, this.mcInstance.theWorld.getLightFor(EnumSkyBlock.BLOCK, pos.offset(EnumFacing.EAST)));
		
		lightLvlNew -= Math.max(1, this.mcInstance.theWorld.getBlockState(pos).getBlock().getLightOpacity());
		lightLvlNew = Math.max(lightLvlNew, this.getBlockLightEmitLvl(pos));
		if(lightLvlNew != lightLvlCurr)
		{
			this.mcInstance.theWorld.setLightFor(EnumSkyBlock.BLOCK, pos, lightLvlNew);
			return true;
		}
		else
			return false;
	}
}
