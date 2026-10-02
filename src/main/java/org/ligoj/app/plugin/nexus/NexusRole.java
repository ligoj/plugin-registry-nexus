/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.nexus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Permissions granted to a group on the subscription repository, keyed by the group name in the role mapping. Each
 * permission is a repository action ({@code browse}, {@code read}, {@code edit}, {@code add}, {@code delete} or
 * {@code *}) turned into the Nexus privilege {@code nx-repository-<view|admin>-<format>-<repository>-<action>}, which
 * exists for every repository format.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NexusRole {

	/**
	 * Content actions: privileges {@code nx-repository-view-*}.
	 */
	@JsonProperty("view-permissions")
	private List<String> viewPermissions;

	/**
	 * Repository administration actions: privileges {@code nx-repository-admin-*}.
	 */
	@JsonProperty("admin-permissions")
	private List<String> adminPermissions;
}
