package com.example.insurance;

import jakarta.annotation.security.DeclareRoles;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import org.eclipse.microprofile.auth.LoginConfig;

@LoginConfig(authMethod = "MP-JWT", realmName = "insurance-app")
@DeclareRoles({"APPLICATION", "customer", "agent", "admin"})
@ApplicationPath("/api")
public class InsuranceApplication extends Application {
}
