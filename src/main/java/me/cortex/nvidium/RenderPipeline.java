package me.cortex.nvidium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.renderers.*;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.TickableManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.format.CompactChunkVertex;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import net.minecraft.client.MinecraftClient;
import org.joml.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.lang.Math;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBDirectStateAccess.glClearNamedBufferSubData;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;
import static org.lwjgl.opengl.NVShaderBufferStore.GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.*;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX;


/*
var hm = new HashSet<Long>();
for (int i = 0; i < rm.maxRegionIndex(); i++) {
    if (rm.isRegionVisible(frustum, i)) {
        if(rm.regions[i] != null) {
            hm.add(ChunkPos.toLong(rm.regions[i].rx, rm.regions[i].rz))
        }
    }
}
hm.size()
 */

//TODO: extract out sectionManager, uploadStream, downloadStream and other funky things to an auxiliary parent NvidiumWorldRenderer class
public class RenderPipeline {
    public static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    public static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    //Reused clear values for glClearNamedBufferSubData (read synchronously, never mutated) so the
    //per-region/per-frame visibility clears don't allocate a one-element int[] on every call.
    private static final int[] CLEAR_ZERO = {0};
    private static final int[] CLEAR_ONE = {1};
    private static final int[] CLEAR_MINUS_ONE = {-1};

    private static final RenderDevice device = new RenderDevice();

    public final SectionManager sectionManager;

    public final RegionVisibilityTracker regionVisibilityTracking;

    private final PrimaryTerrainRasterizer terrainRasterizer;
    private final RegionRasterizer regionRasterizer;
    private final SectionRasterizer sectionRasterizer;
    private final TemporalTerrainRasterizer temporalRasterizer;
    private final TranslucentTerrainRasterizer translucencyTerrainRasterizer;

    private final IDeviceMappedBuffer sceneUniform;
    private static final int SCENE_SIZE = (int) alignUp(4*4*4+4*4+4*4+4+4*4+4*4+8*6+3*4+3, 2);

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer terrainCommandBuffer;

    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final int bufferSizesMB;

    private final BitSet regionVisibilityTracker;

    //Reused each frame to collect visible regions as packed (distance<<16)|id keys, sorted in
    //place instead of allocating a fresh IntAVLTreeSet (+ a node per region) every frame.
    private final int[] regionSortBuffer;

    //Reverse of regionMap for this frame: region id -> its index in the visible list. Only entries
    //whose id is set in regionVisibilityTracker are valid this frame. Lets the player-region marking
    //look regions up directly instead of scanning the whole visible list.
    private final int[] regionIdToVisibleIndex;

    //Per-in-flight-frame snapshots of the visible-position -> region id mapping. The visibility
    //download callback (RegionVisibilityTracker.computeVisibility) captures the frame's mapping and
    //only runs `frames` ticks later, so a single reused buffer would be overwritten before its
    //callback reads it. A ring of frames+1 buffers (one more than the download latency) guarantees
    //each captured snapshot survives until its callback fires, replacing the per-frame short[] alloc.
    private final short[][] regionMapPool;
    private int regionMapCursor;

    //Max memory that the gpu can use to store geometry in mb
    private long max_geometry_memory;
    private long last_sample_time;

    public RenderPipeline() {
        int frames = SodiumClientMod.options().advanced.cpuRenderAheadLimit+1;
        this.uploadStream = new UploadingBufferStream(device, frames, 250000000);
        this.downloadStream = new DownloadTaskStream(device, frames, 16000000);
        update_allowed_memory();
        sectionManager = new SectionManager(device, max_geometry_memory*1024*1024, uploadStream, MinecraftClient.getInstance().options.getClampedViewDistance() + Nvidium.config.extra_rd, 24, CompactChunkVertex.STRIDE);
        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();

        int maxRegions = sectionManager.getRegionManager().maxRegions();
        int cbs = sectionManager.getTotalBufferSizes();
        sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE+ maxRegions*2L);
        cbs += SCENE_SIZE+ maxRegions*2L;
        regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
        cbs += maxRegions;
        sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L);
        cbs += maxRegions * 256L;
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        cbs += maxRegions*8L;

        regionVisibilityTracker = new BitSet(maxRegions);
        regionSortBuffer = new int[maxRegions];
        regionIdToVisibleIndex = new int[maxRegions];
        //frames+1 buffers: the visibility download fires `frames` ticks after enqueue, so one spare
        //slot beyond the latency keeps a captured snapshot alive until its callback has consumed it.
        regionMapPool = new short[frames + 1][maxRegions];

        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, maxRegions);

        bufferSizesMB = cbs/(1024*1024);
    }

    private int prevRegionCount;
    private int frameId;

    //ISSUE TODO: regions that where in frustum but are now out of frustum must have the visibility data cleared
    // this is due to funny issue of pain where the section was "visible" last frame cause it didnt get ticked
    public void renderFrame(Frustum frustum, ChunkRenderMatrices crm, double px, double py, double pz) {//NOTE: can use any of the command list rendering commands to basicly draw X indirects using the same shader, thus allowing for terrain to be rendered very efficently

        if (sectionManager.getRegionManager().regionCount() == 0) return;//Dont render anything if there is nothing to render
        Vector3i blockPos = new Vector3i(((int)Math.floor(px)), ((int)Math.floor(py)), ((int)Math.floor(pz)));
        Vector3i chunkPos = new Vector3i(blockPos.x>>4,blockPos.y>>4,blockPos.z>>4);
        //  /tp @p 0.0 -1.62 0.0 0 0
        //Clear the first gl error, not our fault
        glGetError();
        int err;

        int visibleRegions = 0;

        var rm = sectionManager.getRegionManager();
        short[] regionMap;
        //Enqueue all the visible regions
        {
            //The region data indicies is located at the end of the sceneUniform
            //Collect visible regions as packed (distance<<16)|id keys into a reused buffer and sort
            //in place; ascending order is nearest-first since distance occupies the high bits.
            for (int i = 0; i < rm.maxRegionIndex(); i++) {
                if (!rm.regionExists(i)) continue;
                if ((Nvidium.config.region_keep_distance != 256 && Nvidium.config.region_keep_distance != 32) && !rm.withinSquare(Nvidium.config.region_keep_distance+4, i, chunkPos.x, chunkPos.y, chunkPos.z)) {
                    removeRegion(i);
                    continue;
                }

                if (rm.isRegionVisible(frustum, i)) {
                    regionSortBuffer[visibleRegions++] = (rm.distance(i, chunkPos.x, chunkPos.y, chunkPos.z)<<16)|i;
                    regionVisibilityTracker.set(i);
                } else {
                    if (regionVisibilityTracker.get(i)) {//Going from visible to non visible
                        //Clear the visibility bits
                        if (Nvidium.config.enable_temporal_coherence) {
                            glClearNamedBufferSubData(sectionVisibility.getId(), GL_R8UI, (long) i << 8, 255, GL_RED_INTEGER, GL_UNSIGNED_BYTE, CLEAR_ZERO);
                        }
                    }
                    regionVisibilityTracker.clear(i);
                }

            }
            if (visibleRegions == 0) return;
            Arrays.sort(regionSortBuffer, 0, visibleRegions);//Nearest-first by packed key
            //Take the next ring buffer instead of allocating. Only [0, visibleRegions) is written and
            //read this frame; stale entries past that are never touched. Advancing here (past the
            //visibleRegions==0 early-out) keeps the cursor in lockstep with computeVisibility's
            //download enqueues, which is what the frames+1 sizing is balanced against.
            regionMap = regionMapPool[regionMapCursor];
            regionMapCursor = regionMapCursor + 1 == regionMapPool.length ? 0 : regionMapCursor + 1;
            long addr = sectionManager.uploadStream.getUpload(sceneUniform, SCENE_SIZE, visibleRegions*2);
            for (int j = 0; j < visibleRegions; j++) {
                int packed = regionSortBuffer[j];
                regionMap[j] = (short) packed;
                regionIdToVisibleIndex[packed & 0xFFFF] = j;
                MemoryUtil.memPutShort(addr+((long) j <<1), (short) packed);
            }

        }

        {
            //TODO: maybe segment the uniform buffer into 2 parts, always updating and static where static holds pointers
            Vector3f delta = new Vector3f((float) (px-(chunkPos.x<<4)), (float) (py-(chunkPos.y<<4)), (float) (pz-(chunkPos.z<<4)));
            delta.negate();
            long addr = sectionManager.uploadStream.getUpload(sceneUniform, 0, SCENE_SIZE);
            var mvp =new Matrix4f(crm.projection())
                    .mul(crm.modelView())
                    .translate(delta)//Translate the subchunk position
                    .getToAddress(addr);
            addr += 4*4*4;
            new Vector4i(chunkPos.x, chunkPos.y, chunkPos.z, 0).getToAddress(addr);//Chunk the camera is in//TODO: THIS
            addr += 16;
            new Vector4f(delta,0).getToAddress(addr);//Subchunk offset (note, delta is already negated)
            addr += 16;
            new Vector4f(RenderSystem.getShaderFogColor()).getToAddress(addr);
            addr += 16;
            MemoryUtil.memPutLong(addr, sceneUniform.getDeviceAddress() + SCENE_SIZE);//Put in the location of the region indexs
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getRegionDataAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getSectionDataAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, regionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, terrainCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutFloat(addr, RenderSystem.getShaderFogStart());//FogStart
            addr += 4;
            MemoryUtil.memPutFloat(addr, RenderSystem.getShaderFogEnd());//FogEnd
            addr += 4;
            MemoryUtil.memPutInt(addr, RenderSystem.getShaderFogShape().getId());//IsSphericalFog
            addr += 4;
            MemoryUtil.memPutShort(addr, (short) visibleRegions);
            addr += 2;
            MemoryUtil.memPutByte(addr, (byte) (frameId++));
        }

        sectionManager.commitChanges();//Commit all uploads done to the terrain and meta data

        //TODO: FIXME: THIS FEELS ILLEGAL
        TickableManager.TickAll();

        if ((err = glGetError()) != 0) {
            throw new IllegalStateException("GLERROR: "+err);
        }


        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Bind the uniform, it doesnt get wiped between shader changes
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);

        if (prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            terrainRasterizer.raster(prevRegionCount, terrainCommandBuffer.getDeviceAddress());
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }

        //NOTE: For GL_REPRESENTATIVE_FRAGMENT_TEST_NV to work, depth testing must be disabled, or depthMask = false
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glColorMask(false, false, false, false);
        glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        regionRasterizer.raster(visibleRegions);

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        {//Mark the region(s) the player occupies as visible so they aren't occlusion-culled. The 27
         //block neighbours collapse to a <=2x2x2 box of region coords (a region spans 128x64x128
         //blocks), so look each up directly instead of scanning every visible region — this was
         //O(visibleRegions*27) and is now O(<=8), which matters at high render distances.
            int rx0 = (blockPos.x - 1) >> 7, rx1 = (blockPos.x + 1) >> 7;
            int ry0 = (blockPos.y - 1) >> 6, ry1 = (blockPos.y + 1) >> 6;
            int rz0 = (blockPos.z - 1) >> 7, rz1 = (blockPos.z + 1) >> 7;
            for (int rx = rx0; rx <= rx1; rx++) {
                for (int ry = ry0; ry <= ry1; ry++) {
                    for (int rz = rz0; rz <= rz1; rz++) {
                        int id = rm.regionKeyToId(RegionManager.getRegionKey(rx << 3, ry << 2, rz << 3));
                        if (id != -1 && regionVisibilityTracker.get(id)) {
                            setRegionVisible(regionIdToVisibleIndex[id]);
                        }
                    }
                }
            }
        }


        sectionRasterizer.raster(visibleRegions);
        glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDepthMask(true);
        glColorMask(true, true, true, true);

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        {//This uses the clear buffer to set the byte for the section the player is standing in, this should be cheaper than comparing it on the gpu
            int msk = 0;//This is such a dumb way to do this but it works
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        int mid = 1<<(((chunkPos.x - ((blockPos.x + x) >> 4))+1)+((chunkPos.y - ((blockPos.y + y) >> 4))+1)*3+((chunkPos.z - ((blockPos.z + z) >> 4))+1)*9);
                        if ((msk&mid)==0) {
                            setSectionVisible((blockPos.x + x) >> 4, (blockPos.y + y) >> 4, (blockPos.z + z) >> 4);
                            msk |= mid;
                        }
                    }
                }
            }
        }

        prevRegionCount = visibleRegions;

        //Do temporal rasterization
        if (Nvidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(visibleRegions, terrainCommandBuffer.getDeviceAddress());
        }

        {//Do proper visibility tracking
            glDepthMask(false);
            glColorMask(false, false, false, false);
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);

            regionVisibilityTracking.computeVisibility(visibleRegions, regionVisibility, regionMap);

            glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            glDepthMask(true);
            glColorMask(true, true, true, true);
        }


        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        glDepthFunc(GL11C.GL_LEQUAL);
        glDisable(GL_DEPTH_TEST);


        if ((err = glGetError()) != 0) {
            throw new IllegalStateException("GLERROR: "+err);
        }


        if (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER && (System.currentTimeMillis() - last_sample_time) > 60000) {
            last_sample_time = System.currentTimeMillis();
            update_allowed_memory();
        }

        if (sectionManager.terrainAreana.getUsedMB()>(max_geometry_memory-50)) {
            var rmgr = sectionManager.getRegionManager();
            //Primary heuristic: evict the region that has been in-frustum but unseen the longest.
            //It returns -1 until some region has accumulated enough visibility samples, so when we
            //are over budget with no samples yet (e.g. right after a teleport loads a large area)
            //fall back to evicting the spatially furthest region. Without this the geometry buffer
            //stays full and never frees, starving new uploads at high render distances.
            int leastSeen = regionVisibilityTracking.findMostLikelyLeastSeenRegion(rmgr.maxRegionIndex());
            if (leastSeen == -1) {
                leastSeen = findFurthestRegion(rmgr, chunkPos.x, chunkPos.y, chunkPos.z);
            }
            if (leastSeen != -1) {
                removeRegion(leastSeen);
            }
        }
    }

    //Fallback eviction target when no region has accumulated visibility samples yet: the region
    //furthest from the camera, which is the cheapest to drop and re-stream.
    private int findFurthestRegion(RegionManager rm, int camChunkX, int camChunkY, int camChunkZ) {
        int furthest = -1;
        int maxDist = -1;
        int maxIndex = rm.maxRegionIndex();
        for (int i = 0; i < maxIndex; i++) {
            if (!rm.regionExists(i)) continue;
            int dist = rm.distance(i, camChunkX, camChunkY, camChunkZ);
            if (dist > maxDist) {
                maxDist = dist;
                furthest = i;
            }
        }
        return furthest;
    }

    private void update_allowed_memory() {
        if (Nvidium.config.automatic_memory) {
            max_geometry_memory = (glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) / 1024) + (sectionManager==null?0:sectionManager.terrainAreana.getMemoryUsed()/(1024*1024));
            max_geometry_memory -= 1024;//Minus 1gb of vram
            max_geometry_memory = Math.max(2048, max_geometry_memory);//Minimum 2 gb of vram
        } else {
            max_geometry_memory = Nvidium.config.max_geometry_memory;
        }
    }

    private void removeRegion(int id) {
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
        //The region id can be recycled by a later region; reset its CPU visibility bit and
        //GPU per-section visibility so the reused id does not inherit stale visibility state.
        regionVisibilityTracker.clear(id);
        if (Nvidium.config.enable_temporal_coherence) {
            glClearNamedBufferSubData(sectionVisibility.getId(), GL_R8UI, (long) id << 8, 255, GL_RED_INTEGER, GL_UNSIGNED_BYTE, CLEAR_ZERO);
        }
    }

    private void setRegionVisible(long rid) {
        glClearNamedBufferSubData(regionVisibility.getId(), GL_R8UI, rid, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, CLEAR_ONE);
    }

    private void setSectionVisible(int cx, int cy, int cz) {
        int rid = sectionManager.getRegionManager().regionKeyToId(RegionManager.getRegionKey(cx, cy, cz));
        if (rid != -1) {
            int id = sectionManager.getSectionRegionIndex(cx, cy, cz);
            if (id != -1) {
                id |= rid << 8;
                glClearNamedBufferSubData(sectionVisibility.getId(), GL_R8UI, id, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, CLEAR_MINUS_ONE);
            }
        }
    }


    //Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void renderTranslucent() {

        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Need to rebind the uniform since it might have been wiped
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, sceneUniform.getDeviceAddress(), SCENE_SIZE);

        //Translucency sorting
        {
            glEnable(GL_DEPTH_TEST);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
            translucencyTerrainRasterizer.raster(prevRegionCount, terrainCommandBuffer.getDeviceAddress());
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            glDisable(GL_DEPTH_TEST);
        }

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
    }

    public void delete() {
        sectionManager.delete();
        regionVisibilityTracking.delete();

        sceneUniform.delete();
        regionVisibility.delete();
        sectionVisibility.delete();
        terrainCommandBuffer.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();

        downloadStream.delete();
        uploadStream.delete();
    }

    public int getOtherBufferSizesMB() {
        return bufferSizesMB;
    }

    public void addDebugInfo(List<String> info) {
        info.add("Using nvidium renderer: "+ Nvidium.MOD_VERSION);
        info.add("Other Memory MB: " + getOtherBufferSizesMB());
        info.add("Memory limit MB: " + max_geometry_memory);
        info.add("Terrain Memory MB: " + sectionManager.terrainAreana.getAllocatedMB()+(Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?"":" (fallback mode)"));
        info.add(String.format("Fragmentation: %.2f", sectionManager.terrainAreana.getFragmentation()*100));
        info.add("Regions: " + sectionManager.getRegionManager().regionCount() + "/" + sectionManager.getRegionManager().maxRegions());

    }
}