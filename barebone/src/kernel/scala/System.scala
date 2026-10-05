package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr}
import scala.scalanative.unsafe.*
import kernel.IO

object System {
  
    def fatal(msg: CString): Unit = {
        print(msg)
        Platform.platform_halt()
    }

    def print(message: CString): Unit = IO.writeMessage(message, clear = false)

    def print(value: Boolean): Unit = IO.writeMessage(CStringUtils.toCString(value), clear = false)

    def print(value: Byte): Unit = IO.writeMessage(CStringUtils.toCString(value), clear = false)

    def print(value: Int): Unit = IO.writeMessage(CStringUtils.toCString(value), clear = false)

    def print(value: Long): Unit = IO.writeMessage(CStringUtils.toCString(value), clear = false)

    def print(value: RawPtr): Unit = IO.writeMessage(CStringUtils.toCString(value), clear = false)

    private def newline(): Unit = IO.writeMessage(c"\n", clear = false)

    def println(message: CString): Unit = { print(message); newline() }

    def println(value: Boolean): Unit = { print(value); newline() }

    def println(value: Byte): Unit = { print(value); newline() }

    def println(value: Int): Unit = { print(value); newline() }

    def println(value: Long): Unit = { print(value); newline() }

    def println(value: RawPtr): Unit = { print(value); newline() }

    def sleep(ms: Long): Unit = Platform.platform_delay(ms)

    def screenSize(): (Int, Int) = (
        Platform.platform_framebuffer_height().toInt,
        Platform.platform_framebuffer_width().toInt
    )

    def randomSeed(): Long = Platform.platform_random_seed()
}