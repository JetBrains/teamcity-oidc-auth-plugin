<%@ include file="/include.jsp" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="oidcSettingsConfigured" scope="request" type="java.lang.Boolean"/>
<jsp:useBean id="issuerUrl" scope="request" type="java.lang.String"/>

<c:choose>
  <c:when test="${oidcSettingsConfigured}">
    <p>
      Issuer URL: <c:out value="${issuerUrl}"/>
    </p>
    <p>
      <a href="<c:url value='/admin/admin.html?item=oidcSettings'/>">Edit settings</a>
    </p>
  </c:when>
  <c:otherwise>
    <p class="attentionComment">
      The <a href="<c:url value='/admin/admin.html?item=oidcSettings'/>">OIDC Auth settings</a> are not fully configured.
    </p>
  </c:otherwise>
</c:choose>
