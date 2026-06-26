package org.ligoj.app.plugin.registry.nexus.client;

import org.ligoj.bootstrap.core.NamedBean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Sonatype Nexus repository model. A Nexus repository is the "registry"
 * hosting the artifacts of a given format.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NexusRepository extends NamedBean<String> {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Repository format: <code>docker</code>, <code>maven2</code>,
	 * <code>nuget</code>, <code>npm</code>, <code>pypi</code>, …
	 */
	private String format;

	/**
	 * Repository type: <code>hosted</code>, <code>proxy</code> or
	 * <code>group</code>.
	 */
	private String type;

	/**
	 * Public repository URL.
	 */
	private String url;

}
