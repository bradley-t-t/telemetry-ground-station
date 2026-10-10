plugins {
    id("telemetry.java-conventions")
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":vehicle"))
    implementation(project(":ground"))
}

application {
    mainClass = "telemetry.app.Main"
    applicationName = "groundstation"
}

tasks.named<JavaExec>("run") {
    args("demo")
    standardInput = System.`in`
}

// One runnable jar. The project has no third-party runtime dependencies, so this is
// only the four modules' classes and resources.
tasks.jar {
    archiveFileName = "groundstation.jar"
    manifest {
        attributes["Main-Class"] = "telemetry.app.Main"
    }
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
