import scala.scalanative.unsafe.*
import kernel.Tetris

@exported("kmain")
def kmain(): Int =
  Tetris.run()
  0

// TODO: use as entrypoint
@main
def main(): Int = kmain()
