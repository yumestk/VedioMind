package com.example.server.service.external;

import java.net.URI;

public interface ExternalVideoResolver {

    boolean supports(URI uri);

    ResolvedExternalVideo resolve(URI uri);
}
