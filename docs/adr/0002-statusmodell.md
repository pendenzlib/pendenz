# Statusmodell

- Status: akzeptiert
- Datum: 2026-10-18

## Kontext

Der Status eines Tasks steht als Text in der Statusspalte des Nutzers und wird
von dessen Oberflächen gelesen, von SQL-Rezepten gesetzt und von der
Bibliothek erzwungen. Er ist der zentrale Teil des Schema-Vertrags und muss
vor allem anderen feststehen.

## Entscheidung

Zehn Zustände in drei Gruppen. Die Gruppe sagt, wer als Nächstes handelt.

| Status | Gruppe | Bedeutung |
|---|---|---|
| `PENDING` | aktiv | Fällig, wartet auf Claim |
| `SCHEDULED` | aktiv | Ausführungszeit in der Zukunft |
| `AWAITING_APPROVAL` | wartet auf Mensch | Task-Typ verlangt Freigabe |
| `RUNNING` | aktiv | Geclaimt, Heartbeat läuft |
| `SUCCEEDED` | final | Ergebnis angenommen |
| `RETRY_PENDING` | aktiv | Fehler, Versuche übrig, Backoff läuft |
| `FAILED` | wartet auf Mensch | Versuche aufgebraucht |
| `REJECTED` | wartet auf Mensch | Ergebnis von der Validierung verworfen |
| `QUARANTINED` | wartet auf Mensch | Bewusst aus dem Verkehr gezogen, mit Grund |
| `CANCELLED` | final | Wird nie ausgeführt |

Erlaubte Übergänge sind in `TaskStatusTransitions` im Kern definiert und
dort die einzige Quelle. Generierte SQL-Funktionen und dokumentierte
SQL-Rezepte setzen dieselben Regeln um. Jede Zeile trägt eine Versionsspalte
für optimistisches Sperren, damit ein SQL-Eingriff und ein laufender Worker
nie beide gewinnen.

Regeln, die Tests absichern:

- Finale Zustände haben keine ausgehenden Übergänge.
- Jeder nicht finale Zustand kann abgebrochen werden.
- Nur `PENDING` kann geclaimt werden.
- Zustände, die auf einen Menschen warten, können neu gestartet oder als
  erledigt gesetzt werden.

## Verworfene Alternativen

- **Weniger Zustände, Unterscheidung über Zusatzspalten.** Oberflächen und
  SQL-Filter müssten dann Spaltenkombinationen kennen statt eines Wertes.
- **`REJECTED` als Variante von `FAILED`.** Ein Retry hilft bei abgelehnten
  Ergebnissen nicht, die Behandlung ist eine andere.
- **Keine Quarantäne.** Dann gibt es keinen umkehrbaren Weg, einen Task
  zurückzuhalten, ohne ihn abzubrechen oder seine Versuche zu verbrennen.

## Konsequenzen

- Statusnamen sind ab 1.0 stabil. Umbenennen ist ein Bruch der Schema-API.
- `AWAITING_APPROVAL` und `REJECTED` sind ab v0.1 im Modell, auch wenn
  Freigabe und Validierung erst in v0.3 kommen, damit sich der Vertrag nicht
  nachträglich ändert.
