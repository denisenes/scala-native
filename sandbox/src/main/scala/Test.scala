import scala.scalanative.unsafe.*

case class Point2D(x: Int, y: Int)

object GlobalCtx {
  var obj: Point2D = _
}

def store(): Unit =
  GlobalCtx.obj = Point2D(40, 2)

def loadX(): Int = GlobalCtx.obj.x
def loadY(): Int = GlobalCtx.obj.y

@exported("kmain")
def kmain(): Int =
  store()
  val exitCode: Int = loadX() + loadY()
  exitCode

@main
def main(): Int = kmain()
