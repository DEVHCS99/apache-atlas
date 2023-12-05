package org.apache.atlas.auth.client.heracles;

import org.apache.atlas.auth.client.config.AuthConfig;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.auth.client.heracles.models.HeraclesUsersRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AtlasHeraclesClient {
    public final static Logger LOG = LoggerFactory.getLogger(AtlasHeraclesClient.class);

    private static HeraclesRestClient HERACLES;
    private static AtlasHeraclesClient HERACLES_CLIENT;

    public AtlasHeraclesClient() {}

    public static AtlasHeraclesClient getHeraclesClient() {
        if(Objects.isNull(HERACLES_CLIENT)) {
            LOG.info("Initializing Keycloak client..");
            try{
                init(AuthConfig.getConfig());
            } catch (Exception e) {
                LOG.error("Error initializing Heracles client", e);
            }
        }
        return HERACLES_CLIENT;
    }

    private static void init(AuthConfig authConfig) {
        if (HERACLES == null) {
            HERACLES = new HeraclesRestClient(authConfig);
            HERACLES_CLIENT = new AtlasHeraclesClient();
        }
    }

    public List<UserRepresentation> getAllUsers() throws AtlasBaseException {
        int start = 0;
        int size = 100;
        boolean found = true;

        List<UserRepresentation> ret = new ArrayList<>(0);
        do {

            List<UserRepresentation> userRepresentations = HERACLES.getUsers(start, size, HeraclesUsersRepresentation.USER_PROJECTIONS, null, HeraclesUsersRepresentation.USER_SORT).body().toKeycloakUserRepresentations();
            if (userRepresentations != null && !userRepresentations.isEmpty()) {
                ret.addAll(userRepresentations);
                start += size;
            } else {
                found = false;
            }
        } while (found && ret.size() % size == 0);

        return ret;
    }


    public Set<UserRepresentation> getRoleUserMembers(String roleName, int start, int size) throws AtlasBaseException {
        String template = "{\"$and\":[{\"roles\":{\"$elemMatch\":[\"{0}\"]}}]}";
        String filter = template.replace("{0}", roleName);
        return HERACLES.getUsers(start, size, HeraclesUsersRepresentation.USER_PROJECTIONS, filter,HeraclesUsersRepresentation.USER_SORT ).body().toKeycloakUserRepresentations().stream().collect(Collectors.toSet());
    }
}
