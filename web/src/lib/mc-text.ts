/**
 * Minecraft legacy formatting codes (§ + code) in names, e.g. "§5EV Energy Hatch". Modpacks
 * colour items by voltage tier this way; the dashboard shows the colours by default and can show
 * plain text instead (account setting).
 */

export interface McSegment {
  text: string
  color?: string
  bold?: boolean
  italic?: boolean
  underline?: boolean
  strike?: boolean
}

/** The game's colours. Black and dark blue are lifted a little so they stay readable on dark cards. */
const COLORS: Record<string, string> = {
  '0': '#555555',
  '1': '#3a3aff',
  '2': '#00AA00',
  '3': '#00AAAA',
  '4': '#d83a3a',
  '5': '#AA00AA',
  '6': '#FFAA00',
  '7': '#AAAAAA',
  '8': '#555555',
  '9': '#5555FF',
  a: '#55FF55',
  b: '#55FFFF',
  c: '#FF5555',
  d: '#FF55FF',
  e: '#FFFF55',
  f: '#FFFFFF',
}

/** CSS colour of a Minecraft colour code ("6" = gold), as the dashboard shows it. */
export function mcColor(code: string): string {
  return COLORS[code.toLowerCase()] ?? COLORS.f
}

/** Text without formatting codes. */
export function plainText(text: string): string {
  return text.replace(/§[0-9a-fk-orx]/gi, '')
}

/** Splits a name into styled segments. Unknown codes are dropped; §r resets. */
export function mcSegments(text: string): McSegment[] {
  const segments: McSegment[] = []
  let style: Omit<McSegment, 'text'> = {}
  let buffer = ''
  const flush = () => {
    if (buffer) segments.push({ text: buffer, ...style })
    buffer = ''
  }
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (c === '§' && i + 1 < text.length) {
      const code = text[i + 1].toLowerCase()
      i++
      flush()
      if (COLORS[code]) style = { color: COLORS[code] } // a colour resets the other styles, as in game
      else if (code === 'l') style = { ...style, bold: true }
      else if (code === 'o') style = { ...style, italic: true }
      else if (code === 'n') style = { ...style, underline: true }
      else if (code === 'm') style = { ...style, strike: true }
      else if (code === 'r') style = {}
      continue
    }
    buffer += c
  }
  flush()
  return segments
}
