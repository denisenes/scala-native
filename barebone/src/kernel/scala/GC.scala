package kernel

import scala.scalanative.unsafe.*
import scala.scalanative.runtime.{Intrinsics, RawPtr}
import scala.scalanative.runtime.Intrinsics.*
import kernel.System

object GC {
  @extern
  object HeapBounds {
    @name("__gc_heap_lo") var lo: RawPtr = extern
    @name("__gc_heap_hi") var hi: RawPtr = extern
  }

  private final val RttiOffset = 0
  private final val ArrayLengthOffset = 8
  private final val ArrayStrideOffset = 12
  private final val RttiSizeOffset = 32

  private final val StateCurrentOffset = 0
  private final val StateEndOffset = 8
  private final val StateUsedOffset = 16
  private final val StateBytes = 32

  private def hOffset(offset: Int): RawPtr = elemRawPtr(HeapBounds.lo, offset)

  private def outOfMemory(): Unit = System.fatal(c"[GC]: OOM")

  @exported("k_scalanative_GC_init")
  def init(): Unit = {
    val lo = castRawPtrToLong(HeapBounds.lo)
    val hi = castRawPtrToLong(HeapBounds.hi)
    storeLong(hOffset(StateCurrentOffset), lo + StateBytes)
    storeLong(hOffset(StateEndOffset), hi)
    storeLong(hOffset(StateUsedOffset), 0L)
  }

  @exported("k_scalanative_GC_info")
  def info(): Unit = {
    System.print(c"[MM] heap start: ")
    System.println(loadRawPtr(hOffset(StateCurrentOffset)))
    System.print(c"[MM] heap end:   ")
    System.println(loadRawPtr(hOffset(StateEndOffset)))
  }

  private def bumpAlloc(info: RawPtr, size: Long): RawPtr = {
    var alignedSize = (size + 7L) & ~7L
    if (alignedSize < 8L) alignedSize = 8L

    val current = loadLong(hOffset(StateCurrentOffset))
    val end = loadLong(hOffset(StateEndOffset))
    val next = current + alignedSize

    if (next < current || next > end) 
      outOfMemory()

    storeLong(hOffset(StateCurrentOffset), next)
    storeLong(hOffset(StateUsedOffset), loadLong(hOffset(StateUsedOffset)) + alignedSize)

    val obj = castLongToRawPtr(current)
    // Raw allocations (malloc) pass a null info and get no rtti word.
    if (castRawPtrToLong(info) != 0L)
      storeRawPtr(elemRawPtr(obj, RttiOffset), info) // Object.rtti
    obj
  }

  /** Raw bump allocation without an rtti word. Backs libc `malloc`. */
  def allocRaw(size: Long): RawPtr = bumpAlloc(castLongToRawPtr(0L), size)

  @exported("k_scalanative_GC_alloc")
  def gcAlloc(info: RawPtr, size: Long): RawPtr = bumpAlloc(info, size)

  @exported("k_scalanative_GC_alloc_small")
  def gcAllocSmall(info: RawPtr, size: Long): RawPtr = bumpAlloc(info, size)

  @exported("k_scalanative_GC_alloc_large")
  def gcAllocLarge(info: RawPtr, size: Long): RawPtr = bumpAlloc(info, size)

  @exported("k_scalanative_GC_alloc_array")
  def gcAllocArray(info: RawPtr, length: Long, stride: Long): RawPtr = {
    val headerSize = loadInt(elemRawPtr(info, RttiSizeOffset)).toLong
    val arr = bumpAlloc(info, headerSize + length * stride)
    storeInt(elemRawPtr(arr, ArrayLengthOffset), length.toInt)
    storeInt(elemRawPtr(arr, ArrayStrideOffset), stride.toInt)
    arr
  }

  @exported("k_scalanative_GC_collect")
  def gcCollect(): Unit = () // bump allocator: nothing is ever freed

  @exported("k_scalanative_GC_set_weak_references_collected_callback")
  def gcSetWeakReferencesCollectedCallback(callback: CFuncPtr0[Unit]): Unit = ()

  @exported("k_scalanative_GC_get_init_heapsize")
  def gcGetInitHeapSize(): Long = 0L

  @exported("k_scalanative_GC_get_max_heapsize")
  def gcGetMaxHeapSize(): Long = castRawPtrToLong(HeapBounds.hi) - castRawPtrToLong(HeapBounds.lo)

  @exported("k_scalanative_GC_get_used_heapsize")
  def gcGetUsedHeapSize(): Long = loadLong(hOffset(StateUsedOffset))

  @exported("k_scalanative_GC_stats_collection_total")
  def gcStatsCollectionTotal(): Long = -1L

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
