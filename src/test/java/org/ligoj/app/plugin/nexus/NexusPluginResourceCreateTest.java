/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.nexus;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import jakarta.transaction.Transactional;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.api.SubscriptionMode;
import org.ligoj.app.model.*;
import org.ligoj.app.plugin.nexus.NexusFormat;
import org.ligoj.app.plugin.nexus.NexusPluginResource;
import org.ligoj.app.plugin.nexus.ResponseWarnings;
import org.ligoj.bootstrap.MatcherUtil;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Test class of {@link NexusPluginResource} in CREATE mode: repository creation with its format settings, role
 * mapping, and the remote data deletion.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class NexusPluginResourceCreateTest extends AbstractServerTest {

	private static final String REPOSITORY = "/service/rest/v1/repositories/maven-releases";
	private static final String ROLES = "/service/rest/v1/security/roles";
	private static final String SELECTORS = "/service/rest/v1/security/content-selectors";
	private static final String PRIVILEGES = "/service/rest/v1/security/privileges";

	/**
	 * A role with a content selector, as in the documentation.
	 */
	private static final String ROLES_SELECTOR = """
			{"dev": { "view-permissions": ["browse"],
			  "content-selector": { "permissions": ["browse", "read", "delete"], "expression": "format == \\"maven2\\" and path =^ \\"/org\\"" } } }""";

	/**
	 * The role mapping of the documentation.
	 */
	private static final String ROLES_MAPPING = """
			{
			  "admin": { "view-permissions": ["*"], "admin-permissions": ["*"] },
			  "dev": { "view-permissions": ["browse", "read", "delete", "add"], "admin-permissions": ["browse", "read", "delete"] },
			  "test": { "view-permissions": ["browse"] }
			}""";

	private static final String MAVEN_PAYLOAD = """
			{"name":"maven-releases","online":true,
			 "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"ALLOW_ONCE"},
			 "cleanup":{"policyNames":[]},"component":{"proprietaryComponents":false},
			 "maven":{"versionPolicy":"RELEASE","layoutPolicy":"STRICT","contentDisposition":"INLINE"}}""";

	@Autowired
	private CacheManager cacheManager;

	private NexusPluginResource resource;

	private final List<String> warnings = new ArrayList<>();

	private int subscription;

	@BeforeEach
	void prepareData() throws IOException {
		persistEntities("csv", new Class<?>[] { Node.class, Parameter.class, Project.class, Subscription.class, ParameterValue.class },
				StandardCharsets.UTF_8);
		this.subscription = getSubscription("Jupiter", NexusPluginResource.KEY);
		resource = new NexusPluginResource() {
			@Override
			protected ResponseWarnings.Sink responseWarnings() {
				return (code, parameters) -> warnings.add(code + " " + new TreeMap<>(parameters));
			}
		};
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
	}

	/**
	 * Replace a subscription parameter value.
	 */
	private void setParameter(final String parameter, final String value) {
		em.createQuery("DELETE ParameterValue WHERE parameter.id = :p AND subscription.id = :s").setParameter("p", parameter)
				.setParameter("s", subscription).executeUpdate();
		if (value != null) {
			final var pv = new ParameterValue();
			pv.setParameter(em.find(Parameter.class, parameter));
			pv.setSubscription(em.find(Subscription.class, subscription));
			pv.setData(value);
			em.persist(pv);
		}
		em.flush();
		cacheManager.getCache("subscription-parameters").clear();
	}

	private void stubRepositoryMissing() {
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
	}

	private void stubRoleMissing(final String role) {
		httpServer.stubFor(get(urlEqualTo(ROLES + "/" + role)).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
	}

	private void stubRole(final String role, final String privileges, final String roles) {
		httpServer.stubFor(get(urlEqualTo(ROLES + "/" + role)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"id\":\"" + role + "\",\"source\":\"default\",\"name\":\"" + role + "\",\"description\":\"Existing\",\"readOnly\":false,"
						+ "\"privileges\":" + privileges + ",\"roles\":" + roles + "}")));
	}

	/**
	 * Maven repository created with its defaults, then the role mapping applied: the missing roles are created, an
	 * existing role keeps its other privileges and roles.
	 */
	@Test
	void create() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, ROLES_MAPPING);
		stubRepositoryMissing();
		httpServer.stubFor(post(urlEqualTo("/service/rest/v1/repositories/maven/hosted")).withRequestBody(equalToJson(MAVEN_PAYLOAD))
				.willReturn(aResponse().withStatus(HttpStatus.SC_CREATED)));
		stubRoleMissing("admin");
		httpServer.stubFor(post(urlEqualTo(ROLES)).withRequestBody(equalToJson("""
				{"id":"admin","source":"default","name":"admin","description":"Ligoj: permissions of group admin","readOnly":false,
				 "privileges":["nx-repository-view-maven2-maven-releases-*","nx-repository-admin-maven2-maven-releases-*"],"roles":[]}"""))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		stubRoleMissing("dev");
		httpServer.stubFor(post(urlEqualTo(ROLES)).withRequestBody(matchingJsonPath("$.id", WireMock.equalTo("dev")))
				.withRequestBody(equalToJson("""
						{"id":"dev","source":"default","name":"dev","description":"Ligoj: permissions of group dev","readOnly":false,
						 "privileges":["nx-repository-view-maven2-maven-releases-browse","nx-repository-view-maven2-maven-releases-read",
						 "nx-repository-view-maven2-maven-releases-delete","nx-repository-view-maven2-maven-releases-add",
						 "nx-repository-admin-maven2-maven-releases-browse","nx-repository-admin-maven2-maven-releases-read",
						 "nx-repository-admin-maven2-maven-releases-delete"],"roles":[]}"""))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		stubRole("test", "[\"other-privilege\"]", "[\"other-role\"]");
		httpServer.stubFor(put(urlEqualTo(ROLES + "/test")).withRequestBody(equalToJson("""
				{"id":"test","source":"default","name":"test","description":"Existing","readOnly":false,
				 "privileges":["other-privilege","nx-repository-view-maven2-maven-releases-browse"],"roles":["other-role"]}"""))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.start();

		resource.create(subscription);

		httpServer.verify(1, postRequestedFor(urlEqualTo("/service/rest/v1/repositories/maven/hosted")));
		httpServer.verify(2, postRequestedFor(urlEqualTo(ROLES)));
		httpServer.verify(1, putRequestedFor(urlEqualTo(ROLES + "/test")));
		Assertions.assertEquals(List.of(), warnings);
	}

	/**
	 * The artifact type persisted as the index of the SELECT option (1 = maven), and no role mapping.
	 */
	@Test
	void createTypeIndex() {
		setParameter(NexusPluginResource.PARAMETER_TYPE, "1");
		stubRepositoryMissing();
		httpServer.stubFor(post(urlEqualTo("/service/rest/v1/repositories/maven/hosted")).withRequestBody(equalToJson(MAVEN_PAYLOAD))
				.willReturn(aResponse().withStatus(HttpStatus.SC_CREATED)));
		httpServer.start();

		resource.create(subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo("/service/rest/v1/repositories/maven/hosted")));
		httpServer.verify(0, anyRequestedFor(urlPathMatching(ROLES + ".*")));
	}

	/**
	 * The format settings are merged into the defaults: nested objects are merged, values replaced, the name kept.
	 */
	@Test
	void createYumConfiguration() {
		setParameter(NexusPluginResource.PARAMETER_TYPE, "yum");
		setParameter(NexusPluginResource.PARAMETER_CONFIGURATION,
				"{\"name\":\"ignored\",\"yum\":{\"repodataDepth\":2},\"storage\":{\"writePolicy\":\"ALLOW\"},\"cleanup\":{\"policyNames\":[\"weekly\"]}}");
		stubRepositoryMissing();
		httpServer.stubFor(post(urlEqualTo("/service/rest/v1/repositories/yum/hosted")).withRequestBody(equalToJson("""
				{"name":"maven-releases","online":true,
				 "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"ALLOW"},
				 "cleanup":{"policyNames":["weekly"]},"component":{"proprietaryComponents":false},
				 "yum":{"repodataDepth":2,"deployPolicy":"STRICT"}}"""))
				.willReturn(aResponse().withStatus(HttpStatus.SC_CREATED)));
		httpServer.start();

		resource.create(subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo("/service/rest/v1/repositories/yum/hosted")));
	}

	/**
	 * Python repositories are named "pypi" by Nexus.
	 */
	@Test
	void createPython() {
		setParameter(NexusPluginResource.PARAMETER_TYPE, "python");
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":{\"view-permissions\":[\"read\"]}}");
		stubRepositoryMissing();
		httpServer.stubFor(post(urlEqualTo("/service/rest/v1/repositories/pypi/hosted")).willReturn(aResponse().withStatus(HttpStatus.SC_CREATED)));
		stubRoleMissing("dev");
		httpServer.stubFor(post(urlEqualTo(ROLES))
				.withRequestBody(matchingJsonPath("$.privileges[0]", WireMock.equalTo("nx-repository-view-pypi-maven-releases-read")))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.start();

		resource.create(subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo(ROLES)));
	}

	/**
	 * The JSON parameters are trimmed before being validated and parsed, a blank value is ignored.
	 */
	@Test
	void createTrimmed() {
		setParameter(NexusPluginResource.PARAMETER_TYPE, "yum");
		setParameter(NexusPluginResource.PARAMETER_CONFIGURATION, " \n\t{\"yum\":{\"repodataDepth\":2}}\r\n  ");
		setParameter(NexusPluginResource.PARAMETER_ROLES, "\n  {\"dev\":{\"view-permissions\":[\" read \"]}}\n\n");
		stubRepositoryMissing();
		httpServer.stubFor(post(urlEqualTo("/service/rest/v1/repositories/yum/hosted")).willReturn(aResponse().withStatus(HttpStatus.SC_CREATED)));
		stubRoleMissing("dev");
		httpServer.stubFor(post(urlEqualTo(ROLES))
				.withRequestBody(matchingJsonPath("$.privileges[0]", WireMock.equalTo("nx-repository-view-yum-maven-releases-read")))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.start();

		resource.create(subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo("/service/rest/v1/repositories/yum/hosted"))
				.withRequestBody(matchingJsonPath("$.yum.repodataDepth", WireMock.equalTo("2"))));
		httpServer.verify(1, postRequestedFor(urlEqualTo(ROLES)));

		// Blank values only made of spaces: no settings, no role
		setParameter(NexusPluginResource.PARAMETER_CONFIGURATION, " \n ");
		setParameter(NexusPluginResource.PARAMETER_ROLES, "\t\n");
		resource.create(subscription);
		httpServer.verify(2, postRequestedFor(urlEqualTo("/service/rest/v1/repositories/yum/hosted")));
		httpServer.verify(1, postRequestedFor(urlEqualTo(ROLES)));
	}

	/**
	 * APT repositories need a distribution and a signing key pair: checked before any remote call.
	 */
	@Test
	void createAptMissingSigning() {
		setParameter(NexusPluginResource.PARAMETER_TYPE, "apt");
		setParameter(NexusPluginResource.PARAMETER_CONFIGURATION, "{\"apt\":{\"distribution\":\"bookworm\"}}");
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_CONFIGURATION, "nexus-configuration-required");
		httpServer.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	void createApt() {
		setParameter(NexusPluginResource.PARAMETER_TYPE, "apt");
		setParameter(NexusPluginResource.PARAMETER_CONFIGURATION,
				"{\"apt\":{\"distribution\":\"bookworm\"},\"aptSigning\":{\"keypair\":\"-----BEGIN PGP PRIVATE KEY BLOCK-----\",\"passphrase\":\"p\"}}");
		stubRepositoryMissing();
		httpServer.stubFor(post(urlEqualTo("/service/rest/v1/repositories/apt/hosted"))
				.withRequestBody(matchingJsonPath("$.apt.distribution", WireMock.equalTo("bookworm")))
				.withRequestBody(matchingJsonPath("$.aptSigning.passphrase", WireMock.equalTo("p")))
				.willReturn(aResponse().withStatus(HttpStatus.SC_CREATED)));
		httpServer.start();
		resource.create(subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo("/service/rest/v1/repositories/apt/hosted")));
	}

	@Test
	void createInvalidConfiguration() {
		setParameter(NexusPluginResource.PARAMETER_CONFIGURATION, "[1]");
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_CONFIGURATION, "nexus-configuration-json");
	}

	@Test
	void createInvalidRolesJson() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":");
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_ROLES, "nexus-roles-json");
	}

	/**
	 * A role grants at least one permission, each one among browse, read, edit, add, delete and "*".
	 */
	@Test
	void createInvalidRolesPermission() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":{\"view-permissions\":[\"write\"]}}");
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_ROLES, "nexus-roles-permission");
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":{}}");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_ROLES, "nexus-roles-empty");
	}

	@Test
	void createInvalidName() {
		setParameter(NexusPluginResource.PARAMETER_REGISTRY, "bad name");
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_REGISTRY, "nexus-registry-name");
	}

	/**
	 * Formats Nexus cannot host (proxy only) are rejected.
	 */
	@Test
	void createUnsupportedType() {
		setParameter(NexusPluginResource.PARAMETER_TYPE, "go");
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_TYPE, "nexus-type");
	}

	/**
	 * A hosted repository of the same format already exists: reused with a warning, the roles are still applied.
	 */
	@Test
	void createExisting() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"test\":{\"view-permissions\":[\"browse\"]}}");
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"name\":\"maven-releases\",\"format\":\"maven2\",\"type\":\"hosted\"}")));
		stubRoleMissing("test");
		httpServer.stubFor(post(urlEqualTo(ROLES)).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.start();

		resource.create(subscription);
		httpServer.verify(0, postRequestedFor(urlPathMatching("/service/rest/v1/repositories/.*")));
		httpServer.verify(1, postRequestedFor(urlEqualTo(ROLES)));
		Assertions.assertEquals(List.of("nexus-registry-reused {registry=maven-releases}"), warnings);
	}

	/**
	 * A repository of another format or type already has this name.
	 */
	@Test
	void createExistingOtherFormat() {
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"name\":\"maven-releases\",\"format\":\"npm\",\"type\":\"hosted\"}")));
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_REGISTRY, "nexus-registry-exists");
	}

	/**
	 * Nexus rejects the repository: its own messages are reported.
	 */
	@Test
	void createFailed() {
		stubRepositoryMissing();
		httpServer.stubFor(post(urlEqualTo("/service/rest/v1/repositories/maven/hosted")).willReturn(aResponse()
				.withStatus(HttpStatus.SC_BAD_REQUEST).withBody("[{\"id\":\"PARAMETER storage\",\"message\":\"Blob store missing does not exist\"}]")));
		httpServer.start();
		final var e = Assertions.assertThrows(BusinessException.class, () -> resource.create(subscription));
		Assertions.assertEquals("nexus-create-failed", e.getMessage());
		Assertions.assertEquals(List.of("maven-releases", "Blob store missing does not exist"), List.of(e.getParameters()));
	}

	/**
	 * Wrong node credentials: stop at the first call with an explicit error, without trying the creation (Nexus
	 * throttles the repeated failed logins).
	 */
	@Test
	void createUnauthorized() {
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_UNAUTHORIZED)));
		httpServer.start();
		final var e = Assertions.assertThrows(BusinessException.class, () -> resource.create(subscription));
		Assertions.assertEquals("nexus-access-denied", e.getMessage());
		Assertions.assertEquals(List.of("junit", 401), List.of(e.getParameters()));
		httpServer.verify(0, postRequestedFor(anyUrl()));
	}

	@Test
	void deleteRemoteForbidden() {
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_FORBIDDEN)));
		httpServer.start();
		final var e = Assertions.assertThrows(BusinessException.class, () -> resource.delete(subscription, true));
		Assertions.assertEquals("nexus-access-denied", e.getMessage());
		Assertions.assertEquals(List.of("junit", 403), List.of(e.getParameters()));
	}

	/**
	 * Nexus does not answer: reported as such, not as a rejected creation.
	 */
	@Test
	void createNoResponse() {
		stubRepositoryMissing();
		httpServer.stubFor(post(urlEqualTo("/service/rest/v1/repositories/maven/hosted")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
		httpServer.start();
		final var e = Assertions.assertThrows(BusinessException.class, () -> resource.create(subscription));
		Assertions.assertEquals("nexus-no-response", e.getMessage());
		Assertions.assertEquals(List.of("http://localhost:8120/"), List.of(e.getParameters()));
	}

	@Test
	void createRoleFailed() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":{\"view-permissions\":[\"read\"]}}");
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"name\":\"maven-releases\",\"format\":\"maven2\",\"type\":\"hosted\"}")));
		stubRoleMissing("dev");
		httpServer.stubFor(post(urlEqualTo(ROLES)).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST)
				.withBody("[{\"id\":\"*\",\"message\":\"Privilege missing does not exist\"}]")));
		httpServer.start();
		final var e = Assertions.assertThrows(BusinessException.class, () -> resource.create(subscription));
		Assertions.assertEquals("nexus-role-failed", e.getMessage());
		Assertions.assertEquals(List.of("dev", "Privilege missing does not exist"), List.of(e.getParameters()));
	}

	private void stubExistingRepository() {
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"name\":\"maven-releases\",\"format\":\"maven2\",\"type\":\"hosted\"}")));
	}

	/**
	 * A content selector restricts the group to the matching content: the selector and the privilege of the same name,
	 * bound to the repository, are created, then the privilege is granted through the role of the group. The privilege
	 * format is "*", as stored by the Nexus UI, which shows a privilege having a format as "(All maven2 Repositories)".
	 */
	@Test
	void createContentSelector() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, ROLES_SELECTOR);
		stubExistingRepository();
		httpServer.stubFor(get(urlEqualTo(SELECTORS + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo(SELECTORS)).withRequestBody(equalToJson("""
				{"name":"maven-releases-dev","description":"Ligoj: content of maven-releases for group dev",
				 "expression":"format == \\"maven2\\" and path =^ \\"/org\\""}"""))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.stubFor(get(urlEqualTo(PRIVILEGES + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo(PRIVILEGES + "/repository-content-selector")).withRequestBody(equalToJson("""
				{"name":"maven-releases-dev","description":"Ligoj: content of maven-releases for group dev",
				 "actions":["BROWSE","READ","DELETE"],"format":"*","repository":"maven-releases","contentSelector":"maven-releases-dev"}"""))
				.willReturn(aResponse().withStatus(HttpStatus.SC_CREATED)));
		stubRoleMissing("dev");
		httpServer.stubFor(post(urlEqualTo(ROLES))
				.withRequestBody(matchingJsonPath("$.privileges", WireMock.equalToJson("[\"nx-repository-view-maven2-maven-releases-browse\",\"maven-releases-dev\"]")))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.start();

		resource.create(subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo(SELECTORS)));
		httpServer.verify(1, postRequestedFor(urlEqualTo(PRIVILEGES + "/repository-content-selector")));
		httpServer.verify(1, postRequestedFor(urlEqualTo(ROLES)));
	}

	/**
	 * The selector and its privilege already exist (a new subscription on the same repository): updated. The group
	 * name is made a valid Nexus name, "*" is the "ALL" action, and a selector alone is enough for a role.
	 */
	@Test
	void createContentSelectorUpdate() {
		setParameter(NexusPluginResource.PARAMETER_ROLES,
				"{\"team a\":{\"content-selector\":{\"permissions\":[\"*\"],\"expression\":\" path =^ \\\"/com\\\" \"}}}");
		stubExistingRepository();
		httpServer.stubFor(get(urlEqualTo(SELECTORS + "/maven-releases-team-a")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"name\":\"maven-releases-team-a\",\"type\":\"csel\",\"expression\":\"old\"}")));
		httpServer.stubFor(put(urlEqualTo(SELECTORS + "/maven-releases-team-a")).withRequestBody(equalToJson("""
				{"description":"Ligoj: content of maven-releases for group team a","expression":"path =^ \\"/com\\""}"""))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.stubFor(get(urlEqualTo(PRIVILEGES + "/maven-releases-team-a")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.stubFor(put(urlEqualTo(PRIVILEGES + "/repository-content-selector/maven-releases-team-a"))
				.withRequestBody(matchingJsonPath("$.actions", WireMock.equalToJson("[\"ALL\"]")))
				.withRequestBody(matchingJsonPath("$.format", WireMock.equalTo("*")))
				.withRequestBody(matchingJsonPath("$.repository", WireMock.equalTo("maven-releases")))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.stubFor(get(urlEqualTo(ROLES + "/team%20a")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo(ROLES)).withRequestBody(matchingJsonPath("$.privileges", WireMock.equalToJson("[\"maven-releases-team-a\"]")))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.start();

		resource.create(subscription);
		httpServer.verify(1, putRequestedFor(urlEqualTo(SELECTORS + "/maven-releases-team-a")));
		httpServer.verify(1, putRequestedFor(urlEqualTo(PRIVILEGES + "/repository-content-selector/maven-releases-team-a")));
		httpServer.verify(0, postRequestedFor(urlEqualTo(SELECTORS)));
	}

	/**
	 * A content selector needs an expression and at least one known permission.
	 */
	@Test
	void createContentSelectorInvalid() {
		httpServer.start();
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":{\"content-selector\":{\"permissions\":[\"read\"],\"expression\":\" \"}}}");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_ROLES, "nexus-roles-selector");
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":{\"content-selector\":{\"permissions\":[],\"expression\":\"path =^ \\\"/org\\\"\"}}}");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_ROLES, "nexus-roles-selector");
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":{\"content-selector\":{\"permissions\":[\"write\"],\"expression\":\"path =^ \\\"/org\\\"\"}}}");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription)),
				NexusPluginResource.PARAMETER_ROLES, "nexus-roles-permission");
		httpServer.verify(0, anyRequestedFor(anyUrl()));
	}

	/**
	 * Nexus rejects the expression: its message is reported, even as a single error object.
	 */
	@Test
	void createContentSelectorFailed() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, ROLES_SELECTOR);
		stubExistingRepository();
		httpServer.stubFor(get(urlEqualTo(SELECTORS + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo(SELECTORS)).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST)
				.withBody("{\"id\":\"*\",\"message\":\"Invalid CSEL: unexpected token\"}")));
		httpServer.start();
		final var e = Assertions.assertThrows(BusinessException.class, () -> resource.create(subscription));
		Assertions.assertEquals("nexus-selector-failed", e.getMessage());
		Assertions.assertEquals(List.of("maven-releases-dev", "Invalid CSEL: unexpected token"), List.of(e.getParameters()));
	}

	@Test
	void createContentSelectorPrivilegeFailed() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, ROLES_SELECTOR);
		stubExistingRepository();
		httpServer.stubFor(get(urlEqualTo(SELECTORS + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo(SELECTORS)).willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.stubFor(get(urlEqualTo(PRIVILEGES + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo(PRIVILEGES + "/repository-content-selector")).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST)
				.withBody("[{\"id\":\"PARAMETER name\",\"message\":\"Name is already used\"}]")));
		httpServer.start();
		final var e = Assertions.assertThrows(BusinessException.class, () -> resource.create(subscription));
		Assertions.assertEquals("nexus-privilege-failed", e.getMessage());
		Assertions.assertEquals(List.of("maven-releases-dev", "Name is already used"), List.of(e.getParameters()));
	}

	/**
	 * Remote data deletion with a content selector: the privilege is removed from the role, then the privilege and
	 * the selector are deleted, then the repository.
	 */
	@Test
	void deleteRemoteContentSelector() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, ROLES_SELECTOR);
		stubRole("dev", "[\"nx-repository-view-maven2-maven-releases-browse\",\"maven-releases-dev\",\"other\"]", "[]");
		httpServer.stubFor(put(urlEqualTo(ROLES + "/dev")).withRequestBody(matchingJsonPath("$.privileges", WireMock.equalToJson("[\"other\"]")))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.stubFor(delete(urlEqualTo(PRIVILEGES + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.stubFor(delete(urlEqualTo(SELECTORS + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		stubExistingRepository();
		httpServer.stubFor(delete(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.start();

		resource.delete(subscription, true);
		httpServer.verify(1, putRequestedFor(urlEqualTo(ROLES + "/dev")));
		httpServer.verify(1, deleteRequestedFor(urlEqualTo(PRIVILEGES + "/maven-releases-dev")));
		httpServer.verify(1, deleteRequestedFor(urlEqualTo(SELECTORS + "/maven-releases-dev")));
		httpServer.verify(1, deleteRequestedFor(urlEqualTo(REPOSITORY)));
		Assertions.assertEquals(List.of(), warnings);
	}

	/**
	 * Partially deleted content selector: a missing privilege or selector is skipped, a failure is a warning.
	 */
	@Test
	void deleteRemoteContentSelectorIssues() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, ROLES_SELECTOR);
		stubRoleMissing("dev");
		httpServer.stubFor(delete(urlEqualTo(PRIVILEGES + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(delete(urlEqualTo(SELECTORS + "/maven-releases-dev")).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST)
				.withBody("{\"id\":\"*\",\"message\":\"Content selector is in use\"}")));
		stubExistingRepository();
		httpServer.stubFor(delete(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.start();

		resource.delete(subscription, true);
		Assertions.assertEquals(List.of("nexus-delete-selector-failed {registry=maven-releases, selector=maven-releases-dev}"), warnings);
		httpServer.verify(1, deleteRequestedFor(urlEqualTo(REPOSITORY)));
	}

	/**
	 * Remote data deletion: the privileges of this repository are removed from the mapped roles (a role left empty is
	 * deleted), then the repository.
	 */
	@Test
	void deleteRemote() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, ROLES_MAPPING);
		stubRole("admin", "[\"nx-repository-view-maven2-maven-releases-*\",\"nx-repository-admin-maven2-maven-releases-*\"]", "[]");
		httpServer.stubFor(delete(urlEqualTo(ROLES + "/admin")).willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		stubRole("dev", "[\"nx-repository-view-maven2-maven-releases-read\",\"nx-repository-view-maven2-maven-releases-other-browse\",\"other\"]", "[]");
		httpServer.stubFor(put(urlEqualTo(ROLES + "/dev"))
				.withRequestBody(matchingJsonPath("$.privileges", WireMock.equalToJson("[\"nx-repository-view-maven2-maven-releases-other-browse\",\"other\"]")))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		stubRoleMissing("test");
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"name\":\"maven-releases\",\"format\":\"maven2\",\"type\":\"hosted\"}")));
		httpServer.stubFor(delete(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.start();

		resource.delete(subscription, true);
		httpServer.verify(1, deleteRequestedFor(urlEqualTo(ROLES + "/admin")));
		httpServer.verify(1, putRequestedFor(urlEqualTo(ROLES + "/dev")));
		httpServer.verify(1, deleteRequestedFor(urlEqualTo(REPOSITORY)));
		Assertions.assertEquals(List.of(), warnings);
	}

	/**
	 * Partially deleted remote data: the deletion goes on, each issue is a warning.
	 */
	@Test
	void deleteRemoteIssues() {
		setParameter(NexusPluginResource.PARAMETER_ROLES, "{\"dev\":{\"view-permissions\":[\"read\"]}}");
		stubRole("dev", "[\"nx-repository-view-maven2-maven-releases-read\",\"other\"]", "[]");
		httpServer.stubFor(put(urlEqualTo(ROLES + "/dev")).willReturn(aResponse().withStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR)));
		stubRepositoryMissing();
		httpServer.start();

		resource.delete(subscription, true);
		Assertions.assertEquals(List.of("nexus-delete-role-failed {registry=maven-releases, role=dev}",
				"nexus-delete-registry-missing {registry=maven-releases}"), warnings);
	}

	@Test
	void deleteRemoteFailed() {
		httpServer.stubFor(get(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"name\":\"maven-releases\",\"format\":\"maven2\",\"type\":\"hosted\"}")));
		httpServer.stubFor(delete(urlEqualTo(REPOSITORY)).willReturn(aResponse().withStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR)));
		httpServer.start();
		resource.delete(subscription, true);
		Assertions.assertEquals(List.of("nexus-delete-registry-failed {registry=maven-releases}"), warnings);
	}

	@Test
	void deleteLocal() {
		httpServer.start();
		resource.delete(subscription, false);
		httpServer.verify(0, anyRequestedFor(anyUrl()));
	}

	/**
	 * Upgrade: the tool node accepts the creation and the type list gets the new formats.
	 */
	@Test
	void update() {
		final var type = em.find(Parameter.class, NexusPluginResource.PARAMETER_TYPE);
		type.setData("[\"docker\",\"maven\",\"nuget\",\"npm\",\"python\"]");
		em.find(Node.class, NexusPluginResource.KEY).setMode(SubscriptionMode.LINK);
		em.flush();
		resource.update("1.0.0");
		em.flush();
		em.clear();
		Assertions.assertEquals(SubscriptionMode.ALL, em.find(Node.class, NexusPluginResource.KEY).getMode());
		Assertions.assertEquals(NexusFormat.typesJson(), em.find(Parameter.class, NexusPluginResource.PARAMETER_TYPE).getData());
	}

	/**
	 * The SELECT options are persisted by index: the seeded list must keep the formats order.
	 */
	@Test
	void typesMatchSeed() throws IOException {
		final var csv = Files.readAllLines(new ClassPathResource("csv/parameter.csv").getFile().toPath());
		final var line = csv.stream().filter(l -> l.startsWith(NexusPluginResource.PARAMETER_TYPE + ";")).findFirst().orElseThrow();
		Assertions.assertEquals(NexusFormat.typesJson(), line.split(";")[2]);
		Assertions.assertTrue(NexusFormat.typesJson().startsWith("[\"docker\",\"maven\",\"nuget\",\"npm\",\"python\","));
	}
}
