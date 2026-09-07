package com.paicli.change;

import java.net.URI;
import java.nio.file.Path;

/** Common startup-validation view. Provider-specific credentials remain inside their settings records. */
public interface RemoteScmSettings {
    URI baseUrl();
    Path repository();
    String baseRef();
    String remote();
    String provider();
    String repositoryIdentity();
    String expectedRemoteHost();
}
