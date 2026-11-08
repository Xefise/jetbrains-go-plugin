package io.github.xefise.golsp.debug.model

import java.nio.ByteOrder

internal object ElfFormat {
    private val SECTION_NAME = ".go.buildinfo".toByteArray(Charsets.US_ASCII)
    private const val SHT_NOBITS = 8
    private const val MAX_SECTIONS = 4096

    fun buildInfoOffset(reader: BinaryReader): Long? {
        val ident = reader.read(0, 6, ByteOrder.BIG_ENDIAN) ?: return null
        val is64 = when (ident.get(4).toInt()) {
            1 -> false
            2 -> true
            else -> return null
        }
        val order = when (ident.get(5).toInt()) {
            1 -> ByteOrder.LITTLE_ENDIAN
            2 -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val header = reader.read(0, if (is64) 64 else 52, order) ?: return null
        val tableOffset = if (is64) header.getLong(0x28) else header.uint(0x20)
        val entrySize = header.ushort(if (is64) 0x3a else 0x2e)
        val count = header.ushort(if (is64) 0x3c else 0x30)
        val namesIndex = header.ushort(if (is64) 0x3e else 0x32)
        // No section table (sstrip) or extended numbering: accepted false negative.
        if (count == 0 || count > MAX_SECTIONS || entrySize < (if (is64) 64 else 40) || namesIndex >= count) return null
        val table = reader.read(tableOffset, count.toLong() * entrySize, order) ?: return null

        fun offsetOf(i: Int): Long = if (is64) table.getLong(i * entrySize + 0x18) else table.uint(i * entrySize + 0x10)
        fun sizeOf(i: Int): Long = if (is64) table.getLong(i * entrySize + 0x20) else table.uint(i * entrySize + 0x14)

        val names = reader.read(offsetOf(namesIndex), sizeOf(namesIndex), order) ?: return null
        for (i in 0 until count) {
            val base = i * entrySize
            val nameOffset = table.uint(base)
            if (nameOffset >= names.limit() || !names.hasName(nameOffset.toInt(), SECTION_NAME)) continue
            if (table.getInt(base + 4) == SHT_NOBITS || sizeOf(i) < BUILD_INFO_MAGIC.size) return null
            val offset = offsetOf(i)
            return offset.takeIf { reader.hasMagicAt(it) }
        }
        return null
    }
}

internal object MachOFormat {
    private val SECTION_NAME = "__go_buildinfo".toByteArray(Charsets.US_ASCII)
    private const val LC_SEGMENT = 0x1
    private const val LC_SEGMENT_64 = 0x19
    private const val MAX_LOAD_COMMANDS = 4096
    private const val MAX_FAT_SLICES = 32

    /** Any slice of a universal binary counts: the IDE may run on either architecture. */
    fun fatBuildInfoOffset(reader: BinaryReader, is64: Boolean): Long? {
        val header = reader.read(0, 8, ByteOrder.BIG_ENDIAN) ?: return null
        val count = header.uint(4)
        // Java class files share the 0xcafebabe magic; their version field reads as an absurd slice count.
        if (count == 0L || count > MAX_FAT_SLICES) return null
        val entrySize = if (is64) 32 else 20
        val table = reader.read(8, count * entrySize, ByteOrder.BIG_ENDIAN) ?: return null
        for (i in 0 until count.toInt()) {
            val sliceOffset = if (is64) table.getLong(i * entrySize + 8) else table.uint(i * entrySize + 8)
            buildInfoOffset(reader, sliceOffset)?.let { return it }
        }
        return null
    }

    fun buildInfoOffset(reader: BinaryReader, base: Long): Long? {
        val magic = reader.read(base, 4, ByteOrder.BIG_ENDIAN)?.getInt(0) ?: return null
        val (is64, order) = when (magic) {
            MH_MAGIC -> false to ByteOrder.BIG_ENDIAN
            MH_MAGIC_64 -> true to ByteOrder.BIG_ENDIAN
            MH_CIGAM -> false to ByteOrder.LITTLE_ENDIAN
            MH_CIGAM_64 -> true to ByteOrder.LITTLE_ENDIAN
            else -> return null
        }
        val headerSize = if (is64) 32 else 28
        val header = reader.read(base, headerSize.toLong(), order) ?: return null
        val commandCount = header.uint(16)
        if (commandCount > MAX_LOAD_COMMANDS) return null
        val commands = reader.read(base + headerSize, header.uint(20), order) ?: return null
        val segmentCommand = if (is64) LC_SEGMENT_64 else LC_SEGMENT
        val segmentSize = if (is64) 72 else 56
        val sectionSize = if (is64) 80 else 68
        var pos = 0
        repeat(commandCount.toInt()) {
            if (pos + 8 > commands.limit()) return null
            val commandSize = commands.uint(pos + 4)
            if (commandSize < 8 || commandSize > commands.limit() - pos) return null
            if (commands.getInt(pos) == segmentCommand && commandSize >= segmentSize) {
                val sectionCount = commands.uint(pos + segmentSize - 8)
                if (sectionCount * sectionSize > commandSize - segmentSize) return null
                for (j in 0 until sectionCount.toInt()) {
                    val section = pos + segmentSize + j * sectionSize
                    if (!commands.hasName(section, SECTION_NAME, section + 16)) continue
                    val size = if (is64) commands.getLong(section + 40) else commands.uint(section + 36)
                    val offset = commands.uint(section + if (is64) 48 else 40)
                    if (offset == 0L || size < BUILD_INFO_MAGIC.size) return null
                    return (base + offset).takeIf { reader.hasMagicAt(it) }
                }
            }
            pos += commandSize.toInt()
        }
        return null
    }
}

internal object PeFormat {
    private const val IMAGE_SCN_CNT_INITIALIZED_DATA = 0x00000040
    private const val IMAGE_SCN_MEM_READ = 0x40000000
    private const val IMAGE_SCN_MEM_WRITE = 0x80000000.toInt()
    private const val IMAGE_SCN_ALIGN_MASK = 0x00f00000
    private const val PE_SIGNATURE = 0x00004550
    private const val SECTION_HEADER_SIZE = 40
    private const val MAX_SECTIONS = 1024
    private const val SEARCH_LIMIT = 64L * 1024
    private const val SEARCH_ALIGN = 16

    fun buildInfoOffset(reader: BinaryReader): Long? {
        val dos = reader.read(0, 64, ByteOrder.LITTLE_ENDIAN) ?: return null
        val peOffset = dos.uint(0x3c)
        val coff = reader.read(peOffset, 24, ByteOrder.LITTLE_ENDIAN) ?: return null
        if (coff.getInt(0) != PE_SIGNATURE) return null
        val count = coff.ushort(6)
        if (count == 0 || count > MAX_SECTIONS) return null
        val tableOffset = peOffset + 24 + coff.ushort(20)
        val table = reader.read(tableOffset, count.toLong() * SECTION_HEADER_SIZE, ByteOrder.LITTLE_ENDIAN) ?: return null
        val wanted = IMAGE_SCN_CNT_INITIALIZED_DATA or IMAGE_SCN_MEM_READ or IMAGE_SCN_MEM_WRITE
        for (i in 0 until count) {
            val base = i * SECTION_HEADER_SIZE
            val virtualAddress = table.uint(base + 12)
            val rawSize = table.uint(base + 16)
            if (virtualAddress == 0L || rawSize == 0L) continue
            if (table.getInt(base + 36) and IMAGE_SCN_ALIGN_MASK.inv() != wanted) continue
            // Only the first writable data section holds the build info; past SEARCH_LIMIT is an accepted false negative.
            val rawOffset = table.uint(base + 20)
            val length = minOf(rawSize, SEARCH_LIMIT, reader.size - rawOffset)
            val data = reader.read(rawOffset, length, ByteOrder.LITTLE_ENDIAN) ?: return null
            var pos = 0
            while (pos + BUILD_INFO_MAGIC.size <= data.limit()) {
                if (BUILD_INFO_MAGIC.indices.all { data.get(pos + it) == BUILD_INFO_MAGIC[it] }) return rawOffset + pos
                pos += SEARCH_ALIGN
            }
            return null
        }
        return null
    }
}
