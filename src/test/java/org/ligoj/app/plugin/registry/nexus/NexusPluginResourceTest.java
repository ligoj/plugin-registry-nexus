package org.ligoj.app.plugin.registry.nexus;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.MatcherUtil;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link NexusPluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class NexusPluginResourceTest extends AbstractServerTest {

	@Autowired
	private NexusPluginResource resource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	protected int subscription;

	@BeforeEach
	void prepareData() throws IOException {
		persistEntities("csv",
				new Class<?>[] { Node.class, Parameter.class, Project.class, Subscription.class, ParameterValue.class },
				StandardCharsets.UTF_8);
		this.subscription = getSubscription("Jupiter", NexusPluginResource.KEY);

		// Coverage only
		Assertions.assertEquals("service:registry:nexus", resource.getKey());
	}

	@Test
	void delete() throws Exception {
		resource.delete(subscription, false);
		em.flush();
		em.clear();
		// No custom data -> nothing to check
	}

	@Test
	void getVersion() throws Exception {
		Assertions.assertNull(resource.getVersion(subscription));
	}

	@Test
	void getLastVersion() throws Exception {
		Assertions.assertNull(resource.getLastVersion());
	}

	@Test
	void checkStatus() throws Exception {
		httpServer.stubFor(get(urlPathEqualTo("/service/rest/v1/repositories"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("[]")));
		httpServer.start();
		Assertions.assertTrue(resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
	}

	@Test
	void checkStatusFailed() {
		httpServer.stubFor(get(urlPathEqualTo("/service/rest/v1/repositories"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_UNAUTHORIZED)));
		httpServer.start();
		Assertions.assertFalse(resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
	}

	@Test
	void link() throws Exception {
		prepareMockRepository();
		resource.link(this.subscription);
		// Nothing to validate but the absence of exception
	}

	@Test
	void linkNotFound() {
		httpServer.stubFor(get(urlPathEqualTo("/service/rest/v1/repositories/maven-releases"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.start();
		MatcherUtil.assertThrows(
				Assertions.assertThrows(ValidationJsonException.class, () -> resource.link(this.subscription)),
				"service:registry:nexus:registry", "nexus-registry");
	}

	@Test
	void linkBlank() {
		httpServer.stubFor(get(urlPathEqualTo("/service/rest/v1/repositories/maven-releases"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("")));
		httpServer.start();
		MatcherUtil.assertThrows(
				Assertions.assertThrows(ValidationJsonException.class, () -> resource.link(this.subscription)),
				"service:registry:nexus:registry", "nexus-registry");
	}

	@Test
	void checkSubscriptionStatus() throws IOException {
		prepareMockRepository();
		prepareMockComponents();
		final var status = resource.checkSubscriptionStatus(subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(status.getStatus().isUp());
		Assertions.assertEquals("maven2", status.getData().get("format"));
		Assertions.assertEquals("hosted", status.getData().get("type"));
		// 3 components on the first page + 2 on the second (continuation token)
		Assertions.assertEquals(5, status.getData().get("components"));
	}

	@Test
	void findAllByName() throws IOException {
		prepareMockRepositories();
		final var repositories = resource.findAllByName("service:registry:nexus:dig", "maven");
		Assertions.assertEquals(1, repositories.size());
		Assertions.assertEquals("maven-releases", repositories.getFirst().getId());
		Assertions.assertEquals("maven-releases", repositories.getFirst().getName());
	}

	@Test
	void findAllByNameNoListing() throws IOException {
		httpServer.start();
		final var repositories = resource.findAllByName("service:registry:nexus:dig", "maven");
		Assertions.assertEquals(0, repositories.size());
	}

	private void prepareMockRepository() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/service/rest/v1/repositories/maven-releases")).willReturn(aResponse()
				.withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/registry/nexus/repository.json").getInputStream(),
						StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private void prepareMockRepositories() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/service/rest/v1/repositories")).willReturn(aResponse()
				.withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/registry/nexus/repositories.json").getInputStream(),
						StandardCharsets.UTF_8))));
		httpServer.start();
	}

	/**
	 * Two-pages components listing: the first page carries a continuation token
	 * pointing to the (last) second page, exercising the paging loop.
	 */
	private void prepareMockComponents() {
		httpServer.stubFor(get(urlPathEqualTo("/service/rest/v1/components"))
				.withQueryParam("continuationToken", absent()).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody("{\"items\":[{},{},{}],\"continuationToken\":\"TOKEN2\"}")));
		httpServer.stubFor(get(urlPathEqualTo("/service/rest/v1/components"))
				.withQueryParam("continuationToken", equalTo("TOKEN2")).willReturn(aResponse()
						.withStatus(HttpStatus.SC_OK).withBody("{\"items\":[{},{}],\"continuationToken\":null}")));
		httpServer.start();
	}

}
