package io.github.xefise.golsp.debug.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Recognises Go executables by the linker's build info block, reading only headers and a few small blocks. */
object GoBinaryDetector {
    fun isGoBinary(path: Path): Boolean = withReader(path) { buildInfoOffset(it) } != null

    /** Go version recorded by the linker (`go1.27.3`); null for non-Go files and pre-1.18 binaries. */
    fun goVersion(path: Path): String? = withReader(path) { reader -> buildInfoOffset(reader)?.let { readVersion(reader, it) } }

    private fun <T : Any> withReader(path: Path, block: (BinaryReader) -> T?): T? = try {
        if (!Files.isRegularFile(path)) {
            null
        } else {
            FileChannel.open(path, StandardOpenOption.READ).use { block(BinaryReader(it, it.size())) }
        }
    } catch (_: ClosedByInterruptException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        // Callers scan every local process: unreadable or corrupt files are simply "not Go".
        null
    }

    private fun buildInfoOffset(reader: BinaryReader): Long? {
        val magic = reader.read(0, 4, ByteOrder.BIG_ENDIAN)?.getInt(0) ?: return null
        return when {
            magic == ELF_MAGIC -> ElfFormat.buildInfoOffset(reader)
            magic in MACHO_MAGICS -> MachOFormat.buildInfoOffset(reader, 0)
            magic == FAT_MAGIC || magic == FAT_MAGIC_64 -> MachOFormat.fatBuildInfoOffset(reader, magic == FAT_MAGIC_64)
            magic ushr 16 == MZ -> PeFormat.buildInfoOffset(reader)
            else -> null
        }
    }

    private fun readVersion(reader: BinaryReader, offset: Long): String? {
        val header = reader.read(offset, BUILD_INFO_HEADER_SIZE.toLong(), ByteOrder.LITTLE_ENDIAN) ?: return null
        // Since Go 1.18 the version string is stored inline after the header; older binaries store pointers.
        if (header.get(15).toInt() and FLAG_INLINE_STRINGS == 0) return null
        val start = offset + BUILD_INFO_HEADER_SIZE
        val data = reader.read(start, minOf(MAX_VERSION_BLOCK, reader.size - start), ByteOrder.LITTLE_ENDIAN) ?: return null
        var length = 0L
        var shift = 0
        var pos = 0
        while (true) {
            if (pos >= data.limit() || shift > 28) return null
            val b = data.get(pos++).toInt() and 0xff
            length = length or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (length == 0L || length > data.limit() - pos) return null
        val bytes = ByteArray(length.toInt())
        data.position(pos)
        data.get(bytes)
        val version = String(bytes, Charsets.UTF_8)
        val plausible = (version.startsWith("go") || version.startsWith("devel")) && version.none { it.isISOControl() }
        return version.takeIf { plausible }
    }

    private const val ELF_MAGIC = 0x7f454c46
    private const val FAT_MAGIC = 0xcafebabe.toInt()
    private const val FAT_MAGIC_64 = 0xcafebabf.toInt()
    private const val MZ = 0x4d5a
    private const val BUILD_INFO_HEADER_SIZE = 32
    private const val FLAG_INLINE_STRINGS = 0x2
    private const val MAX_VERSION_BLOCK = 256L
}

internal const val MH_MAGIC = 0xfeedface.toInt()
internal const val MH_MAGIC_64 = 0xfeedfacf.toInt()
internal const val MH_CIGAM = 0xcefaedfe.toInt()
internal const val MH_CIGAM_64 = 0xcffaedfe.toInt()
internal val MACHO_MAGICS = setOf(MH_MAGIC, MH_MAGIC_64, MH_CIGAM, MH_CIGAM_64)

internal val BUILD_INFO_MAGIC: ByteArray = byteArrayOf(0xff.toByte()) + " Go buildinf:".toByteArray(Charsets.US_ASCII)

internal class BinaryReader(private val channel: FileChannel, val size: Long) {
    /** Null when the range is outside the file or larger than [MAX_READ]: corrupt headers never cause big allocations. */
    fun read(offset: Long, length: Long, order: ByteOrder): ByteBuffer? {
        if (offset < 0 || length < 0 || length > MAX_READ || offset > size - length) return null
        val buffer = ByteBuffer.allocate(length.toInt())
        var position = offset
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n <= 0) return null
            position += n
        }
        return buffer.flip().order(order)
    }

    fun hasMagicAt(offset: Long): Boolean {
        val bytes = read(offset, BUILD_INFO_MAGIC.size.toLong(), ByteOrder.BIG_ENDIAN) ?: return false
        return BUILD_INFO_MAGIC.indices.all { bytes.get(it) == BUILD_INFO_MAGIC[it] }
    }

    companion object {
        const val MAX_READ = 256L * 1024
    }
}

/** True when [buffer] holds [name] at [offset] followed by NUL, or ending exactly at [fieldEnd] (fixed-width names). */
internal fun ByteBuffer.hasName(offset: Int, name: ByteArray, fieldEnd: Int = limit()): Boolean {
    if (offset < 0 || offset + name.size > minOf(fieldEnd, limit())) return false
    if (name.indices.any { get(offset + it) != name[it] }) return false
    val end = offset + name.size
    return end == fieldEnd || (end < limit() && get(end) == 0.toByte())
}

internal fun ByteBuffer.uint(index: Int): Long = getInt(index).toLong() and 0xffffffffL

internal fun ByteBuffer.ushort(index: Int): Int = getShort(index).toInt() and 0xffff
