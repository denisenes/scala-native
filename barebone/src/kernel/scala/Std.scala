package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr}
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

}