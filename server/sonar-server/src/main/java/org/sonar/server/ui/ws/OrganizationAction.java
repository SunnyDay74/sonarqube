/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.ui.ws;

import java.util.List;
import java.util.Optional;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.Change;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.api.web.page.Page;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.alm.OrganizationAlmBindingDto;
import org.sonar.db.component.ComponentQuery;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.server.organization.BillingValidations;
import org.sonar.server.organization.BillingValidationsProxy;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.project.Visibility;
import org.sonar.server.ui.PageRepository;
import org.sonar.server.user.UserSession;

import static org.sonar.db.organization.OrganizationDto.Subscription.PAID;
import static org.sonar.db.permission.OrganizationPermission.ADMINISTER;
import static org.sonar.server.ws.KeyExamples.KEY_ORG_EXAMPLE_001;
import static org.sonar.server.ws.WsUtils.checkFoundWithOptional;

public class OrganizationAction implements NavigationWsAction {

  private static final String ACTION_NAME = "organization";
  private static final String PARAM_ORGANIZATION = "organization";

  private final DbClient dbClient;
  private final DefaultOrganizationProvider defaultOrganizationProvider;
  private final UserSession userSession;
  private final PageRepository pageRepository;
  private final BillingValidationsProxy billingValidations;

  public OrganizationAction(DbClient dbClient, DefaultOrganizationProvider defaultOrganizationProvider, UserSession userSession, PageRepository pageRepository,
    BillingValidationsProxy billingValidations) {
    this.dbClient = dbClient;
    this.defaultOrganizationProvider = defaultOrganizationProvider;
    this.userSession = userSession;
    this.pageRepository = pageRepository;
    this.billingValidations = billingValidations;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction projectNavigation = context.createAction(ACTION_NAME)
      .setDescription("Get information concerning organization navigation for the current user")
      .setHandler(this)
      .setInternal(true)
      .setResponseExample(getClass().getResource("organization-example.json"))
      .setSince("6.3")
      .setChangelog(new Change("6.4", "The field 'projectVisibility' is added"));

    projectNavigation.createParam(PARAM_ORGANIZATION)
      .setRequired(true)
      .setDescription("the organization key")
      .setExampleValue(KEY_ORG_EXAMPLE_001);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    String organizationKey = request.mandatoryParam(PARAM_ORGANIZATION);
    try (DbSession dbSession = dbClient.openSession(false)) {
      OrganizationDto organization = checkFoundWithOptional(
        dbClient.organizationDao().selectByKey(dbSession, organizationKey),
        "No organization with key '%s'", organizationKey);
      if (organization.getSubscription() == PAID) {
        // If the organization is PAID without any public project then
        // the organization is only visible to members
        ComponentQuery query = ComponentQuery.builder()
          .setQualifiers(Qualifiers.PROJECT)
          .setPrivate(false)
          .build();
        if (dbClient.componentDao().countByQuery(dbSession, organization.getUuid(), query) == 0) {
          userSession.checkMembership(organization);
        }
      }
      boolean newProjectPrivate = dbClient.organizationDao().getNewProjectPrivate(dbSession, organization);

      JsonWriter json = response.newJsonWriter();
      json.beginObject();
      writeOrganization(json, organization, dbClient.organizationAlmBindingDao().selectByOrganization(dbSession, organization), newProjectPrivate);
      json.endObject()
        .close();
    }
  }

  private void writeOrganization(JsonWriter json, OrganizationDto organization, Optional<OrganizationAlmBindingDto> organizationAlmBinding, boolean newProjectPrivate) {
    json.name("organization")
      .beginObject()
      .prop("isDefault", organization.getKey().equals(defaultOrganizationProvider.get().getKey()))
      .prop("projectVisibility", Visibility.getLabel(newProjectPrivate))
      .prop("subscription", organization.getSubscription().name())
      .prop("canUpdateProjectsVisibilityToPrivate",
        userSession.hasPermission(ADMINISTER, organization) &&
          billingValidations.canUpdateProjectVisibilityToPrivate(new BillingValidations.Organization(organization.getKey(), organization.getUuid())));
    writeAlm(json, organizationAlmBinding);
    json.name("pages");
    writePages(json, pageRepository.getOrganizationPages(false));
    if (userSession.hasPermission(ADMINISTER, organization)) {
      json.name("adminPages");
      writePages(json, pageRepository.getOrganizationPages(true));
    }
    json.endObject();
  }

  private static void writePages(JsonWriter json, List<Page> pages) {
    json.beginArray();
    pages.forEach(p -> json.beginObject()
      .prop("key", p.getKey())
      .prop("name", p.getName())
      .endObject());
    json.endArray();
  }

  private static void writeAlm(JsonWriter json, Optional<OrganizationAlmBindingDto> organizationAlmBindingOpt) {
    organizationAlmBindingOpt.ifPresent(
      organizationAlmBinding -> json
        .name("alm")
        .beginObject()
        .prop("key", organizationAlmBinding.getAlm().getId())
        .prop("url", organizationAlmBinding.getUrl())
        .endObject());
  }
}
