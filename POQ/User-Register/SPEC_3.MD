# SPEC_3.MD — User Registration → Profile Setup → Preference Onboarding

## 1. Overview
This feature implements end-to-end onboarding for public users: registration, authentication, password flows, profile setup, preference selection, and wallet read. It is identity-only and uses shared JWT/security components.

**Primary users:** Public users (`USER`) registering and onboarding
**Secondary users:** Admin onboarding is out of scope for this flow
**Tech stack:** Spring Boot 3.5.11, Java 21, JPA, Flyway, JWT (`shared/security`)
**Scope:** v1.0 — User Onboarding (FR3 Stage 1)

---

## 2. Goals & Non-Goals

### Goals
- Enable registration + login with JWT access tokens and opaque refresh tokens.
- Support authenticated password change and token-based password reset.
- Support profile + preferences onboarding via a junction-backed preferences model (city FK + genre junction table).
- Provide active preference options API and wallet read API.

### Non-Goals (Out of Scope)
- Email verification and MFA.
- Password reset email delivery (token generation only).
- Eventbrite sync during onboarding.
- Admin registration/profile mapping in this flow.

---

## 3. Requirements

### User Registration & Auth
- [MUST] Allow registration with email + password; store password using BCrypt (never plaintext).
- [MUST] Registration request accepts only `email` and `password`. Role is not exposed in the public API; server always sets `role = USER` internally.
- [MUST] Duplicate email check is the **first validation** performed on registration, before all other checks.
- [MUST] Registration request must reject unknown/extra fields (e.g., `role`) with `400 Bad Request`.
- [MUST] Allow login with email + password and issue:
  - Access JWT with 1 hour expiry.
  - Refresh token with 1 day expiry.
- [MUST] Provide refresh token exchange endpoint to issue a new access JWT.
- [MUST] Provide logout endpoint that revokes refresh tokens.
- [MUST] Secure all non-auth endpoints with JWT via `shared/security/JwtTokenProvider` + JWT auth filter (no Basic auth).
- [MUST] Reject duplicate email registrations (case-insensitive).
- [WONT] Email verification and MFA in this flow.

### Password Management
- [MUST] Provide authenticated password change with current + new + confirm.
- [MUST] Provide password reset request endpoint that generates reset token.
- [MUST] Provide password reset confirm endpoint that validates token and sets new password.
- [MUST] Only most recently issued reset token is valid; issuing a new token invalidates prior active tokens.
- [MUST] Store reset token as hash; enforce unexpired + unconsumed + latest-only checks.
- [WONT] Email delivery implementation (token generation only).

### Profile + Preferences
- [MUST] Provide upsert API for user settings (profile + preferences).
- [MUST] Provide API to fetch user settings.
- [MUST] Preferences must be selected from global preference options stored in identity DB.
- [MUST] Enforce exactly 1 city and up to 3 genres.
- [SHOULD] Validate preference values against active options.

### Preference Options
- [MUST] Provide API to list active preference options (city + genre).

### Wallet
- [MUST] Provide API to read wallet balance (identity-owned source of truth).

### Eventbrite Sync
- [WONT] No Eventbrite sync in onboarding flow.

### Non-Functional / Architecture
- [MUST] Implement in `identity` module only, following `docs/MODULE_STRUCTURE_TEMPLATE.md`.
- [MUST] Use `shared/` for JWT/security only; no external API calls in this flow.
- [MUST] Follow test layering from `docs/TESTING_GUIDE.md`.

---

## 4. Domain Model

A `User` in this flow is a public account with role `USER`. User owns `UserSettings` (profile + preferences) and `UserWallet`. Preferences are selected from active global `PreferenceOption` entries. Refresh tokens and password reset tokens are persisted for session and recovery control.

**Entities & Relationships**
- `User`
  - Unique `id`, unique case-insensitive `email`.
  - Fields: `passwordHash` (BCrypt), `role` (default `USER`), `status` (default `ACTIVE`).
  - 1:1 `UserSettings`, 1:1 `UserWallet`.

- `UserSettings`
  - Unique by `user_id`.
  - Profile fields: `fullName`, `phone`, `dob`, `address`.
  - `preferred_city_option_id` FK → `preference_options(id)` where `type = CITY`.
  - Up to 3 genre preferences stored in `user_genre_preferences` junction table.
  - No direct FK to `discovery-catalog`'s `city` table — `identity` owns its own city option list.

- `UserWallet`
  - Unique by `user_id`.
  - Created at registration for `USER`. Initial balance = 0.

- `PreferenceOption`
  - `id BIGSERIAL`, type `CITY` or `GENRE`, value string, active flag, sort order.
  - Identity-owned; independent of `discovery-catalog`'s `city` table (no transitive dependency).

- `UserGenrePreference` (junction — full JPA entity with own PK)
  - `id BIGSERIAL PK`, `user_settings_id FK → user_settings(id)`, `preference_option_id FK → preference_options(id)` where `type = GENRE`.
  - Unique on `(user_settings_id, preference_option_id)`. Max 3 per `user_settings_id` enforced at service layer.

- `RefreshToken`
  - `user_id FK → users(id)`, `token_hash`, `expires_at`, optional `revoked_at`.
  - Revocation is enforced during refresh.

- `PasswordResetToken`
  - `user_id FK → users(id)`, `token_hash`, `expires_at`, optional `consumed_at`.
  - Latest-token-wins policy.

---

## 5. Architecture & File Structure

### Architecture Pattern
**Modular Monolith (Fixed)** — module registry in `PRODUCT.md`.

### Module Ownership
**Primary module:** `identity`  
**Cross-module dependencies:** none

### Inter-Module Communication
| Interaction | Type | Mechanism |
|---|---|---|
| None | N/A | N/A |

### Feature Files
```
identity/src/main/java/com/eventplatform/identity/
├── api/controller/AuthController.java
├── api/controller/UserSettingsController.java
├── api/controller/PreferenceOptionsController.java
├── api/controller/UserWalletController.java
├── api/dto/request/RegisterRequest.java
├── api/dto/request/LoginRequest.java
├── api/dto/request/RefreshTokenRequest.java
├── api/dto/request/LogoutRequest.java
├── api/dto/request/PasswordChangeRequest.java
├── api/dto/request/PasswordResetRequest.java
├── api/dto/request/PasswordResetConfirmRequest.java
├── api/dto/request/UserSettingsUpsertRequest.java
├── api/dto/response/RegisterResponse.java
├── api/dto/response/AuthTokensResponse.java
├── api/dto/response/UserProfileResponse.java
├── api/dto/response/UserSettingsResponse.java
├── api/dto/response/PreferenceOptionsResponse.java
├── api/dto/response/UserWalletResponse.java
├── domain/User.java
├── domain/UserSettings.java
├── domain/UserWallet.java
├── domain/PreferenceOption.java
├── domain/UserGenrePreference.java
├── domain/RefreshToken.java
├── domain/PasswordResetToken.java
├── service/AuthService.java
├── service/UserDetailsServiceImpl.java
├── service/UserSettingsService.java
├── service/PreferenceOptionsService.java
├── service/UserWalletService.java
├── repository/UserRepository.java
├── repository/UserSettingsRepository.java
├── repository/UserWalletRepository.java
├── repository/PreferenceOptionRepository.java
├── repository/RefreshTokenRepository.java
├── repository/PasswordResetTokenRepository.java
├── mapper/UserMapper.java
├── mapper/UserSettingsMapper.java
├── mapper/PreferenceOptionMapper.java
├── mapper/UserWalletMapper.java
└── exception/UserNotFoundException.java
```

### Shared Security Files (implemented — see SPEC_AUTH.MD)
```
shared/src/main/java/com/eventplatform/shared/security/
├── JwtTokenProvider.java          ← RS256 sign, validate, extract claims (@Component)
├── JwtAuthenticationFilter.java   ← OncePerRequestFilter; reads Bearer token, sets SecurityContext
├── SecurityConfig.java            ← stateless JWT filter chain; PasswordEncoder + AuthManager beans
├── SecurityConstants.java         ← single source of truth for all TTLs, header names, claim keys
└── Roles.java                     ← USER / ADMIN string constants
```

> All token TTLs used by `AuthService` must be read from `SecurityConstants` (in `shared/security/`), not hardcoded:
> - `SecurityConstants.ACCESS_TOKEN_TTL_S` = 3600 (1 hour)
> - `SecurityConstants.REFRESH_TOKEN_TTL_S` = 86400 (1 day)
> - `SecurityConstants.RESET_TOKEN_TTL_S` = 900 (15 minutes)
>
> `SecurityConfig.java` permits these FR3 public endpoints without JWT: `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/token/refresh`, `/api/v1/auth/password/reset`, `/api/v1/auth/password/reset/confirm`. All other endpoints require `Authorization: Bearer <token>`.

---

## 6. DB Schema

### Tables

**`users`**
- `id BIGSERIAL PK`, `email VARCHAR(255) NOT NULL`, `password_hash VARCHAR(255) NOT NULL`
- `role VARCHAR(50) NOT NULL DEFAULT 'USER'`, `status VARCHAR(50) DEFAULT 'ACTIVE'`
- `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Constraint: unique index on `LOWER(email)` (case-insensitive).
- No `organization_id` — booking users have no org context in this flow.

**`user_settings`**
- `id BIGSERIAL PK`, `user_id BIGINT UNIQUE FK → users(id) ON DELETE CASCADE`
- `full_name VARCHAR(255)`, `phone VARCHAR(20)`, `dob DATE`, `address TEXT`
- `preferred_city_option_id BIGINT FK → preference_options(id)` — must be `type = CITY`
- `notification_opt_in BOOLEAN DEFAULT TRUE`
- `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Constraint: unique `user_id`.
- Index: `idx_user_settings_city_option` on `preferred_city_option_id` (FK performance)

**`user_wallets`**
- `id BIGSERIAL PK`, `user_id BIGINT UNIQUE FK → users(id) ON DELETE CASCADE`
- `balance_amount NUMERIC(19,4) DEFAULT 0`, `currency VARCHAR(10) DEFAULT 'INR'`
- `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Constraint: unique `user_id`.

**`preference_options`**
- `id BIGSERIAL PK`, `type VARCHAR(50) NOT NULL` (`CITY` or `GENRE`)
- `value VARCHAR(100) NOT NULL`, `active BOOLEAN DEFAULT TRUE`, `sort_order INT DEFAULT 0`
- Constraint: `UNIQUE (type, value)`
- Indexes: `idx_preference_type` on `type`, `idx_preference_active` on `active`

**`user_genre_preferences`** (junction — replaces `TEXT[]` approach)
- `id BIGSERIAL PK`
- `user_settings_id BIGINT FK → user_settings(id) ON DELETE CASCADE`
- `preference_option_id BIGINT FK → preference_options(id)`
- Constraint: `UNIQUE (user_settings_id, preference_option_id)`
- Max 3 rows per `user_settings_id` enforced at service layer (not DB constraint).
- Index: `idx_genre_pref_option_id` on `preference_option_id` (FK performance)

**`refresh_tokens`**
- `id BIGSERIAL PK`, `user_id BIGINT FK → users(id) ON DELETE CASCADE`
- `token_hash VARCHAR(255) NOT NULL`, `expires_at TIMESTAMP NOT NULL`
- `revoked_at TIMESTAMP`, `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Indexes: `idx_refresh_user_id` on `user_id`; `UNIQUE idx_refresh_token_hash` on `token_hash` (lookup on every refresh call)

**`password_reset_tokens`**
- `id BIGSERIAL PK`, `user_id BIGINT FK → users(id) ON DELETE CASCADE`
- `token_hash VARCHAR(255) NOT NULL`, `expires_at TIMESTAMP NOT NULL`
- `consumed_at TIMESTAMP`, `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- Indexes: `idx_reset_user_id` on `user_id`; `UNIQUE idx_reset_token_hash` on `token_hash` (lookup on every reset confirm call)

### Flyway Migrations
Use **next global versions** in `app/src/main/resources/db/migration/` (single Flyway scan path).

FR1 used V0–V4. FR2 used V5–V9 (highest: `V9__create_show_slot_pricing_tier_table.sql`). FR3 starts at V10:

- `V10__create_users.sql` — users table
- `V11__create_preference_options.sql` — preference_options table _(must exist before user_settings FK)_
- `V12__create_user_settings.sql` — user_settings with `preferred_city_option_id FK`
- `V13__create_user_genre_preferences.sql` — junction table
- `V14__create_user_wallets.sql` — user_wallets
- `V15__create_refresh_tokens.sql` — refresh_tokens
- `V16__create_password_reset_tokens.sql` — password_reset_tokens

> Note: `preference_options` must be created before `user_settings` due to the FK on `preferred_city_option_id`.

---

## 7. API Contracts

### Public Auth Endpoints
- `POST /api/v1/auth/register`
  - Request: `{ "email": string, "password": string }`
  - Role is assigned server-side as `USER`. No role field accepted. Unknown fields rejected with `400`.
  - Response: `RegisterResponse` → `{ "userId": Long }`

- `POST /api/v1/auth/login`
  - Request: `{ "email": string, "password": string }`
  - Response: `{ "accessToken": string (JWT, 1h), "refreshToken": string (opaque random string, 1d) }`
  - Note: `accessToken` is a signed JWT. `refreshToken` is an opaque random string stored as hash in DB — it is NOT a JWT.

- `POST /api/v1/auth/token/refresh`
  - Request: `{ "refreshToken": string }`
  - Response: `{ "accessToken": string }` (new JWT, 1h)

- `POST /api/v1/auth/logout`
  - Request: `{ "refreshToken": string }`
  - Response: `{ "message": "Logged out successfully" }`

- `POST /api/v1/auth/password/reset`
  - Request: `{ "email": string }`
  - Response: `{ "message": "Password reset token generated" }` (token not returned to client)

- `POST /api/v1/auth/password/reset/confirm`
  - Request: `token`, `newPassword`, `confirmPassword`
  - Response: success

### JWT-Protected Endpoints
- `GET /api/v1/auth/me`
  - Response: `UserProfileResponse` → `{ "userId": Long, "email": string, "role": string, "status": string }`
- `POST /api/v1/auth/password/change`
  - Request: `{ "currentPassword": string, "newPassword": string, "confirmPassword": string }`
  - Response: `{ "message": "Password changed successfully" }`
- `GET /api/v1/preferences/options`
  - Response: `{ "options": [ { "type": "CITY"|"GENRE", "value": string } ] }`
- `GET /api/v1/users/me/settings`
  - Response: `{ "fullName": string, "phone": string, "dob": string, "address": string, "preferredCity": { "id": Long, "value": string }, "preferredGenres": [ { "id": Long, "value": string } ], "notificationOptIn": boolean }`
- `PUT /api/v1/users/me/settings`
  - Request: `{ "fullName": string, "phone": string, "dob": string, "address": string, "preferredCityOptionId": Long, "preferredGenreOptionIds": [Long, max 3], "notificationOptIn": boolean }`
  - Client sends `preference_options.id` values (not free strings); server validates they exist and are active.
  - Field names are camelCase in API; stored as FK references in DB.
- `GET /api/v1/users/me/wallet`
  - Response: `{ "balance": number, "currency": string }`

### Error Response Shape (all endpoints)
```json
{
  "error": {
    "code": "DUPLICATE_EMAIL",
    "message": "Email already in use",
    "field": "email"
  }
}
```
`field` is optional and only present for validation errors tied to a specific input field.

Error responses use `ErrorResponse` from `shared/common/dto`. Exception classes: `ResourceNotFoundException` (404), `BusinessRuleException` (4xx), `IntegrationException` (502 when applicable).

---

## 8. Business Logic

### Validation Rules
- Email uniqueness (case-insensitive) is checked **first**, before any other registration validation.
- Role is never accepted from the client; server always sets `USER`.
- Access token TTL = 1 hour (`SecurityConstants.ACCESS_TOKEN_TTL_S`); refresh token TTL = 1 day (`SecurityConstants.REFRESH_TOKEN_TTL_S`).
- Password reset token TTL = **15 minutes** (`SecurityConstants.RESET_TOKEN_TTL_S`).
- Logout revokes provided refresh token.
- Password reset token must be latest, unexpired, and unconsumed.
- Exactly 1 city and up to 3 unique genres.
- Preferences must map to active options.

### Security Rules
- JWT-only security for non-auth endpoints. No Basic auth.
- Users can only access their own settings/wallet.
- Logout revokes the **provided refresh token only** (current session). A future `/logout-all` endpoint can extend to all sessions.

### JWT Claim Contract
Applies to **access tokens only**. Refresh tokens are opaque random strings (not JWTs); they carry no claims and are validated only by DB hash lookup + expiry + revocation check.

| Claim | Value |
|---|---|
| `sub` | user `id` as String (BIGSERIAL Long — not UUID, consistent with all schema PKs) |
| `role` | `USER` |
| `iat` | issued-at epoch seconds |
| `exp` | iat + 3600 (1 hour) |
| `jti` | unique token UUID (for future revocation) |
| `typ` | `access` (access tokens only; refresh tokens are not JWTs) |

### External System Calls
- None.

### Concurrency & Integrity
- Unique constraints enforce identity invariants (`email`, `user_id` 1:1 tables).

---

## 9. Error Handling

All exceptions are handled by `GlobalExceptionHandler` in `shared/common/exception/`. No module-level `@ControllerAdvice`.

> Error response shape is defined in Section 7. All error responses use that shape: `{ "error": { "code": string, "message": string, "field"?: string } }`.

| Scenario | Exception Class | HTTP | User Message |
|---|---|---|---|
| Duplicate email on register | BusinessRuleException | 409 | "Email already in use" |
| Invalid credentials | BusinessRuleException | 401 | "Invalid credentials" |
| Missing/invalid JWT | — (Spring Security `AuthenticationEntryPoint`) | 401 | "Unauthorized" |
| Forbidden operation | BusinessRuleException | 403 | "Forbidden" |
| Refresh token invalid/expired/revoked | BusinessRuleException | 401 | "Refresh token invalid" |
| Reset token invalid/expired/consumed/not-latest | BusinessRuleException | 400 | "Reset token invalid" |
| Current password incorrect | BusinessRuleException | 400 | "Current password incorrect" |
| Password mismatch | BusinessRuleException | 400 | "Password mismatch" |
| Preference option inactive/not found | BusinessRuleException | 400 | "Invalid preference" |
| Wallet not found | ResourceNotFoundException | 404 | "Wallet not found" |

---

## 10. Acceptance Criteria (Tests)

### Layer 1 — `domain/` (Pure Unit Tests)
- [ ] should_create_user_with_user_role_assigned_server_side
- [ ] should_reject_duplicate_email_case_insensitive
- [ ] should_enforce_city_option_required_and_max_three_unique_genre_options
- [ ] should_reject_more_than_three_genre_preferences
- [ ] should_reject_duplicate_genre_option_ids_in_same_settings
- [ ] should_reject_expired_or_revoked_refresh_token
- [ ] should_accept_only_latest_unconsumed_unexpired_reset_token
- [ ] should_invalidate_all_prior_reset_tokens_when_new_token_issued
- [ ] should_initialize_wallet_with_zero_balance_on_user_creation
- [ ] should_reject_password_change_when_new_and_confirm_do_not_match

### Layer 2 — `service/` (Mockito Unit Tests)
- [ ] should_hash_password_with_bcrypt_and_save_user_on_register
- [ ] should_create_wallet_for_user_on_registration
- [ ] should_issue_access_and_refresh_tokens_on_login
- [ ] should_revoke_refresh_token_on_logout
- [ ] should_issue_new_access_token_when_refresh_token_valid
- [ ] should_generate_reset_token_and_invalidate_previous_tokens
- [ ] should_reset_password_when_token_is_latest_unexpired_unconsumed
- [ ] should_change_password_when_current_password_matches
- [ ] should_validate_active_preference_options_before_upsert

### Layer 3 — `api/` (@WebMvcTest)
- [ ] should_return_201_on_register_when_payload_valid
- [ ] should_return_409_on_register_when_email_exists
- [ ] should_return_400_on_register_when_role_field_included_in_request
- [ ] should_return_200_and_tokens_on_login_when_credentials_valid
- [ ] should_return_401_on_login_when_credentials_invalid
- [ ] should_return_200_on_token_refresh_when_refresh_token_valid
- [ ] should_return_401_on_token_refresh_when_refresh_token_invalid
- [ ] should_return_200_on_logout
- [ ] should_return_401_for_protected_endpoint_when_jwt_missing_or_expired
- [ ] should_return_200_on_settings_upsert_when_preferences_valid
- [ ] should_return_400_on_settings_upsert_when_city_missing_or_genres_exceed_limit

### Layer 4 — `arch/` (ArchUnit)
- [ ] should_enforce_identity_module_structure_and_layer_boundaries
- [ ] should_prevent_identity_from_direct_external_http_calls
- [ ] should_enforce_shared_global_exception_handler_only

---

## 11. Finalized Decisions

All decisions are closed. Values are enforced in Sections 3, 7, 8, and 9.

| Decision | Value | Location |
|---|---|---|
| Password reset token TTL | 15 minutes | Section 8 — Validation Rules |
| Logout scope | Revoke provided refresh token only | Section 8 — Security Rules |
| JWT claim set | `sub` (user BIGSERIAL id as String), `role`, `iat`, `exp`, `jti`, `typ=access` — access tokens only | Section 8 — JWT Claim Contract |
| Refresh token type | Opaque random string (not JWT); stored as hash in DB; validated by hash + expiry + revocation | Section 7, 8 |
| Role in registration API | Removed; server default = `USER` | Section 3, 7 |
| Duplicate email check order | First validation before all others | Section 8 — Validation Rules |
| Preferred genres storage | `user_genre_preferences` junction table (FK to `preference_options`) | Section 6 — DB Schema |
| Preferred city storage | `preferred_city_option_id FK → preference_options(id)` | Section 6 — DB Schema |
| `preference_options.id` type | `BIGSERIAL` (consistent with all schema PKs) | Section 6 — DB Schema |
| `organization_id` on users | Omitted — booking users have no org context in this flow | Section 6 — DB Schema |
