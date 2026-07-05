package org.ligoj.app.plugin.registry.nexus;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.plugin.registry.RegistryResource;
import org.ligoj.app.plugin.registry.RegistryServicePlugin;
import org.ligoj.app.plugin.registry.nexus.client.NexusComponentPage;
import org.ligoj.app.plugin.registry.nexus.client.NexusRepository;
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sonatype Nexus artifact registry resource. Nexus is multi-format, so the
 * artifact type is a real choice (docker, maven, nuget, npm, python).
 */
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

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private InMemoryPagination inMemoryPagination;

	@Override
	public String getKey() {
		return KEY;
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
		// Node validation: authenticated call to the repositories endpoint.
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
	private NexusRepository validateRegistry(final Map<String, String> parameters) throws IOException {
		final var registry = parameters.get(PARAMETER_REGISTRY);
		final var request = new CurlRequest(HttpMethod.GET,
				getBaseUrl(parameters) + "/service/rest/v1/repositories/" + registry, null);
		request.setSaveResponse(true);
		final boolean found;
		try (var processor = newProcessor(parameters)) {
			found = processor.process(request);
		}
		if (!found || StringUtils.isBlank(request.getResponse())) {
			throw new ValidationJsonException(PARAMETER_REGISTRY, "nexus-registry", registry);
		}
		return objectMapper.readValue(request.getResponse(), NexusRepository.class);
	}

	@Override
	public void link(final int subscription) throws IOException {
		validateRegistry(subscriptionResource.getParameters(subscription));
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final Map<String, String> parameters) throws IOException {
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
	 * @throws IOException When a Nexus response cannot be read.
	 */
	private int countComponents(final Map<String, String> parameters, final String registry) throws IOException {
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
	 * @return The matching repository names.
	 * @throws IOException When the Nexus response cannot be read.
	 */
	@GET
	@Path("{node}/{criteria}")
	public List<NamedBean<String>> findAllByName(@PathParam("node") final String node,
			@PathParam("criteria") final String criteria) throws IOException {
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
					new TypeReference<List<NexusRepository>>() {
						// Nothing to extend
					});
			final var format = new NormalizeFormat();
			final var formatCriteria = format.format(criteria);
			return inMemoryPagination
					.newPage(repositories.stream().filter(r -> format.format(r.getName()).contains(formatCriteria))
							.map(r -> new NamedBean<>(r.getName(), r.getName())).toList(), PageRequest.of(0, 10))
					.getContent();
		}
		return Collections.emptyList();
	}

}
