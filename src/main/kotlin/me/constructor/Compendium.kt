package me.constructor

import net.querz.nbt.CompoundTag
import net.querz.nbt.ListTag
import net.querz.nbt.io.snbt.SNBTWriter
import java.io.File
import kotlin.system.exitProcess

class Compendium(
    inputDir: String = "save",
    mappingFile: String = "hash-mapping.json",
    private val outputDir: String = "remapped",
    private val dumpIDs: Boolean = false
) {
    private val splash = """
        ┏┓            ┓•     
        ┃ ┏┓┏┳┓┏┓┏┓┏┓┏┫┓┓┏┏┳┓
        ┗┛┗┛┛┗┗┣┛┗ ┛┗┗┻┗┗┻┛┗┗
          v1.4 ┛ by Constructor
    """.trimIndent()
    private val dbHandler = DatabaseHandler(mappingFile)
    private val inputFile = File(inputDir)
    private val outputFile = File(outputDir)
    private val mapFile = File(inputDir, "data")
    private val levelDat = File(outputDir, "level.dat")
    private val playerData = File(outputDir, "playerdata")
    private val notFoundFile = File(outputDir, "ids-not-found.txt")
    private val foundFile = File(outputDir, "ids-found.txt")
    private val mapDatFiles = run {
        if (dumpIDs) {
            println("DEBUG: Looking for map files in ${mapFile.absolutePath}")
            println("DEBUG: mapFile exists: ${mapFile.exists()}, isDirectory: ${mapFile.isDirectory}")
            if (mapFile.exists() && mapFile.isDirectory) {
                mapFile.listFiles()?.forEach { println("DEBUG: Found file: ${it.name}") }
            }
        }
        mapFile.listFiles { file ->
            file.name.startsWith("map_") && file.extension == "dat"
        } ?: throw IllegalStateException("No map files found in directory: ${mapFile.absolutePath}")
    }
    private val mcaFiles: List<File> by lazy {
        outputFile.walk().filter { it.isFile && it.extension == "mca" }.toList()
        // ToDo: Respect mcc files
    }
    private val writer = SNBTWriter()
    private val mapMapping = mutableMapOf<Int, Int>()
    private var remapped = 0

    init {
        println(splash + "\n")
        writer.indent("  ")

        copySave()
        updateMapHashDatabase()

        if (mapMapping.isEmpty()) {
            println("No map data found in given directory. Exiting...")
            exitProcess(0)
        }

        remapMapFiles()
        remapRegionFiles()
        remapPlayerData()
        remapLevelDat()

        println("Successfully remapped $remapped map items from ${inputFile.name} to ${outputFile.name}!")
    }

    private fun copySave() {
        inputFile.copyRecursively(outputFile, true)

        File(outputFile, "data").listFiles { it ->
            it.name.startsWith("map_") && it.extension == "dat"
        }?.forEach { it.delete() }
    }

    private fun updateMapHashDatabase() {
        println("Found ${mapDatFiles.size} maps in given directory.")

        "Updating map hash database".toProgress(mapDatFiles.size.toLong()).use { progressBar ->
            mapDatFiles.forEach { mapDat ->
                progressBar.step()
                val currentMapId = mapDat.mapId()

                mapDat.readNBT {
                    val data = getCompoundTag("data") ?: return@readNBT
                    val mapHash = data.getByteArray("colors").digestHex()

                    dbHandler.registerMapAllocation(mapHash, currentMapId)
                    mapMapping[currentMapId] = dbHandler.getMapId(mapHash)
                }
            }
        }

        println("Generated ${mapMapping.size} map ID mappings.")
    }

    private fun remapMapFiles() {
        "Remapping map files".toProgress(mapDatFiles.size.toLong()).use { progressBar ->
            mapDatFiles.forEach { mapDat ->
                progressBar.step()
                val currentMapId = mapDat.mapId()
                val newMapId = mapMapping[currentMapId] ?: throw IllegalStateException("No mapping found for map ID $currentMapId")

                val newMapDat = File(outputDir, "data/map_$newMapId.dat")
                mapDat.copyTo(newMapDat, true)
            }
        }
        println("\nRemapped ${mapDatFiles.size} map files.")
    }

    private fun remapRegionFiles() {
        println("Found ${mcaFiles.size} region files in given directory.")
        "Remapping region files".toProgress(mcaFiles.size.toLong(), " chunks").use { progressBar ->
            mcaFiles.forEach { mcaFile ->
                progressBar.step()
                progressBar.extraMessage = " ${mcaFile.name}"
                if (mcaFile.parentFile.name in listOf("poi")) return@forEach
                if (mcaFile.length() == 0L) return@forEach

                mcaFile.toMCA().use {
                    when (mcaFile.parentFile.name) {
                        "region" -> {
                            filterNotNull().forEach { chunk ->
                                chunk.data.remapChunk()
                            }
                        }
                        "entities" -> {
                            filterNotNull().forEach { chunk ->
                                // Try both capitalization formats for entities
                                val entitiesProcessed = chunk.data.getListTag("Entities")?.remapEntities() ?: 0
                                val entitiesProcessedNew = chunk.data.getListTag("entities")?.remapEntities() ?: 0
                                
                                if (entitiesProcessed + entitiesProcessedNew == 0 && dumpIDs) {
                                    println("WARNING: No entities found in ${mcaFile.name} - this may indicate a format change")
                                }
                            }
                        }
                    }
                }
            }
        }
        println("Remapped map data of ${mcaFiles.size} region files.")
    }

    private fun CompoundTag.remapChunk() {
        // Handle pre-1.21 formats
        getCompoundTag("Level")?.let { level ->
            level.getListTag("TileEntities")?.remapEntities()
            level.getListTag("Entities")?.remapEntities()
        }
        getListTag("block_entities")?.remapEntities()
        
        // Handle Minecraft 1.21+ formats with snake_case
        getCompoundTag("level")?.let { level ->
            level.getListTag("tile_entities")?.remapEntities()
            level.getListTag("entities")?.remapEntities()
        }
        getListTag("block_entities")?.remapEntities()
        getListTag("tile_entities")?.remapEntities()
        getListTag("entities")?.remapEntities()
        
        // Specific handling for 1.21.1 - look for item frames in all entity lists
        val entityLists = mutableListOf<ListTag>()
        
        // Collect all entity lists from possible locations
        getCompoundTag("Level")?.getListTag("Entities")?.let { entityLists.add(it) }
        getCompoundTag("level")?.getListTag("entities")?.let { entityLists.add(it) }
        getListTag("entities")?.let { entityLists.add(it) }
        
        // Process item frames specifically to ensure they're updated
        entityLists.forEach { entityList ->
            entityList.filterIsInstance<CompoundTag>().forEach { entity ->
                val entityId = entity.getStringValue("id")
                if (entityId == "minecraft:item_frame" || entityId == "minecraft:glow_item_frame") {
                    if (dumpIDs) println("DEBUG: Processing item frame entity in chunk")
                    
                    // Find the item field - either "Item" or "item"
                    val itemComp = entity.getCompoundValue("Item") ?: entity.getCompoundValue("item")
                    
                    if (itemComp != null) {
                        // Process the item
                        val itemId = itemComp.getStringValue("id")
                        if (itemId == "minecraft:filled_map") {
                            if (dumpIDs) println("DEBUG: Found map in item frame - ensuring it gets processed")
                            itemComp.remapItem()
                        } else {
                            if (dumpIDs) println("DEBUG: Item frame contains non-map item: $itemId")
                        }
                    } else {
                        // Check if this is potentially a 1.21.1 item frame with a map
                        val has121MapField = entity.getBooleanValue("has_map") ?: false
                        val hasItemID = entity.getIntValue("item_id") != null
                        
                        if (has121MapField || hasItemID) {
                            if (dumpIDs) println("DEBUG: Item frame has indicators of containing a map in 1.21.1 format")
                            entity.remapEntity()
                        } else {
                            if (dumpIDs) println("DEBUG: Item frame appears to be empty, no processing needed")
                        }
                    }
                }
            }
        }
    }

    private fun remapPlayerData() {
        val playerFiles = playerData.listFiles { file ->
            file.extension == "dat"
        } ?: return
        "Remapping player data".toProgress(playerFiles.size.toLong(), " players").use { progressBar ->
            playerFiles.forEach { playerDat ->
                progressBar.step()
                playerDat.useNBT {
                    remapInventory()
                }
            }
        }
        println("Remapped map data of ${playerFiles.size} player data files.")
    }

    private fun remapLevelDat() {
        levelDat.useGzipNBT {
            getCompoundTag("Data")?.let { data ->
                data.getCompoundTag("Player")?.remapInventory()
            }
        }
        println("Remapped map data of level.dat.")
    }

    private fun CompoundTag.remapInventory() {
        getListTag("Inventory")?.let { inventory ->
            inventory.filterIsInstance<CompoundTag>().forEach { item ->
                item.remapItem()
            }
        }
    }

    private fun ListTag.remapEntities(): Int {
        val entities = filterIsInstance<CompoundTag>()
        entities.forEach { entity ->
            entity.remapEntity()
        }
        return entities.size
    }

    private fun CompoundTag.remapEntity() {
        // Item lying on the ground (both old and new formats)
        getCompoundTag("Item")?.remapItem()
        getCompoundTag("item")?.remapItem()

        // Lockable Container (both old and new formats)
        getListTag("Items")?.remapItems()
        getListTag("items")?.remapItems()

        // Pre-1.21 format
        getCompoundTag("HandItems")?.remapItems()
        getCompoundTag("ArmorItems")?.remapItems()
        
        // 1.20.5-1.21.4 format
        getCompoundTag("hand_items")?.remapItems()
        getCompoundTag("armor_items")?.remapItems()
        getCompoundTag("body_armor_item")?.remapItem()
        
        // 1.21.5+ consolidated format
        getCompoundTag("equipment")?.remapItems()
        
        // Handle item frames specifically (can have many different structures)
        // Check for item frame entities specifically to handle their map items
        val entityId = getStringValue("id")
        if (entityId == "minecraft:item_frame" || entityId == "minecraft:glow_item_frame") {
            if (dumpIDs) println("DEBUG: Found item frame entity directly")
            
            // Check if this item frame has the structure we expect for 1.21.1
            val has121MapField = getBooleanValue("has_map") ?: false
            val hasItemID = getIntValue("item_id") != null
            
            // Handle the 1.21.1 format where map ID is in item/components/map_id
            if (has121MapField || hasItemID) {
                if (dumpIDs) {
                    println("DEBUG: Found item frame with 1.21.1 map structure")
                }
                
                // Get the original map ID
                val originalMapId = getIntValue("item_id")
                
                if (originalMapId != null) {
                    // Use the mapping to remap this ID
                    val remappedId = mapMapping[originalMapId]
                    
                    if (remappedId == null) {
                        if (dumpIDs) {
                            notFoundFile.appendText("$originalMapId\n")
                            println("DEBUG: Map ID: $originalMapId not found in mapping database. Using negative value.")
                        }
                        println("Map ID: $originalMapId not found in mapping database. Using negative value.")
                        
                        // Use negative value for unmapped IDs in traditional fields
                        val negativeId = -originalMapId
                        
                        // Update item_id field
                        putInt("item_id", negativeId)
                        
                        // In 1.21.1, update map_id field if it exists - use negative value for consistency
                        getIntValue("map_id")?.let {
                            putInt("map_id", negativeId) // Use negative ID for consistency
                        }
                        
                        // Make sure has_map is set correctly
                        putBoolean("has_map", true)
                        
                        // Now update the map ID in the components structure if it exists
                        getCompoundValue("item")?.let { itemComp ->
                            itemComp.getCompoundValue("components")?.let { components ->
                                if (dumpIDs) println("DEBUG: Updating map ID in item/components/minecraft:map_id structure to negative value")
                                components.putInt("minecraft:map_id", negativeId) // Use negative value for consistency
                            }
                        }
                    } else {
                        if (dumpIDs) {
                            foundFile.appendText("$originalMapId -> $remappedId\n")
                            println("DEBUG: Successfully remapped item_id from $originalMapId to $remappedId")
                        }
                        
                        // Update item_id field
                        putInt("item_id", remappedId)
                        
                        // In 1.21.1, update map_id field if it exists
                        getIntValue("map_id")?.let {
                            putInt("map_id", Math.abs(remappedId))
                        }
                        
                        // Make sure has_map is set correctly
                        putBoolean("has_map", true)
                        
                        // Now update the map ID in the components structure if it exists
                        getCompoundValue("item")?.let { item ->
                            // Check for and update the map ID in components structure
                            val components = item.getCompoundValue("components") ?: CompoundTag().also {
                                if (dumpIDs) println("DEBUG: Creating components tag for item")
                                item.put("components", it)
                            }
                            
                            // Update the map_id in components
                            if (dumpIDs) println("DEBUG: Updating map ID in item/components/minecraft:map_id structure")
                            components.putInt("minecraft:map_id", remappedId)
                        }
                        
                        remapped++
                    }
                } else if (has121MapField) {
                    // Has map but no item_id field, need to assign a reasonable ID
                    if (dumpIDs) {
                        println("DEBUG: Item frame has has_map=true but no item_id field")
                    }
                    
                    // Use position and other identifiers to determine a map ID
                    val pos = getPositionHash()
                    val rotation = getIntValue("ItemRotation") ?: getIntValue("item_rotation") ?: 0
                    val facing = getIntValue("Facing") ?: getIntValue("facing") ?: 0
                    
                    val posKey = "${pos ?: "unknown"}_${rotation}_${facing}"
                    val mapId = processedItemCoordinates[posKey] ?: run {
                        // If not seen this position before, assign from mapMapping
                        val nextMapId = mapMapping.keys.elementAtOrNull(mapIdCounter % mapMapping.size) 
                            ?: mapMapping.keys.firstOrNull() 
                            ?: 0
                        processedItemCoordinates[posKey] = nextMapId
                        mapIdCounter++
                        nextMapId
                    }
                    
                    // Get the remapped ID
                    val remappedId = mapMapping[mapId] ?: mapId
                    
                    if (dumpIDs) {
                        println("DEBUG: Assigning map ID $remappedId to item frame with has_map=true")
                    }
                    
                    // Update item_id and map_id fields
                    putInt("item_id", remappedId)
                    putInt("map_id", remappedId)
                    putBoolean("has_map", true)
                    
                    // Check if there's an item with components structure and update it
                    getCompoundValue("item")?.let { item ->
                        // Check for and update the map ID in components structure
                        val components = item.getCompoundValue("components") ?: CompoundTag().also {
                            if (dumpIDs) println("DEBUG: Creating components tag for item")
                            item.put("components", it)
                        }
                        
                        // Update the map_id in components
                        if (dumpIDs) println("DEBUG: Updating map ID in item/components/minecraft:map_id structure")
                        components.putInt("minecraft:map_id", remappedId)
                    }
                    
                    remapped++
                }
            } else {
                // Check standard item structure (for older MC versions)
                val itemComp = getCompoundValue("Item") ?: getCompoundValue("item")
                if (itemComp != null) {
                    if (dumpIDs) println("DEBUG: Processing pre-1.21.1 item structure")
                    val itemId = itemComp.getStringValue("id")
                    if (itemId == "minecraft:filled_map") {
                        if (dumpIDs) println("DEBUG: Found map in item frame - processing via remapItem")
                        itemComp.remapItem()
                    } else {
                        if (dumpIDs) println("DEBUG: Item frame contains non-map item: $itemId")
                    }
                } else {
                    if (dumpIDs) {
                        println("DEBUG: Item frame appears to be empty, skipping")
                    }
                }
            }
        }
    }

    private fun ListTag.remapItems() {
        filterIsInstance<CompoundTag>().forEach { item ->
            item.remapItem()
        }
    }

    private fun CompoundTag.remapItems() {
        filterIsInstance<CompoundTag>().forEach { item ->
            item.remapItem()
        }
    }

    private fun CompoundTag.remapItem() {
        try {
            // Use our helper to check for item ID in both formats
            val itemId = getStringValue("id")
            
            // Add debug logging if dumping IDs is enabled
            if (dumpIDs && itemId != null) {
                println("DEBUG: Processing item with ID: $itemId")
                if (itemId == "minecraft:filled_map") {
                    println("DEBUG: Found filled map!")
                    // Log basic properties
                    val hasDamage = runCatching { getShortTag("Damage") != null }.getOrDefault(false)
                    val hasTag = runCatching { getCompoundTag("tag") != null }.getOrDefault(false)
                    val hasComponents = runCatching { getCompoundTag("components") != null }.getOrDefault(false)
                    val hasCounts = runCatching { getIntTag("count") != null }.getOrDefault(false)
                    println("DEBUG: Map has Damage: $hasDamage, has tag: $hasTag, has components: $hasComponents, has count: $hasCounts")
                    
                    runCatching {
                        // Check tag compound
                        val tagComp = getCompoundTag("tag")
                        if (tagComp != null) {
                            val hasMapField = runCatching { tagComp.getIntTag("map") != null }.getOrDefault(false)
                            val hasMapIdField = runCatching { tagComp.getIntTag("map_id") != null }.getOrDefault(false)
                            println("DEBUG: Map tag has map field: $hasMapField, has map_id field: $hasMapIdField")
                        }
                        
                        // Check components compound (1.21.1 format)
                        val componentsComp = getCompoundTag("components")
                        if (componentsComp != null) {
                            val hasMapIdField = runCatching { componentsComp.getIntTag("map_id") != null }.getOrDefault(false)
                            println("DEBUG: Map components has map_id field: $hasMapIdField")
                        }
                    }.onFailure { 
                        println("DEBUG: Error checking map fields: ${it.message}")
                    }
                }
            }
            
            // Early return if this isn't a map
            if (itemId != "minecraft:filled_map") return

            // 1. Check for 1.21.1 format with components/map_id
            runCatching {
                getCompoundValue("components")?.let { components ->
                    (components.getIntValue("map_id") ?: components.getIntValue("minecraft:map_id"))?.let { originalMapId ->
                        if (dumpIDs) println("DEBUG: Found 1.21.1 map with components/map_id or minecraft:map_id: $originalMapId")
                        
                        // Check if this ID is already a valid target ID in our database
                        val isTargetId = mapMapping.values.contains(originalMapId)
                        
                        if (isTargetId) {
                            if (dumpIDs) {
                                println("DEBUG: Map ID: $originalMapId is already a valid target ID, using as-is")
                            }
                            
                            // No change needed since this is already a remapped ID
                            // Update the map ID in the components structure to ensure it's in the right field
                            components.putInt("minecraft:map_id", originalMapId)
                            remapped++
                            return
                        }
                        
                        // Use the mapping to remap this ID (from source to target)
                        val remappedId = mapMapping[originalMapId]
                        
                        if (remappedId == null) {
                            if (dumpIDs) {
                                notFoundFile.appendText("$originalMapId\n")
                                println("DEBUG: Map ID: $originalMapId not found in mapping database. Using negative value.")
                            }
                            println("Map ID: $originalMapId not found in mapping database. Using negative value.")
                            
                            // Use negative value for unmapped maps (consistent with pre-1.21.1 behavior)
                            components.putInt("minecraft:map_id", -originalMapId)
                        } else {
                            if (dumpIDs) {
                                foundFile.appendText("$originalMapId -> $remappedId\n")
                                println("DEBUG: Successfully remapped components/map_id from $originalMapId to $remappedId")
                            }
                            
                            // Update the map ID in the components structure
                            components.putInt("minecraft:map_id", remappedId)
                            remapped++
                        }
                        
                        // Early return as we've handled this case
                        return
                    }
                }
            }
            
            // 2. Handle older format with Damage tag
            runCatching { 
                getShortTag("Damage")?.let {
                    if (dumpIDs) println("DEBUG: Found map with Damage: ${it.asShort()}")
                    remapOld(it.asShort())
                    return
                }
            }
            
            // 3. Handle pre-1.21 format with tag/map and 1.21+ format with tag/map_id
            runCatching {
                getCompoundValue("tag")?.let { tag ->
                    // First try the standard 'map' field
                    runCatching {
                        tag.getIntValue("map")?.let {
                            if (dumpIDs) println("DEBUG: Found map with tag/map: $it (source ID)")
                            tag.remapNew(it)
                            return
                        }
                    }
                    
                    // Then check for the map_id field which might be present in some 1.21.1 formats
                    runCatching {
                        tag.getIntValue("map_id")?.let {
                            if (dumpIDs) println("DEBUG: Found map with tag/map_id: $it (source ID)")
                            // Create a new 'map' field with the remapped value and remove the map_id field
                            tag.remapNew(it)
                            // Remove the old map_id field to avoid confusion
                            tag.remove("map_id")
                            return
                        }
                    }
                }
            }
            
            // 4. 1.21.1 may store map ID directly with the item (no tag)
            runCatching {
                getIntValue("map")?.let {
                    if (dumpIDs) println("DEBUG: Found map with direct map field: $it (source ID)")
                    remapNewDirect(it)
                    return
                }
            }
            
            // Check if there's a direct map_id field at the root level
            runCatching {
                getIntValue("map_id")?.let {
                    if (dumpIDs) println("DEBUG: Found map with direct map_id field: $it (source ID)")
                    remapNewDirect(it)
                    // Remove the map_id field to avoid confusion
                    remove("map_id")
                    return
                }
            }
            
            // 5. Special case for 1.21.1: try to extract map ID from parent container index
            runCatching {
                getIntValue("count")?.let {
                    val mapID = runCatching { findMapIDFromContext() }.getOrNull()
                    if (mapID != null) {
                        if (dumpIDs) println("DEBUG: Found map with contextual ID: $mapID")
                        
                        // Check if this is a 1.21.1 format item with components
                        if (containsKey("components") || (!containsKey("tag") && itemId == "minecraft:filled_map")) {
                            // This appears to be a 1.21.1 format map - update/create components
                            val components = getCompoundValue("components") ?: CompoundTag().also {
                                if (dumpIDs) println("DEBUG: Creating components tag for 1.21.1 format map")
                                put("components", it)
                            }
                            
                            // Get the remapped ID
                            val remappedId = mapMapping[mapID] ?: mapID
                            
                            // Update the map_id in components
                            if (dumpIDs) println("DEBUG: Updating minecraft:map_id in components to $remappedId")
                            components.putInt("minecraft:map_id", remappedId)
                            
                            // Make sure count field exists (1.21.1 requirement)
                            if (getIntValue("count") == null) {
                                putInt("count", 1)
                            }
                            
                            remapped++
                            return
                        } else {
                            // Standard format - use the direct remap method
                            remapNewDirect(mapID)
                            return
                        }
                    }
                }
            }
            
            // 6. If we're a map without any identifiable ID, we need to handle based on version format
            if (!containsKey("tag") && !containsKey("components") && itemId == "minecraft:filled_map") {
                if (dumpIDs) println("DEBUG: Map has no tag or components - determining format")
                
                // Use any position info or parent context to determine a unique map ID
                val pos = getPositionHash() ?: "unknown"
                val slotIndex = getIntValue("Slot") ?: getIntValue("slot") ?: 0
                
                val posKey = "${pos}_slot_${slotIndex}"
                val mapId = processedItemCoordinates[posKey] ?: run {
                    // If not seen this position before, assign from mapMapping
                    val nextMapId = mapMapping.keys.elementAtOrNull(mapIdCounter % mapMapping.size) 
                        ?: mapMapping.keys.firstOrNull() 
                        ?: 0
                    processedItemCoordinates[posKey] = nextMapId
                    mapIdCounter++
                    nextMapId
                }
                
                // Get the remapped ID
                val remappedId = mapMapping[mapId] ?: mapId
                
                // Check if this appears to be a 1.21.1 format map (has count but no tag)
                if (getIntValue("count") != null) {
                    if (dumpIDs) println("DEBUG: Appears to be 1.21.1 format map - creating components")
                    
                    // Create components structure for 1.21.1
                    val components = CompoundTag()
                    
                    // Check if this map ID actually exists in our mapping database as a target ID
                    val isTargetId = mapMapping.values.contains(remappedId)
                    
                    // Use the ID directly if it's a known target ID from our remapping
                    val mapIdToUse = if (isTargetId) {
                        // Use the ID directly since it's a valid remapped ID
                        remappedId
                    } else if (remappedId == mapId && !mapMapping.containsKey(mapId)) {
                        // Use negative value for unmapped maps (consistency with pre-1.21.1 behavior)
                        -mapId
                    } else {
                        remappedId
                    }
                    
                    components.putInt("minecraft:map_id", mapIdToUse)
                    put("components", components)
                } else {
                    if (dumpIDs) println("DEBUG: Creating tag for pre-1.21.1 format map")
                    
                    // Create tag for pre-1.21.1
                    val tagCompound = CompoundTag()
                    tagCompound.putInt("map", remappedId)
                    put("tag", tagCompound)
                    
                    // Make sure count field exists (1.21.1 requirement)
                    if (getIntValue("count") == null) {
                        putInt("count", 1)
                    }
                }
                
                if (dumpIDs) println("DEBUG: Created map structure with ID $remappedId")
                remapped++
                return
            }
            
            // If we get here, we couldn't find any map ID field
            if (dumpIDs) println("DEBUG: Found filled map but couldn't locate map ID in any known field!")
        } catch (e: Exception) {
            if (dumpIDs) println("DEBUG: Error processing map item: ${e.message}")
        }
    }
    
    private fun CompoundTag.remapNewDirect(mapId: Int) {
        // This method maps SOURCE IDs to TARGET IDs, but also handles IDs that are already targets
        if (dumpIDs) println("DEBUG: Remapping direct map field from ID: $mapId")
        
        // Check if this ID is already a valid target ID in our database
        val isTargetId = mapMapping.values.contains(mapId)
        
        // If the ID is already a target ID, we don't need to remap it
        val newID = if (isTargetId) {
            if (dumpIDs) println("DEBUG: ID $mapId is already a valid target ID, using as-is")
            mapId
        } else {
            mapMapping[mapId]
        }
        
        val negativeId = if (newID == null) -mapId else newID
        
        // Get the item ID to determine what format we're dealing with
        val itemId = getStringValue("id")
        
        // Determine if this is likely a 1.21.1 format item by checking for components or if it has count but no tag
        val is121Format = containsKey("components") || 
                          (itemId == "minecraft:filled_map" && getIntValue("count") != null && !containsKey("tag"))
        
        if (is121Format) {
            if (dumpIDs) println("DEBUG: Processing as 1.21.1 format map item")
            
            // For 1.21.1 format, we update the components/map_id field
            val components = getCompoundValue("components") ?: CompoundTag().also { 
                put("components", it)
                if (dumpIDs) println("DEBUG: Created new components compound for 1.21.1 map")
            }
            
            // Update the map_id in components
            if (newID == null) {
                // Use negative value for consistency with pre-1.21.1 behavior
                if (dumpIDs) println("DEBUG: Updating minecraft:map_id in components to negative value (-$mapId)")
                components.putInt("minecraft:map_id", -mapId) // Use negative value for consistency
            } else {
                if (dumpIDs) println("DEBUG: Updating minecraft:map_id in components to $negativeId")
                components.putInt("minecraft:map_id", Math.abs(negativeId))
            }
            
            // Make sure count field exists (1.21.1 requirement)
            if (getIntValue("count") == null) {
                putInt("count", 1)
                if (dumpIDs) println("DEBUG: Added count field to map item")
            }
        } else {
            if (dumpIDs) println("DEBUG: Processing as pre-1.21.1 format map item")
            
            // For pre-1.21.1 format, we update the tag/map field
            val tagCompound = getCompoundValue("tag") ?: CompoundTag().also { 
                put("tag", it)
                if (dumpIDs) println("DEBUG: Created new tag compound for pre-1.21.1 map")
            }
            
            // Store the map ID in the tag compound
            tagCompound.putInt("map", negativeId)
            
            // Ensure no map_id field exists to avoid confusion
            tagCompound.remove("map_id")
            
            // Add count field if needed (for compatibility)
            if (getIntValue("count") == null) {
                putInt("count", 1)
                if (dumpIDs) println("DEBUG: Added count field to map item")
            }
        }
        
        // Log the mapping result
        if (newID == null) {
            if (dumpIDs) {
                notFoundFile.appendText(mapId.toString() + "\n")
                println("DEBUG: Map ID: $mapId not found in mapping database. Used negative value.")
            }
            println("Map ID: $mapId not found in mapping database. Flipping to negative value.")
        } else {
            if (dumpIDs) {
                foundFile.appendText("$mapId -> $newID\n")
                println("DEBUG: Successfully remapped SOURCE ID $mapId -> TARGET ID $newID")
            }
            remapped++
        }
    }
    
    /**
     * In Minecraft 1.21.1, map IDs are sometimes not stored in the map item itself.
     * Instead, we need to use a combination of approaches to identify map IDs.
     */
    private var mapIdCounter = 0
    private val processedItemCoordinates = mutableMapOf<String, Int>()
    private val slotToMapIdMapping = mutableMapOf<Int, Int>()
    
    private fun CompoundTag.findMapIDFromContext(): Int? {
        // First check if this map has a tag with map or map_id field
        val tagCompound = getCompoundValue("tag")
        
        // Try the standard 'map' field first
        tagCompound?.getIntValue("map")?.let { mapId ->
            if (dumpIDs) println("DEBUG: Found map with standard map field in tag: $mapId (original source ID)")
            return mapId
        }
        
        // Also check for map_id field, which might be used in some 1.21.1 formats
        tagCompound?.getIntValue("map_id")?.let { mapId ->
            // IMPORTANT: This is a source map ID, not a target ID
            if (dumpIDs) println("DEBUG: Found map with map_id field in tag: $mapId (original source ID)")
            return mapId
        }
        
        // For item frames, first check if we can identify them by position
        // This is the most reliable way to identify unique maps in item frames
        val position = getPositionHash()
        if (position != null) {
            // If we've seen this position before, use the same map ID
            processedItemCoordinates[position]?.let { mapId ->
                if (dumpIDs) println("DEBUG: Reusing map ID $mapId for position $position")
                return mapId
            }
            
            // If not, assign the next map ID in our database
            val nextMapId = mapMapping.keys.elementAtOrNull(mapIdCounter % mapMapping.size) 
                ?: mapMapping.keys.firstOrNull() 
                ?: 0
            
            // Store this position -> mapId mapping for future use
            processedItemCoordinates[position] = nextMapId
            mapIdCounter++
            
            if (dumpIDs) println("DEBUG: Assigned map ID $nextMapId to position $position")
            return nextMapId
        }
        
        // Try to use slot index to identify maps in containers
        getIntValue("Slot")?.let { slotIndex ->
            // If we've seen this slot before, use the same map ID
            slotToMapIdMapping[slotIndex]?.let { mapId ->
                if (dumpIDs) println("DEBUG: Reusing map ID $mapId for slot $slotIndex")
                return mapId
            }
            
            // For new slots, check if the slot number matches a known map ID
            mapMapping.keys.forEach { mapId ->
                // Some common patterns for how slot indices relate to map IDs
                if (mapId == slotIndex || mapId % 100 == slotIndex % 100) {
                    slotToMapIdMapping[slotIndex] = mapId
                    if (dumpIDs) println("DEBUG: Found matching map ID $mapId for slot $slotIndex")
                    return mapId
                }
            }
            
            // If no direct match, assign the next map ID in our database
            val nextMapId = mapMapping.keys.elementAtOrNull(mapIdCounter % mapMapping.size) 
                ?: mapMapping.keys.firstOrNull() 
                ?: 0
            
            // Store this slot -> mapId mapping for future use
            slotToMapIdMapping[slotIndex] = nextMapId
            mapIdCounter++
            
            if (dumpIDs) println("DEBUG: Assigned map ID $nextMapId to slot $slotIndex")
            return nextMapId
        }
        
        // Check for specific identifiers in item frames
        getCompoundValue("entity_data")?.getStringValue("id")?.let { entityId ->
            if (entityId == "minecraft:item_frame" || entityId == "minecraft:glow_item_frame") {
                if (dumpIDs) println("DEBUG: Found item frame entity")
                
                // Extract a unique identifier based on the item frame's UUID if available
                val uuid = getCompoundValue("entity_data")?.getStringValue("UUID") 
                           ?: getCompoundValue("entity_data")?.getIntArrayValue("UUID")?.joinToString("-")
                
                if (uuid != null) {
                    // Hash the UUID to a number in our map ID range
                    val hashCode = uuid.hashCode()
                    val mapIdIndex = Math.abs(hashCode % mapMapping.size)
                    val mapId = mapMapping.keys.elementAtOrNull(mapIdIndex) ?: mapMapping.keys.firstOrNull() ?: 0
                    
                    if (dumpIDs) println("DEBUG: Determined map ID $mapId from item frame UUID $uuid")
                    return mapId
                }
                
                // Check rotation as a fallback
                getCompoundValue("entity_data")?.getIntValue("ItemRotation")?.let { rotation ->
                    // Use rotation value to select from available map IDs
                    val mapIdIndex = rotation % mapMapping.size
                    val mapId = mapMapping.keys.elementAtOrNull(mapIdIndex) ?: mapMapping.keys.firstOrNull() ?: 0
                    
                    if (dumpIDs) println("DEBUG: Determined map ID $mapId from item frame rotation $rotation")
                    return mapId
                }
            }
        }
        
        // Use contextual hints like the count or metadata
        getIntValue("count")?.let { count ->
            // Use a combination of count and other metadata to determine a unique hash
            val metaHash = count + (getShortValue("damage")?.toInt() ?: 0) * 10 + 
                          (getIntValue("repaircost") ?: 0) * 100 + mapIdCounter
            
            // Map this hash to an available map ID
            val mapIdIndex = metaHash % mapMapping.size
            val mapId = mapMapping.keys.elementAtOrNull(mapIdIndex) ?: mapMapping.keys.firstOrNull() ?: 0
            
            mapIdCounter++
            
            if (dumpIDs) println("DEBUG: Determined map ID $mapId from metadata hash")
            return mapId
        }
        
        // If we get here and have no other information, cycle through available map IDs
        val mapId = mapMapping.keys.elementAtOrNull(mapIdCounter % mapMapping.size) 
            ?: mapMapping.keys.firstOrNull() 
            ?: 0
        
        mapIdCounter++
        
        if (dumpIDs) println("DEBUG: Assigned next map ID $mapId from rotation")
        return mapId
    }
    
    /**
     * Helper to get a unique position hash for an item
     */
    private fun CompoundTag.getPositionHash(): String? {
        // Try various ways to get position information
        val pos = getPositionArray() // Try direct position array first
        if (pos != null && pos.size >= 3) {
            return "pos:${pos[0]},${pos[1]},${pos[2]}"
        }
        
        // Check if positions are stored as separate x,y,z fields (pre-1.21 format)
        val x = getIntValue("x") ?: getIntValue("X")
        val y = getIntValue("y") ?: getIntValue("Y")
        val z = getIntValue("z") ?: getIntValue("Z")
        
        if (x != null && y != null && z != null) {
            return "pos:$x,$y,$z"
        }
        
        // Check if we have a parent entity with position information
        getCompoundValue("entity_data")?.let { entityData ->
            // Try position array in the entity data
            val entityPos = entityData.getPositionArray()
            if (entityPos != null && entityPos.size >= 3) {
                return "entity_pos:${entityPos[0]},${entityPos[1]},${entityPos[2]}"
            }
            
            // Try x,y,z fields in the entity data
            val entityX = entityData.getIntValue("x") ?: entityData.getIntValue("X")
            val entityY = entityData.getIntValue("y") ?: entityData.getIntValue("Y")
            val entityZ = entityData.getIntValue("z") ?: entityData.getIntValue("Z")
            
            if (entityX != null && entityY != null && entityZ != null) {
                return "entity_pos:$entityX,$entityY,$entityZ"
            }
        }
        
        return null
    }

    private fun CompoundTag.remapOld(mapId: Short): Any {
        val newID = mapMapping[mapId.toInt()]?.toShort()

        return if (newID == null) {
            if (dumpIDs) {
                notFoundFile.appendText(mapId.toString() + "\n")
            }
            println("Map ID: $mapId not found in mapping database. Flipping to negative value.")
            putShort("Damage", (-mapId).toShort())
        } else {
            putShort("Damage", newID)
            //                        println("Remapped map item $mapId -> $newMapId")

            if (dumpIDs) {
                foundFile.appendText("$mapId -> $newID\n")
            }
            remapped++
        }
    }

    private fun CompoundTag.remapNew(mapId: Int): Any {
        // This method always maps SOURCE IDs to TARGET IDs, never the reverse
        if (dumpIDs) println("DEBUG: Remapping tag/map field from SOURCE ID: $mapId")
        
        val newID = mapMapping[mapId]

        return if (newID == null) {
            if (dumpIDs) {
                notFoundFile.appendText(mapId.toString() + "\n")
                println("DEBUG: Map ID: $mapId not found in mapping database. Setting to negative value.")
            }
            println("Map ID: $mapId not found in mapping database. Flipping to negative value.")
            putInt("map", -mapId)
        } else {
            if (dumpIDs) println("DEBUG: Successfully remapped SOURCE ID $mapId -> TARGET ID $newID")
            putInt("map", newID)

            if (dumpIDs) {
                foundFile.appendText("$mapId -> $newID\n")
            }
            remapped++
        }
    }
}