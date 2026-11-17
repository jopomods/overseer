import { usePreferences, type Locale } from './preferences'

/**
 * UI texts in German and English (ADR-0009). Keys are typed: a missing translation
 * is a compile error.
 */
const en = {
  'app.name': 'Overseer',
  'app.tagline': 'Live data and history for your Minecraft world.',

  'nav.overview': 'Overview',
  'nav.server': 'Server',
  'nav.energy': 'Energy',
  'nav.account': 'Account',
  'nav.logout': 'Log out',

  'login.title': 'Log in',
  'login.intro': 'Type this command in Minecraft chat. This page continues on its own.',
  'login.waiting': 'Waiting for confirmation in game…',
  'login.expiresIn': 'Code expires in {time}',
  'login.expired': 'The code expired.',
  'login.newCode': 'New code',
  'login.copy': 'Copy command',
  'login.copied': 'Copied',
  'login.rateLimited': 'Too many attempts. Try again in a minute.',
  'login.error': 'The server is not reachable.',

  'common.loading': 'Loading…',
  'common.retry': 'Retry',
  'common.unknown': 'unknown',
  'common.never': 'never',
  'common.adminOnly': 'Admins only',
  'common.dataAge': 'Data from {age} ago',
  'common.live': 'Live',
  'common.offline': 'Reconnecting…',
  'common.revoke': 'Revoke',
  'common.create': 'Create',
  'common.close': 'Close',

  'overview.title': 'Overview',
  'overview.server': 'Server',
  'overview.energy': 'Energy',
  'overview.networks': '{count} networks',
  'overview.noNetworks': 'No Flux networks visible to you.',
  'overview.input': 'Input',
  'overview.output': 'Output',
  'overview.health': 'Collectors',
  'overview.healthOk': 'All providers within budget',
  'overview.healthThrottled': '{count} provider(s) throttled',

  'server.title': 'Server',
  'server.tps': 'TPS',
  'server.mspt': 'MSPT',
  'server.players': 'Players',
  'server.playersOnline': 'Players online',
  'server.noPlayers': 'Nobody is online.',
  'server.ping': 'Ping',
  'server.dimensions': 'Dimensions',
  'server.chunks': 'Chunks',
  'server.entities': 'Entities',
  'server.memory': 'Memory',
  'server.collectors': 'Collector cost',
  'server.avg': 'avg',
  'server.max': 'max',
  'server.every': 'every {ticks} ticks',
  'server.throttled': 'throttled',
  'server.errors': '{count} errors',

  'account.title': 'Account',
  'account.role': 'Role',
  'account.role.player': 'Player',
  'account.role.admin': 'Dashboard admin',
  'account.role.super_admin': 'Operator',
  'account.preferences': 'Preferences',
  'account.language': 'Language',
  'account.energyUnit': 'Energy unit',
  'account.sessions': 'Sessions and device tokens',
  'account.thisDevice': 'This device',
  'account.session': 'Browser session',
  'account.deviceToken': 'Device token',
  'account.lastUsed': 'Last used {age} ago',
  'account.logoutEverywhere': 'Log out everywhere',
  'account.newToken': 'New device token',
  'account.tokenName': 'Name, e.g. Tablet on the wall',
  'account.tokenCreated': 'Copy this token now. It is shown only once.',
  'account.deviceTokenNotice': 'You are using a read-only device token.',
} as const

export type MessageKey = keyof typeof en

const de: Record<MessageKey, string> = {
  'app.name': 'Overseer',
  'app.tagline': 'Live-Daten und Verlauf deiner Minecraft-Welt.',

  'nav.overview': 'Übersicht',
  'nav.server': 'Server',
  'nav.energy': 'Energie',
  'nav.account': 'Konto',
  'nav.logout': 'Abmelden',

  'login.title': 'Anmelden',
  'login.intro': 'Gib diesen Befehl im Minecraft-Chat ein. Diese Seite geht dann von selbst weiter.',
  'login.waiting': 'Warte auf Bestätigung im Spiel…',
  'login.expiresIn': 'Code läuft ab in {time}',
  'login.expired': 'Der Code ist abgelaufen.',
  'login.newCode': 'Neuer Code',
  'login.copy': 'Befehl kopieren',
  'login.copied': 'Kopiert',
  'login.rateLimited': 'Zu viele Versuche. Versuch es in einer Minute erneut.',
  'login.error': 'Der Server ist nicht erreichbar.',

  'common.loading': 'Lädt…',
  'common.retry': 'Erneut versuchen',
  'common.unknown': 'unbekannt',
  'common.never': 'nie',
  'common.adminOnly': 'Nur für Admins',
  'common.dataAge': 'Datenstand vor {age}',
  'common.live': 'Live',
  'common.offline': 'Verbinde neu…',
  'common.revoke': 'Widerrufen',
  'common.create': 'Erstellen',
  'common.close': 'Schließen',

  'overview.title': 'Übersicht',
  'overview.server': 'Server',
  'overview.energy': 'Energie',
  'overview.networks': '{count} Netzwerke',
  'overview.noNetworks': 'Keine Flux-Netzwerke für dich sichtbar.',
  'overview.input': 'Eingang',
  'overview.output': 'Ausgang',
  'overview.health': 'Sammler',
  'overview.healthOk': 'Alle Module im Zeitbudget',
  'overview.healthThrottled': '{count} Modul(e) gedrosselt',

  'server.title': 'Server',
  'server.tps': 'TPS',
  'server.mspt': 'MSPT',
  'server.players': 'Spieler',
  'server.playersOnline': 'Spieler online',
  'server.noPlayers': 'Niemand ist online.',
  'server.ping': 'Ping',
  'server.dimensions': 'Dimensionen',
  'server.chunks': 'Chunks',
  'server.entities': 'Entities',
  'server.memory': 'Arbeitsspeicher',
  'server.collectors': 'Kosten der Sammler',
  'server.avg': 'Ø',
  'server.max': 'max',
  'server.every': 'alle {ticks} Ticks',
  'server.throttled': 'gedrosselt',
  'server.errors': '{count} Fehler',

  'account.title': 'Konto',
  'account.role': 'Rolle',
  'account.role.player': 'Spieler',
  'account.role.admin': 'Dashboard-Admin',
  'account.role.super_admin': 'Operator',
  'account.preferences': 'Einstellungen',
  'account.language': 'Sprache',
  'account.energyUnit': 'Energie-Einheit',
  'account.sessions': 'Sessions und Geräte-Tokens',
  'account.thisDevice': 'Dieses Gerät',
  'account.session': 'Browser-Session',
  'account.deviceToken': 'Geräte-Token',
  'account.lastUsed': 'Zuletzt vor {age}',
  'account.logoutEverywhere': 'Überall abmelden',
  'account.newToken': 'Neues Geräte-Token',
  'account.tokenName': 'Name, z. B. Tablet an der Wand',
  'account.tokenCreated': 'Kopiere dieses Token jetzt. Es wird nur einmal angezeigt.',
  'account.deviceTokenNotice': 'Du nutzt ein Geräte-Token (nur lesen).',
}

const messages: Record<Locale, Record<MessageKey, string>> = { en, de }

export type Translate = (key: MessageKey, vars?: Record<string, string | number>) => string

export function translate(locale: Locale, key: MessageKey, vars?: Record<string, string | number>) {
  let text = messages[locale][key]
  if (vars) for (const [name, value] of Object.entries(vars)) text = text.replaceAll(`{${name}}`, String(value))
  return text
}

export function useT(): Translate {
  const { locale } = usePreferences()
  return (key, vars) => translate(locale, key, vars)
}
