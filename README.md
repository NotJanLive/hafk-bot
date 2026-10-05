# HAFK Bot

Discord-Bot für die Hans & Friends Community. Java 25, [JDA 6](https://github.com/discord-jda/JDA), MariaDB.
Eingerichtet und konfiguriert wird er ausschließlich über das [HAFK Dashboard](https://github.com/NotJanLive/hafk-dashboard).
Nach dem Einladen begrüßt der Bot den Server und verlinkt die Einrichtung. `/dashboard` öffnet den Link jederzeit erneut.

## Architektur

```
Dashboard (Next.js) ──HTTP + Bearer-Token──> Bot-API (Javalin, intern)
                                               │
                                               ├── JDA ──> Discord
                                               └── MariaDB (Flyway-Migrationen)
```

- **Der Bot ist die einzige Instanz mit Datenbankzugriff.** Das Dashboard spricht ausschließlich mit der Bot-API.
- **Eine Datenbank für alle Server.** Jede Tabelle ist über `guild_id` getrennt.
- **Zugriffsprüfung bei jeder Dashboard-Anfrage:** Der Bot prüft live, ob der Nutzer Owner ist, Administrator- oder „Server verwalten“-Rechte hat oder eine Dashboard-Rolle besitzt.

```
src/main/java/de/notjan/bot
├── HAFKBot.java        Einstieg und Verdrahtung
├── config/             Konfiguration aus Umgebungsvariablen / .env
├── database/           Hikari-Pool, Flyway, JDBI
├── core/               Modul-System, Slash-Commands, Komponenten-Routing
├── guild/              Server-Einstellungen (Cache, Validierung, Änderungen)
├── access/             Wer darf das Dashboard nutzen?
├── audit/              Änderungsprotokoll (im Dashboard sichtbar)
├── api/                REST-API für das Dashboard
└── modules/            Features, je ein Paket (setup, später tickets, reactionroles, …)
```

Neue Features sind eigenständige `BotModule`. Ein Modul liefert Commands, Komponenten-Handler, Listener,
API-Controller und benötigte Intents. Registriert wird es in `HAFKBot.main`.

## Lokal starten

1. MariaDB bereitstellen und eine leere Datenbank anlegen:
   ```sql
   CREATE DATABASE hafk CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'hafk'@'%' IDENTIFIED BY '...';
   GRANT ALL PRIVILEGES ON hafk.* TO 'hafk'@'%';
   ```
2. `.env.example` nach `.env` kopieren und ausfüllen.
3. `./mvnw package`, danach `java -jar target/hafk-bot.jar`. Alternativ `HAFKBot` direkt in IntelliJ starten.

Die Tabellen legt Flyway beim Start automatisch an (`src/main/resources/db/migration`).
Bis zum ersten Release wird nur `V1__foundation.sql` angepasst. Nach einer Schema-Änderung die lokale Datenbank leeren.
Ab dem Release kommen Änderungen immer als neue Datei `V<n>__beschreibung.sql`.

## Discord Developer Portal

| Bereich | Einstellung |
|---|---|
| Bot → Public Bot | **an**: Nur so können auch andere Server-Admins den Bot über das Dashboard einladen |
| Bot → Requires OAuth2 Code Grant | aus |
| Bot → Presence Intent | aus |
| Bot → Server Members Intent | **an** (Rollenprüfung für das Dashboard) |
| Bot → Message Content Intent | **an** (ab dem Ticket-Modul für Transcripts) |
| Installation | nur *Guild Install* |
| OAuth2 → Redirects | `http://localhost:3000/api/auth/callback/discord` (Login) und `http://localhost:3000/api/invite/callback` (nach dem Einladen zurück ins Dashboard), später zusätzlich mit der Produktions-Domain |

Eingeladen wird über den Button „Bot hinzufügen“ in der Serverauswahl des Dashboards. Der manuelle Link
(Scopes `bot applications.commands`, **ohne Administrator**):

```
https://discord.com/oauth2/authorize?client_id=<CLIENT_ID>&scope=bot+applications.commands&permissions=268823632
```

`268823632` = View Channels, Send Messages, Embed Links, Attach Files, Read Message History, Add Reactions,
Use External Emojis, Manage Roles, Manage Channels, Manage Messages.

## API

Alle Routen liegen unter `/api/v1` und verlangen `Authorization: Bearer <BOT_API_TOKEN>`. Ausgenommen ist `/health`.
Server-bezogene Routen verlangen zusätzlich den Header `X-Acting-User: <discord-user-id>`. Der Bot prüft damit selbst, ob dieser Nutzer Zugriff hat.

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/health` | Status und Gateway |
| GET | `/bot` | Bot-Profil und IDs der Server |
| GET | `/users/{userId}/guilds?candidates=…` | Server, die der Nutzer verwalten darf |
| GET | `/guilds/{guildId}` | Server-Details mit Kanälen, Rollen und Bot-Rechten |
| GET / PUT | `/guilds/{guildId}/settings` | Kern-Einstellungen |
| GET | `/guilds/{guildId}/audit-log?limit=50` | Änderungsprotokoll |

Fehler kommen immer im Format `{"error": {"code", "message", "details"}}`. Discord-IDs werden als Strings übertragen.

## Workflow

- `main` ist geschützt, Änderungen kommen nur per Pull Request.
- Branches heißen `feature/<name>` bzw. `fix/<name>`.
- `./mvnw verify` muss vor jedem PR grün sein.
