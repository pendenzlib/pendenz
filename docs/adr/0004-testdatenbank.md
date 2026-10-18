# Testdatenbank: Testcontainers mit eingebettetem PostgreSQL als Rückfall

- Status: akzeptiert
- Datum: 2026-10-18

## Kontext

Die Claim-Abfrage hängt an `FOR UPDATE SKIP LOCKED`, die Payload-Spalte an
`jsonb`. Beides gibt es in H2 nicht, Tests gegen H2 würden das Wesentliche
nicht prüfen. Testcontainers liefert ein echtes PostgreSQL, braucht aber
Docker. Nicht jeder Entwicklerrechner hat Docker, und auf dem Rechner, auf
dem Pendenz entsteht, war es beim ersten Feature nicht erreichbar.

## Entscheidung

Die Testhilfe `PostgresDatabase` startet PostgreSQL über Testcontainers,
wenn Docker erreichbar ist, und sonst über das eingebettete PostgreSQL von
`io.zonky.test:embedded-postgres`, das echte PostgreSQL-Binaries als
Prozess des aktuellen Nutzers startet. Beides sind echte PostgreSQL-Server.
Welcher Weg genommen wurde, steht im Testlog.

Die CI läuft auf GitHub-Runnern mit Docker und damit über Testcontainers.

## Verworfene Alternativen

- **Nur Testcontainers.** Tests wären auf Rechnern ohne Docker nicht
  ausführbar. Das widerspricht Prinzip 9, testbar ohne Infrastruktur.
- **Nur eingebettetes PostgreSQL.** Funktioniert nicht auf jeder Plattform
  und bildet nicht die Version ab, die Nutzer in Produktion haben.
- **H2 im PostgreSQL-Modus.** Kennt weder `SKIP LOCKED` noch `jsonb`.

## Konsequenzen

- Eine zusätzliche Testabhängigkeit. Der Kern bleibt unberührt.
- Tests können auf beiden Wegen unterschiedlich schnell sein; die
  Zusicherungen warten deshalb mit Awaitility auf Zustände statt auf Zeiten.
