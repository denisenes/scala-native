package kernel

import scala.scalanative.unsafe.*
import scala.scalanative.runtime.{Intrinsics, RawPtr, fromRawPtr, toRawPtr}
import scala.scalanative.runtime.Intrinsics.*
import kernel.System

object GC {
  @extern
  object HeapBounds {
    @name("__gc_heap_lo") var lo: RawPtr = extern
    @name("__gc_heap_hi") var hi: RawPtr = extern
    @name("__gc_data_lo") var dataLo: RawPtr = extern
    @name("__gc_data_hi") var dataHi: RawPtr = extern
    @name("__gc_stack_top") var stackTop: RawPtr = extern
  }

  @extern
  private object GCSupport {
    def dump_non_volatile_regs(): Unit = extern
  }

  // TODO: review and fix this vibe-coded shit, it doesn't work in some cases
  
  private final val WordSize = 8L
  private final val BlockSize = 2048L
  private final val BlockHeaderSize = 64
  private final val MaxObjectSize = BlockSize / 2
  private final val UnitCount = 128
  private final val BitmapBytes = 32

  private final val BlockBitmap = 0
  private final val BlockUnit = 32
  private final val BlockSlots = 36
  private final val BlockCursor = 40
  private final val BlockNext = 48

  private final val MarkStackBaseOffset = 0
  private final val MarkStackTopOffset = 8
  private final val MarkStackEndOffset = 16
  private final val CollectingOffset = 24
  private final val BlocksStartOffset = 32
  private final val BlocksEndOffset = 40
  private final val EmptyHeadOffset = 48
  private final val StatLiveOffset = 56
  private final val StatEmptyOffset = 64
  private final val StatPartialOffset = 72
  private final val StatFullOffset = 80
  private final val StatCollectionsOffset = 88
  private final val ArenaTopOffset = 96
  private final val ArenaEndOffset = 104
  private final val HeapletsOffset = 128
  private final val PartialHeadsOffset = HeapletsOffset + UnitCount * 8
  private final val MarkStackOffset = PartialHeadsOffset + UnitCount * 8
  private final val MarkStackEntries = 16384
  private final val LogScratchOffset = MarkStackOffset + MarkStackEntries * 8
  private final val LogScratchSize = 512
  private final val ArenaOffset = LogScratchOffset + LogScratchSize
  private final val ArenaSize = 4096

  private final val RttiOffset = 0
  private final val ArrayLengthOffset = 8
  private final val ArrayStrideOffset = 12
  private final val RttiSizeOffset = 32

  private inline def meta(offset: Int): RawPtr = elemRawPtr(HeapBounds.lo, offset)
  private inline def at(pointer: RawPtr, offset: Long): RawPtr = elemRawPtr(pointer, castLongToRawSize(offset))
  private inline def nullPtr: RawPtr = castLongToRawPtr(0L)
  private inline def isNull(pointer: RawPtr): Boolean = castRawPtrToLong(pointer) == 0L

  private def outOfMemory(): Unit = System.fatal(c"[GC]: OOM")

  private inline def bit(block: RawPtr, slot: Int): Boolean =
    (loadByte(elemRawPtr(block, BlockBitmap + (slot >> 3))).toInt & (1 << (slot & 7))) != 0

  private inline def setBit(block: RawPtr, slot: Int): Unit = {
    val p = elemRawPtr(block, BlockBitmap + (slot >> 3))
    storeByte(p, (loadByte(p).toInt | (1 << (slot & 7))).toByte)
  }

  private inline def clearBit(block: RawPtr, slot: Int): Unit = {
    val p = elemRawPtr(block, BlockBitmap + (slot >> 3))
    storeByte(p, (loadByte(p).toInt & ~(1 << (slot & 7))).toByte)
  }

  private def clearBitmap(block: RawPtr): Unit = {
    var i = 0
    while i < BitmapBytes do
      storeByte(elemRawPtr(block, BlockBitmap + i), 0)
      i += 1
  }

  private def unitOf(block: RawPtr): Int = loadByte(elemRawPtr(block, BlockUnit)).toInt & 0xff
  private def slotsOf(block: RawPtr): Int = loadInt(elemRawPtr(block, BlockSlots))
  private def slotSizeOf(block: RawPtr): Long = unitOf(block).toLong * WordSize
  private def slotAddress(block: RawPtr, slot: Int): RawPtr =
    at(block, BlockHeaderSize.toLong + slot.toLong * slotSizeOf(block))
  private def heapletOffset(unit: Int): Int = HeapletsOffset + (unit - 1) * 8
  private def partialOffset(unit: Int): Int = PartialHeadsOffset + (unit - 1) * 8

  private def pushBlock(headOffset: Int, block: RawPtr): Unit = {
    storeRawPtr(elemRawPtr(block, BlockNext), loadRawPtr(meta(headOffset)))
    storeRawPtr(meta(headOffset), block)
  }

  private def popBlock(headOffset: Int): RawPtr = {
    val block = loadRawPtr(meta(headOffset))
    if !isNull(block) then
      storeRawPtr(meta(headOffset), loadRawPtr(elemRawPtr(block, BlockNext)))
    block
  }

  private def initBlock(block: RawPtr, unit: Int): Unit = {
    clearBitmap(block)
    storeByte(elemRawPtr(block, BlockUnit), unit.toByte)
    storeInt(
      elemRawPtr(block, BlockSlots),
      ((BlockSize - BlockHeaderSize) / (unit.toLong * WordSize)).toInt
    )
    storeInt(elemRawPtr(block, BlockCursor), 0)
    storeRawPtr(elemRawPtr(block, BlockNext), nullPtr)
  }

  private def takeBlock(unit: Int): RawPtr = {
    var block = popBlock(partialOffset(unit))
    if isNull(block) then
      block = popBlock(EmptyHeadOffset)
      if !isNull(block) then initBlock(block, unit)
    block
  }

  private def tryAlloc(unit: Int): RawPtr = {
    var result = nullPtr
    var done = false
    while !done do
      var block = loadRawPtr(meta(heapletOffset(unit)))
      if isNull(block) then
        block = takeBlock(unit)
        if isNull(block) then done = true
        else storeRawPtr(meta(heapletOffset(unit)), block)
      if !done then
        val slots = slotsOf(block)
        var slot = loadInt(elemRawPtr(block, BlockCursor))
        // Lazy sweep: skip alive slots (washing their marks), reuse dead ones.
        while slot < slots && bit(block, slot) do
          clearBit(block, slot)
          slot += 1
        if slot < slots then
          storeInt(elemRawPtr(block, BlockCursor), slot + 1)
          result = slotAddress(block, slot)
          done = true
        else storeRawPtr(meta(heapletOffset(unit)), nullPtr)
    result
  }

  // Memory handed out while a collection is running: the logging code boxes
  // Ptr values (c"" literals, fromRawPtr), and those boxes must neither
  // re-enter the collector nor touch block metadata. Served from a private
  // scratch arena, reset per collection; exhaustion halts without allocating.
  private def arenaAlloc(size: Long, info: RawPtr): RawPtr = {
    var slotSize = (size + 7L) & ~7L
    if slotSize < WordSize then slotSize = WordSize
    val top = loadLong(meta(ArenaTopOffset))
    val next = top + slotSize
    if next > loadLong(meta(ArenaEndOffset)) then Platform.platform_halt()
    storeLong(meta(ArenaTopOffset), next)
    val obj = castLongToRawPtr(top)
    if !isNull(info) then storeRawPtr(elemRawPtr(obj, RttiOffset), info)
    obj
  }

  private def alloc(size: Long, info: RawPtr): RawPtr = {
    if loadLong(meta(CollectingOffset)) != 0L then arenaAlloc(size, info)
    else {
    if size > MaxObjectSize then 
      System.fatal(c"[GC]: object too large")

    var slotSize = (size + 7L) & ~7L
    if slotSize < WordSize then slotSize = WordSize
    val unit = (slotSize / WordSize).toInt

    var obj = tryAlloc(unit)
    if isNull(obj) then
      collect()
      obj = tryAlloc(unit)
      if isNull(obj) then outOfMemory()

    if !isNull(info) then storeRawPtr(elemRawPtr(obj, RttiOffset), info)
    obj
    }
  }

  def allocRaw(size: Long): RawPtr = alloc(size, nullPtr)

  @exported("k_scalanative_GC_alloc")
  def gcAlloc(info: RawPtr, size: Long): RawPtr = alloc(size, info)

  @exported("k_scalanative_GC_alloc_small")
  def gcAllocSmall(info: RawPtr, size: Long): RawPtr = alloc(size, info)

  @exported("k_scalanative_GC_alloc_large")
  def gcAllocLarge(info: RawPtr, size: Long): RawPtr = alloc(size, info)

  @exported("k_scalanative_GC_alloc_array")
  def gcAllocArray(info: RawPtr, length: Long, stride: Long): RawPtr = {
    val headerSize = loadInt(elemRawPtr(info, RttiSizeOffset)).toLong
    val arr = alloc(headerSize + length * stride, info)
    storeInt(elemRawPtr(arr, ArrayLengthOffset), length.toInt)
    storeInt(elemRawPtr(arr, ArrayStrideOffset), stride.toInt)
    arr
  }
  @exported("k_scalanative_GC_init")
  def init(): Unit = {
    val lo = castRawPtrToLong(HeapBounds.lo)
    val hi = castRawPtrToLong(HeapBounds.hi)

    val stackBase = at(HeapBounds.lo, MarkStackOffset.toLong)
    storeRawPtr(meta(MarkStackBaseOffset), stackBase)
    storeRawPtr(meta(MarkStackTopOffset), stackBase)
    storeRawPtr(meta(MarkStackEndOffset), at(stackBase, MarkStackEntries.toLong * WordSize))
    storeLong(meta(CollectingOffset), 0L)
    storeLong(meta(EmptyHeadOffset), 0L)
    storeLong(meta(StatCollectionsOffset), 0L)
    storeLong(meta(ArenaTopOffset), lo + ArenaOffset)
    storeLong(meta(ArenaEndOffset), lo + ArenaOffset + ArenaSize)

    var i = 1
    while i <= UnitCount do
      storeRawPtr(meta(heapletOffset(i)), nullPtr)
      storeRawPtr(meta(partialOffset(i)), nullPtr)
      i += 1

    val metadataEnd = lo + ArenaOffset + ArenaSize
    val blocksStart = (metadataEnd + BlockSize - 1L) & ~(BlockSize - 1L)
    val blocksEnd = hi & ~(BlockSize - 1L)
    storeLong(meta(BlocksStartOffset), blocksStart)
    storeLong(meta(BlocksEndOffset), blocksEnd)

    var block = blocksStart
    while block < blocksEnd do
      val blockPtr = castLongToRawPtr(block)
      storeByte(elemRawPtr(blockPtr, BlockUnit), 0)
      storeInt(elemRawPtr(blockPtr, BlockSlots), 0)
      storeInt(elemRawPtr(blockPtr, BlockCursor), 0)
      pushBlock(EmptyHeadOffset, blockPtr)
      block += BlockSize
  }

  @exported("k_scalanative_GC_info")
  def info(): Unit = {
    System.print(c"[MM] heap start: ")
    System.println(HeapBounds.lo)
    System.print(c"[MM] heap end:   ")
    System.println(HeapBounds.hi)
  }

  private def washing(): Unit = {
    var i = 1
    while i <= UnitCount do
      val current = loadRawPtr(meta(heapletOffset(i)))
      if !isNull(current) then clearBitmap(current)
      var block = loadRawPtr(meta(partialOffset(i)))
      while !isNull(block) do
        clearBitmap(block)
        block = loadRawPtr(elemRawPtr(block, BlockNext))
      i += 1
  }

  @exported("k_gc_mark")
  def markRoots(): Unit = {
    scanRange(castRawPtrToLong(Intrinsics.stackalloc[Byte]()), castRawPtrToLong(HeapBounds.stackTop))
    scanRange(castRawPtrToLong(HeapBounds.dataLo), castRawPtrToLong(HeapBounds.dataHi))
    drainMarkStack()
  }

  private def scanRange(from: Long, until: Long): Unit = {
    var address = (from + WordSize - 1L) & ~(WordSize - 1L)
    while address + WordSize <= until do
      markCandidate(loadRawPtr(castLongToRawPtr(address)))
      address += WordSize
  }

  private def markCandidate(pointer: RawPtr): Unit = {
    val address = castRawPtrToLong(pointer)
    val blockStart = address & ~(BlockSize - 1L)
    if blockStart >= loadLong(meta(BlocksStartOffset)) &&
      blockStart < loadLong(meta(BlocksEndOffset))
    then
      val block = castLongToRawPtr(blockStart)
      val unit = unitOf(block)
      if unit != 0 then
        val areaStart = blockStart + BlockHeaderSize
        val areaEnd = areaStart + slotsOf(block).toLong * slotSizeOf(block)
        if address >= areaStart && address < areaEnd then
          val slot = ((address - areaStart) / slotSizeOf(block)).toInt
          if !bit(block, slot) then
            setBit(block, slot)
            val top = loadRawPtr(meta(MarkStackTopOffset))
            if castRawPtrToLong(top) == loadLong(meta(MarkStackEndOffset)) then
              System.fatal(c"[GC]: mark stack overflow")
            storeRawPtr(top, slotAddress(block, slot))
            storeRawPtr(meta(MarkStackTopOffset), elemRawPtr(top, 8))
  }

  private def popMark(): RawPtr = {
    val top = elemRawPtr(loadRawPtr(meta(MarkStackTopOffset)), -8)
    storeRawPtr(meta(MarkStackTopOffset), top)
    loadRawPtr(top)
  }

  private def drainMarkStack(): Unit = {
    val base = castRawPtrToLong(loadRawPtr(meta(MarkStackBaseOffset)))
    // Always re-read the shared top: markCandidate pushes above it while we
    // drain, and those entries must be drained too.
    while loadLong(meta(MarkStackTopOffset)) != base do
      val obj = popMark()
      val objectStart = castRawPtrToLong(obj)
      val block = castLongToRawPtr(objectStart & ~(BlockSize - 1L))
      scanRange(objectStart, objectStart + slotSizeOf(block))
  }

  private def classify(): Unit = {
    storeLong(meta(EmptyHeadOffset), 0L)
    storeLong(meta(StatLiveOffset), 0L)
    storeLong(meta(StatEmptyOffset), 0L)
    storeLong(meta(StatPartialOffset), 0L)
    storeLong(meta(StatFullOffset), 0L)
    var i = 1
    while i <= UnitCount do
      storeRawPtr(meta(heapletOffset(i)), nullPtr)
      storeRawPtr(meta(partialOffset(i)), nullPtr)
      i += 1

    val blocksEnd = loadLong(meta(BlocksEndOffset))
    var block = castLongToRawPtr(loadLong(meta(BlocksStartOffset)))
    while castRawPtrToLong(block) < blocksEnd do
      storeInt(elemRawPtr(block, BlockCursor), 0)
      storeRawPtr(elemRawPtr(block, BlockNext), nullPtr)
      val slots = slotsOf(block)
      var live = 0
      var slot = 0
      while slot < slots do
        if bit(block, slot) then live += 1
        slot += 1
      if live == 0 || slots == 0 then
        pushBlock(EmptyHeadOffset, block)
        storeLong(meta(StatEmptyOffset), loadLong(meta(StatEmptyOffset)) + 1L)
      else if live == slots then
        clearBitmap(block) // swept clean now, marks are rebuilt at the next cycle
        storeLong(meta(StatFullOffset), loadLong(meta(StatFullOffset)) + 1L)
      else
        pushBlock(partialOffset(unitOf(block)), block)
        storeLong(meta(StatPartialOffset), loadLong(meta(StatPartialOffset)) + 1L)
      storeLong(
        meta(StatLiveOffset),
        loadLong(meta(StatLiveOffset)) + live.toLong * slotSizeOf(block)
      )
      block = at(block, BlockSize)
  }

  private def collect(): Unit = {
    storeLong(meta(CollectingOffset), 1L)
    storeLong(meta(ArenaTopOffset), loadLong(meta(ArenaEndOffset)) - ArenaSize)
    storeLong(meta(StatCollectionsOffset), loadLong(meta(StatCollectionsOffset)) + 1L)
    washing()
    GCSupport.dump_non_volatile_regs()
    classify()
    printStats()
    storeLong(meta(CollectingOffset), 0L)
  }

  @exported("k_scalanative_GC_collect")
  def gcCollect(): Unit = collect()
  // GC-end logging allocates only Ptr boxes from the scratch arena (see
  // arenaAlloc): text and numbers are formatted into a reserved buffer and
  // printed through the CString path without touching the managed heap.
  private def printStats(): Unit = {
    val scratch = at(HeapBounds.lo, LogScratchOffset.toLong)
    var p = scratch
    p = appendText(p, c"[GC] live: ")
    p = appendNumber(p, loadLong(meta(StatLiveOffset)))
    p = appendText(p, c" B, blocks: empty ")
    p = appendNumber(p, loadLong(meta(StatEmptyOffset)))
    p = appendText(p, c", partial ")
    p = appendNumber(p, loadLong(meta(StatPartialOffset)))
    p = appendText(p, c", full ")
    p = appendNumber(p, loadLong(meta(StatFullOffset)))
    p = appendText(p, c"\n")
    storeByte(p, 0)
    System.print(fromRawPtr[Byte](scratch))
  }

  private def appendText(p: RawPtr, text: CString): RawPtr = {
    var src = toRawPtr(text)
    var dst = p
    var char = loadByte(src)
    while char != 0 do
      storeByte(dst, char)
      dst = elemRawPtr(dst, 1)
      src = elemRawPtr(src, 1)
      char = loadByte(src)
    dst
  }

  private def appendNumber(p: RawPtr, value: Long): RawPtr = {
    val end = at(HeapBounds.lo, (LogScratchOffset + LogScratchSize).toLong)
    var w = end
    var rest = value
    if rest == 0L then
      w = elemRawPtr(w, -1)
      storeByte(w, '0'.toByte)
    else
      while rest != 0L do
        val digit = (rest % 10L).toInt
        w = elemRawPtr(w, -1)
        storeByte(w, ('0' + (if digit < 0 then -digit else digit)).toByte)
        rest = rest / 10L
    if value < 0L then
      w = elemRawPtr(w, -1)
      storeByte(w, '-'.toByte)
    var src = w
    var dst = p
    while castRawPtrToLong(src) < castRawPtrToLong(end) do
      storeByte(dst, loadByte(src))
      dst = elemRawPtr(dst, 1)
      src = elemRawPtr(src, 1)
    dst
  }

  @exported("k_scalanative_GC_set_weak_references_collected_callback")
  def gcSetWeakReferencesCollectedCallback(callback: CFuncPtr0[Unit]): Unit = ()

  @exported("k_scalanative_GC_get_init_heapsize")
  def gcGetInitHeapSize(): Long = 0L

  @exported("k_scalanative_GC_get_max_heapsize")
  def gcGetMaxHeapSize(): Long = castRawPtrToLong(HeapBounds.hi) - castRawPtrToLong(HeapBounds.lo)

  @exported("k_scalanative_GC_get_used_heapsize")
  def gcGetUsedHeapSize(): Long = loadLong(meta(StatLiveOffset))

  @exported("k_scalanative_GC_stats_collection_total")
  def gcStatsCollectionTotal(): Long = loadLong(meta(StatCollectionsOffset))

  @exported("k_scalanative_GC_stats_collection_duration_total")
  def gcStatsCollectionDurationTotal(): Long = -1L

  // Stop-the-world machinery: single-threaded bare metal, all no-ops.
  @exported("k_scalanative_GC_set_mutator_thread_state")
  def gcSetMutatorThreadState(state: Int): Unit = ()

  @exported("k_scalanative_GC_yield")
  def gcYield(): Unit = ()

  @exported("k_scalanative_GC_add_roots")
  def gcAddRoots(addrLow: RawPtr, addrHigh: RawPtr): Unit = ()

  @exported("k_scalanative_GC_remove_roots")
  def gcRemoveRoots(addrLow: RawPtr, addrHigh: RawPtr): Unit = ()
}