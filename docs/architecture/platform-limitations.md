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

Carrier name, display name, slot index, subscription ID, eSIM flag, and **phone number**
(when available) are collected. Requires `READ_PHONE_STATE` (and `READ_PHONE_NUMBERS` on
API 26+ when requesting the line number). Returns an empty list (not an error) when the
permission is denied or the API is unavailable, so heartbeats are never blocked by it.

`slotIndex` / command `sim_slot` are **1-based**: `1` = SIM 1, `2` = SIM 2.

If telephony does not expose the MSISDN, the agent dials USSD `*2#` on that tray (once
per subscription, with a multi-hour cooldown), parses the number from the response, and
caches it for later heartbeats. Accessibility must be enabled for the dial-capture path
on OEMs that block `sendUssdRequest`.

Full dual-SIM behavior (SMS, USSD, dashboard): [sim-slots.md](sim-slots.md).

## SMS delivery reports

`sent` status reflects the OS radio's immediate acceptance of the message (near-instant).
`delivered` status reflects the carrier's delivery acknowledgment, which many carriers do not
support at all — in that case no delivery report ever arrives, which is a carrier/network
limitation, not a RouteBot defect. RouteBot waits up to 2 minutes for a delivery report before
giving up on that particular message.
