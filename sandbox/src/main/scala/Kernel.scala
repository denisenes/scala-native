import scala.scalanative.runtime.{Intrinsics, RawPtr}
import scala.scalanative.unsafe.*

@extern
private object Platform:
  def platform_init_framebuffer(): CBool = extern
  def platform_framebuffer_address(): RawPtr = extern
  def platform_framebuffer_width(): Long = extern
  def platform_framebuffer_height(): Long = extern
  def platform_framebuffer_pitch(): Long = extern
  def platform_terminal_state(): RawPtr = extern
  def platform_halt(): Unit = extern

private inline val FontWidth = 8
private inline val FontHeight = 8
private inline val DefaultColor = 0x07

private inline def at(pointer: RawPtr, byteOffset: Long): RawPtr =
  Intrinsics.elemRawPtr(pointer, Intrinsics.castLongToRawSize(byteOffset))

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

private def glyphRow(character: Byte, row: Int): Int = character.toInt match
  case 32 => 0x00
  case 33 => if row == 5 then 0x00 else if row == 6 then 0x18 else if row < 5 then 0x18 else 0x00
  case 44 => if row == 5 || row == 6 then 0x18 else if row == 7 then 0x30 else 0x00
  case 72 => if row == 3 then 0x7e else if row < 7 then 0x66 else 0x00
  case 87 => row match
    case 3 => 0x6b
    case 4 => 0x7f
    case 5 => 0x77
    case r if r < 7 => 0x63
    case _ => 0x00
  case 100 => row match
    case 0 | 1 => 0x06
    case 2 => 0x3e
    case 3 | 4 | 5 => 0x66
    case 6 => 0x3e
    case _ => 0x00
  case 101 => row match
    case 2 | 6 => 0x3c
    case 3 => 0x66
    case 4 => 0x7e
    case 5 => 0x60
    case _ => 0x00
  case 107 => row match
    case 0 | 1 => 0x60
    case 2 | 6 => 0x66
    case 3 | 5 => 0x6c
    case 4 => 0x78
    case _ => 0x00
  case 108 => row match
    case 0 => 0x38
    case 1 | 2 | 3 | 4 | 5 => 0x18
    case 6 => 0x3c
    case _ => 0x00
  case 110 => row match
    case 2 => 0x7c
    case 3 | 4 | 5 | 6 => 0x66
    case _ => 0x00
  case 111 => row match
    case 2 | 6 => 0x3c
    case 3 | 4 | 5 => 0x66
    case _ => 0x00
  case 114 => row match
    case 2 => 0x6c
    case 3 => 0x76
    case 4 | 5 | 6 => 0x60
    case _ => 0x00
  case _ => 0x00

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
    character: Byte,
    x: Long,
    y: Long,
    foreground: Int,
    background: Int
): Unit =
  var row = 0
  while row < FontHeight do
    val bits = glyphRow(character, row)
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
      character,
      column * FontWidth,
      row * FontHeight,
      color(DefaultColor & 0x0f),
      color((DefaultColor >>> 4) & 0x0f)
    )
    column += 1

    val terminalWidth = width / FontWidth
    if column >= terminalWidth then
      column = 0
      row += 1

  val terminalHeight = height / FontHeight
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
    message: Int
): Unit =
  var index = 0
  var character = messageByte(message, index)
  while character != 0 do
    terminalPutCharacter(address, width, height, pitch, character)
    index += 1
    character = messageByte(message, index)

@exported("strlen")
private def strlen(text: RawPtr): Long =
  var length = 0L
  while Intrinsics.loadByte(at(text, length)) != 0 do length += 1
  length

private def writeMessage(message: Int, clear: Boolean): Unit =
  if Platform.platform_init_framebuffer() then
    val address = Platform.platform_framebuffer_address()
    val width = Platform.platform_framebuffer_width()
    val height = Platform.platform_framebuffer_height()
    val pitch = Platform.platform_framebuffer_pitch()
    if clear then terminalInitialize(address, width, height, pitch)
    terminalWriteMessage(address, width, height, pitch, message)
  else Platform.platform_halt()

@exported("platform_init")
def platform_init(): Unit = writeMessage(message = 0, clear = true)

@exported("report_kmain_ok")
def report_kmain_ok(result: Int): Unit = writeMessage(message = 1, clear = false)

@exported("report_kmain_bad")
def report_kmain_bad(result: Int): Unit = writeMessage(message = 2, clear = false)
