/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.account.ocp.dto.Info;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.TimeService;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import static org.springframework.util.StringUtils.hasText;

/**
 * @author Luke Taylor
 * @author Dave Syer
 * @author Vidya Valmikinathan
 */
public class JdbcUaaUserDatabase implements UaaUserDatabase {

    private static Log logger = LogFactory.getLog(JdbcUaaUserDatabase.class);
    public static final String PRACTITIONER_RESOURCE_TYPE = "Practitioner";

    public static final String USER_FIELDS = "id,username,password,email,givenName,familyName,created,lastModified,authorities,origin,external_id,verified,identity_zone_id,salt,passwd_lastmodified,phoneNumber,legacy_verification_behavior,passwd_change_required,last_logon_success_time,previous_logon_success_time ";

    public static final String PRE_DEFAULT_USER_BY_USERNAME_QUERY = "select " + USER_FIELDS + "from users where %s = ? and active=? and origin=? and identity_zone_id=?";
    public static final String DEFAULT_CASE_SENSITIVE_USER_BY_USERNAME_QUERY = String.format(PRE_DEFAULT_USER_BY_USERNAME_QUERY, "lower(username)");
    public static final String DEFAULT_CASE_INSENSITIVE_USER_BY_USERNAME_QUERY = String.format(PRE_DEFAULT_USER_BY_USERNAME_QUERY, "username");

    public static final String PRE_DEFAULT_USER_BY_EMAIL_AND_ORIGIN_QUERY = "select " + USER_FIELDS + "from users where %s=? and active=? and origin=? and identity_zone_id=?";
    public static final String DEFAULT_CASE_SENSITIVE_USER_BY_EMAIL_AND_ORIGIN_QUERY = String.format(PRE_DEFAULT_USER_BY_EMAIL_AND_ORIGIN_QUERY, "lower(email)");
    public static final String DEFAULT_CASE_INSENSITIVE_USER_BY_EMAIL_AND_ORIGIN_QUERY = String.format(PRE_DEFAULT_USER_BY_EMAIL_AND_ORIGIN_QUERY, "email");
    public static final String DEFAULT_UPDATE_USER_LAST_LOGON = "update users set previous_logon_success_time = last_logon_success_time, last_logon_success_time = ? where id = ? and identity_zone_id=?";

    public static final String DEFAULT_USER_BY_ID_QUERY = "select " + USER_FIELDS + "from users where id = ? and active=? and identity_zone_id=?";

    public static final String USERS_BY_TWO_USERINFO_CRITERIA = "select users.id, users.givenname, users.familyname, groups.displayName, groups.description, user_info.info, users.username, groups.id from users " +
            "left join group_membership on users.id = group_membership.member_id left join groups on groups.id = group_membership.group_id inner join user_info on users.id = user_info.user_id and user_info.info ilike ? and user_info.info ilike ?";

    public static final String USERS_BY_ONE_USERINFO_CRITERIA = "select users.id, users.givenname, users.familyname, groups.displayName, groups.description, user_info.info, users.username, groups.id from users " +
            "left join group_membership on users.id = group_membership.member_id left join groups on groups.id = group_membership.group_id inner join user_info on users.id = user_info.user_id and user_info.info ilike ?";

    public static final String USERS_BY_ORGANIZATION_ROLE_QUERY = "select info from group_membership, user_info, groups where info ilike ? and user_info.user_id = group_membership.member_id and groups.id = group_membership.group_id and displayName ilike ?";

    public static String USER_ROLES_QUERY = "select users.id, users.givenname, users.familyname, groups.displayName, groups.description, user_info.info, users.username, groups.id from users left join group_membership on users.id = group_membership.member_id left join groups on groups.id = group_membership.group_id inner join user_info on users.id = user_info.user_id and ";


    private final TimeService timeService;

    private JdbcTemplate jdbcTemplate;

    private final RowMapper<UaaUser> mapper = new UaaUserRowMapper();
    private final RowMapper<UserInfo> userInfoMapper = new UserInfoRowMapper();

    private boolean caseInsensitive = false;

    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isCaseInsensitive() {
        return caseInsensitive;
    }

    public void setCaseInsensitive(boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
    }

    public RowMapper<UaaUser> getMapper() {
        return mapper;
    }

    public JdbcUaaUserDatabase(JdbcTemplate jdbcTemplate, TimeService timeService) {
        Assert.notNull(jdbcTemplate);
        this.jdbcTemplate = jdbcTemplate;
        this.timeService = timeService;
    }

    @Override
    public UaaUser retrieveUserByName(String username, String origin) throws UsernameNotFoundException {
        try {
            String sql = isCaseInsensitive() ? DEFAULT_CASE_INSENSITIVE_USER_BY_USERNAME_QUERY : DEFAULT_CASE_SENSITIVE_USER_BY_USERNAME_QUERY;
            return jdbcTemplate.queryForObject(sql, mapper, username.toLowerCase(Locale.US), true, origin, IdentityZoneHolder.get().getId());
        } catch (EmptyResultDataAccessException e) {
            throw new UsernameNotFoundException(username);
        }
    }

    @Override
    public UaaUser retrieveUserById(String id) throws UsernameNotFoundException {
        try {
            return jdbcTemplate.queryForObject(DEFAULT_USER_BY_ID_QUERY, mapper, id, true, IdentityZoneHolder.get().getId());
        } catch (EmptyResultDataAccessException e) {
            throw new UsernameNotFoundException(id);
        }
    }

    @Override
    public UaaUser retrieveUserByEmail(String email, String origin) throws UsernameNotFoundException {
        String sql = isCaseInsensitive() ? DEFAULT_CASE_INSENSITIVE_USER_BY_EMAIL_AND_ORIGIN_QUERY : DEFAULT_CASE_SENSITIVE_USER_BY_EMAIL_AND_ORIGIN_QUERY;
        List<UaaUser> results = jdbcTemplate.query(sql, mapper, email.toLowerCase(Locale.US), true, origin, IdentityZoneHolder.get().getId());
        if(results.size() == 0) {
            return null;
        }
        else if(results.size() == 1) {
            return results.get(0);
        }
        else {
            throw new IncorrectResultSizeDataAccessException(String.format("Multiple users match email=%s origin=%s", email, origin), 1, results.size());
        }
    }

    @Override
    public UserInfo getUserInfo(String id) {
        try {
            return jdbcTemplate.queryForObject("select user_id, info from user_info where user_id = ?", userInfoMapper, id);
        } catch (EmptyResultDataAccessException e) {
            logger.debug("No custom attributes stored for user:"+id);
            return null;
        }
    }

    public List<UserDto> getUsersByOrganizationId(String organizationId, String resource) {
        String searchString = "%orgId\":[\"" + organizationId + "\"]%";
        String resourceString = "%\"resource\":[\"" + resource + "\"]%";
        try {
            List<UserDto> userInfos = jdbcTemplate.query(USERS_BY_TWO_USERINFO_CRITERIA, (rs, rowNum) -> {
                String id = rs.getString(1);
                String givenName = rs.getString(2);
                String familyName = rs.getString(3);
                String displayName = rs.getString(4);
                String description = rs.getString(5);
                String info = rs.getString(6);
                String username = rs.getString(7);
                String groupId = rs.getString(8);

                return new UserDto(id, username, givenName, familyName, displayName, description, info, groupId);
            }, searchString, resourceString);

            return userInfos;
        } catch (EmptyResultDataAccessException e) {
            logger.debug("No userInfo available");
            return null;
        }
    }

    public Object retrievePractitionersByOrganizationAndRole(String organizationId, String role) {
        String organizationParam = "%orgId\":[\"" + organizationId + "\"]%";
        String roleParam = "%" + role + "%";

        List<String> infos = jdbcTemplate.query(USERS_BY_ORGANIZATION_ROLE_QUERY, (rs, rowNum) -> {
            String infoString = "";
            try {
                String info = rs.getString(1);
                ObjectMapper mapper = new ObjectMapper();

                Info infoObj = mapper.readValue(info, Info.class);
                infoString = PRACTITIONER_RESOURCE_TYPE + "/" +infoObj.getUserAttributes().getId().get(0);
            } catch (IOException e) {
                logger.error("IOException during parsing the string value of the query");

            }

            return infoString;
        }, organizationParam, roleParam);

        return infos;
    }

    public List<UserDto> getUsersByFhirResource(String resourceId, String resourceType) {
        String resourceString = "%\"resource\":[\"" + resourceType + "\"],\"id\":[\""+resourceId+"\"]%";
        System.out.println(resourceString);
        try {
            List<UserDto> userInfos = jdbcTemplate.query(USERS_BY_ONE_USERINFO_CRITERIA, (rs, rowNum) -> {
                String id = rs.getString(1);
                String givenName = rs.getString(2);
                String familyName = rs.getString(3);
                String displayName = rs.getString(4);
                String description = rs.getString(5);
                String info = rs.getString(6);
                String username = rs.getString(7);
                String groupId = rs.getString(8);

                return new UserDto(id, username, givenName, familyName, displayName, description, info, groupId);
            }, resourceString);

            return userInfos;
        } catch (EmptyResultDataAccessException e) {
            logger.debug("No userInfo available");
            return null;
        }
    }

    @Override
    public Map<String, List<UserDto>> getUserRoles(List<String> fhirIds) {
        StringJoiner joiner = new StringJoiner(" or ", "(", ")");
        fhirIds.stream().forEach(fhirId -> {
            joiner.add("user_info.info ilike '%\"id\":[\"" + fhirId + "\"]%'");
        });
        String finalQuery = USER_ROLES_QUERY + joiner.toString();

        Map<String, List<UserDto>> map = new HashMap<>();
        try {
            jdbcTemplate.query(finalQuery, (rs, rowNum) -> {
                String id = rs.getString(1);
                String givenName = rs.getString(2);
                String familyName = rs.getString(3);
                String displayName = rs.getString(4);
                String description = rs.getString(5);

                String fhirId = "";
                String info = rs.getString(6);
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    Info infoObj = mapper.readValue(info, Info.class);
                    fhirId = infoObj.getUserAttributes().getId().get(0);
                } catch (IOException e) {
                    logger.error("Error occured while parsing info string");
                }

                String username = rs.getString(7);
                String groupId = rs.getString(8);

                UserDto userDto = new UserDto(id, username, givenName, familyName, displayName, description, info, groupId);

                List<UserDto> list = new ArrayList<>();

                if(map.get(fhirId) != null) {
                    list = map.get(fhirId);
                }

                list.add(userDto);

                map.put(fhirId, list);

                return null;
            });

            return map;
        } catch (EmptyResultDataAccessException e) {
            logger.debug("No userInfo available");
            return null;
        }
    }

    @Override
    public UserInfo storeUserInfo(String id, UserInfo info) {
        if (StringUtils.isEmpty(id)) {
            throw new NullPointerException("id is a required field");
        }
        final String insertUserInfoSQL = "insert into user_info(user_id, info) values (?,?)";
        final String updateUserInfoSQL = "update user_info set info = ? where user_id = ?";
        if (info == null) {
            info = new UserInfo();
        }
        String json = JsonUtils.writeValueAsString(info);
        int count = jdbcTemplate.update(updateUserInfoSQL, json, id);
        if (count == 0) {
            jdbcTemplate.update(insertUserInfoSQL, id, json);
        }
        return getUserInfo(id);
    }

    @Override
    public void updateLastLogonTime(String userId) {
        int update = jdbcTemplate.update(DEFAULT_UPDATE_USER_LAST_LOGON, timeService.getCurrentTimeMillis(), userId, IdentityZoneHolder.get().getId());
    }

    private final class UserInfoRowMapper implements RowMapper<UserInfo> {
        @Override
        public UserInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
            String id = rs.getString(1);
            String info = rs.getString(2);
            UserInfo userInfo = hasText(info) ? JsonUtils.readValue(info, UserInfo.class) : new UserInfo();
            return userInfo;
        }
    }

    private final class UaaUserRowMapper implements RowMapper<UaaUser> {
        @Override

        public UaaUser mapRow(ResultSet rs, int rowNum) throws SQLException {
            String id = rs.getString("id");
            UaaUserPrototype prototype = new UaaUserPrototype().withId(id)
                .withUsername(rs.getString("username"))
                .withPassword(rs.getString("password"))
                .withEmail(rs.getString("email"))
                .withGivenName(rs.getString("givenName"))
                .withFamilyName(rs.getString("familyName"))
                .withCreated(rs.getTimestamp("created"))
                .withModified(rs.getTimestamp("lastModified"))
                .withOrigin(rs.getString("origin"))
                .withExternalId(rs.getString("external_id"))
                .withVerified(rs.getBoolean("verified"))
                .withZoneId(rs.getString("identity_zone_id"))
                .withSalt(rs.getString("salt"))
                .withPasswordLastModified(rs.getTimestamp("passwd_lastmodified"))
                .withPhoneNumber(rs.getString("phoneNumber"))
                .withLegacyVerificationBehavior(rs.getBoolean("legacy_verification_behavior"))
                .withPasswordChangeRequired(rs.getBoolean("passwd_change_required"))
                ;

            Long lastLogon = rs.getLong("last_logon_success_time");
            if (rs.wasNull()) {
                lastLogon = null;
            }
            Long previousLogon = rs.getLong("previous_logon_success_time");
            if (rs.wasNull()) {
                previousLogon = null;
            }
            prototype.withLastLogonSuccess(lastLogon)
                .withPreviousLogonSuccess(previousLogon);


            List<GrantedAuthority> authorities =
                AuthorityUtils.commaSeparatedStringToAuthorityList(getAuthorities(id));
            return new UaaUser(prototype.withAuthorities(authorities));
        }

        private String getAuthorities(final String userId) {
            Set<String> authorities = new HashSet<>();
            getAuthorities(authorities, Arrays.asList(userId));
            authorities.addAll(IdentityZoneHolder.get().getConfig().getUserConfig().getDefaultGroups());
            return StringUtils.collectionToCommaDelimitedString(new HashSet<>(authorities));
        }

        protected void getAuthorities(Set<String> authorities, final List<String> memberIdList) {
            List<Map<String, Object>> results;
            if(memberIdList.size() == 0) {
                return;
            }
            StringBuffer dynamicAuthoritiesQuery = new StringBuffer("select g.id,g.displayName from groups g, group_membership m where g.id = m.group_id  and g.identity_zone_id=? and m.member_id in (");
            for (int i = 0; i < memberIdList.size() - 1; i++) {
                dynamicAuthoritiesQuery.append("?,");
            }
            dynamicAuthoritiesQuery.append("?);");

            Object[] parameterList = ArrayUtils.addAll(new Object[]{IdentityZoneHolder.get().getId()},memberIdList.toArray());

            results = jdbcTemplate.queryForList(dynamicAuthoritiesQuery.toString(), parameterList);
            List<String> newMemberIdList = new ArrayList<>();

            for(int i=0;i<results.size();i++) {
                Map<String, Object> record = results.get(i);
                String displayName = (String) record.get("displayName");
                String groupId = (String) record.get("id");
                if (!authorities.contains(displayName)) {
                    authorities.add(displayName);
                    newMemberIdList.add(groupId);
                }
            }
            getAuthorities(authorities,newMemberIdList);
        }
    }
}
