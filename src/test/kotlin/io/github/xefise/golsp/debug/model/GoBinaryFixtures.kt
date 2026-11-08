package io.github.xefise.golsp.debug.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal hand-laid executables: just the headers the detector walks, per the ELF, Mach-O and PE specs. */
object GoBinaryFixtures {
    const val VERSION = "go1.27.3"

    /** Go 1.18+ build info: magic, pointer size, flags (inline strings), padding to 32, varint-prefixed version. */
    fun buildInfo(
        inlineStrings: Boolean = true,
        magic: Boolean = true,
        version: String = VERSION,
        length: ByteArray = uvarint(version.toByteArray().size),
    ): ByteArray {
        val text = version.toByteArray()
        val b = ByteBuffer.allocate(maxOf(64, 32 + length.size + text.size))
        if (magic) b.put(byteArrayOf(0xff.toByte()) + " Go buildinf:".toByteArray()) else b.put(ByteArray(14) { 'x'.code.toByte() })
        b.put(8)
        b.put(if (inlineStrings) 2 else 0)
        b.position(32)
        b.put(length)
        b.put(text)
        return b.array()
    }

    fun uvarint(value: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (v >= 0x80) {
            out += ((v and 0x7f) or 0x80).toByte()
            v = v ushr 7
        }
        out += v.toByte()
        return out.toByteArray()
    }

    fun elf(
        is64: Boolean = true,
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        sectionName: String = ".go.buildinfo",
        data: ByteArray = buildInfo(),
        sectionType: Int = 1,
    ): ByteArray {
        val names = "\u0000.shstrtab\u0000$sectionName\u0000".toByteArray()
        val namesOffset = 0x100
        val dataOffset = 0x200
        val tableOffset = 0x300
        val entrySize = if (is64) 64 else 40
        val b = ByteBuffer.allocate(tableOffset + 3 * entrySize).order(order)
        b.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), if (is64) 2 else 1, if (order == ByteOrder.LITTLE_ENDIAN) 1 else 2, 1))
        if (is64) {
            b.putLong(0x28, tableOffset.toLong())
            b.putShort(0x3a, entrySize.toShort()).putShort(0x3c, 3).putShort(0x3e, 1)
        } else {
            b.putInt(0x20, tableOffset)
            b.putShort(0x2e, entrySize.toShort()).putShort(0x30, 3).putShort(0x32, 1)
        }
        b.put(namesOffset, names)
        b.put(dataOffset, data)
        fun section(index: Int, name: Int, type: Int, offset: Int, size: Int) {
            val base = tableOffset + index * entrySize
            b.putInt(base, name).putInt(base + 4, type)
            if (is64) b.putLong(base + 0x18, offset.toLong()).putLong(base + 0x20, size.toLong())
            else b.putInt(base + 0x10, offset).putInt(base + 0x14, size)
        }
        section(1, 1, 3, namesOffset, names.size)
        section(2, 11, sectionType, dataOffset, data.size)
        return b.array()
    }

    fun macho(
        is64: Boolean = true,
        sectionName: String = "__go_buildinfo",
        data: ByteArray = buildInfo(),
    ): ByteArray {
        val order = ByteOrder.LITTLE_ENDIAN
        val headerSize = if (is64) 32 else 28
        val segmentSize = if (is64) 72 else 56
        val sectionSize = if (is64) 80 else 68
        val dataOffset = 0x200
        val b = ByteBuffer.allocate(dataOffset + data.size).order(order)
        b.putInt(0, if (is64) 0xfeedfacf.toInt() else 0xfeedface.toInt())
        b.putInt(16, 2).putInt(20, 16 + segmentSize + sectionSize)
        // A non-segment command first, so the walk must honour cmdsize.
        var pos = headerSize
        b.putInt(pos, 0x2).putInt(pos + 4, 16)
        pos += 16
        b.putInt(pos, if (is64) 0x19 else 0x1).putInt(pos + 4, segmentSize + sectionSize)
        b.put(pos + 8, "__DATA".toByteArray())
        b.putInt(pos + segmentSize - 8, 1)
        val section = pos + segmentSize
        b.put(section, sectionName.toByteArray())
        b.put(section + 16, "__DATA".toByteArray())
        if (is64) b.putLong(section + 40, data.size.toLong()).putInt(section + 48, dataOffset)
        else b.putInt(section + 36, data.size).putInt(section + 40, dataOffset)
        b.put(dataOffset, data)
        return b.array()
    }

    fun fat(vararg slices: ByteArray, is64: Boolean = false): ByteArray {
        val sliceAlign = 0x1000
        val entrySize = if (is64) 32 else 20
        val b = ByteBuffer.allocate(sliceAlign * (slices.size + 1)).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, if (is64) 0xcafebabf.toInt() else 0xcafebabe.toInt()).putInt(4, slices.size)
        slices.forEachIndexed { i, slice ->
            val offset = sliceAlign * (i + 1)
            val entry = 8 + i * entrySize
            if (is64) b.putLong(entry + 8, offset.toLong()).putLong(entry + 16, slice.size.toLong())
            else b.putInt(entry + 8, offset).putInt(entry + 12, slice.size)
            b.put(offset, slice)
        }
        return b.array()
    }

    fun pe(
        dataCharacteristics: Int = 0x40 or 0x40000000 or 0x80000000.toInt() or 0x00600000,
        magicAt: Int = 0x20,
        data: ByteArray = buildInfo(),
        magicInSecondDataSection: Boolean = false,
    ): ByteArray {
        val peOffset = 0x40
        val optionalSize = 0xf0
        val table = peOffset + 24 + optionalSize
        val rawOffset = 0x400
        val secondRawOffset = rawOffset + 0x200
        val b = ByteBuffer.allocate(secondRawOffset + 0x200).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte()).putInt(0x3c, peOffset)
        b.putInt(peOffset, 0x00004550).putShort(peOffset + 4, 0x8664.toShort())
        b.putShort(peOffset + 6, if (magicInSecondDataSection) 3 else 2).putShort(peOffset + 20, optionalSize.toShort())
        fun section(index: Int, name: String, va: Int, raw: Int, characteristics: Int) {
            val base = table + index * 40
            b.put(base, name.toByteArray())
            b.putInt(base + 8, 0x200).putInt(base + 12, va).putInt(base + 16, 0x200).putInt(base + 20, raw).putInt(base + 36, characteristics)
        }
        section(0, ".text", 0x1000, 0x200, 0x20 or 0x20000000 or 0x40000000)
        section(1, ".data", 0x2000, rawOffset, dataCharacteristics)
        if (magicInSecondDataSection) {
            section(2, ".data2", 0x3000, secondRawOffset, dataCharacteristics)
            b.put(secondRawOffset + magicAt, data)
        } else {
            b.put(rawOffset + magicAt, data)
        }
        return b.array()
    }
}
