package kernel

/** Minimal US-layout PS/2 set-1 decoder used by the command line. */
object Keyboard:
  private var extendedScanCode = false

  private def decode(scanCode: Int): Int = scanCode match
    case 0x1e => 'a'
    case 0x30 => 'b'
    case 0x2e => 'c'
    case 0x20 => 'd'
    case 0x12 => 'e'
    case 0x21 => 'f'
    case 0x22 => 'g'
    case 0x23 => 'h'
    case 0x17 => 'i'
    case 0x24 => 'j'
    case 0x25 => 'k'
    case 0x26 => 'l'
    case 0x32 => 'm'
    case 0x31 => 'n'
    case 0x18 => 'o'
    case 0x19 => 'p'
    case 0x10 => 'q'
    case 0x13 => 'r'
    case 0x1f => 's'
    case 0x14 => 't'
    case 0x16 => 'u'
    case 0x2f => 'v'
    case 0x11 => 'w'
    case 0x2d => 'x'
    case 0x15 => 'y'
    case 0x2c => 'z'
    case 0x39 => ' '
    case 0x1c => '\n'
    case 0x0e => 8 // Backspace
    case _    => -1

  def pollCharacter(): Int =
    var result = -1
    var scanning = true
    while scanning do
      val scanCode = Platform.platform_poll_key()
      if scanCode < 0 then scanning = false
      else if scanCode == 0xe0 then extendedScanCode = true
      else
        if !extendedScanCode && (scanCode & 0x80) == 0 then result = decode(scanCode)
        extendedScanCode = false
        if result >= 0 then scanning = false
    result

  def pollKeys(handle: Int => Boolean): Unit =
    var scanning = true
    while scanning do
      val scanCode = Platform.platform_poll_key()
      if scanCode < 0 then scanning = false
      else if !handle(scanCode) then scanning = false
