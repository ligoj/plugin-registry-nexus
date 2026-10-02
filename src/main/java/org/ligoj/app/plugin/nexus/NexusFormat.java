/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.nexus;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Artifact types of the {@value NexusPluginResource#PARAMETER_TYPE} parameter, with the Nexus settings of the hosted
 * repository created in CREATE mode. The declaration order is the order of the seeded SELECT options: a subscription
 * may persist the option index, so a new type is always appended.
 */
@Getter
public enum NexusFormat {

	DOCKER("docker", "docker", "ALLOW", "{\"docker\":{\"v1Enabled\":false,\"forceBasicAuth\":true}}"),

	MAVEN("maven", "maven2", "ALLOW_ONCE", "{\"maven\":{\"versionPolicy\":\"RELEASE\",\"layoutPolicy\":\"STRICT\",\"contentDisposition\":\"INLINE\"}}"),

	NUGET("nuget", "nuget", "ALLOW_ONCE", "{}"),

	NPM("npm", "npm", "ALLOW_ONCE", "{}"),

	PYTHON("pypi", "pypi", "ALLOW_ONCE", "{}"),

	YUM("yum", "yum", "ALLOW_ONCE", "{\"yum\":{\"repodataDepth\":0,\"deployPolicy\":\"STRICT\"}}"),

	/**
	 * APT needs a distribution and a GPG key pair to sign the metadata: no usable default.
	 */
	APT("apt", "apt", "ALLOW_ONCE", "{}", "/apt/distribution", "/aptSigning/keypair"),

	RAW("raw", "raw", "ALLOW", "{\"raw\":{\"contentDisposition\":\"ATTACHMENT\"}}"),

	HELM("helm", "helm", "ALLOW_ONCE", "{}"),

	RUBYGEMS("rubygems", "rubygems", "ALLOW_ONCE", "{}"),

	R("r", "r", "ALLOW_ONCE", "{}"),

	GITLFS("gitlfs", "gitlfs", "ALLOW", "{}");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Segment of the repository creation API: <code>/service/rest/v1/repositories/{api}/hosted</code>.
	 */
	private final String api;

	/**
	 * Nexus repository format, as returned by the repositories API and used in the repository privilege names.
	 */
	private final String format;

	/**
	 * Default write policy of the hosted repository.
	 */
	private final String writePolicy;

	/**
	 * Format specific default settings, merged over the common ones.
	 */
	private final String defaults;

	/**
	 * JSON pointers of the settings without default, the configuration must provide them.
	 */
	private final List<String> required;

	NexusFormat(final String api, final String format, final String writePolicy, final String defaults, final String... required) {
		this.api = api;
		this.format = format;
		this.writePolicy = writePolicy;
		this.defaults = defaults;
		this.required = List.of(required);
	}

	/**
	 * Artifact type name, as listed in the SELECT parameter.
	 *
	 * @return The lower-case type name.
	 */
	public String getType() {
		return name().toLowerCase();
	}

	/**
	 * The seeded SELECT options, in order.
	 *
	 * @return The JSON array of the type names.
	 */
	public static String typesJson() {
		return Arrays.stream(values()).map(f -> "\"" + f.getType() + "\"").collect(Collectors.joining(",", "[", "]"));
	}

	/**
	 * Resolve a stored artifact type: its name, or the index of the SELECT option.
	 *
	 * @param type The stored value.
	 * @return The format, empty when unknown.
	 */
	public static Optional<NexusFormat> fromType(final String type) {
		final var value = StringUtils.trimToEmpty(type).toLowerCase();
		if (StringUtils.isNumeric(value)) {
			final var index = Integer.parseInt(value);
			return index < values().length ? Optional.of(values()[index]) : Optional.empty();
		}
		return Arrays.stream(values()).filter(f -> f.getType().equals(value)).findFirst();
	}

	/**
	 * Build the creation payload: the common defaults, then the format defaults, then the user configuration, merged
	 * recursively (objects are merged, other values replaced). The repository name always wins.
	 *
	 * @param name          The repository name.
	 * @param configuration The user configuration, may be empty.
	 * @return The payload.
	 */
	public ObjectNode payload(final String name, final ObjectNode configuration) {
		final var payload = (ObjectNode) MAPPER.readTree("""
				{"online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"%s"},
				 "cleanup":{"policyNames":[]},"component":{"proprietaryComponents":false}}""".formatted(writePolicy));
		merge(payload, (ObjectNode) MAPPER.readTree(defaults));
		merge(payload, configuration);
		payload.put("name", name);
		return payload;
	}

	/**
	 * The required settings missing from a payload.
	 *
	 * @param payload The built payload.
	 * @return The first missing setting, as a dotted path, empty when complete.
	 */
	public Optional<String> missing(final JsonNode payload) {
		return required.stream().filter(p -> {
			final var node = payload.at(p);
			return node.isMissingNode() || node.isNull() || StringUtils.isBlank(node.asString());
		}).map(p -> p.substring(1).replace('/', '.')).findFirst();
	}

	private static void merge(final ObjectNode target, final ObjectNode source) {
		source.properties().forEach(e -> {
			final var current = target.get(e.getKey());
			if (current instanceof ObjectNode currentObject && e.getValue() instanceof ObjectNode sourceObject) {
				merge(currentObject, sourceObject);
			} else {
				target.set(e.getKey(), e.getValue());
			}
		});
	}
}
