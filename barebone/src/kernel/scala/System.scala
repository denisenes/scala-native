package kernel

import scala.scalanative.runtime.{Intrinsics, RawPtr}
import scala.scalanative.unsafe.*

object System {
  
    def fatal(msg: CString): Unit = {
        Platform.platform_halt()
    }

}