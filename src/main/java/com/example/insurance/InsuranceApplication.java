package com.example.insurance;

import jakarta.annotation.security.DeclareRoles;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import org.eclipse.microprofile.auth.LoginConfig;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.security.SecuritySchemes;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.annotations.tags.Tags;

/**
 * JAX-RS Application root for insurance-app.
 *
 * The {@link OpenAPIDefinition} below replaces the generic "Generated API /
 * 1.0" stub that Liberty's mpOpenAPI feature emits by default. The Liberty
 * runtime merges these annotation values with the auto-generated {@code paths}
 * tree built from every {@code @Path} resource on the classpath, producing
 * the spec served at {@code /openapi} (YAML) and {@code /openapi?format=json}.
 *
 * The {@code mpJwt} security scheme is declared once here so individual
 * resources don't each have to repeat it; Schemathesis and other OpenAPI
 * consumers pick it up automatically when they see {@code @RolesAllowed} on
 * a method (mpOpenAPI emits a {@code security: - mpJwt: []} requirement
 * against that operation).
 */
@LoginConfig(authMethod = "MP-JWT", realmName = "insurance-app")
@DeclareRoles({"APPLICATION", "customer", "agent", "admin"})
@ApplicationPath("/api")
@OpenAPIDefinition(
        info = @Info(
                title = "Insurance App API",
                version = "0.1.0",
                description = "Jakarta EE 10 + MicroProfile 6.1 backend for the insurance teaching application. "
                        + "Exposes quote, policy, payment, claim, audit, search, and report resources. "
                        + "All /api/* endpoints (except /api/auth/token and /api/ping) require a "
                        + "WSO2 IS-minted JWT in the Authorization header.",
                contact = @Contact(name = "insurance-app maintainers", url = "https://github.com/zeshaq/insurance-app"),
                license = @License(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")
        ),
        servers = {
                @Server(url = "http://localhost:9080", description = "Local Liberty container"),
                @Server(url = "https://localhost:9443", description = "Local Liberty container (TLS)")
        },
        tags = {
                @Tag(name = "quote",   description = "Premium quote lifecycle"),
                @Tag(name = "policy",  description = "Bound policy management"),
                @Tag(name = "payment", description = "Payment capture + refund"),
                @Tag(name = "claim",   description = "Claim filing, review, settlement"),
                @Tag(name = "audit",   description = "Cross-entity audit log queries"),
                @Tag(name = "search",  description = "OpenSearch-backed cross-entity search"),
                @Tag(name = "report",  description = "Aggregated reporting snapshots"),
                @Tag(name = "auth",    description = "Dev JWT mint for the GUI (no production analogue)"),
                @Tag(name = "ping",    description = "Liveness probe")
        },
        security = @SecurityRequirement(name = "mpJwt")
)
@SecuritySchemes({
        @SecurityScheme(
                securitySchemeName = "mpJwt",
                type = SecuritySchemeType.HTTP,
                scheme = "bearer",
                bearerFormat = "JWT",
                description = "WSO2 IS-minted JWT. Fetch one from GET /api/auth/token (dev only)."
        )
})
public class InsuranceApplication extends Application {
}
