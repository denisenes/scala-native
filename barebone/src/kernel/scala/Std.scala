package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr, toRawPtr}
import scala.scalanative.unsafe.*

object Std {

  inline def at(pointer: RawPtr, byteOffset: Long): RawPtr =
    Intrinsics.elemRawPtr(pointer, Intrinsics.castLongToRawSize(byteOffset))

  @exported("strlen")
  def strlen(text: RawPtr): Long = {
    var length = 0L
    while Intrinsics.loadByte(at(text, length)) != 0 do length += 1
    length
  }

  def nullPtr: RawPtr = Intrinsics.castLongToRawPtr(0L)

  @exported("strchr")
  def strchr(text: RawPtr, character: Int): RawPtr = {
    val target = character.toByte
    var index = 0L
    var result = nullPtr
    var done = false
    while !done do
      val current = Intrinsics.loadByte(at(text, index))
      if current == target then
        result = at(text, index)
        done = true
      else if current == 0 then done = true
      else index += 1
    result
  }

  @exported("strrchr")
  def strrchr(text: RawPtr, character: Int): RawPtr = {
    val target = character.toByte
    var length = 0L
    while Intrinsics.loadByte(at(text, length)) != 0 do length += 1
    var result = nullPtr
    var done = false
    while !done do
      // Start at the terminating NUL: strrchr(s, '\0') addresses it.
      if Intrinsics.loadByte(at(text, length)) == target then
        result = at(text, length)
        done = true
      else if length == 0 then done = true
      else length -= 1
    result
  }

  @exported("strcpy")
  def strcpy(dst: RawPtr, src: RawPtr): RawPtr = {
    var index = 0L
    var current = Intrinsics.loadByte(at(src, index))
    while current != 0 do
      Intrinsics.storeByte(at(dst, index), current)
      index += 1
      current = Intrinsics.loadByte(at(src, index))
    Intrinsics.storeByte(at(dst, index), 0) // terminating NUL
    dst
  }

  @exported("strncpy")
  def strncpy(dst: RawPtr, src: RawPtr, count: Long): RawPtr = {
    var index = 0L
    var srcEnded = false
    while index < count do
      val current =
        if srcEnded then 0.toByte
        else
          val byte = Intrinsics.loadByte(at(src, index))
          if byte == 0 then srcEnded = true
          byte
      Intrinsics.storeByte(at(dst, index), current)
      index += 1
    dst
  }

  @exported("strncmp")
  def strncmp(left: RawPtr, right: RawPtr, count: Long): Int = {
    var index = 0L
    var result = 0
    var done = false
    while !done do
      if index >= count then done = true
      else
        val l = Intrinsics.loadByte(at(left, index))
        val r = Intrinsics.loadByte(at(right, index))
        if l != r then
          result = Intrinsics.byteToUInt(l) - Intrinsics.byteToUInt(r)
          done = true
        else if l == 0 then done = true
        else index += 1
    result
  }

  @exported("strerror")
  def strerror(errnum: Int): RawPtr = toRawPtr(c"error")

  /* ---------- memory ---------- */

  @exported("memcpy")
  def memcpy(dst: RawPtr, src: RawPtr, count: Long): RawPtr = {
    var index = 0L
    while index < count do
      Intrinsics.storeByte(at(dst, index), Intrinsics.loadByte(at(src, index)))
      index += 1
    dst
  }

  @exported("memmove")
  def memmove(dst: RawPtr, src: RawPtr, count: Long): RawPtr = {
    val dstAddr = Intrinsics.castRawPtrToLong(dst)
    val srcAddr = Intrinsics.castRawPtrToLong(src)
    if dstAddr > srcAddr && dstAddr < srcAddr + count
    then
      var index = count
      while index > 0 do
        index -= 1
        Intrinsics.storeByte(at(dst, index), Intrinsics.loadByte(at(src, index)))
    else
      var index = 0L
      while index < count do
        Intrinsics.storeByte(at(dst, index), Intrinsics.loadByte(at(src, index)))
        index += 1
    dst
  }

  /** C: `void *memset(void *dst, int c, size_t n)` — fills with `(char)c`. */
  @exported("memset")
  def memset(dst: RawPtr, value: Int, count: Long): RawPtr = {
    val byte = value.toByte
    var index = 0L
    while index < count do
      Intrinsics.storeByte(at(dst, index), byte)
      index += 1
    dst
  }

  @exported("memcmp")
  def memcmp(left: RawPtr, right: RawPtr, count: Long): Int = {
    var index = 0L
    var result = 0
    var done = false
    while !done do
      if index >= count then done = true
      else
        val l = Intrinsics.loadByte(at(left, index))
        val r = Intrinsics.loadByte(at(right, index))
        if l != r then
          result = Intrinsics.byteToUInt(l) - Intrinsics.byteToUInt(r)
          done = true
        else index += 1
    result
  }

  /* ---------- allocation ---------- */

  // This OS doesn't know what is unmanaged memory
  @exported("malloc")
  def malloc(size: Long): RawPtr = GC.allocRaw(size)

  // Free is for weak people
  @exported("free")
  def free(pointer: RawPtr): Unit = ()

  /* ---------- math ---------- */

  @exported("ceil")
  def ceil(value: Double): Double = {
    val integralLimit = 4503599627370496.0 // 2^52
    if value != value || value >= integralLimit || value <= -integralLimit then
      value
    else
      val truncated = value.toLong.toDouble
      if truncated == value then value
      else if value > 0.0 then truncated + 1.0
      else truncated
  }

}