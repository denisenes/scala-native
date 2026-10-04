package kernel

import scala.scalanative.unsafe.*

sealed abstract class Tetromino(
    val color: Int,
    val pixels: Array[Array[Boolean]]
)

final case class IShape()
    extends Tetromino(Colors.Cyan, Array(Array(true, true, true, true)))

final case class OShape()
    extends Tetromino(Colors.Yellow, Array(Array(true, true), Array(true, true)))

final case class TShape()
    extends Tetromino(
      Colors.Purple,
      Array(Array(false, true, false), Array(true, true, true))
    )

final case class SShape()
    extends Tetromino(
      Colors.Green,
      Array(Array(false, true, true), Array(true, true, false))
    )

final case class ZShape()
    extends Tetromino(
      Colors.Red,
      Array(Array(true, true, false), Array(false, true, true))
    )

final case class JShape()
    extends Tetromino(
      Colors.Blue,
      Array(Array(true, false, false), Array(true, true, true))
    )

final case class LShape()
    extends Tetromino(
      Colors.Orange,
      Array(Array(false, false, true), Array(true, true, true))
    )

object Tetris:
  private inline val BoardWidth = 10
  private inline val BoardHeight = 20
  private inline val PreferredCellSize = 24
  private inline val ScreenPadding = 48
  private inline val PanelGap = 24
  private inline val PanelWidth = 224
  private inline val BorderSize = 3

  private inline val InputPollDelayMs = 10L
  private inline val BaseDropTicks = 50
  private inline val DropTicksPerLevel = 4
  private inline val MinimumDropTicks = 10

  private val figures: Array[Tetromino] = Array(
    IShape(),
    OShape(),
    TShape(),
    SShape(),
    ZShape(),
    JShape(),
    LShape()
  )
  private val board = new Array[Int](BoardWidth * BoardHeight)

  private var figureIndex = 0
  private var currentFigure: Tetromino = figures(0)
  private var rotation = 0
  private var pieceX = 3
  private var pieceY = 0
  private var score = 0
  private var clearedLines = 0
  private var extendedScanCode = false

  private var cellSize = PreferredCellSize
  private var boardLeft = 0
  private var boardTop = 0
  private var panelLeft = 0

  private inline def level: Int = clearedLines / 10 + 1

  private def figureWidth(figure: Tetromino, figureRotation: Int): Int =
    if (figureRotation & 1) == 0 then figure.pixels(0).length
    else figure.pixels.length

  private def figureHeight(figure: Tetromino, figureRotation: Int): Int =
    if (figureRotation & 1) == 0 then figure.pixels.length
    else figure.pixels(0).length

  /** Read a cell from the base matrix as if it were rotated clockwise. */
  private def figureHasPixel(figure: Tetromino, figureRotation: Int, x: Int, y: Int): Boolean =
    val baseHeight = figure.pixels.length
    val baseWidth = figure.pixels(0).length
    (figureRotation & 3) match
      case 0 => figure.pixels(y)(x)
      case 1 => figure.pixels(baseHeight - 1 - x)(y)
      case 2 => figure.pixels(baseHeight - 1 - y)(baseWidth - 1 - x)
      case _ => figure.pixels(x)(baseWidth - 1 - y)

  private def configureLayout(): Unit =
    val framebufferWidth = Platform.platform_framebuffer_width().toInt
    val framebufferHeight = Platform.platform_framebuffer_height().toInt
    val availableWidth = framebufferWidth - ScreenPadding - PanelGap - PanelWidth
    val availableHeight = framebufferHeight - ScreenPadding
    val widthCellSize = availableWidth / BoardWidth
    val heightCellSize = availableHeight / BoardHeight
    cellSize = PreferredCellSize
    if widthCellSize < cellSize then cellSize = widthCellSize
    if heightCellSize < cellSize then cellSize = heightCellSize
    if cellSize < 3 then cellSize = 3
    val contentWidth = BoardWidth * cellSize + PanelGap + PanelWidth
    boardLeft = (framebufferWidth - contentWidth) / 2
    boardTop = (framebufferHeight - BoardHeight * cellSize) / 2
    panelLeft = boardLeft + BoardWidth * cellSize + PanelGap

  private def collides(
      figure: Tetromino,
      figureRotation: Int,
      candidateX: Int,
      candidateY: Int
  ): Boolean =
    var hit = false
    var localY = 0
    val height = figureHeight(figure, figureRotation)
    val width = figureWidth(figure, figureRotation)
    while localY < height && !hit do
      var localX = 0
      while localX < width && !hit do
        if figureHasPixel(figure, figureRotation, localX, localY) then
          val x = candidateX + localX
          val y = candidateY + localY
          hit = x < 0 || x >= BoardWidth || y >= BoardHeight ||
            (y >= 0 && board(y * BoardWidth + x) != Colors.Black)
        localX += 1
      localY += 1
    hit

  private def drawCell(x: Int, y: Int, color: Int): Unit =
    val pixelX = boardLeft + x * cellSize
    val pixelY = boardTop + y * cellSize
    IO.fillRectangle(pixelX, pixelY, cellSize, cellSize, Colors.Black)
    if color != Colors.Black then
      IO.fillRectangle(pixelX + 1, pixelY + 1, cellSize - 2, cellSize - 2, color)

  private def drawFigureAt(
      figure: Tetromino,
      figureRotation: Int,
      x: Int,
      y: Int,
      color: Int
  ): Unit =
    var localY = 0
    val height = figureHeight(figure, figureRotation)
    val width = figureWidth(figure, figureRotation)
    while localY < height do
      var localX = 0
      while localX < width do
        if figureHasPixel(figure, figureRotation, localX, localY) then
          val boardX = x + localX
          val boardY = y + localY
          if boardX >= 0 && boardX < BoardWidth && boardY >= 0 && boardY < BoardHeight then
            drawCell(boardX, boardY, color)
        localX += 1
      localY += 1

  private def drawPlayfield(): Unit =
    val width = BoardWidth * cellSize
    val height = BoardHeight * cellSize
    IO.fillRectangle(
      boardLeft - BorderSize,
      boardTop - BorderSize,
      width + BorderSize * 2,
      height + BorderSize * 2,
      Colors.LightGray
    )
    IO.fillRectangle(boardLeft, boardTop, width, height, Colors.Black)
    var y = 0
    while y < BoardHeight do
      var x = 0
      while x < BoardWidth do
        val color = board(y * BoardWidth + x)
        if color != Colors.Black then drawCell(x, y, color)
        x += 1
      y += 1

  private def drawInterface(): Unit =
    IO.drawTextAt(
      boardLeft,
      boardTop - 22,
      c"SCALA TETRIS",
      Colors.BrightCyan,
      Colors.Black
    )
    IO.drawTextAt(panelLeft, boardTop, c"SCORE", Colors.White, Colors.Black)
    IO.drawTextAt(
      panelLeft,
      boardTop + 52,
      c"LINES",
      Colors.White,
      Colors.Black
    )
    IO.drawTextAt(
      panelLeft,
      boardTop + 104,
      c"LEVEL",
      Colors.White,
      Colors.Black
    )
    IO.drawTextAt(
      panelLeft,
      boardTop + 180,
      c"CONTROLS",
      Colors.BrightCyan,
      Colors.Black
    )
    IO.drawTextAt(
      panelLeft,
      boardTop + 204,
      c"LEFT/RIGHT  MOVE",
      Colors.LightGray,
      Colors.Black
    )
    IO.drawTextAt(
      panelLeft,
      boardTop + 224,
      c"UP          ROTATE",
      Colors.LightGray,
      Colors.Black
    )
    IO.drawTextAt(
      panelLeft,
      boardTop + 244,
      c"DOWN        SOFT DROP",
      Colors.LightGray,
      Colors.Black
    )
    IO.drawTextAt(
      panelLeft,
      boardTop + 264,
      c"Q           QUIT",
      Colors.LightGray,
      Colors.Black
    )
    drawStats()

  private def drawStats(): Unit =
    IO.fillRectangle(panelLeft, boardTop + 18, 112, 22, Colors.Black)
    IO.fillRectangle(panelLeft, boardTop + 70, 112, 22, Colors.Black)
    IO.fillRectangle(panelLeft, boardTop + 122, 112, 22, Colors.Black)
    IO.drawNumberAt(
      panelLeft,
      boardTop + 20,
      score,
      Colors.White,
      Colors.Black
    )
    IO.drawNumberAt(
      panelLeft,
      boardTop + 72,
      clearedLines,
      Colors.White,
      Colors.Black
    )
    IO.drawNumberAt(
      panelLeft,
      boardTop + 124,
      level,
      Colors.White,
      Colors.Black
    )

  private def lockPiece(): Unit =
    var localY = 0
    val height = figureHeight(currentFigure, rotation)
    val width = figureWidth(currentFigure, rotation)
    while localY < height do
      var localX = 0
      while localX < width do
        if figureHasPixel(currentFigure, rotation, localX, localY) then
          val x = pieceX + localX
          val y = pieceY + localY
          if y >= 0 then board(y * BoardWidth + x) = currentFigure.color
        localX += 1
      localY += 1

  private def clearLines(): Int =
    var readY = BoardHeight - 1
    var writeY = BoardHeight - 1
    var removed = 0
    while readY >= 0 do
      var x = 0
      var full = true
      while x < BoardWidth do
        if board(readY * BoardWidth + x) == Colors.Black then full = false
        x += 1
      if full then removed += 1
      else
        x = 0
        while x < BoardWidth do
          board(writeY * BoardWidth + x) = board(readY * BoardWidth + x)
          x += 1
        writeY -= 1
      readY -= 1

    while writeY >= 0 do
      var x = 0
      while x < BoardWidth do
        board(writeY * BoardWidth + x) = Colors.Black
        x += 1
      writeY -= 1
    removed

  private def resetGame(): Unit =
    var index = 0
    while index < board.length do
      board(index) = Colors.Black
      index += 1
    score = 0
    clearedLines = 0

  private def spawnNext(): Unit =
    figureIndex = (figureIndex + 1) % figures.length
    currentFigure = figures(figureIndex)
    rotation = 0
    pieceX = (BoardWidth - figureWidth(currentFigure, rotation)) / 2
    pieceY = 0
    if collides(currentFigure, rotation, pieceX, pieceY) then
      resetGame()
      drawPlayfield()
      drawStats()

  private def moveTo(newX: Int, newY: Int): Unit =
    drawFigureAt(currentFigure, rotation, pieceX, pieceY, Colors.Black)
    pieceX = newX
    pieceY = newY
    drawFigureAt(currentFigure, rotation, pieceX, pieceY, currentFigure.color)

  private def moveHorizontally(offset: Int): Unit =
    if !collides(currentFigure, rotation, pieceX + offset, pieceY) then
      moveTo(pieceX + offset, pieceY)

  private def applyRotation(newRotation: Int, offset: Int): Boolean =
    if collides(currentFigure, newRotation, pieceX + offset, pieceY) then false
    else
      drawFigureAt(currentFigure, rotation, pieceX, pieceY, Colors.Black)
      rotation = newRotation
      pieceX += offset
      drawFigureAt(currentFigure, rotation, pieceX, pieceY, currentFigure.color)
      true

  private def rotateClockwise(): Unit =
    val newRotation = (rotation + 1) & 3
    if !applyRotation(newRotation, 0) then
      if !applyRotation(newRotation, -1) then
        applyRotation(newRotation, 1)

  private def lineScore(lines: Int): Int = lines match
    case 1 => 100 * level
    case 2 => 300 * level
    case 3 => 500 * level
    case 4 => 800 * level
    case _ => 0

  private def stepDown(softDrop: Boolean): Unit =
    if !collides(currentFigure, rotation, pieceX, pieceY + 1) then
      moveTo(pieceX, pieceY + 1)
      if softDrop then
        score += 1
        drawStats()
    else
      lockPiece()
      val removed = clearLines()
      if removed > 0 then
        score += lineScore(removed)
        clearedLines += removed
        drawPlayfield()
      spawnNext()
      drawFigureAt(currentFigure, rotation, pieceX, pieceY, currentFigure.color)
      drawStats()

  private def handleKeyboard(): Boolean =
    var quit = false
    var scanCode = Platform.platform_poll_key()
    while scanCode >= 0 && !quit do
      if scanCode == 0xe0 then extendedScanCode = true
      else
        if (scanCode & 0x80) == 0 then
          if extendedScanCode then
            scanCode match
              case 0x4b => moveHorizontally(-1) // Left
              case 0x4d => moveHorizontally(1)  // Right
              case 0x48 => rotateClockwise()    // Up
              case 0x50 => stepDown(true)       // Down
              case _    => ()
          else if scanCode == 0x10 then quit = true // Q
        extendedScanCode = false
      if !quit then scanCode = Platform.platform_poll_key()
    quit

  private def dropTicksForCurrentLevel(): Int =
    val ticks = BaseDropTicks - (level - 1) * DropTicksPerLevel
    if ticks < MinimumDropTicks then MinimumDropTicks else ticks

  /** Clear once, then update only the moving piece and changed UI values. */
  def run(): Unit =
    resetGame()
    configureLayout()
    figureIndex = 0
    currentFigure = figures(figureIndex)
    rotation = 0
    pieceX = (BoardWidth - figureWidth(currentFigure, rotation)) / 2
    pieceY = 0
    IO.clearScreen()
    drawPlayfield()
    drawInterface()
    drawFigureAt(currentFigure, rotation, pieceX, pieceY, currentFigure.color)

    var gravityTicks = 0
    var running = true
    while running do
      Platform.platform_delay(InputPollDelayMs)
      running = !handleKeyboard()
      if running then
        gravityTicks += 1
        if gravityTicks >= dropTicksForCurrentLevel() then
          gravityTicks = 0
          stepDown(false)
    IO.clearScreen()
