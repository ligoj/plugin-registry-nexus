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
 * exists for every repository format. A content selector may restrict the group to a part of the content.
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

	/**
	 * Optional content selector restricting the group to the matching content of the repository.
	 */
	@JsonProperty("content-selector")
	private ContentSelector contentSelector;

	/**
	 * Content selector of a group: a Nexus content selector and a privilege of the same name,
	 * {@code <repository>-<group>}, of type {@code repository-content-selector} bound to the repository.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ContentSelector {

		/**
		 * Content selector expression (CSEL), e.g. {@code format == "maven2" and path =^ "/org"}.
		 */
		private String expression;

		/**
		 * Repository actions granted on the selected content, among the role actions.
		 */
		private List<String> permissions;
	}
}
