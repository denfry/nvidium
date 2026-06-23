package me.cortex.nvidium.managers;


import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.util.IdProvider;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import net.minecraft.util.math.ChunkSectionPos;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

//8x4x8
public class RegionManager {
    public static final int META_SIZE = 8;

    private final Long2IntOpenHashMap regionMap;
    private final IdProvider idProvider = new IdProvider();
    private final IDeviceMappedBuffer regionBuffer;
    private final RenderDevice device;

    private final Region[] regions;

    //Region ids whose GPU meta changed this frame. A region is touched once per added/removed
    //section, but only the final packed state matters, so we coalesce all the changes and upload
    //each dirty region once per frame in flushDirtyRegions() instead of on every section change.
    private final IntArrayList dirtyRegions = new IntArrayList();

    public RegionManager(RenderDevice device, int maxRegions) {
        this.device = device;
        this.regionBuffer = device.createDeviceOnlyMappedBuffer((long) maxRegions * META_SIZE);
        this.regions = new Region[maxRegions];
        this.regionMap = new Long2IntOpenHashMap(maxRegions);
        regionMap.defaultReturnValue(-1);
    }

    private static long packRegion(int tcount, int sizeX, int sizeY, int sizeZ, int startX, int startY, int startZ) {
        long size = (long)sizeY<<62 | (long)sizeX<<59 | (long)sizeZ<<56;
        long count = (long)tcount<<48;
        long offset = ((long)startX&0xfffff)<<0 | ((long)startY&0xff)<<40 | ((long)startZ&0xfffff)<<20;
        return size|count|offset;
    }

    public int regionKeyToId(long key) {
        return regionMap.get(key);
    }

    public long regionIdToKey(int id) {
        if (regions[id] == null) {
            throw new IllegalStateException();
        }
        return regions[id].key;
    }

    public boolean regionIsAtPos(int regionId, int x, int y, int z) {
        var region = regions[regionId];
        if (region == null) return false;
        return region.rx == x && region.ry == y && region.rz == z;
    }

    //IDEA: make it so that sections are packed into regions, that is the local index of a chunk is hard coded to its position, and just 256 sections are processed when a region is visible, this has some overhead but means that the exact amount being processed each time is known and the same
    private static final class Region {
        private final int rx;
        private final int ry;
        private final int rz;
        private final long key;
        private final int id;//This is the location of the region in memory, the sections it belongs to are indexed by region.id*256+section.id
        //private final short[] mapping = new short[256];//can theoretically get rid of this
        private final BitSet freeIndices = new BitSet(256);
        private int count;
        private boolean dirty;//Already queued in dirtyRegions this frame
        private final byte[] id2pos = new byte[256];

        private Region(long key, int id, int rx, int ry, int rz) {
            this.key = key;
            this.id = id;
            freeIndices.set(0,256);
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
        }


        public long getPackedData() {
            if (count == 0) {
                return 0;//Basically return null
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            int lastIdx = 0;
            for (int i = 0; i < 256; i++) {
                if (freeIndices.get(i)) continue;//Skip over non set indicies
                int x = id2pos[i]&7;
                int y = Byte.toUnsignedInt(id2pos[i])>>>6;
                int z = (id2pos[i]>>>3)&7;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
                lastIdx = i;
            }

            return packRegion(lastIdx+1,
                    maxX-minX,
                    maxY-minY,
                    maxZ-minZ,
                    (rx<<3)+minX,
                    (ry<<2)+minY,
                    (rz<<3)+minZ);
        }
    }

    public static long getRegionKey(int sectionX, int sectionY, int sectionZ) {
        return ChunkSectionPos.asLong(sectionX>>3, sectionY>>2, sectionZ>>3);
    }

    //Returns -1 when region capacity is exhausted (more live regions than the estimated
    //maxRegions). Callers must drop the section in that case rather than proceeding.
    public int createSectionIndex(UploadingBufferStream uploadStream, int sectionX, int sectionY, int sectionZ) {
        long key = getRegionKey(sectionX, sectionY, sectionZ);
        int idx = regionMap.get(key);
        if (idx == -1) {
            //A new region is needed. idProvider hands out monotonically increasing ids when
            //it has no freed ones, so guard against indexing regions[]/the GPU buffers out of
            //bounds when more regions are loaded than the maxRegions estimate provisioned.
            idx = idProvider.provide();
            if (idx >= regions.length) {
                idProvider.release(idx);//Roll back so idProvider state stays consistent
                return -1;
            }
            regionMap.put(key, idx);
        }
        Region region = regions[idx];
        Region region2 = region;
        if (region == null) {
            region = regions[idx] = new Region(key, idx, sectionX>>3, sectionY>>2, sectionZ>>3);
        }
        if (region.key != key) {
            throw new IllegalStateException("Had " + region.key + " expected: " + key + " " + region2);
        }
        region.count++;

        int sectionId = region.freeIndices.nextSetBit(0);
        if (sectionId<0||255<sectionId) {
            throw new IllegalStateException();
        }
        region.freeIndices.clear(sectionId);
        //Mark the section is set
        region.id2pos[sectionId] = (byte) ((sectionY & 3) << 6 | sectionX & 7 | (sectionZ & 7) << 3);

        markRegionDirty(region);

        return (region.id<<8)|sectionId;//region.id*8+sectionId
    }

    public void removeSectionIndex(UploadingBufferStream uploadStream, int sectionId) {
        Region region = regions[sectionId>>>8];// divide by 256
        if (region == null) {
            throw new IllegalStateException();
        }
        if ((!regionMap.containsKey(region.key)) || regionMap.get(region.key) != region.id) {
            throw new IllegalStateException();
        }
        region.count--;
        region.freeIndices.set(sectionId&255);
        //Mark the section is not set
        region.id2pos[sectionId&255] = 0;

        if (region.count == 0) {
            idProvider.release(region.id);
            //Note: there is a special-case in region.getPackedData that means when count == 0, it auto nulls
            markRegionDirty(region);
            regions[region.id] = null;
            region.count = -111;

            if (regionMap.remove(region.key) != region.id) {
                throw new IllegalStateException();
            }
        } else {
            markRegionDirty(region);
        }
    }

    //Queue the region's meta for a single upload at the end of the frame. A region is commonly
    //touched many times per frame (e.g. up to 256 sections loading into it); coalescing avoids
    //that many redundant 8-byte uploads since only the final packed state is observable.
    private void markRegionDirty(Region region) {
        if (!region.dirty) {
            region.dirty = true;
            dirtyRegions.add(region.id);
        }
    }

    //Uploads every region touched this frame exactly once. A dirty id whose region was removed
    //(regions[id] == null) writes 0, matching getPackedData()'s removed-region encoding. Must be
    //called once per frame before the upload stream is committed.
    public void flushDirtyRegions(UploadingBufferStream uploadStream) {
        for (int k = 0; k < dirtyRegions.size(); k++) {
            int id = dirtyRegions.getInt(k);
            Region region = regions[id];
            long segment = uploadStream.getUpload(regionBuffer, (long) id * META_SIZE, META_SIZE);
            MemoryUtil.memPutLong(segment, region != null ? region.getPackedData() : 0);
            if (region != null) {
                region.dirty = false;
            }
        }
        dirtyRegions.clear();
    }

    public void delete() {
        regionBuffer.delete();
    }

    public long getRegionDataAddress() {
        return regionBuffer.getDeviceAddress();
    }

    public int maxRegions() {
        return regions.length;
    }

    public int regionCount() {
        return regionMap.size();
    }

    public int maxRegionIndex() {
        return idProvider.maxIndex();
    }

    public boolean isRegionVisible(Frustum frustum, int regionId) {
        var region = regions[regionId];
        if (region == null) {
            return false;
        } else {
            //FIXME: should make it use the region data so that the frustum bounds check is more accurate
            return frustum.isBoxVisible(region.rx<<7,region.ry<<6, region.rz<<7, (region.rx+1)<<7, (region.ry+1)<<6, (region.rz+1)<<7);
        }
    }
    public boolean regionExists(int regionId) {
        return regions[regionId] != null;
    }
    public int distance(int regionId, int camChunkX, int camChunkY, int camChunkZ) {
        var region = regions[regionId];
        return  Math.abs((region.rx<<3)+4-camChunkX)+
                Math.abs((region.ry<<2)+2-camChunkY)+
                Math.abs((region.rz<<3)+4-camChunkZ);
    }
    public boolean withinSquare(int dist, int regionId, int camChunkX, int camChunkY, int camChunkZ) {
        var region = regions[regionId];
        return  Math.abs((region.rx<<3)+4-camChunkX)<=dist &&
                Math.abs((region.ry<<2)+2-camChunkY)<=dist &&
                Math.abs((region.rz<<3)+4-camChunkZ)<=dist;
    }
}
