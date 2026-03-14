<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="/include-internal.jsp" %>
<jsp:useBean id="oidcLoginPath" scope="request" type="java.lang.String"/>
<jsp:useBean id="oidcSettings"  scope="request" type="org.jetbrains.teamcity.oidc.config.OidcPluginSettings"/>
<c:url var="loginUrl" value="${oidcLoginPath}">
  <c:if test="${not empty param.redirectTo}">
    <c:param name="redirectTo" value="${param.redirectTo}"/>
  </c:if>
</c:url>
<a href="${loginUrl}" title="${oidcSettings.loginButtonLabel}" class="oidc-login-btn">
  <c:choose>
    <c:when test="${not empty oidcSettings.loginButtonIconUrl}">
      <img src="${oidcSettings.loginButtonIconUrl}"
           alt="${oidcSettings.loginButtonLabel}"
           style="width:50px;height:50px;">
    </c:when>
    <c:otherwise><%--
      Default icon: generic OpenID / key SVG (inline, no external dependency).
      Derived from the OpenID Foundation mark, simplified.
    --%>
      <svg xmlns="http://www.w3.org/2000/svg" width="50" height="50" viewBox="0 0 50 50"
           aria-label="${oidcSettings.loginButtonLabel}" role="img">
        <title>${oidcSettings.loginButtonLabel}</title>
        <!-- Key body -->
        <circle cx="19" cy="20" r="10" fill="none" stroke="#4A90D9" stroke-width="3.5"/>
        <circle cx="19" cy="20" r="4"  fill="#4A90D9"/>
        <!-- Key shaft -->
        <line x1="26" y1="26" x2="43" y2="43" stroke="#4A90D9" stroke-width="3.5" stroke-linecap="round"/>
        <!-- Key teeth -->
        <line x1="36" y1="36" x2="36" y2="41" stroke="#4A90D9" stroke-width="2.5" stroke-linecap="round"/>
        <line x1="40" y1="40" x2="40" y2="45" stroke="#4A90D9" stroke-width="2.5" stroke-linecap="round"/>
      </svg>
    </c:otherwise>
  </c:choose>
</a>
