# USSD gateway limitations

RouteBot uses a two-path USSD strategy:

1. **Primary** — public `TelephonyManager.sendUssdRequest` (API 26+) with a timeout (single-shot only)
2. **Fallback** — dial the USSD code (`TelecomManager.placeCall` / `ACTION_CALL`) and capture / type
   the carrier dialog via the optional **RouteBot USSD Capture** Accessibility service

This matches how reliable OEM-facing USSD agents work when manufacturers break the telephony
callback (common on Oppo/ColorOS, some Xiaomi/Vivo builds, etc.).

## Requirements

- Runtime permission: `CALL_PHONE`
- For fallback: enable **Settings → Accessibility → RouteBot USSD Capture**
- Screen unlocked when the dial fallback runs (dialog must be visible to Accessibility)

## Dual-SIM

SIM selection uses the same **1-based** tray numbers as SMS. Full details:
[sim-slots.md](sim-slots.md).

| Payload | Behavior |
|---------|----------|
| `"sim_slot": 1` | Force **SIM 1** |
| `"sim_slot": 2` | Force **SIM 2** |
| omit `sim_slot` | Device **Dial / default voice SIM** |
| `subscription_id` | Optional override (Android subscription id from heartbeat `sim_info`); wins if both set |

Example:

```json
{ "command_type": "ussd", "payload": { "code": "*121#", "sim_slot": 1, "steps": ["9", "1"] } }
```

## Supported

- Single-shot USSD codes (e.g. `*123#`) with response text in the command ack
- Multi-step menus via Accessibility (`steps` / `inputs` array) where the OEM shows a dialable dialog
- Dual-SIM selection via `sim_slot` or Dial/default voice SIM

## Not supported / unreliable

- Hidden / reflection-based telephony hooks
- Capture while the screen is off or Accessibility is disabled (fallback path)
- Guaranteed identical menu trees across carriers/OEMs

## Agent behavior

- Command type: `ussd`
- Failures are reported via command ack `failed` with a clear error message
- Operators should enable Accessibility on target hardware if the API path times out
