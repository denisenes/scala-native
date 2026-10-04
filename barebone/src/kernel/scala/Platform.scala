package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr}
import scala.scalanative.unsafe.*
import kernel.IO

@extern
object Platform {
  def platform_init_framebuffer(): CBool = extern
  def platform_check_framebuffer(): CBool = extern
  def platform_framebuffer_address(): RawPtr = extern
  def platform_framebuffer_width(): Long = extern
  def platform_framebuffer_height(): Long = extern
  def platform_framebuffer_pitch(): Long = extern
  def platform_font_address(): RawPtr = extern
  def platform_font_size(): Long = extern
  def platform_terminal_state(): RawPtr = extern
  def platform_init_timer(): Unit = extern
  def platform_delay(milliseconds: Long): Unit = extern
  def platform_poll_key(): Int = extern
  def platform_halt(): Unit = extern
}

// !!! No way to access GC methods directly
// because it can provoke allocations !!! 
@extern
private object GCExports:
  def scalanative_GC_init(): Unit = extern
  def scalanative_GC_info(): Unit = extern

@exported("platform_init")
private def platform_init(): Unit = {
  if (!Platform.platform_init_framebuffer()) {
    Platform.platform_halt()
  }
  GCExports.scalanative_GC_init()

  IO.writeMessage(message = c"==========================================================\n", clear = true)
  IO.writeMessage(message = c"Welcome to ScalOS, the first Scala-written OS in the world\n", clear = false) // TODO: think about name
  IO.writeMessage(message = c"==========================================================\n", clear = false)
  GCExports.scalanative_GC_info()
  Platform.platform_init_timer()
}

@exported("platform_terminate")
private def platform_terminate(result: Int): Unit = {
  IO.writeMessage(message = c"[Termination] err code = ", clear = false)
  IO.writeMessage(message = CStringUtils.toCString(result), clear = false)
  IO.writeMessage(message = c"\n", clear = false)
}
