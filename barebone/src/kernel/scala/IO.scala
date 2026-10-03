package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr, toRawPtr}
import scala.scalanative.unsafe.*
import kernel.Std.at

object IO {
  private inline val FontWidth = 8
  private inline val Psf1HeaderSize = 4L

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

  private inline def putPixel(address: RawPtr, pitch: Long, x: Long, y: Long, value: Int): Unit =
    Intrinsics.storeInt(at(address, y * pitch + x * 4L), value)

  def fillRectangle(x: Int, y: Int, width: Int, height: Int, value: Int): Unit =
    if !Platform.platform_check_framebuffer() then Platform.platform_halt()
    if width > 0 && height > 0 then
      val framebufferWidth = Platform.platform_framebuffer_width()
      val framebufferHeight = Platform.platform_framebuffer_height()
      val startX = if x < 0 then 0L else x.toLong
      val startY = if y < 0 then 0L else y.toLong
      val requestedEndX = x.toLong + width.toLong
      val requestedEndY = y.toLong + height.toLong
      val endX = if requestedEndX > framebufferWidth then framebufferWidth else requestedEndX
      val endY = if requestedEndY > framebufferHeight then framebufferHeight else requestedEndY
      if startX < endX && startY < endY then
        val address = Platform.platform_framebuffer_address()
        val pitch = Platform.platform_framebuffer_pitch()
        var pixelY = startY
        while pixelY < endY do
          var pixelX = startX
          while pixelX < endX do
            putPixel(address, pitch, pixelX, pixelY, value)
            pixelX += 1
          pixelY += 1

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

  private def terminalInitialize(address: RawPtr, width: Long, height: Long, pitch: Long): Unit =
    val state = Platform.platform_terminal_state()
    Intrinsics.storeLong(state, 0L)
    Intrinsics.storeLong(at(state, 8L), 0L)

    var y = 0L
    while y < height do
      var x = 0L
      while x < width do
        putPixel(address, pitch, x, y, Colors.Black)
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
        Colors.LightGray,
        Colors.Black
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

  private def terminalWriteString(
      address: RawPtr,
      width: Long,
      height: Long,
      pitch: Long,
      font: RawPtr,
      fontHeight: Int,
      text: RawPtr
  ): Unit =
    var index = 0L
    var character = Intrinsics.loadByte(at(text, index))
    while character != 0 do
      terminalPutCharacter(address, width, height, pitch, font, fontHeight, character)
      index += 1
      character = Intrinsics.loadByte(at(text, index))

  def drawTextAt(x: Int, y: Int, text: CString, foreground: Int, background: Int): Unit =
    if !Platform.platform_check_framebuffer() then Platform.platform_halt()
    val address = Platform.platform_framebuffer_address()
    val pitch = Platform.platform_framebuffer_pitch()
    val font = Platform.platform_font_address()
    val fontSize = Platform.platform_font_size()
    if !isValidPsf1(font, fontSize) then Platform.platform_halt()
    val fontHeight = psf1FontHeight(font)
    val rawText = toRawPtr(text)
    var cursorX = x
    var cursorY = y
    var index = 0L
    var character = Intrinsics.loadByte(at(rawText, index))
    while character != 0 do
      if character == 10.toByte then
        cursorX = x
        cursorY += fontHeight
      else
        drawCharacter(
          address,
          pitch,
          font,
          fontHeight,
          character,
          cursorX.toLong,
          cursorY.toLong,
          foreground,
          background
        )
        cursorX += FontWidth
      index += 1
      character = Intrinsics.loadByte(at(rawText, index))

  /** Draw an integer without allocating a temporary CString. */
  def drawNumberAt(x: Int, y: Int, value: Int, foreground: Int, background: Int): Unit =
    if !Platform.platform_check_framebuffer() then Platform.platform_halt()
    val address = Platform.platform_framebuffer_address()
    val pitch = Platform.platform_framebuffer_pitch()
    val font = Platform.platform_font_address()
    val fontSize = Platform.platform_font_size()
    if !isValidPsf1(font, fontSize) then Platform.platform_halt()
    val fontHeight = psf1FontHeight(font)
    var cursorX = x
    var magnitude = value.toLong
    if magnitude < 0 then
      drawCharacter(address, pitch, font, fontHeight, '-'.toByte, cursorX, y, foreground, background)
      cursorX += FontWidth
      magnitude = -magnitude
    var divisor = 1L
    while magnitude / divisor >= 10L do divisor *= 10L
    while divisor > 0L do
      val digit = ((magnitude / divisor) % 10L).toInt
      drawCharacter(
        address,
        pitch,
        font,
        fontHeight,
        ('0' + digit).toByte,
        cursorX.toLong,
        y.toLong,
        foreground,
        background
      )
      cursorX += FontWidth
      divisor /= 10L

  def clearScreen(): Unit =
    if Platform.platform_check_framebuffer() then
      terminalInitialize(
        Platform.platform_framebuffer_address(),
        Platform.platform_framebuffer_width(),
        Platform.platform_framebuffer_height(),
        Platform.platform_framebuffer_pitch()
      )
    else Platform.platform_halt()

  private[kernel] def writeMessage(message: CString, clear: Boolean): Unit =
    if Platform.platform_check_framebuffer() then
      val address = Platform.platform_framebuffer_address()
      val width = Platform.platform_framebuffer_width()
      val height = Platform.platform_framebuffer_height()
      val pitch = Platform.platform_framebuffer_pitch()
      val font = Platform.platform_font_address()
      val fontSize = Platform.platform_font_size()
      if isValidPsf1(font, fontSize) then
        val fontHeight = psf1FontHeight(font)
        if clear then terminalInitialize(address, width, height, pitch)
        terminalWriteString(address, width, height, pitch, font, fontHeight, toRawPtr(message))
      else Platform.platform_halt()
    else 
      Platform.platform_halt()
}
