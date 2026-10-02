/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.nexus;

import jakarta.ws.rs.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.ligoj.app.plugin.nexus.client.NexusRepository;
import org.ligoj.bootstrap.core.curl.AuthCurlProcessor;
import org.ligoj.bootstrap.core.curl.CurlRequest;
import org.ligoj.bootstrap.core.curl.HttpResponseCallback;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Nexus REST API calls of the CREATE mode: hosted repository creation and deletion, and the role mapping granting the
 * repository privileges to groups. Nexus maps a directory group to the role having the same identifier.
 */
@Slf4j
class NexusRepositoryManager {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String REPOSITORIES = "service/rest/v1/repositories/";

	private static final String ROLES = "service/rest/v1/security/roles";

	/**
	 * Store every response body, whatever its status, the caller deciding from the status: the default callback
	 * accepts any status of a response without body and drops the body of the rejected ones.
	 */
	private static final HttpResponseCallback CAPTURE = (final CurlRequest request, final ClassicHttpResponse response) -> {
		final var entity = response.getEntity();
		if (entity != null) {
			try {
				request.setResponse(EntityUtils.toString(entity, StandardCharsets.UTF_8));
			} catch (final ParseException e) {
				log.warn("Unreadable Nexus response of {} {}", request.getMethod(), request.getUrl());
			}
		}
		log.info("{} {} {}", response.getCode(), request.getMethod(), request.getUrl());
		return true;
	};

	private final String baseUrl;
	private final String user;
	private final String password;
	private final ResponseWarnings.Sink warnings;

	NexusRepositoryManager(final String url, final String user, final String password, final ResponseWarnings.Sink warnings) {
		this.baseUrl = Strings.CS.appendIfMissing(url, "/");
		this.user = user;
		this.password = StringUtils.trimToEmpty(password);
		this.warnings = warnings;
	}

	/**
	 * Execute a call and return the request holding the status and the response body. A missing answer, or an
	 * authentication or authorization failure, is an error whatever the call.
	 */
	private CurlRequest call(final String method, final String path, final String body) {
		final var request = body == null ? new CurlRequest(method, baseUrl + path, null)
				: new CurlRequest(method, baseUrl + path, body, "Content-Type:application/json");
		request.setSaveResponse(true);
		try (var processor = new AuthCurlProcessor(user, password, CAPTURE)) {
			processor.process(request);
		}
		if (request.getStatus() == 0) {
			// No answer at all: unreachable, or gave up after the throttling retries
			throw new BusinessException("nexus-no-response", baseUrl);
		}
		if (request.getStatus() == 401 || request.getStatus() == 403) {
			// Wrong node credentials or missing privileges: stop at once, Nexus throttles the repeated failed logins
			throw new BusinessException("nexus-access-denied", user, request.getStatus());
		}
		return request;
	}

	private static boolean isOk(final CurlRequest request) {
		return request.getStatus() >= 200 && request.getStatus() < 300;
	}

	private static String encode(final String segment) {
		return UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
	}

	/**
	 * Human message of a rejected call: the messages of the Nexus validation errors, else the raw body.
	 */
	private static String reason(final CurlRequest request) {
		final var body = StringUtils.trimToEmpty(request.getResponse());
		try {
			final var json = MAPPER.readTree(body);
			if (json.isArray()) {
				final var messages = StreamSupport.stream(json.spliterator(), false).map(e -> e.path("message").asString(""))
						.filter(StringUtils::isNotBlank).collect(Collectors.joining(", "));
				if (!messages.isEmpty()) {
					return messages;
				}
			}
		} catch (final JacksonException e) {
			// Not JSON, use the raw body
		}
		return StringUtils.defaultIfBlank(StringUtils.abbreviate(body, 300), "HTTP " + request.getStatus());
	}

	/**
	 * Find a repository by name.
	 *
	 * @param name The repository name.
	 * @return The repository, <code>null</code> when it does not exist.
	 */
	NexusRepository findRepository(final String name) {
		final var request = call(HttpMethod.GET, REPOSITORIES + encode(name), null);
		if (request.getStatus() == 404 || !isOk(request) || StringUtils.isBlank(request.getResponse())) {
			return null;
		}
		return MAPPER.readValue(request.getResponse(), NexusRepository.class);
	}

	/**
	 * Create a hosted repository.
	 *
	 * @param format  The repository format.
	 * @param payload The creation payload, holding the name.
	 */
	void createRepository(final NexusFormat format, final ObjectNode payload) {
		final var name = payload.path("name").asString();
		final var request = call(HttpMethod.POST, REPOSITORIES + format.getApi() + "/hosted", payload.toString());
		if (!isOk(request)) {
			throw new BusinessException("nexus-create-failed", name, reason(request));
		}
		log.info("Nexus {} repository {} created", format.getFormat(), name);
	}

	/**
	 * Delete a repository. Not blocking: a missing repository or a failed deletion is reported as a warning.
	 *
	 * @param name The repository name.
	 */
	void deleteRepository(final String name) {
		if (findRepository(name) == null) {
			warnings.warn("nexus-delete-registry-missing", ResponseWarnings.parameters("registry", name));
		} else if (!isOk(call(HttpMethod.DELETE, REPOSITORIES + encode(name), null))) {
			warnings.warn("nexus-delete-registry-failed", ResponseWarnings.parameters("registry", name));
		}
	}

	/**
	 * Privileges granted by a role definition on a repository.
	 */
	static List<String> privileges(final NexusFormat format, final String name, final NexusRole role) {
		final var result = new ArrayList<String>();
		addPrivileges(result, "view", format, name, role.getViewPermissions());
		addPrivileges(result, "admin", format, name, role.getAdminPermissions());
		return result;
	}

	private static void addPrivileges(final List<String> result, final String kind, final NexusFormat format, final String name,
			final List<String> actions) {
		if (actions != null) {
			actions.stream().map(String::trim).forEach(a -> result.add("nx-repository-" + kind + "-" + format.getFormat() + "-" + name + "-" + a));
		}
	}

	/**
	 * Grant the repository privileges to each group, through the role named like the group: created when missing,
	 * otherwise completed while keeping its other privileges and roles.
	 *
	 * @param format The repository format.
	 * @param name   The repository name.
	 * @param roles  The role mapping, keyed by group.
	 */
	void grant(final NexusFormat format, final String name, final Map<String, NexusRole> roles) {
		roles.forEach((group, role) -> {
			final var privileges = privileges(format, name, role);
			final var existing = findRole(group);
			final CurlRequest request;
			if (existing == null) {
				final var created = MAPPER.createObjectNode().put("id", group).put("source", "default").put("name", group)
						.put("description", "Ligoj: permissions of group " + group).put("readOnly", false);
				privileges.forEach(created.putArray("privileges")::add);
				created.putArray("roles");
				request = call(HttpMethod.POST, ROLES, created.toString());
			} else {
				final var merged = new LinkedHashSet<>(texts(existing.path("privileges")));
				merged.addAll(privileges);
				final var array = existing.putArray("privileges");
				merged.forEach(array::add);
				request = call(HttpMethod.PUT, ROLES + "/" + encode(group), existing.toString());
			}
			if (!isOk(request)) {
				throw new BusinessException("nexus-role-failed", group, reason(request));
			}
		});
	}

	/**
	 * Remove the privileges of the repository from the roles of the groups. A role left without privileges nor
	 * roles is deleted. Not blocking: a failure is reported as a warning.
	 *
	 * @param format The repository format.
	 * @param name   The repository name.
	 * @param groups The groups of the role mapping.
	 */
	void revoke(final NexusFormat format, final String name, final Iterable<String> groups) {
		final var view = "nx-repository-view-" + format.getFormat() + "-" + name + "-";
		final var admin = "nx-repository-admin-" + format.getFormat() + "-" + name + "-";
		for (final var group : groups) {
			final var existing = findRole(group);
			if (existing == null) {
				continue;
			}
			final var privileges = texts(existing.path("privileges"));
			// An action never contains "-": keep the privileges of another repository sharing this name prefix
			final var kept = privileges.stream().filter(p -> !isRepositoryPrivilege(p, view) && !isRepositoryPrivilege(p, admin)).toList();
			final CurlRequest request;
			if (kept.isEmpty() && texts(existing.path("roles")).isEmpty()) {
				request = call(HttpMethod.DELETE, ROLES + "/" + encode(group), null);
			} else if (kept.size() < privileges.size()) {
				final var array = existing.putArray("privileges");
				kept.forEach(array::add);
				request = call(HttpMethod.PUT, ROLES + "/" + encode(group), existing.toString());
			} else {
				continue;
			}
			if (!isOk(request)) {
				warnings.warn("nexus-delete-role-failed", ResponseWarnings.parameters("role", group, "registry", name));
			}
		}
	}

	private static boolean isRepositoryPrivilege(final String privilege, final String prefix) {
		return privilege.startsWith(prefix) && !privilege.substring(prefix.length()).contains("-");
	}

	private ObjectNode findRole(final String group) {
		final var request = call(HttpMethod.GET, ROLES + "/" + encode(group), null);
		if (!isOk(request) || StringUtils.isBlank(request.getResponse())) {
			return null;
		}
		return (ObjectNode) MAPPER.readTree(request.getResponse());
	}

	private static List<String> texts(final JsonNode array) {
		return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
	}
}
