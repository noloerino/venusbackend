package venusbackend.simulator

import venus.Renderer
import venusbackend.plus
import venusbackend.shr
import venusbackend.ushr
import venusbackend.riscv.MemSize

typealias ByteAddress = Number
typealias WordAddress = Int

fun ByteAddress.toWordAddress(): WordAddress = this.toInt() ushr 2
fun ByteAddress.wordOffsetShamt(): Int = 8 * (this.toInt() and 0b11)
fun ByteAddress.isByteAligned(): Boolean = MemSize.BYTE.isAligned(this)
fun ByteAddress.isHalfAligned(): Boolean = MemSize.HALF.isAligned(this)
fun ByteAddress.isWordAligned(): Boolean = MemSize.WORD.isAligned(this)
fun ByteAddress.isLongAligned(): Boolean = MemSize.LONG.isAligned(this)

/**
 * Returns a bit mask that would get the desired byte offset out of the mask.
 * For example, if byteAddr ends in 0b01, this would return 0x0000_FF00.
 */
fun ByteAddress.wordOffsetMask(): Int = 0xFF shl this.wordOffsetShamt()

/**
 * A class representing a computer's memory.
 *
 * @param alignedAddresses is false if unaligned accesses are allowed (the default setting). Otherwise, unaligned
 * accesses raise an [AlignmentError].
 */
class Memory(val alignedAddresses: Boolean = false) {
    /**
     * A hashmap which maps WORD addresses to the WORD value stored at that place in memory.
     * To address specific bytes, examine the lower two bits of the byte address.
     *
     * As the RISCV ISA is little-endian by default, the values of the map are also little endian.
     *
     * Unlike MARS, I've made the design decision to use a hashmap. This allows for us to write anywhere in memory
     * without being concerned with writing out of bounds (4 MB). The downside is that this has a higher overhead.
     */
    private val memory = HashMap<WordAddress, Int>()

    fun removeByte(addr: ByteAddress) {
        val wordAddr = addr.toWordAddress()
        // Mask out specified bits
        val newMemWord = memory[wordAddr]?.and(addr.wordOffsetMask().inv()) ?: 0
        if (newMemWord == 0) {
            memory.remove(wordAddr)
        } else {
            memory[wordAddr] = newMemWord
        }
    }

    /**
     * Loads an unsigned byte from memory
     *
     * @param addr the address to load from
     * @return the byte at that location, or 0 if that location has not been written to
     */
    fun loadByte(addr: ByteAddress): Int = memory[addr.toWordAddress()]?.and(addr.wordOffsetMask())?.ushr(addr.wordOffsetShamt()) ?: 0

    /**
     * Loads an unsigned halfword from memory
     *
     * @param addr the address to load from
     * @return the halfword at that location, or 0 if that location has not been written to
     */
    fun loadHalfWord(addr: ByteAddress): Int {
        if (alignedAddresses) {
            assertHalfWordAligned(addr)
        }
        val msb = loadByte(addr + 1) shl 8
        val lsb = loadByte(addr)
        return msb or lsb
    }

    /**
     * Loads a word from memory
     *
     * @param addr the address to load from
     * @return the word at that location, or 0 if that location has not been written to
     */
    fun loadWord(addr: ByteAddress): Int =
            if (addr.isWordAligned()) {
                memory[addr.toWordAddress()] ?: 0
            } else {
                if (alignedAddresses) {
                    assertWordAligned(addr)
                }
                val msb = loadHalfWord(addr + 2) shl 16
                val lsb = loadHalfWord(addr)
                msb or lsb
            }

    /**
     * Loads a long from memory
     *
     * @param addr the address to load from
     * @return the long at that location, or 0 if that location has not been written to
     */
    fun loadLong(addr: ByteAddress): Long {
        if (alignedAddresses) {
            assertLongAligned(addr)
        }
        val msb = loadWord(addr + 4).toLong() shl 32
        // Upcasting from 2s complement will sign extend
        val lsb = loadWord(addr).toLong() and 0xFFFF_FFFFL
        return msb or lsb
    }

    /**
     * Stores a byte in memory, truncating the given Int if necessary
     *
     * @param addr the address to write to
     * @param value the value to write
     */
    fun storeByte(addr: ByteAddress, value: Number) {
        // retrieve old word and make room for the new
        val wordAddr = addr.toWordAddress()
        val oldWord = memory[wordAddr]?.and(addr.wordOffsetMask().inv()) ?: 0
        val newByte = value.toInt() shl addr.wordOffsetShamt()
        memory[wordAddr] = oldWord or newByte
    }

    /**
     * Stores a halfword in memory, truncating the given Int if necessary
     *
     * @param addr the address to write to
     * @param value the value to write
     */
    fun storeHalfWord(addr: ByteAddress, value: Number) {
        if (alignedAddresses) {
            assertHalfWordAligned(addr)
        }
        storeByte(addr, value)
        storeByte(addr + 1, value shr 8)
    }

    /**
     * Stores a word in memory
     *
     * @param addr the address to write to
     * @param value the value to write
     */
    fun storeWord(addr: ByteAddress, value: Number) {
        if (addr.isWordAligned()) {
            memory[addr.toWordAddress()] = value.toInt()
        } else {
            if (alignedAddresses) {
                assertWordAligned(addr)
            }
            storeHalfWord(addr, value)
            storeHalfWord(addr + 2, value shr 16)
        }
    }

    /**
     * Stores a long in memory
     *
     * @param addr the address to write to
     * @param value the value to write
     */
    fun storeLong(addr: ByteAddress, value: Number) {
        if (alignedAddresses) {
            assertLongAligned(addr)
        }
        storeWord(addr, value)
        storeWord(addr + 4, value ushr 32)
    }

    companion object {
        fun assertByteAligned(byteAddr: ByteAddress) {
            if (!byteAddr.isByteAligned()) {
                throw AlignmentError("Address: '${Renderer.toHex(byteAddr)}' is not BYTE aligned!")
            }
        }

        fun assertHalfWordAligned(byteAddr: ByteAddress) {
            if (!byteAddr.isHalfAligned()) {
                throw AlignmentError("Address: '${Renderer.toHex(byteAddr)}' is not HALF aligned!")
            }
        }

        fun assertWordAligned(byteAddr: ByteAddress) {
            if (!byteAddr.isWordAligned()) {
                throw AlignmentError("Address: '${Renderer.toHex(byteAddr)}' is not WORD aligned!")
            }
        }

        fun assertLongAligned(byteAddr: ByteAddress) {
            if (!byteAddr.isLongAligned()) {
                throw AlignmentError("Address: '${Renderer.toHex(byteAddr)}' is not LONG aligned!")
            }
        }
    }
}