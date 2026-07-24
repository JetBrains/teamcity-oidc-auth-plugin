<%@ include file="/include.jsp" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="discoveryDocument" scope="request" type="org.jetbrains.teamcity.oidc.oidc.OidcDiscoveryDocument"/>
<jsp:useBean id="oidcClientError" scope="request" type="java.lang.String"/>
<jsp:useBean id="issuerUrl" scope="request" type="java.lang.String"/>
<c:set var="docUrl" value="${issuerUrl}/.well-known/openid-configuration"/>

<table class="runnerFormTable">
<c:if test="${not empty issuerUrl}">
<tr>
    <td>
        Document URL: <a href="<c:out value='${docUrl}'/>"><c:out value="${docUrl}"/></a>
    </td>
</tr>
</c:if>
<c:choose>
    <c:when test="${not empty oidcClientError}">
    <tr>
        <td>
            <div class="error" style="margin-left: 0"><c:out value="${oidcClientError}"/></div>
        </td>
    </tr>
    </c:when>
    <c:otherwise>
    <tr>
        <td>Supported scopes:
            <c:forEach var="scope" items="${discoveryDocument.scopesSupported}" varStatus="s">
                <c:out value="${scope}"/><c:if test="${s.last == false}">, </c:if>
            </c:forEach>
        </td>
    </tr>
    <tr>
        <td>Supported claims:
            <c:forEach var="claim" items="${discoveryDocument.claimsSupported}" varStatus="s">
                <c:out value="${claim}"/><c:if test="${s.last == false}">, </c:if>
            </c:forEach>
        </td>
    </tr>
    </c:otherwise>
</c:choose>
</table>


