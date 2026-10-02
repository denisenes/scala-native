package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr}
import scala.scalanative.unsafe.*

@extern
object Platform:
  def platform_init_framebuffer(): CBool = extern
  def platform_framebuffer_address(): RawPtr = extern
  def platform_framebuffer_width(): Long = extern
  def platform_framebuffer_height(): Long = extern
  def platform_framebuffer_pitch(): Long = extern
  def platform_font_address(): RawPtr = extern
  def platform_font_size(): Long = extern
  def platform_terminal_state(): RawPtr = extern
  def platform_halt(): Unit = extern