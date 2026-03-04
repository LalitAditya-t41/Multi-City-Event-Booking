# Stripe API Reference — Payments, Refunds, Webhooks & Sandbox

**Base URL:** `https://api.stripe.com`
**Auth:** All requests use HTTP Basic Auth with your secret key as the username (password left blank).
**Mode:** Key prefix determines mode — `sk_test_...` = sandbox, `sk_live_...` = production.
**Encoding:** Request bodies are `application/x-www-form-urlencoded`. Responses are JSON.
**Versioning:** Stripe API versions are date-based. Pin your version in the `Stripe-Version` header
or in your SDK initialisation to avoid breaking changes from Stripe releases.

### Idempotency Keys

For any `POST` request that should not be executed more than once (create PaymentIntent, create
refund), send an `Idempotency-Key` header. Stripe deduplicates requests with the same key within
24 hours and returns the cached response without re-executing.

```bash
curl https://api.stripe.com/v1/payment_intents \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -H "Idempotency-Key: BK-20240301-001-create-intent" \
  -d amount=2000 \
  -d currency=inr
```

> Use a deterministic key per operation — e.g. `{booking_ref}-create-intent` or
> `{booking_ref}-refund`. This prevents duplicate charges or double refunds on network retries.

### HTTP Error Codes

| Code | Meaning | What to do |
|---|---|---|
| `400` | Bad request — invalid parameters | Check request body, log `error.message` |
| `401` | Unauthorized — bad API key | Check `sk_test_...` key is correct |
| `402` | Payment required — card declined | Read `error.decline_code`, show user-friendly message |
| `404` | Not found — invalid ID | Check PaymentIntent / Refund ID is correct |
| `409` | Conflict — idempotency key reused with different params | Use unique idempotency keys per operation |
| `429` | Rate limited | Back off and retry — stricter in sandbox than live |
| `500` / `502` / `503` | Stripe server error | Retry with exponential backoff |

### Error Object

All errors return a consistent JSON body:

```json
{
  "error": {
    "type": "card_error",
    "code": "card_declined",
    "decline_code": "insufficient_funds",
    "message": "Your card has insufficient funds.",
    "param": null,
    "payment_intent": {
      "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
      "status": "requires_payment_method"
    }
  }
}
```

| Field | Description |
|---|---|
| `type` | `card_error`, `invalid_request_error`, `api_error`, `authentication_error`, `rate_limit_error` |
| `code` | Machine-readable error code (e.g. `card_declined`, `expired_card`, `incorrect_cvc`) |
| `decline_code` | Only on `card_error` — specific decline reason (e.g. `insufficient_funds`, `lost_card`) |
| `message` | Human-readable description — safe to log, do NOT show raw to users |
| `payment_intent` | The PaymentIntent object at time of error — check its `status` to decide next action |

---

## 1. Payment Intents

A `PaymentIntent` guides you through collecting a payment from your customer. Create exactly one
per order or customer session. It transitions through multiple statuses as it interfaces with
Stripe.js to perform authentication flows and ultimately creates at most one successful charge.

### PaymentIntent Object

```json
{
  "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
  "object": "payment_intent",
  "amount": 2000,
  "amount_capturable": 0,
  "amount_received": 0,
  "automatic_payment_methods": { "enabled": true },
  "canceled_at": null,
  "cancellation_reason": null,
  "capture_method": "automatic",
  "client_secret": "pi_3MtwBwLkdIwHu7ix28a3tqPa_secret_YrKJUKribcBjcG8HVhfZluoGH",
  "confirmation_method": "automatic",
  "created": 1680800504,
  "currency": "usd",
  "customer": null,
  "description": null,
  "last_payment_error": null,
  "latest_charge": null,
  "livemode": false,
  "metadata": {},
  "next_action": null,
  "payment_method": null,
  "payment_method_types": ["card", "link"],
  "receipt_email": null,
  "setup_future_usage": null,
  "shipping": null,
  "statement_descriptor": null,
  "statement_descriptor_suffix": null,
  "status": "requires_payment_method",
  "transfer_data": null,
  "transfer_group": null
}
```

### Key Fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique identifier. Save this as `stripe_payment_intent_id` in your DB. |
| `amount` | integer | Amount in smallest currency unit. e.g. USD: 2000 = $20.00 (cents). INR: 2000 = ₹20.00 (paise). Always multiply by 100 for non-zero-decimal currencies. |
| `amount_received` | integer | Amount actually received. Use to verify full payment on confirm. |
| `client_secret` | string | Send to frontend only. Never log or store server-side. |
| `currency` | string | Lowercase ISO 4217 code (e.g. `usd`, `inr`). |
| `last_payment_error` | object | Error from the most recent failed confirmation attempt. Contains `code`, `decline_code`, `message`. Cleared on next update. |
| `latest_charge` | string | The `ch_...` charge ID. Populated only after confirmation is attempted. Save this for refund reference. |
| `livemode` | boolean | `false` = sandbox/test mode. |
| `metadata` | object | Your own key-value data (e.g. `booking_ref`, `event_id`, `user_id`). |
| `next_action` | object | Present when `status=requires_action` (e.g. 3DS redirect). Frontend must handle this. Example structure below. |
| `receipt_email` | string | Stripe sends a receipt to this email on success. |
| `status` | enum | Current state. See status lifecycle below. |

**`next_action` Object — 3DS Redirect Example:**

When `status=requires_action`, the `next_action` object tells your frontend where to redirect
the user for authentication. Stripe.js handles this automatically when using
`stripe.confirmPayment()` — you only need to parse this manually in server-side confirmation flows.

```json
{
  "next_action": {
    "type": "redirect_to_url",
    "redirect_to_url": {
      "url": "https://hooks.stripe.com/3d_secure_2/authenticate/...",
      "return_url": "https://yourapp.com/booking/confirm"
    }
  }
}
```

> If your backend receives `payment_intent.requires_action` via webhook, do NOT confirm the
> booking. Wait for `payment_intent.succeeded` which fires only after the customer completes
> 3DS and payment is captured.

### PaymentIntent Status Lifecycle

```
requires_payment_method
        ↓ (payment method attached)
requires_confirmation
        ↓ (confirmed)
requires_action        ← 3DS / redirect required
        ↓ (action completed)
processing
        ↓
succeeded              ← payment captured
        or
canceled               ← explicitly canceled or too many failed attempts
```

| Status | Meaning |
|---|---|
| `requires_payment_method` | Newly created, no payment method attached yet |
| `requires_confirmation` | Payment method attached, needs confirmation |
| `requires_action` | Additional customer action needed (e.g. 3DS) |
| `processing` | Payment being processed by Stripe |
| `requires_capture` | Authorized but not yet captured (manual capture mode only) |
| `succeeded` | Payment completed successfully |
| `canceled` | Canceled — no further charges possible |

---

### 1.1 Create a PaymentIntent

```
POST /v1/payment_intents
```

**Required Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `amount` | integer | Amount in smallest currency unit. Min $0.50 USD equivalent. Max 8 digits. |
| `currency` | enum | Lowercase ISO 4217 currency code. |

**Common Optional Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `automatic_payment_methods[enabled]` | boolean | Enable payment methods configured in Dashboard. Recommended. |
| `customer` | string | Stripe Customer ID if you have one. |
| `description` | string | Displayed to users. |
| `metadata` | object | Your key-value pairs (e.g. `booking_ref`, `event_id`). |
| `receipt_email` | string | Sends Stripe receipt on success (live mode always sends). |
| `setup_future_usage` | enum | `on_session` or `off_session` — saves payment method to customer. |
| `confirm` | boolean | Set `true` to create and confirm in one call. |
| `payment_method` | string | Only when `confirm=true`. Attach a PaymentMethod ID directly. |

**Request:**
```bash
curl https://api.stripe.com/v1/payment_intents \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d amount=2000 \
  -d currency=usd \
  -d "automatic_payment_methods[enabled]"=true \
  -d receipt_email="user@example.com" \
  -d "metadata[booking_ref]"=BK-20240301-001 \
  -d "metadata[event_id]"=EVT-123
```

**Response:**
```json
{
  "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
  "object": "payment_intent",
  "amount": 2000,
  "amount_received": 0,
  "automatic_payment_methods": { "enabled": true },
  "client_secret": "pi_3MtwBwLkdIwHu7ix28a3tqPa_secret_YrKJUKribcBjcG8HVhfZluoGH",
  "currency": "usd",
  "livemode": false,
  "metadata": { "booking_ref": "BK-20240301-001", "event_id": "EVT-123" },
  "status": "requires_payment_method"
}
```

---

### 1.2 Retrieve a PaymentIntent

```
GET /v1/payment_intents/:id
```

Use after `onOrderComplete` / `return_url` redirect to verify `status=succeeded` before
confirming the booking internally.

**Request:**
```bash
curl https://api.stripe.com/v1/payment_intents/pi_3MtwBwLkdIwHu7ix28a3tqPa \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:"
```

**Response (succeeded):**
```json
{
  "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
  "object": "payment_intent",
  "amount": 2000,
  "amount_received": 2000,
  "currency": "usd",
  "latest_charge": "ch_3MtwBwLkdIwHu7ix28a3tqPa",
  "livemode": false,
  "metadata": { "booking_ref": "BK-20240301-001" },
  "receipt_email": "user@example.com",
  "status": "succeeded"
}
```

> Always check `status === "succeeded"` AND `amount_received === amount` before confirming booking.
> Save `latest_charge` (`ch_...`) — required if you create a refund via charge ID instead of
> payment_intent ID.

---

### 1.3 Confirm a PaymentIntent

```
POST /v1/payment_intents/:id/confirm
```

Confirms the customer intends to pay. In most flows using Stripe.js and `stripe.confirmPayment()`,
this happens client-side automatically. Use this endpoint directly for server-side confirmation
(e.g. when `confirmation_method=manual`).

**`confirmation_method` — Which to use:**

| Value | Behaviour | Use when |
|---|---|---|
| `automatic` (default) | Payment can be confirmed client-side using `client_secret` and Stripe.js | Standard frontend checkout with Payment Element |
| `manual` | All confirmation attempts must use secret key server-side | Server-driven flows, no frontend involvement |

For FR5's standard checkout flow, `automatic` is correct — the frontend uses `stripe.confirmPayment()`
and the backend only retrieves the result via `GET /v1/payment_intents/:id`.

**Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `payment_method` | string | PaymentMethod ID to attach and confirm with. |
| `return_url` | string | Redirect URL after any required 3DS action. |
| `receipt_email` | string | Override receipt email. |

**Request:**
```bash
curl https://api.stripe.com/v1/payment_intents/pi_3MtweELkdIwHu7ix0Dt0gF2H/confirm \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d payment_method=pm_card_visa \
  --data-urlencode return_url="https://yourapp.com/booking/confirm"
```

**Response (succeeded):**
```json
{
  "id": "pi_3MtweELkdIwHu7ix0Dt0gF2H",
  "object": "payment_intent",
  "amount": 2000,
  "amount_received": 2000,
  "latest_charge": "ch_3MtweELkdIwHu7ix05lnLAFd",
  "payment_method": "pm_1MtweELkdIwHu7ixxrsejPtG",
  "status": "succeeded"
}
```

---

### 1.4 Cancel a PaymentIntent

```
POST /v1/payment_intents/:id/cancel
```

Cancels a PaymentIntent when it is in one of these statuses:
`requires_payment_method`, `requires_capture`, `requires_confirmation`, `requires_action`,
or (rarely) `processing`.

Once canceled, no additional charges are made and all further operations on it fail.
For `requires_capture` status, remaining `amount_capturable` is automatically refunded.

**Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `cancellation_reason` | string | Optional. `duplicate`, `fraudulent`, `requested_by_customer`, or `abandoned`. |

**Request:**
```bash
curl -X POST https://api.stripe.com/v1/payment_intents/pi_3MtwBwLkdIwHu7ix28a3tqPa/cancel \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d cancellation_reason=requested_by_customer
```

**Response:**
```json
{
  "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
  "object": "payment_intent",
  "amount": 2000,
  "amount_received": 0,
  "canceled_at": 1680801569,
  "cancellation_reason": "requested_by_customer",
  "status": "canceled"
}
```

> Use `cancel` only when payment has NOT yet succeeded (i.e. no money moved).
> If `status=succeeded`, you must use `POST /v1/refunds` instead — you cannot cancel a
> completed payment.

---

### 1.5 Update a PaymentIntent

```
POST /v1/payment_intents/:id
```

Update properties without confirming. Useful for attaching metadata after creation
or updating `amount` before the user pays. Note: updating `payment_method` always
requires re-confirming the PaymentIntent.

**Request:**
```bash
curl https://api.stripe.com/v1/payment_intents/pi_3MtwBwLkdIwHu7ix28a3tqPa \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d "metadata[order_id]"=6735
```

**Response:**
```json
{
  "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
  "object": "payment_intent",
  "amount": 2000,
  "currency": "usd",
  "metadata": { "booking_ref": "BK-20240301-001", "order_id": "6735" },
  "status": "requires_payment_method"
}
```

---

## 2. Refunds

Refund objects allow you to refund a previously created charge that hasn't been refunded yet.
Funds are returned to the credit or debit card that was originally charged. You can issue
multiple partial refunds until the total charge amount is fully refunded. Once fully refunded,
a charge cannot be refunded again.

### Refund Object

```json
{
  "id": "re_1Nispe2eZvKYlo2Cd31jOCgZ",
  "object": "refund",
  "amount": 1000,
  "balance_transaction": "txn_1Nispe2eZvKYlo2CYezqFhEx",
  "charge": "ch_1NirD82eZvKYlo2CIvbtLWuY",
  "created": 1692942318,
  "currency": "usd",
  "destination_details": {
    "card": {
      "reference": "123456789012",
      "reference_status": "available",
      "reference_type": "acquirer_reference_number",
      "type": "refund"
    },
    "type": "card"
  },
  "metadata": {},
  "payment_intent": "pi_1GszsK2eZvKYlo2CfhZyoZLp",
  "reason": null,
  "receipt_number": null,
  "status": "succeeded"
}
```

### Key Fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique refund ID (`re_...`). Save as `stripe_refund_id` in your DB. |
| `amount` | integer | Amount refunded in smallest currency unit. |
| `charge` | string | The `ch_...` charge that was refunded. |
| `payment_intent` | string | The `pi_...` PaymentIntent that was refunded. |
| `reason` | enum | `duplicate`, `fraudulent`, `requested_by_customer`, or `expired_uncaptured_charge`. |
| `status` | string | `pending`, `requires_action`, `succeeded`, `failed`, or `canceled`. |
| `pending_reason` | enum | Why refund is pending. Values: `processing`, `insufficient_funds`, `charge_for_pending_refund_disputed`, `merchant_request`. Only present when `status=pending`. |
| `failure_reason` | string | Present when `status=failed`. E.g. `merchant_request`, `charge_for_pending_refund_disputed`. |

### Refund Status Values

| Status | Meaning |
|---|---|
| `pending` | Refund initiated, awaiting processing (common for async payment methods) |
| `requires_action` | Customer action needed to complete refund |
| `succeeded` | Refund processed successfully |
| `failed` | Refund failed — see `failure_reason` |
| `canceled` | Refund canceled (only possible when `status=requires_action`) |

---

### 2.1 Create a Refund

```
POST /v1/refunds
```

Must specify either `charge` or `payment_intent`. Using `payment_intent` is recommended —
Stripe resolves the underlying charge automatically.

**Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `payment_intent` | string | The PaymentIntent ID to refund. Recommended over `charge`. |
| `charge` | string | The Charge ID to refund. Alternative to `payment_intent`. |
| `amount` | integer | Partial refund amount in smallest unit. Omit for full refund. |
| `reason` | string | `duplicate`, `fraudulent`, or `requested_by_customer`. |
| `metadata` | object | Your key-value pairs for tracking. |

**Request — Full Refund:**
```bash
curl https://api.stripe.com/v1/refunds \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d payment_intent=pi_3MtwBwLkdIwHu7ix28a3tqPa \
  -d reason=requested_by_customer \
  -d "metadata[booking_ref]"=BK-20240301-001
```

**Request — Partial Refund:**
```bash
curl https://api.stripe.com/v1/refunds \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d payment_intent=pi_3MtwBwLkdIwHu7ix28a3tqPa \
  -d amount=1200 \
  -d reason=requested_by_customer \
  -d "metadata[booking_ref]"=BK-20240301-001
```

**Response:**
```json
{
  "id": "re_1Nispe2eZvKYlo2Cd31jOCgZ",
  "object": "refund",
  "amount": 2000,
  "charge": "ch_1NirD82eZvKYlo2CIvbtLWuY",
  "created": 1692942318,
  "currency": "usd",
  "metadata": { "booking_ref": "BK-20240301-001" },
  "payment_intent": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
  "reason": "requested_by_customer",
  "status": "succeeded"
}
```

> If `reason=fraudulent`, Stripe adds the card and email to block lists and improves fraud
> detection. Only use this if genuinely fraudulent.

---

### 2.2 Retrieve a Refund

```
GET /v1/refunds/:id
```

Use to check current `status` of an async refund.

**Request:**
```bash
curl https://api.stripe.com/v1/refunds/re_1Nispe2eZvKYlo2Cd31jOCgZ \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:"
```

**Response:**
```json
{
  "id": "re_1Nispe2eZvKYlo2Cd31jOCgZ",
  "object": "refund",
  "amount": 1000,
  "charge": "ch_1NirD82eZvKYlo2CIvbtLWuY",
  "currency": "usd",
  "payment_intent": "pi_1GszsK2eZvKYlo2CfhZyoZLp",
  "status": "succeeded"
}
```

---

### 2.3 Update a Refund

```
POST /v1/refunds/:id
```

Only `metadata` can be updated on a refund. Useful for attaching your internal `booking_ref`
or cancellation reason after the refund is created.

**Request:**
```bash
curl https://api.stripe.com/v1/refunds/re_1Nispe2eZvKYlo2Cd31jOCgZ \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d "metadata[booking_ref]"=BK-20240301-001 \
  -d "metadata[cancelled_by]"=user
```

---

### 2.4 Cancel a Refund

```
POST /v1/refunds/:id/cancel
```

Can only cancel a refund when `status=requires_action`. Refunds in any other state cannot
be canceled via API. In sandbox, card refunds can be canceled within 30 minutes.

**Request:**
```bash
curl -X POST https://api.stripe.com/v1/refunds/re_1Nispe2eZvKYlo2Cd31jOCgZ/cancel \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:"
```

**Response:**
```json
{
  "id": "re_1Nispe2eZvKYlo2Cd31jOCgZ",
  "object": "refund",
  "amount": 1000,
  "failure_reason": "merchant_request",
  "status": "canceled"
}
```

---

### 2.5 List All Refunds

```
GET /v1/refunds
```

Filter by `payment_intent` or `charge` to get refunds for a specific transaction.

**Request:**
```bash
curl -G https://api.stripe.com/v1/refunds \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d payment_intent=pi_3MtwBwLkdIwHu7ix28a3tqPa \
  -d limit=10
```

---

## 3. Webhook Events

Stripe sends webhook events to your endpoint as HTTP `POST` requests with a JSON body.
Always verify the `Stripe-Signature` header before processing.

### Signature Verification

```java
// Spring Boot
String payload   = request.getBody();
String sigHeader = request.getHeader("Stripe-Signature");
String secret    = "whsec_..."; // from Stripe Dashboard → Webhooks

Event event = Webhook.constructEvent(payload, sigHeader, secret);
```

```bash
# Test locally with Stripe CLI
stripe login
stripe listen --forward-to localhost:8080/api/webhooks/stripe
stripe trigger payment_intent.succeeded
```

**Critical Webhook Rules:**

| Rule | Detail |
|---|---|
| Return `HTTP 200` immediately | Acknowledge receipt first, then process asynchronously |
| Non-2xx response = retry | Stripe retries with exponential backoff for up to 3 days |
| Always idempotent | Check if booking/refund already processed before acting — Stripe may deliver the same event more than once |
| Verify signature always | Without `Webhook.constructEvent()` verification, anyone can POST fake events to your endpoint |
| Do not trust order | Events can arrive out of order — always fetch the resource fresh if you need guaranteed current state |

### Webhook Event Object Structure

```json
{
  "id": "evt_1NdRBo2eZvKYlo2C0bCq0SXx",
  "object": "event",
  "type": "payment_intent.succeeded",
  "created": 1692888000,
  "livemode": false,
  "data": {
    "object": { }
  }
}
```

> `data.object` contains the full Stripe object that triggered the event
> (e.g. the full `PaymentIntent` or `Refund` object).

---

### 3.1 Payment Intent Events

| Event | data.object | Trigger |
|---|---|---|
| `payment_intent.created` | PaymentIntent | A new PaymentIntent is created |
| `payment_intent.succeeded` | PaymentIntent | PaymentIntent has successfully completed payment |
| `payment_intent.payment_failed` | PaymentIntent | Attempt to create a payment method or payment has failed |
| `payment_intent.canceled` | PaymentIntent | PaymentIntent is canceled |
| `payment_intent.processing` | PaymentIntent | PaymentIntent has started processing |
| `payment_intent.requires_action` | PaymentIntent | PaymentIntent transitions to `requires_action` |
| `payment_intent.amount_capturable_updated` | PaymentIntent | Funds available to capture |

**`payment_intent.requires_action` — Do NOT confirm booking yet:**
```json
{
  "type": "payment_intent.requires_action",
  "data": {
    "object": {
      "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
      "status": "requires_action",
      "next_action": {
        "type": "redirect_to_url",
        "redirect_to_url": {
          "url": "https://hooks.stripe.com/3d_secure_2/authenticate/...",
          "return_url": "https://yourapp.com/booking/confirm"
        }
      },
      "metadata": { "booking_ref": "BK-20240301-001" }
    }
  }
}
```

> When you receive this: do nothing to the booking. The customer is mid-3DS. Wait for
> `payment_intent.succeeded` before confirming. If the customer abandons, `payment_intent.payment_failed`
> or `payment_intent.canceled` will eventually arrive.

**`payment_intent.succeeded` — Primary confirmation event:**
```json
{
  "type": "payment_intent.succeeded",
  "data": {
    "object": {
      "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
      "amount": 2000,
      "amount_received": 2000,
      "currency": "usd",
      "latest_charge": "ch_3MtwBwLkdIwHu7ix28a3tqPa",
      "metadata": { "booking_ref": "BK-20240301-001" },
      "status": "succeeded"
    }
  }
}
```

**`payment_intent.payment_failed` — Trigger seat lock release:**
```json
{
  "type": "payment_intent.payment_failed",
  "data": {
    "object": {
      "id": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
      "last_payment_error": {
        "code": "card_declined",
        "decline_code": "insufficient_funds",
        "message": "Your card has insufficient funds."
      },
      "metadata": { "booking_ref": "BK-20240301-001" },
      "status": "requires_payment_method"
    }
  }
}
```

---

### 3.2 Refund Events

| Event | data.object | Trigger |
|---|---|---|
| `refund.created` | Refund | A new refund is created |
| `refund.updated` | Refund | Refund status changes (e.g. `pending` → `succeeded`) |
| `refund.failed` | Refund | Refund has failed |

**`refund.created`:**
```json
{
  "type": "refund.created",
  "data": {
    "object": {
      "id": "re_1Nispe2eZvKYlo2Cd31jOCgZ",
      "amount": 2000,
      "payment_intent": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
      "reason": "requested_by_customer",
      "status": "pending"
    }
  }
}
```

**`refund.updated` — Async refund resolved:**
```json
{
  "type": "refund.updated",
  "data": {
    "object": {
      "id": "re_1Nispe2eZvKYlo2Cd31jOCgZ",
      "amount": 2000,
      "payment_intent": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
      "status": "succeeded"
    }
  }
}
```

**`refund.failed` — Alert ops and notify user:**
```json
{
  "type": "refund.failed",
  "data": {
    "object": {
      "id": "re_1Nispe2eZvKYlo2Cd31jOCgZ",
      "amount": 2000,
      "failure_reason": "merchant_request",
      "payment_intent": "pi_3MtwBwLkdIwHu7ix28a3tqPa",
      "status": "failed"
    }
  }
}
```

---

### 3.3 Recommended Events to Register

For a booking + payments flow, register only these events:

```
payment_intent.succeeded
payment_intent.payment_failed
payment_intent.canceled
refund.created
refund.updated
refund.failed
```

---

## 4. Sandbox Testing

Use sandbox API keys (`sk_test_...`, `pk_test_...`) and the test cards below.
Transactions in sandbox mode do not move real funds.

> Never use real card details in testing. The Stripe Services Agreement prohibits it.

**Sandbox Behaviour Differences vs Live:**

| Behaviour | Sandbox | Live |
|---|---|---|
| Funds moved | No | Yes |
| Settlement | Instant (added to available balance immediately) | 2–7 days pending → available |
| Rate limits | Stricter | More lenient |
| Webhook retries | Same as live | Same as live |
| Refund timing | Immediate for most cards | 5–10 business days to cardholder |

**Currency Units — Smallest Unit Reference:**

| Currency | Smallest Unit | Example: ₹150.00 / $1.50 |
|---|---|---|
| INR (Indian Rupee) | Paise (1/100) | `amount=15000` |
| USD | Cents (1/100) | `amount=150` |
| JPY | Yen (zero-decimal) | `amount=150` (no conversion) |
| GBP | Pence (1/100) | `amount=150` |
| EUR | Cents (1/100) | `amount=150` |

> INR is NOT a zero-decimal currency. ₹150.00 = `amount=15000` (multiply by 100).

### 4.1 How to Use Test Cards

- Use card numbers below in any payment form or Stripe Dashboard
- Expiry: any valid future date (e.g. `12/34`)
- CVC: any 3 digits (4 digits for Amex)
- Other fields: any value

For server-side test code, use PaymentMethod IDs like `pm_card_visa` instead of raw card numbers.

```bash
curl https://api.stripe.com/v1/payment_intents \
  -u "sk_test_EXAMPLE_KEY_REPLACE_WITH_YOUR_OWN:" \
  -d amount=500 \
  -d currency=usd \
  -d payment_method=pm_card_visa \
  -d "payment_method_types[]"=card
```

**PaymentMethod IDs for Server-Side Testing**

Use these in API calls and test code instead of raw card numbers:

| PaymentMethod ID | Card | Behaviour |
|---|---|---|
| `pm_card_visa` | Visa `4242...` | Succeeds |
| `pm_card_mastercard` | Mastercard | Succeeds |
| `pm_card_amex` | American Express | Succeeds |
| `pm_card_visa_debit` | Visa debit | Succeeds |
| `pm_card_threeDSecure2Required` | 3DS required | Triggers `requires_action` |
| `pm_card_chargeDeclined` | Generic decline | `card_declined` / `generic_decline` |
| `pm_card_chargeDeclinedInsufficientFunds` | Insufficient funds | `card_declined` / `insufficient_funds` |
| `pm_card_chargeDeclinedExpiredCard` | Expired card | `expired_card` |
| `pm_card_chargeDeclinedIncorrectCvc` | Incorrect CVC | `incorrect_cvc` |

---

### 4.2 Successful Payments — By Card Brand

| Brand | Card Number | CVC | Notes |
|---|---|---|---|
| Visa | `4242 4242 4242 4242` | Any 3 | Standard success |
| Visa (debit) | `4000 0566 5566 5556` | Any 3 | |
| Mastercard | `5555 5555 5555 4444` | Any 3 | |
| Mastercard (debit) | `5200 8282 8282 8210` | Any 3 | |
| Mastercard (prepaid) | `5105 1051 0510 5100` | Any 3 | |
| American Express | `3782 822463 10005` | Any 4 | |
| Discover | `6011 1111 1111 1117` | Any 3 | |
| JCB | `3566 0020 2036 0505` | Any 3 | |
| UnionPay | `6200 0000 0000 0005` | Any 3 | |
| Diners Club | `3056 9300 0902 0004` | Any 3 | |

---

### 4.3 Successful Payments — By Country

| Country | Card Number | Brand |
|---|---|---|
| United States (US) | `4242 4242 4242 4242` | Visa |
| India (IN) | `4000 0035 6000 0008` | Visa |
| United Kingdom (GB) | `4000 0082 6000 0000` | Visa |
| Germany (DE) | `4000 0027 6000 0016` | Visa |
| France (FR) | `4000 0025 0000 0003` | Visa |
| Australia (AU) | `4000 0003 6000 0006` | Visa |
| Canada (CA) | `4000 0012 4000 0000` | Visa |
| Singapore (SG) | `4000 0070 2000 0003` | Visa |
| Japan (JP) | `4000 0039 2000 0003` | Visa |
| Brazil (BR) | `4000 0007 6000 0002` | Visa |

---

### 4.4 Declined Payments

| Description | Card Number | Error Code | Decline Code |
|---|---|---|---|
| Generic decline | `4000 0000 0000 0002` | `card_declined` | `generic_decline` |
| Insufficient funds | `4000 0000 0000 9995` | `card_declined` | `insufficient_funds` |
| Lost card | `4000 0000 0000 9987` | `card_declined` | `lost_card` |
| Stolen card | `4000 0000 0000 9979` | `card_declined` | `stolen_card` |
| Expired card | `4000 0000 0000 0069` | `expired_card` | n/a |
| Incorrect CVC | `4000 0000 0000 0127` | `incorrect_cvc` | n/a |
| Processing error | `4000 0000 0000 0119` | `processing_error` | n/a |
| Incorrect number | `4242 4242 4242 4241` | `incorrect_number` | n/a |
| Velocity limit exceeded | `4000 0000 0000 6975` | `card_declined` | `card_velocity_exceeded` |
| Decline after attaching to customer | `4000 0000 0000 0341` | `card_declined` | — |

> Card `4000 0000 0000 0341` attaches successfully to a Customer but fails when charged.
> Cards that simulate issuer declines cannot be attached to Customer objects (except this one).

---

### 4.5 3D Secure (3DS) Authentication

| Scenario | Card Number | Behaviour |
|---|---|---|
| 3DS required → succeeds | `4000 0025 0000 3155` | Requires auth for off-session. After setup, off-session succeeds. On-session always requires auth. |
| 3DS always required | `4000 0027 6000 3184` | Requires auth on all transactions regardless of setup. |
| Already set up for off-session | `4000 0038 0000 0446` | Off-session succeeds without auth. On-session requires auth. |
| 3DS required → insufficient funds | `4000 0082 6000 3178` | Requires auth but payment declined with `insufficient_funds` after auth. |
| 3DS required → succeeds (Radar) | `4000 0000 0000 3220` | Radar requests 3DS. Payment succeeds after auth. |
| 3DS required → declined (Radar) | `4000 0084 0000 1629` | Radar requests 3DS. Payment declined after auth with `card_declined`. |
| 3DS required → processing error | `4000 0084 0000 1280` | 3DS lookup fails with processing error. Payment declined. |
| 3DS supported, not required | `4000 0000 0000 3055` | 3DS may run but isn't required. |
| 3DS not supported | `3782 822463 10005` | AmEx — 3DS cannot be invoked. |

> Radar rules request 3DS on cards marked "3DS Required by default." Test your
> `next_action` handling with these cards.

---

### 4.6 Async Refund Simulation

Use these cards to test refund flows where status changes asynchronously after creation.

| Description | Card Number | Refund Behaviour |
|---|---|---|
| Async refund succeeds | `4000 0000 0000 7726` | Refund starts as `pending` → transitions to `succeeded` + fires `refund.updated` |
| Async refund fails | `4000 0000 0000 5126` | Refund starts as `succeeded` → transitions to `failed` + fires `refund.failed` |

> Canceling a card refund via API is only possible within 30 minutes in sandbox.
> In live mode the window is short and nonspecific.

---

### 4.7 Fraud Prevention (Radar)

| Description | Card Number | Behaviour |
|---|---|---|
| Always blocked (highest risk) | `4100 0000 0000 0019` | Radar always blocks. Risk level: highest. |
| Highest risk (may block) | `4000 0000 0000 4954` | Radar may block depending on settings. |
| Elevated risk | `4000 0000 0000 9235` | Radar may queue for review (Radar for Fraud Teams). |
| CVC check fails | `4000 0000 0000 0101` | CVC check fails if CVC provided. Radar may block. |
| Postal code check fails | `4000 0000 0000 0036` | Postal code check fails if provided. |

---

### 4.8 Dispute Simulation

| Description | Card Number | Behaviour |
|---|---|---|
| Fraudulent dispute | `4000 0000 0000 0259` | Charge succeeds → disputed as fraudulent. Protected after 3DS. |
| Not received dispute | `4000 0000 0000 2685` | Charge succeeds → disputed as product not received. Not 3DS protected. |
| Inquiry | `4000 0000 0000 1976` | Charge succeeds → disputed as inquiry. |

**Dispute evidence values (to simulate win/lose):**

| Evidence Value | Result |
|---|---|
| `winning_evidence` | Closes dispute as won — funds credited back |
| `losing_evidence` | Closes dispute as lost — no credit |
| `escalate_inquiry_evidence` | Escalates inquiry to full chargeback |

---

### 4.9 Stripe CLI — Trigger Test Events

```bash
# Install and authenticate
stripe login

# Forward webhook events to local server
stripe listen --forward-to localhost:8080/api/webhooks/stripe

# Trigger specific events
stripe trigger payment_intent.succeeded
stripe trigger payment_intent.payment_failed
stripe trigger payment_intent.canceled
stripe trigger refund.created
stripe trigger refund.updated
stripe trigger refund.failed
```

---

### 4.10 Rate Limits in Sandbox

Sandbox rate limits are stricter than live mode. If you receive `429` HTTP errors,
reduce request frequency. Do not load test against the Stripe sandbox API — use
Stripe's load testing guidance instead.

---

## 5. Quick Reference — Endpoints Used in FR5

| Step | Method | Endpoint | Purpose |
|---|---|---|---|
| Create payment session | `POST` | `/v1/payment_intents` | Get `client_secret` for frontend |
| Verify payment | `GET` | `/v1/payment_intents/:id` | Confirm `status=succeeded` |
| Update intent metadata | `POST` | `/v1/payment_intents/:id` | Attach `booking_ref` after creation |
| Cancel unpaid intent | `POST` | `/v1/payment_intents/:id/cancel` | Before payment — no refund needed |
| Server-side confirm | `POST` | `/v1/payment_intents/:id/confirm` | Manual confirmation mode only |
| Issue full refund | `POST` | `/v1/refunds` | After payment — buyer cancellation |
| Issue partial refund | `POST` | `/v1/refunds` | With `amount` param — e.g. cancellation fee deducted |
| Check refund status | `GET` | `/v1/refunds/:id` | For async refund polling |
| Update refund metadata | `POST` | `/v1/refunds/:id` | Attach `booking_ref` to refund record |
| Cancel pending refund | `POST` | `/v1/refunds/:id/cancel` | Only when `status=requires_action` |
| List refunds for payment | `GET` | `/v1/refunds?payment_intent=pi_...` | Audit all refunds against a booking |