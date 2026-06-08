package com.nathan.twitchdropsminer.android.service

import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the non-recoverable terminal condition the foreground service uses to decide whether
 * to tear itself down. It must fire only for an expired/invalid Twitch session and never for a
 * resting, running, or generic-error snapshot.
 */
class MinerForegroundServiceTest {
    @Test
    fun expiredAuthenticatingSnapshotIsNonRecoverableTerminal() {
        val snapshot = RuntimeSnapshot(
            phase = RuntimePhase.Authenticating,
            account = LoginSession(state = LoginState.Expired),
        )

        assertTrue(snapshot.isNonRecoverableTerminal())
    }

    @Test
    fun stoppedSnapshotIsNotTerminal() {
        val snapshot = RuntimeSnapshot(
            phase = RuntimePhase.Stopped,
            account = LoginSession(state = LoginState.Expired),
        )

        assertFalse(snapshot.isNonRecoverableTerminal())
    }

    @Test
    fun errorSnapshotIsNotTerminal() {
        val snapshot = RuntimeSnapshot(
            phase = RuntimePhase.Error,
            account = LoginSession(state = LoginState.LoginRequired),
        )

        assertFalse(snapshot.isNonRecoverableTerminal())
    }

    @Test
    fun authenticatedNonTerminalSnapshotsAreNotTerminal() {
        val loggedIn = RuntimeSnapshot(
            phase = RuntimePhase.Watching,
            account = LoginSession(state = LoginState.LoggedIn),
        )
        // Authenticating while merely waiting for activation (not expired) must not stop the service.
        val awaitingActivation = RuntimeSnapshot(
            phase = RuntimePhase.Authenticating,
            account = LoginSession(state = LoginState.LoginRequired),
        )

        assertFalse(loggedIn.isNonRecoverableTerminal())
        assertFalse(awaitingActivation.isNonRecoverableTerminal())
    }
}
