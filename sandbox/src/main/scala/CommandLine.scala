import scala.annotation.tailrec
import scala.scalanative.runtime.toRawPtr
import scala.scalanative.unsafe.*
import kernel.{IO, System, Keyboard, Colors, Std, CStringUtils}

object CommandLine:
  private inline val MaxCommandLength = 31
  private inline val InputPollDelayMs = 10L
  private inline val CursorBlinkPeriodMs = 500L

  final case class Command(name: CString, action: () => Boolean)

  private val commands: List[Command] = List(
    Command(c"tetris", () => { Tetris.run(); System.println(c"Exited Tetris."); true }),
    Command(c"bench", () => { GCBench.run(); true }),
    Command(c"shutdown", () => false)
  )

  private def matches(name: CString, input: CString): Boolean =
    Std.strncmp(toRawPtr(name), toRawPtr(input), MaxCommandLength + 1L) == 0

  private def step(typed: List[Byte], character: Int): Option[List[Byte]] =
    if character == '\n' then
      IO.writeCharacter('\n'.toByte)
      val input = CStringUtils.toCString(typed)
      val running = commands.find(c => matches(c.name, input)) match
        case Some(command) => command.action()
        case None =>
          if typed.nonEmpty then System.println(c"Unknown command")
          true
      if running then { System.print(c"scalash> "); Some(Nil) } else None
    else if character == 8 then
      if typed.isEmpty then Some(Nil) else { IO.writeCharacter(8.toByte); Some(typed.init) }
    else if typed.length < MaxCommandLength then
      IO.writeCharacter(character.toByte)
      Some(typed :+ character.toByte)
    else Some(typed)

  @tailrec
  private def inputLoop(typed: List[Byte], cursorVisible: Boolean, cursorElapsedMs: Long): Unit =
    val character = Keyboard.pollCharacter()
    if character < 0 then
      System.sleep(InputPollDelayMs)
      val elapsed = cursorElapsedMs + InputPollDelayMs
      if elapsed >= CursorBlinkPeriodMs then
        IO.drawTerminalCursor(!cursorVisible)
        inputLoop(typed, !cursorVisible, 0L)
      else inputLoop(typed, cursorVisible, elapsed)
    else
      IO.drawTerminalCursor(visible = false)
      step(typed, character) match
        case Some(next) =>
          IO.drawTerminalCursor(visible = true)
          inputLoop(next, true, 0L)
        case None => ()

  def run(): Unit =
    System.print(c"scalash> ")
    IO.drawTerminalCursor(visible = true)
    inputLoop(Nil, cursorVisible = true, cursorElapsedMs = 0L)
