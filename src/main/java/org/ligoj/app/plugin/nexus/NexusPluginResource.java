/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.nexus;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import lombok.extern.slf4j.Slf4j;
import org.ligoj.app.api.SubscriptionMode;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.dao.ParameterRepository;
import org.ligoj.app.plugin.registry.RegistryResource;
import org.ligoj.app.plugin.registry.RegistryServicePlugin;
import org.ligoj.app.plugin.nexus.client.NexusComponentPage;
import org.ligoj.app.plugin.nexus.client.NexusRepository;
import org.ligoj.app.resource.NormalizeFormat;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.curl.AuthCurlProcessor;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.ligoj.bootstrap.core.curl.CurlRequest;
import org.ligoj.bootstrap.core.json.InMemoryPagination;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sonatype Nexus artifact registry resource. Nexus is multi-format, so the artifact type is a real choice (see
 * {@link NexusFormat}). In LINK mode, the subscription references an existing repository. In CREATE mode, the hosted
 * repository is created with the format defaults completed by the JSON configuration, and the JSON role mapping grants
 * its privileges to groups.
 */
@Slf4j
@Path(NexusPluginResource.URL)
@Component
@Produces(MediaType.APPLICATION_JSON)
public class NexusPluginResource extends AbstractToolPluginResource implements RegistryServicePlugin {

	/**
	 * Plug-in URL.
	 */
	public static final String URL = RegistryResource.SERVICE_URL + "/nexus";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = URL.replace('/', ':').substring(1);

	/**
	 * Nexus Repository Manager base URL (node validation).
	 */
	public static final String PARAMETER_URL = KEY + ":url";

	/**
	 * Login (node validation).
	 */
	public static final String PARAMETER_USER = KEY + ":user";

	/**
	 * Secret (node validation).
	 */
	public static final String PARAMETER_PASSWORD = KEY + ":password";

	/**
	 * Artifact type (subscription level).
	 */
	public static final String PARAMETER_TYPE = KEY + ":type";

	/**
	 * Target repository/registry (subscription level).
	 */
	public static final String PARAMETER_REGISTRY = KEY + ":registry";

	/**
	 * Format settings of the created repository (CREATE mode): JSON object merged over the format defaults.
	 */
	public static final String PARAMETER_CONFIGURATION = KEY + ":configuration";

	/**
	 * Role mapping (CREATE mode): JSON object keyed by group, granting repository view/admin actions.
	 */
	public static final String PARAMETER_ROLES = KEY + ":roles";

	/**
	 * Repository name accepted by Nexus.
	 */
	private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9\\-][a-zA-Z0-9_.\\-]*$");

	/**
	 * Repository actions of the view and admin privileges.
	 */
	private static final Set<String> ACTIONS = Set.of("browse", "read", "edit", "add", "delete", "*");

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private ParameterRepository parameterRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private InMemoryPagination inMemoryPagination;

	@Override
	public String getKey() {
		return KEY;
	}

	/**
	 * Upgrade the installed data, the CSV seed being insert-only: the tool node accepts the creation, and the artifact
	 * types list gets the new formats (appended, the stored option indexes are kept).
	 */
	@Override
	public void update(final String oldVersion) {
		nodeRepository.findById(KEY).filter(n -> n.getMode() == SubscriptionMode.LINK).ifPresent(n -> {
			log.info("Node {} now supports the repository creation", KEY);
			n.setMode(SubscriptionMode.ALL);
		});
		parameterRepository.findById(PARAMETER_TYPE).filter(p -> !NexusFormat.typesJson().equals(p.getData())).ifPresent(p -> {
			log.info("Parameter {} now lists the formats {}", PARAMETER_TYPE, NexusFormat.typesJson());
			p.setData(NexusFormat.typesJson());
		});
	}

	/**
	 * Sink of the non-blocking warnings of a subscription creation or deletion, reported to the caller.
	 */
	protected ResponseWarnings.Sink responseWarnings() {
		return ResponseWarnings::add;
	}

	private NexusRepositoryManager newManager(final Map<String, String> parameters) {
		return new NexusRepositoryManager(parameters.get(PARAMETER_URL), parameters.get(PARAMETER_USER), parameters.get(PARAMETER_PASSWORD),
				responseWarnings());
	}

	/**
	 * Create the hosted repository, or reuse an existing hosted repository of the same format, then apply the role
	 * mapping. Everything is validated before the first remote call.
	 */
	@Override
	public void create(final int subscription) {
		final var parameters = subscriptionResource.getParameters(subscription);
		final var name = StringUtils.trimToEmpty(parameters.get(PARAMETER_REGISTRY));
		if (!NAME.matcher(name).matches()) {
			throw new ValidationJsonException(PARAMETER_REGISTRY, "nexus-registry-name", "name", name);
		}
		final var format = NexusFormat.fromType(parameters.get(PARAMETER_TYPE))
				.orElseThrow(() -> new ValidationJsonException(PARAMETER_TYPE, "nexus-type", "type", String.valueOf(parameters.get(PARAMETER_TYPE))));
		final var payload = format.payload(name, parseConfiguration(parameters.get(PARAMETER_CONFIGURATION)));
		format.missing(payload).ifPresent(field -> {
			throw new ValidationJsonException(PARAMETER_CONFIGURATION, "nexus-configuration-required", "field", field, "format",
					format.getType());
		});
		final var roles = parseRoles(parameters.get(PARAMETER_ROLES));

		final var manager = newManager(parameters);
		final var existing = manager.findRepository(name);
		if (existing == null) {
			manager.createRepository(format, payload);
		} else if (format.getFormat().equalsIgnoreCase(existing.getFormat()) && "hosted".equalsIgnoreCase(existing.getType())) {
			log.info("Nexus repository {} already exists, reused", name);
			responseWarnings().warn("nexus-registry-reused", ResponseWarnings.parameters("registry", name));
		} else {
			throw new ValidationJsonException(PARAMETER_REGISTRY, "nexus-registry-exists", "format",
					existing.getFormat() + " (" + existing.getType() + ")");
		}
		manager.grant(format, name, roles);
	}

	/**
	 * Delete the remote data: the repository privileges are removed from the mapped roles (a role left empty is
	 * deleted), then the repository. The deletion tolerates remote data already partially deleted, each issue is a
	 * warning.
	 */
	@Override
	public void delete(final int subscription, final boolean deleteRemoteData) {
		if (!deleteRemoteData) {
			return;
		}
		final var parameters = subscriptionResource.getParameters(subscription);
		final var name = StringUtils.trimToEmpty(parameters.get(PARAMETER_REGISTRY));
		final var format = NexusFormat.fromType(parameters.get(PARAMETER_TYPE)).orElse(null);
		if (name.isEmpty() || format == null) {
			log.info("No Nexus repository attached to the subscription {}, nothing to delete", subscription);
			return;
		}
		final var manager = newManager(parameters);
		manager.revoke(format, name, parseRoles(parameters.get(PARAMETER_ROLES)).keySet());
		manager.deleteRepository(name);
	}

	/**
	 * Parse the format configuration, trimmed first: a JSON object, empty when blank.
	 */
	private ObjectNode parseConfiguration(final String raw) {
		final var json = StringUtils.trimToNull(raw);
		if (json == null) {
			return objectMapper.createObjectNode();
		}
		try {
			if (objectMapper.readTree(json) instanceof ObjectNode node) {
				return node;
			}
		} catch (final JacksonException e) {
			log.info("Invalid Nexus configuration: {}", e.getOriginalMessage());
		}
		throw new ValidationJsonException(PARAMETER_CONFIGURATION, "nexus-configuration-json");
	}

	/**
	 * Parse and validate the role mapping, trimmed first: a JSON object keyed by group, each role granting at least one
	 * known action.
	 */
	private Map<String, NexusRole> parseRoles(final String raw) {
		final var json = StringUtils.trimToNull(raw);
		if (json == null) {
			return Map.of();
		}
		final Map<String, NexusRole> roles;
		try {
			roles = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, NexusRole>>() {
				// Nothing to extend
			});
		} catch (final JacksonException e) {
			log.info("Invalid Nexus role mapping: {}", e.getOriginalMessage());
			throw new ValidationJsonException(PARAMETER_ROLES, "nexus-roles-json");
		}
		roles.forEach((group, role) -> {
			if (StringUtils.isBlank(group) || role == null) {
				throw new ValidationJsonException(PARAMETER_ROLES, "nexus-roles-json");
			}
			final var actions = new ArrayList<String>();
			actions.addAll(CollectionUtils.emptyIfNull(role.getViewPermissions()));
			actions.addAll(CollectionUtils.emptyIfNull(role.getAdminPermissions()));
			if (actions.isEmpty()) {
				throw new ValidationJsonException(PARAMETER_ROLES, "nexus-roles-empty", "group", group);
			}
			actions.stream().filter(a -> a == null || !ACTIONS.contains(a.trim())).findFirst().ifPresent(a -> {
				throw new ValidationJsonException(PARAMETER_ROLES, "nexus-roles-permission", "group", group, "permission", String.valueOf(a));
			});
		});
		return roles;
	}

	/**
	 * Return the base URL without the trailing slash.
	 */
	private String getBaseUrl(final Map<String, String> parameters) {
		return Strings.CS.removeEnd(parameters.get(PARAMETER_URL), "/");
	}

	/**
	 * Create a new processor using a basic authentication header built from
	 * the node credentials.
	 */
	private CurlProcessor newProcessor(final Map<String, String> parameters) {
		return new AuthCurlProcessor(parameters.get(PARAMETER_USER),
				StringUtils.trimToEmpty(parameters.get(PARAMETER_PASSWORD)));
	}

	@Override
	public boolean checkStatus(final Map<String, String> parameters) {
		// Node validation: authenticated call to the repositories' endpoint.
		final var request = new CurlRequest(HttpMethod.GET, getBaseUrl(parameters) + "/service/rest/v1/repositories",
				null);
		try (var processor = newProcessor(parameters)) {
			return processor.process(request);
		}
	}

	/**
	 * Validate the subscription registry (the Nexus repository) and return it.
	 * Throws when the repository cannot be resolved.
	 */
	private NexusRepository validateRegistry(final Map<String, String> parameters) {
		final var registry = parameters.get(PARAMETER_REGISTRY);
		final var request = new CurlRequest(HttpMethod.GET,
				getBaseUrl(parameters) + "/service/rest/v1/repositories/" + registry, null);
		request.setSaveResponse(true);
		final boolean found;
		try (var processor = newProcessor(parameters)) {
			found = processor.process(request);
		}
		if (!found || StringUtils.isBlank(request.getResponse())) {
			throw new ValidationJsonException(PARAMETER_REGISTRY, "nexus-registry", "registry", registry);
		}
		return objectMapper.readValue(request.getResponse(), NexusRepository.class);
	}

	@Override
	public void link(final int subscription) throws IOException {
		validateRegistry(subscriptionResource.getParameters(subscription));
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final Map<String, String> parameters) {
		final var status = new SubscriptionStatusWithData();
		final var repository = validateRegistry(parameters);
		status.put("format", repository.getFormat());
		status.put("type", repository.getType());
		status.put("components", countComponents(parameters, parameters.get(PARAMETER_REGISTRY)));
		return status;
	}

	/**
	 * Count the components hosted by the given repository, paging through the
	 * continuation-token based components listing.
	 *
	 * @param parameters The node/subscription parameters.
	 * @param registry   The repository name.
	 * @return The total number of components.
	 */
	private int countComponents(final Map<String, String> parameters, final String registry) {
		int total = 0;
		String token = null;
		do {
			final var request = new CurlRequest(HttpMethod.GET, getBaseUrl(parameters) + "/service/rest/v1/components?repository="
					+ registry + (token == null ? "" : "&continuationToken=" + token), null);
			request.setSaveResponse(true);
			try (var processor = newProcessor(parameters)) {
				processor.process(request);
			}
			final var page = objectMapper.readValue(StringUtils.defaultIfBlank(request.getResponse(), "{}"),
					NexusComponentPage.class);
			total += page.getItems().size();
			token = page.getContinuationToken();
		} while (token != null);
		return total;
	}

	/**
	 * Find the Nexus repositories matching the given criteria.
	 *
	 * @param node     The node identifier holding the registry parameters.
	 * @param criteria The search criteria.
	 * @param type     Optional artifact type (docker, maven, npm, …) to filter the
	 *                 repositories by their Nexus format. When blank, all formats
	 *                 match.
	 * @return The matching repository names.
	 */
	@GET
	@Path("{node}/{criteria}")
	public List<NamedBean<String>> findAllByName(@PathParam("node") final String node,
			@PathParam("criteria") final String criteria, @QueryParam("type") final String type) {
		final var parameters = pvResource.getNodeParameters(node);
		final var request = new CurlRequest(HttpMethod.GET, getBaseUrl(parameters) + "/service/rest/v1/repositories",
				null);
		request.setSaveResponse(true);
		final boolean found;
		try (var processor = newProcessor(parameters)) {
			found = processor.process(request);
		}
		if (found) {
			final List<NexusRepository> repositories = objectMapper.readValue(
					StringUtils.defaultIfBlank(request.getResponse(), "[]"),
					new TypeReference<>() {
						// Nothing to extend
					});
			final var format = new NormalizeFormat();
			final var formatCriteria = format.format(criteria);
			final var wantedFormat = toNexusFormat(type);
			return inMemoryPagination
					.newPage(repositories.stream().filter(r -> format.format(r.getName()).contains(formatCriteria))
							.filter(r -> wantedFormat == null || wantedFormat.equalsIgnoreCase(r.getFormat()))
							.map(r -> new NamedBean<>(r.getName(), r.getName())).toList(), PageRequest.of(0, 10))
					.getContent();
		}
		return Collections.emptyList();
	}

	/**
	 * Map a UI artifact type to the Nexus repository format. Nexus names a few
	 * formats differently (maven → maven2, python → pypi); the rest match. A
	 * blank type (no filter) yields <code>null</code>.
	 */
	private String toNexusFormat(final String type) {
		final var t = StringUtils.trimToNull(type);
		return t == null ? null : NexusFormat.fromType(t).map(NexusFormat::getFormat).orElse(t.toLowerCase());
	}

}
