<%@ include file="/include-internal.jsp" %>
<%@ taglib prefix="forms" tagdir="/WEB-INF/tags/forms" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="l" tagdir="/WEB-INF/tags/layout" %>
<jsp:useBean id="settings" scope="request" type="org.jetbrains.teamcity.oidc.config.OidcPluginSettings"/>
<jsp:useBean id="settingsActionUrl" scope="request" type="java.lang.String"/>

<div class="section noMargin">
  <h2 class="noBorder">OIDC Authentication Settings</h2>

  <form action="${settingsActionUrl}" method="post" onsubmit="return OidcSettings.SettingsForm.submitSettings();" id="editParams" autocomplete="off">
    <table class="runnerFormTable">

      <tr>
        <th><label for="issuerUrl">Issuer URL: <l:star/></label></th>
        <td>
          <forms:textField name="issuerUrl" value="${settings.issuerUrl}" className="longField"/>
          <span class="smallNote">Base URL of the identity provider (e.g. <code>https://sso.example.com/realms/master</code>).
            The discovery document is fetched from <code>{issuerUrl}/.well-known/openid-configuration</code>.</span>
          <span class="error" id="error_issuerUrl"></span>
        </td>
      </tr>

      <tr>
        <th><label for="clientId">Client ID: <l:star/></label></th>
        <td>
          <forms:textField name="clientId" value="${settings.clientId}" className="longField"/>
          <span class="error" id="error_clientId"></span>
        </td>
      </tr>

      <tr>
        <th><label for="clientSecret">Client Secret:</label></th>
        <td>
          <forms:passwordField name="clientSecret" encryptedPassword="${encryptedClientSecret}"/>
        </td>
      </tr>

      <tr>
        <th><label for="callbackBaseUrl">Callback base URL:</label></th>
        <td>
          <forms:textField name="callbackBaseUrl" value="${settings.callbackBaseUrl}" className="longField"/>
          <span class="smallNote">Override the base URL used to build the redirect URI sent to the IdP.
            Leave empty to use the TeamCity root URL.</span>
        </td>
      </tr>

      <tr>
        <th><label for="scopes">Scopes:</label></th>
        <td>
          <forms:textField name="scopes" value="${settings.scopesJoined}" className="longField"/>
          <span class="smallNote">Space-separated list of OAuth 2.0 scopes. Default: <code>openid email profile</code>.</span>
        </td>
      </tr>

      <tr>
        <th><label for="discoveryEnabled">Use discovery:</label></th>
        <td>
          <input type="checkbox" name="discoveryEnabled" id="discoveryEnabled" value="true" ${settings.discoveryEnabled ? 'checked' : ''}/>
          <label for="discoveryEnabled">Automatically resolve endpoints from the discovery document</label>
        </td>
      </tr>

      <tr id="manualEndpoints" style="${settings.discoveryEnabled ? 'display:none' : ''}">
        <th><label>Manual endpoints:</label></th>
        <td>
          <table>
            <tr>
              <td><label for="authorizationEndpoint">Authorization:</label></td>
              <td><forms:textField name="authorizationEndpoint" value="${settings.authorizationEndpoint}" className="longField"/></td>
            </tr>
            <tr>
              <td><label for="tokenEndpoint">Token:</label></td>
              <td><forms:textField name="tokenEndpoint" value="${settings.tokenEndpoint}" className="longField"/></td>
            </tr>
            <tr>
              <td><label for="jwksUri">JWKS URI:</label></td>
              <td><forms:textField name="jwksUri" value="${settings.jwksUri}" className="longField"/></td>
            </tr>
            <tr>
              <td><label for="userInfoEndpoint">UserInfo:</label></td>
              <td><forms:textField name="userInfoEndpoint" value="${settings.userInfoEndpoint}" className="longField"/></td>
            </tr>
            <tr>
              <td><label for="introspectionEndpoint">Introspection:</label></td>
              <td><forms:textField name="introspectionEndpoint" value="${settings.introspectionEndpoint}" className="longField"/></td>
            </tr>
          </table>
        </td>
      </tr>

      <tr>
        <th><label for="createUsersAutomatically">Auto-create users:</label></th>
        <td>
          <input type="checkbox" name="createUsersAutomatically" id="createUsersAutomatically" value="true" ${settings.createUsersAutomatically ? 'checked' : ''}/>
          <label for="createUsersAutomatically">Automatically create a TeamCity user on first OIDC login</label>
        </td>
      </tr>

      <tr>
        <th><label for="allowedEmailDomains">Allowed email domains:</label></th>
        <td>
          <forms:textField name="allowedEmailDomains" value="${settings.allowedEmailDomainsJoined}" className="longField"/>
          <span class="smallNote">Comma-separated list. Leave empty to allow all domains.</span>
        </td>
      </tr>

      <tr>
        <th><label for="assignGroups">Group sync:</label></th>
        <td>
          <input type="checkbox" name="assignGroups" id="assignGroups" value="true" ${settings.assignGroups ? 'checked' : ''}/>
          <label for="assignGroups">Sync group membership from OIDC claims</label>
        </td>
      </tr>

      <tr>
        <th><label for="removeUnassignedGroups">Remove from absent groups:</label></th>
        <td>
          <input type="checkbox" name="removeUnassignedGroups" id="removeUnassignedGroups" value="true" ${settings.removeUnassignedGroups ? 'checked' : ''}/>
          <label for="removeUnassignedGroups">Remove user from TeamCity groups not present in the OIDC claim</label>
        </td>
      </tr>

      <tr>
        <th><label for="groupsClaimName">Groups claim name:</label></th>
        <td>
          <forms:textField name="groupsClaimName" value="${settings.groupsClaimName}"/>
        </td>
      </tr>

      <tr>
        <th><label for="usernameClaim_mappingType">Username claim:</label></th>
        <td>
          <select name="usernameClaim_mappingType" id="usernameClaim_mappingType"
                  onchange="OidcSettings.toggleClaimField('usernameClaim', this.value)">
            <option value="SUB"   ${settings.usernameClaim.mappingType == 'SUB'   ? 'selected' : ''}>Subject (sub)</option>
            <option value="CLAIM" ${settings.usernameClaim.mappingType == 'CLAIM' ? 'selected' : ''}>Custom claim</option>
          </select>
          <span id="usernameClaim_claimField" style="${settings.usernameClaim.mappingType == 'CLAIM' ? '' : 'display:none'}">
            <forms:textField name="usernameClaim_claimName" value="${settings.usernameClaim.claimName}"/>
          </span>
        </td>
      </tr>

      <tr>
        <th><label for="emailClaim_mappingType">Email claim:</label></th>
        <td>
          <select name="emailClaim_mappingType" id="emailClaim_mappingType"
                  onchange="OidcSettings.toggleClaimField('emailClaim', this.value)">
            <option value="NONE"  ${settings.emailClaim.mappingType == 'NONE'  ? 'selected' : ''}>None</option>
            <option value="SUB"   ${settings.emailClaim.mappingType == 'SUB'   ? 'selected' : ''}>Subject (sub)</option>
            <option value="CLAIM" ${settings.emailClaim.mappingType == 'CLAIM' ? 'selected' : ''}>Custom claim</option>
          </select>
          <span id="emailClaim_claimField" style="${settings.emailClaim.mappingType == 'CLAIM' ? '' : 'display:none'}">
            <forms:textField name="emailClaim_claimName" value="${settings.emailClaim.claimName}"/>
          </span>
        </td>
      </tr>

      <tr>
        <th><label for="displayNameClaim_mappingType">Display name claim:</label></th>
        <td>
          <select name="displayNameClaim_mappingType" id="displayNameClaim_mappingType"
                  onchange="OidcSettings.toggleClaimField('displayNameClaim', this.value)">
            <option value="NONE"  ${settings.displayNameClaim.mappingType == 'NONE'  ? 'selected' : ''}>None</option>
            <option value="SUB"   ${settings.displayNameClaim.mappingType == 'SUB'   ? 'selected' : ''}>Subject (sub)</option>
            <option value="CLAIM" ${settings.displayNameClaim.mappingType == 'CLAIM' ? 'selected' : ''}>Custom claim</option>
          </select>
          <span id="displayNameClaim_claimField" style="${settings.displayNameClaim.mappingType == 'CLAIM' ? '' : 'display:none'}">
            <forms:textField name="displayNameClaim_claimName" value="${settings.displayNameClaim.claimName}"/>
          </span>
        </td>
      </tr>

      <tr>
        <th><label for="httpTimeoutSeconds">HTTP timeout (s):</label></th>
        <td>
          <forms:textField name="httpTimeoutSeconds" value="${settings.httpTimeoutSeconds}" style="width:5em;"/>
        </td>
      </tr>

      <tr>
        <th><label for="tokenClockSkewSeconds">Clock skew (s):</label></th>
        <td>
          <forms:textField name="tokenClockSkewSeconds" value="${settings.tokenClockSkewSeconds}" style="width:5em;"/>
          <span class="smallNote">Allowed clock skew when validating token expiry.</span>
        </td>
      </tr>

      <tr>
        <th><label for="loginButtonLabel">Login button label:</label></th>
        <td>
          <forms:textField name="loginButtonLabel" value="${settings.loginButtonLabel}" className="longField"/>
        </td>
      </tr>

      <tr>
        <th><label for="loginButtonIconUrl">Login button icon URL:</label></th>
        <td>
          <forms:textField name="loginButtonIconUrl" value="${settings.loginButtonIconUrl}" className="longField"/>
          <span class="smallNote">Absolute URL, root-relative path, or data URI. Leave empty to use the built-in SVG.</span>
        </td>
      </tr>

    </table>

    <div class="saveButtonsBlock">
      <forms:submit label="Save"/>
      <forms:saving id="saving"/>
      <input type="hidden" id="publicKey" name="publicKey" value="<c:out value='${publicKey}'/>"/>
      <span class="savedInfo">Settings have been saved.</span>
      <span class="error" id="error__general"></span>
    </div>
  </form>
</div>

<script type="text/javascript">
  OidcSettings = {
    toggleClaimField: function(prefix, mappingType) {
      var field = document.getElementById(prefix + '_claimField');
      if (field) field.style.display = (mappingType === 'CLAIM') ? '' : 'none';
    }
  };

  OidcSettings.SettingsForm = OO.extend(BS.AbstractPasswordForm, {
    formElement: function() {
      return $('editParams');
    },

    submitSettings: function() {
      BS.PasswordFormSaver.save(this, this.formElement().action, OO.extend(BS.ErrorsAwareListener, {
        onCompleteSave: function(form, responseXML, err) {
          BS.ErrorsAwareListener.onCompleteSave(form, responseXML, err);
          if (!err) {
            form.enable();
          }
        }
      }));
      return false;
    }
  });

  $j(function() {
    OidcSettings.SettingsForm.setupEventHandlers();

    var discoveryCheckbox = document.getElementById('discoveryEnabled');
    var manualEndpoints = document.getElementById('manualEndpoints');
    if (discoveryCheckbox) {
      discoveryCheckbox.addEventListener('change', function() {
        manualEndpoints.style.display = this.checked ? 'none' : '';
      });
    }
  });
</script>
