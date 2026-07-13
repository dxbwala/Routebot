# Platform limitations

RouteBot only uses officially supported Android APIs. Some remote-command features are
inherently constrained by the Android platform itself — these constraints are documented here
rather than worked around with hidden/reflection APIs.

## USSD

See [ussd-limitations.md](ussd-limitations.md).

## Remote screenshot

Android requires **interactive, one-time user consent** (`MediaProjectionManager`) before any
app can capture the screen — this cannot be granted silently, remotely, or while the device is
locked, by design of the platform.

- On the first `take_screenshot` command, RouteBot posts a high-priority notification. Tapping
  it shows the system's screen-capture consent dialog via a transparent activity
  (`ScreenCaptureConsentActivity`).
- Once granted, the app holds the projection token for reuse by subsequent `take_screenshot`
  commands **within the same app process** — no repeated prompts until the process restarts or
  the OS revokes the grant.
- If no response within 60 seconds, the command fails with a clear error (`command.failed`
  webhook/ack), rather than hanging indefinitely.
- Capture uses `MediaProjection` + `VirtualDisplay` + `ImageReader` (all public APIs). The PNG is
  encrypted at rest (Android Keystore AES-GCM key) before upload, then deleted (PRD §16).

## Remote video recording

Video capture uses the legacy `MediaRecorder.VideoSource.CAMERA` path with an explicitly
owned/unlocked `android.hardware.Camera` instance handed to the recorder via `setCamera()`
(the actual point of failure in naive `MediaRecorder` setups that never open a camera at all).

- The legacy `Camera` API is deprecated in the Android SDK; behavior varies by OEM, and some
  newer devices route it through a Camera2 compatibility shim with reduced feature support.
- Only a single rear-facing capture size (640×480 @ 15fps) is used for reliability across the
  widest range of devices; this is not configurable per-command in this version.
- The camera must not be in use by another app (e.g. the Camera app in foreground) or capture
  will fail with a clear error.
- The output is encrypted at rest (Android Keystore AES-GCM) before upload, then deleted
  (PRD §15).

## CPU usage telemetry

Best-effort only: derived from two `/proc/stat` samples 200ms apart. Some Android versions/OEMs
restrict `/proc/stat` access via SELinux policy, in which case `cpu_usage` is reported as `null`
rather than a fabricated value.

## SIM information

Only carrier name, display name, slot index, subscription ID, and eSIM flag are collected — no
phone numbers. Requires `READ_PHONE_STATE`; returns an empty list (not an error) when the
permission is denied or the API is unavailable, so heartbeats are never blocked by it.

## SMS delivery reports

`sent` status reflects the OS radio's immediate acceptance of the message (near-instant).
`delivered` status reflects the carrier's delivery acknowledgment, which many carriers do not
support at all — in that case no delivery report ever arrives, which is a carrier/network
limitation, not a RouteBot defect. RouteBot waits up to 2 minutes for a delivery report before
giving up on that particular message.
