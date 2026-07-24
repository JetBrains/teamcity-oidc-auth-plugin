<%@ include file="/include.jsp" %>
<%@ taglib prefix="forms" tagdir="/WEB-INF/tags/forms" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="l" tagdir="/WEB-INF/tags/layout" %>
<jsp:useBean id="settings" scope="request" type="org.jetbrains.teamcity.oidc.config.OidcPluginSettings"/>
<jsp:useBean id="settingsActionUrl" scope="request" type="java.lang.String"/>
<jsp:useBean id="discoveryInfoUrl" scope="request" type="java.lang.String"/>
<jsp:useBean id="callbackUrl" scope="request" type="java.lang.String"/>

<div class="section noMargin">
  <bs:refreshable pageUrl="${pageUrl}" containerId="oidcSettingsForm">

  <bs:messages key="oidcSettingsSaved"/>

  <form action="<c:url value='${settingsActionUrl}'/>" method="post" onsubmit="return OidcSettings.SettingsForm.submitSettings();" id="editParams" autocomplete="off">
    <table class="runnerFormTable">

      <tr>
        <th><label for="issuerUrl">Issuer URL: <l:star/></label></th>
        <td>
          <forms:textField name="issuerUrl" value="${settings.issuerUrl}" className="longField"/>
          <a class="btn btn_mini" href="#" onclick="OidcSettings.DiscoveryInfoDialog.show($j('#issuerUrl')[0].value); return false;">Fetch Discovery Document</a>
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
        <th><label for="clientSecret">Client secret: <l:star/></label></th>
        <td>
          <forms:passwordField name="clientSecret" encryptedPassword="${encryptedClientSecret}" publicKey="${publicKey}" className="longField"/>
          <span class="error" id="error_clientSecret"></span>
        </td>
      </tr>
      <tr>
        <th>Callback URL:</th>
        <td>
          <span id="callbackUrl"><c:out value="${callbackUrl}"/></span><bs:copy2ClipboardLink dataId="callbackUrl" stripTags="true"/>
          <span class="smallNote">This URL should be added to the list of the allowed callback URLs of the OAuth application</span>
        </td>
      </tr>

      <tr>
        <th><label for="scopes">Scopes:</label></th>
        <td>
          <forms:textField name="scopes" value="${settings.scopesJoined}" className="longField"/>
          <span class="smallNote">Comma-separated list of OAuth 2.0 scopes. Example: <code>openid, email, profile</code>.</span>
        </td>
      </tr>

      <tr>
        <th><label for="discoveryEnabled">Use discovery:</label></th>
        <td>
          <forms:checkbox name="discoveryEnabled" checked="${settings.discoveryEnabled}"/>
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
          <forms:checkbox name="createUsersAutomatically" checked="${settings.createUsersAutomatically}"/>
          <label for="createUsersAutomatically">Automatically create a TeamCity user on first OIDC login</label>
        </td>
      </tr>

      <tr>
        <th><label for="allowedEmailDomains">Allowed email domains:</label></th>
        <td>
          <forms:textField name="allowedEmailDomains" value="${settings.allowedEmailDomainsJoined}" className="longField"/>
          <span class="smallNote">Comma-separated list of allowed email domains. Leave empty to allow all domains.</span>
        </td>
      </tr>

      <tr>
        <th><label for="assignGroups">Group sync:</label></th>
        <td>
          <forms:checkbox name="assignGroups" checked="${settings.assignGroups}"/>
          <label for="assignGroups">Sync group membership from OIDC claims</label>
        </td>
      </tr>

      <tr>
        <th><label for="removeUnassignedGroups">Remove from absent groups:</label></th>
        <td>
          <forms:checkbox name="removeUnassignedGroups" checked="${settings.removeUnassignedGroups}"/>
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
          <forms:select name="usernameClaim_mappingType" onchange="OidcSettings.toggleClaimField('usernameClaim', this.value)">
            <forms:option value="SUB" selected="${settings.usernameClaim.mappingType == 'SUB'}">Subject (sub)</forms:option>
            <forms:option value="CLAIM" selected="${settings.usernameClaim.mappingType == 'CLAIM'}">Custom claim</forms:option>
          </forms:select>
          <span id="usernameClaim_claimField" style="${settings.usernameClaim.mappingType == 'CLAIM' ? '' : 'display:none'}">
            <forms:textField name="usernameClaim_claimName" value="${settings.usernameClaim.claimName}"/>
          </span>
        </td>
      </tr>

      <tr>
        <th><label for="emailClaim_mappingType">Email claim:</label></th>
        <td>
          <forms:select name="emailClaim_mappingType" onchange="OidcSettings.toggleClaimField('emailClaim', this.value)">
            <forms:option value="NONE"  selected="${settings.emailClaim.mappingType == 'NONE'}">None</forms:option>
            <forms:option value="SUB"   selected="${settings.emailClaim.mappingType == 'SUB'}">Subject (sub)</forms:option>
            <forms:option value="CLAIM" selected="${settings.emailClaim.mappingType == 'CLAIM'}">Custom claim</forms:option>
          </forms:select>
          <span id="emailClaim_claimField" style="${settings.emailClaim.mappingType == 'CLAIM' ? '' : 'display:none'}">
            <forms:textField name="emailClaim_claimName" value="${settings.emailClaim.claimName}"/>
          </span>
        </td>
      </tr>

      <tr>
        <th><label for="displayNameClaim_mappingType">Display name claim:</label></th>
        <td>
          <forms:select name="displayNameClaim_mappingType" onchange="OidcSettings.toggleClaimField('displayNameClaim', this.value)">
            <forms:option value="NONE"  selected="${settings.displayNameClaim.mappingType == 'NONE'}">None</forms:option>
            <forms:option value="SUB"   selected="${settings.displayNameClaim.mappingType == 'SUB'}">Subject (sub)</forms:option>
            <forms:option value="CLAIM" selected="${settings.displayNameClaim.mappingType == 'CLAIM'}">Custom claim</forms:option>
          </forms:select>
          <span id="displayNameClaim_claimField" style="${settings.displayNameClaim.mappingType == 'CLAIM' ? '' : 'display:none'}">
            <forms:textField name="displayNameClaim_claimName" value="${settings.displayNameClaim.claimName}"/>
          </span>
        </td>
      </tr>

      <tr>
        <th><label for="httpTimeoutSeconds">HTTP timeout:</label></th>
        <td>
          <forms:textField name="httpTimeoutSeconds" value="${settings.httpTimeoutSeconds}" style="width:5em;"/> seconds
        </td>
      </tr>

      <tr>
        <th><label for="tokenClockSkewSeconds">Clock skew:</label></th>
        <td>
          <forms:textField name="tokenClockSkewSeconds" value="${settings.tokenClockSkewSeconds}" style="width:5em;"/> seconds
          <span class="smallNote">Allowed clock skew when validating token expiry.</span>
        </td>
      </tr>

      <tr>
        <th><label for="loginButtonLabel">Login button label: <l:star/></label></th>
        <td>
          <forms:textField name="loginButtonLabel" value="${settings.loginButtonLabel}" className="longField"/>
          <span class="error" id="error_loginLabel"></span>
        </td>
      </tr>

      <tr>
        <th><label for="loginButtonIconUrl">Login button icon URL:</label></th>
        <td>
          <forms:textField name="loginButtonIconUrl" value="${settings.loginButtonIconUrl}" className="longField"/>
          <span class="smallNote">Absolute URL, root-relative path, or data URI.</span>
        </td>
      </tr>

    </table>

    <div class="saveButtonsBlock">
      <forms:submit label="Save"/>
      <forms:saving id="saving"/>
      <input type="hidden" id="publicKey" name="publicKey" value="<c:out value='${publicKey}'/>"/>
      <span class="error" id="error_general"></span>
    </div>
  </form>

  </bs:refreshable>
</div>

<bs:modalDialog formId="discoveryInfo"
                title="Discovery Result"
                action=""
                closeCommand="OidcSettings.DiscoveryInfoDialog.close();"
                saveCommand="">
  <forms:saving id="discoveryProgress" style="float: none"/>
  <div id="discoveryInfoContent"></div>
</bs:modalDialog>

<script type="text/javascript">
  OidcSettings = {
    toggleClaimField: function(prefix, mappingType) {
      var field = document.getElementById(prefix + '_claimField');
      if (field) field.style.display = (mappingType === 'CLAIM') ? '' : 'none';
    }
  };

  OidcSettings.DiscoveryInfoDialog = OO.extend(BS.AbstractModalDialog, {
    show: function(issuerUrl) {
      $j('#discoveryInfoContent').html('');
      this.showCentered();
      BS.Util.show('discoveryProgress');
      BS.ajaxRequest('<c:url value="${discoveryInfoUrl}"/>', {
        parameters: 'issuerUrl=' + encodeURIComponent(issuerUrl),
        onComplete: function(response) {
          BS.Util.hide('discoveryProgress');
          $j('#discoveryInfoContent').html(response.responseText);
        }
      });
    },

    getContainer: function () {
      return $j('#discoveryInfoDialog')[0];
    },

    formElement: function () {
      return $j('#discoveryInfo')[0];
    }
  });

  OidcSettings.SettingsForm = OO.extend(BS.AbstractPasswordForm, {
    formElement: function() {
      return $('editParams');
    },

    submitSettings: function() {
      BS.PasswordFormSaver.save(this, this.formElement().action, OO.extend(BS.ErrorsAwareListener, {
        onCompleteSave: function(form, responseXML, err) {
          BS.ErrorsAwareListener.onCompleteSave(form, responseXML, err);
          if (!err) {
            $j('#oidcSettingsForm')[0].refresh();
          }
        },

        onGeneralError: function(elem) {
          $j('#error_general').text(elem.firstChild.nodeValue);
        },

        onClientIdError: function(elem) {
          $j('#error_clientId').text(elem.firstChild.nodeValue);
        },

        onIssuerUrlError: function(elem) {
          $j('#error_issuerUrl').text(elem.firstChild.nodeValue);
        },

        onClientSecretError: function(elem) {
          $j('#error_clientSecret').text(elem.firstChild.nodeValue);
        },

        onLoginLabelError: function(elem) {
          $j('#error_loginLabel').text(elem.firstChild.nodeValue);
        }
      }));
      return false;
    }
  });

  $j(function() {
    var discoveryCheckbox = document.getElementById('discoveryEnabled');
    var manualEndpoints = document.getElementById('manualEndpoints');
    if (discoveryCheckbox) {
      discoveryCheckbox.addEventListener('change', function() {
        manualEndpoints.style.display = this.checked ? 'none' : '';
      });
    }
  });
</script>
