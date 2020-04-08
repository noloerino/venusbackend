package venusbackend.simulator

/* ktlint-disable no-wildcard-imports */

import venus.Renderer
import venus.vfs.VirtualFileSystem
import venusbackend.*
import venusbackend.linker.LinkedProgram
import venusbackend.riscv.*
import venusbackend.riscv.insts.dsl.types.Instruction
import venusbackend.riscv.insts.floating.Decimal
import venusbackend.riscv.insts.integer.base.i.ecall.Alloc
import venusbackend.simulator.diffs.*
import kotlin.math.max

/* ktlint-enable no-wildcard-imports */

/** Right now, this is a loose wrapper around SimulatorState
    Eventually, it will support debugging. */
class Simulator(
    val linkedProgram: LinkedProgram,
    val VFS: VirtualFileSystem = VirtualFileSystem("dummy"),
    var settings: SimulatorSettings = SimulatorSettings(),
    val state: SimulatorState = SimulatorState32(),
    val simulatorID: Int = 0
) {

    private var cycles = 0
    private val history = History()
    private val preInstruction = ArrayList<Diff>()
    private val postInstruction = ArrayList<Diff>()
//    private val breakpoints: Array<Boolean>
    private val breakpoints = HashSet<Int>()
    var args = ArrayList<String>()
    var ebreak = false
    var stdout = ""
    var filesHandler = FilesHandler(this)
    val instOrderMapping = HashMap<Int, Int>()
    val invInstOrderMapping = HashMap<Int, Int>()
    var exitcode: Int? = null

    val alloc: Alloc = Alloc(this)

    init {
        (state).getReg(1)
        var i = 0
        for (inst in linkedProgram.prog.insts) {
            instOrderMapping[i] = state.getMaxPC().toInt()
            invInstOrderMapping[state.getMaxPC().toInt()] = i
            i++
            var mcode = inst[InstructionField.ENTIRE]
            for (j in 0 until inst.length) {
                state.mem.storeByte(state.getMaxPC(), mcode and 0xFF)
                mcode = mcode shr 8
                state.incMaxPC(1)
            }
        }

        var dataOffset = MemorySegments.STATIC_BEGIN
        for (datum in linkedProgram.prog.dataSegment) {
            state.mem.storeByte(dataOffset, datum.toInt())
            dataOffset++
        }

        state.setHeapEnd(max(state.getHeapEnd().toInt(), dataOffset))

        setPC(linkedProgram.startPC ?: MemorySegments.TEXT_BEGIN)
        if (settings.setRegesOnInit) {
            state.setReg(Registers.sp, MemorySegments.STACK_BEGIN)
//            state.setReg(Registers.fp, MemorySegments.STACK_BEGIN)
            state.setReg(Registers.gp, MemorySegments.STATIC_BEGIN)
            if (linkedProgram.prog.isGlobalLabel("main")) {
                state.setReg(Registers.ra, state.getMaxPC())
                settings.ecallOnlyExit = false // This is because this will not work with ecall exit only with this current hotfix
                try {
                    Renderer.updateRegister(Registers.ra, state.getMaxPC())
                } catch (e: Exception) {}
            }
        }

//        breakpoints = Array(linkedProgram.prog.insts.size, { false })
    }

    fun isDone(): Boolean {
        return if (settings.ecallOnlyExit) {
            this.exitcode != null
        } else {
            getPC() >= state.getMaxPC()
        }
    }

    fun getCycles(): Int {
        return cycles
    }

    fun getMaxPC(): Number {
        return state.getMaxPC()
    }

    fun incMaxPC(amount: Number) {
        state.incMaxPC(amount)
    }

    fun getInstAt(addr: ByteAddress): MachineCode {
        val instnum = invInstOrderMapping[addr]!!.toInt()
        return linkedProgram.prog.insts[instnum]
    }

    fun setMemory(mem: Memory) {
        state.mem = mem
    }

    fun run() {
        while (!isDone()) {
            step()
        }
    }

    fun runToBreakpoint() {
        if (!isDone()) {
            // We need to step past a breakpoint.
            step()
        }
        while (!isDone() && !atBreakpoint()) {
            step()
        }
    }

    fun step(): List<Diff> {
        if (settings.maxSteps >= 0 && cycles >= settings.maxSteps) {
            throw ExceededAllowedCyclesError("Ran for more than the max allowed steps (${settings.maxSteps})!")
        }
        this.branched = false
        this.jumped = false
        this.ebreak = false
        this.ecallMsg = ""
        cycles++
        preInstruction.clear()
        postInstruction.clear()
        val mcode: MachineCode = getNextInstruction()
        try {
            when (state.registerWidth) {
                16 -> { Instruction[mcode].impl16(mcode, this) }
                32 -> { Instruction[mcode].impl32(mcode, this) }
                64 -> { Instruction[mcode].impl64(mcode, this) }
                128 -> { Instruction[mcode].impl128(mcode, this) }
                else -> { throw SimulatorError("Unsupported register width!") }
            }
        } catch (e: SimulatorError) {
            if (e.infe == null) {
                throw e
            }
            Renderer.displayError("\n[ERROR]: Could not decode the instruction (0x" + mcode.toString(16) + ") at pc='" + toHex(getPC()) + "'!\n" +
                    "Please make sure that you are not jumping to the middle of an instruction!\n")
            throw e
        }
        history.add(preInstruction)
        this.stdout += this.ecallMsg
        if (isDone() && exitcode == null) {
            exitcode = state.getReg(Registers.a0).toInt()
        }
        return postInstruction.toList()
    }

    fun undo(): List<Diff> {
        exitcode = null
        if (!canUndo()) return emptyList() /* TODO: error here? */
        val diffs = history.pop()
        for (diff in diffs) {
            diff(state)
        }
        cycles--
        return diffs
    }

    fun removeAllArgsFromMem() {
        var sp = getReg(2)
        while (sp < MemorySegments.STACK_BEGIN && settings.setRegesOnInit) {
            this.state.mem.removeByte(sp)
            sp++
            setReg(Registers.sp, sp)
        }
    }

    fun removeAllArgs() {
        removeAllArgsFromMem()
        this.args.removeAll(this.args)
    }

    fun removeArg(index: Int) {
        if (index in 0 until this.args.size) {
            this.args.removeAt(index)
            this.removeAllArgsFromMem()
            addArgsToMem()
        }
    }

    fun addArg(arg: String) {
        args.add(arg)
        removeAllArgsFromMem()
        addArgsToMem()
    }

    fun addArg(newargs: List<String>) {
        args.addAll(newargs)
        removeAllArgsFromMem()
        addArgsToMem()
    }

    fun addArgsToMem() {
        val registerSize = state.registerWidth / 8
        val intSize = 4
        if (!settings.setRegesOnInit) {
            return
        }
        var spv = if (getReg(2) == MemorySegments.STACK_BEGIN) {
            getReg(2)
        } else {
            getReg(11)
        } - 1
        var argv = ArrayList<Number>()
        var tmpargs = arrayListOf(linkedProgram.prog.name)
        tmpargs.addAll(args)
        for (arg in tmpargs) {
            spv = getReg(Registers.sp) - 1
            /*Got to add the null terminator as well!*/
            storeByte(spv, 0)
            setRegNoUndo(Registers.sp, spv)
            for (c in arg.reversed()) {
                spv = getReg(Registers.sp) - 1
                storeByte(spv, c.toInt())
                setRegNoUndo(Registers.sp, spv)
            }
            argv.add(spv)
        }
        spv -= (spv % registerSize)
        /**
         * Next we need to create the argv array.
         */
        // First have to allocate a new space and load the null to the end of the array.
        spv -= intSize
        storeWord(spv, 0)
        // Next, we need to add the different arg strings to our argv array.
        for (arg in argv.reversed()) {
            spv -= intSize
            storeWord(spv, arg)
        }
        /**
         * We need to store a0 (x10) to the argc and a1 (x11) to argv.
         */
        setRegNoUndo(Registers.a0, tmpargs.size)
        setRegNoUndo(Registers.a1, spv)
        setRegNoUndo(Registers.sp, spv)
        try {
            Renderer.updateRegister(Registers.sp, getReg(Registers.sp))
            Renderer.updateRegister(Registers.a0, getReg(Registers.a0))
            Renderer.updateRegister(Registers.a1, getReg(Registers.a1))
            Renderer.updateMemory(Renderer.activeMemoryAddress)
        } catch (e: Throwable) {}
    }

    var ecallMsg = ""
    var branched = false
    var jumped = false
    fun reset(keep_args: Boolean = false) {
        while (this.canUndo()) {
            this.undo()
        }
        this.branched = false
        this.jumped = false
        this.ecallMsg = ""
        this.stdout = ""
        cycles = 0
        exitcode = null
        val args = ArrayList(this.args)
        removeAllArgs()
        if (keep_args) {
            addArg(args)
        }
        state.reset()
    }

    fun trace(): Tracer {
        return Tracer(this)
    }

    fun canUndo() = !history.isEmpty()

    fun getReg(id: Int) = state.getReg(id)

    fun setReg(id: Int, v: Number) {
        preInstruction.add(RegisterDiff(id, getReg(id)))
        state.setReg(id, v)
        postInstruction.add(RegisterDiff(id, getReg(id)))
    }

    fun setRegNoUndo(id: Int, v: Number) {
        state.setReg(id, v)
    }

    fun getFReg(id: Int) = state.getFReg(id)

    fun setFReg(id: Int, v: Decimal) {
        preInstruction.add(FRegisterDiff(id, state.getFReg(id)))
        state.setFReg(id, v)
        postInstruction.add(FRegisterDiff(id, state.getFReg(id)))
    }

    fun setFRegNoUndo(id: Int, v: Decimal) {
        state.setFReg(id, v)
    }

    fun toggleBreakpointAt(idx: Int): Boolean {
//        breakpoints[idx] = !breakpoints[idx]
//        return breakpoints[idx]
        if (breakpoints.contains(idx)) {
            breakpoints.remove(idx)
            return false
        } else {
            breakpoints.add(idx)
            return true
        }
    }

    /* TODO Make this more efficient while robust! */
    fun atBreakpoint(): Boolean {
        val location = (getPC() - MemorySegments.TEXT_BEGIN).toLong()
        val inst = invInstOrderMapping[location.toInt()]
        if (inst == null) {
//            Renderer.displayWarning("""Could not find an instruction mapped to the current address when checking for a breakpoint!""")
            return ebreak
        }
//        return ebreak || breakpoints[inst]
//        return ebreak || breakpoints.contains(location.toInt())
        return ebreak xor breakpoints.contains(location.toInt() - 4)
    }

    fun getPC() = state.getPC()

    fun setPC(newPC: Number) {
        preInstruction.add(PCDiff(getPC()))
        state.setPC(newPC)
        postInstruction.add(PCDiff(getPC()))
    }

    fun incrementPC(inc: Number) {
        preInstruction.add(PCDiff(getPC()))
        state.incPC(inc)
        postInstruction.add(PCDiff(getPC()))
    }

    fun isValidAccess(addr: ByteAddress, bytes: Int) {
        if (!this.settings.allowAccessBtnStackHeap) {
            val upperAddr = addr + bytes
            val sp = state.getReg(Registers.sp)
            val heap = state.getHeapEnd()
            if ((addr > heap && addr < sp) ||
                (upperAddr > heap && upperAddr < sp)) {
                throw SimulatorError(
                        "Attempting to access uninitialized memory between the stack and heap. Attempting to access '$bytes' bytes at address '$addr'.",
                        handled = true)
            }
        }
    }

    fun loadByte(addr: ByteAddress): Int = state.mem.loadByte(addr)
    fun loadBytewCache(addr: ByteAddress): Int {
        if (this.settings.alignedAddress) {
            Memory.assertByteAligned(addr)
        }
        this.isValidAccess(addr, MemSize.BYTE.size)
        preInstruction.add(CacheDiff(Address(addr, MemSize.BYTE)))
        state.cache.read(Address(addr, MemSize.BYTE))
        postInstruction.add(CacheDiff(Address(addr, MemSize.BYTE)))
        return this.loadByte(addr)
    }

    fun loadHalfWord(addr: ByteAddress): Int = state.mem.loadHalfWord(addr)
    fun loadHalfWordwCache(addr: ByteAddress): Int {
        if (this.settings.alignedAddress) {
            Memory.assertHalfWordAligned(addr)
        }
        this.isValidAccess(addr, MemSize.HALF.size)
        preInstruction.add(CacheDiff(Address(addr, MemSize.HALF)))
        state.cache.read(Address(addr, MemSize.HALF))
        postInstruction.add(CacheDiff(Address(addr, MemSize.HALF)))
        return this.loadHalfWord(addr)
    }

    fun loadWord(addr: ByteAddress): Int = state.mem.loadWord(addr)
    fun loadWordwCache(addr: ByteAddress): Int {
        if (this.settings.alignedAddress) {
            Memory.assertWordAligned(addr)
        }
        this.isValidAccess(addr, MemSize.WORD.size)
        preInstruction.add(CacheDiff(Address(addr, MemSize.WORD)))
        state.cache.read(Address(addr, MemSize.WORD))
        postInstruction.add(CacheDiff(Address(addr, MemSize.WORD)))
        return this.loadWord(addr)
    }

    fun loadLong(addr: ByteAddress): Long = state.mem.loadLong(addr)
    fun loadLongwCache(addr: ByteAddress): Long {
        if (this.settings.alignedAddress) {
            Memory.assertLongAligned(addr)
        }
        this.isValidAccess(addr, MemSize.LONG.size)
        preInstruction.add(CacheDiff(Address(addr, MemSize.LONG)))
        state.cache.read(Address(addr, MemSize.LONG))
        postInstruction.add(CacheDiff(Address(addr, MemSize.LONG)))
        return this.loadLong(addr)
    }

    fun storeByte(addr: ByteAddress, value: Number) {
        preInstruction.add(MemoryDiff(addr, loadWord(addr)))
        state.mem.storeByte(addr, value)
        postInstruction.add(MemoryDiff(addr, loadWord(addr)))
        this.storeTextOverrideCheck(addr, value, MemSize.BYTE)
    }
    fun storeBytewCache(addr: ByteAddress, value: Number) {
        if (this.settings.alignedAddress) {
            Memory.assertByteAligned(addr)
        }
        // FIXME change the cast to maxpc to something more generic or make the iterator be generic.
        if (!this.settings.mutableText && addr in (MemorySegments.TEXT_BEGIN + 1 - MemSize.BYTE.size)..state.getMaxPC().toInt()) {
            throw StoreError("You are attempting to edit the text of the program though the program is set to immutable at address " + Renderer.toHex(addr) + "!")
        }
        this.isValidAccess(addr, MemSize.BYTE.size)
        preInstruction.add(CacheDiff(Address(addr, MemSize.BYTE)))
        state.cache.write(Address(addr, MemSize.BYTE))
        this.storeByte(addr, value)
        postInstruction.add(CacheDiff(Address(addr, MemSize.BYTE)))
    }

    fun storeHalfWord(addr: ByteAddress, value: Number) {
        preInstruction.add(MemoryDiff(addr, loadWord(addr)))
        state.mem.storeHalfWord(addr, value)
        postInstruction.add(MemoryDiff(addr, loadWord(addr)))
        this.storeTextOverrideCheck(addr, value, MemSize.HALF)
    }
    fun storeHalfWordwCache(addr: ByteAddress, value: Number) {
        if (this.settings.alignedAddress) {
            Memory.assertHalfWordAligned(addr)
        }
        if (!this.settings.mutableText && addr in (MemorySegments.TEXT_BEGIN + 1 - MemSize.HALF.size)..state.getMaxPC().toInt()) {
            throw StoreError("You are attempting to edit the text of the program though the program is set to immutable at address " + Renderer.toHex(addr) + "!")
        }
        this.isValidAccess(addr, MemSize.HALF.size)
        preInstruction.add(CacheDiff(Address(addr, MemSize.HALF)))
        state.cache.write(Address(addr, MemSize.HALF))
        this.storeHalfWord(addr, value)
        postInstruction.add(CacheDiff(Address(addr, MemSize.HALF)))
    }

    fun storeWord(addr: ByteAddress, value: Number) {
        preInstruction.add(MemoryDiff(addr, loadWord(addr)))
        state.mem.storeWord(addr, value)
        postInstruction.add(MemoryDiff(addr, loadWord(addr)))
        this.storeTextOverrideCheck(addr, value, MemSize.WORD)
    }
    fun storeWordwCache(addr: ByteAddress, value: Number) {
        if (this.settings.alignedAddress) {
            Memory.assertWordAligned(addr)
        }
        if (!this.settings.mutableText && addr in (MemorySegments.TEXT_BEGIN + 1 - MemSize.WORD.size)..state.getMaxPC().toInt()) {
            throw StoreError("You are attempting to edit the text of the program though the program is set to immutable at address " + Renderer.toHex(addr) + "!")
        }
        this.isValidAccess(addr, MemSize.WORD.size)
        preInstruction.add(CacheDiff(Address(addr, MemSize.WORD)))
        state.cache.write(Address(addr, MemSize.WORD))
        this.storeWord(addr, value)
        postInstruction.add(CacheDiff(Address(addr, MemSize.WORD)))
    }

    fun storeLong(addr: ByteAddress, value: Number) {
        preInstruction.add(MemoryDiff(addr, loadLong(addr)))
        state.mem.storeLong(addr, value)
        postInstruction.add(MemoryDiff(addr, loadLong(addr)))
        this.storeTextOverrideCheck(addr, value, MemSize.LONG)
    }
    fun storeLongwCache(addr: ByteAddress, value: Number) {
        if (this.settings.alignedAddress) {
            Memory.assertWordAligned(addr)
        }
        if (!this.settings.mutableText && addr in (MemorySegments.TEXT_BEGIN + 1 - MemSize.WORD.size)..state.getMaxPC().toInt()) {
            throw StoreError("You are attempting to edit the text of the program though the program is set to immutable at address " + Renderer.toHex(addr) + "!")
        }
        this.isValidAccess(addr, MemSize.LONG.size)
        preInstruction.add(CacheDiff(Address(addr, MemSize.LONG)))
        state.cache.write(Address(addr, MemSize.LONG))
        this.storeLong(addr, value)
        postInstruction.add(CacheDiff(Address(addr, MemSize.LONG)))
    }

    fun storeTextOverrideCheck(addr: ByteAddress, value: Number, size: MemSize) {
        /*Here, we will check if we are writing to memory*/
        if (addr in (MemorySegments.TEXT_BEGIN until state.getMaxPC().toInt()) || (addr + size.size - MemSize.BYTE.size) in (MemorySegments.TEXT_BEGIN until state.getMaxPC().toInt())) {
            try {
                val adjAddr = ((addr / MemSize.WORD.size) * MemSize.WORD.size)
                val lowerAddr = adjAddr - MemorySegments.TEXT_BEGIN
                var newInst = this.state.mem.loadWord(adjAddr)
                preInstruction.add(Renderer.updateProgramListing(lowerAddr, newInst))
                if ((lowerAddr + MemorySegments.TEXT_BEGIN) != addr && (lowerAddr + MemSize.WORD.size - MemSize.BYTE.size) < state.getMaxPC()) {
                    var newInst2 = this.state.mem.loadWord(adjAddr + MemSize.WORD.size)
                    preInstruction.add(Renderer.updateProgramListing((lowerAddr) + 4, newInst2))
                }
            } catch (e: Throwable) { /*This is to not error the tests.*/ }
        }
    }

    fun getHeapEnd() = state.getHeapEnd()

    fun addHeapSpace(bytes: Number) {
        if (willHeapOverrideStack(bytes)) {
            throw SimulatorError("The heap has grown into the stack.")
        }
        preInstruction.add(HeapSpaceDiff(state.getHeapEnd()))
        state.incHeapEnd(bytes)
        postInstruction.add(HeapSpaceDiff(state.getHeapEnd()))
    }

    fun willHeapOverrideStack(bytes: Number): Boolean {
        return (getHeapEnd() + bytes) >= getReg(Registers.sp)
    }

    private fun getInstructionLength(short0: Int): Int {
        if ((short0 and 0b11) != 0b11) {
            return 2
        } else if ((short0 and 0b11111) != 0b11111) {
            return 4
        } else if ((short0 and 0b111111) == 0b011111) {
            return 6
        } else if ((short0 and 0b1111111) == 0b111111) {
            return 8
        } else {
            throw SimulatorError("instruction lengths > 8 not supported")
        }
    }

    fun getNextInstruction(): MachineCode {
        val pc = getPC()
        var instruction: ULong = loadHalfWord(pc).toULong()
        val length = getInstructionLength(instruction.toInt())
        for (i in 1 until length / 2) {
            val short = loadHalfWord(pc + 2).toULong()
            instruction = (short shl 16 * i) or instruction
        }
        val intStruction = instruction.toInt()
        val mcode = MachineCode(intStruction)
        mcode.length = length
        return mcode
    }

    fun memcpy(destaddr: Int, srcaddr: Int, size: Int): Int {
        var dest = destaddr
        var src = srcaddr
        var s = size
        while (s > 0) {
            storeByte(dest, loadByte(src))
            dest++
            src++
            s--
        }
        return destaddr
    }

    fun memset(destaddr: Int, item: Int, size: Int): Int {
        var dest = destaddr
        var s = size
        while (s > 0) {
            storeByte(dest, item)
            dest++
            s--
        }
        return destaddr
    }

//    fun dump(sim: Simulator): CoreDump {
//        val d = HashMap<String, Any>()
//        d.put("time", Date.now().toString())
//        val integer = HashMap<Int, Number>()
//        val floating = HashMap<Int, Decimal>()
//        for (i in 1 until 32) {
//            integer[i] = sim.getReg(i)
//            floating[i] = sim.getFReg(i)
//        }
//        val registers = HashMap<String, HashMap<Int, Number>>()
//        d.put("registers", registers)
//        val memory = HashMap<String, Short>()
//        d.put("memory", memory)
//        return d
//    }
}
//
// data class CoreDump(
//        var time: String,
//        var regisers: CoreDumpRegisters,
//        var memory: HashMap<Int, Int>
// )
//
// data class CoreDumpRegisters(
//        var integer: CoreDumpRegister,
//        var floating: CoreDumpRegister
// )
//
// data class CoreDumpRegister(
//        var id: String,
//        var value: String
// )