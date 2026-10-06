package com.kolammaster.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionOnboardingTest {
    @Test
    fun requestsPermissionOnlyWhenRuntimePermissionIsNeededAndNotYetRequested() {
        assertTrue(
            shouldRequestNotificationPermission(
                sdkInt = 33,
                permissionGranted = false,
                permissionPreviouslyRequested = false
            )
        )
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = 32,
                permissionGranted = false,
                permissionPreviouslyRequested = false
            )
        )
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = 33,
                permissionGranted = true,
                permissionPreviouslyRequested = false
            )
        )
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = 33,
                permissionGranted = false,
                permissionPreviouslyRequested = true
            )
        )
    }

    @Test
    fun guestSetupWaitsForPermissionRequestBeforeOpeningAnimation() {
        val flow = OnboardingPermissionFlow()

        assertFalse(flow.completeSetup(permissionRequestLaunched = true))
        assertTrue(flow.isWaitingForPermissionResult)
        assertTrue(flow.onPermissionRequestFinished())
        assertFalse(flow.isWaitingForPermissionResult)
    }

    @Test
    fun googleSignInWaitsForPermissionRequestBeforeOpeningAnimation() {
        val flow = OnboardingPermissionFlow()

        assertFalse(flow.completeSetup(permissionRequestLaunched = true))
        assertTrue(flow.isWaitingForPermissionResult)
        assertTrue(flow.onPermissionRequestFinished())
        assertFalse(flow.isWaitingForPermissionResult)
    }

    @Test
    fun allowingPermissionReleasesOpeningGate() {
        val flow = OnboardingPermissionFlow()

        assertFalse(flow.completeSetup(permissionRequestLaunched = true))
        assertTrue(flow.onPermissionRequestFinished())
        assertFalse(flow.isWaitingForPermissionResult)
    }

    @Test
    fun denyingPermissionReleasesOpeningGate() {
        val flow = OnboardingPermissionFlow()

        assertFalse(flow.completeSetup(permissionRequestLaunched = true))
        assertTrue(flow.onPermissionRequestFinished())
        assertFalse(flow.isWaitingForPermissionResult)
    }

    @Test
    fun grantedPermissionAndAndroidBelow13ContinueWithoutWaiting() {
        listOf(
            33 to true,
            32 to false
        ).forEach { (sdkInt, permissionGranted) ->
            val requestRequired = shouldRequestNotificationPermission(
                sdkInt = sdkInt,
                permissionGranted = permissionGranted,
                permissionPreviouslyRequested = false
            )
            val flow = OnboardingPermissionFlow()

            assertFalse(requestRequired)
            assertTrue(flow.completeSetup(permissionRequestLaunched = requestRequired))
            assertFalse(flow.isWaitingForPermissionResult)
        }
    }

    @Test
    fun permissionAlreadyRequestedDoesNotStartAnotherRequest() {
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = 33,
                permissionGranted = false,
                permissionPreviouslyRequested = true
            )
        )
    }
}
