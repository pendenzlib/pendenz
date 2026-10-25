package io.github.d4niella.pendenz

import java.time.Instant

/**
 * What a retry policy sees as the cause when a task's lock expired: the
 * worker that ran it stopped confirming the lock, most likely because its
 * process died. The attempt counts as failed (ADR 0026), and a policy can
 * tell a crash from a handler failure by this type.
 *
 * @property lockedBy the worker that held the lock, null when the row named none.
 * @property lockedUntil the deadline that passed.
 */
public class LockExpiredException(
    public val lockedBy: String?,
    public val lockedUntil: Instant,
) : RuntimeException("lock expired: worker $lockedBy stopped confirming its lock, which ran until $lockedUntil")
