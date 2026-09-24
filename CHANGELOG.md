# Changelog

## 0.2.0

Plug-and-play release: the console now works in any servlet-based Spring Boot app, regardless of
the app's security, interceptors, advice, JSON mapper or filters, with no host changes beyond
`greenmail.console.enabled=true`.

### ⚠️ Behavior changes

- **The starter no longer registers a `SecurityFilterChain`.** Previously it added a
  highest-precedence `permitAll` chain for the console path, which also made Spring Boot's default
  security back off for the rest of the app if the app had no chain of its own. The console is now
  served by a servlet filter that runs *before* Spring Security, so no security rule is needed and
  the host's security is left completely untouched.
- **The console is no longer a Spring MVC `@RestController`.** It is not dispatched through the
  host's `DispatcherServlet`, so host `HandlerInterceptor`s, `@ControllerAdvice`/`ResponseBodyAdvice`,
  message converters and customised `ObjectMapper`/`JsonMapper` beans no longer apply to it. URLs,
  status codes, content types and JSON shape are unchanged. (It also no longer appears in the host's
  springdoc/OpenAPI docs, so the `swagger-annotations` dependency was removed.)
- **Loopback-only by default.** Requests from non-loopback addresses get `403` unless
  `greenmail.console.allow-remote=true`.
- **Startup fails under forbidden profiles.** If the console is enabled while `prod` or `production`
  (configurable via `greenmail.console.forbidden-profiles`) is active, the application refuses to start.
- The startup banner is now logged at `WARN` and includes mode, bind address and a DEV/TEST-ONLY notice.

### Added

- `greenmail.console.mode` — `filter` (default; same port and URL as the app) or `standalone`
  (separate JDK `HttpServer`, nothing registered in the host's servlet container).
- `greenmail.console.port` (default `8025`, free-port fallback) and `greenmail.console.bind-address`
  (default `127.0.0.1`) for standalone mode. The standalone server starts and stops with the
  application context.
- `greenmail.console.allow-remote` (default `false`).
- `greenmail.console.forbidden-profiles` (default `prod,production`).
- `Cache-Control: no-store` on console responses.
- `spring-configuration-metadata.json` is generated again (the configuration processor is now
  declared explicitly, as JDK 23+ no longer discovers annotation processors implicitly).

### Dependencies

- `spring-boot-starter-web` and `spring-boot-starter-security` are no longer compile dependencies
  (both are test-only now). The starter needs only `spring-web`, the servlet API and
  `spring-boot-web-server` (all optional; every servlet Boot app has them), plus `jackson-databind`
  for its private JSON mapper.

## 0.1.1

- Initial Maven Central release.
