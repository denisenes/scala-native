import scala.scalanative.unsafe.*

@exported("kmain")
def kmain(): Int =
  val exitCode: Int = 42
  exitCode

@main
def main(): Int = kmain()
