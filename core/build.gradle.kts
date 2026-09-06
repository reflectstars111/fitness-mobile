plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.register<JavaExec>("regressionTest") {
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("fitness.mobile.core.CoreTest")
    args(file("src/test/fixtures/geometry.tsv").absolutePath)
}
tasks.named("check") { dependsOn("regressionTest") }
