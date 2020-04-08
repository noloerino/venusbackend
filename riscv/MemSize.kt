package venusbackend.riscv

import venusbackend.rem

enum class MemSize(val size: Int) {
    QUAD(16),
    LONG(8),
    WORD(4),
    HALF(2),
    BYTE(1);

    fun isAligned(addr: Number): Boolean = (addr % size) == 0
}