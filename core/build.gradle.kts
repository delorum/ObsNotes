plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies { implementation("org.bouncycastle:bcprov-jdk18on:1.86"); implementation("org.jsoup:jsoup:1.24.1"); testImplementation("junit:junit:4.13.2") }
tasks.test {
    useJUnit()
    jvmArgs("--add-modules", "jdk.httpserver")
    testLogging { events("passed", "skipped", "failed") }
}
