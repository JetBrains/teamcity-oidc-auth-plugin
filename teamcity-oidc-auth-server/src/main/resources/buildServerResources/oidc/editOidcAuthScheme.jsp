<%@ include file="/include-internal.jsp" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<tr>
  <td colspan="2">
    <div class="attentionComment">
      OIDC settings (including the client secret) are managed on the dedicated
      <a href="<c:url value='/admin/admin.html?item=oidcSettings'/>">OIDC Auth settings page</a>.
      Enable this module first, then configure it there.
    </div>
  </td>
</tr>
