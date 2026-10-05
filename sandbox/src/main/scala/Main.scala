import scala.scalanative.unsafe.*

@exported("kmain")
def kmain(): Int =
  CommandLine.run()
  0

// TODO: use as entrypoint
@main
def main(): Int = kmain()
