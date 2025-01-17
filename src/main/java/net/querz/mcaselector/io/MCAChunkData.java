package net.querz.mcaselector.io;

import net.querz.mcaselector.changer.Field;
import net.querz.mcaselector.debug.Debug;
import net.querz.mcaselector.point.Point2i;
import net.querz.nbt.CompoundTag;
import net.querz.nbt.DoubleTag;
import net.querz.nbt.IntArrayTag;
import net.querz.nbt.IntTag;
import net.querz.nbt.ListTag;
import net.querz.nbt.LongArrayTag;
import net.querz.nbt.Tag;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

public class MCAChunkData {

	private long offset; //in actual bytes
	private int timestamp;
	private byte sectors;
	private int length; //length without padding
	private CompressionType compressionType;
	private CompoundTag data;

	//offset in 4KiB chunks
	public MCAChunkData(int offset, int timestamp, byte sectors) {
		this.offset = ((long) offset) * MCAFile.SECTION_SIZE;
		this.timestamp = timestamp;
		this.sectors = sectors;
	}

	public boolean isEmpty() {
		return offset == 0 && timestamp == 0 && sectors == 0;
	}

	public void readHeader(ByteArrayPointer ptr) throws Exception {
		ptr.seek(offset);
		length = ptr.readInt();
		compressionType = CompressionType.fromByte(ptr.readByte());
	}

	public void loadData(ByteArrayPointer ptr) throws Exception {
		//offset + length of length (4 bytes) + length of compression type (1 byte)
		ptr.seek(offset + 5);
		DataInputStream nbtIn = null;

		switch (compressionType) {
		case GZIP:
			nbtIn = new DataInputStream(new BufferedInputStream(new GZIPInputStream(ptr)));
			break;
		case ZLIB:
			nbtIn = new DataInputStream(new BufferedInputStream(new InflaterInputStream(ptr)));
			break;
		case NONE:
			data = null;
			return;
		}
		Tag tag = Tag.deserialize(nbtIn, Tag.DEFAULT_MAX_DEPTH);

		if (tag instanceof CompoundTag) {
			data = (CompoundTag) tag;
		} else {
			throw new Exception("Invalid chunk data: tag is not of type CompoundTag");
		}
	}

	//saves to offset provided by raf, because it might be different when data changed
	//returns the number of bytes that were written to the file
	public int saveData(RandomAccessFile raf) throws Exception {
		DataOutputStream nbtOut;

		ByteArrayOutputStream baos;

		switch (compressionType) {
		case GZIP:
			nbtOut = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(baos = new ByteArrayOutputStream()), sectors * MCAFile.SECTION_SIZE));
			break;
		case ZLIB:
			nbtOut = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(baos = new ByteArrayOutputStream()), sectors * MCAFile.SECTION_SIZE));
			break;
		default:
			return 0;
		}

		data.serialize(nbtOut, Tag.DEFAULT_MAX_DEPTH);
		nbtOut.close();

		byte[] rawData = baos.toByteArray();

		raf.writeInt(rawData.length);
		raf.writeByte(compressionType.getByte());
		raf.write(rawData);

		return rawData.length + 5;
	}

	public void changeData(List<Field<?>> fields, boolean force) {
		for (Field field : fields) {
			try {
				if (force) {
					field.force(data);
				} else {
					field.change(data);
				}
			} catch (Exception ex) {
				Debug.dumpf("error trying to update field: %s", ex.getMessage());
			}
		}
	}

	public long getOffset() {
		return offset;
	}

	void setOffset(int sectorOffset) {
		this.offset = ((long) sectorOffset) * MCAFile.SECTION_SIZE;
	}

	public int getLength() {
		return length;
	}

	public int getTimestamp() {
		return timestamp;
	}

	public byte getSectors() {
		return sectors;
	}

	public CompressionType getCompressionType() {
		return compressionType;
	}

	public CompoundTag getData() {
		return data;
	}

	public Point2i getLocation() {
		if (data == null || !data.containsKey("Level") || !data.getCompoundTag("Level").containsKey("xPos") || !data.getCompoundTag("Level").containsKey("zPos")) {
			return null;
		}
		return new Point2i(data.getCompoundTag("Level").getInt("xPos"), data.getCompoundTag("Level").getInt("zPos"));
	}

	// offset is in blocks
	public boolean relocate(Point2i offset) {
		if (data == null || !data.containsKey("Level")) {
			return false;
		}

		CompoundTag level = catchClassCastException(() -> data.getCompoundTag("Level"));
		if (level == null) {
			return true;
		}

		// adjust or set chunk position
		level.putInt("xPos", level.getInt("xPos") + offset.blockToChunk().getX());
		level.putInt("zPos", level.getInt("zPos") + offset.blockToChunk().getY());

		// adjust entity positions
		if (level.containsKey("Entities")) {
			ListTag<CompoundTag> entities = catchClassCastException(() -> level.getListTag("Entities").asCompoundTagList());
			if (entities != null) {
				entities.forEach(v -> applyOffsetToEntity(v, offset));
			}
		}

		// adjust tile entity positions
		if (level.containsKey("TileEntities")) {
			ListTag<CompoundTag> tileEntities = catchClassCastException(() -> level.getListTag("TileEntities").asCompoundTagList());
			if (tileEntities != null) {
				tileEntities.forEach(v -> applyOffsetToTileEntity(v, offset));
			}
		}

		// adjust tile ticks
		if (level.containsKey("TileTicks")) {
			ListTag<CompoundTag> tileTicks = catchClassCastException(() -> level.getListTag("TileTicks").asCompoundTagList());
			if (tileTicks != null) {
				tileTicks.forEach(v -> applyOffsetToTick(v, offset));
			}
		}

		// adjust liquid ticks
		if (level.containsKey("LiquidTicks")) {
			ListTag<CompoundTag> liquidTicks = catchClassCastException(() -> level.getListTag("LiquidTicks").asCompoundTagList());
			if (liquidTicks != null) {
				liquidTicks.forEach(v -> applyOffsetToTick(v, offset));
			}
		}

		// adjust structures
		if (level.containsKey("Structures")) {
			CompoundTag structures = catchClassCastException(() -> level.getCompoundTag("Structures"));
			if (structures != null) {
				applyOffsetToStructures(structures, offset);
			}
		}

		return true;
	}

	private void applyOffsetToStructures(CompoundTag structures, Point2i offset) {
		Point2i chunkOffset = offset.blockToChunk();

		// update references
		if (structures.containsKey("References")) {
			CompoundTag references = catchClassCastException(() -> structures.getCompoundTag("References"));
			if (references != null) {
				for (Map.Entry<String, Tag<?>> entry : references) {
					long[] reference = catchClassCastException(() -> ((LongArrayTag) entry.getValue()).getValue());
					if (reference != null) {
						for (int i = 0; i < reference.length; i++) {
							int x = (int) (reference[i]);
							int z = (int) (reference[i] >> 32);
							reference[i] = (long) (x + chunkOffset.getX()) | ((long) (z + chunkOffset.getY()) << 32);
						}
					}
				}
			}
		}

		// update starts
		if (structures.containsKey("Starts")) {
			CompoundTag starts = catchClassCastException(() -> structures.getCompoundTag("Starts"));
			if (starts != null) {
				for (Map.Entry<String, Tag<?>> entry : starts) {
					CompoundTag structure = catchClassCastException(() -> (CompoundTag) entry.getValue());
					if (structure == null || "INVALID".equals(catchClassCastException(() -> structure.getString("id")))) {
						continue;
					}
					applyIntIfPresent(structure, "ChunkX", chunkOffset.getX());
					applyIntIfPresent(structure, "ChunkZ", chunkOffset.getY());
					applyOffsetToBB(catchClassCastException(() -> structure.getIntArray("BB")), offset);

					if (structure.containsKey("Processed")) {
						ListTag<CompoundTag> processed = catchClassCastException(() -> structure.getListTag("Processed").asCompoundTagList());
						if (processed != null) {
							for (CompoundTag chunk : processed) {
								applyIntIfPresent(chunk, "X", chunkOffset.getX());
								applyIntIfPresent(chunk, "Z", chunkOffset.getY());
							}
						}
					}

					if (structure.containsKey("Children")) {
						ListTag<CompoundTag> children = catchClassCastException(() -> structure.getListTag("Children").asCompoundTagList());
						if (children != null) {
							for (CompoundTag child : children) {
								applyIntIfPresent(child, "TPX", offset.getX());
								applyIntIfPresent(child, "TPZ", offset.getY());
								applyIntIfPresent(child, "PosX", offset.getX());
								applyIntIfPresent(child, "PosZ", offset.getY());
								applyOffsetToBB(catchClassCastException(() -> child.getIntArray("BB")), offset);

								if (child.containsKey("Entrances")) {
									ListTag<IntArrayTag> entrances = catchClassCastException(() -> child.getListTag("Entrances").asIntArrayTagList());
									if (entrances != null) {
										for (IntArrayTag entrance : entrances) {
											applyOffsetToBB(entrance.getValue(), offset);
										}
									}
								}

								if (child.containsKey("junctions")) {
									ListTag<CompoundTag> junctions = catchClassCastException(() -> child.getListTag("junctions").asCompoundTagList());
									if (junctions != null) {
										for (CompoundTag junction : junctions) {
											applyIntIfPresent(junction, "source_x", offset.getX());
											applyIntIfPresent(junction, "source_z", offset.getY());
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private void applyOffsetToBB(int[] bb, Point2i offset) {
		if (bb == null || bb.length != 6) {
			return;
		}
		bb[0] += offset.getX();
		bb[2] += offset.getY();
		bb[3] += offset.getX();
		bb[5] += offset.getY();
	}

	private void applyOffsetToTick(CompoundTag tick, Point2i offset) {
		applyIntIfPresent(tick, "x", offset.getX());
		applyIntIfPresent(tick, "z", offset.getY());
	}

	private void applyOffsetToTileEntity(CompoundTag tileEntity, Point2i offset) {
		applyIntIfPresent(tileEntity, "x", offset.getX());
		applyIntIfPresent(tileEntity, "z", offset.getY());

		switch (tileEntity.getString("id")) {
			case "beehive":
				CompoundTag flowerPos = catchClassCastException(() -> tileEntity.getCompoundTag("FlowerPos"));
				applyIntOffsetIfRootPresent(flowerPos, "X", "Z", offset);
				break;
			case "end_gateway":
				CompoundTag exitPortal = catchClassCastException(() -> tileEntity.getCompoundTag("ExitPortal"));
				applyIntOffsetIfRootPresent(exitPortal, "X", "Z", offset);
				break;
			case "structure_block":
				applyIntIfPresent(tileEntity, "posX", offset.getX());
				applyIntIfPresent(tileEntity, "posX", offset.getY());
				break;
		}
	}

	private void applyOffsetToEntity(CompoundTag entity, Point2i offset) {
		if (entity == null) {
			return;
		}
		if (entity.containsKey("Pos")) {
			ListTag<DoubleTag> entityPos = catchClassCastException(() -> entity.getListTag("Pos").asDoubleTagList());
			if (entityPos != null && entityPos.size() == 3) {
				entityPos.set(0, new DoubleTag(entityPos.get(0).asDouble() + offset.getX()));
				entityPos.set(2, new DoubleTag(entityPos.get(2).asDouble() + offset.getY()));
			}
		}

		// leashed entities
		if (entity.containsKey("Leash")) {
			CompoundTag leash = catchClassCastException(() -> entity.getCompoundTag("Leash"));
			applyIntOffsetIfRootPresent(leash, "X", "Z", offset);
		}

		// projectiles
		applyIntIfPresent(entity, "xTile", offset.getX());
		applyIntIfPresent(entity, "zTile", offset.getY());

		// entities that have a sleeping place
		applyIntIfPresent(entity, "SleepingX", offset.getX());
		applyIntIfPresent(entity, "SleepingZ", offset.getY());

		// positions for specific entity types
		String id = catchClassCastException(() -> entity.getString("id"));
		if (id != null) {
			switch (id) {
				case "dolphin":
					if (entity.getBoolean("CanFindTreasure")) {
						applyIntIfPresent(entity, "TreasurePosX", offset.getX());
						applyIntIfPresent(entity, "TreasurePosZ", offset.getY());
					}
					break;
				case "phantom":
					applyIntIfPresent(entity, "AX", offset.getX());
					applyIntIfPresent(entity, "AZ", offset.getY());
					break;
				case "shulker":
					applyIntIfPresent(entity, "APX", offset.getX());
					applyIntIfPresent(entity, "APZ", offset.getY());
					break;
				case "turtle":
					applyIntIfPresent(entity, "HomePosX", offset.getX());
					applyIntIfPresent(entity, "HomePosZ", offset.getY());
					applyIntIfPresent(entity, "TravelPosX", offset.getX());
					applyIntIfPresent(entity, "TravelPosZ", offset.getY());
					break;
				case "vex":
					applyIntIfPresent(entity, "BoundX", offset.getX());
					applyIntIfPresent(entity, "BoundZ", offset.getY());
					break;
				case "wandering_trader":
					if (entity.containsKey("WanderTarget")) {
						CompoundTag wanderTarget = catchClassCastException(() -> entity.getCompoundTag("WanderTarget"));
						applyIntOffsetIfRootPresent(wanderTarget, "X", "Z", offset);
					}
					break;
				case "shulker_bullet":
					CompoundTag owner = catchClassCastException(() -> entity.getCompoundTag("Owner"));
					applyIntOffsetIfRootPresent(owner, "X", "Z", offset);
					CompoundTag target = catchClassCastException(() -> entity.getCompoundTag("Target"));
					applyIntOffsetIfRootPresent(target, "X", "Z", offset);
					break;
				case "end_crystal":
					CompoundTag beamTarget = catchClassCastException(() -> entity.getCompoundTag("BeamTarget"));
					applyIntOffsetIfRootPresent(beamTarget, "X", "Z", offset);
					break;
				case "item_frame":
				case "painting":
					applyIntIfPresent(entity, "TileX", offset.getX());
					applyIntIfPresent(entity, "TileZ", offset.getY());
					break;
				case "villager":
					if (entity.containsKey("Brain")) {
						CompoundTag brain = catchClassCastException(() -> entity.getCompoundTag("Brain"));
						if (brain != null && brain.containsKey("memories")) {
							CompoundTag memories = catchClassCastException(() -> brain.getCompoundTag("memories"));
							if (memories != null && memories.size() > 0) {
								if (memories.containsKey("minecraft:meeting_point")) {
									CompoundTag meetingPoint = catchClassCastException(() -> memories.getCompoundTag("minecraft:meeting_point"));
									if (meetingPoint != null) {
										ListTag<IntTag> pos = catchClassCastException(() -> meetingPoint.getListTag("pos").asIntTagList());
										applyOffsetToIntListPos(pos, offset);
									}
								}
								if (memories.containsKey("minecraft:home")) {
									CompoundTag home = catchClassCastException(() -> memories.getCompoundTag("minecraft:home"));
									if (home != null) {
										ListTag<IntTag> pos = catchClassCastException(() -> home.getListTag("pos").asIntTagList());
										applyOffsetToIntListPos(pos, offset);
									}
								}
								if (memories.containsKey("minecraft:job_site")) {
									CompoundTag jobSite = catchClassCastException(() -> memories.getCompoundTag("minecraft:job_site"));
									if (jobSite != null) {
										ListTag<IntTag> pos = catchClassCastException(() -> jobSite.getListTag("pos").asIntTagList());
										applyOffsetToIntListPos(pos, offset);
									}
								}
							}
						}
					}
					break;
				case "pillager":
				case "witch":
				case "vindicator":
				case "ravager":
				case "illusioner":
				case "evoker":
					if (entity.containsKey("PatrolTarget")) {
						CompoundTag patrolTarget = catchClassCastException(() -> entity.getCompoundTag("PatrolTarget"));
						applyIntOffsetIfRootPresent(patrolTarget, "X", "Z", offset);
					}
					break;
			}
		}

		// recursively update passengers

		if (entity.containsKey("Passenger")) {
			CompoundTag passenger = catchClassCastException(() -> entity.getCompoundTag("Passenger"));
			applyOffsetToEntity(passenger, offset);
		}
	}

	private void applyIntOffsetIfRootPresent(CompoundTag root, String xKey, String zKey, Point2i offset) {
		if (root != null) {
			applyIntIfPresent(root, xKey, offset.getX());
			applyIntIfPresent(root, zKey, offset.getY());
		}
	}

	private void applyIntIfPresent(CompoundTag root, String key, int offset) {
		Integer value;
		if (root.containsKey(key) && (value = catchClassCastException(() -> root.getInt(key))) != null) {
			root.putInt(key, value + offset);
		}
	}

	private void applyOffsetToIntListPos(ListTag<IntTag> pos, Point2i offset) {
		if (pos != null && pos.size() == 3) {
			pos.set(0, new IntTag(pos.get(0).asInt() + offset.getX()));
			pos.set(2, new IntTag(pos.get(2).asInt() + offset.getY()));
		}
	}

	private <T> T catchClassCastException(Supplier<T> s) {
		try {
			return s.get();
		} catch (ClassCastException ex) {
			Debug.dumpf("error parsing value in chunk import: %s", ex.getMessage());
			return null;
		}
	}
}
