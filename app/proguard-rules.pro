# ML Kit discovers these registrars by manifest name and calls their no-arg
# constructors through reflection. Keeping only the class name is insufficient
# with R8 full mode: the published APK must retain the constructors too.
-keep,allowoptimization class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
