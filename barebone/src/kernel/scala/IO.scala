package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr}
import scala.scalanative.unsafe.*
import kernel.Std.at

object IO {

  private inline val FontWidth = 8
  private inline val DefaultColor = 0x07
  private inline val Psf1HeaderSize = 4L

  private def color(index: Int): Int = index match
    case 0  => 0x000000
    case 1  => 0x0000aa
    case 2  => 0x00aa00
    case 3  => 0x00aaaa
    case 4  => 0xaa0000
    case 5  => 0xaa00aa
    case 6  => 0xaa5500
    case 7  => 0xaaaaaa
    case 8  => 0x555555
    case 9  => 0x5555ff
    case 10 => 0x55ff55
    case 11 => 0x55ffff
    case 12 => 0xff5555
    case 13 => 0xff55ff
    case 14 => 0xffff55
    case _  => 0xffffff

  private inline def unsignedByte(pointer: RawPtr, offset: Long): Int =
    Intrinsics.loadByte(at(pointer, offset)).toInt & 0xff

  private def psf1FontHeight(font: RawPtr): Int = unsignedByte(font, 3L)

  private def isValidPsf1(font: RawPtr, size: Long): Boolean =
    val mode = unsignedByte(font, 2L)
    val height = psf1FontHeight(font)
    val glyphCount = if (mode & 0x01) != 0 then 512L else 256L
    unsignedByte(font, 0L) == 0x36 &&
      unsignedByte(font, 1L) == 0x04 &&
      height > 0 &&
      size >= Psf1HeaderSize + glyphCount * height

  private inline def putPixel(
      address: RawPtr,
      pitch: Long,
      x: Long,
      y: Long,
      value: Int
  ): Unit =
    Intrinsics.storeInt(at(address, y * pitch + x * 4L), value)

  private def drawCharacter(
      address: RawPtr,
      pitch: Long,
      font: RawPtr,
      fontHeight: Int,
      character: Byte,
      x: Long,
      y: Long,
      foreground: Int,
      background: Int
  ): Unit =
    val glyphIndex = character.toInt & 0xff
    val glyph = at(font, Psf1HeaderSize + glyphIndex.toLong * fontHeight)
    var row = 0
    while row < fontHeight do
      val bits = unsignedByte(glyph, row.toLong)
      var column = 0
      while column < FontWidth do
        val isSet = (bits & (1 << (7 - column))) != 0
        putPixel(address, pitch, x + column, y + row, if isSet then foreground else background)
        column += 1
      row += 1

  private def terminalInitialize(
      address: RawPtr,
      width: Long,
      height: Long,
      pitch: Long
  ): Unit =
    val state = Platform.platform_terminal_state()
    Intrinsics.storeLong(state, 0L)
    Intrinsics.storeLong(at(state, 8L), 0L)

    var y = 0L
    while y < height do
      var x = 0L
      while x < width do
        putPixel(address, pitch, x, y, color(0))
        x += 1
      y += 1

  private def terminalPutCharacter(
      address: RawPtr,
      width: Long,
      height: Long,
      pitch: Long,
      font: RawPtr,
      fontHeight: Int,
      character: Byte
  ): Unit =
    val state = Platform.platform_terminal_state()
    var row = Intrinsics.loadLong(state)
    var column = Intrinsics.loadLong(at(state, 8L))

    if character == 10.toByte then
      column = 0
      row += 1
    else
      drawCharacter(
        address,
        pitch,
        font,
        fontHeight,
        character,
        column * FontWidth,
        row * fontHeight,
        color(DefaultColor & 0x0f),
        color((DefaultColor >>> 4) & 0x0f)
      )
      column += 1

      val terminalWidth = width / FontWidth
      if column >= terminalWidth then
        column = 0
        row += 1

    val terminalHeight = height / fontHeight
    if row >= terminalHeight then row = 0
    Intrinsics.storeLong(state, row)
    Intrinsics.storeLong(at(state, 8L), column)

  private def messageByte(message: Int, index: Int): Byte = message match
    case 0 => index match
      case 0 => 72
      case 1 => 101
      case 2 | 3 => 108
      case 4 => 111
      case 5 => 10
      case _ => 0
    case 1 => index match
      case 0 => 111
      case 1 => 107
      case 2 => 10
      case _ => 0
    case _ => index match
      case 0 => 110
      case 1 => 111
      case 2 => 107
      case 3 => 10
      case _ => 0

  private def terminalWriteMessage(
      address: RawPtr,
      width: Long,
      height: Long,
      pitch: Long,
      font: RawPtr,
      fontHeight: Int,
      message: Int
  ): Unit =
    var index = 0
    var character = messageByte(message, index)
    while character != 0 do
      terminalPutCharacter(address, width, height, pitch, font, fontHeight, character)
      index += 1
      character = messageByte(message, index)

  private def writeMessage(message: Int, clear: Boolean): Unit =
    if Platform.platform_init_framebuffer() then
      val address = Platform.platform_framebuffer_address()
      val width = Platform.platform_framebuffer_width()
      val height = Platform.platform_framebuffer_height()
      val pitch = Platform.platform_framebuffer_pitch()
      val font = Platform.platform_font_address()
      val fontSize = Platform.platform_font_size()
      if isValidPsf1(font, fontSize) then
        val fontHeight = psf1FontHeight(font)
        if clear then terminalInitialize(address, width, height, pitch)
        terminalWriteMessage(address, width, height, pitch, font, fontHeight, message)
      else Platform.platform_halt()
    else Platform.platform_halt()

  @exported("platform_init")
  def platform_init(): Unit = writeMessage(message = 0, clear = true)

  @exported("report_kmain_ok")
  def report_kmain_ok(result: Int): Unit = writeMessage(message = 1, clear = false)

  @exported("report_kmain_bad")
  def report_kmain_bad(result: Int): Unit = writeMessage(message = 2, clear = false)

}
