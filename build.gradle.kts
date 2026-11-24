import java.nio.charset.Charset
import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins {
    java
    application
}

group = "rabbitrace"
version = "0.1.0"

// The toolchain is declared by version, and Gradle finds the install by path.
// The wrapper refuses to start on a JVM older than 17.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("rabbitrace.Main")
}

// ---------------------------------------------------------------------------
// Encoding. javac on Windows defaults to the platform charset, which mangles
// the Spanish interface text. Every javac invocation sets UTF-8 explicitly, and
// the build fails — not warns — without it.
// ---------------------------------------------------------------------------
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    doFirst {
        val encoding = options.encoding
        if (encoding != "UTF-8") {
            throw GradleException(
                "javac for $path runs with encoding '${encoding ?: "<unset>"}'; " +
                    "the build requires an explicit UTF-8 compiler encoding."
            )
        }
    }
}

val verifyEncoding = tasks.register("verifyEncoding") {
    group = "verification"
    description = "Fail when any javac task is not explicitly UTF-8."
    doLast {
        val offenders = tasks.withType(JavaCompile::class.java).mapNotNull { compile ->
            if (compile.options.encoding != "UTF-8") {
                "${compile.path} -> ${compile.options.encoding ?: "<unset>"}"
            } else {
                null
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "The compiler encoding must be UTF-8 everywhere; offenders: $offenders"
            )
        }
        val charset = Charset.defaultCharset().name()
        if (charset != "UTF-8") {
            throw GradleException(
                "The build JVM's default charset is $charset; the build requires UTF-8 " +
                    "(check JAVA_TOOL_OPTIONS / -Dfile.encoding)."
            )
        }
        println("verifyEncoding: every javac task is UTF-8; build JVM charset is UTF-8")
    }
}

tasks.named("check") {
    dependsOn(verifyEncoding)
}

// ---------------------------------------------------------------------------
// Tests. The suite runs single-forked, against the repository root, and is
// never cached: a run must describe the tree in front of it.
// ---------------------------------------------------------------------------
fun Test.testConfiguration() {
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("test.root", projectDir.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.named<Test>("test") {
    testConfiguration()
}

tasks.register<JavaExec>("manifestLock") {
    group = "assets"
    description = "Regenerate assets/manifest.lock.json from the manifest."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("rabbitrace.assets.ManifestLock")
    args = listOf(file("assets").absolutePath)
}

tasks.register<JavaExec>("placeholderKit") {
    group = "assets"
    description = "Regenerate assets/placeholders/ from the manifest."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("rabbitrace.assets.PlaceholderKit")
    args = listOf(file("assets").absolutePath)
}
