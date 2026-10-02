import scala.scalanative.unsafe.*
import kernel.System

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
  val res: Int = loadX() + loadY()
  System.println(res)
  res

// TODO: use as entrypoint
@main
def main(): Int = kmain()
