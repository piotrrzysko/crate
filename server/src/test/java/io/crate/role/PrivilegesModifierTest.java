/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.role;

import static io.crate.role.metadata.RolesHelper.userOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.elasticsearch.cluster.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;

import io.crate.role.metadata.RolesMetadata;

public class PrivilegesModifierTest {

    private static final Privilege GRANT_DQL =
        new Privilege(PrivilegeState.GRANT, Privilege.Type.DQL, Privilege.Clazz.CLUSTER, null, "crate");
    private static final Privilege GRANT_DML =
        new Privilege(PrivilegeState.GRANT, Privilege.Type.DML, Privilege.Clazz.CLUSTER, null, "crate");
    private static final Privilege REVOKE_DQL =
        new Privilege(PrivilegeState.REVOKE, Privilege.Type.DQL, Privilege.Clazz.CLUSTER, null, "crate");
    private static final Privilege REVOKE_DML =
        new Privilege(PrivilegeState.REVOKE, Privilege.Type.DML, Privilege.Clazz.CLUSTER, null, "crate");
    public static final Privilege DENY_DQL =
        new Privilege(PrivilegeState.DENY, Privilege.Type.DQL, Privilege.Clazz.CLUSTER, null, "crate");
    public static final Privilege GRANT_TABLE_DQL =
        new Privilege(PrivilegeState.GRANT, Privilege.Type.DQL, Privilege.Clazz.TABLE, "testSchema.test", "crate");
    public static final Privilege GRANT_TABLE_DDL =
        new Privilege(PrivilegeState.GRANT, Privilege.Type.DDL, Privilege.Clazz.TABLE, "testSchema.test2", "crate");
    public static final Privilege GRANT_VIEW_DQL =
        new Privilege(PrivilegeState.GRANT, Privilege.Type.DQL, Privilege.Clazz.VIEW, "testSchema.view1", "crate");
    public static final Privilege GRANT_VIEW_DDL =
        new Privilege(PrivilegeState.GRANT, Privilege.Type.DDL, Privilege.Clazz.VIEW, "testSchema.view2", "crate");
    public static final Privilege GRANT_VIEW_DML =
        new Privilege(PrivilegeState.GRANT, Privilege.Type.DML, Privilege.Clazz.VIEW, "view3", "crate");
    public static final Privilege GRANT_SCHEMA_DML =
        new Privilege(PrivilegeState.GRANT, Privilege.Type.DML, Privilege.Clazz.SCHEMA, "testSchema", "crate");

    public static final Set<Privilege> PRIVILEGES = new HashSet<>(Arrays.asList(GRANT_DQL, GRANT_DML));
    public static final List<String> USERNAMES = Arrays.asList("Ford", "Arthur");
    public static final String USER_WITHOUT_PRIVILEGES = "noPrivilegesUser";
    public static final String USER_WITH_DENIED_DQL = "userWithDeniedDQL";
    public static final String USER_WITH_SCHEMA_AND_TABLE_PRIVS = "userWithTableAndSchemaPrivs";

    private RolesMetadata rolesMetadata;

    private static RolesMetadata createMetadata() {
        Map<String, Role> roles = new HashMap<>();
        for (String userName : USERNAMES) {
            roles.put(userName, userOf(userName, new HashSet<>(PRIVILEGES), null));
        }
        roles.put(USER_WITHOUT_PRIVILEGES, userOf(USER_WITHOUT_PRIVILEGES));
        roles.put(USER_WITH_DENIED_DQL, userOf(USER_WITH_DENIED_DQL, new HashSet<>(Collections.singletonList(DENY_DQL)), null));
        roles.put(USER_WITH_SCHEMA_AND_TABLE_PRIVS, userOf(USER_WITH_SCHEMA_AND_TABLE_PRIVS, new HashSet<>(
            Arrays.asList(GRANT_SCHEMA_DML, GRANT_TABLE_DQL, GRANT_TABLE_DDL, GRANT_VIEW_DQL, GRANT_VIEW_DML, GRANT_VIEW_DDL)),
            null
        ));

        return new RolesMetadata(roles);
    }

    @Before
    public void setRolesMetadata() throws Exception {
        rolesMetadata = createMetadata();
    }

    @Test
    public void testApplyPrivilegesSameExists() throws Exception {
        long rowCount = PrivilegesModifier.applyPrivileges(rolesMetadata, USERNAMES, new HashSet<>(PRIVILEGES));
        assertThat(rowCount).isEqualTo(0L);
    }

    @Test
    public void testRevokeWithoutGrant() throws Exception {
        long rowCount = PrivilegesModifier.applyPrivileges(
            rolesMetadata,
            Collections.singletonList(USER_WITHOUT_PRIVILEGES),
            Collections.singletonList(REVOKE_DML)
        );
        assertThat(rowCount).isEqualTo(0L);
        assertThat(rolesMetadata.roles().get(USER_WITHOUT_PRIVILEGES).privileges().size()).isEqualTo(0L);
    }

    @Test
    public void testRevokeWithGrant() throws Exception {
        long rowCount = PrivilegesModifier.applyPrivileges(
            rolesMetadata,
            Collections.singletonList("Arthur"),
            Collections.singletonList(REVOKE_DML)
        );

        assertThat(rowCount).isEqualTo(1L);
        assertThat(rolesMetadata.roles().get("Arthur").privileges()).containsExactly(GRANT_DQL);
    }


    @Test
    public void testRevokeWithGrantOfDifferentGrantor() throws Exception {
        long rowCount = PrivilegesModifier.applyPrivileges(
            rolesMetadata,
            Collections.singletonList("Arthur"),
            Collections.singletonList(new Privilege(PrivilegeState.REVOKE, Privilege.Type.DML, Privilege.Clazz.CLUSTER, null, "hoschi"))
        );

        assertThat(rowCount).isEqualTo(1L);
        assertThat(rolesMetadata.roles().get("Arthur").privileges()).containsExactly(GRANT_DQL);
    }

    @Test
    public void testDenyGrantedPrivilegeForUsers() throws Exception {
        long rowCount = PrivilegesModifier.applyPrivileges(
            rolesMetadata,
            USERNAMES,
            Collections.singletonList(DENY_DQL)
        );
        assertThat(rowCount).isEqualTo(2L);
    }

    @Test
    public void testDenyUngrantedPrivilegeStoresTheDeny() throws Exception {
        long rowCount = PrivilegesModifier.applyPrivileges(
            rolesMetadata,
            Collections.singletonList(USER_WITHOUT_PRIVILEGES),
            Collections.singletonList(DENY_DQL)
        );
        assertThat(rowCount).isEqualTo(1L);
        assertThat(rolesMetadata.roles().get(USER_WITHOUT_PRIVILEGES).privileges()).containsExactly(DENY_DQL);
    }

    @Test
    public void testRevokeDenyPrivilegeRemovesIt() throws Exception {
        long rowCount = PrivilegesModifier.applyPrivileges(
            rolesMetadata,
            Collections.singletonList(USER_WITH_DENIED_DQL),
            Collections.singletonList(REVOKE_DQL)
        );
        assertThat(rowCount).isEqualTo(1L);
        assertThat(rolesMetadata.roles().get(USER_WITH_DENIED_DQL).privileges()).isEmpty();
    }

    @Test
    public void testDenyExistingDeniedPrivilegeIsNoOp() {
        long rowCount = PrivilegesModifier.applyPrivileges(
            rolesMetadata,
            Collections.singletonList(USER_WITH_DENIED_DQL),
            new HashSet<>(Collections.singletonList(DENY_DQL))
        );
        assertThat(rowCount).isEqualTo(0L);
        assertThat(rolesMetadata.roles().get(USER_WITH_DENIED_DQL).privileges()).containsExactly(DENY_DQL);
    }

    @Test
    public void testTablePrivilegesAreTransferred() throws Exception {
        var newRolesMetadata = PrivilegesModifier.maybeCopyAndReplaceTableIdents(
            rolesMetadata, GRANT_TABLE_DQL.ident().ident(), "testSchema.testing");

        assertThat(newRolesMetadata).isNotNull();

        var role = newRolesMetadata.roles().get(USER_WITH_SCHEMA_AND_TABLE_PRIVS);
        HashSet<Privilege> updatedPrivileges = new HashSet<>(role.privileges().size());
        for (var privilege : role.privileges()) {
            updatedPrivileges.add(privilege);
        }
        Optional<Privilege> targetPrivilege = updatedPrivileges.stream()
            .filter(hasPrivilegeOn("testSchema.testing"))
            .findAny();
        assertThat(targetPrivilege.isPresent()).isTrue();

        Optional<Privilege> sourcePrivilege = updatedPrivileges.stream()
            .filter(hasPrivilegeOn("testSchema.test"))
            .findAny();
        assertThat(sourcePrivilege.isPresent()).isFalse();

        // unrelated table privileges must be still available
        Optional<Privilege> otherTablePrivilege = updatedPrivileges.stream()
            .filter(hasPrivilegeOn("testSchema.test2"))
            .findAny();
        assertThat(otherTablePrivilege.isPresent()).isTrue();

        Optional<Privilege> schemaPrivilege = updatedPrivileges.stream()
            .filter(p -> p.ident().clazz().equals(Privilege.Clazz.SCHEMA))
            .findAny();
        assertThat(schemaPrivilege.isPresent() && schemaPrivilege.get().equals(GRANT_SCHEMA_DML)).isTrue();
    }

    @Test
    public void testDropTablePrivileges() {
        var mdBuilder = Metadata.builder();
        long affectedPrivileges = PrivilegesModifier.dropTableOrViewPrivileges(mdBuilder, rolesMetadata, GRANT_TABLE_DQL.ident().ident());
        assertThat(affectedPrivileges).isEqualTo(1L);

        var newRolesMetadata = (RolesMetadata) mdBuilder.getCustom(RolesMetadata.TYPE);
        var role = newRolesMetadata.roles().get(USER_WITH_SCHEMA_AND_TABLE_PRIVS);
        HashSet<Privilege> updatedPrivileges = new HashSet<>(role.privileges().size());
        for (var privilege : role.privileges()) {
            updatedPrivileges.add(privilege);
        }
        Optional<Privilege> sourcePrivilege = updatedPrivileges.stream()
            .filter(hasPrivilegeOn("testSchema.test"))
            .findAny();
        assertThat(sourcePrivilege.isPresent()).isFalse();
    }

    @Test
    public void testDropViewPrivileges() {
        var mdBuilder = Metadata.builder();
        long affectedPrivileges = PrivilegesModifier.dropTableOrViewPrivileges(mdBuilder, rolesMetadata, GRANT_VIEW_DQL.ident().ident());
        assertThat(affectedPrivileges).isEqualTo(1L);

        var newRolesMetadata = (RolesMetadata) mdBuilder.getCustom(RolesMetadata.TYPE);
        var role = newRolesMetadata.roles().get(USER_WITH_SCHEMA_AND_TABLE_PRIVS);
        HashSet<Privilege> updatedPrivileges = new HashSet<>(role.privileges().size());
        for (var privilege : role.privileges()) {
            updatedPrivileges.add(privilege);
        }
        Optional<Privilege> sourcePrivilege = updatedPrivileges.stream()
            .filter(hasPrivilegeOn("testSchema.view1"))
            .findAny();
        assertThat(sourcePrivilege.isPresent()).isFalse();
    }

    @NotNull
    private static Predicate<Privilege> hasPrivilegeOn(String object) {
        return p -> object.equals(p.ident().ident());
    }
}
