package com.pixelmc.pixelmcmanager.compat;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;

/** Optional MCA 7.x lifecycle repair. Called only after ServerStoppedEvent. */
public final class BackgroundExecutorShutdown {
    private BackgroundExecutorShutdown() {}

    public static boolean closeMca(ClassLoader loader) throws ReflectiveOperationException {
        try {
            return closeField(Class.forName("forge.net.mca.MCA", false, loader), "executorService");
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    static boolean closeField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getField(name);
        Object value = field.get(null);
        if (!(value instanceof ExecutorService executor)) return false;
        // Drain already submitted work. Never interrupt tasks or block the game thread.
        executor.shutdown();
        return true;
    }
}
