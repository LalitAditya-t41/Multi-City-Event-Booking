# SPEC_AUTH.MD — JWT Authentication Infrastructure (shared/security/)

## 1. Overview

This spec covers the **shared JWT security infrastructure** that all modules depend on for authentication. It defines the three components that live in `shared/security/`: `JwtTokenProvider`, `JwtAuthenticationFilter`, and the updated `SecurityConfig`. It does **not** cover identity module endpoints — those are in `SPEC_3.MD`.

**Consumers:** Every module that has JWT-protected endpoints (identity, booking-inventory, payments-ticketing, etc.)  
**Tech stack:** Spring Boot 3.5.11, Java 21, Spring Security 6, JJWT (`io.jsonwebtoken:jjwt-api`), RS256 (RSA-2048)  
**Scope:** v1.0 — Shared JWT infrastructure layer

---

## 2. Goals & Non-Goals

### Goals
- Replace the existing `httpBasic()` in `SecurityConfig` with a stateless JWT filter chain.
- Provide `JwtTokenProvider` for signing and validating access JWTs using RS256.
- Provide `JwtAuthenticationFilter` (`OncePerRequestFilter`) that validates incoming JWTs and populates `SecurityContextHolder`.
- Provide `BCryptPasswordEncoder` and `AuthenticationManager` beans in `SecurityConfig`.
- Define the RSA key pair loading strategy (PEM files on classpath).
- Document the DI wiring for `UserDetailsServiceImpl` (lives in identity, wired at runtime).

### Non-Goals
- Token issuance logic (login endpoint) — lives in `identity/service/AuthService.java`.
- Refresh token handling — lives in identity module.
- Role-based access control rules beyond `authenticated()` — out of scope for v1.0.
- OAuth2 / OIDC resource server setup.
- Key rotation or key management service integration.

---

## 3. Architectural Position

```
app/ (entry point)
  └── shared/security/         ← THIS SPEC — JWT infra only
        ├── JwtTokenProvider   ← sign + validate JWTs using RSA private/public key
        ├── JwtAuthenticationFilter ← reads Authorization header, validates token, sets context
        └── SecurityConfig     ← filter chain, public endpoints, PasswordEncoder, AuthManager
              ↑
              DI wires UserDetailsServiceImpl at runtime (bean from identity module)
              ↑
        identity/service/UserDetailsServiceImpl  ← implements UserDetailsService, loads by email
              ↑
        identity/repository/UserRepository       ← DB lookup
```

**Key rule:** `shared/` has no dependency on any module. `UserDetailsServiceImpl` is a bean provided by the `identity` module and injected into Spring Security's `AuthenticationProvider` through standard Spring DI. `shared/security/` never imports any identity class directly.

---

## 4. File Structure

### New / Modified Files

```
shared/src/main/java/com/eventplatform/shared/security/
├── JwtTokenProvider.java          ← NEW — RS256 sign, validate, extract claims
├── JwtAuthenticationFilter.java   ← NEW — OncePerRequestFilter, JWT → SecurityContext
├── SecurityConfig.java            ← MODIFY — replace httpBasic(); add JWT filter + beans
└── Roles.java                     ← EXISTING — no changes needed

app/src/main/resources/
└── certs/
    ├── private.pem                ← NEW — RSA-2048 private key (PKCS#8, PEM, NOT committed to git)
    └── public.pem                 ← NEW — RSA-2048 public key (PEM)
```

### Identity File (out of scope for this spec, listed for wiring reference)
```
identity/src/main/java/com/eventplatform/identity/service/
└── UserDetailsServiceImpl.java    ← implements UserDetailsService; loads User by email from UserRepository
```

### Maven Dependency Changes
Add to `shared/pom.xml`:
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

---

## 5. RSA Key Pair

### Algorithm
- **RS256** (RSA + SHA-256): private key signs, public key verifies
- Key size: **RSA-2048** minimum
- Format: PEM (PKCS#8 for private, X.509/SPKI for public)

### Key Generation (one-time, run locally)
```bash
# Generate RSA-2048 private key (PKCS#8)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem

# Extract public key
openssl rsa -pubout -in private.pem -out public.pem
```

Place both files under `app/src/main/resources/certs/`. Add `certs/private.pem` to `.gitignore` — never commit the private key.

### Configuration Properties
In `app/src/main/resources/application.properties`:
```properties
jwt.private-key-path=classpath:certs/private.pem
jwt.public-key-path=classpath:certs/public.pem
```

In test environments (`application-test.properties`), generate a fresh key pair per test run or use a fixed test key pair committed under `src/test/resources/certs/`.

---

## 6. JwtTokenProvider

**Location:** `shared/src/main/java/com/eventplatform/shared/security/JwtTokenProvider.java`  
**Type:** `@Component`

### Responsibilities
1. Load RSA private key and public key from configured PEM paths on startup (`@PostConstruct`).
2. `generateToken(Long userId, String role)` → signed JWT string (RS256).
3. `validateToken(String token)` → `boolean` — returns `false` (never throws) on any failure.
4. `extractUserId(String token)` → `Long` — extracts `sub` claim.
5. `extractRole(String token)` → `String` — extracts `role` claim.

### JWT Claim Contract (from SPEC_3.MD Section 8)

| Claim | Value | Notes |
|-------|-------|-------|
| `sub` | `userId` as String | BIGSERIAL Long, consistent with all schema PKs |
| `role` | `"USER"` | String, not `ROLE_USER` — no Spring prefix |
| `iat` | issued-at epoch seconds | set by JJWT automatically |
| `exp` | iat + 3600 (1 hour) | access token TTL |
| `jti` | `UUID.randomUUID().toString()` | unique per token for future revocation |
| `typ` | `"access"` | differentiates from future token types |

### Design Notes
- `@Value("${jwt.private-key-path}")` and `@Value("${jwt.public-key-path}")` inject paths.
- `@PostConstruct init()` loads PEM bytes from `ResourceLoader`, parses to `PrivateKey` and `PublicKey` using `java.security.KeyFactory`.
- Use `Jwts.builder()` with `.signWith(privateKey, Jwts.SIG.RS256)` (JJWT 0.12.x API).
- `validateToken` wraps `Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token)` in a try/catch; returns `false` on `JwtException` or `IllegalArgumentException`.
- No static state — all fields are instance fields injected at startup.

### PEM Parsing Utility (inside JwtTokenProvider)
```
Private key: strip "-----BEGIN/END PRIVATE KEY-----", Base64 decode, KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes))
Public key:  strip "-----BEGIN/END PUBLIC KEY-----", Base64 decode, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes))
```

---

## 7. JwtAuthenticationFilter

**Location:** `shared/src/main/java/com/eventplatform/shared/security/JwtAuthenticationFilter.java`  
**Type:** `@Component`, extends `OncePerRequestFilter`

### Responsibilities
1. Extract JWT from `Authorization: Bearer <token>` header.
2. If token absent or malformed header format → call `filterChain.doFilter()` and return (no exception, Spring Security's entry point will return 401 for protected routes).
3. Call `JwtTokenProvider.validateToken(token)`.
4. If valid → extract `userId` and `role`, build `UsernamePasswordAuthenticationToken` with `role` authority, set into `SecurityContextHolder`.
5. If invalid → clear `SecurityContextHolder` and continue filter chain (Spring Security handles the 401).

### Filter Logic (pseudocode)
```
doFilterInternal(request, response, chain):
  header = request.getHeader("Authorization")
  if header == null or !header.startsWith("Bearer "):
    chain.doFilter(request, response)
    return

  token = header.substring(7)
  if jwtTokenProvider.validateToken(token):
    userId = jwtTokenProvider.extractUserId(token)
    role   = jwtTokenProvider.extractRole(token)
    auth   = new UsernamePasswordAuthenticationToken(
               userId,                          // principal = Long userId
               null,                            // credentials = null (already verified)
               List.of(new SimpleGrantedAuthority("ROLE_" + role))
             )
    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request))
    SecurityContextHolder.getContext().setAuthentication(auth)
  else:
    SecurityContextHolder.clearContext()

  chain.doFilter(request, response)
```

### Authority Naming
JJWT stores `role` as `"USER"` in the claim. Spring Security expects authorities prefixed with `ROLE_`. The filter applies the prefix: `"ROLE_" + role`. This means `@PreAuthorize("hasRole('USER')")` in controllers will work without any prefix on the claim value.

---

## 8. SecurityConfig (Updated)

**Location:** `shared/src/main/java/com/eventplatform/shared/security/SecurityConfig.java`  
**Current state:** Uses `.httpBasic()` — must be replaced entirely.

### Required Beans
| Bean | Class | Why |
|------|-------|-----|
| `PasswordEncoder` | `BCryptPasswordEncoder` | Used by `AuthService` for hashing and matching |
| `AuthenticationManager` | via `AuthenticationConfiguration` | Used by `AuthService` for credential validation during login |
| `SecurityFilterChain` | — | Full JWT chain replacing httpBasic |

### Public Endpoints (no JWT required)
All existing public endpoints from FR1/FR2 **plus** the FR3 auth endpoints:

```
/api/v1/catalog/**                            ← existing (FR1)
/api/v1/admin/orgs/*/eventbrite/callback      ← existing (FR1)
/admin/v1/webhooks/eventbrite                 ← existing (FR1)
/api/v1/auth/register                         ← FR3
/api/v1/auth/login                            ← FR3
/api/v1/auth/token/refresh                    ← FR3
/api/v1/auth/password/reset                   ← FR3
/api/v1/auth/password/reset/confirm           ← FR3
```

All other requests require a valid JWT in the `Authorization: Bearer <token>` header.

### Filter Chain Configuration
```
http
  .csrf(csrf -> csrf.disable())
  .sessionManagement(session -> session
      .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
  .authorizeHttpRequests(auth -> auth
      .requestMatchers(<public endpoints above>).permitAll()
      .anyRequest().authenticated()
  )
  .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
```

**No `.httpBasic()`. No `.formLogin()`. No `.sessionManagement()` with HTTP sessions.**

### DI Wiring Note — UserDetailsService
`UserDetailsServiceImpl` is a `@Service` bean defined in the `identity` module. It implements Spring Security's `UserDetailsService` interface. Spring Boot's auto-configuration for `DaoAuthenticationProvider` picks up any `UserDetailsService` bean on the classpath automatically — no explicit wiring needed in `SecurityConfig`. Since this is a modular monolith with a single `app/` entry point, all beans from all modules are loaded into the same `ApplicationContext`. The `PasswordEncoder` bean in `SecurityConfig` is also auto-detected by the same `DaoAuthenticationProvider`.

**`SecurityConfig` never imports any class from `identity/`.** The wiring is purely through interface contracts (`UserDetailsService`, `PasswordEncoder`).

---

## 9. Error Handling

The filter never throws exceptions or writes the response directly. Spring Security's default `AuthenticationEntryPoint` handles the 401 response when an unauthenticated request hits a protected endpoint.

This means:
- Missing JWT → Spring Security returns `401 Unauthorized` via `Http403ForbiddenEntryPoint` / default entry point.
- Invalid/expired JWT → filter clears context → Spring Security returns `401`.
- Valid JWT, insufficient role → Spring Security returns `403 Forbidden`.

The `GlobalExceptionHandler` in `shared/common/exception/` handles `@PreAuthorize` failures (`AccessDeniedException`) and maps them to the standard error shape.

> All auth-specific exception scenarios (invalid credentials, revoked refresh token, etc.) are handled in `identity/` — see SPEC_3.MD Section 9.

---

## 10. Acceptance Criteria (Tests)

### Layer 1 — `domain/` (Pure Unit Tests for JwtTokenProvider)
- [ ] should_generate_valid_jwt_with_correct_sub_role_jti_typ_claims
- [ ] should_return_false_on_validateToken_when_token_expired
- [ ] should_return_false_on_validateToken_when_token_tampered
- [ ] should_return_false_on_validateToken_when_token_blank_or_null
- [ ] should_extract_correct_userId_from_valid_token
- [ ] should_extract_correct_role_from_valid_token
- [ ] should_sign_with_private_key_and_verify_with_public_key_only

### Layer 2 — Filter Unit Tests (Mockito)
- [ ] should_set_security_context_when_bearer_token_valid
- [ ] should_not_set_security_context_when_token_invalid
- [ ] should_not_set_security_context_when_authorization_header_missing
- [ ] should_not_set_security_context_when_header_is_not_bearer_format
- [ ] should_apply_ROLE_prefix_to_role_claim_in_authority

### Layer 3 — `api/` (@WebMvcTest / @SpringBootTest slice)
- [ ] should_return_401_when_jwt_missing_on_protected_endpoint
- [ ] should_return_401_when_jwt_expired_on_protected_endpoint
- [ ] should_return_200_when_valid_jwt_on_protected_endpoint
- [ ] should_return_200_without_auth_on_all_public_endpoints

### Layer 4 — `arch/` (ArchUnit)
- [ ] should_enforce_no_identity_class_imported_by_shared_security
- [ ] should_enforce_jwt_filter_registered_before_username_password_filter

---

## 11. Finalized Decisions

| Decision | Value |
|----------|-------|
| JWT signing algorithm | RS256 (RSA-2048) |
| JWT library | `io.jsonwebtoken:jjwt-api` v0.12.6 |
| Key format | PEM (PKCS#8 private, X.509 public) |
| Key loading | `@PostConstruct` from `ResourceLoader` at startup |
| Key location | `app/src/main/resources/certs/` for runtime; `src/test/resources/certs/` for tests |
| Private key in git | Never — add `certs/private.pem` to `.gitignore` |
| SecurityConfig auth type | JWT filter only — no httpBasic, no formLogin, no sessions |
| Session policy | `STATELESS` |
| `UserDetailsService` wiring | Identity module's `UserDetailsServiceImpl` bean, auto-detected via Spring DI interface contract — no shared import |
| Authority format | `ROLE_USER`, `ROLE_ADMIN` (filter adds `ROLE_` prefix to the raw role claim from JWT) |
| Filter position | `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)` |
| Token TTL (access) | 1 hour (3600 seconds) — defined in `JwtTokenProvider` as a constant |
| 401 response | Delegated to Spring Security's default `AuthenticationEntryPoint` — filter does not write the response |
