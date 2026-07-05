package org.ligoj.app.plugin.registry.nexus.client;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * A page of the Nexus components listing
 * (<code>/service/rest/v1/components?repository=…</code>). Only the item count
 * and the pagination token are needed to compute the total component count.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NexusComponentPage {

	/**
	 * The components of this page (only their count is used).
	 */
	private List<Object> items = new ArrayList<>();

	/**
	 * Continuation token of the next page, or <code>null</code> on the last page.
	 */
	private String continuationToken;

}
