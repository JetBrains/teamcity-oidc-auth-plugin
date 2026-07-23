<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="/include-internal.jsp" %>
<jsp:useBean id="oidcLoginPath" scope="request" type="java.lang.String"/>
<jsp:useBean id="oidcSettings"  scope="request" type="org.jetbrains.teamcity.oidc.config.OidcPluginSettings"/>
<c:url var="loginUrl" value="${oidcLoginPath}">
  <c:if test="${not empty param.redirectTo}">
    <c:param name="redirectTo" value="${param.redirectTo}"/>
  </c:if>
</c:url>
<div>
  <a href="${loginUrl}" title="${oidcSettings.loginButtonLabel}" class="oidc-login-btn">
    <c:choose>
      <c:when test="${not empty oidcSettings.loginButtonIconUrl}">
        <img src="${oidcSettings.loginButtonIconUrl}" alt="${oidcSettings.loginButtonLabel}" style="width:50px;height:50px;">
      </c:when>
      <c:when test="${not empty oidcSettings.loginButtonLabel}">
        <c:out value="${oidcSettings.loginButtonLabel}"/>
      </c:when>
    </c:choose>
  </a>
</div>
