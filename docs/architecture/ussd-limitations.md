# USSD gateway limitations

RouteBot uses a two-path USSD strategy:

1. **Primary** — public `TelephonyManager.sendUssdRequest` (API 26+) with a timeout
2. **Fallback** — dial the USSD code (`TelecomManager.placeCall` / `ACTION_CALL`) and capture the carrier dialog text via the optional **RouteBot USSD Capture** Accessibility service

This matches how reliable OEM-facing USSD agents work when manufacturers break the telephony callback (common on Oppo/ColorOS, some Xiaomi/Vivo builds, etc.).

## Requirements

- Runtime permission: `CALL_PHONE`
- For fallback: enable **Settings → Accessibility → RouteBot USSD Capture**
- Screen unlocked when the dial fallback runs (dialog must be visible to Accessibility)

## Supported

- Single-shot USSD codes (e.g. `*123#`) with response text returned in the command ack
- Optional `subscription_id` for dual-SIM when the OEM exposes phone accounts / slot extras

## Not supported / unreliable

- Guaranteed multi-step interactive USSD menus (only the first dialog text is captured)
- Hidden / reflection-based telephony hooks
- Capture while the screen is off or Accessibility is disabled (fallback path)

## Agent behavior

- Command type: `ussd` with payload `{ "code": "*123#", "subscription_id": 1 }`
- Failures are reported via command ack `failed` with a clear error message
- Operators should enable Accessibility on target hardware if the API path times out
