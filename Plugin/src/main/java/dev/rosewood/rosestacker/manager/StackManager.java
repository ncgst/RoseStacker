package dev.rosewood.rosestacker.manager;

import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.manager.Manager;
import dev.rosewood.rosestacker.manager.ConfigurationManager.Setting;
import dev.rosewood.rosestacker.stack.StackedBlock;
import dev.rosewood.rosestacker.stack.StackedEntity;
import dev.rosewood.rosestacker.stack.StackedItem;
import dev.rosewood.rosestacker.stack.StackedSpawner;
import dev.rosewood.rosestacker.stack.StackingLogic;
import dev.rosewood.rosestacker.stack.StackingThread;
import dev.rosewood.rosestacker.stack.settings.BlockStackSettings;
import dev.rosewood.rosestacker.stack.settings.SpawnerStackSettings;
import dev.rosewood.rosestacker.utils.PersistentDataUtils;
import dev.rosewood.rosestacker.utils.QueryUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages StackingThreads
 */
public class StackManager extends Manager implements StackingLogic {

    private final Map<UUID, StackingThread> stackingThreads;
    private final ConversionManager conversionManager;

    private BukkitTask pendingChunkTask, autosaveTask;
    private final Map<Chunk, Long> pendingLoadChunks;
    private volatile boolean processingChunks;
    private long processingChunksTime;

    private boolean isEntityStackingTemporarilyDisabled;
    private boolean isEntityUnstackingTemporarilyDisabled;

    public StackManager(RosePlugin rosePlugin) {
        super(rosePlugin);

        this.stackingThreads = new ConcurrentHashMap<>();
        this.conversionManager = this.rosePlugin.getManager(ConversionManager.class);
        this.pendingLoadChunks = new ConcurrentHashMap<>();

        this.isEntityStackingTemporarilyDisabled = false;
    }

    @Override
    public void reload() {
        // Load a new StackingThread per world
        Bukkit.getWorlds().forEach(this::loadWorld);

        if (Setting.LEGACY_DATA_MIGRATION.getBoolean())
            this.pendingChunkTask = Bukkit.getScheduler().runTaskTimer(this.rosePlugin, this::processPendingChunks, 0L, 3L);
        this.processingChunks = false;
        this.processingChunksTime = System.currentTimeMillis();

        // Load all existing stacks
        for (StackingThread stackingThread : this.stackingThreads.values()) {
            for (Chunk chunk : stackingThread.getTargetWorld().getLoadedChunks()) {
                stackingThread.loadChunk(chunk, chunk.getEntities());
                this.pendingLoadChunks.put(chunk, System.nanoTime());
            }
        }

        // Kick off autosave task if enabled
        long autosaveFrequency = Setting.AUTOSAVE_FREQUENCY.getLong();
        if (autosaveFrequency > 0) {
            long interval = autosaveFrequency * 20 * 60;
            this.autosaveTask = Bukkit.getScheduler().runTaskTimer(this.rosePlugin, this::saveAllData, interval, interval);
        }
    }

    @Override
    public void disable() {
        if (this.pendingChunkTask != null) {
            this.pendingChunkTask.cancel();
            this.pendingChunkTask = null;
        }

        if (this.autosaveTask != null) {
            this.autosaveTask.cancel();
            this.autosaveTask = null;
        }

        DataManager dataManager = this.rosePlugin.getManager(DataManager.class);
        if (!dataManager.isConnected()) {
            this.stackingThreads.clear();
            return;
        }

        this.pendingLoadChunks.clear();

        // Save anything that's loaded
        for (StackingThread stackingThread : this.stackingThreads.values())
            for (Chunk chunk : stackingThread.getTargetWorld().getLoadedChunks())
                stackingThread.saveChunk(chunk, true);

        // Close and clear StackingThreads
        this.stackingThreads.values().forEach(StackingThread::close);
        this.stackingThreads.clear();
    }

    @Override
    public Map<UUID, StackedEntity> getStackedEntities() {
        Map<UUID, StackedEntity> stackedEntities = new HashMap<>();
        this.stackingThreads.values().forEach(x -> stackedEntities.putAll(x.getStackedEntities()));
        return stackedEntities;
    }

    @Override
    public Map<UUID, StackedItem> getStackedItems() {
        Map<UUID, StackedItem> stackedItems = new HashMap<>();
        this.stackingThreads.values().forEach(x -> stackedItems.putAll(x.getStackedItems()));
        return stackedItems;
    }

    @Override
    public Map<Block, StackedBlock> getStackedBlocks() {
        Map<Block, StackedBlock> stackedBlocks = new HashMap<>();
        this.stackingThreads.values().forEach(x -> stackedBlocks.putAll(x.getStackedBlocks()));
        return stackedBlocks;
    }

    @Override
    public Map<Block, StackedSpawner> getStackedSpawners() {
        Map<Block, StackedSpawner> stackedSpawners = new HashMap<>();
        this.stackingThreads.values().forEach(x -> stackedSpawners.putAll(x.getStackedSpawners()));
        return stackedSpawners;
    }

    @Override
    public List<StackedSpawner> getStackedSpawnersList() {
        List<StackedSpawner> stackedSpawners = new ArrayList<>();
        this.stackingThreads.values().forEach(x -> stackedSpawners.addAll(x.getStackedSpawnersList()));
        return stackedSpawners;
    }

    @Override
    public StackedEntity getStackedEntity(LivingEntity livingEntity) {
        StackingThread stackingThread = this.getStackingThread(livingEntity.getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.getStackedEntity(livingEntity);
    }

    @Override
    public StackedItem getStackedItem(Item item) {
        StackingThread stackingThread = this.getStackingThread(item.getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.getStackedItem(item);
    }

    @Override
    public StackedBlock getStackedBlock(Block block) {
        StackingThread stackingThread = this.getStackingThread(block.getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.getStackedBlock(block);
    }

    @Override
    public StackedSpawner getStackedSpawner(Block block) {
        StackingThread stackingThread = this.getStackingThread(block.getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.getStackedSpawner(block);
    }

    @Override
    public boolean isEntityStacked(LivingEntity livingEntity) {
        return this.getStackedEntity(livingEntity) != null;
    }

    @Override
    public boolean isItemStacked(Item item) {
        return this.getStackedItem(item) != null;
    }

    @Override
    public boolean isBlockStacked(Block block) {
        return this.getStackedBlock(block) != null;
    }

    @Override
    public boolean isSpawnerStacked(Block block) {
        return this.getStackedSpawner(block) != null;
    }

    @Override
    public void removeEntityStack(StackedEntity stackedEntity) {
        StackingThread stackingThread = this.getStackingThread(stackedEntity.getEntity().getWorld());
        if (stackingThread != null)
            stackingThread.removeEntityStack(stackedEntity);
    }

    @Override
    public void removeItemStack(StackedItem stackedItem) {
        StackingThread stackingThread = this.getStackingThread(stackedItem.getItem().getWorld());
        if (stackingThread != null)
            stackingThread.removeItemStack(stackedItem);
    }

    @Override
    public void removeBlockStack(StackedBlock stackedBlock) {
        StackingThread stackingThread = this.getStackingThread(stackedBlock.getBlock().getWorld());
        if (stackingThread != null)
            stackingThread.removeBlockStack(stackedBlock);
    }

    @Override
    public void removeSpawnerStack(StackedSpawner stackedSpawner) {
        StackingThread stackingThread = this.getStackingThread(stackedSpawner.getSpawner().getWorld());
        if (stackingThread != null)
            stackingThread.removeSpawnerStack(stackedSpawner);
    }

    @Override
    public int removeAllEntityStacks() {
        int total = 0;
        for (StackingThread stackingThread : this.stackingThreads.values())
            total += stackingThread.removeAllEntityStacks();
        return total;
    }

    @Override
    public int removeAllItemStacks() {
        int total = 0;
        for (StackingThread stackingThread : this.stackingThreads.values())
            total += stackingThread.removeAllItemStacks();
        return total;
    }

    @Override
    public void updateStackedEntityKey(LivingEntity oldKey, LivingEntity newKey) {
        StackingThread stackingThread = this.getStackingThread(newKey.getWorld());
        if (stackingThread != null)
            stackingThread.updateStackedEntityKey(oldKey, newKey);
    }

    @Override
    public StackedEntity splitEntityStack(StackedEntity stackedEntity) {
        StackingThread stackingThread = this.getStackingThread(stackedEntity.getEntity().getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.splitEntityStack(stackedEntity);
    }

    @Override
    public StackedItem splitItemStack(StackedItem stackedItem, int newSize) {
        StackingThread stackingThread = this.getStackingThread(stackedItem.getItem().getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.splitItemStack(stackedItem, newSize);
    }

    @Override
    public StackedEntity createEntityStack(LivingEntity livingEntity, boolean tryStack) {
        StackingThread stackingThread = this.getStackingThread(livingEntity.getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.createEntityStack(livingEntity, tryStack);
    }

    @Override
    public StackedItem createItemStack(Item item, boolean tryStack) {
        StackingThread stackingThread = this.getStackingThread(item.getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.createItemStack(item, tryStack);
    }

    @Override
    public StackedBlock createBlockStack(Block block, int amount) {
        StackingThread stackingThread = this.getStackingThread(block.getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.createBlockStack(block, amount);
    }

    @Override
    public StackedSpawner createSpawnerStack(Block block, int amount, boolean placedByPlayer) {
        StackingThread stackingThread = this.getStackingThread(block.getWorld());
        if (stackingThread == null)
            return null;

        return stackingThread.createSpawnerStack(block, amount, placedByPlayer);
    }

    @Override
    public void addEntityStack(StackedEntity stackedEntity) {
        StackingThread stackingThread = this.getStackingThread(stackedEntity.getEntity().getWorld());
        if (stackingThread == null)
            return;

        stackingThread.addEntityStack(stackedEntity);
    }

    @Override
    public void addItemStack(StackedItem stackedItem) {
        StackingThread stackingThread = this.getStackingThread(stackedItem.getItem().getWorld());
        if (stackingThread == null)
            return;

        stackingThread.addItemStack(stackedItem);
    }

    @Override
    public void preStackEntities(EntityType entityType, int amount, Location location, SpawnReason spawnReason) {
        World world = location.getWorld();
        if (world == null)
            return;

        StackingThread stackingThread = this.getStackingThread(world);
        if (stackingThread == null)
            return;

        stackingThread.preStackEntities(entityType, amount, location, spawnReason);
    }

    @Override
    public void preStackEntities(EntityType entityType, int amount, Location location) {
        this.preStackEntities(entityType, amount, location, SpawnReason.CUSTOM);
    }

    @Override
    public void preStackItems(Collection<ItemStack> items, Location location) {
        World world = location.getWorld();
        if (world == null)
            return;

        StackingThread stackingThread = this.getStackingThread(world);
        if (stackingThread == null)
            return;

        stackingThread.preStackItems(items, location);
    }

    public boolean isEntityStackingEnabled() {
        return Setting.ENTITY_STACKING_ENABLED.getBoolean() && !this.conversionManager.isEntityStackingLocked();
    }

    public boolean isItemStackingEnabled() {
        return Setting.ITEM_STACKING_ENABLED.getBoolean() && !this.conversionManager.isItemStackingLocked();
    }

    public boolean isBlockStackingEnabled() {
        return Setting.BLOCK_STACKING_ENABLED.getBoolean() && !this.conversionManager.isBlockStackingLocked();
    }

    public boolean isSpawnerStackingEnabled() {
        return Setting.SPAWNER_STACKING_ENABLED.getBoolean() && !this.conversionManager.isSpawnerStackingLocked();
    }

    /**
     * Gets a StackingThread for a World
     *
     * @param world the World
     * @return a StackingThread for the World, otherwise null if one doesn't exist
     */
    public StackingThread getStackingThread(World world) {
        return this.stackingThreads.get(world.getUID());
    }

    /**
     * @return a Map of key -> World UUID, value -> StackingThread of all StackingThreads
     */
    public Map<UUID, StackingThread> getStackingThreads() {
        return this.stackingThreads;
    }

    /**
     * Creates a StackingThread for the given World
     *
     * @param world to create a StackingThread for
     */
    public void loadWorld(World world) {
        if (this.isWorldDisabled(world) || this.stackingThreads.containsKey(world.getUID()))
            return;

        this.stackingThreads.put(world.getUID(), new StackingThread(this.rosePlugin, this, world));
    }

    /**
     * Removes a World's StackingThread
     *
     * @param world to remove the StackingThread of
     */
    public void unloadWorld(World world) {
        UUID worldUUID = world.getUID();
        StackingThread stackingThread = this.stackingThreads.get(worldUUID);
        if (stackingThread != null) {
            stackingThread.close();
            this.stackingThreads.remove(worldUUID);
        }
    }

    /**
     * Loads all stacks from a chunk
     *
     * @param chunk the target chunk
     * @param entities the chunk entities
     */
    public void loadChunk(Chunk chunk, Entity[] entities) {
        StackingThread stackingThread = this.getStackingThread(chunk.getWorld());
        if (stackingThread != null) {
            stackingThread.loadChunk(chunk, entities);
            if (Setting.LEGACY_DATA_MIGRATION.getBoolean())
                this.pendingLoadChunks.put(chunk, System.nanoTime());
        }
    }

    /**
     * Saves all stacks in a chunk and unloads them
     *
     * @param chunk the target chunk
     */
    public void saveChunk(Chunk chunk) {
        StackingThread stackingThread = this.getStackingThread(chunk.getWorld());
        if (stackingThread != null)
            stackingThread.saveChunk(chunk, true);
    }

    /**
     * Saves all data in loaded chunks
     */
    public void saveAllData() {
        for (StackingThread stackingThread : this.stackingThreads.values())
            for (Chunk chunk : stackingThread.getTargetWorld().getLoadedChunks())
                stackingThread.saveChunk(chunk, false);
    }

    /**
     * Checks if a given block type is able to be stacked
     *
     * @param block The block to check
     * @return true if the block is stackable, otherwise false
     */
    public boolean isBlockTypeStackable(Block block) {
        BlockStackSettings stackSettings = this.rosePlugin.getManager(StackSettingManager.class).getBlockStackSettings(block);
        return stackSettings != null && stackSettings.isStackingEnabled();
    }

    /**
     * Checks if a given entity type for a spawner is able to be stacked
     *
     * @param entityType the type to check
     * @return true if the spawner entity type is stackable, otherwise false
     */
    public boolean isSpawnerTypeStackable(EntityType entityType) {
        SpawnerStackSettings stackSettings = this.rosePlugin.getManager(StackSettingManager.class).getSpawnerStackSettings(entityType);
        return stackSettings != null && stackSettings.isStackingEnabled();
    }

    /**
     * Checks if stacking is disabled in a given World
     *
     * @param world the World to check
     * @return true if stacking is disabled in the World, otherwise false
     */
    public boolean isWorldDisabled(World world) {
        if (world == null)
            return true;
        return Setting.DISABLED_WORLDS.getStringList().stream().anyMatch(x -> x.equalsIgnoreCase(world.getName()));
    }

    public void changeStackingThread(UUID entityUUID, StackedEntity stackedEntity, World from, World to) {
        StackingThread fromThread = this.getStackingThread(from);
        StackingThread toThread = this.getStackingThread(to);

        if (fromThread == null || toThread == null)
            return;

        fromThread.transferExistingEntityStack(entityUUID, stackedEntity, toThread);
    }

    /**
     * Processes chunks that are either pending load or unload
     */
    private void processPendingChunks() {
        // This is here just for safety, it should hopefully never be used
        if (this.processingChunks && System.currentTimeMillis() - this.processingChunksTime >= 10000)
            this.processingChunks = false;

        if (this.processingChunks)
            return;

        if (!this.pendingLoadChunks.isEmpty()) {
            List<Chunk> chunks = new ArrayList<>();
            List<Chunk> convertChunks = new ArrayList<>();
            Map<UUID, Entity> chunkEntities = new HashMap<>();
            List<Entity> convertChunkEntities = new ArrayList<>();

            for (Chunk chunk : this.pendingLoadChunks.keySet()) {
                if (!chunk.isLoaded())
                    continue;

                Map<UUID, Entity> entities = null;
                if (!PersistentDataUtils.isChunkMigrated(chunk)) {
                    chunks.add(chunk);
                    entities = new HashMap<>();
                    for (Entity entity : chunk.getEntities())
                        entities.put(entity.getUniqueId(), entity);

                    chunkEntities.putAll(entities);
                    PersistentDataUtils.setChunkMigrated(chunk);
                }

                if (this.conversionManager.hasConversions() && !PersistentDataUtils.isChunkConverted(chunk)) {
                    convertChunks.add(chunk);
                    if (entities != null) {
                        convertChunkEntities.addAll(entities.values());
                    } else {
                        convertChunkEntities.addAll(Arrays.asList(chunk.getEntities()));
                    }
                    PersistentDataUtils.setChunkConverted(chunk);
                }
            }

            if (chunks.isEmpty() && convertChunks.isEmpty())
                return;

            this.processingChunks = true;
            this.processingChunksTime = System.currentTimeMillis();

            Bukkit.getScheduler().runTaskAsynchronously(this.rosePlugin, () -> {
                if (!convertChunkEntities.isEmpty())
                    this.conversionManager.convertChunkEntities(convertChunkEntities);

                if (!chunks.isEmpty()) {
                    DataManager dataManager = this.rosePlugin.getManager(DataManager.class);
                    String chunkQuery = QueryUtils.buildChunksWhere(chunks);
                    if (this.isEntityStackingEnabled()) {
                        dataManager.getStackedEntities(chunks, chunkEntities, chunkQuery, stacks -> {
                            for (StackedEntity stack : stacks) {
                                StackingThread stackingThread = this.getStackingThread(stack.getWorld());
                                if (stackingThread != null)
                                    stackingThread.putStackedEntity(stack);
                            }
                        });
                    }

                    if (this.isItemStackingEnabled()) {
                        dataManager.getStackedItems(chunks, chunkEntities, chunkQuery, stacks -> {
                            for (StackedItem stack : stacks) {
                                StackingThread stackingThread = this.getStackingThread(stack.getWorld());
                                if (stackingThread != null)
                                    stackingThread.putStackedItem(stack);
                            }
                        });
                    }

                    if (this.isBlockStackingEnabled()) {
                        dataManager.getStackedBlocks(chunks, chunkQuery, stacks -> {
                            for (StackedBlock stack : stacks) {
                                StackingThread stackingThread = this.getStackingThread(stack.getWorld());
                                if (stackingThread != null)
                                    stackingThread.putStackedBlock(stack);
                            }
                        });
                    }

                    if (this.isSpawnerStackingEnabled()) {
                        dataManager.getStackedSpawners(chunks, chunkQuery, stacks -> {
                            for (StackedSpawner stack : stacks) {
                                StackingThread stackingThread = this.getStackingThread(stack.getWorld());
                                if (stackingThread != null)
                                    stackingThread.putStackedSpawner(stack);
                            }
                        });
                    }
                }

                this.processingChunks = false;
            });

            this.pendingLoadChunks.clear();
        }
    }

    public void processNametags() {
        for (StackingThread stackingThread : this.stackingThreads.values())
            stackingThread.processNametags();
    }

    /**
     * Toggles instant entity stacking as temporarily disabled to allow for entity manipulation without
     * stacks automatically being created.
     *
     * @param disabled true to disable, otherwise false to enable
     */
    public void setEntityStackingTemporarilyDisabled(boolean disabled) {
        this.isEntityStackingTemporarilyDisabled = disabled;
    }

    /**
     * Toggles entity unstacking as temporarily disabled to allow for entity manipulation without stacks
     * automatically unstacking.
     *
     * @param disabled true to disable, otherwise false to enable
     */
    public void setEntityUnstackingTemporarilyDisabled(boolean disabled) {
        this.isEntityUnstackingTemporarilyDisabled = disabled;
    }

    /**
     * @return true if instant entity stacking is temporarily disabled, otherwise false
     */
    public boolean isEntityStackingTemporarilyDisabled() {
        return this.isEntityStackingTemporarilyDisabled;
    }

    /**
     * @return true if entity unstacking is temporarily disabled, otherwise false
     */
    public boolean isEntityUnstackingTemporarilyDisabled() {
        return this.isEntityUnstackingTemporarilyDisabled;
    }

}
