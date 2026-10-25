package com.pixelmc.pixelmcmanager.config;

/** Restart adapters are discovered through the local supervisor's expiring lease. */
public final class RestartConfig {
    public boolean restartOnModChange = false;
    public int metadataRecheckSeconds = 60;

    public void normalize() {
        if (metadataRecheckSeconds < 30 || metadataRecheckSeconds > 3600) metadataRecheckSeconds = 60;
    }
}
