package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr, fromRawPtr}
import scala.scalanative.unsafe.*
import kernel.Std.at
import kernel.CStringUtils

/** Conversions of primitive values to NUL-terminated C strings. */
object CStringUtils {

  private final val BufferSize = 32L

  def toCString(value: Boolean): CString = if value then c"true" else c"false"

  def toCString(value: Byte): CString = toCString(value.toLong)

  def toCString(value: Int): CString = toCString(value.toLong)

  def toCString(value: Long): CString = {
    val buffer = Std.malloc(BufferSize)
    var index = BufferSize - 1L
    Intrinsics.storeByte(at(buffer, index), 0) // NUL terminator
    index -= 1

    var rest = value
    if rest == 0L then
      Intrinsics.storeByte(at(buffer, index), '0'.toByte)
      index -= 1
    else
      while rest != 0L do
        val digit = (rest % 10L).toInt
        val magnitude = if digit < 0 then -digit else digit
        Intrinsics.storeByte(at(buffer, index), ('0' + magnitude).toByte)
        index -= 1
        rest = rest / 10L

    if value < 0L then
      Intrinsics.storeByte(at(buffer, index), '-'.toByte)
      index -= 1

    fromRawPtr[Byte](at(buffer, index + 1L))
  }

  def toCString(value: RawPtr): CString = {
    val buffer = Std.malloc(BufferSize)
    var index = BufferSize - 1L
    Intrinsics.storeByte(at(buffer, index), 0) // NUL terminator
    index -= 1

    var rest = Intrinsics.castRawPtrToLong(value)
    if rest == 0L then
      Intrinsics.storeByte(at(buffer, index), '0'.toByte)
      index -= 1
    else
      while rest != 0L do
        Intrinsics.storeByte(at(buffer, index), hexDigit((rest & 0xfL).toInt))
        index -= 1
        rest = rest >>> 4

    Intrinsics.storeByte(at(buffer, index), 'x'.toByte)
    index -= 1
    Intrinsics.storeByte(at(buffer, index), '0'.toByte)
    index -= 1

    fromRawPtr[Byte](at(buffer, index + 1L))
  }

  private def hexDigit(digit: Int): Byte = (if digit < 10 then '0' + digit else 'a' + digit - 10).toByte
}