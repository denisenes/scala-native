import scala.scalanative.unsafe.*
import kernel.{IO, System, Keyboard, Colors}

object CommandLine:
  private inline val MaxCommandLength = 31
  private inline val InputPollDelayMs = 10L
  private inline val CursorBlinkPeriodMs = 500L
  private val command = new Array[Byte](MaxCommandLength)

  private def isTetrisCommand(length: Int): Boolean =
    length == 6 &&
      command(0) == 't'.toByte &&
      command(1) == 'e'.toByte &&
      command(2) == 't'.toByte &&
      command(3) == 'r'.toByte &&
      command(4) == 'i'.toByte &&
      command(5) == 's'.toByte

  private def isShutdownCommand(length: Int): Boolean =
    length == 8 &&
      command(0) == 's'.toByte &&
      command(1) == 'h'.toByte &&
      command(2) == 'u'.toByte &&
      command(3) == 't'.toByte &&
      command(4) == 'd'.toByte &&
      command(5) == 'o'.toByte &&
      command(6) == 'w'.toByte &&
      command(7) == 'n'.toByte

  private def printPrompt(): Unit = System.print(c"scalash> ")

  def run(): Unit =
    var length = 0
    var cursorVisible = true
    var cursorElapsedMs = 0L
    var running = true
    printPrompt()
    IO.drawTerminalCursor(visible = true)
    while running do
      val character = Keyboard.pollCharacter()
      if character < 0 then
        System.sleep(InputPollDelayMs)
        cursorElapsedMs += InputPollDelayMs
        if cursorElapsedMs >= CursorBlinkPeriodMs then
          cursorVisible = !cursorVisible
          cursorElapsedMs = 0L
          IO.drawTerminalCursor(cursorVisible)
      else
        IO.drawTerminalCursor(visible = false)
        if character == '\n' then
          IO.writeCharacter('\n'.toByte)
          if isTetrisCommand(length) then
            Tetris.run()
            System.println(c"Exited Tetris.")
          else if isShutdownCommand(length) then running = false
          else if length > 0 then System.println(c"Unknown command")
          length = 0
          if running then printPrompt()
        else if character == 8 then
          if length > 0 then
            length -= 1
            IO.writeCharacter(8.toByte)
        else if length < MaxCommandLength then
          command(length) = character.toByte
          length += 1
          IO.writeCharacter(character.toByte)
        cursorVisible = true
        cursorElapsedMs = 0L
        IO.drawTerminalCursor(visible = true)
