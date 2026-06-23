package me.cortex.nvidium.sodiumCompat;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;

// Facade over the Iris state Nvidium cares about. All accessors are null-safe: they return a
// benign default when Iris is absent or its API shifts, so callers never need to guard.
// The isShaderPackInUse/isRenderingShadowPass pair is the S1 scaffolding for the staged Iris
// integration described in docs/IRIS_INTEGRATION.md.
public class IrisCheck {
    public static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

    public static boolean checkIrisShaders() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    // Reads as "should disable" but returns whether Nvidium is ALLOWED to run: true unless an
    // Iris shaderpack is active (Nvidium's mesh-shader terrain can't feed Iris' shader pipeline,
    // see docs/IRIS_INTEGRATION.md). Kept under this name because MixinRenderSectionManager calls it.
    public static boolean checkIrisShouldDisable() {
        return !isShaderPackInUse();
    }

    /** True when an Iris shaderpack is currently active. False if Iris isn't loaded. */
    public static boolean isShaderPackInUse() {
        if (!IRIS_LOADED) return false;
        try {
            return IrisApi.getInstance().isShaderPackInUse();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True while Iris is rendering its shadow pass (terrain from the light's POV). */
    public static boolean isRenderingShadowPass() {
        if (!IRIS_LOADED) return false;
        try {
            return IrisApi.getInstance().isRenderingShadowPass();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
