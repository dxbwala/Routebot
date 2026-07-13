# Platform limitations

RouteBot only uses officially supported Android APIs. Some remote-command features are
inherently constrained by the Android platform itself — these constraints are documented here
rather than worked around with hidden/reflection APIs.

## USSD

See [ussd-limitations.md](ussd-limitations.md).

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
