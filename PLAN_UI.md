# PLAN_UI.md — Login Page UI

This document describes the UI changes required to surface OIDC login on the TeamCity login page.
The mechanism is identical to `teamcity-plugin-saml`: a `SimplePageExtension` injects a fragment
at `PlaceId.LOGIN_PAGE`, rendered from a JSP that produces the icon/button.

---

## 1. Config additions — `OidcPluginSettings`

Two new optional fields:

| Field | Type | Default | Purpose |
|---|---|---|---|
| `loginButtonLabel` | `String` | `"Log in with OIDC"` | `title` attribute and `alt` text on the icon |
| `loginButtonIconUrl` | `String` | `null` | URL (absolute or root-relative) of the icon image. When `null`, a built-in generic OIDC SVG is used. |

The icon URL can point to an image hosted anywhere — an external CDN, a path inside TC's own web root,
or a `data:` URI with a base64-encoded image.

---

## 2. New class — `OidcLoginPageExtension`

**Package:** `org.jetbrains.teamcity.oidc.web`
**Extends:** `jetbrains.buildServer.web.openapi.SimplePageExtension`

```
OidcLoginPageExtension(
    @NotNull PagePlaces pagePlaces,
    @NotNull PluginDescriptor pluginDescriptor,
    @NotNull OidcPluginSettingsStorage settingsStorage,
    @NotNull OidcAuthenticationScheme scheme
)
```

Constructor calls:
```java
super(pagePlaces,
      PlaceId.LOGIN_PAGE,
      "OidcLogin",
      pluginDescriptor.getPluginResourcesPath("oidc/loginButton.jsp"));
register();
```

**`isAvailable(HttpServletRequest)`** — returns `scheme.isConfigured()`, so the fragment is
invisible when `clientId` or `issuerUrl` is blank.

**`fillModel(Map, HttpServletRequest)`** — puts into the model:
- `"loginUrl"` → `OidcConstants.LOGIN_PATH` (`/app/oidc/login`)
- `"oidcSettings"` → the current `OidcPluginSettings` (gives JSP access to label and icon URL)

---

## 3. New method — `OidcAuthenticationScheme.isConfigured()`

```java
public boolean isConfigured() {
    OidcPluginSettings s = settingsStorage.getSettings();
    return !isBlank(s.getIssuerUrl()) && !isBlank(s.getClientId());
}
```

Used by `OidcLoginPageExtension.isAvailable()`.

---

## 4. New JSP — `buildServerResources/oidc/loginButton.jsp`

```
src/main/resources/buildServerResources/oidc/loginButton.jsp
```

Renders a single `<a>` that starts the auth flow. If `oidcSettings.loginButtonIconUrl` is set it
renders an `<img>` pointing to that URL; otherwise it inlines the default SVG.

```jsp
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="/include-internal.jsp" %>
<jsp:useBean id="loginUrl"    scope="request" type="java.lang.String"/>
<jsp:useBean id="oidcSettings" scope="request"
             type="org.jetbrains.teamcity.oidc.config.OidcPluginSettings"/>

<a href="${loginUrl}" title="${oidcSettings.loginButtonLabel}">
  <c:choose>
    <c:when test="${not empty oidcSettings.loginButtonIconUrl}">
      <img src="${oidcSettings.loginButtonIconUrl}"
           alt="${oidcSettings.loginButtonLabel}"
           style="width:50px;height:50px;">
    </c:when>
    <c:otherwise>
      <%-- Inline default SVG (generic lock/OpenID icon) --%>
      <svg .../>
    </c:otherwise>
  </c:choose>
</a>
```

The default SVG is inlined in the JSP (no external dependency). A simple padlock or
generic "OpenID" logo is appropriate.

---

## 5. Post-authentication redirect

TC passes `redirectTo=<url>` on the query string when it redirects the user to `/login.html`
after blocking an unauthenticated request.  The login button points to `/app/oidc/login`.
`OidcLoginController` must forward that parameter through the flow:

1. `OidcLoginController.doHandle()` reads `request.getParameter("redirectTo")` and stores it
   in the session under key `OidcConstants.SESSION_REDIRECT_URL` (`"oidc.redirectUrl"`).
   The login button href becomes `<a href="/app/oidc/login?redirectTo=...">` — the JSP gets
   the raw request URL via `${pageContext.request.queryString}` or TC's `redirectTo` attribute
   already exposed in the login-page model; alternatively the JSP just links to `/app/oidc/login`
   and the controller picks it up from the `Referer`-based redirect that TC's login machinery
   already puts in the session.

   **Simplest approach:** the JSP appends TC's `redirectTo` request attribute to the href:

   ```jsp
   <c:url var="loginUrl" value="/app/oidc/login">
     <c:if test="${not empty param.redirectTo}">
       <c:param name="redirectTo" value="${param.redirectTo}"/>
     </c:if>
   </c:url>
   <a href="${loginUrl}" ...>
   ```

2. After successful token validation and user resolution,
   `OidcAuthenticationScheme.processAuthenticationRequest()` reads `SESSION_REDIRECT_URL`
   from the session. If present and safe (same-origin check), it is passed to
   `HttpAuthenticationResult.authenticated(principal, true).withRedirect(redirectUrl)`;
   otherwise `withRedirect("/")`.

---

## 6. `OidcPluginConfiguration` changes

Add a new `@Bean` method:

```java
@Bean
OidcLoginPageExtension oidcLoginPageExtension(
        PagePlaces pagePlaces,
        PluginDescriptor pluginDescriptor,
        OidcPluginSettingsStorage storage,
        OidcAuthenticationScheme scheme) {
    return new OidcLoginPageExtension(pagePlaces, pluginDescriptor, storage, scheme);
}
```

`PluginDescriptor` and `PagePlaces` are available from the TC parent application context and
are injected by Spring automatically — no extra XML wiring required.

---

## 7. Summary of new/changed artifacts

| Artifact | Change |
|---|---|
| `config/OidcPluginSettings.java` | Add `loginButtonLabel`, `loginButtonIconUrl` fields with defaults |
| `auth/OidcAuthenticationScheme.java` | Add `isConfigured()`, read `SESSION_REDIRECT_URL` for post-auth redirect |
| `web/OidcLoginController.java` | Store `redirectTo` param in session |
| `web/OidcLoginPageExtension.java` | **New** — `SimplePageExtension` at `PlaceId.LOGIN_PAGE` |
| `buildServerResources/oidc/loginButton.jsp` | **New** — icon anchor rendered on login page |
| `OidcPluginConfiguration.java` | Register `OidcLoginPageExtension` bean |

No changes to `OidcConstants`, `OidcClient`, `OidcIdTokenValidator`, or any auth-flow logic.
