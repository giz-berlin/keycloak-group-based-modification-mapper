package berlin.giz.keycloak.mappers.groupbasedmodification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.ProtocolMapperUtils;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.IDToken;

public class OIDCMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    static final String PROVIDER_ID = "group-based-prefix-oidc-mapper";

    static final String OTHER_CLAIM_CONFIG = "other";
    static final String MODIFICATION_CONFIG = "modification";
    static final String GROUP_NAME_CONFIG = "group";
    static final String MEMBERSHIP_CONFIG = "membership";
    static final String LOCATION_CONFIG = "location";

    static final String MODIFICATION_LOCATION_PREFIX = "Prefix";
    static final String MODIFICATION_LOCATION_SUFFIX = "Suffix";

    static final List<ProviderConfigProperty> CONFIG_PROPERTIES;

    private static final Logger LOGGER = Logger.getLogger(OIDCMapper
.class);

    static {

        List<ProviderConfigProperty> configProperties = new ArrayList<>();
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, OIDCMapper.class);
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        configProperties.add(new ProviderConfigProperty(
            ProtocolMapperUtils.USER_ATTRIBUTE,
            ProtocolMapperUtils.USER_MODEL_PROPERTY_LABEL,
            ProtocolMapperUtils.USER_MODEL_PROPERTY_HELP_TEXT,
            ProviderConfigProperty.STRING_TYPE,
            null
        ));
        configProperties.add(new ProviderConfigProperty(
            OTHER_CLAIM_CONFIG,
            "Other Claim",
            "Configures whether to look for the value specified in the attributes field in the other claims " +
                "instead. Use this to e.g. modify the full name claim.",
            ProviderConfigProperty.BOOLEAN_TYPE,
            "false"
        ));
        configProperties.add(new ProviderConfigProperty(
            MODIFICATION_CONFIG,
            "Modification",
            "Modification to add to the configured claim when condition is true",
            ProviderConfigProperty.STRING_TYPE,
            null
        ));
        configProperties.add(new ProviderConfigProperty(
            GROUP_NAME_CONFIG,
            "Group",
            "The group whose membership is checked for the user",
            ProviderConfigProperty.GROUP_TYPE,
            null
        ));
        configProperties.add(new ProviderConfigProperty(
            MEMBERSHIP_CONFIG,
            "Membership",
            "Configures whether the modification is applied when the user is (direct) part of the group (ON) " +
                "or when the user is not part of the group (OFF).",
            ProviderConfigProperty.BOOLEAN_TYPE,
            null
        ));
        configProperties.add(new ProviderConfigProperty(
            LOCATION_CONFIG,
            "Location",
            "Where to add the configured modification",
            ProviderConfigProperty.LIST_TYPE,
            null,
            MODIFICATION_LOCATION_PREFIX,
            MODIFICATION_LOCATION_SUFFIX
        ));

        CONFIG_PROPERTIES = configProperties;
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getDisplayType() {
        return "OIDC Claims Modification Mapper";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "An OIDC mapper that adds a prefix to a claim based on a user's groups";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    /* Returns a modified attribute to be returned in e.g. a OIDC token.
     *
     * This function maps the configured user property to the attribute.
     * If `OTHER_CLAIM_CONFIG` is set, not a user attribute will be used but other claims in the collection.
     */
    private String getModifiedAttributeValueCollection(ProtocolMapperModel mappingModel, UserSessionModel userSession, KeycloakSession keycloakSession,
        Map<String, Object> otherClaims
    ) {
        String userAttribute = mappingModel.getConfig().get(ProtocolMapperUtils.USER_ATTRIBUTE);
        String modification = mappingModel.getConfig().get(MODIFICATION_CONFIG);
        String groupName = mappingModel.getConfig().get(GROUP_NAME_CONFIG);
        String membership = mappingModel.getConfig().get(MEMBERSHIP_CONFIG);
        String location = mappingModel.getConfig().get(LOCATION_CONFIG);
        String checkOtherClaimsValue = mappingModel.getConfig().get(OTHER_CLAIM_CONFIG);

        // Ensure that none of the above is null (mapper is properly set up)
        if (userAttribute == null || modification == null || groupName == null || membership == null || location == null || checkOtherClaimsValue == null) {
            return null;
        }

        Boolean checkOtherClaims = Boolean.valueOf(checkOtherClaimsValue);

        UserModel user = userSession.getUser();

        // Check user's direct group membership
        Boolean requireGroupMembership = Boolean.valueOf(membership);
        GroupModel group = KeycloakModelUtils.findGroupByPath(keycloakSession, userSession.getRealm(), groupName);
        if (group == null) {
            LOGGER.debug("Group is null");
            return null;
        }
        if (user.isMemberOf(group) != requireGroupMembership) {
            LOGGER.debug("Require group membership is not fulfilled");
            return null;
        }

        // get original attribute (either from claim collection or user attributes)
        Object propertyValue = checkOtherClaims ? otherClaims.get(userAttribute) : ProtocolMapperUtils.getUserModelValue(user, userAttribute);
        if (propertyValue == null || !(propertyValue instanceof String)) {
            LOGGER.debug("Property value either null or not String " + userAttribute);
            return null;
        }

        String propertyValueString = (String) propertyValue;
        // Modify attribute
        if (location.equals(MODIFICATION_LOCATION_PREFIX)) {
            return modification + propertyValueString;
        } else if (location.equals(MODIFICATION_LOCATION_SUFFIX)) {
            return propertyValueString + modification;
        }
        return propertyValueString;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
        KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx
    ) {
        String modifiedAttributeValue = this.getModifiedAttributeValueCollection(mappingModel, userSession, keycloakSession, token.getOtherClaims());
        if (modifiedAttributeValue == null) {
            return;
        }

        // Map claim into token
        OIDCAttributeMapperHelper.mapClaim(token, mappingModel, modifiedAttributeValue);
    }

    @Override
    protected void setClaim(AccessTokenResponse accessTokenResponse, ProtocolMapperModel mappingModel,
            UserSessionModel userSession, KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx
    ) {
        String modifiedAttributeValue = this.getModifiedAttributeValueCollection(mappingModel, userSession, keycloakSession, accessTokenResponse.getOtherClaims());
        if (modifiedAttributeValue == null) {
            return;
        }

        // Map claim into token
        OIDCAttributeMapperHelper.mapClaim(accessTokenResponse, mappingModel, modifiedAttributeValue);
    }

    @Override
    public int getPriority() {
        // Use a priority higher than 0, so that we are executed after the default mappers (e.g. family name mapper)
        return 100;
    }
}
