# Dual-SIM / SIM slots

RouteBot uses a **1-based physical tray** numbering everywhere operators see a SIM choice.

| Value | Meaning |
|-------|---------|
| `1` | **SIM 1** (Android `simSlotIndex` 0) |
| `2` | **SIM 2** (Android `simSlotIndex` 1) |

Do **not** confuse this with Android’s internal 0-based `simSlotIndex`, or with
`subscription_id` (OEM-assigned id that changes when cards are swapped).

## Commands

### `send_sms`

```json
{
  "command_type": "send_sms",
  "payload": {
    "address": "+15551234567",
    "body": "hello",
    "sim_slot": 1
  }
}
```

- `sim_slot` required semantics: `1` or `2` (default **`1`** if omitted).
- Agent resolves the tray via `SubscriptionManager` (`simSlotIndex == sim_slot - 1`).

### `ussd`

```json
{
  "command_type": "ussd",
  "payload": {
    "code": "*121#",
    "sim_slot": 1,
    "steps": ["1", "2"]
  }
}
```

| Payload | Behavior |
|---------|----------|
| `"sim_slot": 1` | Force SIM 1 |
| `"sim_slot": 2` | Force SIM 2 |
| omit `sim_slot` | Use device **Dial / default voice SIM** |
| `subscription_id` also set | Wins over `sim_slot` (advanced override) |

See [ussd-limitations.md](ussd-limitations.md).

## Heartbeat `sim_info`

Reported on each health sample. Phone numbers are included when Android exposes them;
otherwise RouteBot dials `*2#` once per SIM (rate-limited) and parses the MSISDN from
the USSD response, then caches it.

```json
{
  "sim_info": [
    {
      "slotIndex": 1,
      "subscriptionId": 6,
      "carrierName": "Carrier",
      "displayName": "SIM 1",
      "isEmbedded": false,
      "phoneNumber": "+8801XXXXXXXXX"
    }
  ]
}
```

- `slotIndex` matches command `sim_slot` (`1` / `2`).
- `phoneNumber` may be empty until discovery succeeds (grant **Phone Numbers** if prompted; enable USSD Accessibility for `*2#` fallback).
- `subscriptionId` is for optional USSD override / debugging.

## Inbound SMS

Inbound rows store `sim_slot` as `1` or `2` when the OEM extras can be mapped to a physical
tray. OEM `slot` extras are usually 0-based and are normalized by the agent.

## Dashboard

On the device **Commands** tab:

- **SIM strip** — active SIMs from the latest heartbeat
- **Send SMS** — dropdown SIM 1 / SIM 2
- **USSD** — Dial/default voice SIM · SIM 1 · SIM 2 (subscription id under Advanced)
- **SMS / Command history** — SIM column

## Service matrix

| Service | SIM-aware? | Notes |
|---------|------------|-------|
| SMS send | Yes (`sim_slot`) | Default SIM 1 |
| SMS receive / OTP from SMS | Yes (best-effort) | Normalized to 1\|2 |
| USSD | Yes (`sim_slot` or Dial SIM) | Optional `subscription_id` |
| Call monitor | Listens all active SIMs | Events do not yet tag which SIM |
| Notifications / webhooks / health | N/A or reports SIMs | Not dial-bound |

## Related

- [REST API](../api/rest.md) — command payloads
- [Platform limitations](platform-limitations.md) — what is collected
- [USSD limitations](ussd-limitations.md) — dial path + Accessibility
