package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr, toRawPtr}
import scala.scalanative.unsafe.*
import kernel.Std.at

object IO:
  private inline val FontWidth = 8
  private inline val Psf1HeaderSize = 4L

  private var initialized = false
  private var framebuffer = Intrinsics.castLongToRawPtr(0L)
  private var framebufferWidth = 0L
  private var framebufferHeight = 0L
  private var framebufferPitch = 0L
  private var font = Intrinsics.castLongToRawPtr(0L)
  private var fontHeight = 0
  private var terminalColumns = 0L
  private var terminalRows = 0L

  private inline def unsignedByte(pointer: RawPtr, offset: Long): Int =
    Intrinsics.loadByte(at(pointer, offset)).toInt & 0xff

  private def isValidPsf1(address: RawPtr, size: Long): Boolean =
    if size < Psf1HeaderSize then false
    else
      val mode = unsignedByte(address, 2L)
      val height = unsignedByte(address, 3L)
      val glyphCount = if (mode & 0x01) != 0 then 512L else 256L
      unsignedByte(address, 0L) == 0x36 &&
        unsignedByte(address, 1L) == 0x04 &&
        height > 0 &&
        size >= Psf1HeaderSize + glyphCount * height

  private def ensureInitialized(): Unit =
    if !initialized then
      if !Platform.platform_check_framebuffer() then Platform.platform_halt()
      val candidateFont = Platform.platform_font_address()
      if !isValidPsf1(candidateFont, Platform.platform_font_size()) then Platform.platform_halt()

      framebuffer = Platform.platform_framebuffer_address()
      framebufferWidth = Platform.platform_framebuffer_width()
      framebufferHeight = Platform.platform_framebuffer_height()
      framebufferPitch = Platform.platform_framebuffer_pitch()
      font = candidateFont
      fontHeight = unsignedByte(font, 3L)
      terminalColumns = framebufferWidth / FontWidth
      terminalRows = framebufferHeight / fontHeight
      if terminalColumns == 0 || terminalRows == 0 then Platform.platform_halt()
      initialized = true

  private inline def putPixel(x: Long, y: Long, value: Int): Unit =
    Intrinsics.storeInt(at(framebuffer, y * framebufferPitch + x * 4L), value)

  def fillRectangle(x: Int, y: Int, width: Int, height: Int, color: Int): Unit =
    ensureInitialized()
    if width > 0 && height > 0 then
      val startX = if x < 0 then 0L else x.toLong
      val startY = if y < 0 then 0L else y.toLong
      val requestedEndX = x.toLong + width
      val requestedEndY = y.toLong + height
      val endX = if requestedEndX < framebufferWidth then requestedEndX else framebufferWidth
      val endY = if requestedEndY < framebufferHeight then requestedEndY else framebufferHeight
      var pixelY = startY
      while pixelY < endY do
        var pixelX = startX
        while pixelX < endX do
          putPixel(pixelX, pixelY, color)
          pixelX += 1
        pixelY += 1

  private def drawCharacter(character: Byte, x: Long, y: Long, foreground: Int, background: Int): Unit =
    val glyph = at(font, Psf1HeaderSize + (character.toInt & 0xff).toLong * fontHeight)
    var row = 0
    while row < fontHeight do
      val pixelY = y + row
      if pixelY >= 0 && pixelY < framebufferHeight then
        val bits = unsignedByte(glyph, row.toLong)
        var column = 0
        while column < FontWidth do
          val pixelX = x + column
          if pixelX >= 0 && pixelX < framebufferWidth then
            val color = if (bits & (1 << (7 - column))) != 0 then foreground else background
            putPixel(pixelX, pixelY, color)
          column += 1
      row += 1

  private def terminalInitialize(): Unit =
    val state = Platform.platform_terminal_state()
    Intrinsics.storeLong(state, 0L)
    Intrinsics.storeLong(at(state, 8L), 0L)

    var y = 0L
    while y < framebufferHeight do
      var x = 0L
      while x < framebufferWidth do
        putPixel(x, y, Colors.Black)
        x += 1
      y += 1

  private def scrollTerminal(): Unit =
    val textHeight = terminalRows * fontHeight
    val copyHeight = textHeight - fontHeight
    var y = 0L
    while y < copyHeight do
      var x = 0L
      while x < framebufferWidth do
        val source = at(framebuffer, (y + fontHeight) * framebufferPitch + x * 4L)
        putPixel(x, y, Intrinsics.loadInt(source))
        x += 1
      y += 1
    while y < textHeight do
      var x = 0L
      while x < framebufferWidth do
        putPixel(x, y, Colors.Black)
        x += 1
      y += 1

  private def terminalPutCharacter(character: Byte): Unit =
    val state = Platform.platform_terminal_state()
    var row = Intrinsics.loadLong(state)
    var column = Intrinsics.loadLong(at(state, 8L))

    if character == '\n'.toByte then
      column = 0
      row += 1
    else if character == 8.toByte then
      if column > 0 then column -= 1
      else if row > 0 then
        row -= 1
        column = terminalColumns - 1
      drawCharacter(' '.toByte, column * FontWidth, row * fontHeight, Colors.LightGray, Colors.Black)
    else
      drawCharacter(character, column * FontWidth, row * fontHeight, Colors.LightGray, Colors.Black)
      column += 1
      if column >= terminalColumns then
        column = 0
        row += 1

    if row >= terminalRows then
      scrollTerminal()
      row = terminalRows - 1
    Intrinsics.storeLong(state, row)
    Intrinsics.storeLong(at(state, 8L), column)

  private def terminalWriteString(text: RawPtr): Unit =
    var index = 0L
    var character = Intrinsics.loadByte(text)
    while character != 0 do
      terminalPutCharacter(character)
      index += 1
      character = Intrinsics.loadByte(at(text, index))

  def writeCharacter(character: Byte): Unit =
    ensureInitialized()
    terminalPutCharacter(character)

  def drawTerminalCursor(visible: Boolean): Unit =
    ensureInitialized()
    val state = Platform.platform_terminal_state()
    val row = Intrinsics.loadLong(state)
    val column = Intrinsics.loadLong(at(state, 8L))
    val character = if visible then '_'.toByte else ' '.toByte
    drawCharacter(character, column * FontWidth, row * fontHeight, Colors.LightGray, Colors.Black)

  def drawTextAt(x: Int, y: Int, text: CString, foreground: Int, background: Int): Unit =
    ensureInitialized()
    val rawText = toRawPtr(text)
    var cursorX = x.toLong
    var cursorY = y.toLong
    var index = 0L
    var character = Intrinsics.loadByte(rawText)
    while character != 0 do
      if character == '\n'.toByte then
        cursorX = x
        cursorY += fontHeight
      else
        drawCharacter(character, cursorX, cursorY, foreground, background)
        cursorX += FontWidth
      index += 1
      character = Intrinsics.loadByte(at(rawText, index))

  def drawNumberAt(x: Int, y: Int, value: Int, foreground: Int, background: Int): Unit =
    ensureInitialized()
    var cursorX = x.toLong
    var magnitude = value.toLong
    if magnitude < 0 then
      drawCharacter('-'.toByte, cursorX, y, foreground, background)
      cursorX += FontWidth
      magnitude = -magnitude
    var divisor = 1L
    while magnitude / divisor >= 10L do divisor *= 10L
    while divisor > 0L do
      val digit = ((magnitude / divisor) % 10L).toInt
      drawCharacter(('0' + digit).toByte, cursorX, y, foreground, background)
      cursorX += FontWidth
      divisor /= 10L

  def clearScreen(): Unit =
    ensureInitialized()
    terminalInitialize()

  private[kernel] def writeMessage(message: CString, clear: Boolean): Unit =
    ensureInitialized()
    if clear then terminalInitialize()
    terminalWriteString(toRawPtr(message))
