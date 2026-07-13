# USSD gateway limitations

RouteBot uses only public Android APIs (`TelephonyManager.sendUssdRequest` on API 26+).

## Supported

- Single-shot USSD codes (e.g. `*123#`) when the OEM/telephony stack implements the callback
- Optional subscription/SIM slot selection where `createForSubscriptionId` is available

## Not supported / unreliable

- Multi-step interactive USSD menus (no public session API)
- Guaranteed response capture on all OEMs — some devices never invoke the callback
- Hidden / reflection-based telephony hooks (explicitly out of scope)
- Background initiation without appropriate runtime permissions on some manufacturers

## Agent behavior

- Command type: `ussd` with payload `{ "code": "*123#", "subscription_id": 0 }`
- Failures are reported via command ack `failed` with a clear error message
- Operators should treat USSD as best-effort and verify on target hardware
