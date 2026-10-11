package com.kolammaster.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedLessonUnlockFlowTest {
    @Test
    fun verifiedGuestUnlockIsGrantedBeforeWarningAndDoesNotOpenLesson() {
        var unlockGranted = false
        var warningShown = false
        var lessonOpened = false

        completeVerifiedRewardedLessonUnlock(
            isGuest = true,
            onUnlockGranted = { unlockGranted = true },
            onShowGuestWarning = {
                assertTrue(unlockGranted)
                warningShown = true
            },
            onOpenLesson = { lessonOpened = true }
        )

        assertTrue(unlockGranted)
        assertTrue(warningShown)
        assertFalse(lessonOpened)
    }

    @Test
    fun verifiedGoogleUnlockOpensLessonWithoutGuestWarning() {
        var unlockGranted = false
        var warningShown = false
        var lessonOpened = false

        completeVerifiedRewardedLessonUnlock(
            isGuest = false,
            onUnlockGranted = { unlockGranted = true },
            onShowGuestWarning = { warningShown = true },
            onOpenLesson = {
                assertTrue(unlockGranted)
                lessonOpened = true
            }
        )

        assertTrue(unlockGranted)
        assertFalse(warningShown)
        assertTrue(lessonOpened)
    }

    @Test
    fun cancellingWarningOpensLessonAsGuestAndKeepsEarnedUnlock() {
        var unlockGranted = false
        var lessonOpened = false
        var signInRequested = false

        completeVerifiedRewardedLessonUnlock(
            isGuest = true,
            onUnlockGranted = { unlockGranted = true },
            onShowGuestWarning = {},
            onOpenLesson = { lessonOpened = true }
        )
        handleGuestUnlockWarningAction(
            action = GuestUnlockWarningAction.CANCEL,
            onSignIn = { signInRequested = true },
            onCancelAndOpen = { lessonOpened = true }
        )

        assertTrue(unlockGranted)
        assertFalse(signInRequested)
        assertTrue(lessonOpened)
    }

    @Test
    fun signingInWaitsUntilGoogleMergeHasCompletedBeforeOpeningLesson() {
        var unlockGranted = false
        var signInRequested = false
        var lessonOpened = false
        var mergeCompleted = false

        completeVerifiedRewardedLessonUnlock(
            isGuest = true,
            onUnlockGranted = { unlockGranted = true },
            onShowGuestWarning = {},
            onOpenLesson = { lessonOpened = true }
        )
        handleGuestUnlockWarningAction(
            action = GuestUnlockWarningAction.SIGN_IN,
            onSignIn = {
                assertTrue(unlockGranted)
                signInRequested = true
            },
            onCancelAndOpen = {}
        )

        assertTrue(unlockGranted)
        assertTrue(signInRequested)
        assertFalse(lessonOpened)
        assertEquals(
            PendingGoogleLessonAction.WAITING,
            pendingGoogleLessonAction(
                hasPendingLesson = true,
                signInAttemptStarted = true,
                isAuthProcessing = true,
                isGoogleAccount = false
            )
        )

        mergeCompleted = true
        assertTrue(mergeCompleted)
        val postMergeAction = pendingGoogleLessonAction(
            hasPendingLesson = true,
            signInAttemptStarted = true,
            isAuthProcessing = false,
            isGoogleAccount = true
        )
        assertEquals(PendingGoogleLessonAction.OPEN_LESSON, postMergeAction)
        lessonOpened = postMergeAction == PendingGoogleLessonAction.OPEN_LESSON
        assertTrue(lessonOpened)
    }

    @Test
    fun cancelledOrFailedGoogleSignInReturnsToWarningWithoutOpeningLesson() {
        assertEquals(
            PendingGoogleLessonAction.RETURN_TO_WARNING,
            pendingGoogleLessonAction(
                hasPendingLesson = true,
                signInAttemptStarted = true,
                isAuthProcessing = false,
                isGoogleAccount = false
            )
        )
    }
}
