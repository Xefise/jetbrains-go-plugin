package io.github.xefise.golsp.debug.model

import io.github.xefise.golsp.debug.model.GoBinaryFixtures.buildInfo
import io.github.xefise.golsp.debug.model.GoBinaryFixtures.elf
import io.github.xefise.golsp.debug.model.GoBinaryFixtures.fat
import io.github.xefise.golsp.debug.model.GoBinaryFixtures.macho
import io.github.xefise.golsp.debug.model.GoBinaryFixtures.pe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random

class GoBinaryDetectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(bytes: ByteArray): Path = temp.newFile().toPath().also { Files.write(it, bytes) }

    private fun isGo(bytes: ByteArray) = GoBinaryDetector.isGoBinary(file(bytes))

    @Test
    fun `elf64 little endian with go buildinfo section is go`() = assertTrue(isGo(elf()))

    @Test
    fun `elf32 big endian with go buildinfo section is go`() = assertTrue(isGo(elf(is64 = false, order = ByteOrder.BIG_ENDIAN)))

    @Test
    fun `elf32 little endian with go buildinfo section is go`() = assertTrue(isGo(elf(is64 = false)))

    @Test
    fun `elf without go buildinfo section is not go`() = assertFalse(isGo(elf(sectionName = ".data")))

    @Test
    fun `elf section whose name only starts with go buildinfo is not go`() = assertFalse(isGo(elf(sectionName = ".go.buildinfox")))

    @Test
    fun `elf go buildinfo section without magic is not go`() = assertFalse(isGo(elf(data = buildInfo(magic = false))))

    @Test
    fun `elf go buildinfo section of type nobits is not go`() = assertFalse(isGo(elf(sectionType = 8)))

    @Test
    fun `elf with huge bogus section count is not go`() {
        val bytes = elf()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(0x3c, 0xffff.toShort()).putShort(0x3e, 0xfffe.toShort())
        assertFalse(isGo(bytes))
    }

    @Test
    fun `elf with section table offset beyond the file is not go`() {
        val bytes = elf()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(0x28, Long.MIN_VALUE + 7)
        assertFalse(isGo(bytes))
    }

    @Test
    fun `elf truncated inside the section table is not go`() = assertFalse(isGo(elf().copyOf(0x300 + 64 + 10)))

    @Test
    fun `elf truncated after the identification bytes is not go`() = assertFalse(isGo(elf().copyOf(16)))

    @Test
    fun `macho64 with go buildinfo section is go`() = assertTrue(isGo(macho()))

    @Test
    fun `macho32 with go buildinfo section is go`() = assertTrue(isGo(macho(is64 = false)))

    @Test
    fun `macho without go buildinfo section is not go`() = assertFalse(isGo(macho(sectionName = "__data")))

    @Test
    fun `macho section name filling all 16 bytes with go buildinfo prefix is not go`() =
        assertFalse(isGo(macho(sectionName = "__go_buildinfoXY")))

    @Test
    fun `macho go buildinfo section without magic is not go`() = assertFalse(isGo(macho(data = buildInfo(magic = false))))

    @Test
    fun `macho with huge bogus load command size is not go`() {
        val bytes = macho()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1).putInt(20, -1)
        assertFalse(isGo(bytes))
    }

    @Test
    fun `macho with huge bogus section count is not go`() {
        val bytes = macho()
        // nsects of the segment command: header 32 + first command 16 + segment 72 - 8.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32 + 16 + 64, Int.MAX_VALUE)
        assertFalse(isGo(bytes))
    }

    @Test
    fun `macho with zero sized load command is not go`() {
        val bytes = macho()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32 + 4, 0)
        assertFalse(isGo(bytes))
    }

    @Test
    fun `fat macho is go when only its second slice is go`() = assertTrue(isGo(fat(macho(sectionName = "__data"), macho())))

    @Test
    fun `fat macho without go slices is not go`() = assertFalse(isGo(fat(macho(sectionName = "__data"), macho(sectionName = "__data"))))

    @Test
    fun `fat macho with huge bogus slice count is not go`() {
        val bytes = fat(macho())
        ByteBuffer.wrap(bytes).putInt(4, -1)
        assertFalse(isGo(bytes))
    }

    @Test
    fun `java class file sharing the fat magic is not go`() =
        assertFalse(isGo(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, 65) + ByteArray(64)))

    @Test
    fun `pe with magic at the start of the first writable data section is go`() = assertTrue(isGo(pe(magicAt = 0)))

    @Test
    fun `pe with magic at a 16 byte aligned offset of the data section is go`() = assertTrue(isGo(pe(magicAt = 0x20)))

    @Test
    fun `pe with magic at an unaligned offset is not go`() = assertFalse(isGo(pe(magicAt = 0x28)))

    @Test
    fun `pe whose data section is also executable is not go`() =
        assertFalse(isGo(pe(dataCharacteristics = 0x40 or 0x40000000 or 0x80000000.toInt() or 0x20000000)))

    @Test
    fun `pe data section without magic is not go`() = assertFalse(isGo(pe(data = buildInfo(magic = false))))

    @Test
    fun `pe with huge bogus section count is not go`() {
        val bytes = pe()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(0x40 + 6, 0xffff.toShort())
        assertFalse(isGo(bytes))
    }

    @Test
    fun `pe with header offset beyond the file is not go`() {
        val bytes = pe()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3c, -16)
        assertFalse(isGo(bytes))
    }

    @Test
    fun `dos stub without pe signature is not go`() = assertFalse(isGo(pe().copyOf(0x40)))

    @Test
    fun `empty file is not go`() = assertFalse(isGo(ByteArray(0)))

    @Test
    fun `shell script is not go`() = assertFalse(isGo("#!/bin/sh\necho ÿ Go buildinf:\n".toByteArray()))

    @Test
    fun `random garbage with every format magic is not go`() {
        val random = Random(42)
        val magics = listOf(
            byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
            byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte()),
            byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()),
            byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()),
        )
        for (magic in magics) {
            repeat(50) { assertFalse(isGo(magic + random.nextBytes(random.nextInt(0, 2048)))) }
        }
    }

    @Test
    fun `directory is not go`() = assertFalse(GoBinaryDetector.isGoBinary(temp.newFolder().toPath()))

    @Test
    fun `missing path is not go`() = assertFalse(GoBinaryDetector.isGoBinary(temp.root.toPath().resolve("missing")))

    @Test
    fun `system ls on macos is not go`() {
        val ls = Paths.get("/bin/ls")
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Mac") && Files.isRegularFile(ls))
        assertFalse(GoBinaryDetector.isGoBinary(ls))
    }

    @Test
    fun `go version is read from inline build info`() = assertEquals("go1.27.3", GoBinaryDetector.goVersion(file(elf())))

    @Test
    fun `go version is read from pe build info at an aligned offset`() =
        assertEquals("go1.27.3", GoBinaryDetector.goVersion(file(pe(magicAt = 0x30))))

    @Test
    fun `pre 1_18 build info is go without a version`() {
        val path = file(macho(data = buildInfo(inlineStrings = false)))
        assertTrue(GoBinaryDetector.isGoBinary(path))
        assertNull(GoBinaryDetector.goVersion(path))
    }

    @Test
    fun `go version of a non go file is null`() = assertNull(GoBinaryDetector.goVersion(file(elf(sectionName = ".data"))))

    @Test
    fun `pe with magic only in the second writable data section is not go`() = assertFalse(isGo(pe(magicInSecondDataSection = true)))

    @Test
    fun `fat64 macho with a go slice is go`() = assertTrue(isGo(fat(macho(sectionName = "__data"), macho(), is64 = true)))

    @Test
    fun `go version longer than 127 bytes is read through a two byte varint length`() {
        val version = "devel go1.28-" + "a".repeat(140)
        val data = buildInfo(version = version)
        assertEquals(2, data.size - 32 - version.length)
        assertEquals(version, GoBinaryDetector.goVersion(file(elf(data = data))))
    }

    @Test
    fun `go version is null when the file ends inside the version string`() {
        // Data starts at 0x200: 32 header bytes + 1 length byte + 3 of the 8 version bytes.
        val bytes = macho().copyOf(0x200 + 32 + 1 + 3)
        assertNull(GoBinaryDetector.goVersion(file(bytes)))
    }

    @Test
    fun `go version not starting with go or devel is null`() =
        assertNull(GoBinaryDetector.goVersion(file(elf(data = buildInfo(version = "xyz1.2")))))

    @Test
    fun `go version with control characters is null`() =
        assertNull(GoBinaryDetector.goVersion(file(elf(data = buildInfo(version = "go1.2\n\u0000")))))
}
