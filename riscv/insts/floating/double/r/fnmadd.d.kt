package venusbackend.riscv.insts.floating.double.r

import venusbackend.riscv.insts.dsl.types.extensions.floating.FR4TypeInstruction
import venusbackend.riscv.insts.floating.Decimal

val fnmaddd = FR4TypeInstruction(
        name = "fnmadd.d",
        opcode = 0b1001111,
        funct2 = 0b01,
        eval32 = { a, b, c -> Decimal(d = -((a.getCurrentDouble() * b.getCurrentDouble()) + c.getCurrentDouble()), isF = false) }
)