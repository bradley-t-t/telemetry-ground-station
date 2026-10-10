plugins {
    id("telemetry.java-conventions")
}

dependencies {
    implementation(project(":protocol"))
    testImplementation(project(":vehicle"))
}
