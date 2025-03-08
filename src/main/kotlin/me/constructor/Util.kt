package me.constructor

import me.tongfei.progressbar.ProgressBar
import net.querz.mca.MCAFile
import net.querz.nbt.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

fun compound(block: CompoundTag.() -> Unit): CompoundTag {
    val compoundTag = CompoundTag()
    compoundTag.block()
    return compoundTag
}

inline fun listTag(type: Tag.Type, block: ListTag.() -> Unit): ListTag {
    val listTag = ListTag(type)
    listTag.block()
    return listTag
}

fun File.readNBT(modify: CompoundTag.() -> Unit) {
    val tag = NBTUtil.read(this) as CompoundTag
    tag.modify()
}

fun File.useNBT(compressed: Boolean = false, modify: CompoundTag.() -> Unit) {
    val tag = NBTUtil.read(this) as CompoundTag
    tag.modify()
    NBTUtil.write(this, tag, compressed)
}

fun File.useGzipNBT(modify: CompoundTag.() -> Unit) {
    val tag = NBTUtil.read(this) as CompoundTag
    tag.modify()

    GZIPOutputStream(outputStream()).use { output ->
        NBTUtil.write(output, tag)
    }
}

fun File.toMCA() = MCAFile(this)

fun MCAFile.use(use: MCAFile.() -> Unit) {
    load()
    use()
    save()
}

fun File.mapId() = name.substringAfterLast("_").substringBeforeLast(".dat").toInt()

fun ByteArray.digestHex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(this)
    return hashBytes.joinToString("") { "%02x".format(it) }
}

fun String.toProgress(maxIteration: Long, unit: String = ""): ProgressBar =
    ProgressBar.builder()
        .setInitialMax(maxIteration)
        .setUpdateIntervalMillis(100)
        .clearDisplayOnFinish()
        .continuousUpdate()
        .showSpeed()
        .setTaskName(this)
        .setUnit(unit, 1)
        .setMaxRenderedLength(150)
        .build()

fun Byte.toTag() = ByteTag.valueOf(this)
fun Short.toTag() = ShortTag.valueOf(this)
fun Int.toTag() = IntTag.valueOf(this)
fun Long.toTag() = LongTag.valueOf(this)
fun Float.toTag() = FloatTag.valueOf(this)
fun Double.toTag() = DoubleTag.valueOf(this)
fun ByteArray.toTag() = ByteArrayTag(this)
fun String.toTag() = StringTag.valueOf(this)
fun IntArray.toTag() = IntArrayTag(this)
fun LongArray.toTag() = LongArrayTag(this)

/**
 * Helper extension to get array-based positions from a CompoundTag
 * Used for Minecraft 1.21+ where positions are stored as arrays instead of X,Y,Z fields
 */
fun CompoundTag.getPositionArray(): IntArray? {
    return getIntArrayTag("pos")?.value
}

/**
 * Helper function to convert between PascalCase and snake_case
 */
private fun convertFieldName(name: String): String {
    // Convert from snake_case to PascalCase
    if (name.contains('_')) {
        return name.split('_')
            .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }
    
    // Convert from PascalCase to snake_case
    if (name.any { it.isUpperCase() }) {
        return name.mapIndexed { index, c -> 
            if (index > 0 && c.isUpperCase()) "_${c.lowercase()}" else c.lowercase() 
        }.joinToString("")
    }
    
    return name
}

// We don't have direct access to parent tags in this context

/**
 * Helper extension to get a string from a CompoundTag with field name compatibility
 * Tries both PascalCase (pre-1.21) and snake_case (1.21+) versions of field names
 */
fun CompoundTag.getStringValue(name: String): String? {
    // Try original case first
    val originalCase = runCatching { getStringTag(name)?.value }.getOrNull()
    if (originalCase != null) return originalCase
    
    // Try converted case
    val convertedName = convertFieldName(name)
    return runCatching { getStringTag(convertedName)?.value }.getOrNull()
}

/**
 * Helper extension to get an integer from a CompoundTag with field name compatibility
 * Tries both PascalCase (pre-1.21) and snake_case (1.21+) versions of field names
 */
fun CompoundTag.getIntValue(name: String): Int? {
    // Try original case first
    val originalCase = runCatching { getIntTag(name)?.asInt() }.getOrNull()
    if (originalCase != null) return originalCase
    
    // Try converted case
    val convertedName = convertFieldName(name)
    return runCatching { getIntTag(convertedName)?.asInt() }.getOrNull()
}

/**
 * Helper extension to get a short value from a CompoundTag with field name compatibility
 */
fun CompoundTag.getShortValue(name: String): Short? {
    // Try original case first
    val originalCase = runCatching { getShortTag(name)?.asShort() }.getOrNull()
    if (originalCase != null) return originalCase
    
    // Try converted case
    val convertedName = convertFieldName(name)
    return runCatching { getShortTag(convertedName)?.asShort() }.getOrNull()
}

/**
 * Helper extension to get an int array from a CompoundTag with field name compatibility
 */
fun CompoundTag.getIntArrayValue(name: String): IntArray? {
    // Try original case first
    val originalCase = runCatching { getIntArrayTag(name)?.value }.getOrNull()
    if (originalCase != null) return originalCase
    
    // Try converted case
    val convertedName = convertFieldName(name)
    return runCatching { getIntArrayTag(convertedName)?.value }.getOrNull()
}

/**
 * Helper extension to get a compound tag from a CompoundTag with field name compatibility
 * Tries both PascalCase (pre-1.21) and snake_case (1.21+) versions of field names
 */
fun CompoundTag.getCompoundValue(name: String): CompoundTag? {
    // Try original case first
    val originalCase = runCatching { getCompoundTag(name) }.getOrNull()
    if (originalCase != null) return originalCase
    
    // Try converted case
    val convertedName = convertFieldName(name)
    return runCatching { getCompoundTag(convertedName) }.getOrNull()
}

/**
 * Helper extension to get a boolean value from a CompoundTag with field name compatibility
 */
fun CompoundTag.getBooleanValue(name: String): Boolean? {
    // Try original case first - byte tags with value 0 or 1 are often used for booleans
    runCatching { getByteTag(name)?.asByte() }.getOrNull()?.let {
        return it != 0.toByte()
    }
    
    // Try converted case with byte tag
    val convertedName = convertFieldName(name)
    runCatching { getByteTag(convertedName)?.asByte() }.getOrNull()?.let {
        return it != 0.toByte()
    }
    
    // Try as boolean tag if available (some versions might use actual boolean)
    runCatching { containsKey(name) && get(name).toString().toBoolean() }.getOrNull()?.let {
        return it
    }
    
    // Try converted name with boolean interpretation
    runCatching { containsKey(convertedName) && get(convertedName).toString().toBoolean() }.getOrNull()?.let {
        return it
    }
    
    return null
}