# Implementation Plan - Android Release Signing Configuration

This plan outlines a secure, minimal procedure for creating a new Android upload keystore and configuring release signing for the first Google Play Store production release of Kolam Master (`com.kolammaster.app`).

## User Review Required

> [!IMPORTANT]
> **No files or keys have been created or modified yet.** Review this plan and confirm approval before we proceed with configuration.

- **Keystore Location:** We recommend storing the upload keystore file (`kolam-master-upload.jks`) in a secure directory outside your Git repository (such as your user profile folder on Windows, e.g., `C:\Users\YourUsername\.android\keystores\`).
- **Local Properties:** Signing passwords and file paths will be stored solely in local-only `local.properties` (which is already git-ignored).

## Open Questions

- Do you have a preferred secure folder outside the project root where you would like to keep the upload keystore file?
- Are you ready for me to provide the exact Gradle configuration snippet once this plan is approved?

## Proposed Changes

### [Build Configuration]
- Update `app/build.gradle.kts` to define a `release` signing configuration that reads keystore credentials from `local.properties`.
- Ensure `.gitignore` ignores sensitive keystore files and local credential properties.

## Verification Plan

### Automated Tests
- None (signing configuration is verified at build time).

### Manual Verification
- Verify that `./gradlew assembleRelease` successfully produces a signed release APK/AAB locally without exposing credentials in version control.
