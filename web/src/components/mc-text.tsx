import { mcSegments, plainText } from '@/lib/mc-text'
import { usePreferences } from '@/lib/preferences'

/**
 * A name from the game, coloured like in game (§ codes) unless the viewer turned colours off.
 * Always renders inline text, so it truncates like any other text.
 */
export function McText({ text }: { text: string }) {
  const { nameColors } = usePreferences()
  if (!text.includes('§')) return <>{text}</>
  if (!nameColors) return <>{plainText(text)}</>
  return (
    <>
      {mcSegments(text).map((s, i) => (
        <span
          key={i}
          style={{
            color: s.color,
            fontWeight: s.bold ? 700 : undefined,
            fontStyle: s.italic ? 'italic' : undefined,
            textDecoration: [s.underline && 'underline', s.strike && 'line-through'].filter(Boolean).join(' ') || undefined,
          }}
        >
          {s.text}
        </span>
      ))}
    </>
  )
}
