# Architekturentscheidungen dokumentieren

- Status: akzeptiert
- Datum: 2026-10-18

## Kontext

Pendenz trifft bewusst einige unübliche Entscheidungen, etwa kein eigenes
Schema, kein Framework im Kern, keine KI-Funktionen trotz KI-Anwendungsfall.
Ohne Begründung werden solche Entscheidungen später für Versehen gehalten und
rückgängig gemacht.

## Entscheidung

Jede Grundsatzentscheidung wird als ADR im MADR-Format unter `docs/adr`
festgehalten, bevor sie umgesetzt wird.

## Konsequenzen

- Mehr Schreibarbeit vor dem Code.
- Entscheidungen sind für Reviewer, Mitwirkende und KI-Werkzeuge
  nachvollziehbar und müssen nicht aus dem Code erraten werden.
